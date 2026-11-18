package com.tom.rv2ide.testing.kotlinfixture.library

/**
 * Stable library API used by Kotlin Analysis runtime fixtures.
 */
class LibraryApi {
  fun load(value: String): String = value
}

fun libraryExtension(value: String): String = value.trim()

@JvmName("renamedLibrary")
fun originalLibrary(value: String): String = value.trim()

class InteropOverloads(val label: String) {
  fun pick(value: String): String = label + value

  fun pick(value: Int): String = label + value

  fun String.decorated(suffix: String): String = this + suffix

  private fun hidden(): String = label

  class Nested

  inner class Inner

  companion object {
    @JvmField val TOKEN: String = "token"
  }
}
