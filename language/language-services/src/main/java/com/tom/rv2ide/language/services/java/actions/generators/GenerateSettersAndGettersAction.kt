/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.language.services.java.actions.generators

import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.services.java.actions.FieldBasedAction
import com.tom.rv2ide.language.services.java.interop.JavaPsiAccessorsResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateAccessorsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.flashError
import io.github.rosemoe.sora.widget.CodeEditor
import java.util.concurrent.CompletableFuture
import org.slf4j.LoggerFactory

/**
 * Allows the user to select fields from the current class, then generates setters and getters for
 * the selected fields.
 *
 * @author Akash Yadav
 */
class GenerateSettersAndGettersAction : FieldBasedAction() {

  override val id: String = "ide.editor.lsp.java.generator.settersAndGetters"
  override var label: String = ""

  override val titleTextRes: Int = R.string.action_generate_setters_getters

  companion object {

    private val log = LoggerFactory.getLogger(GenerateSettersAndGettersAction::class.java)
  }

  override fun onGetPsiFields(result: JavaPsiFieldsResult, data: ActionData) {
    val editor = data[CodeEditor::class.java] ?: return
    val labels = result.fields.map { "${it.name}: ${it.typeText}" }
    showFieldSelector(labels, data, result.fields.map { it.name }) { selected ->
      val params = JavaPsiGenerateAccessorsParams(
          result.file,
          Range(
              Position(result.selectionStartLine, result.selectionStartColumn),
              Position(result.selectionEndLine, result.selectionEndColumn),
          ),
          selected.toSet(),
          result.documentVersion,
          result.documentRevision,
          EditorPreferences.tabSize,
          EditorPreferences.useSoftTab,
          ICancelChecker.NOOP,
      )
      CompletableFuture.supplyAsync { JavaPsiServiceInterop.generateAccessors(params) }
          .whenComplete { generated, error ->
            ThreadUtils.runOnUiThread {
              if (editor.isReleased || (editor as? IEditor)?.file?.toPath()?.normalize() != result.file) {
                return@runOnUiThread
              }
              val arguments = editor.extraArguments
              if (arguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1) != result.documentVersion ||
                  arguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L) != result.documentRevision) {
                return@runOnUiThread
              }
              val snapshot = FileManager.getActiveDocumentSnapshot(result.file) ?: return@runOnUiThread
              if (snapshot.version != result.documentVersion || snapshot.revision != result.documentRevision) {
                return@runOnUiThread
              }
              if (error != null) {
                if (IdeLogConfig.shouldLogError()) log.error("Unable to generate setters and getters", error)
                flashError(R.string.msg_cannot_generate_setters_getters)
                return@runOnUiThread
              }
              when (generated?.status) {
                JavaPsiAccessorsResultStatus.GENERATED -> {
                  val edit = checkNotNull(checkNotNull(generated).edit)
                  if (edit.file != result.file || edit.documentVersion != snapshot.version ||
                      edit.documentRevision != snapshot.revision) return@runOnUiThread
                  editor.text.insert(edit.startLine, edit.startColumn, edit.newText)
                  editor.formatCodeAsync()
                }
                JavaPsiAccessorsResultStatus.UNSUPPORTED,
                JavaPsiAccessorsResultStatus.EMPTY_SELECTION -> flashError(R.string.msg_cannot_generate_setters_getters)
                null -> Unit
              }
            }
          }
    }
  }
}
