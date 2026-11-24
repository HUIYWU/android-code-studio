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
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.requireFile
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.language.services.java.JavaCompilerProvider
import com.tom.rv2ide.language.services.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnhandledExceptionDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiAddUnhandledExceptionsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.java.models.DiagnosticCode
import com.tom.rv2ide.language.services.java.rewrite.AddException
import com.tom.rv2ide.language.services.java.utils.CodeActionUtils
import com.tom.rv2ide.language.services.java.visitors.FindPathAt
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.resources.R
import io.github.rosemoe.sora.widget.CodeEditor
import java.nio.file.Path
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.type.DeclaredType
import openjdk.source.tree.AssignmentTree
import openjdk.source.tree.ExpressionStatementTree
import openjdk.source.tree.ExpressionTree
import openjdk.source.tree.MethodInvocationTree
import openjdk.source.tree.NewClassTree
import openjdk.source.tree.ReturnTree
import openjdk.source.tree.ThrowTree
import openjdk.source.tree.VariableTree
import openjdk.source.util.TreePath
import openjdk.source.util.Trees

/** @author Akash Yadav */
class AddThrowsAction : BaseJavaCodeAction() {

  override val id = "ide.editor.lsp.java.diagnostics.addThrows"
  override var label: String = ""
  private val diagnosticCode = DiagnosticCode.NOT_THROWN.id

