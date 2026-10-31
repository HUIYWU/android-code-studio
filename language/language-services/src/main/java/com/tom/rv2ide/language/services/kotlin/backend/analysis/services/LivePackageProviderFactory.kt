package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.projects.models.ProjectInputSnapshotFactory
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinCompositePackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderBase
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderMerger
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageProviderFactory
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile

internal class LivePackageProviderFactory : KotlinPackageProviderFactory {
  private lateinit var project: Project
  private lateinit var metadataIndex: KotlinSourceMetadataIndex
  private var standaloneFactory: KotlinStandalonePackageProviderFactory? = null

  fun setup(
      project: MockProject,
      metadataIndex: KotlinSourceMetadataIndex,
      librarySourceFiles: Collection<KtFile>,
  ) {
    this.project = project
    this.metadataIndex = metadataIndex
    if (ProjectInputSnapshotFactory.LIBRARY_SOURCE_ATTACHMENTS_ENABLED) {
      standaloneFactory = KotlinStandalonePackageProviderFactory(project, librarySourceFiles)
    }
  }

  internal fun createLiveProvider(searchScope: GlobalSearchScope): LivePackageProvider =
      LivePackageProvider(project, searchScope, metadataIndex)

  override fun createPackageProvider(searchScope: GlobalSearchScope): KotlinPackageProvider {
    val live = createLiveProvider(searchScope)
    val standalone = standaloneFactory?.createPackageProvider(searchScope) ?: return live
    return org.jetbrains.kotlin.analysis.api.platform.packages.KotlinCompositePackageProvider.create(listOf(live, standalone))
  }
}

internal class LivePackageProvider(
    project: Project,
    searchScope: GlobalSearchScope,
    private val metadataIndex: KotlinSourceMetadataIndex,
) : KotlinPackageProviderBase(project, searchScope) {
  override fun doesKotlinOnlyPackageExist(packageFqName: FqName): Boolean =
      packageFqName.isRoot || metadataIndex.packageExists(packageFqName.asString())

  override fun getKotlinOnlySubpackageNames(packageFqName: FqName): Set<Name> =
      metadataIndex.subpackageNames(packageFqName.asString()).map(Name::identifier).toSet()
}

internal class LivePackageProviderMerger(
    private val factory: LivePackageProviderFactory,
) : KotlinPackageProviderMerger {
  override fun merge(providers: List<KotlinPackageProvider>): KotlinPackageProvider =
      providers.mergeSpecificProviders<_, LivePackageProvider>(KotlinCompositePackageProvider.factory) { targets ->
        val scope = GlobalSearchScope.union(targets.map { it.searchScope })
        factory.createLiveProvider(scope)
      }
}