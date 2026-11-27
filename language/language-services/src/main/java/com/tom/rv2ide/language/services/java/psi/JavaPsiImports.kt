package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedTypeDiagnostic
import com.tom.rv2ide.language.services.java.diagnostics.JavaUnresolvedStaticMemberDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiFixImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiFixImportsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiImportCandidate
import com.tom.rv2ide.language.services.java.interop.JavaPsiStaticImportChoices
import com.tom.rv2ide.language.services.java.interop.JavaPsiImportChoices
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.JavaErrorCollector
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiMember
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStaticStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceList
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil

internal object JavaPsiImports {
  class Targets(
      val types: List<JavaUnresolvedTypeDiagnostic>,
      val members: List<JavaUnresolvedStaticMemberDiagnostic>,
  )

  fun unresolved(file: PsiJavaFile, params: JavaPsiFixImportsParams): Targets {
    val types = LinkedHashMap<Int, JavaUnresolvedTypeDiagnostic>()
    val members = LinkedHashMap<Int, JavaUnresolvedStaticMemberDiagnostic>()
    val collector = JavaErrorCollector(file) { error ->
      params.cancelChecker.abortIfCancelled()
      val key = error.kind().key()
      val psi = error.psi()
      if (key == JavaUnresolvedTypeDiagnostic.REFERENCE_UNRESOLVED_KEY ||
          key == JavaUnresolvedTypeDiagnostic.TYPE_UNKNOWN_CLASS_KEY) {
        val reported = when (psi) {
          is PsiTypeElement -> psi.innermostComponentReferenceElement
          is PsiJavaCodeReferenceElement -> psi
          else -> null
        }
        if (reported != null) {
          collectType(reported, params)?.let { types[it.referenceStart] = it }
        }
        if (psi is PsiReferenceExpression && psi.qualifierExpression == null &&
            (psi.parent as? PsiMethodCallExpression)?.methodExpression !== psi &&
            !PsiUtil.isAccessedForWriting(psi) && !excluded(psi)) {
          collectStaticMember(psi, false, params)?.let { members[it.referenceStart] = it }
        }
      }
      if ((key == "call.unresolved.name" || key == "call.ambiguous.no.match") &&
          psi is PsiMethodCallExpression && psi.methodExpression.qualifierExpression == null && !excluded(psi)) {
        collectStaticMember(psi.methodExpression, true, params)?.let { members[it.referenceStart] = it }
      }
    }
    file.accept(object : PsiRecursiveElementWalkingVisitor() {
      override fun visitElement(element: PsiElement) {
        params.cancelChecker.abortIfCancelled()
        collector.processElement(element)
        super.visitElement(element)
      }
    })
    return Targets(types.values.toList(), members.values.toList())
  }

