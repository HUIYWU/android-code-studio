package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisPriority
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisScheduler
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Path

internal sealed interface LiveKtFile {
  val path: Path
  val isStale: Boolean

  fun <T> read(action: (KtFile) -> T): T

  fun <T> analyzing(
      priority: AnalysisPriority,
      cancelChecker: ICancelChecker,
      useSite: KtElement? = null,
      action: KaSession.(KtFile) -> T,
  ): T

  fun <T> analyzingVariant(
      name: String,
      text: String,
      priority: AnalysisPriority,
      cancelChecker: ICancelChecker,
      action: KaSession.(KtFile) -> T,
  ): T
}

internal class PinnedLiveKtFile(
    override val path: Path,
    private val index: LiveSourceFileIndex,
    private val pinnedFile: KtFile,
    private val pinnedIdentity: LiveFileIdentity,
) : LiveKtFile {
  override val isStale: Boolean
    get() = !index.isCurrent(path, pinnedIdentity, pinnedFile)

  override fun <T> read(action: (KtFile) -> T): T =
      ApplicationManager.getApplication().runReadAction(Computable {
        index.project.analysisRead { action(pinnedFile) }
      })

  override fun <T> analyzing(
      priority: AnalysisPriority,
      cancelChecker: ICancelChecker,
      useSite: KtElement?,
      action: KaSession.(KtFile) -> T,
  ): T =
      AnalysisScheduler.withLock(priority, cancelChecker) {
        ApplicationManager.getApplication().runReadAction(Computable {
          index.project.analysisRead {
            analyze(useSite ?: pinnedFile) { action(pinnedFile) }
          }
        })
      }

  override fun <T> analyzingVariant(
      name: String,
      text: String,
      priority: AnalysisPriority,
      cancelChecker: ICancelChecker,
      action: KaSession.(KtFile) -> T,
  ): T {
    val variant =
        index.project.analysisRead {
          KtPsiFactory(index.project, markGenerated = true, eventSystemEnabled = false)
              .createFile(name, text)
              .apply {
                originalFile = pinnedFile
                originalKtFile = pinnedFile
              }
        }
    return AnalysisScheduler.withLock(priority, cancelChecker) {
      ApplicationManager.getApplication().runReadAction(Computable {
        index.project.analysisRead {
          analyze(variant) { action(variant) }
        }
      })
    }
  }
}
