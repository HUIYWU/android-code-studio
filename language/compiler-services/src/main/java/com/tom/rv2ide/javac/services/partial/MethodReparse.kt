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

package com.tom.rv2ide.javac.services.partial

import com.tom.rv2ide.javac.services.compiler.JavacFlowListener
import com.tom.rv2ide.javac.services.util.ReparserUtils
import com.tom.rv2ide.javac.services.visitors.FindAnonymousVisitor
import com.tom.rv2ide.javac.services.visitors.TranslateMethodPositionsVisitor
import java.nio.CharBuffer
import java.util.Arrays
import openjdk.source.tree.BlockTree
import openjdk.source.tree.ClassTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.Tree.Kind.BLOCK
import openjdk.source.tree.Tree.Kind.METHOD
import openjdk.source.util.TreePath
import openjdk.tools.javac.api.JavacScope
import openjdk.tools.javac.api.JavacTrees
import openjdk.tools.javac.code.Flags
import openjdk.tools.javac.code.Symbol
import openjdk.tools.javac.code.Symtab
import openjdk.tools.javac.comp.Attr
import openjdk.tools.javac.comp.AttrContext
import openjdk.tools.javac.comp.Enter
import openjdk.tools.javac.comp.Env
import openjdk.tools.javac.comp.Flow
import openjdk.tools.javac.parser.JavacParser
import openjdk.tools.javac.parser.LazyDocCommentTable.Entry
import openjdk.tools.javac.parser.ScannerFactory
import openjdk.tools.javac.tree.DocCommentTable
import openjdk.tools.javac.tree.EndPosTable
import openjdk.tools.javac.tree.JCTree
import openjdk.tools.javac.tree.JCTree.JCBlock
import openjdk.tools.javac.tree.JCTree.JCClassDecl
import openjdk.tools.javac.tree.JCTree.JCCompilationUnit
import openjdk.tools.javac.tree.JCTree.JCMethodDecl
import openjdk.tools.javac.tree.TreeInfo
import openjdk.tools.javac.tree.TreeMaker
import openjdk.tools.javac.util.Context
import openjdk.tools.javac.util.List
import openjdk.tools.javac.util.Log
import openjdk.tools.javac.util.Names
import org.slf4j.LoggerFactory

/**
 * Method-level partial reparse implementation.
 *
 * <p>This directly mutates a supplied javac task and is intended to update one method body in place.
 *
 * @author Akash Yadav
 */
class MethodReparse : PartialReparse {

  /** True when the current attempt changed the cached javac AST or its semantic state. */
  var taskMutated: Boolean = false
    private set

  private val allowPartialReparse: Boolean = ReparserUtils.canReparse()

  companion object {
    private val log = LoggerFactory.getLogger(MethodReparse::class.java)
  }
  override fun reparseMethod(

      ci: CompilationInfo,
      methodPath: TreePath,
      newBody: String,
      fileContents: CharSequence,
  ): Boolean {
    taskMutated = false
    var partialReparseCommitted = false

    if (!allowPartialReparse) {
      log.debug("Partial reparse is disabled")
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

    val methodScope = ci.trees.getScope(TreePath(methodPath, method.body))
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

    val fav = FindAnonymousVisitor()
    fav.scan(method.body, null)
    if (fav.hasLocalClass) {
      log.debug("Skipped reparse method. Old local classes: {}", fo)
      return false
    }

    val noInner = fav.noInner
    val context = task.context

    try {
      val l = com.tom.rv2ide.javac.services.NBLog.instance(context)
      l.startPartialReparse(fo)
      val prevLogged = l.useSource(fo)
      val block: JCBlock?

      try {
        val dl = ci.diagnosticListener as DiagnosticListenerImpl
        try {
          dl.startPartialReparse(origStartPos, origEndPos)
        } catch (err: Throwable) {
          log.error(
              "Partial reparse stage failed: stage=diagnostic-begin method={} source={} sourceUri={} listenerSource={} listenerSourceUri={} origStartPos={} origEndPos={} newBodyLength={} errorType={} errorMessage={}",
              method.name,
              fo,
              fo.toUri(),
              dl.jfoForDebug(),
              dl.sourceUriForDebug(),
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
        log.debug("Reparse method...")
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
          log.debug(
              "Skipped legacy AST mutation because the parsed body position is invalid; newBodyLength={}",
              newBody.length,
          )
          return false
        }

        val sourcePositions = jt.sourcePositions
        val newEndPos = sourcePositions.getEndPosition(cu, block).toInt()
        if (newEndPos != origStartPos + newBody.length) {
          log.warn(
              "Invalid position in reparsed method method={} origStartPos={} origEndPos={} newEndPos={} newBodyLength={} expectedEndPos={}",
              method.name,
              origStartPos,
              origEndPos,
              newEndPos,
              newBody.length,
              origStartPos + newBody.length,
          )
          return false
        }

        fav.reset()
        fav.scan(block, null)

        val newNoInner = fav.noInner
        if (fav.hasLocalClass || noInner != newNoInner) {
          log.debug("Skipped method reparse (new local class): {}", fo)
          return false
        }
        // End positions/doc-comments/unenter have already started mutating the cached task.
        taskMutated = true
        val cuDocComments = cu.docComments
        if (cuDocComments == null) {
          log.debug(
              "Skipping doc-comments update because compilation unit has no docComments table method={} origStartPos={} origEndPos={} newBodyLength={} addedDocComments={} removedOwners={}",
              method.name,
              origStartPos,
              origEndPos,
              newBody.length,
              docComments.size,
              fav.docOwners.size,
          )
        } else {
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
                fav.docOwners.size,
                err.javaClass.name,
                err.message,
                err,
            )
            return false
          }
          try {
            docCommentsTable.keys.removeAll(fav.docOwners)
          } catch (err: Throwable) {
            log.error(
                "Partial reparse stage failed: stage=doc-comments-remove method={} origStartPos={} origEndPos={} newBodyLength={} tableSize={} addedDocComments={} removedOwners={} errorType={} errorMessage={}",
                method.name,
                origStartPos,
                origEndPos,
                newBody.length,
                  docCommentsTable.size,
                docComments.size,
                fav.docOwners.size,
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
                fav.docOwners.size,
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

        log.debug("ReAttr method...")
        try {
          reAttrMethodBody(context, methodScope, method, block)
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
            log.debug("Reflow method...")
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
            log.debug("Unable to rollback partial diagnostics", ignored)
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

    log.warn("JavacParser parsed invalid statment. Block statement was expected...")
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
        com.tom.rv2ide.javac.services.NBParserFactory.instance(context)
            as com.tom.rv2ide.javac.services.NBParserFactory
    val scannerFactory = ScannerFactory.instance(context)
    val cancelService = com.tom.rv2ide.javac.services.CancelService.instance(context)
    val lexer = scannerFactory.newScanner(buf, true)
    if (
        endPositions is com.tom.rv2ide.javac.services.NBParserFactory.NBJavacParser.EndPosTableImpl
    ) {
      endPositions.resetErrorEndPos()
    }

    return object :
        com.tom.rv2ide.javac.services.NBParserFactory.NBJavacParser(
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
}
