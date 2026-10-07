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
package com.tom.rv2ide.lsp.java.actions.generators

import com.tom.rv2ide.common.logging.IdeLogConfig
import android.content.Context
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.newDialogBuilder
import com.tom.rv2ide.actions.requireFile
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.lsp.java.JavaCompilerProvider
import com.tom.rv2ide.lsp.java.actions.BaseJavaCodeAction
import com.tom.rv2ide.lsp.java.compiler.CompileTask
import com.tom.rv2ide.lsp.java.compiler.CompilerProvider
import com.tom.rv2ide.lsp.java.parser.ParseTask
import com.tom.rv2ide.lsp.java.rewrite.AddImport
import com.tom.rv2ide.lsp.java.utils.EditHelper
import com.tom.rv2ide.lsp.java.utils.FindHelper
import com.tom.rv2ide.lsp.java.utils.JavaParserUtils
import com.tom.rv2ide.lsp.java.utils.MethodPtr
import com.tom.rv2ide.lsp.java.utils.MethodStubGenerator
import com.tom.rv2ide.lsp.java.visitors.FindAnonymousTypeDeclaration
import com.tom.rv2ide.lsp.java.visitors.FindTypeDeclarationAt
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.preferences.utils.indentationString
import com.tom.rv2ide.projects.IProjectManager

import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.flashError
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.IOException
import java.util.Arrays
import java.util.Optional
import java.util.concurrent.CompletableFuture

