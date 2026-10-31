@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    org.jetbrains.kotlin.analysis.api.KaNonPublicApi::class,
    org.jetbrains.kotlin.psi.KtNonPublicApi::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.findKDoc
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.com.intellij.psi.PsiDocCommentOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.kdoc.psi.api.KDocCommentDescriptor
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.psi.KtDeclaration

internal fun KaSession.documentationText(
    symbol: KaDeclarationSymbol,
    sourceDeclaration: KtDeclaration?,
): String {
  val descriptor = sourceDeclaration?.findKDoc() ?: symbol.findKDoc()
  if (descriptor != null) {
    val text = renderKDoc(descriptor)
    if (text.isNotEmpty()) return text
  }
  return javadocText(symbol.psi)
}

private fun renderKDoc(descriptor: KDocCommentDescriptor): String {
  val entries = mutableListOf<String>()
  val seen = HashSet<String>()

  fun addEntry(name: String?, subject: String?, content: String) {
    val key = "${name.orEmpty()}|${subject.orEmpty()}|$content"
    if (!seen.add(key)) return
    if (name == null) {
      if (content.isNotEmpty()) entries += content
      return
    }
    val label = if (subject.isNullOrEmpty()) "@$name" else "@$name $subject"
    entries += if (content.isEmpty()) "`$label`" else "`$label` — $content"
  }

  fun collect(tag: KDocTag) {
    addEntry(tag.getName(), tag.getSubjectName(), tag.getContent().trim())
    tag.children.filterIsInstance<KDocTag>().forEach { collect(it) }
  }

  collect(descriptor.primaryTag)
  descriptor.additionalSections.forEach(::collect)
  return entries.joinToString("\n\n")
}

private fun javadocText(psi: PsiElement?): String {
  val owner = psi as? PsiDocCommentOwner ?: return ""
  val raw = owner.docComment?.text?.trim().orEmpty()
  if (raw.isEmpty()) return ""
  var body = raw
  if (body.startsWith("/**")) body = body.removePrefix("/**")
  if (body.endsWith("*/")) body = body.removeSuffix("*/")
  return body
      .lines()
      .map { line ->
        var stripped = line.trimStart()
        if (stripped.startsWith("*")) {
          stripped = stripped.substring(1)
          if (stripped.startsWith(" ")) stripped = stripped.substring(1)
        }
        stripped
      }
      .joinToString("\n")
      .trim()
}