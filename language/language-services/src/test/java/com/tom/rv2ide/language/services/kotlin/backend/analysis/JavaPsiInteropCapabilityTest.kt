package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.language.services.java.compiler.JavaCompilerService
import com.tom.rv2ide.language.services.java.interop.JavaPsiDefinitionTarget
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.providers.JavaSelectionProvider
import com.tom.rv2ide.language.services.java.providers.JavaHoverProvider
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.interop.KotlinSourceResolver
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.preferences.internal.JavaPreferences
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.load.java.JavaClassFinder
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
@OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)
class JavaPsiInteropCapabilityTest {

  @Test
  fun javaSelectionExpansionUsesTheActivePsiFile() {
    KotlinAnalysisTestSupport.withWorkspace {
      val path = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/Selection.java",
      )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          class Selection {
            void run() {
              try {
                call();
              } finally {
                done();
              }
            }
          }
      """.trimIndent()
      fun rangeAt(start: Int, end: Int): com.tom.rv2ide.models.Range {
        val index = com.tom.rv2ide.lsp.models.LineIndex.from(content)
        val startPosition = index.indexToPosition(start).apply { this.index = -1 }
        val endPosition = index.indexToPosition(end).apply { this.index = -1 }
        return com.tom.rv2ide.models.Range(startPosition, endPosition)
      }
      fun assertRangeEquals(expected: com.tom.rv2ide.models.Range, actual: com.tom.rv2ide.models.Range) {
        assertEquals(expected.start.line, actual.start.line)
        assertEquals(expected.start.column, actual.start.column)
        assertEquals(expected.end.line, actual.end.line)
        assertEquals(expected.end.column, actual.end.column)
      }
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; class Selection {}"
      Files.write(path, disk.toByteArray(Charsets.UTF_8))
      try {
        openDocument(path, content, version = 1, revision = 81L)
        KotlinAnalysisTestSupport.withRuntime(workspace) { runtime ->
          KotlinAnalysisTestSupport.runAnalysis { runtime.onFileOpened(path) }
          fun expand(
              start: Int,
              end: Int,
              version: Int = 1,
              revision: Long = 81L,
          ): com.tom.rv2ide.models.Range =
              KotlinAnalysisTestSupport.runAnalysis {
                JavaSelectionProvider().expandSelection(
                    ExpandSelectionParams(path, rangeAt(start, end)).apply {
                      documentVersion = version
                      documentRevision = revision
                    },
                )
              }

          val callStart = content.indexOf("call()")
          val statementStart = content.lastIndexOf("call();", callStart)
          assertRangeEquals(
              rangeAt(statementStart, statementStart + "call();".length),
              expand(callStart, callStart + "call()".length),
          )

          val methodStart = content.indexOf("void run()")
          val methodBodyStart = content.indexOf('{', methodStart)
          val methodEnd = content.indexOf("\n  }", methodBodyStart) + "\n  }".length
          assertRangeEquals(
              rangeAt(methodStart, methodEnd),
              expand(methodBodyStart, methodEnd),
          )

          val tryStart = content.indexOf("try {")
          val tryBlockStart = content.indexOf('{', tryStart)
          val finallyStart = content.indexOf("finally", tryStart)
          val tryBlockEnd = content.lastIndexOf('}', finallyStart) + 1
          val finallyBlockStart = content.indexOf('{', finallyStart)
          val finallyBlockEnd = content.indexOf('}', finallyBlockStart) + 1
          assertRangeEquals(
              rangeAt(tryStart, finallyBlockEnd),
              expand(tryBlockStart, tryBlockEnd),
          )
          assertRangeEquals(
              rangeAt(tryStart, finallyBlockEnd),
              expand(finallyBlockStart, finallyBlockEnd),
          )

          val classStart = content.indexOf("class Selection")
          val classEnd = content.lastIndexOf('}')
          assertRangeEquals(rangeAt(0, content.length), expand(classStart, classEnd + 1))
          val packageEnd = content.indexOf(';') + 1
          assertRangeEquals(rangeAt(0, content.length), expand(0, packageEnd))
          assertRangeEquals(rangeAt(0, content.length), expand(0, content.length))
          assertRangeEquals(
              rangeAt(callStart, callStart + "call()".length),
              expand(callStart, callStart + "call()".length, version = 0),
          )
          assertRangeEquals(
              rangeAt(callStart, callStart + "call()".length),
              expand(callStart, callStart + "call()".length, revision = 80L),
          )
          val cancelled = ICancelChecker.Default().apply { cancel() }
          var cancellationPropagated = false
          try {
            JavaSelectionProvider().expandSelection(
                ExpandSelectionParams(path, rangeAt(callStart, callStart + "call()".length), cancelled),
            )
          } catch (_: java.util.concurrent.CancellationException) {
            cancellationPropagated = true
          }
          assertTrue("Selection cancellation must propagate", cancellationPropagated)
          assertEquals(disk, String(Files.readAllBytes(path), Charsets.UTF_8))
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

  @Test
  fun javaDefinitionResolvesActiveJavaPsiAndRejectsStaleDocumentStamp() {
    KotlinAnalysisTestSupport.withWorkspace {
      val path = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val content = path.toFile().readText() + """

          class JavaCaller {
            String call() { return new JavaInterop().value("x"); }
          }
      """.trimIndent()
      openDocument(path, content, version = 4, revision = 91L)
      try {
        KotlinAnalysisTestSupport.withRuntime(workspace) {
          val callOffset = content.indexOf("value(\"x\")")
          val position = com.tom.rv2ide.lsp.models.LineIndex.from(content)
              .indexToPosition(callOffset)
          val params = DefinitionParams(path, position, ICancelChecker.NOOP).apply {
            documentVersion = 4
            documentRevision = 91L
          }
          val target = requireNotNull(
              KotlinAnalysisTestSupport.runAnalysis { JavaPsiServiceInterop.definition(params) },
          )
          assertEquals(JavaPsiDefinitionTarget.Kind.JAVA_SOURCE, target.kind)
          val location = target.locations.single()
          assertEquals(path, location.file)
          assertEquals(
              com.tom.rv2ide.lsp.models.LineIndex.from(content).indexToPosition(content.indexOf("value(String")),
              location.range.start,
          )
          params.documentRevision = 90L
          assertNull(KotlinAnalysisTestSupport.runAnalysis { JavaPsiServiceInterop.definition(params) })
        }
      } finally {
        closeDocument(path)
      }
    }
  }

  @Test
  fun javaReferencesResolveMethodsFieldsAndTypesAcrossJavaSources() {
    KotlinAnalysisTestSupport.withWorkspace {
      val directory = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app",
      )
      val declarationPath = directory.resolve("JavaPsiReferenceTarget.java")
      val callerPath = directory.resolve("JavaPsiReferenceCaller.java")
      val declaration = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          public class JavaPsiReferenceTarget {
            public String value;
            public String call() { return value; }
          }
      """.trimIndent()
      val caller = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          class JavaReferenceCaller {
            String run(JavaPsiReferenceTarget target) {
              target.value = new JavaPsiReferenceTarget().call();
              return target.value;
            }
          }
      """.trimIndent()
      Files.write(declarationPath, declaration.toByteArray(Charsets.UTF_8))
      Files.write(callerPath, caller.toByteArray(Charsets.UTF_8))
      openDocument(declarationPath, declaration, version = 1, revision = 101L)
      openDocument(callerPath, caller, version = 1, revision = 102L)
      try {
        KotlinAnalysisTestSupport.withRuntime(workspace) { runtime ->
          KotlinAnalysisTestSupport.runAnalysis {
            runtime.onFileCreated(declarationPath)
            runtime.onFileCreated(callerPath)
            runtime.onFileOpened(declarationPath)
            runtime.onFileOpened(callerPath)
          }
          fun references(
              path: java.nio.file.Path,
              content: String,
              marker: String,
              includeDeclaration: Boolean = false,
          ) = KotlinAnalysisTestSupport.runAnalysis {
                JavaPsiServiceInterop.references(
                    ReferenceParams(
                        path,
                        com.tom.rv2ide.lsp.models.LineIndex.from(content)
                            .indexToPosition(content.indexOf(marker)),
                        includeDeclaration,
                        ICancelChecker.NOOP,
                    ).apply {
                      documentVersion = 1
                      documentRevision = if (path == declarationPath) 101L else 102L
                    },
                )
              }

          val fieldReferences = requireNotNull(references(declarationPath, declaration, "value", true))
          assertTrue(fieldReferences.locations.count { it.file == callerPath } >= 2)
          assertTrue(fieldReferences.roles.contains(ReferenceRole.DEFINITION))
          assertTrue(fieldReferences.roles.contains(ReferenceRole.USAGE))

          val methodReferences = requireNotNull(references(declarationPath, declaration, "call()", true))
          assertTrue(methodReferences.locations.any { it.file == callerPath })

          val typeReferences = requireNotNull(references(callerPath, caller, "JavaPsiReferenceTarget target", true))
          assertTrue(typeReferences.locations.count { it.file == callerPath } >= 2)
          assertTrue(typeReferences.locations.any { it.file == declarationPath })

          val stale = ReferenceParams(
              declarationPath,
              com.tom.rv2ide.lsp.models.LineIndex.from(declaration).indexToPosition(declaration.indexOf("value;")),
              true,
              ICancelChecker.NOOP,
          ).apply {
            documentVersion = 1
            documentRevision = 100L
          }
          assertNull(KotlinAnalysisTestSupport.runAnalysis { JavaPsiServiceInterop.references(stale) })
        }
      } finally {
        closeDocument(declarationPath)
        closeDocument(callerPath)
        Files.deleteIfExists(declarationPath)
        Files.deleteIfExists(callerPath)
      }
    }
  }

  @Test
  fun javaHoverUsesActivePsiSignatureAndJavaDocAndRejectsStaleStamp() {
    KotlinAnalysisTestSupport.withWorkspace {
      val path = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaPsiHover.java",
      )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;
          /** Hover API documentation. */
          public class JavaPsiHover {
            /** Returns the supplied value. */
            public String value(String input) { return input; }
          }
      """.trimIndent()
      val disk = "package com.tom.rv2ide.testing.kotlinfixture.app; public class JavaPsiHover {}"
      Files.write(path, disk.toByteArray(Charsets.UTF_8))
      openDocument(path, content, version = 2, revision = 111L)
      try {
        KotlinAnalysisTestSupport.withRuntime(workspace) { runtime ->
          KotlinAnalysisTestSupport.runAnalysis {
            runtime.onFileCreated(path)
            runtime.onFileOpened(path)
          }
          val offset = content.indexOf("value(String")
          val params = DefinitionParams(
              path,
              com.tom.rv2ide.lsp.models.LineIndex.from(content).indexToPosition(offset),
              ICancelChecker.NOOP,
          ).apply {
            documentVersion = 2
            documentRevision = 111L
          }
          val hover = requireNotNull(
              KotlinAnalysisTestSupport.runAnalysis { JavaPsiServiceInterop.hover(params) },
          )
          assertTrue(hover.content.value.contains("String value(String input)"))
          assertTrue(hover.content.value.contains("Returns the supplied value."))
          params.documentRevision = 110L
          assertNull(KotlinAnalysisTestSupport.runAnalysis { JavaPsiServiceInterop.hover(params) })
          assertEquals(disk, String(Files.readAllBytes(path), Charsets.UTF_8))
        }
      } finally {
        closeDocument(path)
        Files.deleteIfExists(path)
      }
    }
  }

  @Test
  fun kotlinSourceHoverStillUsesTheKotlinSemanticHoverBridge() {
    KotlinAnalysisTestSupport.withWorkspace {
      val previousRecognition = JavaPreferences.isJavaKotlinRecognitionEnabled
      val javaPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val kotlinPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt",
      )
      val callerPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/KotlinHoverCaller.java",
      )
      try {
        JavaPreferences.isJavaKotlinRecognitionEnabled = true
        val javaContent = String(Files.readAllBytes(javaPath), Charsets.UTF_8)
        val kotlinContent = """
            package com.tom.rv2ide.testing.kotlinfixture.app
            /** Kotlin hover documentation. */
            fun kotlinHoverTarget(value: String): String = value
        """.trimIndent()
        openDocument(javaPath, javaContent, version = 1, revision = 121L)
        openDocument(kotlinPath, kotlinContent, version = 1, revision = 122L)
        KotlinAnalysisTestSupport.withRuntime(workspace) { runtime ->
          KotlinAnalysisTestSupport.runAnalysis {
            runtime.onFileOpened(javaPath)
            runtime.onFileOpened(kotlinPath)
          }
          val javaCall = """
              package com.tom.rv2ide.testing.kotlinfixture.app;
              class KotlinHoverCaller {
                String call() { return ActiveDocumentKt.kotlinHoverTarget("value"); }
              }
          """.trimIndent()
          Files.write(callerPath, javaCall.toByteArray(Charsets.UTF_8))
          openDocument(callerPath, javaCall, version = 1, revision = 123L)
          KotlinAnalysisTestSupport.runAnalysis {
            runtime.onFileCreated(callerPath)
            runtime.onFileOpened(callerPath)
          }
          val hover = requireNotNull(
              KotlinAnalysisTestSupport.runAnalysis {
                JavaHoverProvider(ICancelChecker.NOOP).hover(
                    DefinitionParams(
                        callerPath,
                        com.tom.rv2ide.lsp.models.LineIndex.from(javaCall)
                            .indexToPosition(javaCall.indexOf("kotlinHoverTarget")),
                        ICancelChecker.NOOP,
                    ).apply {
                      documentVersion = 1
                      documentRevision = 123L
                    },
                )
              },
          )
          assertTrue(hover.value.contains("fun kotlinHoverTarget(value: String): String"))
          assertTrue(hover.value.contains("Kotlin hover documentation."))
        }
      } finally {
        closeDocument(javaPath)
        closeDocument(kotlinPath)
        closeDocument(callerPath)
        Files.deleteIfExists(callerPath)
        JavaPreferences.isJavaKotlinRecognitionEnabled = previousRecognition
      }
    }
  }

  @Test
  fun kotlinBackendNavigatesToUnsavedJavaAndRestoresDiskAfterClose() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val kotlinPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ActiveDocument.kt",
      )
      val disk = String(Files.readAllBytes(javaPath), Charsets.UTF_8)
      val active = disk.replace("value(String", "unsavedValue(String")
      val caller = """
          package com.tom.rv2ide.testing.kotlinfixture.app
          fun probe(): String = JavaInterop().unsavedValue("input")
      """.trimIndent()
      openDocument(javaPath, active, version = 1, revision = 21L)
      openDocument(kotlinPath, caller, version = 1, revision = 22L)
      val backend = KotlinAnalysisTestSupport.createBackend()
      val documents = com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentSync(backend)
      try {
        assertFalse(backend.supportsDocument(javaPath))
        assertTrue(backend.synchronizesDocument(javaPath))
        assertTrue(backend.startBackend(KotlinProjectClasspathProvider(workspace)))
        backend.initialize(com.google.gson.JsonObject()) { assertTrue(it != null) }
        documents.resyncActiveDocuments()
        val target = KotlinAnalysisTestSupport.runSuspend {
          backend.findDefinition(KotlinAnalysisTestSupport.request(kotlinPath, caller, "unsavedValue")) { true }
        }
        assertEquals(javaPath, requireNotNull(target).locations.single().file)
        assertEquals(
            com.tom.rv2ide.lsp.models.LineIndex.from(active).indexToPosition(active.indexOf("unsavedValue")),
            target.locations.single().range.start,
        )
        val changedJava = active.replace("unsavedValue", "changedValue")
        changeDocument(javaPath, active, changedJava, version = 2, revision = 23L)
        documents.syncActiveDocument(javaPath)
        val stale = KotlinAnalysisTestSupport.runSuspend {
          backend.findDefinition(KotlinAnalysisTestSupport.request(kotlinPath, caller, "unsavedValue")) { true }
        }
        assertTrue(stale == null || stale.locations.isEmpty())
        val changedCaller = caller.replace("unsavedValue", "changedValue")
        changeDocument(kotlinPath, caller, changedCaller, version = 2, revision = 24L)
        documents.syncActiveDocument(kotlinPath)
        val changedTarget = KotlinAnalysisTestSupport.runSuspend {
          backend.findDefinition(KotlinAnalysisTestSupport.request(kotlinPath, changedCaller, "changedValue")) { true }
        }
        assertEquals(javaPath, requireNotNull(changedTarget).locations.single().file)
        closeDocument(javaPath)
        documents.closeDocument(javaPath)
        val closedTarget = KotlinAnalysisTestSupport.runSuspend {
          backend.findDefinition(KotlinAnalysisTestSupport.request(kotlinPath, changedCaller, "changedValue")) { true }
        }
        assertTrue(closedTarget == null || closedTarget.locations.isEmpty())
        val diskCaller = caller.replace("unsavedValue", "value")
        changeDocument(kotlinPath, changedCaller, diskCaller, version = 3, revision = 25L)
        documents.syncActiveDocument(kotlinPath)
        val restored = KotlinAnalysisTestSupport.runSuspend {
          backend.findDefinition(KotlinAnalysisTestSupport.request(kotlinPath, diskCaller, "value")) { true }
        }
        assertEquals(javaPath, requireNotNull(restored).locations.single().file)
        assertEquals(disk, String(Files.readAllBytes(javaPath), Charsets.UTF_8))
      } finally {
        documents.clear()
        backend.close()
      }
    }
  }

  @Test
  fun activeJavaSnapshotShadowsDiskAndCloseRestoresIt() {
    KotlinAnalysisTestSupport.withWorkspace {
      val path = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val disk = String(Files.readAllBytes(path), Charsets.UTF_8)
      val active = disk.replace("class JavaInterop", "class UnsavedInterop")
          .replace("value(String", "unsavedValue(String")
      assertTrue(disk != active)
      openDocument(path, active, version = 1, revision = 11L)
      KotlinAnalysisTestSupport.prepareAnalysisPaths()
      KotlinAnalysisTestSupport.runAnalysis {
        val environment = LiveAnalysisEnvironment(
            KotlinProjectClasspathProvider(workspace), KotlinAnalysisTestSupport.analysisApiJar(),
        )
        try {
          val started = environment.start()
          assertTrue(environment.startFailure?.stackTraceToString(), started)
          fun verify(activeExpected: Boolean) {
            AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
              ApplicationManager.getApplication().runReadAction(Computable {
                environment.project.analysisRead {
                  val scope = GlobalSearchScope.allScope(environment.project)
                  val facade = JavaPsiFacade.getInstance(environment.project)
                  val prefix = "com.tom.rv2ide.testing.kotlinfixture.app"
                  val activeClass = facade.findClass("$prefix.UnsavedInterop", scope)
                  val diskClass = facade.findClass("$prefix.JavaInterop", scope)
                  if (activeExpected) {
                    assertNull(diskClass)
                    assertEquals("unsavedValue", requireNotNull(activeClass).methods.single { it.name == "unsavedValue" }.name)
                  } else {
                    assertNull(activeClass)
                    assertTrue(requireNotNull(diskClass).methods.any { it.name == "value" })
                  }
                  val names = environment.javaFiles.knownClassNamesInPackage(FqName(prefix))
                  assertEquals(activeExpected, "UnsavedInterop" in names)
                  assertEquals(!activeExpected, "JavaInterop" in names)
                  val activeRequest = JavaClassFinder.Request(ClassId.topLevel(FqName("$prefix.UnsavedInterop")))
                  val diskRequest = JavaClassFinder.Request(ClassId.topLevel(FqName("$prefix.JavaInterop")))
                  assertEquals(activeExpected, environment.javaFiles.findClass(activeRequest, scope) != null)
                  assertEquals(!activeExpected, environment.javaFiles.findClass(diskRequest, scope) != null)
                  if (activeExpected) {
                    val module = requireNotNull(environment.sourceRegistry.moduleForPath(path))
                    assertTrue(module.contentScope.contains(requireNotNull(activeClass).containingFile.viewProvider.virtualFile))
                    val library = requireNotNull(environment.sourceModulesByPath[":library"])
                    assertNull(environment.javaFiles.findClass("$prefix.UnsavedInterop", library.contentScope))
                  }
                }
              })
            }
          }
          verify(activeExpected = true)
          fun currentJavaPsi(version: Int = 1, revision: Long = 11L) =
              AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
                ApplicationManager.getApplication().runReadAction(Computable {
                  environment.project.analysisRead {
                    environment.javaFiles.currentFile(path, version, revision)
                  }
                })
              }
          val activePsi = requireNotNull(currentJavaPsi())
          assertNull(currentJavaPsi(version = 0))
          assertNull(currentJavaPsi(revision = 10L))
          environment.javaFiles.refresh(path)
          assertTrue(activePsi === currentJavaPsi())
          closeDocument(path)
          environment.javaFiles.refresh(path)
          verify(activeExpected = false)
          assertEquals(disk, String(Files.readAllBytes(path), Charsets.UTF_8))
        } finally {
          environment.close()
        }
      }
    }
  }

  @Test
  fun javaPsiResolvesJavaSourcesAndKotlinLightMethods() {
    KotlinAnalysisTestSupport.withWorkspace {
      KotlinAnalysisTestSupport.prepareAnalysisPaths()
      KotlinAnalysisTestSupport.runAnalysis {
        val environment =
            LiveAnalysisEnvironment(
                KotlinProjectClasspathProvider(workspace),
                KotlinAnalysisTestSupport.analysisApiJar(),
            )
        try {
          val started = environment.start()
          assertTrue(
              "${environment.startFailureReason}\n${environment.startFailure?.stackTraceToString().orEmpty()}",
              started,
          )
          AnalysisApiGuard.withAccess(ICancelChecker.NOOP) {
            ApplicationManager.getApplication().runReadAction(Computable {
              environment.project.analysisRead {
                val text = """
                    package com.tom.rv2ide.testing.kotlinfixture.app;

                    import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi;
                    import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApiKt;

                    final class JavaPsiProbe {
                      String fromJava() {
                        return new JavaInterop().value("java");
                      }

                      String fromKotlinClass() {
                        return new LibraryApi().load("kotlin");
                      }

                      String fromKotlinFacade() {
                        return LibraryApiKt.libraryExtension(" facade ");
                      }
                    }
                """.trimIndent()
                val file =
                    PsiFileFactory.getInstance(environment.project)
                        .createFileFromText("JavaPsiProbe.java", JavaFileType.INSTANCE, text)
                assertTrue("Java parser must produce PsiJavaFile", file is PsiJavaFile)
                assertTrue(
                    "Java probe must parse without syntax errors",
                    PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).isEmpty(),
                )
                val calls = PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression::class.java)
                    .associateBy { it.methodExpression.referenceName }
                assertEquals(setOf("value", "load", "libraryExtension"), calls.keys)
                val expectedOwners = mapOf(
                    "value" to "com.tom.rv2ide.testing.kotlinfixture.app.JavaInterop",
                    "load" to "com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi",
                    "libraryExtension" to "com.tom.rv2ide.testing.kotlinfixture.library.LibraryApiKt",
                )
                for ((name, owner) in expectedOwners) {
                  val method = requireNotNull(calls.getValue(name).resolveMethod()) {
                    "Java PSI failed to resolve $owner.$name"
                  }
                  assertEquals(owner, method.containingClass?.qualifiedName)
                  if (name != "value") {
                    val declaration = method.navigationElement
                    assertTrue("$owner.$name must navigate to Kotlin source", declaration is KtNamedFunction)
                    assertEquals(name, (declaration as KtNamedFunction).name)
                  }
                }
              }
            })
          }
        } finally {
          environment.close()
        }
      }
    }
  }

  @Test
  fun javaInteropResolvesKotlinDeclarationFromUnsavedJava() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val libraryPath = KotlinAnalysisTestSupport.fixturePath(
          "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt",
      )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi;

          final class JavaInterop {
            String read() {
              return new LibraryApi().load("value");
            }
          }
      """.trimIndent()
      openDocument(javaPath, content, version = 1, revision = 31L)
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val location = requireNotNull(
            com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinDefinition(
                javaPath,
                content.indexOf(".load(") + 1,
                ICancelChecker.NOOP,
            ),
        ) { "Java interop did not resolve the unsaved Kotlin call" }
        assertEquals(libraryPath, location.file)
        val libraryContent = String(Files.readAllBytes(libraryPath), Charsets.UTF_8)
        val index = com.tom.rv2ide.lsp.models.LineIndex.from(libraryContent)
        val start = index.lineColumnToIndex(location.range.start.line, location.range.start.column)
        val end = index.lineColumnToIndex(location.range.end.line, location.range.end.column)
        assertEquals("load", libraryContent.substring(start, end))
        val hover = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinHover(
                  javaPath,
                  content.indexOf(".load(") + 1,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not render Kotlin hover content" }
        assertTrue(hover.value.startsWith("```kotlin\n"))
        assertTrue(hover.value.contains("fun load"))
        val signature = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              javaSignature(
                  javaPath,
                  content.indexOf("load(") + "load(".length,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not render Kotlin signature" }
        assertEquals("load(value: String)", signature.signatures.single().label)
        assertEquals(0, signature.activeParameter)
      }
    }
  }

  @Test
  fun javaInteropRendersConstructorOverloadsExtensionAndFacade() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath = KotlinAnalysisTestSupport.fixturePath(
          "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
      )
      val libraryPath = KotlinAnalysisTestSupport.fixturePath(
          "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt",
      )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads;
          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi;
          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApiKt;

          final class JavaInterop {
            String overload() {
              return new InteropOverloads("label").pick("value");
            }

            String extension() {
              return new InteropOverloads("label").decorated("receiver", "suffix");
            }

            String defaultConstructor() {
              return new LibraryApi().load("value");
            }

            String facade() {
              return LibraryApiKt.libraryExtension("facade");
            }
          }
      """.trimIndent()
      openDocument(javaPath, content, version = 1, revision = 41L)
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val constructor = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              javaSignature(
                  javaPath,
                  content.indexOf("InteropOverloads(\"label\")") + "InteropOverloads(".length,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not render the Kotlin constructor signature" }
        assertEquals("InteropOverloads(label: String)", constructor.signatures.single().label)
        assertEquals(0, constructor.activeParameter)

        val overloads = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              javaSignature(
                  javaPath,
                  content.indexOf(".pick(") + ".pick(".length,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not render Kotlin overload candidates" }
        assertEquals(
            listOf("pick(value: String)", "pick(value: Int)"),
            overloads.signatures.map { it.label },
        )
        assertEquals(0, overloads.activeSignature)
        assertEquals(0, overloads.activeParameter)

        val topLevel = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              javaSignature(
                  javaPath,
                  content.indexOf("libraryExtension(") + "libraryExtension(".length,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not render the Kotlin top-level signature" }
        assertEquals("libraryExtension(value: String)", topLevel.signatures.single().label)

        val extension = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              javaSignature(
                  javaPath,
                  content.indexOf("decorated(") + "decorated(".length,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not render the Kotlin extension signature" }
        assertEquals("decorated(String, suffix: String)", extension.signatures.single().label)
        assertEquals(0, extension.activeParameter)

        val extensionTail = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              javaSignature(
                  javaPath,
                  content.indexOf("\"suffix\"") + 1,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not map the extension argument position" }
        assertEquals(1, extensionTail.activeParameter)

        val defaultConstructor = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              javaSignature(
                  javaPath,
                  content.indexOf("new LibraryApi(") + "new LibraryApi(".length,
                  ICancelChecker.NOOP,
              )
            },
        ) { "Java interop did not render the implicit Kotlin constructor" }
        assertEquals("LibraryApi()", defaultConstructor.signatures.single().label)

        val facade = requireNotNull(
            com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinDefinition(
                javaPath,
                content.indexOf("LibraryApiKt.") + 3,
                ICancelChecker.NOOP,
            ),
        ) { "Java interop did not resolve the Kotlin facade class" }
        assertEquals(libraryPath, facade.file)
        assertEquals(0, facade.range.start.line)
        assertEquals(0, facade.range.start.column)
        assertEquals(0, facade.range.end.line)
        assertEquals(0, facade.range.end.column)
      }
    }
  }

  @Test
  fun javaInteropFindsKotlinReferencesFromJavaCallSite() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath =
          KotlinAnalysisTestSupport.fixturePath(
              "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
          )
      val libraryPath =
          KotlinAnalysisTestSupport.fixturePath(
              "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt",
          )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi;

          final class JavaInterop {
            String read() {
              return new LibraryApi().load("value");
            }
          }
      """.trimIndent()
      openDocument(javaPath, content, version = 1, revision = 61L)
      try {
        KotlinAnalysisTestSupport.withRuntime(workspace) {
          val references = requireNotNull(
              KotlinAnalysisTestSupport.runAnalysis {
                com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinReferences(
                    javaPath,
                    content.indexOf(".load(") + 1,
                    true,
                    ICancelChecker.NOOP,
                )
              }
          ) { "Java interop returned no reference result" }
          val callLine =
              com.tom.rv2ide.lsp.models.LineIndex.from(content)
                  .indexToPosition(content.indexOf("load("))
                  .line
          assertTrue(
              "java call site missing: ${references.locations}",
              references.locations.any { it.file == javaPath && it.range.start.line == callLine },
          )
          assertTrue(
              "kotlin declaration missing: ${references.locations}",
              references.locations.any { it.file == libraryPath },
          )
        }
      } finally {
        closeDocument(javaPath)
      }
    }
  }

  @Test
  fun javaInteropFindsKotlinReferencesForJvmNamedFunction() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath =
          KotlinAnalysisTestSupport.fixturePath(
              "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
          )
      val libraryPath =
          KotlinAnalysisTestSupport.fixturePath(
              "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt",
          )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApiKt;

          final class JavaInterop {
            String renamed() {
              return LibraryApiKt.renamedLibrary("value");
            }
          }
      """.trimIndent()
      openDocument(javaPath, content, version = 1, revision = 71L)
      try {
        KotlinAnalysisTestSupport.withRuntime(workspace) {
          val references = requireNotNull(
              KotlinAnalysisTestSupport.runAnalysis {
                com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinReferences(
                    javaPath,
                    content.indexOf("renamedLibrary") + 3,
                    true,
                    ICancelChecker.NOOP,
                )
              }
          ) { "Java interop returned no reference result for @JvmName" }
          val callLine =
              com.tom.rv2ide.lsp.models.LineIndex.from(content)
                  .indexToPosition(content.indexOf("renamedLibrary"))
                  .line
          assertTrue(
              "java call site missing for @JvmName: ${references.locations}",
              references.locations.any { it.file == javaPath && it.range.start.line == callLine },
          )
          assertTrue(
              "kotlin declaration missing for @JvmName: ${references.locations}",
              references.locations.any { it.file == libraryPath },
          )
        }
      } finally {
        closeDocument(javaPath)
      }
    }
  }

  @Test
  fun javaInteropFindsKotlinConstructorReferencesFromJavaCallSites() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath =
          KotlinAnalysisTestSupport.fixturePath(
              "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
          )
      val libraryPath =
          KotlinAnalysisTestSupport.fixturePath(
              "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt",
          )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi;

          final class JavaInterop {
            String first() {
              return new LibraryApi().load("value");
            }

            Object second() {
              return new LibraryApi();
            }
          }
      """.trimIndent()
      openDocument(javaPath, content, version = 1, revision = 71L)
      try {
        KotlinAnalysisTestSupport.withRuntime(workspace) {
          val references = requireNotNull(
              KotlinAnalysisTestSupport.runAnalysis {
                com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinReferences(
                    javaPath,
                    content.indexOf("new LibraryApi()") + "new ".length,
                    true,
                    ICancelChecker.NOOP,
                )
              }
          ) { "Java interop returned no constructor reference result" }
          val index = com.tom.rv2ide.lsp.models.LineIndex.from(content)
          val firstLine = index.indexToPosition(content.indexOf("new LibraryApi().load")).line
          val secondLine = index.indexToPosition(content.indexOf("return new LibraryApi();")).line
          assertTrue(
              "first constructor call missing: ${references.locations}",
              references.locations.any { it.file == javaPath && it.range.start.line == firstLine },
          )
          assertTrue(
              "second constructor call missing: ${references.locations}",
              references.locations.any { it.file == javaPath && it.range.start.line == secondLine },
          )
          assertTrue(
              "kotlin declaration missing: ${references.locations}",
              references.locations.any { it.file == libraryPath },
          )
        }
      } finally {
        closeDocument(javaPath)
      }
    }
  }

  @Test
  fun javaInteropFindsKotlinPropertyReferencesFromJavaCallSite() {
    KotlinAnalysisTestSupport.withWorkspace {
      val javaPath =
          KotlinAnalysisTestSupport.fixturePath(
              "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/JavaInterop.java",
          )
      val libraryPath =
          KotlinAnalysisTestSupport.fixturePath(
              "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt",
          )
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app;

          import com.tom.rv2ide.testing.kotlinfixture.library.InteropOverloads;

          final class JavaInterop {
            String read() {
              return new InteropOverloads("x").getLabel();
            }
          }
      """.trimIndent()
      openDocument(javaPath, content, version = 1, revision = 72L)
      try {
        KotlinAnalysisTestSupport.withRuntime(workspace) {
          val references = requireNotNull(
              KotlinAnalysisTestSupport.runAnalysis {
                com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinReferences(
                    javaPath,
                    content.indexOf("getLabel("),
                    true,
                    ICancelChecker.NOOP,
                )
              }
          ) { "Java interop returned no property reference result" }
          val callLine =
              com.tom.rv2ide.lsp.models.LineIndex.from(content)
                  .indexToPosition(content.indexOf("getLabel("))
                  .line
          assertTrue(
              "java property call missing: ${references.locations}",
              references.locations.any { it.file == javaPath && it.range.start.line == callLine },
          )
          assertTrue(
              "kotlin property declaration missing: ${references.locations}",
              references.locations.any { it.file == libraryPath },
          )
        }
      } finally {
        closeDocument(javaPath)
      }
    }
  }

  @Test
  fun kotlinTopLevelTypesReflectModuleSources() {
    KotlinAnalysisTestSupport.withWorkspace {
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val types = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinTopLevelTypes(
                  ":library",
                  ICancelChecker.NOOP,
              )
            }
        ) { "Java interop returned no shared top-level types" }
        val prefix = "com.tom.rv2ide.testing.kotlinfixture.library"
        assertTrue("missing LibraryApi; types=$types", types.contains("$prefix.LibraryApi"))
        assertTrue(
            "missing InteropOverloads; types=$types",
            types.contains("$prefix.InteropOverloads"),
        )
        assertTrue(
            "missing LibraryApiKt; types=$types",
            types.contains("$prefix.LibraryApiKt"),
        )
        assertTrue(
            "unexpected JavaInterop; types=$types",
            types.none { it.endsWith(".JavaInterop") },
        )
      }
    }
  }

  @Test
  fun kotlinTopLevelTypesExcludeAliasesAndPrivateOnlyFacades() {
    KotlinAnalysisTestSupport.withWorkspace {
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val types = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinTopLevelTypes(
                  ":library",
                  ICancelChecker.NOOP,
              )
            }
        ) { "Java interop returned no shared top-level types" }
        val prefix = "com.tom.rv2ide.testing.kotlinfixture.library"
        assertTrue(
            "missing TypeAliasesKt positive control; types=$types",
            types.contains("$prefix.TypeAliasesKt"),
        )
        assertFalse(
            "Kotlin typealias must not be offered as a Java class; types=$types",
            types.contains("$prefix.LibraryAlias"),
        )
        assertFalse(
            "private-only Kotlin file must not expose a Java facade; types=$types",
            types.contains("$prefix.PrivateOnlyKt"),
        )
      }
    }
  }

  @Test
  fun kotlinTopLevelTypesIncludeCompileDependencySources() {
    KotlinAnalysisTestSupport.withWorkspace {
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val types = requireNotNull(
            KotlinAnalysisTestSupport.runAnalysis {
              com.tom.rv2ide.language.services.interop.JavaPsiInterop.kotlinTopLevelTypes(
                  ":app",
                  ICancelChecker.NOOP,
              )
            }
        ) { "Java interop returned no shared top-level types for the consumer module" }
        assertTrue(
            "consumer must see its own Kotlin source; types=$types",
            types.contains("com.tom.rv2ide.testing.kotlinfixture.app.AppConsumer"),
        )
        assertTrue(
            "consumer must see Kotlin source from compile dependencies; types=$types",
            types.contains("com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi"),
        )
      }
    }
  }

  @Test
  fun javaCompilerTopLevelTypesConsumeSharedKotlinTypes() {
    KotlinAnalysisTestSupport.withWorkspace {
      val consumerFile =
          KotlinAnalysisTestSupport.fixturePath(
              "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt",
          )
      val module = requireNotNull(workspace.findModuleForFile(consumerFile, checkExistance = true))
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val previous = JavaPreferences.isJavaKotlinRecognitionEnabled
        val prefix = "com.tom.rv2ide.testing.kotlinfixture"
        try {
          JavaPreferences.isJavaKotlinRecognitionEnabled = true
          val enabled =
              KotlinAnalysisTestSupport.runAnalysis {
                JavaCompilerService(module).publicTopLevelTypes()
              }
          assertTrue(
              "Java compiler must expose the module's own Kotlin types; hasAppConsumer=${enabled.contains("$prefix.app.AppConsumer")}",
              enabled.contains("$prefix.app.AppConsumer"),
          )
          assertTrue(
              "Java compiler must expose Kotlin types from compile dependencies; hasLibraryApi=${enabled.contains("$prefix.library.LibraryApi")}",
              enabled.contains("$prefix.library.LibraryApi"),
          )
          assertTrue(
              "Java compiler must expose Kotlin file facades from dependencies; hasTypeAliasesKt=${enabled.contains("$prefix.library.TypeAliasesKt")}",
              enabled.contains("$prefix.library.TypeAliasesKt"),
          )
          assertFalse(
              "Java compiler must not expose Kotlin typealiases; hasLibraryAlias=${enabled.contains("$prefix.library.LibraryAlias")}",
              enabled.contains("$prefix.library.LibraryAlias"),
          )
          assertFalse(
              "Java compiler must not expose private-only Kotlin facades; hasPrivateOnlyKt=${enabled.contains("$prefix.library.PrivateOnlyKt")}",
              enabled.contains("$prefix.library.PrivateOnlyKt"),
          )
          JavaPreferences.isJavaKotlinRecognitionEnabled = false
          val disabled =
              KotlinAnalysisTestSupport.runAnalysis {
                JavaCompilerService(module).publicTopLevelTypes()
              }
          assertFalse(
              "Kotlin source types must disappear when recognition is disabled; hasLibraryApi=${disabled.contains("$prefix.library.LibraryApi")}",
              disabled.contains("$prefix.library.LibraryApi"),
          )
        } finally {
          JavaPreferences.isJavaKotlinRecognitionEnabled = previous
        }
      }
    }
  }

  @Test
  fun sharedTypeSnapshotTracksResolverGeneration() {
    val first = FakeTopLevelTypesResolver(setOf("shared.First"))
    val second = FakeTopLevelTypesResolver(setOf("shared.Second"))
    JavaPsiInterop.register(first)
    try {
      val firstSnapshot = JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.NOOP)
      assertEquals(setOf("shared.First"), firstSnapshot.types)
      val firstStamp = JavaPsiInterop.kotlinTopLevelTypesStamp()

      JavaPsiInterop.register(second)
      val secondSnapshot = JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.NOOP)
      assertEquals(setOf("shared.Second"), secondSnapshot.types)
      assertTrue(
          "resolver replacement must advance the shared generation",
          secondSnapshot.resolverGeneration > firstStamp.resolverGeneration,
      )
      assertEquals(1, second.calls.get())

      JavaPsiInterop.unregister(second)
      val unavailable = JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.NOOP)
      assertNull(unavailable.types)
    } finally {
      JavaPsiInterop.unregister(first)
      JavaPsiInterop.unregister(second)
    }
  }

  @Test
  fun sharedTypeSnapshotRequeriesWhenMetadataBecomesReady() {
    val resolver = FakeTopLevelTypesResolver(types = null)
    JavaPsiInterop.register(resolver)
    try {
      val beforeReady = JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.NOOP)
      assertNull(beforeReady.types)
      assertEquals(0L, beforeReady.metadataRevision)
      assertEquals(1, resolver.calls.get())

      resolver.types = setOf("shared.Ready")
      resolver.revision = 1L
      val ready = JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.NOOP)
      assertEquals(setOf("shared.Ready"), ready.types)
      assertEquals(1L, ready.metadataRevision)
      assertEquals(2, resolver.calls.get())
    } finally {
      JavaPsiInterop.unregister(resolver)
    }
  }

  @Test
  fun cancellationFromSharedTypeEnumerationPropagatesToTheCaller() {
    val resolver = FakeTopLevelTypesResolver(types = setOf("shared.Cancelled"))
    JavaPsiInterop.register(resolver)
    var propagated = false
    try {
      JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.CANCELLED)
    } catch (ignored: java.util.concurrent.CancellationException) {
      propagated = true
    } finally {
      JavaPsiInterop.unregister(resolver)
    }
    assertTrue("cancellation must not become a fallback type result", propagated)
  }

  @Test
  fun cancelledSharedTypeSnapshotDoesNotPublishAResult() {
    val resolver = FakeTopLevelTypesResolver(setOf("shared.AfterCancel"))
    JavaPsiInterop.register(resolver)
    try {
      var cancelled = false
      try {
        JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.CANCELLED)
      } catch (ignored: java.util.concurrent.CancellationException) {
        cancelled = true
      }
      assertTrue("a cancelled shared type query must abort", cancelled)
      assertEquals("cancellation must not publish a result", 1, resolver.calls.get())
      val next = JavaPsiInterop.kotlinTopLevelTypesSnapshot(":app", ICancelChecker.NOOP)
      assertEquals(setOf("shared.AfterCancel"), next.types)
      assertEquals("the next query must query the shared source", 2, resolver.calls.get())
    } finally {
      JavaPsiInterop.unregister(resolver)
    }
  }

  @Test
  fun cancelledClassOutputQueryDoesNotScanDependencyOutputs() {
    KotlinAnalysisTestSupport.withWorkspace {
      val consumerFile =
          KotlinAnalysisTestSupport.fixturePath(
              "app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/AppConsumer.kt",
          )
      val module = requireNotNull(workspace.findModuleForFile(consumerFile, checkExistance = true))
      var cancelled = false
      try {
        com.tom.rv2ide.language.services.java.kotlin.KotlinClassOutputProvider
            .publicDependencyTopLevelTypes(module, ICancelChecker.CANCELLED)
      } catch (ignored: java.util.concurrent.CancellationException) {
        cancelled = true
      }
      assertTrue("a cancelled class-output query must abort", cancelled)
    }
  }

  @Test
  fun topLevelTypesStampPairsGenerationWithTheResolverRevision() {
    val first = FakeTopLevelTypesResolver(setOf("shared.First"), revision = 1L)
    val second = FakeTopLevelTypesResolver(setOf("shared.Second"), revision = 2L)
    JavaPsiInterop.register(first)
    val firstStamp = JavaPsiInterop.kotlinTopLevelTypesStamp()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    first.beforeMetadataRevision = {
      entered.countDown()
      assertTrue("the first revision read must be released", release.await(10, TimeUnit.SECONDS))
    }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val future = executor.submit(Callable { JavaPsiInterop.kotlinTopLevelTypesStamp() })
      assertTrue("the first resolver must be queried", entered.await(10, TimeUnit.SECONDS))
      JavaPsiInterop.register(second)
      release.countDown()
      val stamp = future.get(10, TimeUnit.SECONDS)
      assertTrue(
          "stamp generation must advance past the replaced resolver",
          stamp.resolverGeneration > firstStamp.resolverGeneration,
      )
      assertEquals(
          "stamp must carry the replacement resolver's revision",
          2L,
          stamp.metadataRevision,
      )
    } finally {
      release.countDown()
      executor.shutdownNow()
      JavaPsiInterop.unregister(second)
      JavaPsiInterop.unregister(first)
    }
  }

  @Test
  fun kotlinTypeSourceFilesResolveClassesAndFacades() {
    KotlinAnalysisTestSupport.withWorkspace {
      KotlinAnalysisTestSupport.withRuntime(workspace) {
        val libraryFile =
            KotlinAnalysisTestSupport.fixturePath(
                "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/LibraryApi.kt",
            ).normalize()
        val aliasesFile =
            KotlinAnalysisTestSupport.fixturePath(
                "library/src/main/java/com/tom/rv2ide/testing/kotlinfixture/library/TypeAliases.kt",
            ).normalize()
        val prefix = "com.tom.rv2ide.testing.kotlinfixture.library"
        assertEquals(
            "a Kotlin class must resolve to its declaring file",
            listOf(libraryFile),
            JavaPsiInterop.kotlinTypeSourceFiles(":library", "$prefix.LibraryApi").map { it.normalize() },
        )
        assertEquals(
            "a file facade must resolve to its declaring file",
            listOf(libraryFile),
            JavaPsiInterop.kotlinTypeSourceFiles(":library", "$prefix.LibraryApiKt").map { it.normalize() },
        )
        assertEquals(
            "a top-level function facade must resolve to its declaring file",
            listOf(aliasesFile),
            JavaPsiInterop.kotlinTypeSourceFiles(":library", "$prefix.TypeAliasesKt").map { it.normalize() },
        )
        assertTrue(
            "an unknown type must not resolve to a source file",
            JavaPsiInterop.kotlinTypeSourceFiles(":library", "$prefix.MissingType").isEmpty(),
        )
      }
    }
  }

  private fun javaSignature(
      file: java.nio.file.Path,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): com.tom.rv2ide.lsp.models.SignatureHelp? {
    val snapshot = requireNotNull(com.tom.rv2ide.projects.FileManager.getActiveDocumentSnapshot(file))
    val previousRecognition = JavaPreferences.isJavaKotlinRecognitionEnabled
    try {
      JavaPreferences.isJavaKotlinRecognitionEnabled = true
      return JavaPsiServiceInterop.signatureHelp(
          com.tom.rv2ide.lsp.models.SignatureHelpParams(
              file,
              com.tom.rv2ide.lsp.models.LineIndex.from(snapshot.content).indexToPosition(offset),
              snapshot.content,
              cancelChecker,
          ).apply {
            documentVersion = snapshot.version
            documentRevision = snapshot.revision
          },
      )
    } finally {
      JavaPreferences.isJavaKotlinRecognitionEnabled = previousRecognition
    }
  }
}

private class FakeTopLevelTypesResolver(
    var types: Set<String>?,
    var revision: Long = 0L,
) : KotlinSourceResolver {
  val calls = java.util.concurrent.atomic.AtomicInteger()
  var beforeMetadataRevision: (() -> Unit)? = null

  override fun kotlinDefinition(
      javaFile: java.nio.file.Path,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): com.tom.rv2ide.models.Location? = null

  override fun kotlinHover(
      javaFile: java.nio.file.Path,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): com.tom.rv2ide.lsp.models.MarkupContent? = null

  override fun kotlinReferences(
      javaFile: java.nio.file.Path,
      offset: Int,
      includeDeclaration: Boolean,
      cancelChecker: ICancelChecker,
  ): com.tom.rv2ide.lsp.models.ReferenceResult? = null

  override fun kotlinTopLevelTypes(
      modulePath: String,
      cancelChecker: ICancelChecker,
  ): Set<String>? {
    calls.incrementAndGet()
    cancelChecker.abortIfCancelled()
    return types
  }

  override fun metadataRevision(): Long {
    beforeMetadataRevision?.invoke()
    return revision
  }
}
