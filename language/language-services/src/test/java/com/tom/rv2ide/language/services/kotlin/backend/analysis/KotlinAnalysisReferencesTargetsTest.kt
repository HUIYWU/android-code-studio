package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.request
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.runAnalysis
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withRuntime
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import com.tom.rv2ide.lsp.models.LineIndex
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class KotlinAnalysisReferencesTargetsTest {

  @Test
  fun accessorTargetUsesPropertyReferences() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceAccessor.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class AccessorTarget {
            val raw: String = "x"
            val computed: String
              get() = raw
          }

          fun useAccessor(): String = AccessorTarget().computed
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "get", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
          assertEquals(2, references.roles.size)
          assertTrue(references.locations.all { it.file == file })
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun typeAliasTargetMatchesAliasTypeReferences() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceAliasTarget.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi

          typealias AliasApi = LibraryApi

          fun aliasParameter(value: AliasApi): AliasApi = value
          fun useAliasConstructor(): AliasApi = AliasApi()
          fun useOriginalConstructor(): LibraryApi = LibraryApi()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(
                request(file, content, "AliasApi", includeDeclaration = true)
            )
          })
          assertEquals(5, references.locations.size)
          assertTrue(references.locations.all { it.file == file })
          val originalLine =
              LineIndex.from(content).indexToPosition(content.indexOf("LibraryApi()")).line
          assertTrue(references.locations.none { it.range.start.line == originalLine })
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun constructorTargetKeepsOverloadBinding() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceOverload.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class OverloadTarget {
            constructor(value: String) : this(0)
            constructor(value: Int)
          }

          fun useOverloadString(): OverloadTarget = OverloadTarget("s")
          fun useOverloadInt(): OverloadTarget = OverloadTarget(1)
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(
                request(file, content, "OverloadTarget", occurrence = 2, includeDeclaration = true)
            )
          })
          val intLine = LineIndex.from(content).indexToPosition(content.indexOf("OverloadTarget(1)")).line
          assertTrue(references.locations.none { it.range.start.line == intLine })
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun companionQualifierDoesNotLeakInstanceReceiver() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceCompanion.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class CompanionHost {
            companion object {
              fun companionValue(): String = "c"
            }
            fun instanceValue(): String = "i"
          }

          fun useCompanion(): String = CompanionHost.companionValue()
          fun useInstance(): String = CompanionHost().instanceValue()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(
                request(file, content, "CompanionHost", occurrence = 1, includeDeclaration = true)
            )
          })
          val companionLine =
              LineIndex.from(content).indexToPosition(content.indexOf("CompanionHost.companionValue")).line
          val instanceLine =
              LineIndex.from(content).indexToPosition(content.indexOf("CompanionHost().instanceValue")).line
          assertTrue(references.locations.any { it.range.start.line == companionLine })
          assertTrue(references.locations.none { it.range.start.line == instanceLine })
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun overrideReferencesUseDeclaredBindingAndKeepSuper() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceOverride.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          open class BaseReference {
            open fun value(): String = "base"
          }

          class DerivedReference : BaseReference() {
            override fun value(): String = "derived"
            fun callSuper(): String = super.value()
          }

          fun useDerivedReference(value: DerivedReference): String = value.value()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(
                request(file, content, "value", occurrence = 0, includeDeclaration = true)
            )
          })
          val superLine = LineIndex.from(content).indexToPosition(content.indexOf("super.value")).line
          val derivedCallLine =
              LineIndex.from(content).indexToPosition(content.indexOf("value.value()")).line
          assertTrue(references.locations.any { it.range.start.line == superLine })
          assertTrue(references.locations.none { it.range.start.line == derivedCallLine })
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }
}