import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.Modifier
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.ExecutableType
import jdkx.tools.JavaFileObject
import openjdk.source.tree.LineMap
import openjdk.source.tree.MethodTree
import openjdk.source.util.SourcePositions
import openjdk.source.util.Trees

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
  private var position: Long = -1

  companion object {

    private val log = LoggerFactory.getLogger(OverrideSuperclassMethodsAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (
        !visible ||
            !data.hasRequiredData(com.tom.rv2ide.models.Range::class.java, CodeEditor::class.java)
    ) {
      markInvisible()
      return
    }

    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    val range = data[com.tom.rv2ide.models.Range::class.java]!!
    val compiler =
        JavaCompilerProvider.get(
            IProjectManager.getInstance()
                .getWorkspace()
                ?.findModuleForFile(data.requireFile(), false) ?: return Any()
        )
    val file = data.requirePath()
    return compiler.compile(file).get { task ->
      val fileRoot =
          try {
            task.root(file)
          } catch (e: Throwable) {
            return@get false
          }
      // 1-based line and column index
      val startLine = range.start.line + 1
      val startColumn = range.start.column + 1
      val endLine = range.end.line + 1
      val endColumn = range.end.column + 1
      val lines = fileRoot.lineMap
      val start = lines.getPosition(startLine.toLong(), startColumn.toLong())
      val end = lines.getPosition(endLine.toLong(), endColumn.toLong())


      if (start == (-1).toLong() || end == (-1).toLong()) {
        return@get false
      }

      this.position = start
      val typeFinder = FindTypeDeclarationAt(task.task)
      var type = typeFinder.scan(fileRoot, start)
      var classPath = typeFinder.path
      if (type == null) {
        type = typeFinder.scan(fileRoot, end)
        classPath = typeFinder.path
        position = end
      }

      if (type == null || classPath == null) {
        val anonymousFinder = FindAnonymousTypeDeclaration(task.task, fileRoot)
        type = anonymousFinder.scan(fileRoot, start)
        classPath = anonymousFinder.storedPath
        if (type == null || classPath == null) {
          type = anonymousFinder.scan(fileRoot, end)
          classPath = anonymousFinder.storedPath
          position = end
        }
      }

      if (type == null || classPath == null) {
        return@get false
      }

      val overridable = mutableListOf<MethodPtr>()
      val trees = Trees.instance(task.task)
      val elements = task.task.elements
      val element = trees.getElement(classPath) as TypeElement

      for (member in elements.getAllMembers(element)) {
        if (member.modifiers.contains(Modifier.FINAL) || member.kind != ElementKind.METHOD) {
          continue
        }

        val method = member as ExecutableElement
        if (method.simpleName.contentEquals("<init>")) {
          continue
        }
        val methodSource = member.getEnclosingElement() as TypeElement
        if (
            methodSource.qualifiedName.contentEquals("java.lang.Object") || methodSource == element
        ) {
          continue
        }

        val pointer = MethodPtr(task.task, method)
        overridable.add(pointer)
      }

      return@get overridable
    }
  }

  @Suppress("UNCHECKED_CAST")
  override fun postExec(data: ActionData, result: Any) {
    if (result !is List<*> || result.isEmpty() || position < 0) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn("Unable to find any overridable method")
      }
      flashError(data[Context::class.java]!!.getString(R.string.msg_no_overridable_methods))
      return
    }

    val methods = (result as List<MethodPtr>).sortedBy { it.methodName }
    val checkedMethods = mutableListOf<MethodPtr>()
    val names = mapMethodPointers(methods)
    val builder = newDialogBuilder(data)
    builder.setTitle(data[Context::class.java]!!.getString(R.string.msg_select_methods))
    builder.setMultiChoiceItems(names, BooleanArray(names.size)) { _, which, isChecked ->
      checkedMethods.apply {
        if (isChecked) {
          add(methods[which])
        } else {
          remove(methods[which])
        }
      }
    }
    builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
      dialog.dismiss()

      if (checkedMethods.isEmpty()) {
        flashError(data[Context::class.java]!!.getString(R.string.msg_no_methods_selected))
        return@setPositiveButton
      }

      CompletableFuture.runAsync { overrideMethods(data, checkedMethods) }
          .whenComplete { _, error,
            ->
            if (error != null) {
              if (IdeLogConfig.shouldLogError()) {
                log.error("An error occurred overriding methods", error)
              }
 
              ThreadUtils.runOnUiThread {
                flashError(
                    data[Context::class.java]!!.getString(R.string.msg_cannot_override_methods)
                )
              }
            }
          }
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  private fun overrideMethods(data: ActionData, checkedMethods: MutableList<MethodPtr>) {
    val compiler =
        JavaCompilerProvider.get(
            IProjectManager.getInstance()
                .getWorkspace()
                ?.findModuleForFile(data.requireFile(), false) ?: return
        )
    val file = data.requirePath()
    compiler.compile(file).run { task ->
      val fileRoot =
          try {
            task.root(file)
          } catch (e: Throwable) {
            return@run
          }
      val types = task.task.types
      val trees = Trees.instance(task.task)
      val sb = StringBuilder()
      val imports = mutableSetOf<String>()

      val typeFinder = FindTypeDeclarationAt(task.task)
      var classTreeCandidate = typeFinder.scan(fileRoot, position)
      var classPath = typeFinder.path
      if (classTreeCandidate == null || classPath == null) {
        val anonymousFinder = FindAnonymousTypeDeclaration(task.task, fileRoot)
        classTreeCandidate = anonymousFinder.scan(fileRoot, position)
        classPath = anonymousFinder.storedPath
      }
      if (classTreeCandidate == null || classPath == null) {
        return@run
      }
      val classTree: openjdk.source.tree.ClassTree = classTreeCandidate
      val thisClassCandidate = trees.getElement(classPath) as? TypeElement
      if (thisClassCandidate == null) {
        return@run
      }
      val thisClass: TypeElement = thisClassCandidate
      val insertPosition = EditHelper.insertAtEndOfClass(task.task, fileRoot, classTree)
      val braceIndent = EditHelper.lineIndent(task.task, fileRoot, classTree)
      val indent = braceIndent + EditorPreferences.tabSize
      val fileImports = fileRoot.imports.map { it.qualifiedIdentifier.toString() }.toSet()
      val filePackage = fileRoot.`package`.packageName.toString()
      val source =
          try {
            fileRoot.sourceFile.getCharContent(true)
          } catch (error: IOException) {
            log.warn("OverrideSuperclassMethods could not read source content", error)
            return@run
          }
      val sourcePositions: SourcePositions = Trees.instance(task.task).sourcePositions
      val treeStartOffset = sourcePositions.getStartPosition(fileRoot, classTree)
      val treeEndOffset = sourcePositions.getEndPosition(fileRoot, classTree)
      var openBraceOffset = -1
      var closeBraceOffset = -1
      for (i in maxOf(0L, treeStartOffset).toInt() until minOf(source.length, treeEndOffset.toInt())) {
        if (source[i] == '{') {
          openBraceOffset = i
          break
        }
      }
      for (i in minOf(treeEndOffset.toInt() - 1, source.length - 1) downTo maxOf(0L, treeStartOffset).toInt()) {
        if (source[i] == '}') {
          closeBraceOffset = i
          break
        }
      }
 
      for (pointer in checkedMethods) {
        val superMethod =
            FindHelper.findMethod(
                task,
                pointer.className,
                pointer.methodName,
                pointer.erasedParameterTypes,
            ) ?: continue

        val thisDeclaredType = thisClass.asType() as DeclaredType
        val executableType = types.asMemberOf(thisDeclaredType, superMethod) as ExecutableType
        val generated =
            MethodStubGenerator.generate(
                method = superMethod,
                parameterizedType = executableType,
                source = null,
                bodyStrategy = MethodStubGenerator.BodyStrategy.OVERRIDE_SUPER,
                module = task.module(),
             )
        val newImports = generated.imports
        val memberIndent = indentationString(indent)
        // Keep method text assembly explicit so anonymous-class insertions preserve
        // the pre-format brace layout for both compact and multi-line forms.
        sb.append("\n")
        val lines = generated.renderedText.replace("\r\n", "\n").split("\n")
        lines.forEachIndexed { lineIndex, line ->
          if (lineIndex > 0) {
            sb.append("\n")
          }
          sb.append(memberIndent)
          sb.append(line)
        }
        sb.append("\n")
        sb.append(indentationString(braceIndent))

        newImports.removeIf { importName ->
          importName.startsWith("java.lang.") ||
              importName.startsWith(filePackage) ||
              fileImports.contains(importName)
        }

        imports.addAll(newImports)
      }

      val targetPosition =
          if (closeBraceOffset >= 0) {
            // Insert immediately before the anonymous class closing brace so existing members are kept,
            // while the brace line itself stays in a valid pre-format position.
            val lineMap: LineMap = fileRoot.lineMap
            Position(
                lineMap.getLineNumber(closeBraceOffset.toLong()).toInt() - 1,
                lineMap.getColumnNumber(closeBraceOffset.toLong()).toInt() - 1,
                closeBraceOffset,
            )
          } else {
            insertPosition
          }
      ThreadUtils.runOnUiThread {
        performEdits(
            data,
            sb,
            imports,
            targetPosition,
        )
      }
    }
  }
  private fun performEdits(
      data: ActionData,
      sb: StringBuilder,
      imports: MutableSet<String>,
      position: Position,
  ) {
    val compiler =
        JavaCompilerProvider.get(
            IProjectManager.getInstance()
                .getWorkspace()
                ?.findModuleForFile(data.requireFile(), false) ?: return
        )
    val editor = data[CodeEditor::class.java]!!
    val file = data.requirePath()
    val text = editor.text

    text.beginBatchEdit()
    text.insert(position.line, position.column, sb)

    for (name in imports) {
      val rewrite = AddImport(file, name)
      val edits = rewrite.rewrite(compiler)[file]
      if (edits.isNullOrEmpty()) {
        continue
      }

      for (edit in edits) {
        if (edit.range.start == edit.range.end) {
          text.insert(edit.range.start.line, edit.range.start.column, edit.newText)
        } else {
          text.replace(
              edit.range.start.line,
              edit.range.start.column,
              edit.range.end.line,
              edit.range.end.column,
              edit.newText,
          )
        }
      }
    }

    text.endBatchEdit()
    editor.formatCodeAsync()
  }

  private fun mapMethodPointers(methods: List<MethodPtr>): Array<CharSequence> {
    return methods
        .map { Arrays.toString(it.simplifiedErasedParameterTypes) }
        .map {
          val arr = it.toCharArray()
          arr[0] = '('
          arr[arr.size - 1] = ')'

          String(arr)
        }
        .mapIndexed { index, params -> "${methods[index].methodName}$params" }
        .toTypedArray()
  }

  private fun findSource(
      compiler: CompilerProvider,
      task: CompileTask,
      method: ExecutableElement,
  ): MethodTree? {
    val superClass = method.enclosingElement as TypeElement
    val superClassName = superClass.qualifiedName.toString()
    val methodName = method.simpleName.toString()
    val erasedParameterTypes = FindHelper.erasedParameterTypes(task, method)
    val sourceFile: Optional<JavaFileObject> = compiler.findAnywhere(superClassName)
    if (!sourceFile.isPresent) return null
    val parse: ParseTask = compiler.parse(sourceFile.get())
    return FindHelper.findMethod(parse, superClassName, methodName, erasedParameterTypes)
  }
}
