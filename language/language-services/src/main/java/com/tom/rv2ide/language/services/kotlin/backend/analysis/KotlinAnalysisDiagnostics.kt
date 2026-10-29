package com.tom.rv2ide.language.services.kotlin.backend.analysis

import android.util.Log
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.models.Range
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.atomic.AtomicLong

private const val ANDROID_DIAGNOSTIC_TAG = "ACS-KotlinAnalysis"
private const val SAMPLER_INTERVAL_MS = 500L

// TODO(ACS-ANALYSIS-EXPERIMENT): Remove the stage counters, the file structure probe and the stall sampler once the Scudo abort is localized.
internal object KotlinAnalysisStageCounters {
  val declarationProviderCreations = AtomicLong()
  val packageProviderCreations = AtomicLong()

  @Volatile var standaloneSourceIndexState: () -> String = { "unavailable" }
}

internal object KotlinAnalysisDiagnostics {
  fun collectDiagnosticsFor(session: KaSession, file: KtFile): List<DiagnosticItem> {
    val beginNanos = System.nanoTime()
    val text = file.text
    val lineIndex = LineIndex.from(text)
    Log.i(
        ANDROID_DIAGNOSTIC_TAG,
        "diagnostics_stage parser_begin path=${file.name} textLength=${text.length} " +
            "thread=${Thread.currentThread().name}",
    )
    val parserBeginNanos = System.nanoTime()
    val parserItems =
        PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).map { error ->
          item(lineIndex, error.textRange, "PARSER_ERROR", error.errorDescription, DiagnosticSeverity.ERROR, "syntax")
        }
    Log.i(
        ANDROID_DIAGNOSTIC_TAG,
        "diagnostics_stage parser_end path=${file.name} count=${parserItems.size} " +
            "elapsedMs=${elapsedMillis(parserBeginNanos)}",
    )

    val structureProbe = probeFileStructure(session, file)
    KotlinAnalysisStallSampler.start(file.name, structureProbe)

    val checkerBeginNanos = System.nanoTime()
    Log.i(
        ANDROID_DIAGNOSTIC_TAG,
        "diagnostics_stage checker_begin path=${file.name} " +
            "declProviders=${KotlinAnalysisStageCounters.declarationProviderCreations.get()} " +
            "pkgProviders=${KotlinAnalysisStageCounters.packageProviderCreations.get()} " +
            "standaloneIndex=${KotlinAnalysisStageCounters.standaloneSourceIndexState()}",
    )
    val collected =
        try {
          with(session) { file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS) }
        } finally {
          KotlinAnalysisStallSampler.stop()
        }
    Log.i(
        ANDROID_DIAGNOSTIC_TAG,
        "diagnostics_stage checker_end path=${file.name} count=${collected.size} " +
            "elapsedMs=${elapsedMillis(checkerBeginNanos)} " +
            "standaloneIndex=${KotlinAnalysisStageCounters.standaloneSourceIndexState()}",
    )

    val items = buildList {
      addAll(parserItems)
      collected.forEach { diagnostic ->
        val ranges = diagnostic.textRanges.ifEmpty { listOf(diagnostic.psi.textRange) }
        ranges.forEach { range ->
          add(item(lineIndex, range, diagnostic.factoryName, diagnostic.defaultMessage, diagnostic.severity.toIdeSeverity(), "kotlin"))
        }
      }
    }
    val result = items.distinct().sortedWith(DiagnosticItem.START_COMPARATOR)
    Log.i(
        ANDROID_DIAGNOSTIC_TAG,
        "diagnostics_stage done path=${file.name} items=${result.size} elapsedMs=${elapsedMillis(beginNanos)}",
    )
    return result
  }

  private fun probeFileStructure(session: KaSession, file: KtFile): KotlinAnalysisFileStructureProbe? {
    val beginNanos = System.nanoTime()
    Log.i(ANDROID_DIAGNOSTIC_TAG, "diagnostics_stage file_structure_begin path=${file.name}")
    val probe =
        runCatching {
              val facade = invokeReflectiveNoArg(session, "getResolutionFacade") ?: return@runCatching null
              val module = invokeReflectiveNoArg(facade, "getUseSiteModule") ?: return@runCatching null
              val sessionProvider = invokeReflectiveNoArg(facade, "getSessionProvider") ?: return@runCatching null
              val resolvable =
                  sessionProvider.javaClass.methods
                      .firstOrNull { method -> method.name == "getResolvableSession" && method.parameterCount == 1 }
                      ?.invoke(sessionProvider, module)
                      ?: return@runCatching null
              val components =
                  invokeReflectiveNoArg(resolvable, "getModuleComponents\$low_level_api_fir")
                      ?: return@runCatching null
              val structureCache = invokeReflectiveNoArg(components, "getFileStructureCache") ?: return@runCatching null
              val fileStructure =
                  structureCache.javaClass.methods
                      .firstOrNull { method -> method.name == "getFileStructure" && method.parameterCount == 1 }
                      ?.invoke(structureCache, file)
                      ?: return@runCatching null
              KotlinAnalysisFileStructureProbe(structureCache, fileStructure)
            }
            .getOrElse { error ->
              Log.i(
                  ANDROID_DIAGNOSTIC_TAG,
                  "diagnostics_stage file_structure_probe_error path=${file.name} error=${error.javaClass.simpleName}",
              )
              null
            }
    Log.i(
        ANDROID_DIAGNOSTIC_TAG,
        "diagnostics_stage file_structure_end path=${file.name} " +
            "elements=${probe?.structureElementCount() ?: "unavailable"} " +
            "cachedFileStructures=${probe?.cachedFileStructureCount() ?: "unavailable"} " +
            "elapsedMs=${elapsedMillis(beginNanos)}",
    )
    return probe
  }

  private fun item(
      lineIndex: LineIndex,
      range: TextRange,
      code: String,
      message: String,
      severity: DiagnosticSeverity,
      source: String,
  ) = DiagnosticItem(
      message = message,
      code = code,
      range = Range(lineIndex.indexToPosition(range.startOffset), lineIndex.indexToPosition(range.endOffset)),
      source = source,
      severity = severity,
  )

  private fun KaSeverity.toIdeSeverity(): DiagnosticSeverity = when (this) {
    KaSeverity.ERROR -> DiagnosticSeverity.ERROR
    KaSeverity.WARNING -> DiagnosticSeverity.WARNING
    KaSeverity.INFO -> DiagnosticSeverity.INFO
  }
}

