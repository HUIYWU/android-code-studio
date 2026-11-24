package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaUnhandledExceptionDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil

internal object JavaPsiUnhandledExceptionGeneration {
  fun generate(
      file: PsiJavaFile,
      diagnostic: JavaUnhandledExceptionDiagnostic,
      cancelChecker: ICancelChecker,
  ): List<JavaPsiTextEdit>? {
    val method = methodAt(file, diagnostic) ?: return null
    val content = file.text
    var parent = method.parent
    while (parent != null && parent !is PsiJavaFile) {
      if (parent is PsiLambdaExpression || parent is PsiMethodReferenceExpression) return null
      if (parent is PsiMethod && parent !== method) return null
      parent = parent.parent
    }
    val index = LineIndex.from(content)
    val factory = JavaPsiFacade.getElementFactory(file.project)
    val existingTypes = method.throwsList.referencedTypes
    val existingReferences = method.throwsList.referenceElements
    val pendingImports = LinkedHashMap<String, String>()
    val renderedTypes = LinkedHashSet<String>()

    for (qualifiedName in diagnostic.exceptionQualifiedNames) {
      cancelChecker.abortIfCancelled()
      val exceptionClass = JavaPsiFacade.getInstance(file.project).findClass(qualifiedName, file.resolveScope)
          ?: return null
      val exceptionType = factory.createType(exceptionClass)
      val exceptionPsiType = exceptionType as? PsiClassType ?: return null
      if (existingTypes.any { it.isAssignableFrom(exceptionPsiType) }) continue
      val rendered = renderType(file, method, exceptionClass, qualifiedName, pendingImports)
      renderedTypes += rendered
    }
    if (renderedTypes.isEmpty()) return emptyList()

    val insertionOffset: Int
    val insertionText: String
    if (existingReferences.isNotEmpty()) {
      insertionOffset = existingReferences.last().textRange.endOffset
      insertionText = renderedTypes.joinToString(", ", prefix = ", ")
    } else {
      val body = method.body
      if (body != null) {
        insertionOffset = body.textRange.startOffset
        val separator = if (content.getOrNull(insertionOffset - 1)?.isWhitespace() == true) "" else " "
        insertionText = "${separator}throws ${renderedTypes.joinToString(", ")} "
      } else {
        insertionOffset = method.textRange.endOffset - 1
        if (insertionOffset < 0 || content.getOrNull(insertionOffset) != ';') return null
        val separator = if (content.getOrNull(insertionOffset - 1)?.isWhitespace() == true) "" else " "
        insertionText = "${separator}throws ${renderedTypes.joinToString(", ")}"
      }
    }

    fun edit(offset: Int, text: String): JavaPsiTextEdit {
      val position = index.indexToPosition(offset)
      return JavaPsiTextEdit(
          diagnostic.file.normalize(), diagnostic.documentVersion, diagnostic.documentRevision,
          position.line, position.column, position.line, position.column, text,
      )
    }

    val edits = ArrayList<JavaPsiTextEdit>(2)
    edits += edit(insertionOffset, insertionText)
    importText(file, pendingImports.values.toList())?.let { text ->
      val importOffset = importOffset(file)
      edits += edit(importOffset, text)
    }
    return edits
  }

  private fun methodAt(
      file: PsiJavaFile,
      diagnostic: JavaUnhandledExceptionDiagnostic,
  ): PsiMethod? {
    val start = diagnostic.methodStart
    if (start !in 0 until file.textLength || diagnostic.methodEnd <= start || diagnostic.methodEnd > file.textLength ||
        diagnostic.ownerStart !in 0 until file.textLength || diagnostic.ownerEnd <= diagnostic.ownerStart ||
        diagnostic.ownerEnd > file.textLength || diagnostic.highlightStart < diagnostic.methodStart ||
        diagnostic.highlightEnd < diagnostic.highlightStart || diagnostic.highlightEnd > diagnostic.methodEnd ||
        diagnostic.highlightEnd > file.textLength) return null
    val ownerLeaf = file.findElementAt(diagnostic.ownerStart) ?: return null
    val owner = PsiTreeUtil.getParentOfType(ownerLeaf, PsiClass::class.java, false) ?: return null
    if (owner.textRange.startOffset != diagnostic.ownerStart || owner.textRange.endOffset != diagnostic.ownerEnd ||
        owner.qualifiedName != diagnostic.ownerQualifiedName) return null
    val leaf = file.findElementAt(start) ?: return null
    val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java, false) ?: return null
    val methodOwner = method.containingClass ?: return null
    if (!method.isValid || method.textRange.startOffset != start || method.textRange.endOffset != diagnostic.methodEnd ||
        methodOwner != owner || method.name != diagnostic.methodName || method.isConstructor != diagnostic.constructor) return null
    val parameterTypes = method.parameterList.parameters.map {
      TypeConversionUtil.erasure(it.type).canonicalText
    }
    if (parameterTypes != diagnostic.erasedParameterTypes) return null
    return method
  }

  private fun renderType(
      file: PsiJavaFile,
      method: PsiMethod,
      exceptionClass: PsiClass,
      qualifiedName: String,
      pendingImports: MutableMap<String, String>,
  ): String {
    val simpleName = exceptionClass.name ?: return qualifiedName
    val exceptionFile = exceptionClass.containingFile as? PsiJavaFile
    val packageName = exceptionFile?.packageName
    val explicitImport = file.importList?.importStatements.orEmpty()
        .firstOrNull { !it.isOnDemand && it.qualifiedName?.substringAfterLast('.') == simpleName }
        ?.qualifiedName
    if (explicitImport != null) return if (explicitImport == qualifiedName) simpleName else qualifiedName

    val pending = pendingImports[simpleName]
    if (pending != null) return if (pending == qualifiedName) simpleName else qualifiedName

    val visible = JavaPsiFacade.getInstance(file.project).resolveHelper.resolveReferencedClass(simpleName, method)
    if (visible != null) return if (visible.isEquivalentTo(exceptionClass)) simpleName else qualifiedName
    if (exceptionClass.containingClass == null &&
        (qualifiedName.substringBeforeLast('.') == "java.lang" || packageName == file.packageName)) return simpleName

    pendingImports[simpleName] = qualifiedName
    return simpleName
  }

  private fun importOffset(file: PsiJavaFile): Int {
    val anchor = file.importList?.allImportStatements?.lastOrNull() ?: file.packageStatement
    return anchor?.textRange?.endOffset ?: 0
  }

  private fun importText(file: PsiJavaFile, imports: List<String>): String? {
    if (imports.isEmpty()) return null
    val offset = importOffset(file)
    val content = file.text
    val newlineOffset = content.indexOfFirst { it == '\r' || it == '\n' }
    val newline = when {
      newlineOffset < 0 -> "\n"
      content[newlineOffset] == '\r' && content.getOrNull(newlineOffset + 1) == '\n' -> "\r\n"
      else -> content[newlineOffset].toString()
    }
    return imports.sorted().joinToString(
        newline,
        if (offset == 0) "" else newline,
        if (offset == 0) "$newline$newline" else newline,
    ) { "import $it;" }
  }
}