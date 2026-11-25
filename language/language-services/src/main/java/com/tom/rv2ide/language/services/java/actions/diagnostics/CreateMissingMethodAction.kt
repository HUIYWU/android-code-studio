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

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.requireFile
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.services.java.JavaCompilerProvider
import com.tom.rv2ide.language.services.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedMethodDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.java.interop.JavaPsiUnresolvedMethodParams
import com.tom.rv2ide.language.services.java.models.DiagnosticCode
import com.tom.rv2ide.language.services.java.rewrite.CreateMissingMethod
import com.tom.rv2ide.language.services.java.utils.CodeActionUtils.findPosition
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** @author Akash Yadav */
class CreateMissingMethodAction : BaseJavaCodeAction() {

  override val id: String = "ide.editor.lsp.java.diagnostics.createMissingMethod"
  override var label: String = ""
  private val diagnosticCode = DiagnosticCode.MISSING_METHOD.id

  override val titleTextRes: Int = R.string.action_create_missing_method

  companion object {

    private val log = LoggerFactory.getLogger(CreateMissingMethodAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (!visible || !data.hasRequiredData(DiagnosticItem::class.java)) {
      markInvisible()
      return
    }

    val diagnostic = data[DiagnosticItem::class.java]!!
    val target = JavaUnresolvedMethodDiagnostic.from(diagnostic)
    if (JavaPreferences.isModernDiagnosticsEnabled && target == null) {
      markInvisible()
      return
    }
    if (JavaPreferences.isModernDiagnosticsEnabled && target != null) {
      val identity = data[DocumentSnapshotIdentity::class.java]
      val editor = data[CodeEditor::class.java]
      val file = data.requirePath().normalize()
      if (identity == null || editor == null || target.file.normalize() != file ||
          !isCurrent(editor, file, target.documentVersion, target.documentRevision, identity)) {
        markInvisible()
        return
      }
      enabled = JavaPsiServiceInterop.isAvailable()
      return
    }
    if (diagnosticCode != diagnostic.code) {
      markInvisible()
      return
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    val diagnostic = data[DiagnosticItem::class.java]!!
    val target = JavaUnresolvedMethodDiagnostic.from(diagnostic)
    if (JavaPreferences.isModernDiagnosticsEnabled && target == null) return EmptyResult
    if (JavaPreferences.isModernDiagnosticsEnabled && target != null) {
      val identity = data[DocumentSnapshotIdentity::class.java] ?: return EmptyResult
      val file = data.requirePath().normalize()
      if (!isCurrentSnapshot(file, target.documentVersion, target.documentRevision, identity)) return EmptyResult
      val edit = JavaPsiServiceInterop.createMissingMethod(
          JavaPsiUnresolvedMethodParams(
              target, EditorPreferences.tabSize, EditorPreferences.useSoftTab, ICancelChecker.NOOP,
          ),
      ) ?: return EmptyResult
      return PsiResult(file, target.documentVersion, target.documentRevision, edit)
    }
    if (diagnostic.code != diagnosticCode) return EmptyResult
    val compiler =
        JavaCompilerProvider.get(
            IProjectManager.getInstance()
                .getWorkspace()
                ?.findModuleForFile(data.requireFile(), false) ?: return Any()
        )
    val file = data.requirePath()
    return compiler.compile(file).get {
      CreateMissingMethod(file, findPosition(it, file, diagnostic.range.start))
    }
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result is PsiResult) {
      applyPsiEdit(data, result)
      return
    }
    if (result !is CreateMissingMethod) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn("Unable to create missing method")
      }
      return
    }

    performCodeAction(data, result)
  }

  private fun applyPsiEdit(data: ActionData, result: PsiResult) {
    val editor = data[CodeEditor::class.java] ?: return
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return
    val edit = result.edit
    if (!isCurrent(editor, result.file, result.version, result.revision, identity) ||
        edit.file.normalize() != result.file || edit.documentVersion != result.version ||
        edit.documentRevision != result.revision || edit.startLine != edit.endLine ||
        edit.startColumn != edit.endColumn) return
    val text = editor.text
    text.beginBatchEdit()
    try {
      text.insert(edit.startLine, edit.startColumn, edit.newText)
    } finally {
      text.endBatchEdit()
    }
    editor.formatCodeAsync()
  }

  private fun isCurrent(
      editor: CodeEditor,
      file: Path,
      version: Int,
      revision: Long,
      identity: DocumentSnapshotIdentity,
  ): Boolean {
    if (editor.isReleased ||
        (editor as? IEditor)?.file?.toPath()?.normalize() != file ||
        identity.file.normalize() != file || identity.version != version || identity.revision != revision) return false
    val arguments = editor.extraArguments
    if (arguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1) != version ||
        arguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L) != revision) return false
    return isCurrentSnapshot(file, version, revision, identity)
  }

  private fun isCurrentSnapshot(
      file: Path,
      version: Int,
      revision: Long,
      identity: DocumentSnapshotIdentity,
  ): Boolean {
    if (identity.file.normalize() != file || identity.version != version || identity.revision != revision) return false
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return false
    return snapshot.version == version && snapshot.revision == revision
  }

  private class PsiResult(
      val file: Path,
      val version: Int,
      val revision: Long,
      val edit: JavaPsiTextEdit,
  )

  private object EmptyResult
}
