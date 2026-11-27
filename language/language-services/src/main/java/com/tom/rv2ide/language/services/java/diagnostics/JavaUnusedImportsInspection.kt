package com.tom.rv2ide.language.services.java.diagnostics

import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionManager
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptionsProcessor
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemDescriptor
import org.jetbrains.kotlin.com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.com.intellij.codeInspection.unusedImport.UnusedImportInspection
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile

internal object JavaUnusedImportsInspection {
  fun inspect(
      file: PsiJavaFile,
      cancelChecker: ICancelChecker,
      consume: (UnusedImportInspection, ProblemDescriptor) -> Unit,
  ) {
    cancelChecker.abortIfCancelled()
    if (file.importList?.allImportStatements.isNullOrEmpty()) return
    val manager = InspectionManager.getInstance(file.project)
    val inspection = UnusedImportInspection()
    val holder = ProblemsHolder(manager, file, true)
    val context = manager.createNewGlobalContext()
    try {
      inspection.checkFile(file, manager, holder, context, object : ProblemDescriptionsProcessor {})
      cancelChecker.abortIfCancelled()
      holder.results.forEach { descriptor ->
        cancelChecker.abortIfCancelled()
        consume(inspection, descriptor)
      }
    } finally {
      context.cleanup()
    }
  }
}
