/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.editor.ui

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.resolveAttr
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.StrongEmphasis

/** Renders LSP hover markup using Markwon and editor-theme-aware code highlighting. */
class HoverMarkdownRenderer(private val context: Context) {

  companion object {
    private const val MAX_HOVER_LENGTH = 8_000
    private const val MIN_TEXT_CONTRAST = 3.2
    private val ANDROID_RESOURCE_REFERENCE =
        Regex(
            "^([@?])(?:(android|[A-Za-z_][A-Za-z0-9_.]*):)?" +
                "([A-Za-z_][A-Za-z0-9_]*)/([A-Za-z_][A-Za-z0-9_.-]*)$"
        )
    private val FIRST_XML_CODE_BLOCK = Regex("```xml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
    private val ANDROID_ATTRIBUTE = Regex("^(android:)([A-Za-z_][A-Za-z0-9_]*)$")
    private val XML_WIDGET_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.]*$")
    private val NUMERIC_HTML_ENTITY = Regex("&(?:amp;)*#(?:([0-9]+)|[xX]([0-9a-fA-F]+));")
    private val JAVA_UNICODE_ESCAPE = Regex("\\\\u+([0-9a-fA-F]{4})")
  }

  private val backgroundColor = context.resolveAttr(R.attr.colorSurface)
  private val textColor = context.resolveAttr(R.attr.colorOnPrimaryContainer)
  private val outlineColor = context.resolveAttr(R.attr.colorOutline)
  // Deliberately use separated hues here. Material primary/secondary colors are often too similar
  // to make the four resource-reference components distinguishable at a glance.
  private val resourceMarkerColor =
      readableAccent(if (isDarkTheme()) 0xFFE1BEE7.toInt() else 0xFF7B1FA2.toInt())
  private val resourcePackageColor =
      readableAccent(if (isDarkTheme()) 0xFF80CBC4.toInt() else 0xFF00695C.toInt())
  private val resourceTypeColor =
      readableAccent(if (isDarkTheme()) 0xFFFFCC80.toInt() else 0xFFE65100.toInt())
  private val resourceEntryColor =
      readableAccent(if (isDarkTheme()) 0xFF82B1FF.toInt() else 0xFF0D47A1.toInt())
  private val inlineCodeBackground = blend(backgroundColor, textColor, 0.10f)

