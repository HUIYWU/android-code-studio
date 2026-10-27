package com.tom.rv2ide.testing.kotlinfixture.app

import kotlin.collections.ArrayDeque
import com.tom.rv2ide.testing.kotlinfixture.library.LibraryApi
import com.tom.rv2ide.testing.kotlinfixture.library.LibraryBase
import com.tom.rv2ide.testing.kotlinfixture.library.libraryExtension

class AppConsumer : LibraryBase() {
  override fun inheritedValue(): String = "app"

  fun consume(value: String): String {
    val api = LibraryApi()
    val javaInterop = JavaInterop()
    val inputLength = value.length
    val queue = ArrayDeque<String>()
    queue.addLast(value)
    return javaInterop.value(api.load(queue.removeFirst()).libraryExtension()) + inputLength
  }
}