private class KotlinAnalysisFileStructureProbe(
    private val structureCache: Any,
    private val fileStructure: Any,
) {
  fun structureElementCount(): String = mapSize(readReflectiveField(fileStructure, "structureElements"))

  fun cachedFileStructureCount(): String = mapSize(readReflectiveField(structureCache, "cache"))

  private fun mapSize(value: Any?): String = runCatching {
    (value as? java.util.Map<*, *>)?.size()?.toString() ?: "unavailable"
  }.getOrDefault("unavailable")
}

private object KotlinAnalysisStallSampler {
  private val lock = Any()
  private var worker: Thread? = null

  @Volatile private var stopRequested = false
  @Volatile private var startNanos = 0L

  fun start(path: String, probe: KotlinAnalysisFileStructureProbe?) {
    stop()
    synchronized(lock) {
      startNanos = System.nanoTime()
      stopRequested = false
      val thread = Thread({ sampleLoop(path, probe) }, "acs-kotlin-diagnostics-sampler")
      thread.isDaemon = true
      worker = thread
      thread.start()
    }
  }

  fun stop() {
    val thread =
        synchronized(lock) {
          stopRequested = true
          val current = worker
          worker = null
          current
        } ?: return
    runCatching { thread.interrupt() }
  }

  private fun sampleLoop(path: String, probe: KotlinAnalysisFileStructureProbe?) {
    while (!stopRequested) {
      try {
        Thread.sleep(SAMPLER_INTERVAL_MS)
      } catch (interrupted: InterruptedException) {
        return
      }
      if (stopRequested) return
      val runtime = Runtime.getRuntime()
      val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
      val heapMaxMb = runtime.maxMemory() / (1024L * 1024L)
      Log.i(
          ANDROID_DIAGNOSTIC_TAG,
          "diagnostics_stall path=$path elapsedMs=${elapsedMillis(startNanos)} " +
              "heapUsedMb=$heapUsedMb heapMaxMb=$heapMaxMb " +
              "structureElements=${probe?.structureElementCount() ?: "unavailable"} " +
              "cachedFileStructures=${probe?.cachedFileStructureCount() ?: "unavailable"} " +
              "declProviders=${KotlinAnalysisStageCounters.declarationProviderCreations.get()} " +
              "pkgProviders=${KotlinAnalysisStageCounters.packageProviderCreations.get()} " +
              "standaloneIndex=${KotlinAnalysisStageCounters.standaloneSourceIndexState()}",
      )
    }
  }
}

private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

private fun invokeReflectiveNoArg(target: Any?, name: String): Any? = runCatching {
  target?.javaClass?.methods
      ?.firstOrNull { method -> method.name == name && method.parameterCount == 0 }
      ?.invoke(target)
}.getOrNull()

private fun readReflectiveField(target: Any?, name: String): Any? = runCatching {
  var type: Class<*>? = target?.javaClass
  while (type != null) {
    val field = type.declaredFields.firstOrNull { it.name == name }
    if (field != null) {
      field.isAccessible = true
      return@runCatching field.get(target)
    }
    type = type.superclass
  }
  null
}.getOrNull()