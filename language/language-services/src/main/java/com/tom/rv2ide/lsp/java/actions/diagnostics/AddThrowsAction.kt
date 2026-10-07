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
package com.tom.rv2ide.lsp.java.actions.diagnostics

import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.requireFile
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.lsp.java.JavaCompilerProvider
import com.tom.rv2ide.lsp.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.lsp.java.models.DiagnosticCode
import com.tom.rv2ide.lsp.java.rewrite.AddException
import com.tom.rv2ide.lsp.java.utils.CodeActionUtils
import com.tom.rv2ide.lsp.java.visitors.FindPathAt
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.resources.R
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

    val diagnostic = data[DiagnosticItem::class.java]!!
    if (diagnosticCode != diagnostic.code) {
      markInvisible()
      return
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    val diagnostic = data[DiagnosticItem::class.java]!!
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
    if (result !is AddException) {
      return
    }

    performCodeAction(data, result)
  }


  private fun findUnhandledExceptionType(
      task: com.tom.rv2ide.lsp.java.compiler.CompileTask,
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
      task: com.tom.rv2ide.lsp.java.compiler.CompileTask,
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
      task: com.tom.rv2ide.lsp.java.compiler.CompileTask,
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