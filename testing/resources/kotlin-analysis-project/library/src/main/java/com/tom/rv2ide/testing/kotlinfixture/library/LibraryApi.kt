package com.tom.rv2ide.testing.kotlinfixture.library

/**
 * Stable library API used by Kotlin Analysis runtime fixtures.
 */
class LibraryApi {
  fun load(value: String): String = value
}

fun libraryExtension(value: String): String = value.trim()
