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

package com.tom.rv2ide.language.compiler.javac.partial

import com.tom.rv2ide.language.compiler.javac.compiler.JavacFlowListener
import com.tom.rv2ide.language.compiler.javac.util.ReparserUtils
import com.tom.rv2ide.language.compiler.javac.visitors.DocCommentOwners
import com.tom.rv2ide.language.compiler.javac.visitors.ErrorAwareTreeScanner
import com.tom.rv2ide.language.compiler.javac.visitors.TranslateMethodPositionsVisitor
import java.nio.CharBuffer
import java.util.Arrays
import openjdk.source.tree.BlockTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.Tree.Kind.BLOCK
import openjdk.source.tree.Tree
import openjdk.source.tree.Tree.Kind.METHOD
import openjdk.source.tree.Tree.Kind.VARIABLE
import openjdk.source.util.TreePath
import openjdk.tools.javac.api.JavacScope
import openjdk.tools.javac.api.JavacTrees
import openjdk.tools.javac.code.Flags
import openjdk.tools.javac.code.Symbol
import openjdk.tools.javac.code.Symtab
import openjdk.tools.javac.code.Type
import openjdk.tools.javac.code.Types
import openjdk.tools.javac.comp.Attr
import openjdk.tools.javac.comp.AttrContext
import openjdk.tools.javac.comp.Check
import openjdk.tools.javac.comp.Enter
import openjdk.tools.javac.comp.Env
import openjdk.tools.javac.comp.Flow
import openjdk.tools.javac.comp.MemberEnter
import openjdk.tools.javac.parser.JavacParser
import openjdk.tools.javac.parser.Lexer
import openjdk.tools.javac.parser.LazyDocCommentTable.Entry
import openjdk.tools.javac.parser.ScannerFactory
import openjdk.tools.javac.parser.Tokens.TokenKind
import openjdk.tools.javac.tree.DocCommentTable
import openjdk.tools.javac.tree.EndPosTable
import openjdk.tools.javac.tree.JCTree
import openjdk.tools.javac.tree.JCTree.JCBlock
import openjdk.tools.javac.tree.JCTree.JCClassDecl
import openjdk.tools.javac.tree.JCTree.JCCompilationUnit
import openjdk.tools.javac.tree.JCTree.JCExpression
import openjdk.tools.javac.tree.JCTree.JCMethodDecl
import openjdk.tools.javac.tree.JCTree.JCModifiers
import openjdk.tools.javac.tree.JCTree.JCVariableDecl
import openjdk.tools.javac.tree.TreeInfo
import openjdk.tools.javac.tree.TreeMaker
import openjdk.tools.javac.util.Context
import openjdk.tools.javac.util.List
import openjdk.tools.javac.util.Log
import openjdk.tools.javac.util.Names
import org.slf4j.LoggerFactory

/**
 * In-place javac AST and semantic-state mutation for one member at a time.
 *
 * Seven in-place mutations are supported today, all of which leave the enclosing class member table
 * alone:
 *
 * - `reparseMethod` replaces a method or constructor body and re-attributes it in the method
 *   environment;
 * - `reparseFieldInitializer` replaces a field initializer expression and re-attributes it in the
 *   field's own environment;
 * - `reparseInitializerBlock` replaces a static or instance initializer block and re-attributes it in
 *   the class environment;
 * - `reparseMethodModifiers` rewrites the written modifiers of a method declaration;
 * - `reparseFieldModifiers` rewrites the written modifiers of a field declaration;
 * - `reparseClassModifiers` rewrites the written modifiers of a class declaration;
 * - `reparseThrowsClause` replaces or adds the declared throws types and rebuilds the method type.
 *
 * All entry points return `false` when the edit cannot be applied safely; callers then fall back to
 * a full compilation. `taskMutated` reports whether the cached task was already modified, so a failed
 * attempt can never be retried against the same task.
 */
class InPlaceReparser {

  /** True when the current attempt changed the cached javac AST or its semantic state. */
  var taskMutated: Boolean = false
    private set

  private val allowPartialReparse: Boolean = ReparserUtils.canReparse()

  companion object {
    private val log = LoggerFactory.getLogger(InPlaceReparser::class.java)
  }