  override val titleTextRes: Int = R.string.action_add_throws

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!visible || !data.hasRequiredData(DiagnosticItem::class.java)) {
      markInvisible()
      return
    }
    val item = data[DiagnosticItem::class.java]!!
    val target = JavaUnhandledExceptionDiagnostic.from(item)
    if (target != null) {
      val identity = data[DocumentSnapshotIdentity::class.java]
      val editor = data[CodeEditor::class.java]
      val file = data.requirePath().normalize()
      val snapshot = FileManager.getActiveDocumentSnapshot(file)
      if (identity == null || editor == null || identity.file.normalize() != file ||
          target.file.normalize() != file || snapshot == null || identity.version != target.documentVersion ||
          identity.revision != target.documentRevision || snapshot.version != target.documentVersion ||
          snapshot.revision != target.documentRevision) {
        markInvisible()
        return
      }
      enabled = JavaPsiServiceInterop.isAvailable()
      return
    }
    if (item.source == JavaUnhandledExceptionDiagnostic.CODESERVER_SOURCE ||
        item.code != diagnosticCode) markInvisible()
  }

  override suspend fun execAction(data: ActionData): Any {
    val item = data[DiagnosticItem::class.java] ?: return EmptyResult
    if (item.source == JavaUnhandledExceptionDiagnostic.CODESERVER_SOURCE) {
      val target = JavaUnhandledExceptionDiagnostic.from(item) ?: return EmptyResult
      val identity = data[DocumentSnapshotIdentity::class.java] ?: return EmptyResult
      val file = data.requirePath().normalize()
      val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return EmptyResult
      if (identity.file.normalize() != file || target.file.normalize() != file ||
          identity.version != target.documentVersion || identity.revision != target.documentRevision ||
          snapshot.version != target.documentVersion || snapshot.revision != target.documentRevision) return EmptyResult
      val edits = JavaPsiServiceInterop.addUnhandledExceptions(
          JavaPsiAddUnhandledExceptionsParams(target, ICancelChecker.NOOP),
      ) ?: return EmptyResult
      return PsiResult(file, target.documentVersion, target.documentRevision, edits)
    }
    if (item.code != diagnosticCode) return EmptyResult
    val diagnostic = item
    val compiler =
        JavaCompilerProvider.get(
            IProjectManager.getInstance()
                .getWorkspace()
                ?.findModuleForFile(data.requireFile(), false) ?: return Any()
        )
    val file = data.requirePath()
    return compiler.compile(file).get { task ->
      val needsThrow = CodeActionUtils.findMethod(task, file, diagnostic.range)
      val exceptionName = findUnhandledExceptionType(task, file, diagnostic.range)
      if (exceptionName.isBlank()) {
        return@get false
      }
      return@get AddException(
          needsThrow.className,
          needsThrow.methodName,
          needsThrow.erasedParameterTypes,
          exceptionName,
      )
    }
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result is AddException) {
      performCodeAction(data, result)
      return
    }
    if (result !is PsiResult) return
    val editor = data[CodeEditor::class.java] ?: return
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return
    if (!isCurrent(editor, result, identity) || result.edits.isEmpty() || result.edits.any {
          it.file.normalize() != result.file || it.documentVersion != result.version ||
              it.documentRevision != result.revision || it.startLine != it.endLine ||
              it.startColumn != it.endColumn
        }) return
    val text = editor.text
    text.beginBatchEdit()
    try {
      for (edit in result.edits.sortedWith(
          compareByDescending<JavaPsiTextEdit> { it.startLine }.thenByDescending { it.startColumn },
      )) text.insert(edit.startLine, edit.startColumn, edit.newText)
    } finally {
      text.endBatchEdit()
    }
    editor.formatCodeAsync()
  }

  private fun isCurrent(
      editor: CodeEditor,
      result: PsiResult,
      identity: DocumentSnapshotIdentity,
  ): Boolean {
    if (editor.isReleased ||
        (editor as? IEditor)?.file?.toPath()?.normalize() != result.file ||
        identity.file.normalize() != result.file || identity.version != result.version ||
        identity.revision != result.revision) return false
    val arguments = editor.extraArguments
    if (arguments.getInt(IEditor.KEY_DOCUMENT_VERSION, -1) != result.version ||
        arguments.getLong(IEditor.KEY_DOCUMENT_REVISION, -1L) != result.revision) return false
    val snapshot = FileManager.getActiveDocumentSnapshot(result.file) ?: return false
    return snapshot.version == result.version && snapshot.revision == result.revision
  }

  private class PsiResult(
      val file: Path,
      val version: Int,
      val revision: Long,
      val edits: List<JavaPsiTextEdit>,
  )

  private object EmptyResult

  private fun findUnhandledExceptionType(
      task: com.tom.rv2ide.language.services.java.compiler.CompileTask,
      file: java.nio.file.Path,
      range: com.tom.rv2ide.models.Range,
  ): String {
    val root = task.root(file)
    val position = root.lineMap.getPosition(range.start.line + 1L, range.start.column + 1L)
    val path = FindPathAt(task.task).scan(root, position) ?: return ""
    val trees = Trees.instance(task.task)
    val targetPath = findThrowingSite(path) ?: return ""
    val target = resolveInvocationTarget(task, trees, targetPath)
    val declaredThrown = target?.thrownTypes?.mapNotNull { (it as? DeclaredType)?.asElement() as? TypeElement }
    if (!declaredThrown.isNullOrEmpty()) {
      return declaredThrown.first().qualifiedName.toString()
    }
    if (targetPath.leaf is ThrowTree) {
      val throwTree = targetPath.leaf as ThrowTree
      return resolveThrownExpressionType(trees, throwTree.expression, targetPath)
    }
    return ""
  }

  private fun findThrowingSite(path: TreePath): TreePath? {
    var current: TreePath? = path
    while (current != null) {
      when (val leaf = current.leaf) {
        is MethodInvocationTree, is NewClassTree, is ThrowTree -> return current
        is VariableTree -> {
          val nested = leaf.initializer?.let { findThrowingSite(TreePath(current, it)) }
          if (nested != null) {
            return nested
          }
        }
        is AssignmentTree -> {
          val nested = findThrowingSite(TreePath(current, leaf.expression))
          if (nested != null) {
            return nested
          }
        }
        is ExpressionStatementTree -> {
          val nested = findThrowingSite(TreePath(current, leaf.expression))
          if (nested != null) {
            return nested
          }
        }
        is ReturnTree -> {
          val expr = leaf.expression
          if (expr != null) {
            val nested = findThrowingSite(TreePath(current, expr))
            if (nested != null) {
              return nested
            }
          }
        }
      }
      current = current.parentPath
    }
    return null
  }

  private fun resolveInvocationTarget(
      task: com.tom.rv2ide.language.services.java.compiler.CompileTask,
      trees: Trees,
      path: TreePath,
  ): ExecutableElement? {
    val leaf = path.leaf
    return when (leaf) {
      is MethodInvocationTree -> {
        val targetPath = TreePath(path, leaf.methodSelect)
        trees.getElement(targetPath) as? ExecutableElement
      }
      is NewClassTree -> resolveConstructorTarget(task, trees, path, leaf)
      else -> null
    }
  }

  private fun resolveConstructorTarget(
      task: com.tom.rv2ide.language.services.java.compiler.CompileTask,
      trees: Trees,
      path: TreePath,
      leaf: NewClassTree,
  ): ExecutableElement? {
    val identifierPath = TreePath(path, leaf.identifier)
    val type = trees.getElement(identifierPath) as? TypeElement ?: return null
    val argumentTypes =
        leaf.arguments.mapNotNull { argument -> trees.getTypeMirror(TreePath(path, argument))?.toString() }.toTypedArray()
    return task.task.elements.getAllMembers(type).firstNotNullOfOrNull { member ->
      if (member.kind != ElementKind.CONSTRUCTOR) {
        return@firstNotNullOfOrNull null
      }
      val constructor = member as ExecutableElement
      if (matchesConstructor(constructor, argumentTypes)) {
        constructor
      } else {
        null
      }
    }
  }

  private fun resolveThrownExpressionType(
      trees: Trees,
      expression: ExpressionTree,
      path: TreePath,
  ): String {
    val expressionPath = TreePath(path, expression)
    val type = trees.getTypeMirror(expressionPath) as? DeclaredType ?: return ""
    val element = type.asElement() as? TypeElement ?: return ""
    return element.qualifiedName.toString()
  }

  private fun matchesConstructor(
      constructor: ExecutableElement,
      argumentTypes: Array<String>,
  ): Boolean {
    if (constructor.parameters.size != argumentTypes.size) {
      return false
    }
    return constructor.parameters.indices.all { index ->
      val parameterType = constructor.parameters[index].asType().toString()
      parameterType == argumentTypes[index]
    }
  }

}