package com.tom.rv2ide.language.services.kotlin.backend.analysis.environment

import com.tom.rv2ide.language.services.java.interop.JavaPsiFiles
import com.tom.rv2ide.language.services.java.interop.JavaPsiProjectAccess
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.liveBackingPath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager

internal class LiveJavaPsiProjectAccess(
    private val environment: LiveAnalysisEnvironment,
) : JavaPsiProjectAccess {
  override fun sourcePath(file: PsiFile): Path? =
      file.liveBackingPath
          ?: file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path?.let(Paths::get)

  override fun isProjectSource(path: Path): Boolean {
    val normalized = path.normalize()
    return environment.projectContext.modules.any { module ->
      (module.javaSourceRoots + module.generatedSourceRoots).any { root ->
        val normalizedRoot = root.normalize()
        normalized == normalizedRoot || normalized.startsWith(normalizedRoot)
      }
    }
  }
  override fun sourceFilesFor(file: Path): List<Path> {
    val modulePath = environment.modulePathFor(file) ?: return emptyList()
    val visibleModules = (environment.runtimeGraph.dependentClosure(modulePath) + modulePath).toSet()
    val visibleIndexed = visibleModules.flatMap { environment.javaSourceIndex.sourceFiles(it) }
    val active = activeJavaFilesFor(visibleModules)
    return (visibleIndexed + active).map(Path::normalize).distinct()
  }

  private fun activeJavaFilesFor(visibleModules: Set<String>): List<Path> =
      FileManager.getActiveDocumentFiles().filter { path ->
        path.toString().endsWith(".java") && environment.modulePathFor(path) in visibleModules
      }

  override fun <T> withJavaSourceFiles(
      requestFile: Path,
      requestedVersion: Int,
      requestedRevision: Long,
      sourceFiles: Collection<Path>,
      cancelChecker: ICancelChecker,
      action: (JavaPsiFiles) -> T?,
  ): T? {
    val requestPath = requestFile.normalize()
    val initialSnapshot = FileManager.getActiveDocumentSnapshot(requestPath) ?: return null
    if (
        requestedVersion >= 0 && initialSnapshot.version != requestedVersion ||
            requestedRevision >= 0L && initialSnapshot.revision != requestedRevision
    ) {
      return null
    }
    val requestVersion = initialSnapshot.version
    val requestRevision = initialSnapshot.revision
    val candidates = sourceFiles.asSequence()
        .map(Path::normalize)
        .filter { it.toString().endsWith(".java") }
        .plus(sequenceOf(requestPath))
        .distinct()
        .toList()
    val initialStamps = candidates.associateWith { path ->
      documentStamp(path)
    }
    cancelChecker.abortIfCancelled()
    return AnalysisApiGuard.withAccess(cancelChecker) {
      if (!isCurrentDocument(requestPath, requestVersion, requestRevision)) return@withAccess null
      candidates.forEach(environment.javaFiles::refresh)
      ApplicationManager.getApplication().runReadAction(Computable {
        environment.project.analysisRead {
          cancelChecker.abortIfCancelled()
          if (!isCurrentDocument(requestPath, requestVersion, requestRevision)) return@analysisRead null
          val files = object : JavaPsiFiles {
            override fun file(path: Path): PsiJavaFile? =
                environment.javaFiles.currentFile(path.normalize()) ?: diskFile(path.normalize())

            override fun forEach(
                include: (Path, String) -> Boolean,
                action: (Path, PsiJavaFile) -> Unit,
            ): Boolean {
              for (path in candidates) {
                cancelChecker.abortIfCancelled()
                val content = FileManager.getDocumentContents(path)
                if (!include(path, content)) continue
                val psiFile = file(path) ?: continue
                action(path, psiFile)
              }
              return isCurrentDocument(requestPath, requestVersion, requestRevision) &&
                  candidates.all { path -> documentStamp(path) == initialStamps[path] }
            }
          }
          val result = action(files)
          if (
              isCurrentDocument(requestPath, requestVersion, requestRevision) &&
                  candidates.all { path -> documentStamp(path) == initialStamps[path] }
          ) result else null
        }
      })
    }
  }

  private fun diskFile(path: Path): PsiJavaFile? {
    val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return null
    return PsiManager.getInstance(environment.project).findFile(virtualFile) as? PsiJavaFile
  }

  private fun documentStamp(path: Path): Any? =
      FileManager.getActiveDocumentSnapshot(path)?.let { it.version to it.revision }
          ?: runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()

  private fun isCurrentDocument(path: Path, version: Int, revision: Long): Boolean {
    val snapshot = FileManager.getActiveDocumentSnapshot(path) ?: return false
    return snapshot.version == version && snapshot.revision == revision
  }

  override fun <T> withCurrentJavaFile(
      file: Path,
      requestedVersion: Int,
      requestedRevision: Long,
      cancelChecker: ICancelChecker,
      action: (PsiJavaFile) -> T?,
  ): T? {
    val normalized = file.normalize()
    cancelChecker.abortIfCancelled()
    val snapshot = FileManager.getActiveDocumentSnapshot(normalized) ?: return null
    if (
        requestedVersion >= 0 && snapshot.version != requestedVersion ||
            requestedRevision >= 0L && snapshot.revision != requestedRevision
    ) {
      return null
    }
    val version = snapshot.version
    val revision = snapshot.revision
    return AnalysisApiGuard.withAccess(cancelChecker) {
      if (!isCurrentDocument(normalized, version, revision)) return@withAccess null
      environment.javaFiles.refresh(normalized)
      ApplicationManager.getApplication().runReadAction(Computable {
        environment.project.analysisRead {
          cancelChecker.abortIfCancelled()
          if (!isCurrentDocument(normalized, version, revision)) return@analysisRead null
          val current = environment.javaFiles.currentFile(normalized, version, revision)
              ?: return@analysisRead null
          val result = action(current)
          if (isCurrentDocument(normalized, version, revision)) result else null
        }
      })
    }
  }
}