  private val markwon: Markwon =
      Markwon.builder(context)
          .usePlugin(StrikethroughPlugin.create())
          .usePlugin(
              object : AbstractMarkwonPlugin() {
                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                  builder.on(FencedCodeBlock::class.java) { visitor, block ->
                    val code = block.literal.trimEnd()
                    visitor.builder().append(highlightCode(block.info, code))
                  }
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                  builder
                      .setFactory(Emphasis::class.java) { _, _ -> StyleSpan(android.graphics.Typeface.ITALIC) }
                      .setFactory(StrongEmphasis::class.java) { _, _ -> StyleSpan(android.graphics.Typeface.BOLD) }
                      .setFactory(BlockQuote::class.java) { _, _ -> QuoteSpan(outlineColor) }
                      .setFactory(Strikethrough::class.java) { _, _ -> StrikethroughSpan() }
                      .setFactory(Code::class.java) { _, _ ->
                        arrayOf<Any>(BackgroundColorSpan(inlineCodeBackground), TypefaceSpan("monospace"))
                      }
                      .setFactory(ListItem::class.java) { _, _ -> BulletSpan() }
                }
              }
          )
          .build()

  fun render(content: MarkupContent): CharSequence {
    val normalized =
        decodeJavaUnicodeEscapes(decodeNumericHtmlEntities(content.value))
            .replace("\r\n", "\n")
            .trim()
            .take(MAX_HOVER_LENGTH)
    if (normalized.isBlank()) return ""

    return when (content.kind) {
      MarkupKind.MARKDOWN -> highlightXmlHoverSymbol(markwon.toMarkdown(normalized), normalized)
      MarkupKind.PLAIN -> normalized
    }
  }

  private fun decodeJavaUnicodeEscapes(text: String): String =
      JAVA_UNICODE_ESCAPE.replace(text) { match ->
        match.groups[1]?.value?.toInt(16)?.toChar()?.toString() ?: match.value
      }

  private fun decodeNumericHtmlEntities(text: String): String =
      NUMERIC_HTML_ENTITY.replace(text) { match ->
        val radix = if (match.groups[1] == null) 16 else 10
        val digits = match.groups[1]?.value ?: match.groups[2]?.value ?: return@replace match.value
        val codePoint = digits.toIntOrNull(radix) ?: return@replace match.value
        if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else match.value
      }

  private fun highlightXmlHoverSymbol(rendered: CharSequence, markdown: String): CharSequence {
    val symbol =
        FIRST_XML_CODE_BLOCK.find(markdown)?.groupValues?.getOrNull(1)?.trim()
            ?: markdown.lineSequence().firstOrNull()?.trim()
            ?: return rendered
    val symbolStart = rendered.toString().indexOf(symbol)
    if (symbolStart < 0) return rendered
    val builder = SpannableStringBuilder(rendered)

    ANDROID_RESOURCE_REFERENCE.matchEntire(symbol)?.let { match ->
      // Groups are marker, optional package, type, and entry.
      colorMatchGroup(builder, symbolStart, match, 1, resourceMarkerColor)
      colorMatchGroup(builder, symbolStart, match, 2, resourcePackageColor)
      colorMatchGroup(builder, symbolStart, match, 3, resourceTypeColor)
      colorMatchGroup(builder, symbolStart, match, 4, resourceEntryColor)
      return builder
    }

    ANDROID_ATTRIBUTE.matchEntire(symbol)?.let { match ->
      // Framework attributes have the same package/name visual vocabulary as qualified resources.
      colorMatchGroup(builder, symbolStart, match, 1, resourcePackageColor)
      colorMatchGroup(builder, symbolStart, match, 2, resourceEntryColor)
      return builder
    }

    if (XML_WIDGET_NAME.matches(symbol)) {
      // API hover only emits a widget after WidgetTable has validated it as a framework layout tag.
      builder.setSpan(
          ForegroundColorSpan(resourceTypeColor),
          symbolStart,
          symbolStart + symbol.length,
          Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
    }
    return builder
  }

  private fun colorMatchGroup(
      builder: SpannableStringBuilder,
      symbolStart: Int,
      match: MatchResult,
      groupIndex: Int,
      color: Int,
  ) {
    val group = match.groups[groupIndex] ?: return
    val start = symbolStart + group.range.first
    val end = symbolStart + group.range.last + 1
    if (start < 0 || end > builder.length || start >= end) return
    builder.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
  }

  private fun highlightCode(language: String?, code: String): CharSequence {
    val builder = SpannableStringBuilder(code)
    if (code.isEmpty()) return builder

    builder.setSpan(TypefaceSpan("monospace"), 0, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    // Do not paint fenced blocks. KLS wraps most declarations in a fenced code block, so a
    // full-range background makes most hover text look shadowed. Inline code remains shaded.

    val normalizedLanguage = language?.trim()?.lowercase().orEmpty()
    val isKotlin = normalizedLanguage in setOf("", "kotlin", "kt", "kts")
    val isJava = normalizedLanguage in setOf("java", "jav")
    if (!isKotlin && !isJava) {
      return builder
    }

    val darkTheme = isDarkTheme()
    val keywordColor = readableAccent(if (darkTheme) 0xFF82B1FF.toInt() else 0xFF0D47A1.toInt())
    val typeColor = readableAccent(if (darkTheme) 0xFFFFCC80.toInt() else 0xFFE65100.toInt())
    val stringColor = readableAccent(if (darkTheme) 0xFFA5D6A7.toInt() else 0xFF1B5E20.toInt())
    val commentColor = readableMuted(if (darkTheme) 0xFFB0BEC5.toInt() else 0xFF546E7A.toInt())
    val functionColor = readableAccent(if (darkTheme) 0xFF80CBC4.toInt() else 0xFF00695C.toInt())
    val annotationColor = readableAccent(if (darkTheme) 0xFFE1BEE7.toInt() else 0xFF6A1B9A.toInt())
    val numberColor = readableAccent(if (darkTheme) 0xFFFFAB91.toInt() else 0xFFBF360C.toInt())
    val symbolColor = readableMuted(if (darkTheme) 0xFFCFD8DC.toInt() else 0xFF455A64.toInt())
    val occupied = BooleanArray(code.length)

    fun applyColor(pattern: Regex, color: Int) {
      pattern.findAll(code).forEach { match ->
        val start = match.range.first
        val endExclusive = match.range.last + 1
        if (start < 0 || endExclusive > code.length || (start until endExclusive).any { occupied[it] }) {
          return@forEach
        }
        builder.setSpan(
            ForegroundColorSpan(color),
            start,
            endExclusive,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        for (index in start until endExclusive) occupied[index] = true
      }
    }

    applyColor(Regex("""//.*?$|/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)), commentColor)
    applyColor(Regex("""\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'"""), stringColor)
    applyColor(Regex("""@[A-Za-z_][A-Za-z0-9_]*"""), annotationColor)
    val keywordPattern =
        if (isJava) {
          Regex(
              """\b(class|interface|enum|public|private|protected|static|final|abstract|extends|implements|new|return|if|else|for|while|switch|case|try|catch|finally|throw|throws|void|boolean|byte|short|int|long|float|double|char|null|true|false)\b""",
          )
        } else {
          Regex(
              """\b(fun|val|var|class|interface|object|return|if|else|for|while|when|in|is|as|private|protected|public|internal|override|suspend|inline|data|sealed|enum|companion|constructor|init|by|where|null|true|false)\b""",
          )
        }
    applyColor(keywordPattern, keywordColor)
    applyColor(Regex("""\b\d+(?:_\d+)*(?:\.\d+)?(?:[eE][+-]?\d+)?[fFdDlL]?\b"""), numberColor)
    applyColor(Regex("""\b([A-Z][A-Za-z0-9_]*)\b"""), typeColor)
    applyColor(Regex("""\b([a-zA-Z_][A-Za-z0-9_]*)\s*(?=\()"""), functionColor)
    applyColor(Regex("""[<>?:=!,.|&+\-*/%]+"""), symbolColor)
    return builder
  }

  private fun readableMuted(candidate: Int): Int =
      if (contrastRatio(candidate, backgroundColor) >= MIN_TEXT_CONTRAST) candidate else textColor

  private fun readableAccent(candidate: Int): Int {
    if (contrastRatio(candidate, backgroundColor) >= MIN_TEXT_CONTRAST) return candidate
    val shifted = shiftTowards(candidate, if (isDarkTheme()) 0.18f else -0.18f)
    return if (contrastRatio(shifted, backgroundColor) >= MIN_TEXT_CONTRAST) shifted else textColor
  }

  private fun shiftTowards(color: Int, factor: Float): Int {
    val clamped = factor.coerceIn(-1f, 1f)
    fun channel(value: Int): Int {
      val target = if (clamped >= 0f) 255 else 0
      return (value + ((target - value) * kotlin.math.abs(clamped))).toInt().coerceIn(0, 255)
    }
    return Color.argb(255, channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
  }

  private fun contrastRatio(foreground: Int, background: Int): Double {
    val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
  }

  private fun relativeLuminance(color: Int): Double {
    fun channel(value: Int): Double {
      val normalized = value / 255.0
      return if (normalized <= 0.03928) normalized / 12.92
      else Math.pow((normalized + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(Color.red(color)) +
        0.7152 * channel(Color.green(color)) +
        0.0722 * channel(Color.blue(color))
  }

  private fun isDarkTheme(): Boolean {
    val mode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
  }

  private fun blend(background: Int, foreground: Int, amount: Float): Int {
    val fraction = amount.coerceIn(0f, 1f)
    fun channel(bg: Int, fg: Int) = (bg + ((fg - bg) * fraction)).toInt().coerceIn(0, 255)
    return Color.rgb(
        channel(Color.red(background), Color.red(foreground)),
        channel(Color.green(background), Color.green(foreground)),
        channel(Color.blue(background), Color.blue(foreground)),
    )
  }
}