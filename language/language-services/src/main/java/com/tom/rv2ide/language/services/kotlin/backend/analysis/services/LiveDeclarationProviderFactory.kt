package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceDeclarationKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceDeclarationRecord
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
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
    val scope: GlobalSearchScope,
    protected val liveIndex: LiveSourceFileIndex,
    protected val registry: LiveSourceFileRegistry,
    protected val metadataIndex: KotlinSourceMetadataIndex,
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

  override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> {
    val candidates = metadataIndex.classLikeDeclarations(classId)
        .filter { it.kind == KotlinSourceDeclarationKind.CLASS_LIKE }
    val result = candidates.mapNotNull { record -> materialize(record, classId) as? KtClassOrObject }
    logDeclarationQuery("class-like", classId, candidates.size, result.size)
    return result
  }

  override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> {
    val candidates = metadataIndex.typeAliasDeclarations(classId)
        .filter { it.kind == KotlinSourceDeclarationKind.TYPE_ALIAS }
    val result = candidates.mapNotNull { record -> materialize(record, classId) as? KtTypeAlias }
    logDeclarationQuery("type-alias", classId, candidates.size, result.size)
    return result
  }

  override fun getClassLikeDeclarationByClassId(classId: ClassId): KtClassLikeDeclaration? =
      getAllClassesByClassId(classId).firstOrNull() ?: getAllTypeAliasesByClassId(classId).firstOrNull()

  override fun getTopLevelCallableFiles(callableId: CallableId): Collection<KtFile> = buildSet {
    getTopLevelProperties(callableId).mapTo(this) { it.containingKtFile }
    getTopLevelFunctions(callableId).mapTo(this) { it.containingKtFile }
  }

  override fun getTopLevelFunctions(callableId: CallableId): Collection<KtNamedFunction> {
    val candidates = metadataIndex.topLevelCallableDeclarations(callableId)
        .filter { it.kind == KotlinSourceDeclarationKind.TOP_LEVEL_FUNCTION }
    val result = candidates.mapNotNull { record -> materialize(record, callableId) as? KtNamedFunction }
    logDeclarationQuery("top-level-function", callableId, candidates.size, result.size)
    return result
  }

  override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> {
    val paths = metadataIndex.filesForPackage(packageFqName.asString())
        .filter(::isInScope)
    val result = paths.asSequence()
        .flatMap { path -> metadataIndex.metadata(path)?.topLevelClassifierNames.orEmpty().asSequence() }
        .map { Name.identifier(it) }
        .toSet()
    logDeclarationQuery("package-classifier-names", packageFqName, paths.size, result.size)
    return result
  }

  override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> {
    val paths = metadataIndex.filesForPackage(packageFqName.asString())
        .filter(::isInScope)
    val result = paths.asSequence()
        .flatMap { path -> metadataIndex.metadata(path)?.topLevelCallableNames.orEmpty().asSequence() }
        .map { Name.identifier(it) }
        .toSet()
    logDeclarationQuery("package-callable-names", packageFqName, paths.size, result.size)
    return result
  }

  override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> {
    val candidates = metadataIndex.topLevelCallableDeclarations(callableId)
        .filter { it.kind == KotlinSourceDeclarationKind.TOP_LEVEL_PROPERTY }
    val result = candidates.mapNotNull { record -> materialize(record, callableId) as? KtProperty }
    logDeclarationQuery("top-level-property", callableId, candidates.size, result.size)
    return result
  }

  private fun logDeclarationQuery(kind: String, key: Any, candidateCount: Int, resultCount: Int) {
    KlsLogs.infoThrottled(
        "kotlin-analysis-declaration-query:$kind:$key",
        500L,
        "Kotlin Analysis declaration provider: action=query, queryKind={}, queryKey={}, candidateCount={}, resultCount={}",
        kind,
        key,
        candidateCount,
        resultCount,
    )
  }

  private fun isInScope(path: Path): Boolean {
    val virtualFile = registry.virtualFile(path)
        ?: VirtualFileManager.getInstance().findFileByNioPath(path)
        ?: return false
    return virtualFile in scope
  }

  private fun materialize(record: KotlinSourceDeclarationRecord, key: Any): org.jetbrains.kotlin.psi.KtDeclaration? {
    val virtualFile = registry.virtualFile(record.path)
        ?: VirtualFileManager.getInstance().findFileByNioPath(record.path)
        ?: run {
          KlsLogs.info("Kotlin Analysis declaration provider: action=candidate-drop, path={}, reason=virtual-file-missing", record.path)
          return null
        }
    if (virtualFile !in scope) {
      KlsLogs.info("Kotlin Analysis declaration provider: action=candidate-drop, path={}, reason=scope, identity={}", record.path, record.identity)
      return null
    }
    val file = liveIndex.getCurrentKtFile(record.path) ?: run {
      KlsLogs.info("Kotlin Analysis declaration provider: action=candidate-drop, path={}, reason=current-file-missing, identity={}", record.path, record.identity)
      return null
    }
    val currentIdentity = metadataIndex.metadata(record.path)?.identity ?: run {
      KlsLogs.info("Kotlin Analysis declaration provider: action=candidate-drop, path={}, reason=metadata-missing, identity={}", record.path, record.identity)
      return null
    }
    if (currentIdentity != record.identity || !liveIndex.isCurrent(record.path, record.identity, file)) {
      KlsLogs.info("Kotlin Analysis declaration provider: action=candidate-drop, path={}, reason=stale-identity, candidateIdentity={}, currentIdentity={}", record.path, record.identity, currentIdentity)
      return null
    }
    return project.analysisRead {
      val element = file.findElementAt(record.startOffset) ?: return@analysisRead null
      val declaration = PsiTreeUtil.getParentOfType(element, org.jetbrains.kotlin.psi.KtDeclaration::class.java, false)
          ?: return@analysisRead null
      when (key) {
        is ClassId -> if (declaration is KtClassLikeDeclaration && declaration.getClassId() == key) declaration else null
        is CallableId -> if (declaration is KtCallableDeclaration && declaration.nameAsName == key.callableName && declaration.containingKtFile.packageFqName == key.packageName) declaration else null
        else -> null
      }
    }
  }
}

internal class LiveDeclarationProvider(
    scope: GlobalSearchScope,
    project: Project,
    liveIndex: LiveSourceFileIndex,
    registry: LiveSourceFileRegistry,
    metadataIndex: KotlinSourceMetadataIndex,
) : AbstractLiveDeclarationProvider(project, scope, liveIndex, registry, metadataIndex) {
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