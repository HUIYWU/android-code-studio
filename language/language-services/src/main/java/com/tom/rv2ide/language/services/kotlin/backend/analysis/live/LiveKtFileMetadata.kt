package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import java.nio.file.Path

private val liveBackingPathKey = Key.create<Path>("acs.kotlin.live.backing.path")

internal var PsiFile.liveBackingPath: Path?
  get() = getUserData(liveBackingPathKey)
  set(value) {
    putUserData(liveBackingPathKey, value)
  }