  private fun excluded(element: PsiElement): Boolean =
      PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java, false) != null ||
          PsiUtil.isInsideJavadocComment(element)

  private fun terminal(reference: PsiJavaCodeReferenceElement): PsiJavaCodeReferenceElement {
    var current = reference
    while (true) {
      val parent = current.parent as? PsiJavaCodeReferenceElement ?: return current
      if (parent is PsiReferenceExpression || parent.qualifier !== current) return current
      current = parent
    }
  }

  private fun rootReference(reference: PsiJavaCodeReferenceElement): PsiJavaCodeReferenceElement {
    var current = reference
    while (current.qualifier is PsiJavaCodeReferenceElement) {
      current = current.qualifier as PsiJavaCodeReferenceElement
    }
    return current
  }

  private fun suffix(reference: PsiJavaCodeReferenceElement): List<String> {
    val names = ArrayList<String>()
    var current = reference
    val end = terminal(reference)
    while (current !== end) {
      current = current.parent as PsiJavaCodeReferenceElement
      names += requireNotNull(current.referenceName)
    }
    return names
  }

  private fun typeContext(reference: PsiJavaCodeReferenceElement, includeAnnotationsAndThrows: Boolean): Boolean {
    if (excluded(reference)) return false
    val end = terminal(reference)
    val parent = end.parent
    val annotation = parent is PsiAnnotation && parent.nameReferenceElement === end
    val referenceList = parent as? PsiReferenceList
    if (!includeAnnotationsAndThrows &&
        (annotation || referenceList?.role == PsiReferenceList.Role.THROWS_LIST)) return false
    val typeElement = parent as? PsiTypeElement
    if (typeElement?.innermostComponentReferenceElement === end || referenceList != null ||
        isNewClassReference(end) || includeAnnotationsAndThrows && annotation) return true
    return includeAnnotationsAndThrows && reference is PsiReferenceExpression &&
        (parent as? PsiReferenceExpression)?.qualifierExpression === reference
  }

  private fun isNewClassReference(reference: PsiJavaCodeReferenceElement): Boolean =
      PsiTreeUtil.getParentOfType(reference, PsiNewExpression::class.java, false)
          ?.classOrAnonymousClassReference === reference

  private fun collectType(
      reported: PsiJavaCodeReferenceElement,
      params: JavaPsiFixImportsParams,
  ): JavaUnresolvedTypeDiagnostic? {
    val root = rootReference(reported)
    if (root.resolve() != null || !typeContext(root, true)) return null
    val name = root.referenceName ?: return null
    val token = root.referenceNameElement ?: return null
    val range = token.textRange
    return JavaUnresolvedTypeDiagnostic(
        params.file.normalize(), params.documentVersion, params.documentRevision,
        range.startOffset, range.endOffset, range.startOffset, range.endOffset,
        token.text, name, suffix(root),
    )
  }

  private fun collectStaticMember(
      reference: PsiReferenceExpression,
      method: Boolean,
      params: JavaPsiFixImportsParams,
  ): JavaUnresolvedStaticMemberDiagnostic? {
    if (excluded(reference) || reference.parent is PsiAnnotation ||
        PsiTreeUtil.getParentOfType(reference, PsiTypeElement::class.java) != null ||
        PsiTreeUtil.getParentOfType(reference, PsiReferenceList::class.java) != null) return null
    val name = reference.referenceName ?: return null
    val token = reference.referenceNameElement ?: return null
    val range = token.textRange
    return JavaUnresolvedStaticMemberDiagnostic(
        params.file.normalize(), params.documentVersion, params.documentRevision,
        range.startOffset, range.endOffset, name, method,
    )
  }

  fun reference(
      file: PsiJavaFile,
      target: JavaUnresolvedTypeDiagnostic,
      includeAnnotationsAndThrows: Boolean = false,
  ): PsiJavaCodeReferenceElement? {
    val leaf = file.findElementAt(target.referenceStart) ?: return null
    val reference = PsiTreeUtil.getParentOfType(leaf, PsiJavaCodeReferenceElement::class.java, false) ?: return null
    val token = reference.referenceNameElement ?: return null
    if (reference.qualifier != null || reference.resolve() != null ||
        token.textRange.startOffset != target.referenceStart || token.textRange.endOffset != target.referenceEnd ||
        token.text != target.referenceText || reference.referenceName != target.simpleName ||
        suffix(reference) != target.qualifierSuffix || !typeContext(reference, includeAnnotationsAndThrows)) return null
    return reference
  }

  private fun staticReference(file: PsiJavaFile, target: JavaUnresolvedStaticMemberDiagnostic): PsiReferenceExpression? {
    val leaf = file.findElementAt(target.referenceStart) ?: return null
    val reference = PsiTreeUtil.getParentOfType(leaf, PsiReferenceExpression::class.java, false) ?: return null
    val token = reference.referenceNameElement ?: return null
    if (reference.qualifierExpression != null || token.textRange.startOffset != target.referenceStart ||
        token.textRange.endOffset != target.referenceEnd || token.text != target.simpleName || excluded(reference)) return null
    return reference
  }

  private fun qualifiedText(container: PsiElement, reference: PsiJavaCodeReferenceElement, name: String): String {
    val token = requireNotNull(reference.referenceNameElement)
    val start = token.textRange.startOffset - container.textRange.startOffset
    return container.text.replaceRange(start, start + token.textLength, name)
  }

  private fun typeCandidate(
      file: PsiJavaFile,
      reference: PsiJavaCodeReferenceElement,
      qualifiedName: String,
      cancelChecker: ICancelChecker,
  ): Boolean {
    cancelChecker.abortIfCancelled()
    val facade = JavaPsiFacade.getInstance(file.project)
    val targetClass = facade.findClass(qualifiedName, file.resolveScope) ?: return false
    val simpleName = reference.referenceName ?: return false
    if (targetClass.name != simpleName) return false
    val imports = file.importList?.importStatements.orEmpty().filter { !it.isOnDemand }
    if (imports.any {
          it.qualifiedName?.substringAfterLast('.') == simpleName &&
              it.qualifiedName != qualifiedName
        }) return false
    if (targetClass.containingClass == null) {
      val packageName = qualifiedName.substringBeforeLast('.', "")
      if (packageName == file.packageName || packageName == "java.lang" ||
          packageName.isEmpty() && file.packageName.isNotEmpty()) return false
    }
    val visible = facade.resolveHelper.resolveReferencedClass(simpleName, reference)
    if (visible != null) return false
    var owner: PsiClass? = targetClass
    while (owner != null) {
      if (!PsiUtil.isAccessible(owner, reference, null)) return false
      owner = owner.containingClass
    }
    val end = terminal(reference)
    val parsed = JavaPsiFacade.getElementFactory(file.project).createReferenceFromText(
        qualifiedText(end, reference, qualifiedName), end,
    )
    val result = parsed.advancedResolve(false)
    val selected = result.element as? PsiClass ?: return false
    var expected = targetClass
    for (name in suffix(reference)) {
      expected = expected.findInnerClassByName(name, true) ?: return false
    }
    val annotation = end.parent as? PsiAnnotation
    return result.isValidResult && selected.isEquivalentTo(expected) &&
        (annotation?.nameReferenceElement !== end || selected.isAnnotationType)
  }

  fun candidates(
      file: PsiJavaFile,
      simpleName: String,
      references: List<PsiJavaCodeReferenceElement>,
      qualifiedNames: Collection<String>,
      cancelChecker: ICancelChecker,
  ): List<String> = qualifiedNames.asSequence().distinct().sorted().filter { qualifiedName ->
    references.isNotEmpty() && references.all { reference ->
      reference.referenceName == simpleName && typeCandidate(file, reference, qualifiedName, cancelChecker)
    }
  }.toList()

  private fun staticMemberApplicable(
      file: PsiJavaFile,
      target: JavaUnresolvedStaticMemberDiagnostic,
      reference: PsiReferenceExpression,
      ownerName: String,
      cancelChecker: ICancelChecker,
      verifySelection: Boolean = false,
  ): Boolean {
    cancelChecker.abortIfCancelled()
    if (PsiUtil.isAccessedForWriting(reference)) return false
    val owner = JavaPsiFacade.getInstance(file.project).findClass(ownerName, file.resolveScope) ?: return false
    var enclosing: PsiClass? = owner
    while (enclosing != null) {
      if (!PsiUtil.isAccessible(enclosing, reference, null)) return false
      enclosing = enclosing.containingClass
    }
    val factory = JavaPsiFacade.getElementFactory(file.project)
    val parsed = if (target.isMethod) {
      val call = reference.parent as? PsiMethodCallExpression ?: return false
      if (call.methodExpression !== reference) return false
      val typeArguments = reference.parameterList?.text.orEmpty()
      val referenceStart = reference.textRange.startOffset - call.textRange.startOffset
      val referenceEnd = reference.textRange.endOffset - call.textRange.startOffset
      val callText = call.text.replaceRange(
          referenceStart, referenceEnd, "$ownerName.$typeArguments${target.simpleName}",
      )
      factory.createExpressionFromText(callText, call) as PsiMethodCallExpression
    } else {
      factory.createExpressionFromText("$ownerName.${target.simpleName}", reference) as PsiReferenceExpression
    }
    val result = when (parsed) {
      is PsiMethodCallExpression -> parsed.resolveMethodGenerics()
      is PsiReferenceExpression -> parsed.advancedResolve(false)
      else -> return false
    }
    val member = result.element as? PsiMember ?: return false
    if (!result.isValidResult || !member.hasModifierProperty(PsiModifier.STATIC)) return false
    if (!verifySelection) return true
    if (target.isMethod) {
      val selected = (reference.parent as PsiMethodCallExpression).resolveMethodGenerics()
      return selected.isValidResult && selected.element?.isEquivalentTo(member) == true
    }
    val selected = reference.advancedResolve(false)
    if (!selected.isValidResult || selected.element?.isEquivalentTo(member) != true) return false
    return !isReferenceQualifier(reference) ||
        (reference.parent as PsiReferenceExpression).resolve() != null
  }

  private class ImportContext(
      original: PsiJavaFile,
      normalNames: Collection<String>,
      staticNames: Collection<String>,
  ) {
    private val offset = original.packageStatement?.textRange?.endOffset ?: 0
    private val declarations = "\n" + (
        normalNames.distinct().sorted().map { "import $it;" } +
            staticNames.distinct().sorted().map { "import static $it;" }
        ).joinToString("\n") + "\n"
    val file = (PsiFileFactory.getInstance(original.project).createFileFromText(
        original.name, original.language,
        original.text.replaceRange(offset, offset, declarations),
        false, false, false, original.viewProvider.virtualFile,
    ) as PsiJavaFile).also { (it as PsiFileImpl).setOriginalFile(original) }

    fun reference(target: JavaUnresolvedStaticMemberDiagnostic): PsiReferenceExpression? {
      val delta = if (target.referenceStart >= offset) declarations.length else 0
      return staticReference(file, target.copy(
          referenceStart = target.referenceStart + delta,
          referenceEnd = target.referenceEnd + delta,
      ))
    }
  }

  private fun isReferenceQualifier(reference: PsiReferenceExpression): Boolean =
      (reference.parent as? PsiReferenceExpression)?.qualifierExpression === reference

  private fun staticKey(simpleName: String, isMethod: Boolean): String = "$simpleName|$isMethod"

  fun choices(
      file: PsiJavaFile,
      types: List<JavaUnresolvedTypeDiagnostic>,
      members: List<JavaUnresolvedStaticMemberDiagnostic>,
      topLevelNames: Map<String, List<String>>,
      nestedTypeNames: Map<String, List<String>>,
      staticOwnerNames: Map<String, List<String>>,
      cancelChecker: ICancelChecker,
  ): JavaPsiFixImportsResult {
    val typeChoices = ArrayList<JavaPsiImportChoices>()
    for ((name, uses) in types.groupBy { it.simpleName }) {
      cancelChecker.abortIfCancelled()
      val references = uses.mapNotNull { reference(file, it, includeAnnotationsAndThrows = true) }
      if (references.size != uses.size) continue
      val names = topLevelNames[name].orEmpty() + nestedTypeNames[name].orEmpty()
      val accepted = candidates(file, name, references, names, cancelChecker)
      if (accepted.isNotEmpty()) typeChoices += JavaPsiImportChoices(name, accepted)
    }
    val staticChoices = ArrayList<JavaPsiStaticImportChoices>()
    for ((key, uses) in members.groupBy { staticKey(it.simpleName, it.isMethod) }) {
      cancelChecker.abortIfCancelled()
      val references = uses.mapNotNull { staticReference(file, it) }
      if (references.size != uses.size) continue
      val accepted = staticOwnerNames[uses.first().simpleName].orEmpty().distinct().sorted().filter { owner ->
        cancelChecker.abortIfCancelled()
        val method = uses.first().isMethod
        val qualifierUse = !method && references.any { isReferenceQualifier(it) }
        val context = when {
          method -> ImportContext(file, emptyList(), listOf("$owner.*"))
          qualifierUse -> ImportContext(file, emptyList(), listOf("$owner.${uses.first().simpleName}"))
          else -> null
        }
        references.indices.all { index ->
          val candidateReference = if (context == null) references[index] else {
            context.reference(uses[index]) ?: return@all false
          }
          staticMemberApplicable(
              context?.file ?: file, uses[index], candidateReference, owner, cancelChecker,
              verifySelection = !method && isReferenceQualifier(candidateReference),
          )
        }
      }
      if (accepted.isNotEmpty()) {
        staticChoices += JavaPsiStaticImportChoices(uses.first().simpleName, uses.first().isMethod, accepted, key)
      }
    }
    return JavaPsiFixImportsResult(typeChoices, staticChoices)
  }

  fun candidatesWithEdits(
      file: PsiJavaFile,
      target: JavaUnresolvedTypeDiagnostic,
      qualifiedNames: List<String>,
  ): List<JavaPsiImportCandidate> {
    val layout = ImportLayout(file, target.file, target.documentVersion, target.documentRevision)
    return qualifiedNames.map { qualifiedName ->
      JavaPsiImportCandidate(qualifiedName, layout.edits(listOf(qualifiedName)).single())
    }
  }

  fun generate(file: PsiJavaFile, params: JavaPsiGenerateImportsParams): List<JavaPsiTextEdit>? {
    params.cancelChecker.abortIfCancelled()
    if (params.selectedImports.isEmpty() && params.selectedStaticImports.isEmpty()) return emptyList()
    val targets = unresolved(
        file, JavaPsiFixImportsParams(params.file, params.documentVersion, params.documentRevision, null, params.cancelChecker),
    )
    val groupedTypes = targets.types.groupBy { it.simpleName }
    val groupedMembers = targets.members.groupBy { staticKey(it.simpleName, it.isMethod) }
    val normalImports = LinkedHashSet<String>()
    val staticImports = LinkedHashSet<String>()
    for ((key, qualifiedName) in params.selectedImports) {
      val uses = groupedTypes[key] ?: return null
      for (use in uses) {
        val reference = reference(file, use, includeAnnotationsAndThrows = true) ?: return null
        if (!typeCandidate(file, reference, qualifiedName, params.cancelChecker)) return null
      }
      normalImports += qualifiedName
    }
    for ((key, ownerName) in params.selectedStaticImports) {
      val uses = groupedMembers[key] ?: return null
      staticImports += "$ownerName.${uses.first().simpleName}"
    }
    if (staticImports.isNotEmpty()) {
      params.cancelChecker.abortIfCancelled()
      val context = ImportContext(file, normalImports, staticImports)
      for ((key, ownerName) in params.selectedStaticImports) {
        for (use in groupedMembers.getValue(key)) {
          staticReference(file, use) ?: return null
          val reference = context.reference(use) ?: return null
          if (!staticMemberApplicable(
                  context.file, use, reference, ownerName, params.cancelChecker, verifySelection = true,
              )) return null
        }
      }
    }
    return ImportLayout(file, params.file, params.documentVersion, params.documentRevision)
        .edits(normalImports, staticImports)
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
    private val statements = file.importList?.allImportStatements.orEmpty()
    private val newlineOffset = content.indexOfFirst { it == '\r' || it == '\n' }
    private val newline = when {
      newlineOffset < 0 -> "\n"
      content[newlineOffset] == '\r' && content.getOrNull(newlineOffset + 1) == '\n' -> "\r\n"
      else -> content[newlineOffset].toString()
    }

    fun edits(normalNames: Collection<String>, staticNames: Collection<String> = emptyList()): List<JavaPsiTextEdit> {
      if (normalNames.isEmpty() && staticNames.isEmpty()) return emptyList()
      if (statements.isEmpty()) {
        val declarations = listOf(
            normalNames.distinct().sorted().joinToString(newline) { "import $it;" },
            staticNames.distinct().sorted().joinToString(newline) { "import static $it;" },
        ).filter(String::isNotEmpty).joinToString("$newline$newline")
        val (offset, text) = if (file.packageStatement != null) {
          after(file.packageStatement!!, declarations, true)
        } else {
          val firstClass = file.classes.firstOrNull()
          val start = firstClass?.docComment?.textRange?.startOffset ?: firstClass?.textRange?.startOffset ?: 0
          start to "$declarations$newline$newline"
        }
        val position = index.indexToPosition(offset)
        return listOf(JavaPsiTextEdit(path, version, revision, position.line, position.column, position.line, position.column, text))
      }
      val insertions = LinkedHashMap<Int, MutableList<String>>()
      for ((isStatic, names) in listOf(false to normalNames, true to staticNames)) {
        val matching = statements.filter { (it is PsiImportStaticStatement) == isStatic }
        val groups = names.distinct().sorted().groupBy { name ->
          matching.firstOrNull { statement ->
            val imported = statement.importReference?.qualifiedName
            imported != null && name < imported
          }
        }
        for ((before, group) in groups) {
          val declarations = group.joinToString(newline) { name ->
            if (isStatic) "import static $name;" else "import $name;"
          }
          val (offset, text) = when {
            before != null -> before.textRange.startOffset to "$declarations$newline"
            matching.isNotEmpty() -> after(matching.last(), declarations, isStatic && statements.last() !is PsiImportStaticStatement)
            isStatic -> after(statements.last(), declarations, true)
            statements.first() is PsiImportStaticStatement ->
              statements.first().textRange.startOffset to "$declarations$newline$newline"
            else -> statements.first().textRange.startOffset to "$declarations$newline"
          }
          insertions.getOrPut(offset) { ArrayList() }.add(text)
        }
      }
      return insertions.entries.sortedByDescending { it.key }.map { (offset, texts) ->
        val position = index.indexToPosition(offset)
        JavaPsiTextEdit(
            path, version, revision, position.line, position.column, position.line, position.column,
            texts.joinToString(""),
        )
      }
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
