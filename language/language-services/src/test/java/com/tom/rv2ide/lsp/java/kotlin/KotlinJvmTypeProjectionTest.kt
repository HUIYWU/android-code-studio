package com.tom.rv2ide.lsp.java.kotlin

import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KotlinJvmTypeProjectionTest {

  private val typeName = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")

  @Test
  fun parseTypeApplication_splitsTopLevelArgumentsOnly() {
    val application = KotlinJvmTypeProjection.parseTypeApplication("Map<String, List<Int>>")!!
    assertEquals("Map", application.rawType)
    assertEquals(listOf("String", " List<Int>"), application.arguments)
    assertNull(KotlinJvmTypeProjection.parseTypeApplication("Map<String, List<Int>"))
  }

  @Test
  fun expandGenericAlias_substitutesSimpleArgumentsConservatively() {
    val application = KotlinJvmTypeProjection.parseTypeApplication("PairBox<String, Long>")!!
    assertEquals(
      "Map<String, Long>",
      KotlinJvmTypeProjection.expandGenericAlias(
        application, listOf("K", "V"), "Map<K, V>", typeName))

    val nested = KotlinJvmTypeProjection.parseTypeApplication("PairBox<List<String>, Long>")!!
    assertNull(KotlinJvmTypeProjection.expandGenericAlias(
      nested, listOf("K", "V"), "Map<K, V>", typeName))
  }

  @Test
  fun javaCollectionType_isSharedAcrossGeneratorAndNavigator() {
    assertEquals("java.util.List", KotlinJvmTypeProjection.javaCollectionType("List"))
    assertEquals(
      "java.util.List",
      KotlinJvmTypeProjection.javaCollectionType("kotlin.collections.MutableList"))
    assertEquals("java.lang.Iterable", KotlinJvmTypeProjection.javaCollectionType("Iterable"))
    assertNull(KotlinJvmTypeProjection.javaCollectionType("UnknownCollection"))
  }
}