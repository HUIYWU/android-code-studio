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

package com.tom.rv2ide.artificial.render

import android.content.Context
import android.content.res.Configuration
import android.text.style.BackgroundColorSpan
import android.text.style.TypefaceSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock

/**
 * Renders the AI agent's textual (non file-modification) replies as Markdown.
 *
 * The chat output used to assign the raw provider response to a plain `TextView`, so `**bold**`,
 * headings, lists and fenced code blocks were displayed as literal markup. Rendering is best-effort:
 * if Markwon fails, the original text is returned so the agent's answer is never lost.
 *
 * Use [shared] from list items. The transcript is a `RecyclerView`, so a renderer built per adapter
 * call would rebuild the Markwon parser and its plugin chain on every scroll. Only the theme is
 * captured at construction time, and it is read from the application context, so one instance is
 * valid for the whole process.
 */
class AIMarkdownRenderer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val isDarkTheme: Boolean =
        (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // Alpha overlays instead of theme attributes: they read correctly on both light and dark
    // surfaces without pulling the editor theme into the chat layer.
    private val inlineCodeBackground: Int = if (isDarkTheme) 0x33FFFFFF else 0x14000000
    private val codeBlockBackground: Int = if (isDarkTheme) 0x24FFFFFF else 0x0F000000

    private val markwon: Markwon =
        Markwon.builder(appContext)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                        builder
                            .setFactory(Code::class.java) { _, _ ->
                                arrayOf<Any>(
                                    BackgroundColorSpan(inlineCodeBackground),
                                    TypefaceSpan("monospace"),
                                )
                            }
                            .setFactory(FencedCodeBlock::class.java) { _, _ ->
                                arrayOf<Any>(
                                    TypefaceSpan("monospace"),
                                    BackgroundColorSpan(codeBlockBackground),
                                )
                            }
                    }
                }
            )
            .build()

    fun render(text: String): CharSequence {
        if (text.isBlank()) return text
        return try {
            markwon.toMarkdown(text)
        } catch (e: Exception) {
            text
        }
    }

    companion object {
        @Volatile
        private var instance: AIMarkdownRenderer? = null

        /**
         * Process-wide renderer. See the class KDoc for why this is shared rather than constructed
         * per call site.
         */
        fun shared(context: Context): AIMarkdownRenderer {
            return instance ?: synchronized(this) {
                instance ?: AIMarkdownRenderer(context.applicationContext).also { instance = it }
            }
        }
    }
}
