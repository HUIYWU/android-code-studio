package com.tom.rv2ide.language.services.kotlin.backend.analysis.snippet

import com.tom.rv2ide.lsp.snippets.ISnippetScope

internal enum class KotlinSnippetScope(override val filename: String) : ISnippetScope {
  TOP_LEVEL("top-level"),
  MEMBER("member"),
  LOCAL("local"),
  GLOBAL("global"),
}