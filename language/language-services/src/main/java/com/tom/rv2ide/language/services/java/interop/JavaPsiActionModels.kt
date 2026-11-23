package com.tom.rv2ide.language.services.java.interop

import com.tom.rv2ide.language.services.java.diagnostics.JavaAbstractMethodsDiagnostic
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path

class JavaPsiFieldsParams(
    @JvmField val file: Path,
    @JvmField val selection: Range,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val cancelChecker: ICancelChecker,
)
data class JavaPsiFieldInfo(
    @JvmField val name: String,
    @JvmField val typeText: String,
    @JvmField val isFinal: Boolean,
)

data class JavaPsiFieldsResult(
    @JvmField val file: Path,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val fields: List<JavaPsiFieldInfo>,
    @JvmField val selectionStartLine: Int,
    @JvmField val selectionStartColumn: Int,
    @JvmField val selectionEndLine: Int,
    @JvmField val selectionEndColumn: Int,
)

class JavaPsiGenerateConstructorParams(
    @JvmField val file: Path,
    @JvmField val selection: Range,
    @JvmField val selectedFields: Set<String>,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val indentationTabSize: Int,
    @JvmField val useSoftTabs: Boolean,
    @JvmField val cancelChecker: ICancelChecker,
)

enum class JavaPsiConstructorResultStatus {
  GENERATED,
  EMPTY_SELECTION,
  CONSTRUCTOR_EXISTS,
  UNSUPPORTED,
}

data class JavaPsiTextEdit(
    @JvmField val file: Path,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val startLine: Int,
    @JvmField val startColumn: Int,
    @JvmField val endLine: Int,
    @JvmField val endColumn: Int,
    @JvmField val newText: String,
)

data class JavaPsiConstructorResult(
    @JvmField val status: JavaPsiConstructorResultStatus,
    @JvmField val edit: JavaPsiTextEdit?,
)

class JavaPsiGenerateAccessorsParams(
    @JvmField val file: Path,
    @JvmField val selection: Range,
    @JvmField val selectedFields: Set<String>,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val indentationTabSize: Int,
    @JvmField val useSoftTabs: Boolean,
    @JvmField val cancelChecker: ICancelChecker,
)

enum class JavaPsiAccessorsResultStatus {
  GENERATED,
  EMPTY_SELECTION,
  UNSUPPORTED,
}

data class JavaPsiAccessorsResult(
    @JvmField val status: JavaPsiAccessorsResultStatus,
    @JvmField val edit: JavaPsiTextEdit?,
)

class JavaPsiGenerateToStringParams(
    @JvmField val file: Path,
    @JvmField val selection: Range,
    @JvmField val selectedFields: Set<String>,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val indentationTabSize: Int,
    @JvmField val useSoftTabs: Boolean,
    @JvmField val cancelChecker: ICancelChecker,
)

enum class JavaPsiToStringResultStatus {
  GENERATED,
  EMPTY_SELECTION,
  METHOD_EXISTS,
  UNSUPPORTED,
}

data class JavaPsiToStringResult(
    @JvmField val status: JavaPsiToStringResultStatus,
    @JvmField val edit: JavaPsiTextEdit?,
)

class JavaPsiOverrideMethodsParams(
    @JvmField val file: Path,
    @JvmField val selection: Range,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val recognizeKotlin: Boolean,
    @JvmField val cancelChecker: ICancelChecker,
)

data class JavaPsiOverrideMethodInfo(
    @JvmField val id: String,
    @JvmField val label: String,
)

data class JavaPsiOverrideMethodsResult(
    @JvmField val file: Path,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val methods: List<JavaPsiOverrideMethodInfo>,
    @JvmField val selectionStartLine: Int,
    @JvmField val selectionStartColumn: Int,
    @JvmField val selectionEndLine: Int,
    @JvmField val selectionEndColumn: Int,
    @JvmField val recognizeKotlin: Boolean,
)

class JavaPsiGenerateOverridesParams(
    @JvmField val file: Path,
    @JvmField val selection: Range,
    @JvmField val selectedMethodIds: Set<String>,
    @JvmField val documentVersion: Int,
    @JvmField val documentRevision: Long,
    @JvmField val recognizeKotlin: Boolean,
    @JvmField val indentationTabSize: Int,
    @JvmField val useSoftTabs: Boolean,
    @JvmField val cancelChecker: ICancelChecker,
)

class JavaPsiImplementAbstractMethodsParams internal constructor(
    internal val diagnostic: JavaAbstractMethodsDiagnostic,
    @JvmField val recognizeKotlin: Boolean,
    @JvmField val indentationTabSize: Int,
    @JvmField val useSoftTabs: Boolean,
    @JvmField val cancelChecker: ICancelChecker,
)

enum class JavaPsiOverridesResultStatus {
  GENERATED,
  EMPTY_SELECTION,
  UNSUPPORTED,
}

data class JavaPsiOverridesResult(
    @JvmField val status: JavaPsiOverridesResultStatus,
    @JvmField val edits: List<JavaPsiTextEdit>,
)
