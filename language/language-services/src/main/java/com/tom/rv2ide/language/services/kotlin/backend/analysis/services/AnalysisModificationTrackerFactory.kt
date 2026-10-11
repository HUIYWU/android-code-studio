package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerByEventFactoryBase
import org.jetbrains.kotlin.com.intellij.openapi.project.Project

internal class AnalysisModificationTrackerFactory(project: Project) : KotlinModificationTrackerByEventFactoryBase(project)