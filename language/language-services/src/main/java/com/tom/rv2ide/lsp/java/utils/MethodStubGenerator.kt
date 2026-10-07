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
package com.tom.rv2ide.lsp.java.utils

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CharLiteralExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LongLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.tom.rv2ide.lsp.java.utils.JavaParserUtils.prettyPrint
import com.tom.rv2ide.lsp.java.utils.TypeUtils.toType
import com.tom.rv2ide.projects.ModuleProject
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.type.ExecutableType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.type.TypeMirror
import openjdk.source.tree.MethodTree

object MethodStubGenerator {

  enum class BodyStrategy {
    IMPLEMENT_ABSTRACT,
    OVERRIDE_SUPER
  }

  data class GeneratedMethod(
      val declaration: MethodDeclaration,
      val imports: MutableSet<String>,
      val renderedText: String,
  )

  @JvmStatic
  @JvmOverloads
  fun generate(
      method: ExecutableElement,
      parameterizedType: ExecutableType,
      source: MethodTree?,
      bodyStrategy: BodyStrategy,
      module: ModuleProject? = null,
  ): GeneratedMethod {
    val declaration =
        if (source != null) {
          JavaParserUtils.toMethodDeclaration(source, parameterizedType)
        } else {
          JavaParserUtils.toMethodDeclaration(method, parameterizedType)
        }

    if (source == null) {
      ExternalMethodParameterNameResolver.resolve(module, method)
          ?.takeIf { it.size == declaration.parameters.size }
          ?.forEachIndexed { index, name -> declaration.parameters[index].setName(name) }
    }

    normalizeOverrideAnnotation(declaration)
    normalizeVisibility(declaration, method)
    val imports = JavaParserUtils.collectImports(parameterizedType)
    fillBody(declaration, method, bodyStrategy)
    val renderedText = prettyPrint(declaration) { false } ?: declaration.toString()
    return GeneratedMethod(declaration, imports, renderedText)
  }

  private fun normalizeOverrideAnnotation(declaration: MethodDeclaration) {
    val annotations = declaration.annotations.filter { it.nameAsString == "Override" }
    if (annotations.isEmpty()) {
      declaration.addMarkerAnnotation(Override::class.java)
      return
    }
    annotations.drop(1).forEach { it.remove() }
  }

  private fun normalizeVisibility(declaration: MethodDeclaration, method: ExecutableElement) {
    declaration.isDefault = false
    val enclosing = method.enclosingElement as? TypeElement ?: return
    if (enclosing.kind == ElementKind.INTERFACE) {
      declaration.removeModifier(Modifier.Keyword.PROTECTED)
      declaration.removeModifier(Modifier.Keyword.PRIVATE)
      declaration.removeModifier(Modifier.Keyword.DEFAULT)
      if (!declaration.hasModifier(Modifier.Keyword.PUBLIC)) {
        declaration.addModifier(Modifier.Keyword.PUBLIC)
      }
    }
  }

  private fun fillBody(
      declaration: MethodDeclaration,
      method: ExecutableElement,
      bodyStrategy: BodyStrategy,
  ) {
    declaration.removeModifier(Modifier.Keyword.ABSTRACT)
    val body = BlockStmt()
    when (bodyStrategy) {
      BodyStrategy.IMPLEMENT_ABSTRACT -> appendDefaultBody(body, declaration.type, method.returnType)
      BodyStrategy.OVERRIDE_SUPER -> {
        val enclosing = method.enclosingElement as? TypeElement
        if (enclosing?.kind == ElementKind.INTERFACE) {
          appendDefaultBody(body, declaration.type, method.returnType)
        } else {
          appendSuperBody(body, declaration)
        }
      }
    }
    declaration.setBody(body)
  }

  private fun appendDefaultBody(body: BlockStmt, returnTypeNode: com.github.javaparser.ast.type.Type, returnTypeMirror: TypeMirror) {
    when {
      returnTypeNode.isVoidType -> body.addOrphanComment(com.github.javaparser.ast.comments.LineComment(" TODO: Implement this method"))
      returnTypeMirror.kind == TypeKind.BOOLEAN -> body.addStatement(ReturnStmt(BooleanLiteralExpr(false)))
      returnTypeMirror.kind == TypeKind.CHAR -> body.addStatement(ReturnStmt(CharLiteralExpr("\\0")))
      returnTypeMirror.kind == TypeKind.LONG -> body.addStatement(ReturnStmt(LongLiteralExpr("0L")))
      returnTypeMirror.kind == TypeKind.FLOAT -> body.addStatement(ReturnStmt(com.github.javaparser.ast.expr.NameExpr("0f")))
      returnTypeMirror.kind == TypeKind.DOUBLE -> body.addStatement(ReturnStmt(DoubleLiteralExpr("0d")))
      returnTypeMirror.kind.isPrimitive -> body.addStatement(ReturnStmt(IntegerLiteralExpr("0")))
      else -> body.addStatement(ReturnStmt(NullLiteralExpr()))
    }
  }

  private fun appendSuperBody(body: BlockStmt, declaration: MethodDeclaration) {
    val call = MethodCallExpr()
    call.setScope(SuperExpr())
    call.name = declaration.name
    call.arguments = NodeList.nodeList(*declaration.parameters.map { it.nameAsExpression }.toTypedArray())
    if (declaration.type.isVoidType) {
      body.addStatement(call)
    } else {
      body.addStatement(ReturnStmt(call))
    }
  }
}
