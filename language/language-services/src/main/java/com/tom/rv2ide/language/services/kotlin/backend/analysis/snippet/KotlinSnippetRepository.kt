package com.tom.rv2ide.language.services.kotlin.backend.analysis.snippet

import com.tom.rv2ide.lsp.snippets.ISnippet
import com.tom.rv2ide.lsp.snippets.SnippetParser

internal object KotlinSnippetRepository {

  @Volatile private var cachedSnippets: Map<KotlinSnippetScope, List<ISnippet>>? = null

  val snippets: Map<KotlinSnippetScope, List<ISnippet>>
    get() =
        cachedSnippets
            ?: synchronized(this) {
              cachedSnippets
                  ?: SnippetParser.parse("kotlin", KotlinSnippetScope.entries.toTypedArray()).also {
                    cachedSnippets = it
                  }
            }

  fun init() {
    snippets
  }
}