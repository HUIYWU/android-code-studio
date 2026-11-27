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

import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.requireContext
import com.tom.rv2ide.actions.requireFile
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.language.services.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.language.services.java.compiler.CompileTask
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.services.java.interop.JavaPsiFixImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiImportChoices
import com.tom.rv2ide.language.services.java.interop.JavaPsiStaticImportChoices
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.preferences.internal.JavaPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.language.services.java.models.DiagnosticCode
import com.tom.rv2ide.language.services.java.utils.positionForImports
import com.tom.rv2ide.lsp.models.CodeActionItem
import com.tom.rv2ide.lsp.models.CodeActionKind
import com.tom.rv2ide.lsp.models.DocumentChange
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.utils.DialogUtils
import com.tom.rv2ide.utils.flashInfo
import java.nio.file.Path
import io.github.rosemoe.sora.widget.CodeEditor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Analyzes the source file for unresolved names and tries to import all of them at once.
 *
 * @author Akash Yadav
 */
class AutoFixImportsAction : BaseJavaCodeAction() {

  override val titleTextRes: Int = R.string.title_fix_imports
  override val id: String = "ide.editor.lsp.java.diagnostics.autoFixImports"
  override var label: String = ""

  companion object {

    private val log = LoggerFactory.getLogger(AutoFixImportsAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!visible || !JavaPreferences.isModernDiagnosticsEnabled) return
    val file = data.requirePath().normalize()
    val identity = data[DocumentSnapshotIdentity::class.java]
    val editor = data[CodeEditor::class.java]
    if (identity == null || editor == null || !isCurrent(editor, file, identity)) {
      markInvisible()
      return
    }
    enabled = JavaPsiServiceInterop.isAvailable()
  }

  override suspend fun execAction(data: ActionData): Any {
    if (JavaPreferences.isModernDiagnosticsEnabled) return fixImportsWithPsi(data)
    val path = data.requirePath()
    val compiler = data.requireCompiler()
    return compiler.compile(path).get { task ->
      val classes = mutableMapOf<String, List<String>>()

      // find all unresolved simple names
      unresolvedNames(path, task).forEach { simpleName ->

        // if we have already looked for this simple name
        // we do not need to look it up again
        if (classes[simpleName] != null) return@forEach

        // find classes with those names
        compiler.findQualifiedNames(simpleName).let { names ->

          // if we find classes with that specific simple name, map them to the simple name
          if (names.isNotEmpty()) {
            classes[simpleName] = names
          }
        }
      }

      // return the result
      Result(getFileImports(task, path), classes)
    }
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result === EmptyResult) return
    if (result === NoImportsResult) {
      flashInfo(R.string.msg_no_unresolved_classes)
      return
    }
    if (result is PsiResult) {
      val editor = data[CodeEditor::class.java] ?: return
      if (!JavaPsiServiceInterop.isCurrentGeneration(result.serviceGeneration) ||
          !isCurrent(editor, result.file, result.identity) || result.edits.isEmpty() || result.edits.any {
            it.file.normalize() != result.file || it.documentVersion != result.identity.version ||
                it.documentRevision != result.identity.revision ||
                it.startLine != it.endLine || it.startColumn != it.endColumn
          }) return
      val text = editor.text
      text.beginBatchEdit()
      try {
        for (edit in result.edits) text.insert(edit.startLine, edit.startColumn, edit.newText)
      } finally {
        text.endBatchEdit()
      }
      return
    }
    if (result !is Result) {
      if (IdeLogConfig.shouldLogError()) {
        log.error("Invalid result returned from execAction: {}", result)
      }
      return
    }

    if (result.classes.isEmpty()) {
      flashInfo(R.string.msg_no_unresolved_classes)
      return
    }

