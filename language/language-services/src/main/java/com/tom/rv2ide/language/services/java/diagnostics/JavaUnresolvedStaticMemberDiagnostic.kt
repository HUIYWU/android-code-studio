package com.tom.rv2ide.language.services.java.diagnostics

import java.nio.file.Path

internal data class JavaUnresolvedStaticMemberDiagnostic(
    val file: Path,
    val documentVersion: Int,
    val documentRevision: Long,
    val referenceStart: Int,
    val referenceEnd: Int,
    val simpleName: String,
    val isMethod: Boolean,
)
