package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceDeclarationKey
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceDeclarationKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceDeclarationRecord
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileIndex
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.fir.utils.isSubclassOf
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject

@OptIn(KaImplementationDetail::class)
internal class LiveDirectInheritorsProvider : KotlinDirectInheritorsProvider {
  private lateinit var project: Project
  private lateinit var liveIndex: LiveSourceFileIndex
  private lateinit var metadataIndex: KotlinSourceMetadataIndex

  fun setup(project: MockProject, liveIndex: LiveSourceFileIndex, metadataIndex: KotlinSourceMetadataIndex) {
    this.project = project
    this.liveIndex = liveIndex
    this.metadataIndex = metadataIndex
  }

  @OptIn(SymbolInternals::class)
  override fun getDirectKotlinInheritors(
      ktClass: KtClass,
      scope: GlobalSearchScope,
      includeLocalInheritors: Boolean,
  ): Iterable<KtClassOrObject> {
    val classId = ktClass.getClassId() ?: return emptyList()
    val baseModule = KotlinProjectStructureProvider.getModule(project, ktClass, useSiteModule = null)
    val baseFirClass = classId.toFirSymbol(baseModule)?.fir as? FirClass ?: return emptyList()
    val baseClassNames = mutableSetOf(classId.shortClassName)
    calculateAliases(classId.shortClassName, baseClassNames)
    val candidates = baseClassNames
        .flatMap { name -> metadataIndex.classesBySupertypeName(name.asString()) }
        .filter { it.kind == KotlinSourceDeclarationKind.CLASS_LIKE }
    val materialized = candidates.mapNotNull(::materializeClass)
    val result = materialized.filter { isValidInheritor(it, baseFirClass, scope, includeLocalInheritors) }
    KlsLogs.infoThrottled(
        "kotlin-analysis-direct-inheritors-query:$classId:$includeLocalInheritors",
        500L,
        "Kotlin Analysis direct inheritors: action=query, baseClassId={}, aliasNameCount={}, candidateCount={}, materializedCount={}, resultCount={}, includeLocalInheritors={}",
        classId,
        baseClassNames.size,
        candidates.size,
        materialized.size,
        result.size,
        includeLocalInheritors,
    )
    return result
  }

  private fun calculateAliases(aliasedName: Name, aliases: MutableSet<Name>) {
    metadataIndex.typeAliasesByAliasedName(aliasedName.asString())
        .filter { it.kind == KotlinSourceDeclarationKind.TYPE_ALIAS }
        .forEach { alias ->
          val aliasName = Name.identifier(alias.name)
          if (aliases.add(aliasName)) calculateAliases(aliasName, aliases)
        }
  }

  private fun materializeClass(record: KotlinSourceDeclarationRecord): KtClassOrObject? {
    val file = liveIndex.getCurrentKtFile(record.path) ?: run {
      KlsLogs.info("Kotlin Analysis direct inheritors: action=candidate-drop, path={}, reason=current-file-missing, identity={}", record.path, record.identity)
      return null
    }
    val currentIdentity = metadataIndex.metadata(record.path)?.identity ?: run {
      KlsLogs.info("Kotlin Analysis direct inheritors: action=candidate-drop, path={}, reason=metadata-missing, identity={}", record.path, record.identity)
      return null
    }
    if (currentIdentity != record.identity || !liveIndex.isCurrent(record.path, record.identity, file)) {
      KlsLogs.info("Kotlin Analysis direct inheritors: action=candidate-drop, path={}, reason=stale-identity, candidateIdentity={}, currentIdentity={}", record.path, record.identity, currentIdentity)
      return null
    }
    return project.analysisRead {
      val element = file.findElementAt(record.startOffset) ?: return@analysisRead null
      val declaration = org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil.getParentOfType(
          element,
          KtClassOrObject::class.java,
          false,
      ) ?: return@analysisRead null
      val expectedClassId = (record.key as? KotlinSourceDeclarationKey.ClassLike)?.classId ?: return@analysisRead null
      declaration.takeIf { it.getClassId() == expectedClassId }
    }
  }

  @OptIn(KaImplementationDetail::class, SymbolInternals::class)
  private fun isValidInheritor(
      candidate: KtClassOrObject,
      baseFirClass: FirClass,
      scope: GlobalSearchScope,
      includeLocalInheritors: Boolean,
  ): Boolean {
    if (!includeLocalInheritors && candidate.isLocal) return false
    val candidateVirtualFile = candidate.containingKtFile.virtualFile ?: return false
    if (!scope.contains(candidateVirtualFile)) return false
    val candidateClassId = candidate.getClassId() ?: return false
    val candidateModule = KotlinProjectStructureProvider.getModule(project, candidate, useSiteModule = null)
    val candidateFirSymbol = candidateClassId.toFirSymbol(candidateModule) ?: return false
    val candidateFirClass = candidateFirSymbol.fir as? FirClass ?: return false
    return isSubclassOf(
        candidateFirClass,
        baseFirClass,
        candidateFirClass.moduleData.session,
        allowIndirectSubtyping = false,
    )
  }

  @OptIn(LLFirInternals::class)
  private fun ClassId.toFirSymbol(module: KaModule): FirClassLikeSymbol<*>? {
    val session = LLFirSessionCache.getInstance(project).getSession(module, preferBinary = true)
    return session.symbolProvider.getClassLikeSymbolByClassId(this)
  }
}
