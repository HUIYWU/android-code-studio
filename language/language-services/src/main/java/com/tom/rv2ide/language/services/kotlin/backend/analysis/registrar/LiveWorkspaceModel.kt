package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisModuleContext
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisProjectContext
import com.tom.rv2ide.projects.models.ProjectBinaryRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.kotlin.com.intellij.java.workspace.entities.JavaModuleSettingsEntity
import org.jetbrains.kotlin.com.intellij.java.workspace.entities.JavaSourceRootPropertiesEntity
import org.jetbrains.kotlin.com.intellij.platform.backend.workspace.WorkspaceModel
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.ContentRootEntity
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.DependencyScope
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryDependency
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryEntity
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryId
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryRoot
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryRootTypeId
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryTableId
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.LibraryTypeId
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.ModuleDependency
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.ModuleDependencyItem
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.ModuleEntity
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.ModuleId
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.ModuleSourceDependency
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.ModuleTypeId
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.SourceRootEntity
import org.jetbrains.kotlin.com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.EntityChange
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.EntitySource
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.ImmutableEntityStorage
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.MutableEntityStorage
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.VersionedStorageChange
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.WorkspaceEntity
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentationApi
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.instrumentation.MutableEntityStorageInstrumentation
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.impl.url.VirtualFileUrlManagerImpl
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.url.VirtualFileUrl
import org.jetbrains.kotlin.com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import java.nio.file.Files
import java.nio.file.Path

