package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnusedThrowsDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiRemoveUnusedThrowsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar.LiveJavaClassIdIndexer
import com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar.LiveFileBasedIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.backend.analysis.source.withJavaDiagnosticProgress
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileEditorManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition
import org.jetbrains.kotlin.com.intellij.openapi.module.Module
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndex
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndexer
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdTableBuilding
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.idCache.JavaIdIndexer
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.search.ProjectScope
import org.jetbrains.kotlin.com.intellij.psi.search.SearchRequestCollector
import org.jetbrains.kotlin.com.intellij.psi.search.SearchSession
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex.AllKeysQuery
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.concurrency.SensitiveProgressWrapper
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.StandardProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.WrappedProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiRemoveUnusedThrowsCapabilityTest {
  @Test
  fun sharedRedundantThrowsWarningGeneratesSnapshotBoundRemovalEdits() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiRemoveUnusedThrows.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiRemoveUnusedThrows {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.io.IOException;
          import java.sql.SQLException;
          class JavaPsiRemoveUnusedThrows {
            void redundant() throws IOException, SQLException {}
            void single() throws SQLException {}
            void actual() throws IOException { throw new IOException(); }
          }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      try {
        openDocument(path, content, version = 1, revision = 1101L)
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(path) }
          fun diagnostics(version: Int = 1, revision: Long = 1101L) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }
          fun generate(target: JavaUnusedThrowsDiagnostic) = runAnalysis {
            JavaPsiServiceInterop.removeUnusedThrows(
                JavaPsiRemoveUnusedThrowsParams(target, ICancelChecker.NOOP),
            )
          }

          val allDiagnostics = diagnostics()
          val warningItems = allDiagnostics.filter {
            it.code == "ide.java.unused.throws" && it.source == "java-inspection"
          }
          assertTrue(warningItems.isNotEmpty())
          assertTrue(warningItems.all { it.severity == DiagnosticSeverity.WARNING })
          val targets = warningItems.mapNotNull(JavaUnusedThrowsDiagnostic::from)
              .filter { it.methodName == "redundant" }
          assertEquals(2, targets.size)
          val singleTarget = requireNotNull(allDiagnostics.mapNotNull(JavaUnusedThrowsDiagnostic::from)
              .single { it.methodName == "single" })
          assertTrue(allDiagnostics.none {
            JavaUnusedThrowsDiagnostic.from(it)?.methodName == "actual"
          })

          val edits = targets.map { requireNotNull(generate(it)) }
          assertTrue(edits.all {
            it.file.normalize() == path.normalize() && it.documentVersion == 1 &&
                it.documentRevision == 1101L && it.newText.isEmpty()
          })
          val singleEdit = requireNotNull(generate(singleTarget))
          assertTrue(apply(content, singleEdit).contains("void single() {}"))
          val firstApplied = apply(content, edits.first())
          assertTrue(firstApplied.contains("void redundant() throws SQLException {}"))
          val secondApplied = apply(content, edits.last())
          assertTrue(secondApplied.contains("void redundant() throws IOException {}"))

          openDocument(path, content, version = 2, revision = 1102L)
          runAnalysis { runtime.onFileOpened(path) }
          assertNull(generate(targets.first()))
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  @Test
  fun publicRedundantThrowsUsesOfficialIndexersWithPluginDescriptors() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiPublicThrows.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; public class JavaPsiPublicThrows {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val probePath = path.resolveSibling("JavaPsiIdCacheProbe1501.txt")
      val previousProbe = if (Files.exists(probePath)) Files.readAllBytes(probePath) else null
      val probe = "class JavaPsiIdCacheProbe1501 { int firstProbe1501; }"
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          import java.io.IOException;
          public class JavaPsiPublicThrows {
            public void publicThrows1501() throws IOException {}
            public void caught() throws IOException {}
            public void actual() throws IOException { throw new IOException(); }
            void caller() {
              try { caught(); } catch (IOException expected) {}
            }
          }
      """.trimIndent()
      try {
        Files.write(path, disk.toByteArray())
        Files.write(probePath, probe.toByteArray())
        openDocument(path, content, version = 1, revision = 1501L)
        KotlinAnalysisTestSupport.prepareAnalysisPaths()
        runAnalysis {
          val environment = LiveAnalysisEnvironment(
              KotlinProjectClasspathProvider(workspace), KotlinAnalysisTestSupport.analysisApiJar(),
          )
          try {
            val started = environment.start()
            assertTrue(environment.startFailure?.stackTraceToString(), started)
            AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
              ApplicationManager.getApplication().runReadAction(Computable {
                environment.project.analysisRead {
                  val beans = ApplicationManager.getApplication().extensionArea
                      .getExtensionPoint<FileTypeExtensionPoint<IdIndexer>>("org.jetbrains.kotlin.com.intellij.idIndexer")
                      .extensionList
                  for (fileType in listOf(JavaFileType.INSTANCE, JavaClassFileType.INSTANCE)) {
                    val bean = beans.single { it.filetype == fileType.name }
                    assertEquals("com.intellij.java", bean.pluginDescriptor.pluginId.idString)
                    assertTrue(bean.pluginDescriptor.pluginClassLoader === JavaIdIndexer::class.java.classLoader)
                    val indexer = bean.instance
                    // CLASS uses the fixed indexer; JAVA keeps the official one.
                    if (fileType == JavaClassFileType.INSTANCE) {
                      assertTrue(indexer is LiveJavaClassIdIndexer)
                    } else {
                      assertTrue(indexer is JavaIdIndexer)
                    }
                    val resolvedIndexer = IdTableBuilding.getFileTypeIndexer(fileType)
                    assertTrue(resolvedIndexer === indexer)
                  }
                  val psiFile = requireNotNull(environment.javaFiles.currentFile(path, 1, 1501L))
                  val virtualFile = psiFile.viewProvider.virtualFile
                  val editors = FileEditorManager.getInstance(environment.project)
                  assertTrue(editors.project === environment.project)
                  assertTrue("active overlay missing from open editor files", editors.openFiles.any { it === virtualFile })
                  assertTrue(editors.openFilesWithRemotes.any { it === virtualFile })
                  assertTrue(editors.isFileOpen(virtualFile))
                  assertNull(editors.focusedEditor)
                  assertTrue(com.android.java.awt.Component::class.java.isAssignableFrom(com.android.javax.swing.JComponent::class.java))
                  val allScope = GlobalSearchScope.allScope(environment.project)
                  val librariesScope = ProjectScope.getLibrariesScope(environment.project)
                  val standardScopes = listOf(
                      allScope,
                      librariesScope,
                      ProjectScope.getProjectScope(environment.project),
                      ProjectScope.getContentScope(environment.project),
                      GlobalSearchScope.everythingScope(environment.project),
                  )
                  standardScopes.forEach { standardScope ->
                    assertTrue("standard scope lost project: $standardScope", standardScope.project === environment.project)
                  }
                  assertTrue(allScope === GlobalSearchScope.allScope(environment.project))
                  assertTrue(allScope.contains(virtualFile))
                  assertTrue(allScope.isSearchInLibraries())
                  assertTrue(!librariesScope.contains(virtualFile))
                  val method = psiFile.classes.single().methods.single { it.name == "publicThrows1501" }
                  val useScope = method.useScope as GlobalSearchScope
                  assertTrue("method use scope lost project: $useScope", useScope.project === environment.project)
                  val effectiveScope = ReferencesSearch.SearchParameters(method, allScope, false)
                      .effectiveSearchScope as GlobalSearchScope
                  assertTrue("reference scope lost project: $effectiveScope", effectiveScope.project === environment.project)
                  val requests = SearchRequestCollector(SearchSession(method))
                  requests.searchWord(method.name, effectiveScope, true, method)
                  val searchRequests = requests.takeSearchRequests()
                  assertTrue("official search did not enqueue a request", searchRequests.isNotEmpty())
                  assertTrue(searchRequests.all { (it.searchScope as GlobalSearchScope).project === environment.project })
                  val scope = GlobalSearchScope.fileScope(environment.project, virtualFile)
                  val index = FileBasedIndex.getInstance()
                  val present = IdIndexEntry("caught", true)
                  val absent = IdIndexEntry("acsMissingIdIndexProbe1501", true)
                  val data = index.getFileData(IdIndex.NAME, virtualFile, environment.project)
                  assertTrue("official IdIndex lost the positive control", data.containsKey(present))
                  assertTrue("missing-key probe collides with an indexed word", !data.containsKey(absent))
                  assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, present, scope).toSet())
                  assertTrue("absent word matched a file", index.getContainingFiles(IdIndex.NAME, absent, scope).isEmpty())
                  assertEquals(setOf(data.getValue(present)), index.getValues(IdIndex.NAME, present, scope).toSet())
                  assertTrue("absent word emitted a value", index.getValues(IdIndex.NAME, absent, scope).isEmpty())
                  val hits = ArrayList<VirtualFile>()
                  val completed = index.processFilesContainingAllKeys(
                      listOf(AllKeysQuery(IdIndex.NAME, listOf(present, absent), null)), scope,
                      Processor { hits.add(it); true },
                  )
                  assertTrue(completed)
                  assertTrue("multi-key query matched an absent word: $hits", hits.isEmpty())
                  assertTrue(index.processFilesContainingAnyKey(
                      IdIndex.NAME, listOf(absent, present), scope, null, null,
                      Processor { hits.add(it); true },
                  ))
                  assertEquals(setOf(virtualFile), hits.toSet())
                  val liveIndex = index as LiveFileBasedIndex
                  class CountingScope : GlobalSearchScope(environment.project) {
                    var visits = 0
                    override fun contains(file: VirtualFile): Boolean {
                      visits++
                      return file === virtualFile
                    }
                    override fun isSearchInModuleContent(module: Module): Boolean = true
                    override fun isSearchInLibraries(): Boolean = false
                  }
                  val queryScope = CountingScope()
                  val second = IdIndexEntry("publicThrows1501", true)
                  val stopped = IdIndexEntry("IOException", true)
                  val interrupted = IdIndexEntry("actual", true)
                  liveIndex.withIdQueryCache {
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, present, queryScope).toSet())
                    val fullScanVisits = queryScope.visits
                    assertTrue(fullScanVisits > 1)
                    queryScope.visits = 0
                    assertTrue(index.processFilesContainingAllKeys(
                        listOf(AllKeysQuery(IdIndex.NAME, listOf(present), Condition<Int> { false })),
                        queryScope, Processor { error("rejected mask emitted a file") },
                    ))
                    assertEquals(1, queryScope.visits)
                    queryScope.visits = 0
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, present, queryScope).toSet())
                    assertEquals(1, queryScope.visits)
                    queryScope.visits = 0
                    val combined = ArrayList<VirtualFile>()
                    assertTrue(index.processFilesContainingAllKeys(
                        listOf(
                            AllKeysQuery(IdIndex.NAME, listOf(present), Condition<Int> { it == data.getValue(present) }),
                            AllKeysQuery(IdIndex.NAME, listOf(second), null),
                        ),
                        queryScope, Processor { combined.add(it); true },
                    ))
                    assertEquals(setOf(virtualFile), combined.toSet())
                    assertEquals(1, queryScope.visits)
                    assertEquals(setOf(data.getValue(present)), index.getValues(IdIndex.NAME, present, queryScope).toSet())
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, second, queryScope).toSet())
                    queryScope.visits = 0
                    val allCached = ArrayList<VirtualFile>()
                    assertTrue(index.processFilesContainingAllKeys(
                        IdIndex.NAME, listOf(present, second), queryScope, null,
                        Processor { allCached.add(it); true },
                    ))
                    assertEquals(setOf(virtualFile), allCached.toSet())
                    assertEquals(1, queryScope.visits)
                    val otherScope = CountingScope()
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, present, otherScope).toSet())
                    assertEquals(fullScanVisits, otherScope.visits)
                    val rejectedFirstScope = CountingScope()
                    assertTrue(index.processFilesContainingAllKeys(
                        IdIndex.NAME, listOf(present), rejectedFirstScope, Condition<Int> { false },
                        Processor { error("rejected first query emitted a file") },
                    ))
                    rejectedFirstScope.visits = 0
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, present, rejectedFirstScope).toSet())
                    assertEquals(1, rejectedFirstScope.visits)
                    queryScope.visits = 0
                    assertTrue(index.getContainingFiles(IdIndex.NAME, absent, queryScope).isEmpty())
                    assertEquals(fullScanVisits, queryScope.visits)
                    queryScope.visits = 0
                    assertTrue(index.getContainingFiles(IdIndex.NAME, absent, queryScope).isEmpty())
                    assertEquals(0, queryScope.visits)
                    val any = ArrayList<VirtualFile>()
                    assertTrue(index.processFilesContainingAnyKey(
                        IdIndex.NAME, listOf(absent, present), queryScope, null, null,
                        Processor { any.add(it); true },
                    ))
                    assertEquals(setOf(virtualFile), any.toSet())
                    assertEquals(1, queryScope.visits)
                    assertTrue(!index.processFilesContainingAllKeys(
                        IdIndex.NAME, listOf(stopped), queryScope, null, Processor { false },
                    ))
                    queryScope.visits = 0
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, stopped, queryScope).toSet())
                    assertEquals(fullScanVisits, queryScope.visits)
                    var queryCancelled = false
                    try {
                      index.processFilesContainingAllKeys(
                          IdIndex.NAME, listOf(interrupted), queryScope, null,
                          Processor { throw CancellationException("cancel query cache probe") },
                      )
                    } catch (_: CancellationException) {
                      queryCancelled = true
                    }
                    assertTrue(queryCancelled)
                    queryScope.visits = 0
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, interrupted, queryScope).toSet())
                    assertEquals(fullScanVisits, queryScope.visits)
                    index.requestReindex(virtualFile)
                    queryScope.visits = 0
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, present, queryScope).toSet())
                    assertEquals(fullScanVisits, queryScope.visits)
                  }
                  queryScope.visits = 0
                  liveIndex.withIdQueryCache {
                    assertEquals(setOf(virtualFile), index.getContainingFiles(IdIndex.NAME, present, queryScope).toSet())
                  }
                  assertTrue(queryScope.visits > 1)
                }
              })
            }
            val diagnostics = requireNotNull(
                environment.javaDiagnosticsResolver.diagnostics(path, ICancelChecker.NOOP, 1, 1501L),
            )
            val targets = diagnostics.mapNotNull(JavaUnusedThrowsDiagnostic::from)
            val target = targets.single { it.methodName == "publicThrows1501" }
            assertEquals(path.normalize(), target.file)
            assertEquals(1, target.documentVersion)
            assertEquals(1501L, target.documentRevision)
            assertEquals("java.io.IOException", target.exceptionQualifiedName)
            assertEquals("IOException", content.substring(target.referenceStart, target.referenceEnd))
            assertTrue(targets.none { it.methodName == "caught" || it.methodName == "actual" })
            assertEquals(disk, Files.readAllBytes(path).decodeToString())
            AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
              val manager = ProgressManager.getInstance()
              val outer = EmptyProgressIndicator()
              manager.runProcess(Runnable {
                val checker = ICancelChecker.Default()
                var cancelled = false
                try {
                  withJavaDiagnosticProgress(checker) {
                    val installed = requireNotNull(manager.progressIndicator)
                    assertTrue(installed !== outer)
                    assertTrue(installed is StandardProgressIndicator)
                    val cancellation = (installed as WrappedProgressIndicator).originalProgressIndicator
                    assertTrue(cancellation !is StandardProgressIndicator)
                    val wrapper = SensitiveProgressWrapper(installed)
                    assertSame(installed, wrapper.originalProgressIndicator)
                    wrapper.checkCanceled()
                    manager.runProcess(Runnable {
                      assertSame(wrapper, manager.progressIndicator)
                      checker.cancel()
                      val deadline = System.nanoTime() + 5_000_000_000L
                      while (System.nanoTime() < deadline) {
                        ProgressManager.checkCanceled()
                        Thread.sleep(1L)
                      }
                    }, wrapper)
                  }
                } catch (_: ProcessCanceledException) {
                  cancelled = true
                }
                assertTrue("official indicator ignored host cancellation", cancelled)
                assertSame(outer, manager.progressIndicator)
              }, outer)
              assertNull(manager.progressIndicator)
            }
            fun <T> readIndex(action: () -> T): T = AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
              ApplicationManager.getApplication().runReadAction(Computable { environment.project.analysisRead(action) })
            }
            fun probeData() = readIndex {
              val file = requireNotNull(StandardFileSystems.local().findFileByPath(probePath.toString()))
              FileBasedIndex.getInstance().getFileData(IdIndex.NAME, file, environment.project)
            }
            val libraryFile = readIndex {
              val active = requireNotNull(environment.javaFiles.currentFile(path, 1, 1501L))
              val method = active.classes.single().methods.single { it.name == "publicThrows1501" }
              requireNotNull(method.throwsList.referencedTypes.single().resolve()).containingFile.viewProvider.virtualFile
            }
            val libraryData = readIndex {
              FileBasedIndex.getInstance().getFileData(IdIndex.NAME, libraryFile, environment.project)
            }
            assertTrue("exception library file was not indexed", libraryData.isNotEmpty())
            val originalData = probeData()
            assertTrue(originalData.containsKey(IdIndexEntry("firstProbe1501", true)))
            assertSame(originalData, probeData())
            val originalTime = Files.getLastModifiedTime(probePath)
            val changedContent = content.replace("caught", "caughtAfter1502")
            openDocument(path, "$changedContent\n", version = 2, revision = 1502L)
            environment.javaFiles.refresh(path)
            assertSame("unrelated overlay edit invalidated disk IdIndex", originalData, probeData())
            readIndex {
              assertSame("unrelated overlay edit invalidated library IdIndex", libraryData,
                  FileBasedIndex.getInstance().getFileData(IdIndex.NAME, libraryFile, environment.project))
            }
            val changedProbe = probe.replace("firstProbe1501", "otherProbe1501")
            Files.write(probePath, changedProbe.toByteArray())
            Files.setLastModifiedTime(probePath, FileTime.fromMillis(originalTime.toMillis() + 2000L))
            val changedData = probeData()
            assertTrue(changedData.containsKey(IdIndexEntry("otherProbe1501", true)))
            assertTrue(!changedData.containsKey(IdIndexEntry("firstProbe1501", true)))
            Files.write(probePath, probe.toByteArray())
            Files.setLastModifiedTime(probePath, FileTime.fromMillis(originalTime.toMillis() + 4000L))
            assertTrue(probeData().containsKey(IdIndexEntry("firstProbe1501", true)))
            readIndex {
              val index = FileBasedIndex.getInstance()
              val active = requireNotNull(environment.javaFiles.currentFile(path, 2, 1502L))
              val activeData = index.getFileData(IdIndex.NAME, active.viewProvider.virtualFile, environment.project)
              assertTrue(activeData.containsKey(IdIndexEntry("caughtAfter1502", true)))
              assertTrue(!activeData.containsKey(IdIndexEntry("caught", true)))
            }
            val cancelledChecker = object : ICancelChecker.Default() {
              override fun isCancelled(): Boolean {
                if (ProgressManager.getInstance().progressIndicator != null) cancel()
                return super.isCancelled()
              }
            }
            var diagnosticsCancelled = false
            try {
              environment.javaDiagnosticsResolver.diagnostics(path, cancelledChecker, 2, 1502L)
            } catch (_: CancellationException) {
              diagnosticsCancelled = true
            }
            assertTrue("diagnostics swallowed internal platform cancellation", diagnosticsCancelled)
            assertNull(ProgressManager.getInstance().progressIndicator)
            val afterCancellation = requireNotNull(
                environment.javaDiagnosticsResolver.diagnostics(path, ICancelChecker.Default(), 2, 1502L),
            )
            assertTrue(afterCancellation.mapNotNull(JavaUnusedThrowsDiagnostic::from).any { it.methodName == "publicThrows1501" })
            openDocument(path, "$changedContent\n\n", version = 3, revision = 1503L)
            var staleCancelled = false
            try {
              environment.javaDiagnosticsResolver.diagnostics(path, ICancelChecker.Default(), 2, 1502L)
            } catch (_: CancellationException) {
              staleCancelled = true
            }
            assertTrue("old PSI revision was not cancelled after a document edit", staleCancelled)
            closeDocument(path)
            environment.javaFiles.refresh(path)
            AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
              ApplicationManager.getApplication().runReadAction(Computable {
                environment.project.analysisRead {
                  val editors = FileEditorManager.getInstance(environment.project)
                  assertTrue(editors.openFiles.none { it.name == path.fileName.toString() })
                }
              })
            }
          } finally {
            environment.close()
          }
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
        if (previousProbe == null) Files.deleteIfExists(probePath) else Files.write(probePath, previousProbe)
      }
    }
  }

  private fun apply(content: String, edit: JavaPsiTextEdit): String {
    val index = LineIndex.from(content)
    val start = index.lineColumnToIndex(edit.startLine, edit.startColumn)
    val end = index.lineColumnToIndex(edit.endLine, edit.endColumn)
    return content.replaceRange(start, end, edit.newText)
  }
}