package com.tom.rv2ide.lsp.java.kotlin

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** File-system coverage for cross-file generic typealias visibility. */
class KotlinJvmTypeIndexGenericAliasVisibilityTest {

  @Test
  fun visibleGenericTypeAliases_enforcesPackageImportVisibilityAndSupportedShape() {
    val root = Files.createTempDirectory("kotlin-generic-alias-index")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }

      writeSource(shared.resolve("PublicAliases.kt"), """
        package shared
        typealias ImportedList<T> = List<T>
        typealias ImportedMap<K, V> = Map<K, V>
        private typealias PrivateList<T> = List<T>
        internal typealias InternalList<T> = List<T>
        typealias NullableList<T> = List<T>?
        typealias NestedList<T> = List<List<T>>
        typealias Callback<T> = (T) -> Unit
      """.trimIndent())
      writeSource(consumer.resolve("SamePackageAliases.kt"), """
        package consumer
        typealias LocalSet<T> = Set<T>
      """.trimIndent())
      val consumerFile = consumer.resolve("Api.kt")
      writeSource(consumerFile, """
        package consumer
        import shared.ImportedList
        import shared.ImportedMap
        import shared.*
        fun use(value: ImportedList<String>): LocalSet<String> = emptySet()
      """.trimIndent())

      val aliases = KotlinJvmTypeIndex.visibleGenericTypeAliases(
        listOf(root.toFile()), consumerFile)

      assertEquals(listOf("T"), aliases.getValue("ImportedList").parameters)
      assertEquals("List", aliases.getValue("ImportedList").targetRawType)
      assertEquals(listOf("K", "V"), aliases.getValue("ImportedMap").parameters)
      assertTrue(aliases.containsKey("LocalSet"))
      assertFalse(aliases.containsKey("PrivateList"))
      assertFalse(aliases.containsKey("InternalList"))
      assertFalse(aliases.containsKey("NullableList"))
      assertFalse(aliases.containsKey("NestedList"))
      assertFalse(aliases.containsKey("Callback"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun visibleGenericTypeAliases_doesNotUseCrossPackageWildcardImport() {
    val root = Files.createTempDirectory("kotlin-generic-alias-wildcard")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      writeSource(shared.resolve("Aliases.kt"), """
        package shared
        typealias WildcardOnly<T> = List<T>
      """.trimIndent())
      val consumerFile = consumer.resolve("Api.kt")
      writeSource(consumerFile, """
        package consumer
        import shared.*
        fun untouched(): String = "ok"
      """.trimIndent())

      val aliases = KotlinJvmTypeIndex.visibleGenericTypeAliases(
        listOf(root.toFile()), consumerFile)

      assertFalse(aliases.containsKey("WildcardOnly"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun writeSource(path: Path, source: String) {
    Files.write(path, source.toByteArray(Charsets.UTF_8))
  }
}