  fun reparseMethod(
      ci: CompilationInfo,
      methodPath: TreePath,
      newBody: String,
      fileContents: CharSequence,
  ): Boolean {
    taskMutated = false
    var partialReparseCommitted = false

    if (!allowPartialReparse) {
      return false
    }

    val cu = ci.cu
    val fo = cu.sourceFile
    val task = ci.task
    if (methodPath.leaf.kind != METHOD) {
      log.warn("Provided TreePath does not correspond to a MethodTree")
      return false
    }
    val method = methodPath.leaf as JCMethodDecl

    // The body path would make JavacTrees copy and attribute the old body, which registers copy
    // symbols for local/anonymous classes. The declaration path only builds the method env.
    val methodEnv = ci.trees.getScope(methodPath)
    if (methodEnv.env.enclMethod?.sym !== method.sym) {
      log.debug("Unable to resolve the method environment for {}", method.name)
      return false
    }
    val jt = JavacTrees.instance(task)
    val origStartPos = jt.sourcePositions.getStartPosition(cu, method.body).toInt()
    val origEndPos = jt.sourcePositions.getEndPosition(cu, method.body).toInt()
    if (origStartPos < 0) {
      log.warn("Javac returned start position {} < 0", origStartPos)
      return false
    }

    if (origStartPos > origEndPos) {
      log.warn("Java returned start position: {} > end position: {}", origStartPos, origEndPos)
      return false
    }

    // Collected from the old body so the doc-comment table can drop stale owners. Local and
    // anonymous classes are allowed here: doUnenter removes their symbols before the new body is
    // entered again, which is what makes local-class edits repairable in place.
    val oldBodyDocs = DocCommentOwners()
    oldBodyDocs.scan(method.body, null)
    val context = task.context

    try {
      val l = com.tom.rv2ide.language.compiler.javac.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      val block: JCBlock?
      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(origStartPos, origEndPos)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin method={} source={} sourceUri={} origStartPos={} origEndPos={} newBodyLength={} errorType={} errorMessage={}",
              method.name,
              fo,
              fo.toUri(),
              origStartPos,
              origEndPos,
              newBody.length,
              err.javaClass.name,
              err.message,
              err,
          )
          return false
        }
        val docComments = HashMap<JCTree, Entry>()
        block = try {
          reparseMethodBody(context, cu, method, newBody, docComments)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=parse method={} origStartPos={} origEndPos={} newBodyLength={}",
              method.name,
              origStartPos,
              origEndPos,
              newBody.length,
              err,
          )
          return false
        }
        val endPosTable = (cu as JCCompilationUnit).endPositions
        if (block == null) {
          return false
        }

        val sourcePositions = jt.sourcePositions
        val newEndPos = sourcePositions.getEndPosition(cu, block).toInt()
        if (newEndPos != origStartPos + newBody.length) {
          return false
        }

        // End positions/doc-comments/unenter have already started mutating the cached task.
        taskMutated = true
        val cuDocComments = cu.docComments
        if (cuDocComments != null) {
          val docCommentsTable = try {
            getLazyDocCommentsTable(cuDocComments)
          } catch (err: Throwable) {
            log.error(
                "Partial reparse stage failed: stage=doc-comments-get-table method={} origStartPos={} origEndPos={} newBodyLength={} addedDocComments={} removedOwners={} errorType={} errorMessage={}",
                method.name,
                origStartPos,
                origEndPos,
                newBody.length,
                  docComments.size,
                oldBodyDocs.owners.size,
                err.javaClass.name,
                err.message,
                err,
            )
            return false
          }
          try {
            docCommentsTable.keys.removeAll(oldBodyDocs.owners)
          } catch (err: Throwable) {
            log.error(
                "Partial reparse stage failed: stage=doc-comments-remove method={} origStartPos={} origEndPos={} newBodyLength={} tableSize={} addedDocComments={} removedOwners={} errorType={} errorMessage={}",
                method.name,
                origStartPos,
                origEndPos,
                newBody.length,
                  docCommentsTable.size,
                docComments.size,
                oldBodyDocs.owners.size,
                err.javaClass.name,
                err.message,
                err,
            )
            return false
          }
          try {
            docCommentsTable.putAll(docComments)
          } catch (err: Throwable) {
            log.error(
                "Partial reparse stage failed: stage=doc-comments-put method={} origStartPos={} origEndPos={} newBodyLength={} tableSize={} addedDocComments={} removedOwners={} errorType={} errorMessage={}",
                method.name,
                origStartPos,
                origEndPos,
                newBody.length,
                  docCommentsTable.size,
                docComments.size,
                oldBodyDocs.owners.size,
                err.javaClass.name,
                err.message,
                err,
            )
            return false
          }
        }
        val delta = newEndPos - origEndPos
        try {
          val tpv = TranslateMethodPositionsVisitor(method, endPosTable, delta)
          tpv.scan(cu, null)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=translate-positions method={} origStartPos={} origEndPos={} newBodyLength={} delta={}",
              method.name,
              origStartPos,
              origEndPos,
              newBody.length,
              delta,
              err,
          )
          return false
        }
        try {
          doUnenter(context, cu, method)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=unenter method={} origStartPos={} origEndPos={} newBodyLength={}",
              method.name,
              origStartPos,
              origEndPos,
              newBody.length,
              err,
          )
          return false
        }
        // From this point the cached task is no longer safely retryable as the old task.
        method.body = block
        taskMutated = true
        try {
          reAttrMethodBody(context, methodEnv, method, block)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=reattr method={} origStartPos={} origEndPos={} newBodyLength={}",
              method.name,
              origStartPos,
              origEndPos,
              newBody.length,
              err,
          )
          return false
        }

        if (!dl.hasPartialReparseErrors()) {
          val fl = JavacFlowListener.instance(context)
          if (fl != null && fl.hasFlowCompleted(cu.sourceFile)) {
            val tp = TreePath.getPath(cu, method)
            val t = tp.parentPath.leaf as ClassTree
            try {
              reflowMethodBody(context, t, method)
            } catch (err: Throwable) {
              log.error(
                  "Partial reparse stage failed: stage=reflow method={} origStartPos={} origEndPos={} newBodyLength={}",
                  method.name,
                  origStartPos,
                  origEndPos,
                  newBody.length,
                      err,
              )
              return false
            }
          }
        }

        val arr = CharArray(fileContents.length)
        for (index in fileContents.indices) {
          arr[index] = fileContents[index]
        }

        try {
          buildLineMap(cu, arr)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=line-map method={} origStartPos={} origEndPos={} newBodyLength={}",
              method.name,
              origStartPos,
              origEndPos,
              newBody.length,
              err,
          )
          return false
        }
        try {
          dl.endPartialReparse(delta)
          partialReparseCommitted = true
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-end method={} origStartPos={} origEndPos={} newBodyLength={} delta={}",
              method.name,
              origStartPos,
              origEndPos,
              newBody.length,
              delta,
              err,
          )
          return false
        }
      } finally {
        if (!partialReparseCommitted) {
          try {
            (ci.diagnosticListener as? DiagnosticListenerImpl)?.abortPartialReparse()
          } catch (ignored: Throwable) {
          }
        }
        l.endPartialReparse(cu.sourceFile)
        l.useSource(prevLogged)
      }
    } catch (err: Throwable) {
      log.error(
          "An error occurred while applying legacy AST mutation method={} origStartPos={} origEndPos={} newBodyLength={}",
          method.name,
          origStartPos,
          origEndPos,
          newBody.length,
          err,
      )
      return false
    }

    return true
  }

  /**
   * Replaces the initializer expression of a class-member field in place.
   *
   * The field symbol is unchanged, so the new expression is only re-attributed in the field's own
   * environment; the class member table is never re-entered.
   */
  fun reparseFieldInitializer(
      ci: CompilationInfo,
      fieldPath: TreePath,
      newInitializer: String,
      fileContents: CharSequence,
  ): Boolean {
    taskMutated = false
    var committed = false
    if (!allowPartialReparse) {
      return false
    }
    if (fieldPath.leaf.kind != VARIABLE) {
      return false
    }
    val field = fieldPath.leaf as JCVariableDecl
    val oldInitializer = field.init ?: return false
    val fieldSymbol = field.sym ?: return false
    val classPath = fieldPath.parentPath ?: return false
    val classEnv = ci.trees.getScope(classPath)
    if (classEnv.env.enclClass?.sym !== fieldSymbol.owner) {
      log.debug("Unable to resolve the field environment for {}", field.name)
      return false
    }
    val initEnv = MemberEnter.instance(ci.task.context).getInitEnv(field, classEnv.env) ?: return false

    val cu = ci.cu
    val fo = cu.sourceFile
    val context = ci.task.context
    val jt = JavacTrees.instance(ci.task)
    val origStartPos = jt.sourcePositions.getStartPosition(cu, oldInitializer).toInt()
    val origEndPos = jt.sourcePositions.getEndPosition(cu, oldInitializer).toInt()
    if (origStartPos < 0 || origStartPos > origEndPos) {
      return false
    }

    try {
      val l = com.tom.rv2ide.language.compiler.javac.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(origStartPos, origEndPos)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin field={} source={} origStartPos={} origEndPos={} newInitializerLength={} errorType={} errorMessage={}",
              field.name,
              fo,
              origStartPos,
              origEndPos,
              newInitializer.length,
              err.javaClass.name,
              err.message,
              err,
          )
          return false
        }

        val startPos = oldInitializer.pos
        val buffer = CharArray(startPos + newInitializer.length + 1)
        Arrays.fill(buffer, ' ')
        for (index in newInitializer.indices) {
          buffer[startPos + index] = newInitializer[index]
        }
        buffer[startPos + newInitializer.length] = '\u0000'

        val parsed = try {
          val parser =
              newParser(
                  context,
                  CharBuffer.wrap(buffer, 0, buffer.size - 1),
                  (cu as JCCompilationUnit).endPositions,
              )
          parser.parseExpression()
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=parse field={} origStartPos={} origEndPos={} newInitializerLength={}",
              field.name,
              origStartPos,
              origEndPos,
              newInitializer.length,
              err,
          )
          return false
        }

        val newEndPos = jt.sourcePositions.getEndPosition(cu, parsed).toInt()
        if (newEndPos != origStartPos + newInitializer.length) {
          return false
        }

        // The old initializer may have introduced local or anonymous classes; drop their symbols
        // before the replacement is attributed again.
        taskMutated = true
        ReparserUtils.unenter(context, cu, oldInitializer)
        TranslatePositionsAfter(
                (cu as JCCompilationUnit).endPositions, origEndPos, newEndPos - origEndPos)
            .scan(cu, null)

        field.init = parsed
        Attr.instance(context).attribExpr(parsed, initEnv, fieldSymbol.type)

        val characters = CharArray(fileContents.length)
        for (index in fileContents.indices) {
          characters[index] = fileContents[index]
        }
        buildLineMap(cu, characters)
        dl.endPartialReparse(newEndPos - origEndPos)
        committed = true
      } finally {
        if (!committed) {
          try {
            (ci.diagnosticListener as? DiagnosticListenerImpl)?.abortPartialReparse()
          } catch (ignored: Throwable) {
            // Rollback failures must not mask the original stage failure.
          }
        }
        l.endPartialReparse(fo)
        l.useSource(prevLogged)
      }
    } catch (err: Throwable) {
      log.error(
          "An error occurred while applying the initializer mutation field={} origStartPos={} origEndPos={} newInitializerLength={}",
          field.name,
          origStartPos,
          origEndPos,
          newInitializer.length,
          err,
      )
      return false
    }

    return true
  }

  /**
   * Replaces a static or instance initializer block in place.
   *
   * The new block is re-attributed in the class environment; javac gives class-level blocks their own
   * BLOCK scope, so the class member table is untouched. Instance/static initializer blocks have no
   * flow information of their own, so no reflow step is required.
   */
  fun reparseInitializerBlock(
      ci: CompilationInfo,
      blockPath: TreePath,
      newBlock: String,
      fileContents: CharSequence,
  ): Boolean {
    taskMutated = false
    var committed = false
    if (!allowPartialReparse) {
      return false
    }
    if (blockPath.leaf.kind != BLOCK) {
      return false
    }
    val oldBlock = blockPath.leaf as JCBlock
    val classPath = blockPath.parentPath ?: return false
    val classDecl = classPath.leaf as? JCClassDecl ?: return false
    val classEnv = ci.trees.getScope(classPath)
    if (classEnv.env.enclClass !== classDecl) {
      log.debug("Unable to resolve the class environment for an initializer block")
      return false
    }

    val cu = ci.cu
    val fo = cu.sourceFile
    val context = ci.task.context
    val jt = JavacTrees.instance(ci.task)
    val origStartPos = jt.sourcePositions.getStartPosition(cu, oldBlock).toInt()
    val origEndPos = jt.sourcePositions.getEndPosition(cu, oldBlock).toInt()
    if (origStartPos < 0 || origStartPos > origEndPos) {
      return false
    }

    try {
      val l = com.tom.rv2ide.language.compiler.javac.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(origStartPos, origEndPos)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin block=initializer source={} origStartPos={} origEndPos={} newBlockLength={} errorType={} errorMessage={}",
              fo,
              origStartPos,
              origEndPos,
              newBlock.length,
              err.javaClass.name,
              err.message,
              err,
          )
          return false
        }

        val startPos = oldBlock.pos
        val buffer = CharArray(startPos + newBlock.length + 1)
        Arrays.fill(buffer, ' ')
        for (index in newBlock.indices) {
          buffer[startPos + index] = newBlock[index]
        }
        buffer[startPos + newBlock.length] = '\u0000'

        val parsed = try {
          val parser =
              newParser(
                  context,
                  CharBuffer.wrap(buffer, 0, buffer.size - 1),
                  (cu as JCCompilationUnit).endPositions,
              )
          parser.parseStatement()
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=parse block=initializer origStartPos={} origEndPos={} newBlockLength={}",
              origStartPos,
              origEndPos,
              newBlock.length,
              err,
          )
          return false
        }

        if (parsed == null || parsed.kind != BLOCK) {
          return false
        }
        val newEndPos = jt.sourcePositions.getEndPosition(cu, parsed).toInt()
        if (newEndPos != origStartPos + newBlock.length) {
          return false
        }

        // The old block may have introduced local or anonymous classes; drop their symbols first.
        taskMutated = true
        ReparserUtils.unenter(context, cu, oldBlock)
        TranslatePositionsAfter(
                (cu as JCCompilationUnit).endPositions, origEndPos, newEndPos - origEndPos)
            .scan(cu, null)

        replaceClassMember(classDecl, oldBlock, parsed as JCBlock)
        Attr.instance(context).attribStat(parsed, classEnv.env)

        val characters = CharArray(fileContents.length)
        for (index in fileContents.indices) {
          characters[index] = fileContents[index]
        }
        buildLineMap(cu, characters)
        dl.endPartialReparse(newEndPos - origEndPos)
        committed = true
      } finally {
        if (!committed) {
          try {
            (ci.diagnosticListener as? DiagnosticListenerImpl)?.abortPartialReparse()
          } catch (ignored: Throwable) {
            // Rollback failures must not mask the original stage failure.
          }
        }
        l.endPartialReparse(fo)
        l.useSource(prevLogged)
      }
    } catch (err: Throwable) {
      log.error(
          "An error occurred while applying the initializer block mutation origStartPos={} origEndPos={} newBlockLength={}",
          origStartPos,
          origEndPos,
          newBlock.length,
          err,
      )
      return false
    }

    return true
  }

  /**
   * Replaces the written modifiers of a method or constructor declaration in place.
   *
   * The declaration keeps its symbol: only the flag bits that the edit actually changed are rewritten
   * on [JCMethodDecl.mods] and on [MethodSymbol.flags_field], so parameters, return type, type
   * parameters, throws clause and body identity stay untouched. Annotations are not re-attributed,
   * therefore any edit that touches one is rejected instead of silently dropping it.
   */
  fun reparseMethodModifiers(
      ci: CompilationInfo,
      methodPath: TreePath,
      newModifiers: String,
      declarationStart: Int,
      oldContents: CharSequence,
      newContents: CharSequence,
      windowStart: Int,
      windowEnd: Int,
  ): Boolean {
    taskMutated = false
    var committed = false
    if (!allowPartialReparse) {
      return false
    }
    if (methodPath.leaf.kind != METHOD) {
      return false
    }
    val method = methodPath.leaf as JCMethodDecl
    val mods = method.mods ?: return false
    val symbol = method.sym ?: return false
    if (declarationStart < 0) {
      return false
    }

    val cu = ci.cu
    val fo = cu.sourceFile
    val context = ci.task.context
    val endPositions = (cu as JCCompilationUnit).endPositions
    val delta = newContents.length - oldContents.length

    try {
      val l = com.tom.rv2ide.language.compiler.javac.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(windowStart, windowEnd)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin unit=modifiers windowStart={} windowEnd={} errorType={} errorMessage={}",
              windowStart,
              windowEnd,
              err.javaClass.name,
              err.message,
              err,
          )
          return false
        }

        val parsed = try {
          parseDeclarationModifiers(context, declarationStart, newModifiers)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=parse unit=modifiers declarationStart={} newModifiersLength={}",
              declarationStart,
              newModifiers.length,
              err,
          )
          return false
        }

        if (dl.hasPartialReparseErrors()) {
          return false
        }
        if (!annotationsUntouched(ci, cu, mods, parsed, windowStart, windowEnd)) {
          return false
        }

        val allowed = (Flags.MethodFlags and (Flags.ABSTRACT or Flags.NATIVE).inv()).toLong()
        val methodFlags = Flags.MethodFlags.toLong()
        val changed = (parsed.flags and methodFlags) xor (mods.flags and methodFlags)
        val added = parsed.flags and mods.flags.inv() and methodFlags.inv()
        if (added != 0L || (changed and allowed.inv()) != 0L) {
          return false
        }

        taskMutated = true
        symbol.flags_field = (symbol.flags_field and changed.inv()) or (parsed.flags and changed)
        if (delta != 0) {
          TranslatePositionsAfter(endPositions, windowStart, delta).scan(cu, null)
        }
        mods.flags = (mods.flags and methodFlags.inv()) or (parsed.flags and methodFlags)
        mods.pos = parsed.pos
        buildLineMap(cu, charactersOf(newContents))
        dl.endPartialReparse(delta)
        committed = true
      } finally {
        if (!committed) {
          try {
            (ci.diagnosticListener as? DiagnosticListenerImpl)?.abortPartialReparse()
          } catch (ignored: Throwable) {
            // Rollback failures must not mask the original stage failure.
          }
        }
        l.endPartialReparse(fo)
        l.useSource(prevLogged)
      }
    } catch (err: Throwable) {
      log.error(
          "An error occurred while applying the declaration modifiers declarationStart={} newModifiersLength={} delta={}",
          declarationStart,
          newModifiers.length,
          delta,
          err,
      )
      return false
    }

    return true
  }

  /**
   * Replaces the written modifiers of a class declaration in place.
   *
   * Only access, {@code final}, {@code abstract}, {@code static} and {@code strictfp} may change.
   * Structural bits such as {@code interface}, {@code enum} or {@code record} are rejected, and the
   * legality of the resulting combination is not re-checked here.
   */
  fun reparseClassModifiers(
      ci: CompilationInfo,
      classPath: TreePath,
      newModifiers: String,
      declarationStart: Int,
      oldContents: CharSequence,
      newContents: CharSequence,
      windowStart: Int,
      windowEnd: Int,
  ): Boolean {
    taskMutated = false
    var committed = false
    if (!allowPartialReparse) {
      return false
    }
    val classDecl = classPath.leaf as? JCClassDecl ?: return false
    val mods = classDecl.mods ?: return false
    val symbol = classDecl.sym ?: return false
    if (declarationStart < 0) {
      return false
    }

    val cu = ci.cu
    val fo = cu.sourceFile
    val context = ci.task.context
    val endPositions = (cu as JCCompilationUnit).endPositions
    val delta = newContents.length - oldContents.length

    try {
      val l = com.tom.rv2ide.language.compiler.javac.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(windowStart, windowEnd)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin unit=class-modifiers windowStart={} windowEnd={} errorType={} errorMessage={}",
              windowStart,
              windowEnd,
              err.javaClass.name,
              err.message,
              err,
          )
          return false
        }

        val parsed = try {
          parseDeclarationModifiers(context, declarationStart, newModifiers)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=parse unit=class-modifiers declarationStart={} newModifiersLength={}",
              declarationStart,
              newModifiers.length,
              err,
          )
          return false
        }

        if (dl.hasPartialReparseErrors()) {
          return false
        }
        if (!annotationsUntouched(ci, cu, mods, parsed, windowStart, windowEnd)) {
          return false
        }

        val classFlags =
            (Flags.AccessFlags or Flags.FINAL or Flags.ABSTRACT or Flags.STATIC or Flags.STRICTFP)
                .toLong()
        val changed = (parsed.flags and classFlags) xor (mods.flags and classFlags)
        val added = parsed.flags and mods.flags.inv() and classFlags.inv()
        if (added != 0L) {
          return false
        }

        taskMutated = true
        symbol.flags_field = (symbol.flags_field and changed.inv()) or (parsed.flags and changed)
        if (delta != 0) {
          TranslatePositionsAfter(endPositions, windowStart, delta).scan(cu, null)
        }
        mods.flags = (mods.flags and classFlags.inv()) or (parsed.flags and classFlags)
        mods.pos = parsed.pos
        buildLineMap(cu, charactersOf(newContents))
        dl.endPartialReparse(delta)
        committed = true
      } finally {
        if (!committed) {
          try {
            (ci.diagnosticListener as? DiagnosticListenerImpl)?.abortPartialReparse()
          } catch (ignored: Throwable) {
            // Rollback failures must not mask the original stage failure.
          }
        }
        l.endPartialReparse(fo)
        l.useSource(prevLogged)
      }
    } catch (err: Throwable) {
      log.error(
          "An error occurred while applying the class modifiers declarationStart={} newModifiersLength={} delta={}",
          declarationStart,
          newModifiers.length,
          delta,
          err,
      )
      return false
    }

    return true
  }

  /**
   * Replaces the written modifiers of a class-member field in place.
   *
   * Only flags that cannot change initialization semantics are accepted: access, {@code volatile} and
   * {@code transient}. {@code final} and {@code static} stay rejected because they decide constant
   * folding and initialization order, which this path does not recompute.
   */
  fun reparseFieldModifiers(
      ci: CompilationInfo,
      fieldPath: TreePath,
      newModifiers: String,
      declarationStart: Int,
      oldContents: CharSequence,
      newContents: CharSequence,
      windowStart: Int,
      windowEnd: Int,
  ): Boolean {
    taskMutated = false
    var committed = false
    if (!allowPartialReparse) {
      return false
    }
    if (fieldPath.leaf.kind != VARIABLE) {
      return false
    }
    val field = fieldPath.leaf as JCVariableDecl
    val mods = field.mods ?: return false
    val symbol = field.sym ?: return false
    if (declarationStart < 0) {
      return false
    }

    val cu = ci.cu
    val fo = cu.sourceFile
    val context = ci.task.context
    val endPositions = (cu as JCCompilationUnit).endPositions
    val delta = newContents.length - oldContents.length

    try {
      val l = com.tom.rv2ide.language.compiler.javac.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(windowStart, windowEnd)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin unit=field-modifiers windowStart={} windowEnd={} errorType={} errorMessage={}",
              windowStart,
              windowEnd,
              err.javaClass.name,
              err.message,
              err,
          )
          return false
        }

        val parsed = try {
          parseDeclarationModifiers(context, declarationStart, newModifiers)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=parse unit=field-modifiers declarationStart={} newModifiersLength={}",
              declarationStart,
              newModifiers.length,
              err,
          )
          return false
        }

        if (dl.hasPartialReparseErrors()) {
          return false
        }
        if (!annotationsUntouched(ci, cu, mods, parsed, windowStart, windowEnd)) {
          return false
        }

        val fieldFlags = (Flags.AccessFlags or Flags.VOLATILE or Flags.TRANSIENT).toLong()
        val changed = (parsed.flags and fieldFlags) xor (mods.flags and fieldFlags)
        val added = parsed.flags and mods.flags.inv() and fieldFlags.inv()
        if (added != 0L) {
          return false
        }

        taskMutated = true
        symbol.flags_field = (symbol.flags_field and changed.inv()) or (parsed.flags and changed)
        if (delta != 0) {
          TranslatePositionsAfter(endPositions, windowStart, delta).scan(cu, null)
        }
        mods.flags = (mods.flags and fieldFlags.inv()) or (parsed.flags and fieldFlags)
        mods.pos = parsed.pos
        buildLineMap(cu, charactersOf(newContents))
        dl.endPartialReparse(delta)
        committed = true
      } finally {
        if (!committed) {
          try {
            (ci.diagnosticListener as? DiagnosticListenerImpl)?.abortPartialReparse()
          } catch (ignored: Throwable) {
            // Rollback failures must not mask the original stage failure.
          }
        }
        l.endPartialReparse(fo)
        l.useSource(prevLogged)
      }
    } catch (err: Throwable) {
      log.error(
          "An error occurred while applying the field modifiers declarationStart={} newModifiersLength={} delta={}",
          declarationStart,
          newModifiers.length,
          delta,
          err,
      )
      return false
    }

    return true
  }

  /**
   * Replaces the throws clause of a method or constructor in place.
   *
   * An existing clause is replaced inside its declared types. With [addingClause] the text is an
   * insertion in front of the body and must start with the {@code throws} keyword, which is how a
   * clause is added to a declaration that has none.
   *
   * Each new thrown type is attributed in the method environment and validated against
   * `java.lang.Throwable`, then the method type is rebuilt with [Types.createMethodTypeWithThrown].
   */
  fun reparseThrowsClause(
      ci: CompilationInfo,
      methodPath: TreePath,
      newThrows: String,
      throwsStart: Int,
      oldContents: CharSequence,
      newContents: CharSequence,
      windowStart: Int,
      windowEnd: Int,
      addingClause: Boolean,
  ): Boolean {
    taskMutated = false
    var committed = false
    if (!allowPartialReparse) {
      return false
    }
    if (methodPath.leaf.kind != METHOD) {
      return false
    }
    val method = methodPath.leaf as JCMethodDecl
    val symbol = method.sym ?: return false
    if (throwsStart < 0) {
      return false
    }

    val cu = ci.cu
    val fo = cu.sourceFile
    val context = ci.task.context
    val endPositions = (cu as JCCompilationUnit).endPositions
    val delta = newContents.length - oldContents.length

    try {
      val l = com.tom.rv2ide.language.compiler.javac.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(windowStart, windowEnd)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin unit=throws windowStart={} windowEnd={} errorType={} errorMessage={}",
              windowStart,
              windowEnd,
              err.javaClass.name,
              err.message,
              err,
          )
          return false
        }

        val parsed = try {
          if (addingClause) {
            parseAddedThrowsClause(context, throwsStart, newThrows)
          } else {
            parseThrownTypes(context, throwsStart, newThrows)
          }
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=parse unit=throws throwsStart={} newThrowsLength={}",
              throwsStart,
              newThrows.length,
              err,
          )
          return false
        }

        if (parsed.isEmpty()) {
          return false
        }

        val methodEnv = ci.trees.getScope(methodPath)
        if (methodEnv.env.enclMethod?.sym !== symbol) {
          log.debug("Unable to resolve the method environment for {}", method.name)
          return false
        }

        val attr = Attr.instance(context)
        val check = Check.instance(context)
        val syms = Symtab.instance(context)
        val types = Types.instance(context)
        var thrownExprs: List<JCExpression> = List.nil()
        var thrownTypes: List<Type> = List.nil()
        try {
          for (expression in parsed) {
            val thrownType = attr.attribType(expression, methodEnv.env)
            if (thrownType.isErroneous || !types.isSubtype(thrownType, syms.throwableType)) {
              return false
            }
            check.checkType(expression.pos(), thrownType, syms.throwableType)
            thrownExprs = thrownExprs.append(expression)
            thrownTypes = thrownTypes.append(thrownType)
          }
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=attribute unit=throws throwsStart={} newThrowsLength={}",
              throwsStart,
              newThrows.length,
              err,
          )
          return false
        }

        if (dl.hasPartialReparseErrors()) {
          return false
        }

        taskMutated = true
        if (delta != 0) {
          TranslatePositionsAfter(endPositions, windowStart, delta).scan(cu, null)
        }
        method.thrown = thrownExprs
        symbol.type = types.createMethodTypeWithThrown(symbol.type, thrownTypes)
        method.type = symbol.type
        buildLineMap(cu, charactersOf(newContents))
        dl.endPartialReparse(delta)
        committed = true
      } finally {
        if (!committed) {
          try {
            (ci.diagnosticListener as? DiagnosticListenerImpl)?.abortPartialReparse()
          } catch (ignored: Throwable) {
            // Rollback failures must not mask the original stage failure.
          }
        }
        l.endPartialReparse(fo)
        l.useSource(prevLogged)
      }
    } catch (err: Throwable) {
      log.error(
          "An error occurred while applying the throws clause throwsStart={} newThrowsLength={} delta={}",
          throwsStart,
          newThrows.length,
          delta,
          err,
      )
      return false
    }

    return true
  }

  /** Replaces [oldMember] with [newMember] inside [classDecl], preserving declaration order. */
  private fun replaceClassMember(classDecl: JCClassDecl, oldMember: JCTree, newMember: JCTree) {
    var updated: List<JCTree> = List.nil()
    for (member in classDecl.defs) {
      updated = updated.append(if (member === oldMember) newMember else member)
    }
    classDecl.defs = updated
  }

  /** Shifts the positions of every node that starts at or after [changedEnd] by [delta]. */
  private class TranslatePositionsAfter(
      private val endPositions: EndPosTable,
      private val changedEnd: Int,
      private val delta: Int,
  ) : ErrorAwareTreeScanner<Unit, Unit>() {

    override fun scan(node: Tree?, p: Unit?) {
      if (node is JCTree && node.pos >= changedEnd) {
        node.pos += delta
      }
      super.scan(node, p)
      if (node is JCTree) {
        val end = endPositions.replaceTree(node, null)
        if (end >= 0) {
          endPositions.storeEnd(node, if (end >= changedEnd) end + delta else end)
        }
      }
    }
  }

  private fun buildLineMap(cu: JCCompilationUnit, arr: CharArray) {
    ReparserUtils.buildLineMap(cu.getLineMap(), arr, arr.size)
  }

  private fun getLazyDocCommentsTable(cu: JCCompilationUnit): MutableMap<JCTree, Entry> {
    return getLazyDocCommentsTable(cu.docComments)
  }

  private fun getLazyDocCommentsTable(docs: DocCommentTable): MutableMap<JCTree, Entry> {
    return ReparserUtils.getLazyDocCommentsTable(docs)!!
  }

  private fun doUnenter(context: Context?, cu: CompilationUnitTree, method: JCMethodDecl) {
    if (cu !is JCCompilationUnit) {
      throw IllegalStateException()
    }

    ReparserUtils.unenter(context, cu, method.body)
  }

  private fun reparseMethodBody(
      context: Context,
      cu: CompilationUnitTree,
      method: JCMethodDecl,
      newBody: String,
      docComments: MutableMap<JCTree, Entry>,
  ): JCBlock? {
    val startPos = method.body.pos
    val body = CharArray(startPos + newBody.length + 1)
    Arrays.fill(body, ' ')
    for (index in newBody.indices) {
      body[startPos + index] = newBody[index]
    }
    body[startPos + newBody.length] = '\u0000'
    val buf = CharBuffer.wrap(body, 0, body.size - 1)
    val parser = newParser(context, buf, (cu as JCCompilationUnit).endPositions)
    val statement = parser.parseStatement()
    if (statement.kind == BLOCK) {
      docComments.putAll(getLazyDocCommentsTable(getParserDocComments(parser)))
      return statement as JCBlock
    }
    return null
  }

  private fun getParserDocComments(parser: JavacParser): DocCommentTable {
    return ReparserUtils.getDocComments(parser)!!
  }

  private fun newParser(
      context: Context,
      buf: CharBuffer?,
      endPositions: EndPosTable?,
  ): JavacParser {
    val factory =
        com.tom.rv2ide.language.compiler.javac.NBParserFactory.instance(context)
            as com.tom.rv2ide.language.compiler.javac.NBParserFactory
    val scannerFactory = ScannerFactory.instance(context)
    val cancelService = com.tom.rv2ide.language.compiler.javac.CancelService.instance(context)
    val lexer = scannerFactory.newScanner(buf, true)
    if (
        endPositions is com.tom.rv2ide.language.compiler.javac.NBParserFactory.NBJavacParser.EndPosTableImpl
    ) {
      endPositions.resetErrorEndPos()
    }

    return object :
        com.tom.rv2ide.language.compiler.javac.NBParserFactory.NBJavacParser(
            factory,
            lexer,
            true,
            false,
            true,
            false,
            cancelService,
        ) {
      override fun newEndPosTable(keepEndPositions: Boolean): AbstractEndPosTable {
        return object : AbstractEndPosTable(this) {
          override fun storeEnd(tree: JCTree?, endpos: Int) {
            (endPositions as EndPosTableImpl).storeEnd(tree, endpos)
          }

          override fun <T : JCTree?> to(t: T): T {
            storeEnd(t, token.endPos)
            return t
          }

          override fun <T : JCTree?> toP(t: T): T {
            storeEnd(t, S.prevToken().endPos)
            return t
          }

          override fun getEndPos(tree: JCTree?): Int {
            return endPositions!!.getEndPos(tree)
          }

          override fun replaceTree(oldtree: JCTree?, newtree: JCTree?): Int {
            return endPositions!!.replaceTree(oldtree, newtree)
          }

          override fun setErrorEndPos(errPos: Int) {
            super.setErrorEndPos(errPos)
            (endPositions as EndPosTableImpl).setErrorEndPos(errPos)
          }
        }
      }
    }
  }

  private fun reAttrMethodBody(
      context: Context,
      scope: JavacScope,
      tree: JCMethodDecl,
      block: JCBlock,
  ): JCBlock {
    val attr = Attr.instance(context)
    val names: Names = Names.instance(context)
    val syms: Symtab = Symtab.instance(context)
    val log: Log = Log.instance(context)
    val make: TreeMaker = TreeMaker.instance(context)
    val env: Env<AttrContext> = scope.env // this is a copy anyway...
    val owner: Symbol.ClassSymbol = env.enclClass.sym

    if (tree.name === names.init && !owner.type.isErroneous && owner.type !== syms.objectType) {
      val body = tree.body
      if (body.stats.isEmpty() || !TreeInfo.isSelfCall(body.stats.head)) {
        body.stats =
            body.stats.prepend(
                make.at(body.pos).Exec(make.Apply(List.nil(), make.Ident(names._super), List.nil()))
            )
      } else if (
          (env.enclClass.sym.flags() and Flags.ENUM.toLong()) != 0L &&
              (tree.mods.flags and Flags.GENERATEDCONSTR) == 0L &&
              TreeInfo.isSuperCall(body.stats.head)
      ) {
        // enum constructors are not allowed to call super
        // directly, so make sure there aren't any super calls
        // in enum constructorObjectCheckers, except in the compiler
        // generated one.
        log.error(
            tree.body.stats.head.pos(),
            openjdk.tools.javac.util.JCDiagnostic.Error(
                "compiler",
                "call.to.super.not.allowed.in.enum.ctor",
                env.enclClass.sym,
            ),
        )
      }
    }
    attr.attribStat(block, env)
    return block
  }

  private fun reflowMethodBody(
      context: Context,
      ownerClass: ClassTree,
      decl: JCMethodDecl,
  ): BlockTree {
    val flow = Flow.instance(context)
    val maker = TreeMaker.instance(context)
    val enter = Enter.instance(context)
    flow.analyzeTree(enter.getEnv((ownerClass as JCClassDecl).sym), maker)
    return decl.body
  }

  /** Copies [contents] into the char array the line map is rebuilt from. */
  private fun charactersOf(contents: CharSequence): CharArray {
    val characters = CharArray(contents.length)
    for (index in contents.indices) {
      characters[index] = contents[index]
    }
    return characters
  }

  /**
   * Parses the declaration modifiers of [modifiers], padded at [startPos] so that the produced tree
   * positions are absolute offsets into the document.
   */
  private fun parseDeclarationModifiers(
      context: Context,
      startPos: Int,
      modifiers: String,
  ): JCModifiers = newProbeParser(context, startPos, modifiers).parseModifiers()

  /** Parses a throws clause, starting at its first thrown type. */
  private fun parseThrownTypes(
      context: Context,
      startPos: Int,
      thrown: String,
  ) = newProbeParser(context, startPos, thrown).parseThrows()

  /** Parses an inserted throws clause, which must start with the throws keyword. */
  private fun parseAddedThrowsClause(
      context: Context,
      startPos: Int,
      thrown: String,
  ) = newProbeParser(context, startPos, thrown).parseAddedThrows()

  /**
   * Builds a probe parser over [text] padded at [startPos], so that the produced tree positions are
   * absolute offsets into the document.
   */
  private fun newProbeParser(context: Context, startPos: Int, text: String): DeclarationProbeParser {
    val buffer = CharArray(startPos + text.length + 1)
    Arrays.fill(buffer, ' ')
    for (index in text.indices) {
      buffer[startPos + index] = text[index]
    }
    buffer[startPos + text.length] = '\u0000'
    val factory =
        com.tom.rv2ide.language.compiler.javac.NBParserFactory.instance(context)
            as com.tom.rv2ide.language.compiler.javac.NBParserFactory
    val lexer =
        ScannerFactory.instance(context)
            .newScanner(CharBuffer.wrap(buffer, 0, buffer.size - 1), false)
    val cancelService = com.tom.rv2ide.language.compiler.javac.CancelService.instance(context)
    return DeclarationProbeParser(factory, lexer, cancelService)
  }

  /**
   * True when the edit kept the declaration annotations untouched.
   *
   * Annotations are attributed by a dedicated javac phase, so a changed annotation cannot be applied
   * here. The annotation count must match and the window must not overlap any annotation of the old
   * tree; an empty window strictly inside an annotation is an insertion into its text and is
   * rejected as well.
   */
  private fun annotationsUntouched(
      ci: CompilationInfo,
      cu: CompilationUnitTree,
      mods: JCModifiers,
      parsed: JCModifiers,
      windowStart: Int,
      windowEnd: Int,
  ): Boolean {
    if (mods.annotations.size != parsed.annotations.size) {
      return false
    }
    val positions = ci.trees.sourcePositions
    for (annotation in mods.annotations) {
      if (annotation.pos < 0) {
        return false
      }
      val end = positions.getEndPosition(cu, annotation).toInt()
      if (end <= annotation.pos) {
        return false
      }
      if (windowStart < end && windowEnd > annotation.pos) {
        return false
      }
      if (windowStart == windowEnd && windowStart > annotation.pos && windowStart < end) {
        return false
      }
    }
    return true
  }

  /**
   * Parser for the declaration sub-expressions that are replaced in place. It is built without end
   * positions, so probing never writes into the compilation unit's end-position table.
   */
  private class DeclarationProbeParser(
      factory: com.tom.rv2ide.language.compiler.javac.NBParserFactory,
      lexer: Lexer,
      cancelService: com.tom.rv2ide.language.compiler.javac.CancelService,
  ) :
      com.tom.rv2ide.language.compiler.javac.NBParserFactory.NBJavacParser(
          factory,
          lexer,
          false,
          false,
          false,
          false,
          cancelService,
      ) {
    fun parseModifiers(): JCModifiers = modifiersOpt()

    fun parseThrows() = qualidentList(false)

    fun parseAddedThrows(): List<JCExpression> {
      if (token.kind != TokenKind.THROWS) {
        return List.nil()
      }
      nextToken()
      return qualidentList(false)
    }
  }
}
