package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Contracts for the real Tree-sitter Kotlin structured path.
 *
 * These tests deliberately call [KotlinJvmSyntaxParser] directly. They must not silently pass via
 * the ABI generator's text fallback: when the host native grammar is unavailable, the failure
 * message should make that environment problem explicit.
 */
class KotlinJvmSyntaxParserTest {

  companion object {
    @Volatile private var nativeLoadAttempted = false
    @Volatile private var nativeLoadFailure: Throwable? = null

    @Synchronized
    private fun loadNativeLibraries(): Throwable? {
      if (!nativeLoadAttempted) {
        nativeLoadAttempted = true
        nativeLoadFailure =
            try {
              TreeSitter.loadLibrary()
              System.loadLibrary("tree-sitter-kotlin")
              null
            } catch (error: Throwable) {
              error
            }
      }
      return nativeLoadFailure
    }
  }

  @Test
  fun structured_nativeKotlinGrammarIsAvailable() {
    val loadFailure = loadNativeLibraries()
    assertNull(
        "Unable to explicitly load Tree-sitter core/Kotlin native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure)
    val status = KotlinJvmSyntaxParser.parseStatus("fun value(): Int = 1")
    assertTrue("Tree-sitter Kotlin structured parser unavailable after explicit load: $status", status.available)
  }

  @Test
  fun structured_usesFwcd038BindingPatternNodes() {
    val loadFailure = loadNativeLibraries()
    assertNull(
        "Unable to explicitly load Tree-sitter core/Kotlin native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure)
    val source =
        """
        package sample

        val topLevel: Int = 1
        class Profile(val id: Int, var name: String)
        """.trimIndent()

    TSParser.create().use { parser ->
      parser.language = TSLanguageKotlin.getInstance()
      parser.parseString(source).use { tree ->
        assertNotNull("tree-sitter-kotlin returned no syntax tree", tree)
        val root = tree.rootNode
        assertTrue(
            "Expected fwcd tree-sitter-kotlin 0.3.8+ AST node 'binding_pattern_kind'. " +
                "The host libtree-sitter-kotlin may still be built from 0.3.6. Root: $root",
            containsNodeType(root, "binding_pattern_kind"))
      }
    }
  }

  @Test
  fun structured_projectsTopLevelJvmNameAcrossAnnotationLayouts() {
    val separateLine =
        """
        package sample

        @JvmName("loadValue")
        fun load(): String = "value"
        """.trimIndent()
    val sameLine =
        """
        package sample

        @JvmName("fetchValue") fun fetch(id: Int): String = id.toString()
        """.trimIndent()

    val load = requireTopLevelMembers(separateLine).single { it.name == "load" }
    assertEquals("loadValue", load.jvmName)
    assertEquals("String", load.declaredType)
    assertTrue(load.functionBodyPresent)
    assertNameRange(separateLine, load.nameOffset, load.nameLength, "load")

    val fetch = requireTopLevelMembers(sameLine).single { it.name == "fetch" }
    assertEquals("fetchValue", fetch.jvmName)
    assertEquals(1, fetch.parameterList.size)
    assertNameRange(sameLine, fetch.nameOffset, fetch.nameLength, "fetch")
  }

  @Test
  fun structured_projectsExtensionReceivers() {
    val source =
        """
        package sample

        @JvmName("renderText")
        fun String.render(): String = this

        val String.renderLength: Int
          get() = length
        """.trimIndent()

    val members = requireTopLevelMembers(source)
    val function = members.single { it.name == "render" }
    assertTrue(function.function())
    assertEquals("String", function.receiverType)
    assertEquals("renderText", function.jvmName)

    val property = members.single { it.name == "renderLength" }
    assertFalse(property.function())
    assertEquals("String", property.receiverType)
    assertEquals("Int", property.declaredType)
    assertTrue(
        "Explicit getter body must be preserved by the structured Kotlin AST.",
        property.functionBodyPresent)
  }

  @Test
  fun structured_projectsJvmAccessorUseSiteAnnotations() {
    val source =
        """
        package sample

        @get:JvmName("readMode")
        @set:JvmName("writeMode")
        var mode: String = "default"
        val stable: Int = 1
        """.trimIndent()

    val members = requireTopLevelMembers(source)
    val mode = members.single { it.name == "mode" }
    assertFalse(mode.function())
    assertTrue(mode.mutableProperty)
    assertFalse(mode.readOnlyProperty)
    assertEquals("readMode", mode.getterJvmName)
    assertEquals("writeMode", mode.setterJvmName)
    assertNameRange(source, mode.nameOffset, mode.nameLength, "mode")

    val stable = members.single { it.name == "stable" }
    assertTrue(stable.readOnlyProperty)
    assertFalse(stable.mutableProperty)
    assertNameRange(source, stable.nameOffset, stable.nameLength, "stable")
  }

  @Test
  fun structured_preservesNestedOwnerChainAndDirectMemberBoundaries() {
    val source =
        """
        package sample

        class Outer {
          fun outerLabel(): String = "outer"

          fun createLocal() {
            class Local
          }

          val factory = {
            class LambdaLocal
          }

          class Nested(val value: String) {
            fun nestedLabel(): String = value
            class Deep(val count: Int)
          }

          interface Listener {
            fun onChanged(value: Int)
          }

          object Defaults {
            val enabled: Boolean = true
          }
        }
        """.trimIndent()

    val outer = requireTopLevelType(source, "Outer")
    assertEquals(listOf("Nested", "Listener", "Defaults"), outer.nestedTypes.map { it.name })
    assertEquals(listOf("outerLabel", "createLocal", "factory"), outer.members.map { it.name })
    assertFalse(outer.nestedTypes.any { it.name == "Local" || it.name == "LambdaLocal" })

    val nested = outer.nestedTypes.single { it.name == "Nested" }
    assertEquals(listOf("Deep"), nested.nestedTypes.map { it.name })
    assertEquals(listOf("nestedLabel"), nested.members.map { it.name })
    assertNameRange(source, nested.nameOffset, nested.nameLength, "Nested")

    val deep = nested.nestedTypes.single()
    assertEquals("Deep", deep.name)
    assertTrue(deep.nestedTypes.isEmpty())
    assertNameRange(source, deep.nameOffset, deep.nameLength, "Deep")

    val listener = outer.nestedTypes.single { it.name == "Listener" }
    assertTrue(listener.interfaceType)
    val callback = listener.members.single { it.name == "onChanged" }
    assertNull(callback.declaredType)
    assertFalse(callback.functionBodyPresent)

    val defaults = outer.nestedTypes.single { it.name == "Defaults" }
    assertTrue(defaults.objectType())
    assertEquals(listOf("enabled"), defaults.members.map { it.name })
  }

  @Test
  fun structured_collectsOrderedWhereClauseTypeParameterBounds() {
    val source =
        """
        package sample

        class MultipleBounds<T>(val value: T)
          where T : CharSequence, T : Comparable<T> {
          fun <R> multiple(value: R): R
            where R : CharSequence, R : Comparable<R> = value
        }
        """.trimIndent()

    val type = requireTopLevelType(source, "MultipleBounds")
    val classParameter = type.typeParameters.single { it.name == "T" }
    assertEquals(listOf("CharSequence", "Comparable<T>"), classParameter.upperBounds)
    assertEquals("CharSequence", classParameter.upperBound)

    val method = type.members.single { it.name == "multiple" }
    val methodParameter = method.typeParameters.single { it.name == "R" }
    assertEquals(listOf("CharSequence", "Comparable<R>"), methodParameter.upperBounds)
    assertEquals("CharSequence", methodParameter.upperBound)
  }

  @Test
  fun structured_keepsCompanionMembersSeparateFromHostMembers() {
    val source =
        """
        package sample

        class Host {
          fun instanceMethod(): String = "instance"

          companion object {
            @JvmStatic
            fun create(): Host = Host()

            val version: Int = 1
          }
        }
        """.trimIndent()

    val host = requireTopLevelType(source, "Host")
    assertEquals(listOf("instanceMethod"), host.members.map { it.name })
    assertNotNull("Structured parser must locate the companion body", host.companionBody)
    assertEquals(listOf("create", "version"), host.companionMembers.map { it.name })
    assertTrue(host.companionMembers.single { it.name == "create" }.jvmStatic)
  }

  @Test
  fun structured_projectsPrimaryAndSecondaryConstructors() {
    val source =
        """
        package sample

        class Profile @JvmOverloads internal constructor(
          val id: Int,
          var name: String = "default"
        ) {
          protected constructor(id: Long) : this(id.toInt())
        }
        """.trimIndent()

    val profile = requireTopLevelType(source, "Profile")
    assertTrue(profile.primaryConstructorPresent)
    assertEquals("public", profile.constructorVisibility)
    assertTrue(profile.constructorJvmOverloads)
    assertEquals(listOf("id", "name"), profile.constructorParameters.map { it.name })
    val id = profile.constructorParameters.single { it.name == "id" }
    assertTrue(id.property)
    assertFalse(id.mutableProperty)
    assertTrue(profile.constructorParameters.single { it.name == "name" }.mutableProperty)
    assertTrue(profile.constructorParameters.single { it.name == "name" }.defaultValue)

    assertEquals(1, profile.secondaryConstructors.size)
    val secondary = profile.secondaryConstructors.single()
    assertEquals("protected", secondary.visibility)
    assertEquals(listOf("Long"), secondary.parameters.map { it.type })
  }

  private fun containsNodeType(node: TSNode, expectedType: String): Boolean {
    if (node.type == expectedType) return true
    for (index in 0 until node.childCount) {
      if (containsNodeType(node.getChild(index), expectedType)) return true
    }
    return false
  }
private fun requireTopLevelMembers(source: String): List<KotlinJvmSyntaxParser.MemberSyntax> {
    val loadFailure = loadNativeLibraries()
    assumeTrue(
        "Unable to explicitly load Tree-sitter native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure == null)
    val status = KotlinJvmSyntaxParser.parseStatus(source)
    assumeTrue("Tree-sitter Kotlin structured parser unavailable: $status", status.available)
    val members = KotlinJvmSyntaxParser.findTopLevelMembers(source)
    assertNotNull(
        "Tree-sitter reported available but did not produce structured top-level members: $status",
        members)
    return members!!
  }

  private fun requireTopLevelType(
      source: String,
      name: String
  ): KotlinJvmSyntaxParser.TypeSyntax {
    val loadFailure = loadNativeLibraries()
    assumeTrue(
        "Unable to explicitly load Tree-sitter native libraries: " +
            nativeFailureDescription(loadFailure),
        loadFailure == null)
    val status = KotlinJvmSyntaxParser.parseStatus(source)
    assumeTrue("Tree-sitter Kotlin structured parser unavailable: $status", status.available)
    val type = KotlinJvmSyntaxParser.findTopLevelType(source, name)
    assertNotNull(
        "Tree-sitter reported available but did not produce top-level type $name: $status",
        type)
    return type!!
  }

  private fun nativeFailureDescription(error: Throwable?): String {
    if (error == null) return "none"
    return error.javaClass.name + ": " + (error.message ?: error.toString())
  }

  private fun assertNameRange(source: String, offset: Int, length: Int, expected: String) {
    assertTrue("Expected a non-negative source offset for $expected, got $offset", offset >= 0)
    assertEquals(expected, source.substring(offset, offset + length))
  }
}
