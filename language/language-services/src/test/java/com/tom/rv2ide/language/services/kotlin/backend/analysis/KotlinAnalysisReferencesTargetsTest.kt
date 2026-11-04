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
  fun genericBaseOverrideKeepsDeclaredBinding() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceGenericOverride.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          open class GenericBase<T> {
            open fun value(item: T): T = item
          }

          class GenericDerived : GenericBase<String>() {
            override fun value(item: String): String = item
            fun callSuper(): String = super.value("x")
          }

          fun useGeneric(value: GenericDerived): String = value.value("y")
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
              LineIndex.from(content).indexToPosition(content.indexOf("value.value(")).line
          assertTrue(references.locations.any { it.range.start.line == superLine })
          assertTrue(references.locations.none { it.range.start.line == derivedCallLine })
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun operatorConventionFindsPlusUsage() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceOperator.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class OperatorTarget {
            operator fun plus(other: OperatorTarget): OperatorTarget = this
          }

          fun useOperator(left: OperatorTarget, right: OperatorTarget): OperatorTarget = left + right
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "plus", includeDeclaration = true))
          })
          val operatorLine =
              LineIndex.from(content).indexToPosition(content.indexOf("left + right")).line
          assertEquals(2, references.locations.size)
          assertTrue(references.locations.any { it.range.start.line == operatorLine })
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun arrayGetConventionFindsIndexUsage() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceArrayGet.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class ArrayGetTarget {
            operator fun get(index: Int): String = index.toString()
          }

          fun useArrayGet(target: ArrayGetTarget): String = target[0]
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
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun plusAssignConventionFindsCompoundAssignment() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferencePlusAssign.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class PlusAssignTarget {
            operator fun plusAssign(other: PlusAssignTarget) = Unit
          }

          fun usePlusAssign(target: PlusAssignTarget, other: PlusAssignTarget) {
            target += other
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "plusAssign", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun arraySetConventionFindsIndexAssignment() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceArraySet.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class ArraySetTarget {
            operator fun set(index: Int, value: String) = Unit
          }

          fun useArraySet(target: ArraySetTarget, value: String) {
            target[0] = value
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "set", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun invokeConventionFindsCallOnValue() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceInvoke.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class InvokeTarget {
            operator fun invoke(): String = "invoke"
          }

          fun useInvoke(target: InvokeTarget): String = target()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "invoke", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun componentConventionFindsDestructuring() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceComponent.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class ComponentTarget {
            operator fun component1(): String = "component"
          }

          fun useComponent(target: ComponentTarget): String {
            val (value) = target
            return value
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "component1", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun forLoopConventionFindsIterator() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceForLoop.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class ForLoopTarget {
            operator fun iterator(): Iterator<String> = emptyList<String>().iterator()
          }

          fun useForLoop(target: ForLoopTarget): Int {
            var count = 0
            for (value in target) count++
            return count
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "iterator", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun delegateConventionFindsGetValue() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceDelegate.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import kotlin.reflect.KProperty

          class DelegateTarget {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): String = "delegate"
          }

          val delegatedValue: String by DelegateTarget()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "getValue", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun unaryIncConventionFindsIncrement() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceInc.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class IncTarget {
            var value: Int = 0
            operator fun inc(): IncTarget {
              value++
              return this
            }
          }

          fun useInc(target: IncTarget) {
            var current = target
            current++
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "inc", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun delegateSetValueConventionFindsAssignment() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceDelegateSet.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import kotlin.reflect.KProperty

          class MutableDelegateTarget {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): String = "delegate"
            operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = Unit
          }

          var mutableDelegated: String by MutableDelegateTarget()

          fun assignDelegated() {
            mutableDelegated = "next"
          }
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "setValue", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun provideDelegateConventionFindsDelegation() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceProvideDelegate.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          import kotlin.reflect.KProperty

          class ProvidedTarget {
            operator fun getValue(thisRef: Any?, property: KProperty<*>): String = "provided"
          }

          class DelegateProvider {
            operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ProvidedTarget =
                ProvidedTarget()
          }

          val providedValue: String by DelegateProvider()
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "provideDelegate", includeDeclaration = true))
          })
          assertEquals(2, references.locations.size)
        }
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
      }
    }
  }

  @Test
  fun unboundSameNameFunctionIsNotConventionUsage() {
    withWorkspace {
      val file = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app/ReferenceUnboundOperator.kt")
      val content = """
          package com.tom.rv2ide.testing.kotlinfixture.app

          class UnboundTarget {
            fun plus(other: Int): Int = other
          }

          fun useUnbound(): Int = 1 + 2
      """.trimIndent() + "\n"
      Files.write(file, content.toByteArray(Charsets.UTF_8))
      openDocument(file, content, version = 1, revision = 1L)
      try {
        withRuntime(workspace) { runtime ->
          runAnalysis { runtime.onFileOpened(file) }
          val references = requireNotNull(runAnalysis {
            runtime.findReferences(request(file, content, "plus", includeDeclaration = true))
          })
          assertEquals(1, references.locations.size)
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
          fun useExplicitCompanion(): String = CompanionHost.Companion.companionValue()
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
          val explicitLine =
              LineIndex.from(content)
                  .indexToPosition(content.indexOf("CompanionHost.Companion.companionValue"))
                  .line
          val instanceLine =
              LineIndex.from(content).indexToPosition(content.indexOf("CompanionHost().instanceValue")).line
          assertTrue(references.locations.any { it.range.start.line == companionLine })
          assertTrue(references.locations.any { it.range.start.line == explicitLine })
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