package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionEngine
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionManager
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
import org.jetbrains.kotlin.com.intellij.codeInspection.accessStaticViaInstance.AccessStaticViaInstanceBase
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
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor

internal object JavaPsiDiagnosticsCollector {
  fun collect(
      file: PsiJavaFile,
      path: Path,
      documentVersion: Int,
      documentRevision: Long,
      cancelChecker: ICancelChecker,
  ): List<DiagnosticItem> {
    val lineIndex = LineIndex.from(file.text)
    val result = ArrayList<DiagnosticItem>()
    val collector = JavaErrorCollector(file) { error ->
      cancelChecker.abortIfCancelled()
      result += toCompilationDiagnostic(error, lineIndex, path, documentVersion, documentRevision)
    }
    val inspections = listOf(
        AccessStaticViaInstanceBase(),
        NumericOverflowInspection(),
        SuspiciousArrayMethodCallInspection(),
        SafeVarargsHasNoEffectInspection(),
        SafeVarargsOnNonReifiableTypeInspection(),
    )
    val inspectionRuns = inspections.map { inspection ->
      val holder = ProblemsHolder(InspectionManager.getInstance(file.project), file, true)
      val session = LocalInspectionToolSession(file, file.textRange, file.textRange, HighlightSeverity.INFORMATION)
      val visitor = InspectionEngine.createVisitor(inspection, holder, true, session)
      inspection.inspectionStarted(session, true)
      InspectionRun(inspection, holder, session, visitor)
    }
    try {
      file.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
          cancelChecker.abortIfCancelled()
          inspectionRuns.forEach { run -> element.accept(run.visitor) }
          if (element is PsiErrorElement) {
            val rawRange = requireNotNull(element.textRange)
            result += toSyntaxDiagnostic(element, rawRange, lineIndex)
          }
          collector.processElement(element)
          super.visitElement(element)
        }
      })
    } finally {
      inspectionRuns.forEach { run -> run.tool.inspectionFinished(run.session, run.holder) }
    }
    inspectionRuns.forEach { run ->
      run.holder.results.forEach descriptorLoop@{ descriptor ->
        cancelChecker.abortIfCancelled()
        val anchor = requireNotNull(descriptor.psiElement)
        if (SuppressionUtil.inspectionResultSuppressed(anchor, run.tool)) return@descriptorLoop
        val severity = descriptor.highlightType.toSeverity() ?: return@descriptorLoop
        val range = descriptor.absoluteRange()
        result += DiagnosticItem(
            message = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, anchor),
            code = run.tool.shortName,
            range = Range(lineIndex.indexToPosition(range.startOffset), lineIndex.indexToPosition(range.endOffset)),
            source = "java-inspection",
            severity = severity,
        )
      }
    }
    return result.sortedWith(DiagnosticItem.START_COMPARATOR)
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
      }
    }
  }

  private data class InspectionRun(
      val tool: LocalInspectionTool,
      val holder: ProblemsHolder,
      val session: LocalInspectionToolSession,
      val visitor: PsiElementVisitor,
  )

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