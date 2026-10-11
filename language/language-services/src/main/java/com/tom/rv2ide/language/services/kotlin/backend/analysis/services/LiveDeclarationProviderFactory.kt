package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinCompositeDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.declarations.createDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import java.nio.file.Path

internal class LiveDeclarationProviderFactory : KotlinDeclarationProviderFactory {
  private lateinit var project: Project
  private lateinit var liveIndex: LiveSourceFileIndex
  private lateinit var registry: LiveSourceFileRegistry
  private lateinit var metadataIndex: KotlinSourceMetadataIndex

  fun setup(
      project: MockProject,
      liveIndex: LiveSourceFileIndex,
      registry: LiveSourceFileRegistry,
      metadataIndex: KotlinSourceMetadataIndex,
  ) {
    this.project = project
    this.liveIndex = liveIndex
    this.registry = registry
    this.metadataIndex = metadataIndex
  }

  override fun createDeclarationProvider(
      scope: GlobalSearchScope,
      contextualModule: KaModule?,
  ): KotlinDeclarationProvider = LiveDeclarationProvider(scope, project, liveIndex, registry, metadataIndex)
}

internal class LiveDeclarationProviderMerger(private val project: Project) : KotlinDeclarationProviderMerger {
  override fun merge(providers: List<KotlinDeclarationProvider>): KotlinDeclarationProvider =
      providers.mergeSpecificProviders<_, LiveDeclarationProvider>(KotlinCompositeDeclarationProvider.factory) { targets ->
        val scope = GlobalSearchScope.union(targets.map { it.scope })
        project.createDeclarationProvider(scope, contextualModule = null).also {
          check(it is LiveDeclarationProvider)
        }
      }
}

internal abstract class AbstractLiveDeclarationProvider(
    protected val project: Project,
) : KotlinDeclarationProvider {
  protected abstract fun filesForPackage(fqName: FqName): Sequence<KtFile>

  override fun findFilesForFacade(facadeFqName: FqName): Collection<KtFile> {
    if (facadeFqName.shortNameOrSpecial().isSpecial) return emptyList()
    return findFilesForFacadeByPackage(facadeFqName.parent()).filter { it.javaFileFacadeFqName == facadeFqName }
  }

  override fun findInternalFilesForFacade(facadeFqName: FqName): Collection<KtFile> = emptyList()

  override fun findFilesForFacadeByPackage(packageFqName: FqName): Collection<KtFile> = filesForPackage(packageFqName).toList()

  override fun findFilesForScript(scriptFqName: FqName): Collection<KtScript> =
      filesForPackage(scriptFqName).mapNotNull { it.script }.toList()

  override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> =
      filesForPackage(classId.packageFqName).flatMap { file ->
        project.analysisRead {
          PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java).filter { it.getClassId() == classId }
        }
      }.toList()

  override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> =
      filesForPackage(classId.packageFqName).flatMap { file ->
        project.analysisRead {
          PsiTreeUtil.collectElementsOfType(file, KtTypeAlias::class.java).filter { it.getClassId() == classId }
        }
      }.toList()

  override fun getClassLikeDeclarationByClassId(classId: ClassId): KtClassLikeDeclaration? =
      getAllClassesByClassId(classId).firstOrNull() ?: getAllTypeAliasesByClassId(classId).firstOrNull()

  override fun getTopLevelCallableFiles(callableId: CallableId): Collection<KtFile> = buildSet {
    getTopLevelProperties(callableId).mapTo(this) { it.containingKtFile }
    getTopLevelFunctions(callableId).mapTo(this) { it.containingKtFile }
  }

  override fun getTopLevelFunctions(callableId: CallableId): Collection<KtNamedFunction> =
      filesForPackage(callableId.packageName).flatMap { file ->
        project.analysisRead {
          PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java)
              .filter { it.isTopLevel && it.nameAsName == callableId.callableName }
        }
      }.toList()

  override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> =
      filesForPackage(packageFqName).flatMap { file ->
        project.analysisRead {
          PsiTreeUtil.collectElementsOfType(file, KtClassLikeDeclaration::class.java)
              .filter { it.isTopLevelKtOrJavaMember() }
              .mapNotNull { it.nameAsName }
        }
      }.toSet()

  override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> =
      filesForPackage(packageFqName).flatMap { file ->
        project.analysisRead {
          PsiTreeUtil.collectElementsOfType(file, KtCallableDeclaration::class.java)
              .filter { it.isTopLevelKtOrJavaMember() }
              .mapNotNull { it.nameAsName }
        }
      }.toSet()

  override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> =
      filesForPackage(callableId.packageName).flatMap { file ->
        project.analysisRead {
          PsiTreeUtil.collectElementsOfType(file, KtProperty::class.java)
              .filter { it.isTopLevel && it.nameAsName == callableId.callableName }
        }
      }.toList()
}

internal class LiveDeclarationProvider(
    val scope: GlobalSearchScope,
    project: Project,
    private val liveIndex: LiveSourceFileIndex,
    private val registry: LiveSourceFileRegistry,
    private val metadataIndex: KotlinSourceMetadataIndex,
) : AbstractLiveDeclarationProvider(project) {
  override val hasSpecificCallablePackageNamesComputation: Boolean = false
  override val hasSpecificClassifierPackageNamesComputation: Boolean = false

  override fun filesForPackage(fqName: FqName): Sequence<KtFile> =
      metadataIndex.filesForPackage(fqName.asString()).asSequence().mapNotNull { path ->
        val virtualFile = registry.virtualFile(path)
            ?: VirtualFileManager.getInstance().findFileByNioPath(path)
            ?: return@mapNotNull null
        if (virtualFile !in scope) return@mapNotNull null
        liveIndex.getCurrentKtFile(path)
      }
}