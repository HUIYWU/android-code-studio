package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.CommonClassNames
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.search.PsiSearchScopeUtil
import org.jetbrains.kotlin.com.intellij.psi.search.searches.DirectClassInheritorsSearch
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.util.QueryExecutor

// TODO(ACS-ANALYSIS-EXPERIMENT): Validate sealed-type diagnostics, anonymous inheritors, and scoped query results on device.
internal class LiveJavaDirectInheritorsSearcher : QueryExecutor<PsiClass, DirectClassInheritorsSearch.SearchParameters> {
  override fun execute(
      parameters: DirectClassInheritorsSearch.SearchParameters,
      consumer: Processor<in PsiClass>,
  ): Boolean = ApplicationManager.getApplication().runReadAction(Computable {
    if (!parameters.shouldSearchInLanguage(JavaLanguage.INSTANCE)) return@Computable true
    val baseClass = parameters.classToProcess
    val project = baseClass.project
    project.analysisRead {
      val name = baseClass.name ?: return@analysisRead true
      val startedAt = System.nanoTime()
      ProgressManager.checkCanceled()
      val index = requireNotNull(project.getService(LiveJavaDirectInheritorsIndex::class.java))
      val candidates = index.candidates(name)
      val scope = parameters.scope.intersectWith(baseClass.useScope)
      val cache = HashMap<VirtualFile, List<PsiClass>>()
      var matches = 0
      var completed = true
      for (candidate in candidates) {
        ProgressManager.checkCanceled()
        if (!parameters.includeAnonymous() && candidate.anonymous) continue
        if (scope is GlobalSearchScope && !scope.contains(candidate.file)) continue
        val klass = index.materialize(candidate, cache)
        if (!PsiSearchScopeUtil.isInScope(scope, klass)) continue
        if (parameters.isCheckInheritance) {
          val inheritor = if (baseClass.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) {
            klass.isInterface || klass.superClass?.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT
          } else {
            klass.isInheritor(baseClass, false)
          }
          if (!inheritor) continue
        }
        matches++
        if (!consumer.process(klass)) {
          completed = false
          break
        }
      }
      KlsLogs.info(
          "Java direct inheritors search: project={}, base={}, candidates={}, matches={}, includeAnonymous={}, checkInheritance={}, completed={}, durationUs={}",
          System.identityHashCode(project), baseClass.qualifiedName ?: name, candidates.size, matches,
          parameters.includeAnonymous(), parameters.isCheckInheritance, completed,
          (System.nanoTime() - startedAt) / 1_000L,
      )
      completed
    }
  })
}
