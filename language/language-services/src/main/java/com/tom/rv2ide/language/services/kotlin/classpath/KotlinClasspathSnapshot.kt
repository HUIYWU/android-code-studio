/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.language.services.kotlin.classpath

enum class KotlinClasspathSnapshotKind {
  ORDINARY_COMPILER,
  GRADLE_DSL_SCRIPT,
}

enum class KotlinClasspathSnapshotStatus {
  COMPLETE,
  UNRESOLVED,
  MISSING,
}

data class KotlinClasspathEntrySnapshot(
    val path: String,
    val ownerModulePaths: Set<String>,
    val variantNames: Set<String>,
    val origins: Set<String>,
    val exists: Boolean,
    val fingerprint: String,
)

data class KotlinClasspathSnapshot(
    val kind: KotlinClasspathSnapshotKind,
    val status: KotlinClasspathSnapshotStatus,
    val resolved: Boolean,
    val entries: List<KotlinClasspathEntrySnapshot>,
    val missingSources: Set<String>,
    val fingerprint: String,
)
