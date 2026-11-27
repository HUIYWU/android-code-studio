package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.language.services.java.models.DiagnosticCode
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionEngine
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionManager
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionProfileEntry
import org.jetbrains.kotlin.com.intellij.codeInspection.LocalInspectionTool
import org.jetbrains.kotlin.com.intellij.codeInspection.LocalInspectionToolSession
import org.jetbrains.kotlin.com.intellij.codeInspection.NumericOverflowInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptor
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptorBase
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptorUtil
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemHighlightType
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.com.intellij.codeInspection.SafeVarargsHasNoEffectInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.SafeVarargsOnNonReifiableTypeInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.SuppressionUtil
import org.jetbrains.kotlin.com.intellij.codeInspection.SuspiciousArrayMethodCallInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.uncheckedWarnings.UncheckedWarningLocalInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.unneededThrows.RedundantThrowsDeclarationLocalInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.accessStaticViaInstance.AccessStaticViaInstanceBase
import org.jetbrains.kotlin.com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import org.jetbrains.kotlin.com.intellij.profile.codeInspection.InspectionProjectProfileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiPatternVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiResourceVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter

import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.JavaErrorCollector
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.errors.JavaCompilationError
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.errors.JavaErrorHighlightType
import org.jetbrains.kotlin.com.intellij.lang.LanguageUtil
import org.jetbrains.kotlin.com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiImplicitClass
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiResourceListElement
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceList
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil

