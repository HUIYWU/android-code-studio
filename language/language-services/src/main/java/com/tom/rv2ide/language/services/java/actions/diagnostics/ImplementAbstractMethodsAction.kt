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
package com.tom.rv2ide.language.services.java.actions.diagnostics

import android.content.Context
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.services.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.language.services.java.diagnostics.JavaAbstractMethodsDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiImplementAbstractMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.flashError
import io.github.rosemoe.sora.widget.CodeEditor
import java.nio.file.Path

class ImplementAbstractMethodsAction : BaseJavaCodeAction() {
  override val id: String = "ide.editor.lsp.java.diagnostics.implementAbstractMethods"
  override var label: String = ""
  override val titleTextRes: Int = R.string.action_implement_abstract_methods

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!visible) return
    if (!data.hasRequiredData(DiagnosticItem::class.java, CodeEditor::class.java,
            DocumentSnapshotIdentity::class.java)) {
      markInvisible()
      return
    }
    val target = JavaAbstractMethodsDiagnostic.from(data[DiagnosticItem::class.java]!!) ?: run {
      markInvisible()
      return
    }
    val identity = data[DocumentSnapshotIdentity::class.java]!!
    val file = data.requirePath().normalize()
    val snapshot = FileManager.getActiveDocumentSnapshot(file)
    if (identity.file.normalize() != file || target.file.normalize() != file || snapshot == null ||
        identity.version != target.documentVersion || identity.revision != target.documentRevision ||
        snapshot.version != target.documentVersion || snapshot.revision != target.documentRevision) {
      markInvisible()
      return
    }
    enabled = JavaPsiServiceInterop.isAvailable()
  }

  override suspend fun execAction(data: ActionData): Any {
    val item = data[DiagnosticItem::class.java] ?: return EmptyResult
    val target = JavaAbstractMethodsDiagnostic.from(item) ?: return EmptyResult
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return EmptyResult
    val file = data.requirePath().normalize()
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return EmptyResult
    if (identity.file.normalize() != file || target.file.normalize() != file ||
        identity.version != target.documentVersion || identity.revision != target.documentRevision ||
        snapshot.version != target.documentVersion || snapshot.revision != target.documentRevision) {
      return EmptyResult
    }
    val params = JavaPsiImplementAbstractMethodsParams(
        target,
        JavaPreferences.isJavaKotlinRecognitionEnabled,
        EditorPreferences.tabSize,
        EditorPreferences.useSoftTab,
        ICancelChecker.NOOP,
    )
    val generated = JavaPsiServiceInterop.implementAbstractMethods(params) ?: return EmptyResult
    return ActionResult(file, target.documentVersion, target.documentRevision, generated)
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result === EmptyResult) return
    val actionResult = result as ActionResult
    val editor = data[CodeEditor::class.java] ?: return
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return
    if (!isCurrent(editor, actionResult, identity)) return
    if (actionResult.generated.status != JavaPsiOverridesResultStatus.GENERATED) {
      if (actionResult.generated.status == JavaPsiOverridesResultStatus.UNSUPPORTED) {
        flashError(data[Context::class.java]!!.getString(R.string.msg_cannot_override_methods))
      }
      return
    }
    val edits = actionResult.generated.edits
    if (edits.isEmpty() || edits.any {
          it.file.normalize() != actionResult.file || it.documentVersion != actionResult.version ||
              it.documentRevision != actionResult.revision || it.startLine != it.endLine ||
              it.startColumn != it.endColumn
        }) return
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

  private fun isCurrent(
      editor: CodeEditor,
      result: ActionResult,
      identity: DocumentSnapshotIdentity,
  ): Boolean {
    if (editor.isReleased || (editor as? IEditor)?.file?.toPath()?.normalize() != result.file ||
        identity.file.normalize() != result.file || identity.version != result.version ||
        identity.revision != result.revision) return false
    val arguments = editor.extraArguments
    if (arguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1) != result.version ||
        arguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L) != result.revision) return false
    val snapshot = FileManager.getActiveDocumentSnapshot(result.file) ?: return false
    return snapshot.version == result.version && snapshot.revision == result.revision
  }

  private class ActionResult(
      val file: Path,
      val version: Int,
      val revision: Long,
      val generated: JavaPsiOverridesResult,
  )

  private object EmptyResult
}
