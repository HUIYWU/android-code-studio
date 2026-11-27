package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaPsiDiagnosticsCollector
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedTypeDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiFixImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiImportCandidate
import com.tom.rv2ide.language.services.java.interop.JavaPsiImportChoices
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.JavaErrorCollector
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStaticStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceList
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil

internal object JavaPsiImports {
  fun unresolvedTypes(file: PsiJavaFile, params: JavaPsiFixImportsParams): List<JavaUnresolvedTypeDiagnostic> {
    val targets = LinkedHashMap<Int, JavaUnresolvedTypeDiagnostic>()
    val collector = JavaErrorCollector(file) { error ->
      params.cancelChecker.abortIfCancelled()
      if (error.kind().key() == JavaUnresolvedTypeDiagnostic.REFERENCE_UNRESOLVED_KEY ||
          error.kind().key() == JavaUnresolvedTypeDiagnostic.TYPE_UNKNOWN_CLASS_KEY) {
        val anchorRange = requireNotNull(error.anchor().textRange)
        val relativeRange = error.range()
        val start = anchorRange.startOffset + (relativeRange?.startOffset ?: 0)
        val end = if (relativeRange == null) anchorRange.endOffset else anchorRange.startOffset + relativeRange.endOffset
        JavaPsiDiagnosticsCollector.unresolvedTypeTarget(
            error, params.file, params.documentVersion, params.documentRevision, start, end,
            includeAnnotationsAndThrows = true,
        )?.let { targets[it.referenceStart] = it }
      }
    }
    file.accept(object : PsiRecursiveElementWalkingVisitor() {
      override fun visitElement(element: PsiElement) {
        params.cancelChecker.abortIfCancelled()
        collector.processElement(element)
        super.visitElement(element)
      }
    })
    return targets.values.toList()
  }

  fun reference(
      file: PsiJavaFile,
      target: JavaUnresolvedTypeDiagnostic,
      includeAnnotationsAndThrows: Boolean = false,
  ): PsiJavaCodeReferenceElement? {
    val nameElement = file.findElementAt(target.referenceStart) ?: return null
    val reference = PsiTreeUtil.getParentOfType(
        nameElement, PsiJavaCodeReferenceElement::class.java, false,
    ) ?: return null
    if (nameElement.textRange.startOffset != target.referenceStart ||
        nameElement.textRange.endOffset != target.referenceEnd || nameElement.text != target.referenceText ||
        reference.referenceName != target.simpleName || reference.qualifier != null) return null
    val referenceList = PsiTreeUtil.getParentOfType(reference, PsiReferenceList::class.java, false)
    val typeElement = PsiTreeUtil.getParentOfType(reference, PsiTypeElement::class.java, false)
    val parent = reference.parent
    val annotationName = parent is PsiAnnotation && parent.nameReferenceElement === reference
    val typeContext = typeElement?.innermostComponentReferenceElement === reference ||
        referenceList != null || parent is PsiNewExpression && parent.classOrAnonymousClassReference === reference ||
        includeAnnotationsAndThrows && annotationName
    if (!typeContext || !includeAnnotationsAndThrows &&
        (annotationName || referenceList?.role == PsiReferenceList.Role.THROWS_LIST)) return null
    return reference
  }

  fun candidates(
      file: PsiJavaFile,
      simpleName: String,
      references: List<PsiJavaCodeReferenceElement>,
      qualifiedNames: Collection<String>,
      cancelChecker: ICancelChecker,
  ): List<String> {
    val imports = file.importList?.allImportStatements.orEmpty()
    val importedNames = file.importList?.importStatements.orEmpty().mapNotNull { it.qualifiedName }.toSet()
    val importedPackages = imports.filter { it.isOnDemand && it !is PsiImportStaticStatement }
        .mapNotNull { it.importReference?.qualifiedName }.toSet()
    val facade = JavaPsiFacade.getInstance(file.project)
    val resolveHelper = facade.resolveHelper
    return qualifiedNames.asSequence().sorted().filter { qualifiedName ->
      cancelChecker.abortIfCancelled()
      val targetClass = facade.findClass(qualifiedName, file.resolveScope) ?: return@filter false
      if (targetClass.containingClass != null) return@filter false
      if (qualifiedName in importedNames ||
          importedNames.any { it.substringAfterLast('.') == simpleName && it != qualifiedName }) return@filter false
      val candidatePackage = qualifiedName.substringBeforeLast('.', "")
      if (candidatePackage == file.packageName || candidatePackage == "java.lang" ||
          candidatePackage in importedPackages) return@filter false
      references.all { reference ->
        cancelChecker.abortIfCancelled()
        val parent = reference.parent
        if (parent is PsiAnnotation && parent.nameReferenceElement === reference &&
            !targetClass.isAnnotationType) return@all false
        val visible = resolveHelper.resolveReferencedClass(simpleName, reference)
        PsiUtil.isAccessible(targetClass, reference, null) &&
            (visible == null || visible.isEquivalentTo(targetClass))
      }
    }.distinct().toList()
  }

