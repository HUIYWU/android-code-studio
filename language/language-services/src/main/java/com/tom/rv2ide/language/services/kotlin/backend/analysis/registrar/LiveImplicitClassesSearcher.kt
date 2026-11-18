package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.psi.PsiImplicitClass
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ImplicitClassSearch
import org.jetbrains.kotlin.com.intellij.psi.util.JavaImplicitClassUtil
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.util.QueryExecutor

// TODO(ACS-ANALYSIS-EXPERIMENT): Validate implicit-class queries against live PSI and project scopes on device.
internal class LiveImplicitClassesSearcher : QueryExecutor<PsiImplicitClass, ImplicitClassSearch.Parameters> {
  override fun execute(
      queryParameters: ImplicitClassSearch.Parameters,
      consumer: Processor<in PsiImplicitClass>,
  ): Boolean = ApplicationManager.getApplication().runReadAction(Computable {
    val project = queryParameters.project
    project.analysisRead {
      val startedAt = System.nanoTime()
      ProgressManager.checkCanceled()
      val files = requireNotNull(project.getService(LiveProjectFiles::class.java))
      val psiManager = PsiManager.getInstance(project)
      var candidates = 0
      var matches = 0
      val completed = files.processJavaFilesWithJvmName(queryParameters.scope, queryParameters.name, Processor { file ->
        ProgressManager.checkCanceled()
        if (file.isDirectory || file.fileType != JavaFileType.INSTANCE) return@Processor true
        candidates++
        val psiFile = requireNotNull(psiManager.findFile(file)) { "Analysis Java candidate PSI is missing: ${file.path}" }
        val implicitClass = JavaImplicitClassUtil.getImplicitClassFor(psiFile) ?: return@Processor true
        matches++
        consumer.process(implicitClass)
      })
      KlsLogs.info(
          "Java implicit class search: project={}, name={}, candidates={}, matches={}, completed={}, durationUs={}",
          System.identityHashCode(project),
          queryParameters.name,
          candidates,
          matches,
          completed,
          (System.nanoTime() - startedAt) / 1_000L,
      )
      completed
    }
  })
}
