package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.java.diagnostics.JavaDiagnosticsInterop
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnusedSymbolDiagnostic
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import org.jetbrains.kotlin.com.intellij.codeInsight.MetaAnnotationUtil
import org.jetbrains.kotlin.com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.profile.codeInspection.InspectionProjectProfileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.uast.UastModificationTracker
import org.jetbrains.uast.UastLanguagePlugin
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaPsiUnusedSymbolCapabilityTest {
  @Test
  fun officialLocalUnusedDiagnosticsPreserveSuppressionRangesAndDocumentIdentity() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiUnusedSymbol.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class JavaPsiUnusedSymbol {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaPsiUnusedSymbol {
            void localUsage() {
              int deadLocal = 4;
              int writeOnlyLocal = 5;
              writeOnlyLocal = 6;
              int liveLocal = 7;
              @SuppressWarnings("unused") int suppressedLocal = 8;
              //noinspection unused
              int commentSuppressedLocal = 9;
              class LocalType {}
              class HiddenType {}
              System.out.println(liveLocal);
            }
            public static class PublicType {}
          }
      """.trimIndent()
      Files.write(path, disk.toByteArray())
      try {
        openDocument(path, content, version = 1, revision = 1301L)
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(path) }
          fun diagnostics(version: Int = 1, revision: Long = 1301L) = runAnalysis {
            when (val result = JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, version, revision)) {
              is JavaDiagnosticsInterop.Result.Success -> result.diagnostics
              JavaDiagnosticsInterop.Result.Unavailable -> error("shared Java diagnostics unavailable")
              is JavaDiagnosticsInterop.Result.Failure -> throw result.error
            }
          }
          val items = diagnostics().filter { it.code == JavaUnusedSymbolDiagnostic.CODE }
          assertTrue(items.isNotEmpty())
          assertTrue(items.all { it.source == JavaUnusedSymbolDiagnostic.SOURCE && it.severity == DiagnosticSeverity.WARNING })
          val targets = items.mapNotNull(JavaUnusedSymbolDiagnostic::from)
          assertEquals(items.size, targets.size)
          val names = targets.map { it.name }.toSet()
          assertTrue(names.toString(), names.containsAll(setOf("deadLocal", "writeOnlyLocal", "LocalType", "HiddenType")))
          assertFalse(names.toString(), names.any { it in setOf(
              "liveLocal", "suppressedLocal", "commentSuppressedLocal", "PublicType", "JavaPsiUnusedSymbol",
          ) })
          targets.forEach { target ->
            assertEquals(path.normalize(), target.file)
            assertEquals(1, target.documentVersion)
            assertEquals(1301L, target.documentRevision)
            assertEquals(target.name, content.substring(target.highlightStart, target.highlightEnd))
            assertTrue(target.declarationStart <= target.highlightStart && target.declarationEnd >= target.highlightEnd)
          }
          assertEquals(JavaUnusedSymbolDiagnostic.DeclarationKind.LOCAL_VARIABLE, targets.first { it.name == "deadLocal" }.declarationKind)
          assertEquals(JavaUnusedSymbolDiagnostic.DeclarationKind.LOCAL_VARIABLE, targets.first { it.name == "writeOnlyLocal" }.declarationKind)
          assertEquals(JavaUnusedSymbolDiagnostic.DeclarationKind.CLASS, targets.single { it.name == "LocalType" }.declarationKind)
          assertEquals(JavaUnusedSymbolDiagnostic.DeclarationKind.CLASS, targets.single { it.name == "HiddenType" }.declarationKind)
          assertTrue(targets.all { it.declarationKind in setOf(
              JavaUnusedSymbolDiagnostic.DeclarationKind.LOCAL_VARIABLE,
              JavaUnusedSymbolDiagnostic.DeclarationKind.CLASS,
          ) })

          assertEquals(JavaDiagnosticsInterop.Result.Unavailable, runAnalysis {
            JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, 0, 1301L)
          })
          assertEquals(JavaDiagnosticsInterop.Result.Unavailable, runAnalysis {
            JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, 1, 1300L)
          })
          assertThrows(CancellationException::class.java) {
            JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.CANCELLED, 1, 1301L)
          }

          val updated = content.replace("System.out.println(liveLocal);", "System.out.println(liveLocal + deadLocal);")
          openDocument(path, updated, version = 2, revision = 1302L)
          runAnalysis { runtime.onFileOpened(path) }
          val refreshed = diagnostics(2, 1302L).mapNotNull(JavaUnusedSymbolDiagnostic::from)
          assertTrue(refreshed.isNotEmpty())
          assertTrue(refreshed.none { it.name == "deadLocal" })
          assertTrue(refreshed.all { it.documentVersion == 2 && it.documentRevision == 1302L })
          assertEquals(JavaDiagnosticsInterop.Result.Unavailable, runAnalysis {
            JavaDiagnosticsInterop.diagnostics(path, ICancelChecker.NOOP, 1, 1301L)
          })
          assertEquals(disk, Files.readAllBytes(path).decodeToString())
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  @Test
  fun officialPrivateUnusedDiagnosticsTrackMembersParametersAndOverlayChanges() {
    withWorkspace {
      val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiPrivateUnused.java")
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; public class JavaPsiPrivateUnused {}"
      val previousDisk = if (Files.exists(path)) Files.readAllBytes(path) else null
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          public class JavaPsiPrivateUnused implements java.io.Serializable {
            private static final long serialVersionUID = 1L;
            private int deadField = 1;
            private int writeOnlyField;
            private int readOnlyField;
            private int liveField = 2;
            @SuppressWarnings("unused") private int suppressedField = 3;
            public int externalField;
            protected int inheritedField;
            int packageField;
            private static class DeadNested {}
            private static class LiveNested {}
            @SuppressWarnings("unused") private static class SuppressedNested {}
            private static class ConstructorOwner {
              private ConstructorOwner() {}
              private ConstructorOwner(int usedCtorParameter) { System.out.println(usedCtorParameter); }
            }
            private <UnusedType> void deadMethod() {}
            private void liveMethod(int deadParameter, int liveParameter) {
              System.out.println(liveParameter + liveField + readOnlyField);
              writeOnlyField = 3;
            }
            private void recursive(int recursiveParameter, int readParameter) {
              if (readParameter > 0) recursive(recursiveParameter, readParameter - 1);
            }
            @SuppressWarnings("unused") private void suppressedMethod(int suppressedParameter) {}
            public void api(int publicParameter) {}
            protected void inherited(int protectedParameter) {}
            void packageApi(int packageParameter) {}
            public void caller() {
              liveMethod(1, 2);
              recursive(1, 2);
              new LiveNested();
              new ConstructorOwner(1);
            }
            private void readObject(java.io.ObjectInputStream stream)
                throws java.io.IOException, ClassNotFoundException {
              stream.defaultReadObject();
            }
          }
      """.trimIndent()
      try {
        Files.write(path, disk.toByteArray())
        openDocument(path, content, version = 1, revision = 1601L)
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
                  val file = requireNotNull(environment.javaFiles.currentFile(path, 1, 1601L))
                  val profile = InspectionProjectProfileManager.getInstance(environment.project).currentProfile
                  val inspection = profile.getUnwrappedTool(UnusedDeclarationInspectionBase.SHORT_NAME, file)
                      as UnusedDeclarationInspectionBase
                  assertFalse(inspection.isGlobalEnabledInEditor)
                  val local = inspection.sharedLocalInspectionTool
                  assertTrue(local.LOCAL_VARIABLE && local.CLASS && local.FIELD && local.METHOD && local.PARAMETER)
                  assertEquals(PsiModifier.PRIVATE, local.parameterVisibility)
                }
              })
            }
            fun diagnostics(version: Int = 1, revision: Long = 1601L) = requireNotNull(
                environment.javaDiagnosticsResolver.diagnostics(path, ICancelChecker.NOOP, version, revision),
            ).filter { it.code == JavaUnusedSymbolDiagnostic.CODE }
            val items = diagnostics()
            val targets = items.map { requireNotNull(JavaUnusedSymbolDiagnostic.from(it)) }
            val expected = mapOf(
                "deadField" to JavaUnusedSymbolDiagnostic.DeclarationKind.FIELD,
                "writeOnlyField" to JavaUnusedSymbolDiagnostic.DeclarationKind.FIELD,
                "readOnlyField" to JavaUnusedSymbolDiagnostic.DeclarationKind.FIELD,
                "DeadNested" to JavaUnusedSymbolDiagnostic.DeclarationKind.CLASS,
                "ConstructorOwner" to JavaUnusedSymbolDiagnostic.DeclarationKind.CONSTRUCTOR,
                "deadMethod" to JavaUnusedSymbolDiagnostic.DeclarationKind.METHOD,
                "UnusedType" to JavaUnusedSymbolDiagnostic.DeclarationKind.TYPE_PARAMETER,
                "deadParameter" to JavaUnusedSymbolDiagnostic.DeclarationKind.PARAMETER,
                "recursiveParameter" to JavaUnusedSymbolDiagnostic.DeclarationKind.PARAMETER,
            )
            assertEquals(expected, targets.associate { it.name to it.declarationKind })
            assertEquals(expected.size, targets.size)
            assertTrue(items.all { it.source == JavaUnusedSymbolDiagnostic.SOURCE && it.severity == DiagnosticSeverity.WARNING })
            targets.forEach { target ->
              assertEquals(path.normalize(), target.file)
              assertEquals(1, target.documentVersion)
              assertEquals(1601L, target.documentRevision)
              assertEquals(target.name, content.substring(target.highlightStart, target.highlightEnd))
              assertTrue(target.declarationStart <= target.highlightStart && target.declarationEnd >= target.highlightEnd)
            }
            val updated = content.replace("liveMethod(1, 2);", "liveMethod(1, 2); System.out.println(deadField); deadMethod();")
            openDocument(path, updated, version = 2, revision = 1602L)
            environment.javaFiles.refresh(path)
            val refreshed = diagnostics(2, 1602L).map { requireNotNull(JavaUnusedSymbolDiagnostic.from(it)) }
            assertEquals(expected.keys - setOf("deadField", "deadMethod"), refreshed.map { it.name }.toSet())
            assertTrue(refreshed.all { it.documentVersion == 2 && it.documentRevision == 1602L })
            assertEquals(null, environment.javaDiagnosticsResolver.diagnostics(path, ICancelChecker.NOOP, 1, 1601L))
            assertEquals(disk, Files.readAllBytes(path).decodeToString())
          } finally {
            environment.close()
          }
        }
      } finally {
        closeDocument(path)
        if (previousDisk == null) Files.deleteIfExists(path) else Files.write(path, previousDisk)
      }
    }
  }

  @Test
  fun officialMetaAnnotationCacheTracksJavaOverlayChanges() {
    withWorkspace {
      val prefix = "com.tom.rv2ide.testing.kotlinfixture.app"
      val annotationsPath = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiMetaAnnotations.java")
      val usagePath = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiMetaUsage.java")
      val paths = listOf(annotationsPath, usagePath)
      val previousDisk = paths.associateWith { if (Files.exists(it)) Files.readAllBytes(it) else null }
      val annotations = "package $prefix; @interface MetaRoot {} @MetaRoot @interface MetaBridge {}"
      val usage = "package $prefix; @MetaBridge @interface MetaLeaf {} class JavaPsiMetaUsage { @MetaLeaf Object value; }"
      try {
        Files.write(annotationsPath, "package $prefix; @interface MetaRoot {} @interface MetaBridge {}".toByteArray())
        Files.write(usagePath, "package $prefix; class JavaPsiMetaUsage {}".toByteArray())
        openDocument(annotationsPath, annotations, version = 1, revision = 1401L)
        openDocument(usagePath, usage, version = 1, revision = 1401L)
        KotlinAnalysisTestSupport.prepareAnalysisPaths()
        runAnalysis {
          val environment = LiveAnalysisEnvironment(
              KotlinProjectClasspathProvider(workspace), KotlinAnalysisTestSupport.analysisApiJar(),
          )
          try {
            val started = environment.start()
            assertTrue(environment.startFailure?.stackTraceToString(), started)
            fun <T> read(action: () -> T): T = AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
              ApplicationManager.getApplication().runReadAction(Computable {
                environment.project.analysisRead(action)
              })
            }
            val tracker = read { UastModificationTracker.getInstance(environment.project) }
            read {
              assertTrue(UastLanguagePlugin.EP.extensionList.any { it.language === JavaLanguage.INSTANCE })
            }
            val originalUsage = read { requireNotNull(environment.javaFiles.currentFile(usagePath, 1, 1401L)) }
            fun verify(expected: Boolean) = read {
              val file = requireNotNull(environment.javaFiles.currentFile(usagePath, 1, 1401L))
              assertTrue(originalUsage === file)
              val field = file.classes.single { it.name == "JavaPsiMetaUsage" }.fields.single()
              assertEquals(expected, MetaAnnotationUtil.isMetaAnnotated(field, listOf("$prefix.MetaRoot")))
            }
            verify(true)
            val before = read { tracker.modificationCount }
            environment.javaFiles.refresh(annotationsPath)
            assertEquals(before, read { tracker.modificationCount })
            openDocument(annotationsPath, annotations.replace("@MetaRoot @interface", "@interface"), version = 2, revision = 1402L)
            environment.javaFiles.refresh(annotationsPath)
            val changed = read { tracker.modificationCount }
            assertTrue(changed > before)
            verify(false)
            openDocument(annotationsPath, annotations, version = 3, revision = 1403L)
            environment.javaFiles.refresh(annotationsPath)
            val restored = read { tracker.modificationCount }
            assertTrue(restored > changed)
            verify(true)
            environment.invalidateProjectFiles(annotationsPath)
            val invalidated = read { tracker.modificationCount }
            assertTrue(invalidated > restored)
            verify(true)
            closeDocument(annotationsPath)
            environment.javaFiles.refresh(annotationsPath)
            assertTrue(read { tracker.modificationCount } > invalidated)
            verify(false)
            assertEquals(usage, read { originalUsage.text })
          } finally {
            environment.close()
          }
        }
      } finally {
        paths.forEach { path ->
          closeDocument(path)
          val previous = previousDisk[path]
          if (previous == null) Files.deleteIfExists(path) else Files.write(path, previous)
        }
      }
    }
  }

  @Test
  fun unusedPayloadDoesNotAcceptLegacyDeletionCodesOrInvalidRanges() {
    val path = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiUnusedSymbol.java")
    val target = JavaUnusedSymbolDiagnostic(
        path, 1, 1301L, 12, 16, 0, 20, "dead", JavaUnusedSymbolDiagnostic.DeclarationKind.FIELD,
    )
    fun item(code: String = JavaUnusedSymbolDiagnostic.CODE, payload: JavaUnusedSymbolDiagnostic = target) = DiagnosticItem(
        message = "unused",
        code = code,
        range = Range(),
        source = JavaUnusedSymbolDiagnostic.SOURCE,
        severity = DiagnosticSeverity.WARNING,
    ).also { it.extra = payload }
    assertEquals(target, JavaUnusedSymbolDiagnostic.from(item()))
    assertEquals(null, JavaUnusedSymbolDiagnostic.from(item("ide.java.unused.field")))
    assertEquals(null, JavaUnusedSymbolDiagnostic.from(item(payload = target.copy(highlightStart = -1))))
    assertEquals(null, JavaUnusedSymbolDiagnostic.from(item(payload = target.copy(highlightEnd = 21))))
    assertEquals(null, JavaUnusedSymbolDiagnostic.from(item(payload = target.copy(documentRevision = -1L))))
  }
}
