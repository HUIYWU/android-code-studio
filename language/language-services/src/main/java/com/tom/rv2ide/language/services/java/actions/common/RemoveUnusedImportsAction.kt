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

package com.tom.rv2ide.language.services.java.actions.common

import com.google.googlejavaformat.java.FormatterException
import com.google.googlejavaformat.java.RemoveUnusedImports
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.requireEditor
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.services.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.language.services.java.interop.JavaPsiRemoveUnusedImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.resources.R.string
import io.github.rosemoe.sora.widget.CodeEditor
import java.nio.file.Path
import org.slf4j.LoggerFactory

class RemoveUnusedImportsAction : BaseJavaCodeAction() {

  override val id: String = "ide.editor.lsp.java.removeUnusedImports"
  override var label: String = ""
  override val titleTextRes: Int = string.action_remove_unused_imports

  companion object {

    private val log = LoggerFactory.getLogger(RemoveUnusedImportsAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!visible) {
      return
    }

    if (!data.hasRequiredData(CodeEditor::class.java)) {
      markInvisible()
      return
    }

    if (JavaPreferences.isModernDiagnosticsEnabled) {
      val identity = data[DocumentSnapshotIdentity::class.java]
      val file = data.requirePath().normalize()
      if (identity == null || !isCurrent(data.requireEditor(), file, identity)) {
        markInvisible()
        return
      }
      enabled = JavaPsiServiceInterop.isAvailable()
      return
    }
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    if (JavaPreferences.isModernDiagnosticsEnabled) {
      val identity = data[DocumentSnapshotIdentity::class.java] ?: return EmptyResult
      val file = data.requirePath().normalize()
      if (!isCurrentSnapshot(file, identity)) return EmptyResult
      val checker = object : ICancelChecker.Default() {
        override fun isCancelled(): Boolean {
          if (super.isCancelled()) return true
          val current = FileManager.getActiveDocument(file)
          val stale = current == null || synchronized(current) {
            current.version != identity.version || current.revision != identity.revision
          }
          if (stale) cancel()
          return stale
        }
      }
      val edits = JavaPsiServiceInterop.removeUnusedImports(
          JavaPsiRemoveUnusedImportsParams(file, identity.version, identity.revision, checker),
      ) ?: return EmptyResult
      return PsiResult(file, identity, edits)
    }
    val watch = com.tom.rv2ide.utils.StopWatch("Remove unused imports")
    return try {
      val editor = data.requireEditor()
      val content = editor.text
      val output = RemoveUnusedImports.removeUnusedImports(content.toString())
      if (IdeLogConfig.shouldLogDebug()) {
        watch.log()
      }
      output
    } catch (e: FormatterException) {
      if (IdeLogConfig.shouldLogError()) {
        log.error("Failed to remove unused imports", e)
      }
      false
    }
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result is PsiResult) {
      val editor = data.requireEditor()
      if (!isCurrent(editor, result.file, result.identity) || result.edits.isEmpty() || result.edits.any {
            it.file.normalize() != result.file || it.documentVersion != result.identity.version ||
                it.documentRevision != result.identity.revision
          }) return
      val text = editor.text
      text.beginBatchEdit()
      try {
        for (edit in result.edits) {
          text.delete(edit.startLine, edit.startColumn, edit.endLine, edit.endColumn)
        }
      } finally {
        text.endBatchEdit()
      }
      return
    }
    if (result is String && result.isNotEmpty()) {
      val editor = data.requireEditor()
      editor.setText(result)
    }
  }

  private fun isCurrent(editor: CodeEditor, file: Path, identity: DocumentSnapshotIdentity): Boolean {
    if (editor.isReleased || (editor as? IEditor)?.file?.toPath()?.normalize() != file) return false
    val arguments = editor.extraArguments
    if (arguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1) != identity.version ||
        arguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L) != identity.revision) return false
    return isCurrentSnapshot(file, identity)
  }

  private fun isCurrentSnapshot(file: Path, identity: DocumentSnapshotIdentity): Boolean {
    if (identity.file.normalize() != file || identity.version < 0 || identity.revision < 0L) return false
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return false
    return snapshot.version == identity.version && snapshot.revision == identity.revision
  }

  private class PsiResult(
      val file: Path,
      val identity: DocumentSnapshotIdentity,
      val edits: List<JavaPsiTextEdit>,
  )

  private object EmptyResult
}
