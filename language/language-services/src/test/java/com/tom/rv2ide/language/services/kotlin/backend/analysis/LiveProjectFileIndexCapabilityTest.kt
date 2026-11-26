package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar.LiveProjectFileIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.models.ProjectRootDescriptor
import com.tom.rv2ide.projects.models.ProjectRootOrigin
import com.tom.rv2ide.projects.models.ProjectRootRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class LiveProjectFileIndexCapabilityTest {
  @Test
  fun indexedRootsPreserveSourceArchiveAndOverlayMembership() {
    withWorkspace {
      val directory = Files.createTempDirectory("acs-root-index")
      val jars = CoreJarFileSystem()
      try {
        val content = directory.resolve("project")
        val source = content.resolve("src")
        val generated = source.resolve("generated")
        val output = content.resolve("build/classes")
        val archive = directory.resolve("libraries/api.jar")
        val adjacentArchive = archive.resolveSibling("api.jar2")
        val localSource = source.resolve("pkg/Subject.java")
        val localGenerated = generated.resolve("pkg/Generated.java")
        val sourceSibling = content.resolve("src-extra/Outside.java")
        val outputFile = output.resolve("pkg/Output.class")
        for (file in listOf(localSource, localGenerated, sourceSibling, outputFile)) {
          Files.createDirectories(file.parent)
          Files.write(file, byteArrayOf())
        }
        Files.createDirectories(archive.parent)
        for (file in listOf(archive, adjacentArchive)) {
          ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for (name in listOf("pkg/Type.class", "pkg/Type.java", "other/Other.class")) {
              zip.putNextEntry(ZipEntry(name))
              zip.write(byteArrayOf(1))
              zip.closeEntry()
            }
          }
        }
        KotlinAnalysisTestSupport.prepareAnalysisPaths()
        runAnalysis {
          val environment = LiveAnalysisEnvironment(
              KotlinProjectClasspathProvider(workspace), KotlinAnalysisTestSupport.analysisApiJar(),
          )
          try {
            val started = environment.start()
            assertTrue(environment.startFailure?.stackTraceToString(), started)
            AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
              val template = requireNotNull(environment.projectContext.module(":app"))
              fun descriptor(path: Path, owner: String, role: ProjectRootRole) = ProjectRootDescriptor(
                  path = path, ownerModulePath = owner, role = role,
                  origin = ProjectRootOrigin.UNKNOWN, exists = true,
              )
              val parentModule = template.copy(
                  modulePath = ":parent", projectDirectory = content,
                  sourceRootDescriptors = listOf(descriptor(source, ":parent", ProjectRootRole.JAVA_SOURCE)),
                  sourceRoots = listOf(source), javaSourceRoots = listOf(source), generatedSourceRoots = emptyList(),
                  moduleOutputRoots = listOf(output), compileModulePaths = emptyList(), binaryRoots = emptyList(),
                  bootClasspaths = emptyList(), binaryRootContexts = emptyList(),
                  libraryAttachments = listOf(KotlinAnalysisLibraryAttachmentContext(
                      libraryIdentity = "api", binaryPaths = listOf(archive), sourcePaths = listOf(archive),
                      javadocPaths = emptyList(), ownerModulePath = ":parent",
                  )),
              )
              val nestedModule = parentModule.copy(
                  modulePath = ":nested", projectDirectory = generated,
                  sourceRootDescriptors = listOf(descriptor(generated, ":nested", ProjectRootRole.GENERATED_SOURCE)),
                  sourceRoots = listOf(generated), javaSourceRoots = emptyList(), generatedSourceRoots = listOf(generated),
                  moduleOutputRoots = emptyList(), libraryAttachments = emptyList(),
              )
              val registry = LiveSourceFileRegistry()
              fun createIndex(context: KotlinAnalysisProjectContext) = LiveProjectFileIndex(environment.project, context, registry)
              val context = KotlinAnalysisProjectContext(listOf(parentModule, nestedModule))
              val index = createIndex(context)
              fun local(path: Path): VirtualFile = requireNotNull(StandardFileSystems.local().findFileByPath(path.toString()))
              val ordinary = local(localSource)
              val nested = local(localGenerated)
              val outside = local(sourceSibling)
              assertTrue(index.isInSourceContent(ordinary))
              assertFalse(index.isInGeneratedSources(ordinary))
              assertEquals(":parent", requireNotNull(index.moduleForFile(ordinary)).name)
              assertTrue(index.isInGeneratedSources(nested))
              assertEquals(":nested", requireNotNull(index.moduleForFile(nested)).name)
              assertEquals(source.toString(), requireNotNull(index.getSourceRootForFile(ordinary)).path)
              assertEquals(generated.toString(), requireNotNull(index.getSourceRootForFile(nested)).path)
              assertEquals(content.toString(), requireNotNull(index.getContentRootForFile(ordinary)).path)
              assertEquals(generated.toString(), requireNotNull(index.getContentRootForFile(nested)).path)
              assertEquals("pkg", index.getPackageNameByDirectory(local(localSource.parent)))
              assertFalse(index.isInSourceContent(outside))
              assertTrue(index.isInContent(outside))
              val samePathRoots = createIndex(KotlinAnalysisProjectContext(listOf(parentModule.copy(
                  sourceRootDescriptors = listOf(
                      descriptor(source, ":nested", ProjectRootRole.GENERATED_SOURCE),
                      descriptor(source, ":parent", ProjectRootRole.JAVA_SOURCE),
                  ),
              ), nestedModule)))
              assertTrue(samePathRoots.isInGeneratedSources(ordinary))
              assertEquals(":nested", requireNotNull(samePathRoots.moduleForFile(ordinary)).name)
              val normalizedRoots = createIndex(KotlinAnalysisProjectContext(listOf(parentModule.copy(
                  sourceRootDescriptors = listOf(
                      descriptor(source, ":parent", ProjectRootRole.JAVA_SOURCE),
                      descriptor(source.resolve("alias/.."), ":nested", ProjectRootRole.GENERATED_SOURCE),
                  ),
              ), nestedModule)))
              assertTrue(normalizedRoots.isInGeneratedSources(ordinary))
              assertEquals(":nested", requireNotNull(normalizedRoots.moduleForFile(ordinary)).name)
              registry.register(sourceSibling, ordinary)
              assertTrue(index.isInSourceContent(ordinary))
              assertEquals(":parent", requireNotNull(index.moduleForFile(ordinary)).name)
              registry.unregister(sourceSibling)
              registry.register(localGenerated, ordinary)
              assertTrue(index.isInGeneratedSources(ordinary))
              assertEquals(":nested", requireNotNull(index.moduleForFile(ordinary)).name)
              registry.unregister(localGenerated)
              assertFalse(index.isInGeneratedSources(ordinary))
              val binaryOutput = local(outputFile)
              assertEquals(output.toString(), requireNotNull(index.getClassRootForFile(binaryOutput)).path)
              assertFalse(index.isInLibraryClasses(binaryOutput))
              val member = requireNotNull(jars.findFileByPath("$archive!/pkg/Type.class"))
              val jarRoot = requireNotNull(jars.findFileByPath("$archive!/"))
              assertTrue(index.isInLibraryClasses(member))
              assertTrue(index.isInLibrarySource(member))
              assertTrue(index.isInLibrary(member))
              assertTrue(index.isInProjectOrExcluded(member))
              assertSame(jarRoot, index.getClassRootForFile(member))
              assertFalse(index.isInLibraryClasses(requireNotNull(jars.findFileByPath("$adjacentArchive!/pkg/Type.class"))))
              assertNotNull(index.getOrderEntriesForFile(member).firstOrNull())
              val memberRoot = Path.of("$archive!/pkg")
              val membersOnly = createIndex(KotlinAnalysisProjectContext(listOf(parentModule.copy(
                  libraryAttachments = listOf(parentModule.libraryAttachments.single().copy(
                      binaryPaths = listOf(memberRoot), sourcePaths = listOf(memberRoot),
                  )),
              ))))
              assertTrue(membersOnly.isInLibraryClasses(member))
              assertFalse(membersOnly.isInLibraryClasses(requireNotNull(jars.findFileByPath("$archive!/other/Other.class"))))
              val withNestedLibraryRoot = createIndex(KotlinAnalysisProjectContext(listOf(parentModule.copy(
                  libraryAttachments = listOf(parentModule.libraryAttachments.single().copy(
                      binaryPaths = listOf(archive, memberRoot),
                  )),
              ))))
              assertEquals(memberRoot.toString(), requireNotNull(withNestedLibraryRoot.getClassRootForFile(member)).path)
              val overlay = LightVirtualFile("Detached.java", JavaFileType.INSTANCE, "class Detached {}")
              registry.register(localGenerated, overlay)
              assertTrue(index.isInSourceContent(overlay))
              assertTrue(index.isInGeneratedSources(overlay))
              assertEquals(":nested", requireNotNull(index.moduleForFile(overlay)).name)
              registry.unregister(localGenerated)
              registry.register(sourceSibling, overlay)
              assertFalse(index.isInSourceContent(overlay))
              assertEquals(":parent", requireNotNull(index.moduleForFile(overlay)).name)
              registry.unregister(sourceSibling)
              assertFalse(index.isInSourceContent(overlay))
              assertNull(index.moduleForFile(overlay))
              assertTrue(index.isInSourceContent(nested))
              val rebuilt = createIndex(KotlinAnalysisProjectContext(listOf(parentModule.copy(
                  sourceRootDescriptors = emptyList(), sourceRoots = emptyList(), javaSourceRoots = emptyList(),
              ))))
              assertFalse(rebuilt.isInSourceContent(ordinary))
              assertTrue(index.isInSourceContent(ordinary))
            }
          } finally {
            environment.close()
          }
        }
      } finally {
        jars.clearHandlersCache()
        Files.walk(directory).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
      }
    }
  }
}