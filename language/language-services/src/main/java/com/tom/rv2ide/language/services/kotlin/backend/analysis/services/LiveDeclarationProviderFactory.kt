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
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
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
  private var standaloneFactory: KotlinStandaloneDeclarationProviderFactory? = null

  fun setup(
      project: MockProject,
      liveIndex: LiveSourceFileIndex,
      registry: LiveSourceFileRegistry,
      metadataIndex: KotlinSourceMetadataIndex,
      coreApplicationEnvironment: CoreApplicationEnvironment,
      librarySourceFiles: Collection<KtFile>,
  ) {
    this.project = project
    this.liveIndex = liveIndex
    this.registry = registry
    this.metadataIndex = metadataIndex
    standaloneFactory = KotlinStandaloneDeclarationProviderFactory(
        project = project,
        environment = coreApplicationEnvironment,
        sourceKtFiles = librarySourceFiles,
        postponeIndexing = true,
    )
  }

  internal fun createLiveProvider(scope: GlobalSearchScope): LiveDeclarationProvider =
      LiveDeclarationProvider(scope, project, liveIndex, registry, metadataIndex)

  override fun createDeclarationProvider(
      scope: GlobalSearchScope,
      contextualModule: KaModule?,
  ): KotlinDeclarationProvider {
    val live = createLiveProvider(scope)
    val standalone = standaloneFactory?.createDeclarationProvider(scope, contextualModule)
        ?: return live
    return KotlinCompositeDeclarationProvider.create(listOf(live, standalone))
  }
}

internal class LiveDeclarationProviderMerger(
    private val factory: LiveDeclarationProviderFactory,
) : KotlinDeclarationProviderMerger {
  override fun merge(providers: List<KotlinDeclarationProvider>): KotlinDeclarationProvider =
      providers.mergeSpecificProviders<_, LiveDeclarationProvider>(KotlinCompositeDeclarationProvider.factory) { targets ->
        val scope = GlobalSearchScope.union(targets.map { it.scope })
        factory.createLiveProvider(scope)
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
    val candidates = metadataIndex.facadeDeclarations(facadeFqName)
        .filter { it.kind == KotlinSourceDeclarationKind.FACADE }
    val materialized = candidates.mapNotNull(::materializeFile)
    val result = materialized.filter { it.javaFileFacadeFqName == facadeFqName }
    return result
  }

  override fun findInternalFilesForFacade(facadeFqName: FqName): Collection<KtFile> = emptyList()

  override fun findFilesForFacadeByPackage(packageFqName: FqName): Collection<KtFile> = filesForPackage(packageFqName).toList()

  override fun findFilesForScript(scriptFqName: FqName): Collection<KtScript> {
    val candidates = metadataIndex.scriptDeclarations(scriptFqName)
        .filter { it.kind == KotlinSourceDeclarationKind.SCRIPT }
    val result = candidates.mapNotNull { record -> materializeScript(record, scriptFqName) }
    logScriptQuery(scriptFqName, candidates.size, result.size)
    return result
  }

  override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> {
    val candidates = metadataIndex.classLikeDeclarations(classId)
        .filter { it.kind == KotlinSourceDeclarationKind.CLASS_LIKE }
    val result = candidates.mapNotNull { record -> materialize(record, classId) as? KtClassOrObject }
    return result
  }

  override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> {
    val candidates = metadataIndex.typeAliasDeclarations(classId)
        .filter { it.kind == KotlinSourceDeclarationKind.TYPE_ALIAS }
    val result = candidates.mapNotNull { record -> materialize(record, classId) as? KtTypeAlias }
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
    return result
  }

  override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> {
    val paths = metadataIndex.filesForPackage(packageFqName.asString())
        .filter(::isInScope)
    val result = paths.asSequence()
        .flatMap { path -> metadataIndex.metadata(path)?.topLevelClassifierNames.orEmpty().asSequence() }
        .map { Name.identifier(it) }
        .toSet()
    return result
  }

  override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> {
    val paths = metadataIndex.filesForPackage(packageFqName.asString())
        .filter(::isInScope)
    val result = paths.asSequence()
        .flatMap { path -> metadataIndex.metadata(path)?.topLevelCallableNames.orEmpty().asSequence() }
        .map { Name.identifier(it) }
        .toSet()
    return result
  }

  override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> {
    val candidates = metadataIndex.topLevelCallableDeclarations(callableId)
        .filter { it.kind == KotlinSourceDeclarationKind.TOP_LEVEL_PROPERTY }
    val result = candidates.mapNotNull { record -> materialize(record, callableId) as? KtProperty }
    return result
  }

  private fun logScriptQuery(key: FqName, candidateCount: Int, resultCount: Int) {
    KlsLogs.infoThrottled(
        "kotlin-analysis-script-query:$key",
        500L,
        "Kotlin Analysis script provider: action=query, queryKey={}, candidateCount={}, resultCount={}",
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

  private fun materializeFile(record: KotlinSourceDeclarationRecord): KtFile? {
    val virtualFile = registry.virtualFile(record.path)
        ?: VirtualFileManager.getInstance().findFileByNioPath(record.path)
        ?: run {
          KlsLogs.info("Kotlin Analysis declaration provider: action=candidate-drop, path={}, reason=virtual-file-missing, identity={}", record.path, record.identity)
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
    return file
  }

  private fun materializeScript(record: KotlinSourceDeclarationRecord, scriptFqName: FqName): KtScript? =
      materializeFile(record)?.let { file ->
        project.analysisRead {
          file.script?.takeIf { it.fqName == scriptFqName }
        }
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