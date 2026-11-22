package com.tom.rv2ide.language.services.java.interop

import com.tom.rv2ide.lsp.models.MarkupContent

class JavaPsiHoverTarget(
    @JvmField val kind: Kind,
    @JvmField val content: MarkupContent,
) {
  enum class Kind {
    JAVA,
    KOTLIN_SOURCE,
  }
}
