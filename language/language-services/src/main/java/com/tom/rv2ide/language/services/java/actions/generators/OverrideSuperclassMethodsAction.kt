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

import android.content.Context
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.newDialogBuilder
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.services.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateOverridesParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.flashError
import io.github.rosemoe.sora.widget.CodeEditor
import java.util.concurrent.CompletableFuture
import org.slf4j.LoggerFactory

/**
 * Allows the user to override multiple methods from superclass at once.
 *
 * @author Akash Yadav
 */
class OverrideSuperclassMethodsAction : BaseJavaCodeAction() {

  override val titleTextRes: Int = R.string.action_override_superclass_methods
  override val id: String = "ide.editor.lsp.java.generator.overrideSuperclassMethods"
  override var label: String = ""
  companion object {

    private val log = LoggerFactory.getLogger(OverrideSuperclassMethodsAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (
        !visible ||
            !data.hasRequiredData(Range::class.java, CodeEditor::class.java)
    ) {
      markInvisible()
      return
    }

    enabled = JavaPsiServiceInterop.isAvailable()
  }

  override suspend fun execAction(data: ActionData): Any {
    val file = data.requirePath()
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return EmptyMethodsResult
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return EmptyMethodsResult
    if (identity.file.normalize() != file.normalize() || identity.version != snapshot.version ||
        identity.revision != snapshot.revision) return EmptyMethodsResult
    val recognizeKotlin = JavaPreferences.isJavaKotlinRecognitionEnabled
    val range = data[Range::class.java]!!
    return JavaPsiServiceInterop.overrideMethods(
        JavaPsiOverrideMethodsParams(
            file, range, snapshot.version, snapshot.revision, recognizeKotlin, ICancelChecker.NOOP,
        ),
    ) ?: EmptyMethodsResult
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result === EmptyMethodsResult) return
    val methods = result as JavaPsiOverrideMethodsResult
    val snapshot = FileManager.getActiveDocumentSnapshot(methods.file) ?: return
    if (snapshot.version != methods.documentVersion || snapshot.revision != methods.documentRevision) return
    if (methods.methods.isEmpty()) {
      if (IdeLogConfig.shouldLogWarn()) log.warn("Unable to find any overridable method")
      flashError(data[Context::class.java]!!.getString(R.string.msg_no_overridable_methods))
      return
    }
    val checked = LinkedHashSet<String>()
    val names = methods.methods.map { it.label }.toTypedArray()
    val builder = newDialogBuilder(data)
    builder.setTitle(data[Context::class.java]!!.getString(R.string.msg_select_methods))
    builder.setMultiChoiceItems(names, BooleanArray(names.size)) { _, which, selected ->
      val id = methods.methods[which].id
      if (selected) checked.add(id) else checked.remove(id)
    }
    builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
      dialog.dismiss()
      if (checked.isEmpty()) {
        flashError(data[Context::class.java]!!.getString(R.string.msg_no_methods_selected))
        return@setPositiveButton
      }
      generateOverrides(data, methods, checked.toSet())
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  private fun generateOverrides(
      data: ActionData,
      methods: JavaPsiOverrideMethodsResult,
      selected: Set<String>,
  ) {
    val editor = data[CodeEditor::class.java] ?: return
    val params = JavaPsiGenerateOverridesParams(
        methods.file,
        Range(
            Position(methods.selectionStartLine, methods.selectionStartColumn),
            Position(methods.selectionEndLine, methods.selectionEndColumn),
        ),
        selected,
        methods.documentVersion,
        methods.documentRevision,
        methods.recognizeKotlin,
        EditorPreferences.tabSize,
        EditorPreferences.useSoftTab,
        ICancelChecker.NOOP,
    )
    CompletableFuture.supplyAsync { JavaPsiServiceInterop.generateOverrides(params) }
        .whenComplete { generated, error ->
          ThreadUtils.runOnUiThread {
            if (editor.isReleased || (editor as? IEditor)?.file?.toPath()?.normalize() != methods.file) return@runOnUiThread
            val arguments = editor.extraArguments
            if (arguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1) != methods.documentVersion ||
                arguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L) != methods.documentRevision) return@runOnUiThread
            val snapshot = FileManager.getActiveDocumentSnapshot(methods.file) ?: return@runOnUiThread
            if (snapshot.version != methods.documentVersion || snapshot.revision != methods.documentRevision) return@runOnUiThread
            if (error != null) {
              if (IdeLogConfig.shouldLogError()) log.error("An error occurred overriding methods", error)
              flashError(data[Context::class.java]!!.getString(R.string.msg_cannot_override_methods))
              return@runOnUiThread
            }
            when (generated?.status) {
              JavaPsiOverridesResultStatus.GENERATED -> {
                val edits = checkNotNull(generated).edits
                if (edits.any { it.file != methods.file || it.documentVersion != snapshot.version ||
                        it.documentRevision != snapshot.revision }) return@runOnUiThread
                val ordered = edits.sortedWith(
                    compareByDescending<JavaPsiTextEdit> { it.startLine }.thenByDescending { it.startColumn },
                )
                val text = editor.text
                text.beginBatchEdit()
                try {
                  for (edit in ordered) text.insert(edit.startLine, edit.startColumn, edit.newText)
                } finally {
                  text.endBatchEdit()
                }
                editor.formatCodeAsync()
              }
              JavaPsiOverridesResultStatus.UNSUPPORTED,
              JavaPsiOverridesResultStatus.EMPTY_SELECTION -> flashError(data[Context::class.java]!!.getString(R.string.msg_cannot_override_methods))
              null -> Unit
            }
          }
        }
  }

  private object EmptyMethodsResult
}
