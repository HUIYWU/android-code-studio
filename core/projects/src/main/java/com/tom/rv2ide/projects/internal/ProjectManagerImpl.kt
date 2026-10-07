/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.projects.internal

import androidx.annotation.RestrictTo
import com.android.builder.model.v2.models.ProjectSyncIssues
import com.google.auto.service.AutoService
import com.google.common.collect.ImmutableList
import com.tom.rv2ide.projects.util.RuntimeProbe
import com.tom.rv2ide.eventbus.events.EventReceiver
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import com.tom.rv2ide.eventbus.events.file.FileCreationEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.eventbus.events.project.ProjectInitializedEvent
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.CachingProject
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.tasks.executeAsync
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.models.BuildVariantInfo
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.withStopWatch
import com.tom.rv2ide.utils.GradleFileParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

/**
 * Internal implementation of [IProjectManager].
 *
 * @author Akash Yadav
 */
@AutoService(IProjectManager::class)
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class ProjectManagerImpl : IProjectManager, EventReceiver {

  private var _workspace: WorkspaceImpl? = null
  private var _projectDir: File? = null
  private val modulesGeneratingSources = ConcurrentHashMap.newKeySet<String>()
  private val fullBuildIntents = AtomicInteger(0)
  private var resourceTableRefreshes = ResourceTableRefreshCoordinator()

  var projectInitialized: Boolean = false
  var cachedInitResult: InitializeResult? = null

  override val projectDir: File
    get() = checkNotNull(_projectDir) { "Cannot get project directory. Path has not been set." }

  override val projectSyncIssues: ProjectSyncIssues?
    get() = getWorkspace()?.getProjectSyncIssues()

  override fun getWorkspace(): IWorkspace? {
    return _workspace
  }

  override fun openProject(directory: File) {
    // IMP: Always use canonical path
    this._projectDir = directory.canonicalFile
  }

  override suspend fun setupProject(project: IProject) {
    this._workspace =
        withStopWatch("Transform project proxy") {
          withContext(Dispatchers.IO) {
            WorkspaceModelBuilder.build(projectDir, CachingProject(project))
          }
        }

    val rootProject = this.getWorkspace() ?: return

    // build variants must be updated before the sources and classpaths are indexed
    updateBuildVariants { buildVariants -> _workspace!!.setVariantSelections(buildVariants) }

    log.info(
        "Found {} project sync issues: {}",
        rootProject.getProjectSyncIssues().syncIssues.size,
        rootProject.getProjectSyncIssues().syncIssues,
    )
    RuntimeProbe.mark("ProjectManager.setupProject:modules=${rootProject.getSubProjects().filterIsInstance<ModuleProject>().size}")

    withStopWatch("Setup project") {
      val indexingDispatcher = Dispatchers.Default.limitedParallelism(4)
      val indexerScope = CoroutineScope(indexingDispatcher)
      val modulesFlow = flow {
        rootProject.getSubProjects().filterIsInstance<ModuleProject>().forEach { emit(it) }
      }
      val jobs =
          modulesFlow.map { module ->
            indexerScope.async {
              if (module.isLazyCompositeBuildModule()) {
                log.info("Setup project: defer indexing lazy composite module {}", module.path)
                return@async
              }
              log.info("Setup project: start indexing module {}", module.path)
              module.indexSourcesAndClasspaths()
              if (module is AndroidModule) {
                module.readResources()
              }
              log.info("Setup project: finished indexing module {}", module.path)
            }
          }


      // wait for the indexing to finish
      jobs.toList().awaitAll()
    }
  }

  override fun destroy() {
    log.info("Destroying project manager")

    this._workspace?.setVariantSelections(emptyMap())
    this._workspace = null

    this._projectDir = null
    this.cachedInitResult = null
    this.projectInitialized = false
    this.modulesGeneratingSources.clear()
    this.fullBuildIntents.set(0)
    resourceTableRefreshes.close()
    resourceTableRefreshes = ResourceTableRefreshCoordinator()
  }
  private fun generateSourcesForModule(
      module: AndroidModule,
      builder: BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE),
  ) {
    if (builder == null || !builder.isToolingServerStarted() || builder.isBuildInProgress) {
      return
    }
    if (!modulesGeneratingSources.add(module.path)) {
      log.debug("Source generation is already running for module '{}'", module.path)
      return
    }

    val variant = module.getSelectedVariant()
    val artifact = variant?.mainArtifact
    val tasks =
        listOf(artifact?.resGenTaskName, artifact?.sourceGenTaskName)
            .mapNotNull { taskName ->
              taskName
                  ?.takeIf(String::isNotBlank)
                  ?.let { name -> module.tasks.firstOrNull { it.name == name }?.path }
            }
            .distinct()

    if (tasks.isEmpty()) {
      modulesGeneratingSources.remove(module.path)
      log.debug("No exact source generation tasks found for module '{}'", module.path)
      return
    }

    log.info("Generating Android sources for changed module '{}': {}", module.path, tasks)
    builder.executeTasks(*tasks.toTypedArray()).whenComplete { result, error ->
      modulesGeneratingSources.remove(module.path)
      if (result == null || !result.isSuccessful || error != null) {
        log.warn("Source generation failed for module '{}': {}", module.path, error ?: result)
      } else {
        resourceTableRefreshes.schedule(module, immediate = true)
        log.info("Android source generation completed for changed module '{}'", module.path)
      }
    }
  }

  fun notifyProjectUpdate() {

    executeAsync {
      getWorkspace()?.apply {
        getSubProjects().forEach { subproject ->
          if (subproject is ModuleProject) {
            subproject.indexSources()
          }
        }
      }

      val event = ProjectInitializedEvent()
      event.put(IWorkspace::class.java, getWorkspace())
      EventBus.getDefault().post(event)
    }
  }

  private fun updateBuildVariants(onUpdated: (Map<String, BuildVariantInfo>) -> Unit = {}) {
    val rootProject =
        checkNotNull(this.getWorkspace()) {
          "Cannot update build variants. Root project model is null."
        }

    val buildVariants = mutableMapOf<String, BuildVariantInfo>()
    rootProject.getSubProjects().forEach { subproject ->
      if (subproject is AndroidModule) {

        val variantNames =
            ImmutableList.builder<String>()
                .addAll(subproject.variants.map { variant -> variant.name })
                .build()

        val variantName = subproject.configuredVariant?.name ?: IAndroidProject.DEFAULT_VARIANT

        val moduleDir = File(projectDir, subproject.path.replace(":", File.separator))
        val gradleInfo = GradleFileParser.parseModuleBuildGradle(moduleDir)

        buildVariants[subproject.path] =
            BuildVariantInfo(
                projectPath = subproject.path,
                buildVariants = variantNames,
                selectedVariant = variantName,
                versionName = gradleInfo?.versionName,
                versionCode = gradleInfo?.versionCode,
                minSdk = gradleInfo?.minSdk,
                targetSdk = gradleInfo?.targetSdk,
                compileSdk = gradleInfo?.compileSdk
            )
      }
    }

    onUpdated(buildVariants)
  }
  private fun refreshResourcesIfNecessary(event: FileEvent) {
    val workspace = getWorkspace() ?: return
    val file = event.file
    if (!workspace.isAndroidResource(file)) {
      return
    }

    // Resource edits must not trigger a workspace-wide Gradle warm-up. AGP produces generated
    // binding/navigation sources during initialization or an explicit build; editor file events
    // only need the owning module's resource table refreshed for completion and diagnostics.
    (workspace.findModuleForFile(file, false) as? AndroidModule)?.let { module ->
      resourceTableRefreshes.schedule(module, immediate = true)
    }
  }


  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentChanged(event: DocumentChangeEvent) {
    scheduleActiveValuesRefresh(event.changedFile)
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentClosed(event: DocumentCloseEvent) {
    scheduleActiveValuesRefresh(event.closedFile, immediate = true)
  }

  private fun scheduleActiveValuesRefresh(file: java.nio.file.Path, immediate: Boolean = false) {
    if (file.extension != "xml") return
    val module = getWorkspace()?.findModuleForFile(file, false) as? AndroidModule ?: return
    val isValuesResource =
        module.mainSourceSet?.sourceProvider?.resDirectories?.any { resDirectory ->
          file.normalize().startsWith(resDirectory.toPath().normalize().resolve("values"))
        } ?: false
    if (isValuesResource) {
      resourceTableRefreshes.schedule(module, immediate)
    }
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onFileSaved(event: DocumentSaveEvent) {
    val file = event.file
    if (file.isDirectory()) return

    if (file.extension == "kt" || file.extension == "kts") {
      getWorkspace()?.findModuleForFile(file, false)?.bumpSourceIndexVersion()
      return
    }
    if (file.extension != "xml") return

    val module = getWorkspace()?.findModuleForFile(file, false) as? AndroidModule ?: return
    val sourceProvider = module.mainSourceSet?.sourceProvider
    val isResource =
        sourceProvider?.resDirectories?.any { resourceDir ->
          file.normalize().startsWith(resourceDir.toPath().normalize())
        } ?: false
    val isManifest =
        sourceProvider?.manifestFile?.let { manifest ->
          manifest.exists() && file.normalize() == manifest.toPath().normalize()
        } ?: false
    if (!isResource && !isManifest) return

    resourceTableRefreshes.schedule(module, immediate = true)
    if (!event.buildWillFollow && !hasFullBuildIntent()) {
      generateSourcesForModule(module)
    } else {
      log.debug(
          "Skipping standalone source generation for '{}' because a full build will follow",
          module.path,
      )
    }
  }

  /** Marks a caller-owned full Gradle build lifecycle that subsumes resource generation tasks. */
  fun beginFullBuildIntent() {
    fullBuildIntents.incrementAndGet()
  }

  /** Ends a lifecycle started by [beginFullBuildIntent]. */
  fun endFullBuildIntent() {
    fullBuildIntents.updateAndGet { count -> if (count > 0) count - 1 else 0 }
  }

  private fun hasFullBuildIntent(): Boolean = fullBuildIntents.get() > 0

  override fun notifyFileCreated(file: File) {
    onFileCreated(FileCreationEvent(file))
  }

  override fun notifyFileDeleted(file: File) {
    onFileDeleted(FileDeletionEvent(file))
  }

  override fun notifyFileRenamed(from: File, to: File) {
    onFileRenamed(FileRenameEvent(from, to))
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileCreated(event: FileCreationEvent) {
    refreshResourcesIfNecessary(event)

    if (DocumentUtils.isKotlinFile(event.file.toPath())) {
      getWorkspace()?.findModuleForFile(event.file, false)?.bumpSourceIndexVersion()
    }

    if (DocumentUtils.isJavaFile(event.file.toPath())) {
      getWorkspace()?.findModuleForFile(event.file, false)?.let {
        val sourceRoot = it.findSourceRoot(event.file) ?: return@let

        // add the source node entry
        it.compileJavaSourceClasses.append(event.file.toPath(), sourceRoot)
        it.bumpSourceIndexVersion()
      }
    }
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileDeleted(event: FileDeletionEvent) {
    refreshResourcesIfNecessary(event)

    // Remove the source node entry
    // Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
    // well. As the file is already deleted, it will always return false
    if (event.file.extension == "kt" || event.file.extension == "kts") {
      getWorkspace()?.findModuleForFile(event.file, false)?.bumpSourceIndexVersion()
    }

    if (event.file.extension == "java") {
      getWorkspace()
          ?.findModuleForFile(event.file, false)
          ?.let { module ->
            module.compileJavaSourceClasses
                .findSource(event.file.toPath())
                ?.let {
                  it.parent?.removeChild(it)
                  module.bumpSourceIndexVersion()
                }
          }
    }
  }

  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.BACKGROUND)
  fun onFileRenamed(event: FileRenameEvent) {
    refreshResourcesIfNecessary(event)

    // Do not check for Java file DocumentUtils.isJavaFile(...) as it checks for file existence as
    // well. As the file is already renamed to another filename, it will always return false
    if (event.file.extension == "kt" || event.file.extension == "kts") {
      getWorkspace()?.findModuleForFile(event.file, false)?.bumpSourceIndexVersion()
    }

    if (event.file.extension == "java") {
      // remove the source node entry
      getWorkspace()
          ?.findModuleForFile(event.file, false)
          ?.let { module ->
            module.compileJavaSourceClasses
                .findSource(event.file.toPath())
                ?.let {
                  it.parent?.removeChild(it)
                  module.bumpSourceIndexVersion()
                }
          }
    }

    if (DocumentUtils.isKotlinFile(event.newFile.toPath())) {
      getWorkspace()?.findModuleForFile(event.newFile, false)?.bumpSourceIndexVersion()
    }

    if (DocumentUtils.isJavaFile(event.newFile.toPath())) {
      getWorkspace()?.findModuleForFile(event.newFile, false)?.let {
        val sourceRoot = it.findSourceRoot(event.newFile) ?: return@let
        // add the new source node entry
        it.compileJavaSourceClasses.append(event.newFile.toPath(), sourceRoot)
        it.bumpSourceIndexVersion()
      }
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(ProjectManagerImpl::class.java)

    @JvmStatic
    fun getInstance(): ProjectManagerImpl = IProjectManager.getInstance() as ProjectManagerImpl
  }
}
