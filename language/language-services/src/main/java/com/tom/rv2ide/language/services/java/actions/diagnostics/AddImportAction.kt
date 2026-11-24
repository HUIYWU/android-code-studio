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

import com.google.common.collect.Iterables.toArray
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.newDialogBuilder
import com.tom.rv2ide.actions.requireFile
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.compiler.javac.util.JavaDiagnosticUtils
import com.tom.rv2ide.language.services.java.JavaCompilerProvider
import com.tom.rv2ide.language.services.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedTypeDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiImportCandidate
import com.tom.rv2ide.language.services.java.interop.JavaPsiResolveImportCandidatesParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.models.DiagnosticCode
import com.tom.rv2ide.language.services.java.rewrite.AddImport
import com.tom.rv2ide.language.services.java.rewrite.Rewrite
import com.tom.rv2ide.lsp.models.CodeActionItem
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.nio.file.Path
import jdkx.tools.Diagnostic
import jdkx.tools.JavaFileObject
import org.slf4j.LoggerFactory

/** @author Akash Yadav */
class AddImportAction : BaseJavaCodeAction() {

  override val id: String = "ide.editor.lsp.java.diagnostics.addImport"
  override var label: String = ""
  private val diagnosticCode = DiagnosticCode.NOT_IMPORTED.id

  override val titleTextRes: Int = R.string.action_import_classes

  companion object {

    private val log = LoggerFactory.getLogger(AddImportAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (!visible || !data.hasRequiredData(DiagnosticItem::class.java)) {
      markInvisible()
      return
    }

    val diagnostic = data.get(DiagnosticItem::class.java)!!
    val target = JavaUnresolvedTypeDiagnostic.from(diagnostic)
    if (target != null) {
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
    if (diagnosticCode != diagnostic.code || diagnostic.extra !is Diagnostic<*>) {
      markInvisible()
      return
    }

    val file = data.requireFile()
    val module =
        IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false)
            ?: run {
              markInvisible()
              return
            }

    val compiler = JavaCompilerProvider.get(module)

    @Suppress("UNCHECKED_CAST")
    val jcDiagnostic =
        JavaDiagnosticUtils.asJCDiagnostic(diagnostic.extra as Diagnostic<out JavaFileObject>)
    if (jcDiagnostic == null) {
      markInvisible()
      return
    }

    val found =
        jcDiagnostic.args[1]?.toString()?.let { compiler.findQualifiedNames(it, true).isNotEmpty() }
            ?: false

    visible = found
    enabled = found
  }

  override suspend fun execAction(data: ActionData): Any {
    val item = data[DiagnosticItem::class.java] ?: return EmptyResult
    val target = JavaUnresolvedTypeDiagnostic.from(item)
    if (target != null) {
      val identity = data[DocumentSnapshotIdentity::class.java] ?: return EmptyResult
      val file = data.requirePath().normalize()
      val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return EmptyResult
      if (identity.file.normalize() != file || target.file.normalize() != file ||
          identity.version != target.documentVersion || identity.revision != target.documentRevision ||
          snapshot.version != target.documentVersion || snapshot.revision != target.documentRevision) return EmptyResult
      val module = IProjectManager.getInstance().getWorkspace()?.findModuleForFile(data.requireFile(), false)
      val candidates = JavaPsiServiceInterop.resolveImportCandidates(
          JavaPsiResolveImportCandidatesParams(
              target, module, ICancelChecker.NOOP,
          ),
      ) ?: return EmptyResult
      return ImportResult(file, target.documentVersion, target.documentRevision, candidates)
    }
    if (item.code != diagnosticCode || item.extra !is Diagnostic<*>) return EmptyResult
    @Suppress("UNCHECKED_CAST")
    val diagnostic =
        JavaDiagnosticUtils.asUnwrapper(
            item.extra as Diagnostic<out JavaFileObject>
        )!!
    val file = data.requireFile()
    val module =
        IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false)
            ?: run {
              markInvisible()
              return Any()
            }
    val compiler = JavaCompilerProvider.get(module)
    val titles = mutableListOf<String>()
    val rewrites = mutableListOf<AddImport>()
    val simpleName = diagnostic.d.args[1]
    for (name in compiler.publicTopLevelTypes()) {
      val klass = name.replace('/', '.')
      if (!klass.endsWith(".$simpleName")) continue
      titles.add(klass)
      rewrites.add(AddImport(data.requirePath(), klass))
    }
    if (rewrites.isEmpty()) return false
    return Pair(titles, rewrites)
  }

  @Suppress("UNCHECKED_CAST")
  override fun postExec(data: ActionData, result: Any) {
    if (result === EmptyResult) return
    if (result is ImportResult) {
      if (result.candidates.isEmpty()) return
      if (result.candidates.size == 1) {
        applyImportCandidate(data, result, result.candidates.single())
        return
      }
      val builder = newDialogBuilder(data)
      builder.setTitle(label)
      builder.setItems(result.candidates.map { it.qualifiedName }.toTypedArray()) { dialog, which ->
        dialog.dismiss()
        applyImportCandidate(data, result, result.candidates[which])
      }
      builder.show()
      return
    }
    if (result !is Pair<*, *>) return

    val file = data.requireFile()
    val module =
        IProjectManager.getInstance().getWorkspace()?.findModuleForFile(file, false)
            ?: run {
              markInvisible()
              return
            }

    val compiler = JavaCompilerProvider.get(module)
    val client = data.getLanguageClient() ?: return
    val actions = mutableListOf<CodeActionItem>()
    val titles = result.first as List<String>
    val rewrites = result.second as List<Rewrite>

    for (index in rewrites.indices) {
      val name = titles[index]
      val rewrite = rewrites[index]
      rewrite.asCodeActions(compiler, name)?.let { actions.add(it) }
    }

    when (actions.size) {
      0 -> {
        if (IdeLogConfig.shouldLogWarn()) {
          log.warn("No rewrites found. Cannot perform action")
        }
      }

      1 -> {
        client.performCodeAction(actions[0])
      }

      else -> {
        val builder = newDialogBuilder(data)
        builder.setTitle(label)
        builder.setItems(toArray(titles, String::class.java)) { d, w ->
          d.dismiss()
          client.performCodeAction(actions[w])
        }
        builder.show()
      }
    }
  }

  private fun applyImportCandidate(
      data: ActionData,
      result: ImportResult,
      candidate: JavaPsiImportCandidate,
  ) {
    val editor = data[CodeEditor::class.java] ?: return
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return
    val edit = candidate.edit
    if (!isCurrent(editor, result.file, result.version, result.revision, identity) ||
        edit.file.normalize() != result.file ||
        edit.documentVersion != result.version || edit.documentRevision != result.revision ||
        edit.startLine != edit.endLine || edit.startColumn != edit.endColumn) return
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
    if (editor.isReleased || (editor as? IEditor)?.file?.toPath()?.normalize() != file ||
        identity.file.normalize() != file || identity.version != version || identity.revision != revision) return false
    val arguments = editor.extraArguments
    if (arguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1) != version ||
        arguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L) != revision) return false
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return false
    return snapshot.version == version && snapshot.revision == revision
  }

  private class ImportResult(
      val file: Path,
      val version: Int,
      val revision: Long,
      val candidates: List<JavaPsiImportCandidate>,
  )

  private object EmptyResult
}
