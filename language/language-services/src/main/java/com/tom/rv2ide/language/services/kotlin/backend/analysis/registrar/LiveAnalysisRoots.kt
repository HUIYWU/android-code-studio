package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import org.jetbrains.kotlin.com.intellij.ide.highlighter.ArchiveFileType
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.util.io.URLUtil.JAR_SEPARATOR
import java.nio.file.Path

// TODO(ACS-ANALYSIS-EXPERIMENT): Validate directory and archive root identity with the Java collector on device.
internal fun analysisRootVirtualFile(path: Path): VirtualFile? {
  val normalized = path.toAbsolutePath().normalize()
  val local = VirtualFileManager.getInstance().findFileByNioPath(normalized) ?: return null
  if (local.isDirectory) return local
  require(local.fileType is ArchiveFileType) { "Analysis root is not a directory or archive: ${local.url}" }
  return StandardFileSystems.jar().findFileByPath(normalized.toString().replace('\\', '/') + JAR_SEPARATOR)
}