    // if there are multiple classes with same simple name
    // ask the user to choose the appropriate class
    if (result.classes.any { it.value.size > 1 }) {
      finalizeClassNames(data, result)
    } else {
      performEdits(data, result)
    }
  }

  private fun finalizeClassNames(data: ActionData, result: Result) {
    var e: Map.Entry<String, List<String>>? = null
    for (entry in result.classes) {
      if (entry.value.size > 1) {
        e = entry
        break
      }
    }

    if (e == null) {
      performEdits(data, result)
      return
    }

    val context = data.requireContext()
    DialogUtils.newMaterialDialogBuilder(context)
        .setCancelable(true)
        .setItems(e.value.toTypedArray()) { dialog, which ->
          dialog.dismiss()
          result.classes[e.key] = listOf(e.value[which])

          // once the user decides which class to import for this simple name,
          // call this method again to see if there any other simple names with multiple options
          finalizeClassNames(data, result)
        }
        .setTitle(context.getString(R.string.title_class_chooser, e.key))
        .show()
  }

  private fun performEdits(data: ActionData, result: Result) {
    val path = data.requirePath()
    val compiler = data.requireCompiler()
    val client =
        data.getLanguageClient()
            ?: run {
              if (IdeLogConfig.shouldLogWarn()) {
                log.warn("No language client found. Cannot perform edits.")
              }
              return
            }

    val classes = result.classes.mapNotNull { it.value.firstOrNull() }

    if (classes.isEmpty()) {
      flashInfo(R.string.msg_no_unresolved_classes)
      return
    }

    val insertText = StringBuilder()
    if (result.fileImports.isEmpty() && classes.isNotEmpty()) {
      // if there are no file imports, the new imports will be added just after the package
      // declaration. To avoid this, add a new line before the imports
      insertText.append("\n")
    }

    for (klass in classes) {
      insertText.append("import ${klass};\n")
    }

    val position = compiler.compile(path).get { positionForImports(classes[0], it, path) }

    val change = DocumentChange()
    change.file = path
    change.edits = listOf(TextEdit(Range.pointRange(position), insertText.toString()))

    val action = CodeActionItem()
    action.title = data.requireContext().getString(R.string.title_fix_imports)
    action.kind = CodeActionKind.QuickFix
    action.changes = listOf(change)
    client.performCodeAction(action)
  }

  /**
   * Walks through the diagnostics of the compilation task, looks for [DiagnosticCode.NOT_IMPORTED]
   * errors and returns a list of simple names of all not imported classes.
   */
  private fun unresolvedNames(file: Path, task: CompileTask): List<String> {
    val names = mutableListOf<String>()
    var docContents: CharSequence? = null
    val diagnostics =
        task.diagnostics.filter {
          it.source.toUri() == file.toUri() && it.code == DiagnosticCode.NOT_IMPORTED.id
        }
    for (diagnostic in diagnostics) {
      val content =
          try {
            docContents ?: diagnostic.source.getCharContent(true).also { docContents = it }
          } catch (e: Exception) {
            if (IdeLogConfig.shouldLogError()) {
              log.error("Failed to get contents of file {}", file, e)
            }
            continue
          }

      val name =
          content.subSequence(diagnostic.startPosition.toInt(), diagnostic.endPosition.toInt())
      names.add(name.toString())
    }
    return names
  }

  private fun getFileImports(task: CompileTask, file: Path): Set<String> {
    return task.root(file).imports.map { it.qualifiedIdentifier }.map { it.toString() }.toSet()
  }

  private suspend fun fixImportsWithPsi(data: ActionData): Any {
    val file = data.requirePath().normalize()
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return EmptyResult
    if (!isCurrentSnapshot(file, identity)) return EmptyResult
    val serviceGeneration = JavaPsiServiceInterop.currentGeneration() ?: return EmptyResult
    val coroutineContext = currentCoroutineContext()
    val checker = object : ICancelChecker.Default() {
      override fun isCancelled(): Boolean {
        if (super.isCancelled() || !coroutineContext.isActive) return true
        val document = FileManager.getActiveDocument(file)
        val stale = !JavaPsiServiceInterop.isCurrentGeneration(serviceGeneration) ||
            document == null || synchronized(document) {
              document.version != identity.version || document.revision != identity.revision
            }
        if (stale) cancel()
        return stale
      }
    }
    val module = IProjectManager.getInstance().getWorkspace()?.findModuleForFile(data.requireFile(), false)
    val choices = JavaPsiServiceInterop.fixImports(
        JavaPsiFixImportsParams(file, identity.version, identity.revision, module, checker),
    ) ?: return EmptyResult
    if (choices.types.isEmpty() && choices.staticMembers.isEmpty()) return NoImportsResult
    val selected = LinkedHashMap<String, String>()
    val selectedStatic = LinkedHashMap<String, String>()
    for (choice in choices.types) {
      checker.abortIfCancelled()
      val qualifiedName = if (choice.qualifiedNames.size == 1) choice.qualifiedNames.single() else {
        withContext(Dispatchers.Main.immediate) {
          val editor = data[CodeEditor::class.java] ?: return@withContext null
          checker.abortIfCancelled()
          if (!isCurrent(editor, file, identity)) return@withContext null
          chooseImport(data, choice)
        } ?: return EmptyResult
      }
      selected[choice.key] = qualifiedName
    }
    for (choice in choices.staticMembers) {
      checker.abortIfCancelled()
      val qualifiedName = if (choice.qualifiedNames.size == 1) choice.qualifiedNames.single() else {
        withContext(Dispatchers.Main.immediate) {
          val editor = data[CodeEditor::class.java] ?: return@withContext null
          checker.abortIfCancelled()
          if (!isCurrent(editor, file, identity)) return@withContext null
          chooseStaticImport(data, choice)
        } ?: return EmptyResult
      }
      selectedStatic[choice.key] = qualifiedName
    }
    checker.abortIfCancelled()
    val edits = JavaPsiServiceInterop.generateImports(
        JavaPsiGenerateImportsParams(file, identity.version, identity.revision, selected, selectedStatic, checker),
    ) ?: return EmptyResult
    checker.abortIfCancelled()
    return PsiResult(file, identity, serviceGeneration, edits)
  }

  private suspend fun chooseImport(data: ActionData, choice: JavaPsiImportChoices): String? = chooseImportItems(
      data, choice.simpleName, choice.qualifiedNames,
  )

  private suspend fun chooseStaticImport(data: ActionData, choice: JavaPsiStaticImportChoices): String? = chooseImportItems(
      data, choice.simpleName, choice.qualifiedNames,
  )

  private suspend fun chooseImportItems(data: ActionData, simpleName: String, qualifiedNames: List<String>): String? =
      suspendCancellableCoroutine { continuation ->
        val context = data.requireContext()
        val dialog = DialogUtils.newMaterialDialogBuilder(context)
            .setCancelable(true)
            .setTitle(context.getString(R.string.title_class_chooser, simpleName))
            .setItems(qualifiedNames.toTypedArray()) { dialog, which ->
              dialog.dismiss()
              if (continuation.isActive) continuation.resume(qualifiedNames[which])
            }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
            .create()
        continuation.invokeOnCancellation {
          android.os.Handler(android.os.Looper.getMainLooper()).post { dialog.dismiss() }
        }
        dialog.show()
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
      val serviceGeneration: Long,
      val edits: List<JavaPsiTextEdit>,
  )

  private object EmptyResult
  private object NoImportsResult

  inner class Result(val fileImports: Set<String>, val classes: MutableMap<String, List<String>>)
}