internal object JavaPsiDiagnosticsCollector {
  fun collect(
      file: PsiJavaFile,
      path: Path,
      documentVersion: Int,
      documentRevision: Long,
      cancelChecker: ICancelChecker,
  ): List<DiagnosticItem> {
    val trace = IdeLogConfig.shouldLogInfo()
    val startedAt = if (trace) System.nanoTime() else 0L
    var codeserverNs = 0L
    var unresolvedMethodNs = 0L
    var elements = 0
    val lineIndex = LineIndex.from(file.text)
    val result = ArrayList<DiagnosticItem>()
    val collector = JavaErrorCollector(file) { error ->
      cancelChecker.abortIfCancelled()
      result += toCompilationDiagnostic(error, lineIndex, path, documentVersion, documentRevision)
    }
    val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
    val unused = profile.getUnwrappedTool(UnusedDeclarationInspectionBase.SHORT_NAME, file)
        as UnusedDeclarationInspectionBase
    val inspections = listOf(
        unused.sharedLocalInspectionTool,
        AccessStaticViaInstanceBase(),
        NumericOverflowInspection(),
        SuspiciousArrayMethodCallInspection(),
        SafeVarargsHasNoEffectInspection(),
        SafeVarargsOnNonReifiableTypeInspection(),
        UncheckedWarningLocalInspection(),
        RedundantThrowsDeclarationLocalInspection(),
    )
    val inspectionManager = InspectionManager.getInstance(file.project)
    val inspectionRuns = inspections.map { inspection ->
      val initStartedAt = if (trace) System.nanoTime() else 0L
      val holder = ProblemsHolder(inspectionManager, file, true)
      val session = LocalInspectionToolSession(file, file.textRange, file.textRange, HighlightSeverity.INFORMATION)
      val onTheFly = inspection !is UncheckedWarningLocalInspection
      val visitor = InspectionEngine.createVisitor(inspection, holder, onTheFly, session)
      inspection.inspectionStarted(session, onTheFly)
      InspectionRun(inspection, holder, session, visitor).also {
        if (trace) it.initNs = System.nanoTime() - initStartedAt
      }
    }
    val setupNs = if (trace) System.nanoTime() - startedAt else 0L
    val walkStartedAt = if (trace) System.nanoTime() else 0L
    var walked = false
    try {
      file.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
          cancelChecker.abortIfCancelled()
          if (trace) elements++
          inspectionRuns.forEach { run ->
            if (!trace) {
              element.accept(run.visitor)
            } else {
              val method = if (run.tool is RedundantThrowsDeclarationLocalInspection) element as? PsiMethod else null
              val problemsBefore = if (method != null) run.holder.results.size else 0
              val visitorStartedAt = System.nanoTime()
              var completed = false
              try {
                element.accept(run.visitor)
                completed = true
              } finally {
                val elapsed = System.nanoTime() - visitorStartedAt
                run.visitNs += elapsed
                run.visits++
                if (method != null) {
                  KlsLogs.info(
                      "Java inspect throws method perf: file={}, version={}, revision={}, method={}, offset={}, durationUs={}, addedProblems={}, completed={}",
                      path, documentVersion, documentRevision, method.name, method.textRange.startOffset,
                      elapsed / 1_000L, run.holder.results.size - problemsBefore, completed,
                  )
                }
              }
            }
          }
          if (element is PsiErrorElement) {
            val rawRange = requireNotNull(element.textRange)
            result += toSyntaxDiagnostic(element, rawRange, lineIndex)
          }
          val codeserverStartedAt = if (trace) System.nanoTime() else 0L
          try {
            collector.processElement(element)
          } finally {
            if (trace) codeserverNs += System.nanoTime() - codeserverStartedAt
          }
          if (element is PsiMethodCallExpression) {
            val unresolvedStartedAt = if (trace) System.nanoTime() else 0L
            try {
              unresolvedMethodTarget(element, path, documentVersion, documentRevision)?.let { target ->
                result += DiagnosticItem(
                    message = "Cannot resolve method '${target.methodName}'",
                    code = JavaUnresolvedMethodDiagnostic.CODE,
                    range = Range(
                        lineIndex.indexToPosition(target.highlightStart),
                        lineIndex.indexToPosition(target.highlightEnd),
                    ),
                    source = JavaUnresolvedMethodDiagnostic.SOURCE,
                    severity = DiagnosticSeverity.ERROR,
                ).also { it.extra = target }
              }
            } finally {
              if (trace) unresolvedMethodNs += System.nanoTime() - unresolvedStartedAt
            }
          }
          super.visitElement(element)
        }
      })
      walked = true
    } finally {
      val walkNs = if (trace) System.nanoTime() - walkStartedAt else 0L
      inspectionRuns.forEach { run ->
        val finishStartedAt = if (trace) System.nanoTime() else 0L
        try {
          run.tool.inspectionFinished(run.session, run.holder)
        } finally {
          if (trace) run.finishNs = System.nanoTime() - finishStartedAt
        }
      }
      if (trace) {
        inspectionRuns.forEach { run ->
          KlsLogs.info(
              "Java inspect tool perf: file={}, version={}, revision={}, tool={}, initUs={}, visits={}, visitUs={}, finishUs={}, problems={}, walked={}",
              path, documentVersion, documentRevision, run.tool.shortName, run.initNs / 1_000L,
              run.visits, run.visitNs / 1_000L, run.finishNs / 1_000L, run.holder.results.size, walked,
          )
        }
        KlsLogs.info(
            "Java inspect walk perf: file={}, version={}, revision={}, setupUs={}, walkUs={}, elements={}, visitorUs={}, codeserverUs={}, unresolvedMethodUs={}, walked={}",
            path, documentVersion, documentRevision, setupNs / 1_000L, walkNs / 1_000L, elements,
            inspectionRuns.sumOf { it.visitNs } / 1_000L, codeserverNs / 1_000L, unresolvedMethodNs / 1_000L, walked,
        )
      }
    }
    val descriptorsStartedAt = if (trace) System.nanoTime() else 0L
    inspectionRuns.forEach { run ->
      run.holder.results.forEach { descriptor ->
        addInspectionDiagnostic(
            descriptor, run.tool, lineIndex, path, documentVersion, documentRevision, cancelChecker, result,
        )
      }
    }
    JavaUnusedImportsInspection.inspect(file, cancelChecker) { inspection, descriptor ->
      addInspectionDiagnostic(
          descriptor, inspection, lineIndex, path, documentVersion, documentRevision, cancelChecker, result,
      )
    }
    val descriptorsNs = if (trace) System.nanoTime() - descriptorsStartedAt else 0L
    val sortStartedAt = if (trace) System.nanoTime() else 0L
    val sorted = result.sortedWith(DiagnosticItem.START_COMPARATOR)
    if (trace) {
      val now = System.nanoTime()
      KlsLogs.info(
          "Java inspect collect perf: file={}, version={}, revision={}, totalUs={}, descriptorsUs={}, sortUs={}, diagnosticCount={}",
          path, documentVersion, documentRevision, (now - startedAt) / 1_000L,
          descriptorsNs / 1_000L, (now - sortStartedAt) / 1_000L, sorted.size,
      )
    }
    return sorted
  }

  private fun toSyntaxDiagnostic(
      error: PsiErrorElement,
      range: TextRange,
      lineIndex: LineIndex,
  ): DiagnosticItem {
    val startOffset = range.startOffset
    var endOffset = range.endOffset
    if (range.isEmpty) {
      val file = error.containingFile
      val elementText = file.viewProvider.findElementAt(startOffset, LanguageUtil.getRootLanguage(error))?.text
      endOffset = if (startOffset < file.textLength && elementText != null &&
          !elementText.startsWith("\n") && !elementText.startsWith("\r")) {
        startOffset + 1
      } else if (startOffset == 0) {
        minOf(1, file.textLength)
      } else {
        startOffset
      }
    }
    return DiagnosticItem(
        message = error.errorDescription,
        code = "java.parser.error",
        range = Range(lineIndex.indexToPosition(startOffset), lineIndex.indexToPosition(endOffset)),
        source = "java-psi-parser",
        severity = DiagnosticSeverity.ERROR,
    )
  }

  private fun toCompilationDiagnostic(
      error: JavaCompilationError<*, *>,
      lineIndex: LineIndex,
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
  ): DiagnosticItem {
    val anchor = error.anchor()
    val anchorRange = requireNotNull(anchor.textRange)
    val relativeRange = error.range()
    val startOffset = if (relativeRange == null) anchorRange.startOffset
    else anchorRange.startOffset + relativeRange.startOffset
    val endOffset = if (relativeRange == null) anchorRange.endOffset
    else anchorRange.startOffset + relativeRange.endOffset
    return DiagnosticItem(
        message = error.description().toString(),
        code = error.kind().key(),
        range = Range(lineIndex.indexToPosition(startOffset), lineIndex.indexToPosition(endOffset)),
        source = "java-codeserver",
        severity = error.highlightType().toSeverity(),
    ).also { item ->
      unresolvedTypeTarget(error, file, documentVersion, documentRevision, startOffset, endOffset)?.let {
        item.extra = it
      }
      if (error.kind().key() == JavaAbstractMethodsDiagnostic.CODESERVER_KEY &&
          error.psi() is PsiClass && error.psi() !is PsiEnumConstant) {
        val target = error.psi() as PsiClass
        item.extra = JavaAbstractMethodsDiagnostic(
            file.normalize(), documentVersion, documentRevision,
            startOffset, target is PsiAnonymousClass,
            target.qualifiedName ?: target.name,
            (target as? PsiAnonymousClass)?.baseClassType?.resolve()?.qualifiedName,
            startOffset, endOffset, JavaAbstractMethodsDiagnostic.Backend.CODESERVER,
        )
      } else if (error.kind().key() == JavaUnhandledExceptionDiagnostic.CODESERVER_KEY &&
          error.psi() !is PsiResourceListElement) {
        val errorElement = error.psi()
        val method = if (errorElement is PsiMethod) errorElement
        else PsiTreeUtil.getParentOfType(errorElement, PsiMethod::class.java, false)
        var ancestor: PsiElement? = errorElement
        var functionBoundary = false
        while (ancestor != null && ancestor !== method) {
          if (ancestor is PsiLambdaExpression || ancestor is PsiMethodReferenceExpression) {
            functionBoundary = true
            break
          }
          ancestor = ancestor.parent
        }
        val owner = method?.containingClass
        val exceptionTypes = (error.context() as? Collection<*>)?.mapNotNull { it as? PsiClassType }.orEmpty()
        val qualifiedNames = exceptionTypes.map { it.resolve()?.qualifiedName }.takeIf { names ->
          names.isNotEmpty() && names.all { it != null }
        }?.filterNotNull().orEmpty()
        if (method != null && owner != null && owner !is PsiImplicitClass &&
            errorElement !is PsiClass && errorElement !is PsiEnumConstant &&
            errorElement !is PsiLambdaExpression && errorElement !is PsiMethodReferenceExpression &&
            !functionBoundary && qualifiedNames.isNotEmpty()) {
          val methodRange = requireNotNull(method.textRange)
          val ownerRange = requireNotNull(owner.textRange)
          item.extra = JavaUnhandledExceptionDiagnostic(
              file.normalize(), documentVersion, documentRevision,
              startOffset, endOffset, methodRange.startOffset, methodRange.endOffset,
              ownerRange.startOffset, ownerRange.endOffset, owner.qualifiedName,
              method.name, method.isConstructor,
              method.parameterList.parameters.map { TypeConversionUtil.erasure(it.type).canonicalText },
              qualifiedNames.distinct(),
          )
        }
      }
    }
  }

  private fun unresolvedMethodTarget(
      call: PsiMethodCallExpression,
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
  ): JavaUnresolvedMethodDiagnostic? {
    val reference = call.methodExpression
    if (reference.qualifierExpression != null || reference.resolve() != null) return null
    val nameElement = reference.referenceNameElement ?: return null
    val callRange = call.textRange ?: return null
    val owner = PsiTreeUtil.getParentOfType(call, PsiClass::class.java, false) ?: return null
    val ownerRange = owner.textRange ?: return null
    if (callRange.startOffset < ownerRange.startOffset || callRange.endOffset > ownerRange.endOffset) return null
    val methodName = reference.referenceName ?: return null
    if (methodName.isEmpty()) return null
    val nameRange = nameElement.textRange
    return JavaUnresolvedMethodDiagnostic(
        file.normalize(), documentVersion, documentRevision,
        nameRange.startOffset, nameRange.endOffset, callRange.startOffset, callRange.endOffset,
        ownerRange.startOffset, ownerRange.endOffset, methodName,
    )
  }

  internal fun unresolvedTypeTarget(
      error: JavaCompilationError<*, *>,
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
      highlightStart: Int,
      highlightEnd: Int,
      includeAnnotationsAndThrows: Boolean = false,
  ): JavaUnresolvedTypeDiagnostic? {
    if (error.kind().key() !in setOf(
            JavaUnresolvedTypeDiagnostic.REFERENCE_UNRESOLVED_KEY,
            JavaUnresolvedTypeDiagnostic.TYPE_UNKNOWN_CLASS_KEY,
        )) return null
    val psi = error.psi()
    val reference = when {
      psi is PsiTypeElement -> psi.innermostComponentReferenceElement
      psi is PsiJavaCodeReferenceElement -> psi
      else -> null
    } ?: return null
    if (reference.qualifier != null) return null
    if (PsiTreeUtil.getParentOfType(reference, PsiImportStatementBase::class.java, false) != null) return null
    val referenceList = PsiTreeUtil.getParentOfType(reference, PsiReferenceList::class.java, false)
    if (!includeAnnotationsAndThrows && referenceList?.role == PsiReferenceList.Role.THROWS_LIST) return null
    val typeElement = PsiTreeUtil.getParentOfType(reference, PsiTypeElement::class.java, false)
    val parent = reference.parent
    val annotationName = parent is PsiAnnotation && parent.nameReferenceElement === reference
    val typeContext = typeElement?.innermostComponentReferenceElement === reference ||
        referenceList != null || parent is PsiNewExpression && parent.classOrAnonymousClassReference === reference ||
        includeAnnotationsAndThrows && annotationName
    if (!typeContext || !includeAnnotationsAndThrows && annotationName) return null
    val name = reference.referenceName ?: return null
    val nameElement = reference.referenceNameElement ?: return null
    if (name.isEmpty() || nameElement.text != name) return null
    val range = nameElement.textRange
    if (highlightStart > range.startOffset || highlightEnd < range.endOffset) return null
    return JavaUnresolvedTypeDiagnostic(
        file.normalize(), documentVersion, documentRevision,
        highlightStart, highlightEnd, range.startOffset, range.endOffset, nameElement.text, name,
    )
  }

  private fun uncheckedWarningTarget(
      anchor: PsiElement,
      highlightRange: TextRange,
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
  ): JavaUncheckedWarningDiagnostic? {
    val containingFile = anchor.containingFile as? PsiJavaFile ?: return null
    val owner = uncheckedOwner(anchor, highlightRange) ?: return null
    val ownerRange = owner.textRange ?: return null
    if (highlightRange.startOffset < ownerRange.startOffset ||
        highlightRange.endOffset > ownerRange.endOffset ||
        ownerRange.startOffset < 0 || ownerRange.endOffset > containingFile.textLength) return null
    val kind = when (owner) {
      is PsiClass -> JavaUncheckedWarningDiagnostic.OwnerKind.CLASS
      is PsiMethod -> if (owner.isConstructor) {
        JavaUncheckedWarningDiagnostic.OwnerKind.CONSTRUCTOR
      } else {
        JavaUncheckedWarningDiagnostic.OwnerKind.METHOD
      }
      is PsiField -> JavaUncheckedWarningDiagnostic.OwnerKind.FIELD
      is PsiParameter -> JavaUncheckedWarningDiagnostic.OwnerKind.PARAMETER
      is PsiLocalVariable -> JavaUncheckedWarningDiagnostic.OwnerKind.LOCAL_VARIABLE
      else -> return null
    }
    return JavaUncheckedWarningDiagnostic(
        file.normalize(), documentVersion, documentRevision,
        highlightRange.startOffset, highlightRange.endOffset,
        ownerRange.startOffset, ownerRange.endOffset, kind,
    )
  }

  private fun uncheckedOwner(
      anchor: PsiElement,
      highlightRange: TextRange,
  ): PsiElement? {
    val containingFile = anchor.containingFile as? PsiJavaFile ?: return null
    fun find(start: PsiElement?): PsiElement? {
      var current = start
      while (current != null && current.containingFile === containingFile) {
        when (current) {
          is PsiField, is PsiParameter, is PsiLocalVariable, is PsiMethod, is PsiClass -> return current
        }
        current = current.parent
      }
      return null
    }
    val fileRange = containingFile.textRange
    if (highlightRange.startOffset >= fileRange.startOffset && highlightRange.endOffset <= fileRange.endOffset) {
      find(anchor)?.let { return it }
    }
    if (highlightRange.startOffset !in 0 until containingFile.textLength) return null
    val element = containingFile.findElementAt(highlightRange.startOffset) ?: return null
    return find(element)
  }

  private fun unusedSymbolTarget(
      anchor: PsiElement,
      highlightRange: TextRange,
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
  ): JavaUnusedSymbolDiagnostic? {
    val declaration = anchor as? PsiNameIdentifierOwner ?: anchor.parent as? PsiNameIdentifierOwner ?: return null
    val nameIdentifier = declaration.nameIdentifier ?: return null
    if (nameIdentifier.textRange != highlightRange) return null
    val kind = when (declaration) {
      is PsiPatternVariable -> JavaUnusedSymbolDiagnostic.DeclarationKind.PATTERN_VARIABLE
      is PsiResourceVariable -> JavaUnusedSymbolDiagnostic.DeclarationKind.RESOURCE_VARIABLE
      is PsiLocalVariable -> JavaUnusedSymbolDiagnostic.DeclarationKind.LOCAL_VARIABLE
      is PsiParameter -> JavaUnusedSymbolDiagnostic.DeclarationKind.PARAMETER
      is PsiEnumConstant -> JavaUnusedSymbolDiagnostic.DeclarationKind.ENUM_CONSTANT
      is PsiField -> JavaUnusedSymbolDiagnostic.DeclarationKind.FIELD
      is PsiMethod -> if (declaration.isConstructor) JavaUnusedSymbolDiagnostic.DeclarationKind.CONSTRUCTOR
          else JavaUnusedSymbolDiagnostic.DeclarationKind.METHOD
      is PsiTypeParameter -> JavaUnusedSymbolDiagnostic.DeclarationKind.TYPE_PARAMETER
      is PsiClass -> JavaUnusedSymbolDiagnostic.DeclarationKind.CLASS
      else -> return null
    }
    val declarationRange = declaration.textRange ?: return null
    return JavaUnusedSymbolDiagnostic(
        file.normalize(), documentVersion, documentRevision,
        highlightRange.startOffset, highlightRange.endOffset,
        declarationRange.startOffset, declarationRange.endOffset,
        nameIdentifier.text, kind,
    )
  }

  private fun unusedThrowsTarget(
      anchor: PsiElement,
      highlightRange: TextRange,
      file: Path,
      documentVersion: Int,
      documentRevision: Long,
  ): JavaUnusedThrowsDiagnostic? {
    val reference = anchor as? PsiJavaCodeReferenceElement
        ?: PsiTreeUtil.getParentOfType(anchor, PsiJavaCodeReferenceElement::class.java, false)
        ?: return null
    val referenceRange = reference.textRange
    val throwsList = PsiTreeUtil.getParentOfType(reference, PsiReferenceList::class.java, false)
        ?: return null
    if (throwsList.role != PsiReferenceList.Role.THROWS_LIST ||
        referenceRange.startOffset < highlightRange.startOffset ||
        referenceRange.endOffset > highlightRange.endOffset) return null
    val method = PsiTreeUtil.getParentOfType(reference, PsiMethod::class.java, false) ?: return null
    val owner = method.containingClass ?: return null
    val exception = reference.resolve() as? PsiClass ?: return null
    val exceptionQualifiedName = exception.qualifiedName ?: return null
    val methodRange = method.textRange
    val ownerRange = owner.textRange
    return JavaUnusedThrowsDiagnostic(
        file.normalize(), documentVersion, documentRevision,
        highlightRange.startOffset, highlightRange.endOffset,
        methodRange.startOffset, methodRange.endOffset,
        ownerRange.startOffset, ownerRange.endOffset, owner.qualifiedName,
        method.name, method.isConstructor,
        method.parameterList.parameters.map { TypeConversionUtil.erasure(it.type).canonicalText },
        exceptionQualifiedName, referenceRange.startOffset, referenceRange.endOffset, reference.text,
    )
  }

  private fun addInspectionDiagnostic(
      descriptor: ProblemDescriptor,
      tool: InspectionProfileEntry,
      lineIndex: LineIndex,
      path: Path,
      documentVersion: Int,
      documentRevision: Long,
      cancelChecker: ICancelChecker,
      result: MutableList<DiagnosticItem>,
  ) {
    cancelChecker.abortIfCancelled()
    val anchor = requireNotNull(descriptor.psiElement)
    if (SuppressionUtil.inspectionResultSuppressed(anchor, tool)) return
    val severity = descriptor.highlightType.toSeverity() ?: return
    val range = descriptor.absoluteRange()
    val item = DiagnosticItem(
        message = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, anchor),
        code = when (tool.shortName) {
          JavaUnusedThrowsDiagnostic.INSPECTION_SHORT_NAME -> DiagnosticCode.UNUSED_THROWS.id
          JavaUnusedSymbolDiagnostic.INSPECTION_SHORT_NAME -> JavaUnusedSymbolDiagnostic.CODE
          else -> tool.shortName
        },
        range = Range(lineIndex.indexToPosition(range.startOffset), lineIndex.indexToPosition(range.endOffset)),
        source = "java-inspection",
        severity = severity,
    )
    if (tool.shortName == JavaUnusedSymbolDiagnostic.INSPECTION_SHORT_NAME) {
      unusedSymbolTarget(anchor, range, path, documentVersion, documentRevision)?.let { item.extra = it }
    } else if (tool.shortName == JavaUnusedThrowsDiagnostic.INSPECTION_SHORT_NAME) {
      unusedThrowsTarget(anchor, range, path, documentVersion, documentRevision)?.let { item.extra = it }
    } else if (tool.shortName == JavaUncheckedWarningDiagnostic.INSPECTION_SHORT_NAME &&
        severity == DiagnosticSeverity.WARNING) {
      uncheckedWarningTarget(anchor, range, path, documentVersion, documentRevision)?.let { item.extra = it }
    }
    result += item
  }

  private data class InspectionRun(
      val tool: LocalInspectionTool,
      val holder: ProblemsHolder,
      val session: LocalInspectionToolSession,
      val visitor: PsiElementVisitor,
  ) {
    var initNs = 0L
    var visitNs = 0L
    var finishNs = 0L
    var visits = 0
  }

  private fun ProblemDescriptor.absoluteRange(): TextRange {
    if (this is ProblemDescriptorBase) return requireNotNull(textRange)
    val start = requireNotNull(startElement.textRange)
    val end = requireNotNull(endElement.textRange)
    val range = start.union(end)
    val relative = textRangeInElement ?: return range
    return TextRange(start.startOffset + relative.startOffset, start.startOffset + relative.endOffset)
  }

  private fun ProblemHighlightType.toSeverity(): DiagnosticSeverity? = when (this) {
    ProblemHighlightType.POSSIBLE_PROBLEM -> null
    ProblemHighlightType.ERROR,
    ProblemHighlightType.GENERIC_ERROR,
    -> DiagnosticSeverity.ERROR
    ProblemHighlightType.INFO,
    ProblemHighlightType.INFORMATION,
    -> DiagnosticSeverity.INFO
    else -> DiagnosticSeverity.WARNING
  }

  private fun JavaErrorHighlightType.toSeverity(): DiagnosticSeverity = when (this) {
    JavaErrorHighlightType.PENDING_REF -> DiagnosticSeverity.INFO
    else -> DiagnosticSeverity.ERROR
  }
}