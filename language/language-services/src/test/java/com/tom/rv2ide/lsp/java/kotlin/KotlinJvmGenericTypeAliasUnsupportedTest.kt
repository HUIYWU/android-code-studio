package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TreeSitter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unsupported generic aliases must degrade to valid Java Object signatures. */
class KotlinJvmGenericTypeAliasUnsupportedTest {

  @Test
  fun generate_fallsBackToObjectForUnsupportedImportedGenericAliases() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")

    val source =
      """
      package consumer

      import shared.ManualNullableAlias
      import shared.ManualNestedAlias
      import shared.ManualCallbackAlias

      fun nullable(value: ManualNullableAlias<String>): ManualNullableAlias<String> = value
      fun nested(value: ManualNestedAlias<String>): ManualNestedAlias<String> = value
      fun callback(value: ManualCallbackAlias<String>): ManualCallbackAlias<String> = value
      """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate(
      "consumer.UnsupportedAliasApiKt",
      "UnsupportedAliasApi.kt",
      source,
      emptySet(),
      emptyMap(),
      emptyMap()
    )

    assertNotNull(stub)
    assertTrue(stub!!.contains("Object nullable(Object value)"))
    assertTrue(stub.contains("Object nested(Object value)"))
    assertTrue(stub.contains("Object callback(Object value)"))
    assertTrue(!stub.contains("ManualNullableAlias<String>"))
    assertTrue(!stub.contains("ManualNestedAlias<String>"))
    assertTrue(!stub.contains("ManualCallbackAlias<String>"))
  }
}
