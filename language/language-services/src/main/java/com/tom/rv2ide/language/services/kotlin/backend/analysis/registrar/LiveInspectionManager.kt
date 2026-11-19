package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import org.jetbrains.kotlin.com.intellij.codeInspection.GlobalInspectionContext
import org.jetbrains.kotlin.com.intellij.codeInspection.InspectionManagerBase
import org.jetbrains.kotlin.com.intellij.codeInspection.ex.GlobalInspectionContextBase
import org.jetbrains.kotlin.com.intellij.openapi.project.Project

internal class LiveInspectionManager(project: Project) : InspectionManagerBase(project) {
  override fun createNewGlobalContext(): GlobalInspectionContext = GlobalInspectionContextBase(getProject())

  override fun createNewGlobalContext(reuse: Boolean): GlobalInspectionContext = GlobalInspectionContextBase(getProject())
}