  fun choices(
      file: PsiJavaFile,
      targets: List<JavaUnresolvedTypeDiagnostic>,
      qualifiedNames: Map<String, List<String>>,
      cancelChecker: ICancelChecker,
  ): List<JavaPsiImportChoices> = targets.groupBy { it.simpleName }.mapNotNull { (name, uses) ->
    cancelChecker.abortIfCancelled()
    val references = uses.map { reference(file, it, includeAnnotationsAndThrows = true) ?: return@mapNotNull null }
    val candidates = candidates(file, name, references, qualifiedNames[name].orEmpty(), cancelChecker)
    if (candidates.isEmpty()) null else JavaPsiImportChoices(name, candidates)
  }

  fun candidatesWithEdits(
      file: PsiJavaFile,
      target: JavaUnresolvedTypeDiagnostic,
      qualifiedNames: List<String>,
  ): List<JavaPsiImportCandidate> {
    if (qualifiedNames.isEmpty()) return emptyList()
    val layout = ImportLayout(file, target.file, target.documentVersion, target.documentRevision)
    return qualifiedNames.map { qualifiedName ->
      JavaPsiImportCandidate(qualifiedName, layout.edits(listOf(qualifiedName)).single())
    }
  }

  fun generate(file: PsiJavaFile, params: JavaPsiGenerateImportsParams): List<JavaPsiTextEdit>? {
    if (params.selectedImports.isEmpty()) return emptyList()
    val targets = unresolvedTypes(
        file, JavaPsiFixImportsParams(
            params.file, params.documentVersion, params.documentRevision, null, params.cancelChecker,
        ),
    ).groupBy { it.simpleName }
    for ((simpleName, qualifiedName) in params.selectedImports) {
      params.cancelChecker.abortIfCancelled()
      if (qualifiedName.substringAfterLast('.') != simpleName) return null
      val uses = targets[simpleName] ?: return null
      val references = uses.map {
        reference(file, it, includeAnnotationsAndThrows = true) ?: return null
      }
      if (candidates(file, simpleName, references, listOf(qualifiedName), params.cancelChecker).isEmpty()) return null
    }
    return ImportLayout(file, params.file, params.documentVersion, params.documentRevision)
        .edits(params.selectedImports.values)
  }

  private class ImportLayout(
      private val file: PsiJavaFile,
      path: Path,
      private val version: Int,
      private val revision: Long,
  ) {
    private val path = path.normalize()
    private val content = file.text
    private val index = LineIndex.from(content)
    private val statements = file.importList?.importStatements.orEmpty()
    private val newlineOffset = content.indexOfFirst { it == '\r' || it == '\n' }
    private val newline = when {
      newlineOffset < 0 -> "\n"
      content[newlineOffset] == '\r' && content.getOrNull(newlineOffset + 1) == '\n' -> "\r\n"
      else -> content[newlineOffset].toString()
    }

    fun edits(qualifiedNames: Collection<String>): List<JavaPsiTextEdit> {
      val groups = qualifiedNames.distinct().sorted().groupBy { qualifiedName ->
        statements.firstOrNull { it.qualifiedName != null && qualifiedName < it.qualifiedName!! }
      }
      return groups.map { (before, names) ->
        val declarations = names.joinToString(newline) { "import $it;" }
        val (offset, text) = when {
          before != null -> before.textRange.startOffset to "$declarations$newline"
          statements.isNotEmpty() -> after(statements.last(), declarations, false)
          file.importList?.allImportStatements?.isNotEmpty() == true ->
            file.importList!!.allImportStatements.first().textRange.startOffset to "$declarations$newline"
          file.packageStatement != null -> after(file.packageStatement!!, declarations, true)
          else -> {
            val start = file.classes.firstOrNull()?.textRange?.startOffset ?: 0
            start to "$declarations$newline$newline"
          }
        }
        val position = index.indexToPosition(offset)
        JavaPsiTextEdit(path, version, revision, position.line, position.column, position.line, position.column, text)
      }.sortedWith(compareByDescending<JavaPsiTextEdit> { it.startLine }.thenByDescending { it.startColumn })
    }

    private fun after(statement: PsiElement, declarations: String, blankLine: Boolean): Pair<Int, String> {
      var offset = statement.textRange.endOffset
      var cursor = offset
      while (cursor < content.length) {
        while (cursor < content.length && (content[cursor] == ' ' || content[cursor] == '\t')) cursor++
        if (cursor == content.length) break
        val comment = file.findElementAt(cursor) as? PsiComment ?: break
        offset = comment.textRange.endOffset
        cursor = offset
      }
      if (cursor < content.length && (content[cursor] == '\r' || content[cursor] == '\n')) {
        val end = cursor + if (content[cursor] == '\r' && content.getOrNull(cursor + 1) == '\n') 2 else 1
        val prefix = if (blankLine) newline else ""
        return end to "$prefix$declarations$newline"
      }
      val prefix = if (blankLine) "$newline$newline" else newline
      return offset to "$prefix$declarations$newline"
    }
  }
}
