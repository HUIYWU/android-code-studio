package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedMethodDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.java.interop.JavaPsiUnresolvedMethodParams
import com.tom.rv2ide.lsp.models.LineIndex
import org.jetbrains.kotlin.com.intellij.psi.PsiAssignmentExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassInitializer
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiReturnStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil

internal object JavaPsiMissingMethodGeneration {
  fun generate(
      file: PsiJavaFile,
      diagnostic: JavaUnresolvedMethodDiagnostic,
      params: JavaPsiUnresolvedMethodParams,
  ): JavaPsiTextEdit? {
    val content = file.text
    val call = findCall(file, diagnostic) ?: return null
    if (PsiTreeUtil.getParentOfType(call, PsiLambdaExpression::class.java, false) != null) return null
    val owner = findOwner(file, diagnostic) ?: return null
    if (owner.isInterface || owner.isAnnotationType || owner.isEnum) return null
    val methodExpression = call.methodExpression
    if (methodExpression.qualifierExpression != null || methodExpression.referenceName != diagnostic.methodName ||
        methodExpression.resolve() != null) return null
    val returnType = returnType(call) ?: return null
    val arguments = call.argumentList.expressions.map { expression ->
      params.cancelChecker.abortIfCancelled()
      val type = expression.type ?: return null
      if (!PsiTypesUtil.isDenotableType(type, call)) return null
      val typeText = type.presentableText
      if (typeText.isEmpty()) return null
      typeText
    }
    if (arguments.any { it.isEmpty() }) return null
    val brace = owner.rBrace ?: return null
    val braceOffset = brace.textRange.startOffset
    val braceLineStart = lineStart(content, braceOffset)
    val bracePrefix = content.substring(braceLineStart, braceOffset)
    val braceOnOwnLine = bracePrefix.all { it == ' ' || it == '\t' }
    val offset = if (braceOnOwnLine) braceLineStart else braceOffset
    val ownerIndent = content.substring(lineStart(content, owner.textRange.startOffset), owner.textRange.startOffset)
        .takeWhile { it == ' ' || it == '\t' }
    val unit = if (params.useSoftTabs) " ".repeat(params.indentationTabSize) else "\t"
    val memberIndent = ownerIndent + unit
    val newline = newline(content)
    val static = isStaticContext(call, owner)
    val modifier = if (static) "private static" else "private"
    val header = "$modifier $returnType ${diagnostic.methodName}(${arguments.mapIndexed { index, type ->
      "$type param$index"
    }.joinToString(", ")})"
    val text = buildString {
      append(newline)
      append(memberIndent).append(header).append(" {").append(newline)
      append(memberIndent).append(unit).append("// TODO: Implement this method").append(newline)
      if (returnType != "void") {
        append(memberIndent).append(unit).append(returnStatement(returnType)).append(newline)
      }
      append(memberIndent).append('}').append(newline)
      if (!braceOnOwnLine) append(ownerIndent)
    }
    val position = LineIndex.from(content).indexToPosition(offset)
    return JavaPsiTextEdit(
        diagnostic.file.normalize(), diagnostic.documentVersion, diagnostic.documentRevision,
        position.line, position.column, position.line, position.column, text,
    )
  }

  private fun findCall(file: PsiJavaFile, diagnostic: JavaUnresolvedMethodDiagnostic) =
      file.findElementAt(diagnostic.callStart)?.let {
        PsiTreeUtil.getParentOfType(it, org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression::class.java, false)
      }?.takeIf {
        it.textRange.startOffset == diagnostic.callStart && it.textRange.endOffset == diagnostic.callEnd
      }

  private fun findOwner(file: PsiJavaFile, diagnostic: JavaUnresolvedMethodDiagnostic): PsiClass? {
    if (diagnostic.ownerStart < 0 || diagnostic.ownerEnd > file.textLength || diagnostic.ownerEnd <= diagnostic.ownerStart) return null
    val element = file.findElementAt(diagnostic.ownerStart) ?: return null
    return PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)?.takeIf {
      it.textRange.startOffset == diagnostic.ownerStart && it.textRange.endOffset == diagnostic.ownerEnd
    }
  }

  private fun returnType(call: org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression): String? {
    val parent = call.parent
    val type = when (parent) {
      is PsiVariable -> parent.type
      is PsiAssignmentExpression -> parent.lExpression.type
      is PsiReturnStatement -> PsiTreeUtil.getParentOfType(call, PsiMethod::class.java, false)?.returnType
      is PsiExpressionStatement -> null
      else -> return null
    }
    return type?.presentableText ?: if (parent is PsiExpressionStatement) "void" else null
  }

  private fun isStaticContext(
      call: org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression,
      owner: PsiClass,
  ): Boolean {
    val method = PsiTreeUtil.getParentOfType(call, PsiMethod::class.java, false)
    if (method != null) return method.hasModifierProperty(PsiModifier.STATIC)
    val field = PsiTreeUtil.getParentOfType(call, PsiField::class.java, false)
    if (field != null) return field.hasModifierProperty(PsiModifier.STATIC)
    val initializer = PsiTreeUtil.getParentOfType(call, PsiClassInitializer::class.java, false)
    return initializer?.hasModifierProperty(PsiModifier.STATIC) == true || owner.isInterface
  }

  private fun returnStatement(type: String): String = when (type) {
    "boolean" -> "return false;"
    "byte", "short", "int" -> "return 0;"
    "long" -> "return 0L;"
    "float" -> "return 0f;"
    "double" -> "return 0d;"
    "char" -> "return '\\0';"
    else -> "return null;"
  }

  private fun newline(content: String): String {
    val offset = content.indexOfFirst { it == '\r' || it == '\n' }
    return when {
      offset < 0 -> "\n"
      content[offset] == '\r' && content.getOrNull(offset + 1) == '\n' -> "\r\n"
      else -> content[offset].toString()
    }
  }

  private fun lineStart(content: String, offset: Int): Int =
      maxOf(content.lastIndexOf('\n', offset - 1), content.lastIndexOf('\r', offset - 1)) + 1
}