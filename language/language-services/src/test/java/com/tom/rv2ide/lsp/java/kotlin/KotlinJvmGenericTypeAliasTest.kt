package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TreeSitter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for generic aliases supplied by the cross-file visibility index. */
class KotlinJvmGenericTypeAliasTest {

  @Test
  fun generate_projectsVisibleCrossFileGenericAliases() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")

    val source =
      """
      package consumer

      import shared.ManualListAlias
      import shared.ManualMapAlias

      fun result(): ManualListAlias<String> = emptyList()
      fun accept(value: ManualListAlias<Int>): Unit {}
      fun lookup(value: ManualMapAlias<String, Long>): ManualMapAlias<String, Long> = emptyMap()
      """.trimIndent()

    val visibleAliases = mapOf(
      "ManualListAlias" to KotlinJvmTypeIndex.GenericTypeAlias(
        listOf("T"), "List", listOf("T")),
      "ManualMapAlias" to KotlinJvmTypeIndex.GenericTypeAlias(
        listOf("K", "V"), "Map", listOf("K", "V"))
    )

    val stub = KotlinJvmAbiStubGenerator.generate(
      "consumer.CrossFileAliasApiKt",
      "CrossFileAliasApi.kt",
      source,
      emptySet(),
      emptyMap(),
      visibleAliases
    )

    assertNotNull(stub)
    assertTrue(stub!!.contains("java.util.List<String> result()"))
    assertTrue(stub.contains("void accept(java.util.List<Integer> value)"))
    assertTrue(
      stub.contains("java.util.Map<String, Long> lookup(java.util.Map<String, Long> value)")
    )
  }
}