// TODO(ACS-ANALYSIS-EXPERIMENT): Validate this entity bridge against the full Java collector before removing the experimental marker.
@OptIn(EntityStorageInstrumentationApi::class)
internal class LiveWorkspaceModel(
    private val projectContext: KotlinAnalysisProjectContext,
) : WorkspaceModel {
  private val urlManager = VirtualFileUrlManagerImpl(true)
  private val events = MutableSharedFlow<VersionedStorageChange>(extraBufferCapacity = 8)
  private val entitySource = LiveEntitySource

  @Volatile
  private var snapshot: ImmutableEntityStorage = buildSnapshot()

  override val currentSnapshot: ImmutableEntityStorage
    get() = snapshot

  override fun getVirtualFileUrlManager(): VirtualFileUrlManager = urlManager

  override val eventLog: Flow<VersionedStorageChange>
    get() = events.asSharedFlow()

  override fun updateProjectModel(
      description: String,
      updater: (MutableEntityStorage) -> Unit,
  ) {
    synchronized(this) {
      val before = snapshot
      val mutable = MutableEntityStorage.from(before)
      updater(mutable)
      val changes = (mutable as MutableEntityStorageInstrumentation).collectChanges()
      val after = mutable.toSnapshot()
      snapshot = after
      events.tryEmit(LiveStorageChange(before, after, changes))
    }
  }

  override suspend fun update(
      description: String,
      updater: (MutableEntityStorage) -> Unit,
  ) {
    updateProjectModel(description, updater)
  }

  private fun buildSnapshot(): ImmutableEntityStorage {
    val storage = MutableEntityStorage.create()
    val libraryDependencies = linkedMapOf<String, MutableList<String>>()
    val libraries = linkedMapOf<String, MutableList<LibraryRoot>>()
    val modulePaths = projectContext.modules.mapTo(linkedSetOf()) { it.modulePath }

    projectContext.modules.forEach { context ->
      val dependencies = libraryDependencies.getOrPut(context.modulePath) { mutableListOf() }
      context.binaryRootContexts
          .filter { it.role == ProjectBinaryRole.EXTERNAL_BINARY }
          .forEach { binary ->
            val libraryName = externalLibraryName(binary.path)
            dependencies += libraryName
            libraries.getOrPut(libraryName) { mutableListOf() }
                .add(LibraryRoot(rootUrl(binary.path), LibraryRootTypeId.COMPILED, LibraryRoot.InclusionOptions.ROOT_ITSELF))
          }
      context.libraryAttachments.forEach { attachment ->
        val libraryName = attachment.libraryIdentity
        dependencies += libraryName
        val roots = libraries.getOrPut(libraryName) { mutableListOf() }
        attachment.binaryPaths.forEach { path ->
          roots += LibraryRoot(rootUrl(path), LibraryRootTypeId.COMPILED, LibraryRoot.InclusionOptions.ROOT_ITSELF)
        }
        attachment.sourcePaths.forEach { path ->
          roots += LibraryRoot(rootUrl(path), LibraryRootTypeId.SOURCES, LibraryRoot.InclusionOptions.ROOT_ITSELF)
        }
      }
    }

    projectContext.modules.forEach { context ->
      val dependencies = buildList<ModuleDependencyItem> {
        add(ModuleSourceDependency)
        context.compileModulePaths
            .filter(modulePaths::contains)
            .forEach { modulePath ->
              add(ModuleDependency(ModuleId(modulePath), true, DependencyScope.COMPILE, false))
            }
        libraryDependencies[context.modulePath].orEmpty().distinct().forEach { libraryName ->
          add(LibraryDependency(LibraryId(libraryName, LibraryTableId.ProjectLibraryTableId), true, DependencyScope.COMPILE))
        }
      }
      val module = ModuleEntity(context.modulePath, dependencies, entitySource)
      module.type = ModuleTypeId("JAVA_MODULE")
      module.facets = emptyList()

      val contentRoot = ContentRootEntity(rootUrl(context.projectDirectory), emptyList(), entitySource)
      contentRoot.module = module
      contentRoot.excludedUrls = emptyList()
      val sourceRoots = sourceRootBuilders(context, contentRoot)
      contentRoot.sourceRoots = sourceRoots
      module.contentRoots = listOf(contentRoot)

      val settings = JavaModuleSettingsEntity(
          false,
          false,
          entitySource,
      )
      settings.module = module
      context.moduleOutputRoots.firstOrNull()?.let { output ->
        settings.compilerOutput = urlManager.fromPath(normalizedPath(output))
      }
      storage.addEntity(module)
    }

    libraries.forEach { (name, roots) ->
      val library = LibraryEntity(
          name,
          LibraryTableId.ProjectLibraryTableId,
          roots.distinct(),
          entitySource,
      )
      library.typeId = LibraryTypeId("java")
      library.excludedRoots = emptyList()
      storage.addEntity(library)
    }
    return storage.toSnapshot()
  }

  private fun sourceRootBuilders(
      context: KotlinAnalysisModuleContext,
      contentRoot: ContentRootEntity.Builder,
  ): List<SourceRootEntity.Builder> {
    val generatedRoots = context.generatedSourceRoots.toSet()
    return (context.javaSourceRoots + context.generatedSourceRoots)
        .distinct()
        .map { path ->
          val sourceRoot = SourceRootEntity(rootUrl(path), SourceRootTypeId("java-source"), entitySource)
          val javaProperties = JavaSourceRootPropertiesEntity(path in generatedRoots, "", entitySource)
          javaProperties.sourceRoot = sourceRoot
          sourceRoot.contentRoot = contentRoot
          sourceRoot
        }
  }

  private fun rootUrl(path: Path): VirtualFileUrl {
    val normalized = normalizedPath(path)
    return if (Files.isDirectory(path)) {
      urlManager.fromPath(normalized)
    } else {
      urlManager.getOrCreateFromUrl("jar://$normalized!/")
    }
  }

  private fun normalizedPath(path: Path): String =
      path.toAbsolutePath().normalize().toString().replace('\\', '/')

  private fun externalLibraryName(path: Path): String = "acs-external:${normalizedPath(path)}"

  private object LiveEntitySource : EntitySource

  private class LiveStorageChange(
      override val storageBefore: ImmutableEntityStorage,
      override val storageAfter: ImmutableEntityStorage,
      private val changes: Map<Class<*>, List<EntityChange<*>>>,
  ) : VersionedStorageChange {
    override fun <T : WorkspaceEntity> getChanges(entityClass: Class<T>): List<EntityChange<T>> {
      @Suppress("UNCHECKED_CAST")
      return changes[entityClass] as? List<EntityChange<T>> ?: emptyList()
    }
  }
}
