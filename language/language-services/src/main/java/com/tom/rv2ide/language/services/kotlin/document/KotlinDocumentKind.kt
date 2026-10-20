package com.tom.rv2ide.language.services.kotlin.document

import java.nio.file.Path

enum class KotlinDocumentKind {
  KOTLIN_SOURCE,
  GRADLE_KOTLIN_SCRIPT,
  PLAIN_KOTLIN_SCRIPT,
  UNSUPPORTED,
}

fun Path.kotlinDocumentKind(): KotlinDocumentKind {
  val fileName = fileName?.toString()?.lowercase() ?: return KotlinDocumentKind.UNSUPPORTED
  return when {
    fileName.endsWith(".gradle.kts") -> KotlinDocumentKind.GRADLE_KOTLIN_SCRIPT
    fileName.endsWith(".kt") -> KotlinDocumentKind.KOTLIN_SOURCE
    fileName.endsWith(".kts") -> KotlinDocumentKind.PLAIN_KOTLIN_SCRIPT
    else -> KotlinDocumentKind.UNSUPPORTED
  }
}

fun Path.isKotlinLanguageDocument(): Boolean =
    kotlinDocumentKind() != KotlinDocumentKind.UNSUPPORTED
