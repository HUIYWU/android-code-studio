package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReference
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.util.QueryExecutor

internal class LiveJavaReferencesSearcher : QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
  override fun execute(
      parameters: ReferencesSearch.SearchParameters,
      consumer: Processor<in PsiReference>,
  ): Boolean = ApplicationManager.getApplication().runReadAction(Computable {
    val target = parameters.elementToSearch as? PsiMethod ?: return@Computable true
    val scope = parameters.effectiveSearchScope as? GlobalSearchScope ?: return@Computable true
    val project = parameters.project
    project.analysisRead {
      val psiManager = PsiManager.getInstance(project)
      var completed = true
      files(project).process(scope, null, Processor { virtualFile ->
        ProgressManager.checkCanceled()
        if (virtualFile.isDirectory || virtualFile.fileType != JavaFileType.INSTANCE) return@Processor true
        val file = psiManager.findFile(virtualFile) as? PsiJavaFile ?: return@Processor true
        file.accept(object : JavaRecursiveElementWalkingVisitor() {
          override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
            if (!completed) return
            ProgressManager.checkCanceled()
            if (target.isEquivalentTo(expression.resolveMethod())) {
              completed = consumer.process(expression.methodExpression)
              if (!completed) return
            }
            super.visitMethodCallExpression(expression)
          }

          override fun visitNewExpression(expression: PsiNewExpression) {
            if (!completed) return
            ProgressManager.checkCanceled()
            if (target.isEquivalentTo(expression.resolveConstructor())) {
              val reference = expression.classReference ?: expression.classOrAnonymousClassReference
              if (reference != null) {
                completed = consumer.process(reference)
                if (!completed) return
              }
            }
            super.visitNewExpression(expression)
          }

          override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
            if (!completed) return
            ProgressManager.checkCanceled()
            if (target.isEquivalentTo(expression.resolve())) {
              completed = consumer.process(expression)
              if (!completed) return
            }
            super.visitMethodReferenceExpression(expression)
          }

          override fun visitElement(element: PsiElement) {
            if (!completed) return
            ProgressManager.checkCanceled()
            super.visitElement(element)
          }
        })
        completed
      })
    }
  })

  private fun files(project: Project): LiveProjectFiles =
      checkNotNull(project.getService(LiveProjectFiles::class.java))
}