package com.tom.rv2ide.language.services.kotlin.backend

import java.nio.file.Path

public data class KotlinBackendFileChange(
    val path: Path,
    val kind: Kind,
) {
  public enum class Kind {
    CREATED,
    MODIFIED,
    REMOVED,
  }
}