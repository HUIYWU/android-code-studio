package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val analysisLockKey = Key.create<ReentrantReadWriteLock>("acs.kotlin.analysis.project.lock")

internal fun Project.installAnalysisProjectLock() {
  putUserData(analysisLockKey, ReentrantReadWriteLock(true))
}

internal fun <T> Project.analysisRead(action: () -> T): T {
  val lock = checkNotNull(getUserData(analysisLockKey))
  return lock.read(action)
}

internal fun <T> Project.analysisWrite(action: () -> T): T {
  val lock = checkNotNull(getUserData(analysisLockKey))
  return lock.write(action)
}
