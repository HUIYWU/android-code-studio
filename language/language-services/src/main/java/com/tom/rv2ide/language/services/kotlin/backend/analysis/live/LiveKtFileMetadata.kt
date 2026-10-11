package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

private val liveBackingPathKey = Key.create<Path>("acs.kotlin.live.backing.path")

internal var KtFile.liveBackingPath: Path?
  get() = getUserData(liveBackingPathKey)
  set(value) {
    putUserData(liveBackingPathKey, value)
  }
