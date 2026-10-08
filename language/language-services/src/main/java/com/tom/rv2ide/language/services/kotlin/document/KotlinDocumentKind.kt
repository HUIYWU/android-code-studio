package com.tom.rv2ide.language.services.kotlin.document

import java.nio.file.Path

enum class KotlinDocumentKind {
  KOTLIN_SOURCE,
  GRADLE_KOTLIN_SCRIPT,
  UNSUPPORTED,
}

fun Path.kotlinDocumentKind(isGradleScript: Boolean = false): KotlinDocumentKind =
    when {
      fileName.toString().endsWith(".kt") -> KotlinDocumentKind.KOTLIN_SOURCE
      fileName.toString().endsWith(".kts") && isGradleScript -> KotlinDocumentKind.GRADLE_KOTLIN_SCRIPT
      else -> KotlinDocumentKind.UNSUPPORTED
    }
