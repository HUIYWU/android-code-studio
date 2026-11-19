package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.language.services.interop.JavaDiagnosticsResolver
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.JavaErrorCollector
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.errors.JavaCompilationError
import org.jetbrains.kotlin.com.intellij.java.codeserver.highlighting.errors.JavaErrorHighlightType
import org.jetbrains.kotlin.com.intellij.lang.LanguageUtil
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionManager
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionProfileEntry
import org.jetbrains.kotlin.com.intellij.codeInspection.LocalInspectionTool
import org.jetbrains.kotlin.com.intellij.codeInspection.LocalInspectionToolSession
import org.jetbrains.kotlin.com.intellij.codeInspection.NumericOverflowInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptor
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemHighlightType
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.com.intellij.codeInspection.SuppressionUtil
import org.jetbrains.kotlin.com.intellij.codeInspection.accessStaticViaInstance.AccessStaticViaInstanceBase
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionEngine
import org.jetbrains.kotlin.com.intellij.codeInspection.SuspiciousArrayMethodCallInspection
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptorBase
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptorUtil
import org.jetbrains.kotlin.com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal class LiveJavaDiagnosticsResolver(
    private val environment: LiveAnalysisEnvironment,
) : JavaDiagnosticsResolver {
  override fun diagnostics(file: Path, cancelChecker: ICancelChecker): List<DiagnosticItem>? {
    val startedAt = System.nanoTime()
    var stage = "begin"
    KlsLogs.info(
        "Java codeserver resolver begin: file={}, project={}, thread={}",
        file,
        System.identityHashCode(environment.project),
        Thread.currentThread().name,
    )
    return try {
      stage = "analysis-api-access"
      AnalysisApiGuard.withAccess(cancelChecker) {
        stage = "application-read-action"
        ApplicationManager.getApplication().runReadAction(Computable {
          stage = "project-analysis-read"
          environment.project.analysisRead {
            stage = "psi-lookup"
            val psiFile = environment.javaFiles.currentFile(file.normalize()) ?: run {
              KlsLogs.warn("Java codeserver resolver PSI missing: file={}", file)
              return@analysisRead null
            }
            stage = "psi-ready"
            KlsLogs.info(
                "Java codeserver resolver PSI ready: file={}, psi={}, textLength={}",
                file,
                System.identityHashCode(psiFile),
                psiFile.textLength,
            )
            cancelChecker.abortIfCancelled()
            stage = "collector"
            collect(psiFile, cancelChecker)

          }
        })
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      KlsLogs.warn(
          "Java codeserver resolver failed: file={}, stage={}, durationUs={}, type={}, message={}",
          file,
          stage,
          (System.nanoTime() - startedAt) / 1_000L,
          error.javaClass.name,
          error.message,
          error,
      )
      throw error
    }.also { result ->
      KlsLogs.info(
          "Java codeserver resolver end: file={}, result={}, diagnostics={}, durationUs={}",
          file,
          if (result == null) "unavailable" else "success",
          result?.size ?: 0,
          (System.nanoTime() - startedAt) / 1_000L,
      )
    }
  }

  private fun collect(
      file: PsiJavaFile,
      cancelChecker: ICancelChecker,
  ): List<DiagnosticItem> {
    val startedAt = System.nanoTime()
    KlsLogs.info(
        "Java codeserver collector begin: file={}, psi={}, textLength={}",
        file.virtualFile?.path,
        System.identityHashCode(file),
        file.textLength,
    )
    return try {
      val lineIndex = LineIndex.from(file.text)
      val result = ArrayList<DiagnosticItem>()
      var visitedElements = 0
      var syntaxDiagnostics = 0
      var semanticDiagnostics = 0
      val collector = JavaErrorCollector(file) { error ->
        cancelChecker.abortIfCancelled()
        result += toDiagnostic(error, lineIndex)
        semanticDiagnostics++
      }
      var inspectionDiagnostics = 0
      // TODO(ACS-ANALYSIS-EXPERIMENT): Validate NumericOverflow and SuspiciousArrayMethodCall diagnostics on device.
      val inspections =
          listOf(
              AccessStaticViaInstanceBase(),
              NumericOverflowInspection(),
              SuspiciousArrayMethodCallInspection(),
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
            visitedElements++
            inspectionRuns.forEach { run -> element.accept(run.visitor) }
            if (element is PsiErrorElement) {
              val rawRange = requireNotNull(element.textRange)
              val diagnostic = toSyntaxDiagnostic(element, rawRange, lineIndex)
              result += diagnostic
              syntaxDiagnostics++
              KlsLogs.info(
                  "Java codeserver parser error: file={}, psi={}, rawStart={}, rawEnd={}, range={}, message={}",
                  file.virtualFile?.path,
                  System.identityHashCode(file),
                  rawRange.startOffset,
                  rawRange.endOffset,
                  diagnostic.range,
                  diagnostic.message,
              )
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
          logSuppressionDecision(anchor, run.tool)
          if (SuppressionUtil.inspectionResultSuppressed(anchor, run.tool)) return@descriptorLoop
          val descriptorRange = descriptor.absoluteRange()
          val severity = descriptor.highlightType.toSeverity() ?: return@descriptorLoop
          result += DiagnosticItem(
              message = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, anchor),
              code = run.tool.shortName,
              range = Range(
                  lineIndex.indexToPosition(descriptorRange.startOffset),
                  lineIndex.indexToPosition(descriptorRange.endOffset),
              ),
              source = "java-inspection",
              severity = severity,
          )
          inspectionDiagnostics++
        }
      }
      result.sortedWith(DiagnosticItem.START_COMPARATOR).also { diagnostics ->
        KlsLogs.info(
            "Java codeserver collector end: file={}, psi={}, visitedElements={}, syntaxDiagnostics={}, semanticDiagnostics={}, inspectionDiagnostics={}, visitedDiagnostics={}, durationUs={}",
            file.virtualFile?.path,
            System.identityHashCode(file),
            visitedElements,
            syntaxDiagnostics,
            semanticDiagnostics,
            inspectionDiagnostics,
            diagnostics.size,
            (System.nanoTime() - startedAt) / 1_000L,
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      KlsLogs.warn(
          "Java codeserver collector failed: file={}, durationUs={}, type={}, message={}",
          file.virtualFile?.path,
          (System.nanoTime() - startedAt) / 1_000L,
          error.javaClass.name,
          error.message,
          error,
      )
      throw error
    }
  }

  // TODO(ACS-ANALYSIS-EXPERIMENT): Validate parser-error publication and point ranges with the live editor.
  private fun toSyntaxDiagnostic(
      error: PsiErrorElement,
      range: TextRange,
      lineIndex: LineIndex,
  ): DiagnosticItem {
    val startOffset = range.startOffset
    var endOffset = range.endOffset
    if (range.isEmpty) {
      val file = error.containingFile
      val fileLength = file.textLength
      val elementText = file.viewProvider.findElementAt(startOffset, LanguageUtil.getRootLanguage(error))?.text
      endOffset = if (startOffset < fileLength && elementText != null &&
          !elementText.startsWith("\n") && !elementText.startsWith("\r")) {
        startOffset + 1
      } else if (startOffset == 0) {
        minOf(1, fileLength)
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

  private data class InspectionRun(
      val tool: LocalInspectionTool,
      val holder: ProblemsHolder,
      val session: LocalInspectionToolSession,
      val visitor: PsiElementVisitor,
  )

  private fun logSuppressionDecision(
      anchor: PsiElement,
      tool: InspectionProfileEntry,
  ) {
    val suppressors = InspectionProfileEntry.getSuppressors(anchor)
    val suppressId = tool.suppressId
    val alternativeId = tool.alternativeID
    val suppressorResults = suppressors.joinToString("|") { suppressor ->
      val primary = suppressor.isSuppressedFor(anchor, suppressId)
      val alternative = alternativeId?.let { suppressor.isSuppressedFor(anchor, it) } ?: false
      "${suppressor.javaClass.name}(primary=$primary,alternative=$alternative)"
    }
    val parentChain = generateSequence(anchor) { it.parent }
        .take(8)
        .joinToString(" > ") { element ->
          "${element.javaClass.simpleName}:${element.text.replace("\\n", "\\\\n").take(48)}"
        }
    val modifierOwner = PsiTreeUtil.getParentOfType(anchor, PsiModifierListOwner::class.java, false)
    val modifierList = modifierOwner?.modifierList
    KlsLogs.info(
        "Java inspection suppression: file={}, anchorClass={}, anchorRange={}, language={}, tool={}, suppressId={}, alternativeId={}, officialSuppressed={}, suppressors={}, suppressorResults={}, parentChain={}, modifierOwner={}, modifierList={}",
        anchor.containingFile?.virtualFile?.path,
        anchor.javaClass.name,
        anchor.textRange,
        anchor.language.id,
        tool.shortName,
        suppressId,
        alternativeId,
        tool.isSuppressedFor(anchor),
        suppressors.map { it.javaClass.name },
        suppressorResults,
        parentChain,
        modifierOwner?.javaClass?.name,
        modifierList?.text,
    )
  }

  private fun ProblemDescriptor.absoluteRange(): TextRange {
    if (this is ProblemDescriptorBase) return requireNotNull(textRange)
    val start = requireNotNull(startElement.textRange)
    val end = requireNotNull(endElement.textRange)
    val range = start.union(end)
    val rangeInElement = textRangeInElement ?: return range
    return TextRange(
        start.startOffset + rangeInElement.startOffset,
        start.startOffset + rangeInElement.endOffset,
    )
  }

  private fun ProblemHighlightType.toSeverity(): DiagnosticSeverity? =
      when (this) {
        ProblemHighlightType.POSSIBLE_PROBLEM -> null
        ProblemHighlightType.ERROR,
        ProblemHighlightType.GENERIC_ERROR,
        -> DiagnosticSeverity.ERROR
        ProblemHighlightType.INFO,
        ProblemHighlightType.INFORMATION,
        -> DiagnosticSeverity.INFO
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
        ProblemHighlightType.LIKE_DEPRECATED,
        ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL,
        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
        ProblemHighlightType.WARNING,
        ProblemHighlightType.WEAK_WARNING,
        -> DiagnosticSeverity.WARNING
      }

  private fun toDiagnostic(
      error: JavaCompilationError<*, *>,
      lineIndex: LineIndex,
  ): DiagnosticItem {
    val anchor = error.anchor()
    val anchorRange = requireNotNull(anchor.textRange)
    val relativeRange = error.range()
    val startOffset =
        if (relativeRange == null) anchorRange.startOffset else anchorRange.startOffset + relativeRange.startOffset
    val endOffset =
        if (relativeRange == null) anchorRange.endOffset else anchorRange.startOffset + relativeRange.endOffset
    return DiagnosticItem(
        message = error.description().toString(),
        code = error.kind().key(),
        range = Range(lineIndex.indexToPosition(startOffset), lineIndex.indexToPosition(endOffset)),
        source = "java-codeserver",
        severity = error.highlightType().toSeverity(),
    )
  }

  private fun JavaErrorHighlightType.toSeverity(): DiagnosticSeverity =
      when (this) {
        JavaErrorHighlightType.PENDING_REF -> DiagnosticSeverity.INFO
        JavaErrorHighlightType.ERROR,
        JavaErrorHighlightType.FILE_LEVEL_ERROR,
        JavaErrorHighlightType.WRONG_REF,
        JavaErrorHighlightType.UNHANDLED_EXCEPTION,
        -> DiagnosticSeverity.ERROR
        else -> DiagnosticSeverity.ERROR
      }
}