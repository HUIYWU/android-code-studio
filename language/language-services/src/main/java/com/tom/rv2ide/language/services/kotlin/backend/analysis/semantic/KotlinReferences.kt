@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    org.jetbrains.kotlin.analysis.api.KaNonPublicApi::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingDeclaration
import org.jetbrains.kotlin.analysis.api.components.containingModule
import org.jetbrains.kotlin.analysis.api.components.declaredMemberScope
import org.jetbrains.kotlin.analysis.api.components.fakeOverrideOriginal
import org.jetbrains.kotlin.analysis.api.components.intersectionOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol as callableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertyAccessorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.ControlFlowException
import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtImportAlias
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal sealed interface ReferenceScope {
  data class FileScope(val path: Path) : ReferenceScope

  data class Modules(val modulePaths: Set<String>) : ReferenceScope

  data object AllSource : ReferenceScope
}

internal data class ReferenceDeclarationSite(
    val path: Path,
    val startOffset: Int,
    val endOffset: Int,
    val location: Location,
)

internal data class ReferenceSearchStamp(
    val metadataRevision: Long,
    val identityRevision: Long,
    val requestVersion: Int,
    val requestRevision: Long,
)

internal data class ReferenceSearchPlan(
    val matchPointers: List<KaSymbolPointer<KaSymbol>>,
    val searchNames: Set<String>,
    val scanAllNames: Boolean,
    val requiredConventions: Int,
    val aliasMappingComplete: Boolean,
    val aliasConstructorClassIds: Set<ClassId>,
    val aliasWrittenName: String?,
    val declaration: ReferenceDeclarationSite?,
    val includeDeclaration: Boolean,
    val scope: ReferenceScope,
    val stamp: ReferenceSearchStamp,
)

internal data class ReferenceHit(val location: Location, val role: ReferenceRole)

internal fun KaSession.buildReferencePlan(
    requestPath: Path,
    file: KtFile,
    offset: Int,
    includeDeclaration: Boolean,
    requestVersion: Int,
    requestRevision: Long,
    environment: LiveAnalysisEnvironment,
): ReferenceSearchPlan? {
  val symbol =
      when (val resolution = resolveOrdinaryTargetAt(file, offset)) {
        is KotlinSemanticTargetResolution.Unique -> resolution.symbol
        is KotlinSemanticTargetResolution.Ambiguous ->
            selectAmbiguousTarget(resolution.symbols, file, offset) ?: return null
        KotlinSemanticTargetResolution.None -> return null
      }
  val declaration = symbol as? KaDeclarationSymbol ?: return null
  val normalized = normalizeReferenceTarget(declaration)
  val declarationTarget =
      declaration.psi?.navigationElement
          ?: declaration.containingDeclaration?.psi?.navigationElement
  val site =
      declarationTarget?.let { target ->
        val definition = definitionTargetPsi(target)
        val location = locationForPsi(requestPath, file, definition) ?: return@let null
        if (!isProjectSourcePath(location.file, environment)) return@let null
        val range = definition.textRange ?: return@let null
        ReferenceDeclarationSite(
            path = location.file.normalize(),
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            location = location,
        )
      }
  val targetName = symbolName(normalized)
  val fqNames = targetFqNames(normalized)
  val aliasNames = environment.metadataIndex.aliasNamesFor(fqNames)
  val searchNames = LinkedHashSet<String>()
  targetName?.let { searchNames.add(it) }
  searchNames.addAll(aliasNames)
  if (normalized is KaClassSymbol && normalized.classKind == KaClassKind.COMPANION_OBJECT) {
    (normalized.containingDeclaration as? KaClassSymbol)?.let { outer ->
      symbolName(outer)?.let { searchNames.add(it) }
    }
  }
  val targetModulePath = (declaration.containingModule as? KaSourceModule)?.name
  val scope =
      when (declaration.visibility) {
        KaSymbolVisibility.PRIVATE,
        KaSymbolVisibility.LOCAL,
        KaSymbolVisibility.PACKAGE_PRIVATE -> site?.let { ReferenceScope.FileScope(it.path) } ?: return null
        KaSymbolVisibility.INTERNAL ->
            targetModulePath?.let { ReferenceScope.Modules(setOf(it)) }
                ?: ReferenceScope.AllSource
        else ->
            targetModulePath
                ?.let { modulePath ->
                  ReferenceScope.Modules(
                      setOf(modulePath) + environment.runtimeGraph.dependentClosure(modulePath)
                  )
                }
                ?: ReferenceScope.AllSource
      }
  val aliasConstructorClassIds =
      if (normalized is KaTypeAliasSymbol) {
        setOfNotNull(
            normalized.classId,
            (normalized.expandedType.symbol as? KaClassSymbol)?.classId,
        )
      } else {
        emptySet()
      }
  val aliasWrittenName = if (normalized is KaTypeAliasSymbol) targetName else null
  val pointers = ArrayList<KaSymbolPointer<KaSymbol>>()
  pointers.add(normalized.createPointer())
  if (normalized is KaClassSymbol) {
    normalized.declaredMemberScope.constructors.forEach { pointers.add(it.createPointer()) }
  }
  if (normalized is KaCallableSymbol) {
    normalized.intersectionOverriddenSymbols.forEach { pointers.add(it.createPointer()) }
  }
  return ReferenceSearchPlan(
      matchPointers = pointers,
      searchNames = searchNames,
      scanAllNames = targetName == null,
      requiredConventions = ReferenceConventions.forTarget(targetName),
      aliasMappingComplete =
          fqNames.isNotEmpty() ||
              declaration.visibility == KaSymbolVisibility.PRIVATE ||
              declaration.visibility == KaSymbolVisibility.LOCAL,
      aliasConstructorClassIds = aliasConstructorClassIds,
      aliasWrittenName = aliasWrittenName,
      declaration = site,
      includeDeclaration = includeDeclaration,
      scope = scope,
      stamp =
          ReferenceSearchStamp(
              metadataRevision = environment.metadataIndex.revision(),
              identityRevision = environment.liveIndex.identityRevision(),
              requestVersion = requestVersion,
              requestRevision = requestRevision,
          ),
  )
}

internal fun collectReferences(
    plan: ReferenceSearchPlan,
    environment: LiveAnalysisEnvironment,
    cancelChecker: ICancelChecker,
    observer: ReferenceScanObserver = ReferenceScanObserver.NONE,
): ReferenceResult {
  val orderedPaths = LinkedHashSet<Path>()
  orderedPaths.addAll(selectCandidatePaths(plan, environment))
  val hits = ArrayList<ReferenceHit>()
  if (plan.includeDeclaration) {
    plan.declaration?.let { declaration ->
      hits += ReferenceHit(declaration.location, ReferenceRole.DEFINITION)
    }
  }
  for (path in orderedPaths.sortedBy { it.toString() }) {
    cancelChecker.abortIfCancelled()
    observer.candidateSelected(path)
    val identityBefore = environment.liveIndex.currentIdentity(path)
    val fileHits =
        try {
          scanReferenceFile(plan, path, environment, cancelChecker, observer)
        } catch (t: Throwable) {
          if (t.isReferenceFatal()) throw t
          observer.fileFailed(path)
          KlsLogs.warn(
              "Kotlin references file scan failed: path={}, error={}",
              path,
              t.message,
          )
          continue
        } ?: return ReferenceResult(emptyList())
    if (environment.liveIndex.currentIdentity(path) != identityBefore) {
      return ReferenceResult(emptyList())
    }
    hits += fileHits
  }
  if (plan.stamp.metadataRevision != environment.metadataIndex.revision() ||
      plan.stamp.identityRevision != environment.liveIndex.identityRevision()) {
    return ReferenceResult(emptyList())
  }
  return toReferenceResult(hits)
}

private fun selectCandidatePaths(
    plan: ReferenceSearchPlan,
    environment: LiveAnalysisEnvironment,
): List<Path> {
  val base =
      when (val scope = plan.scope) {
        is ReferenceScope.FileScope -> listOf(scope.path)
        is ReferenceScope.Modules -> environment.liveIndex.currentPaths()
        ReferenceScope.AllSource -> environment.liveIndex.currentPaths()
      }
  val scoped = base.map { it.normalize() }.filter { isPathInScope(it, plan.scope, environment) }
  if (plan.scanAllNames) return scoped
  val candidates = LinkedHashSet<Path>()
  if (plan.searchNames.isNotEmpty()) {
    candidates.addAll(environment.metadataIndex.pathsReferencing(plan.searchNames))
  }
  if (plan.requiredConventions != 0) {
    candidates.addAll(environment.metadataIndex.pathsWithConventions(plan.requiredConventions))
  }
  if (!plan.aliasMappingComplete) {
    candidates.addAll(environment.metadataIndex.pathsWithAliasImports())
  }
  return scoped.filter { path ->
    val metadata = environment.metadataIndex.metadata(path)
    val current = environment.liveIndex.currentIdentity(path)
    metadata == null ||
        current == null ||
        metadata?.identity != current ||
        candidates.contains(path)
  }
}

private fun isPathInScope(
    path: Path,
    scope: ReferenceScope,
    environment: LiveAnalysisEnvironment,
): Boolean =
    when (scope) {
      is ReferenceScope.FileScope -> path == scope.path
      ReferenceScope.AllSource -> true
      is ReferenceScope.Modules -> {
        val metadata = environment.metadataIndex.metadata(path)
        val current = environment.liveIndex.currentIdentity(path)
        val ownerModulePath = metadata?.ownerModulePath
        metadata == null ||
            current == null ||
            metadata?.identity != current ||
            ownerModulePath == null ||
            ownerModulePath in scope.modulePaths
      }
    }

private fun scanReferenceFile(
    plan: ReferenceSearchPlan,
    path: Path,
    environment: LiveAnalysisEnvironment,
    cancelChecker: ICancelChecker,
    observer: ReferenceScanObserver,
): List<ReferenceHit>? =
    environment.liveIndex.withLiveKtFile(path, cancelChecker) { live ->
      if (live.isStale) return@withLiveKtFile null
      val scanAllNames =
          plan.scanAllNames ||
              (!plan.aliasMappingComplete &&
                  environment.metadataIndex.metadata(path)?.aliasImports?.isNotEmpty() == true)
      val hasCandidate =
          scanAllNames || live.read { file -> file.hasReferenceName(plan, cancelChecker) }
      if (!hasCandidate) {
        observer.prefilterSkipped(path)
        emptyList()
      } else {
        observer.analysisSessionStarted(path)
        live.analyzing(cancelChecker) { file ->
          scanReferenceTree(plan, path, file, cancelChecker, scanAllNames, observer)
        }
      }
    }

private fun KtFile.hasReferenceName(
    plan: ReferenceSearchPlan,
    cancelChecker: ICancelChecker,
): Boolean {
  cancelChecker.abortIfCancelled()
  if (plan.scanAllNames) return true
  if (plan.requiredConventions != 0) return true
  if (plan.searchNames.isEmpty()) return false
  var found = false
  accept(
      object : KtTreeVisitorVoid() {
        override fun visitElement(element: PsiElement) {
          cancelChecker.abortIfCancelled()
          if (found) return
          val referencedName = (element as? KtSimpleNameExpression)?.getReferencedName()
          if (referencedName != null && referencedName in plan.searchNames) {
            found = true
            return
          }
          super.visitElement(element)
        }
      }
  )
  return found
}

private fun KaSession.scanReferenceTree(
    plan: ReferenceSearchPlan,
    path: Path,
    file: KtFile,
    cancelChecker: ICancelChecker,
    scanAllNames: Boolean,
    observer: ReferenceScanObserver,
): List<ReferenceHit> {
  val session = this
  val lineIndex = LineIndex.from(file.text)
  val textLength = file.textLength
  val hits = LinkedHashMap<ReferenceKey, ReferenceHit>()
  fun scanConvention(element: PsiElement, reference: KtReference?): Boolean {
    if (reference == null) return false
    cancelChecker.abortIfCancelled()
    if (!session.referenceMatches(reference, plan, null)) return false
    observer.referenceResolved(path)
    locationForReferenceRange(path, element, reference, lineIndex, textLength)?.let { location ->
      hits.putIfAbsent(
          ReferenceKey.of(location),
          ReferenceHit(location, ReferenceRole.USAGE),
      )
    }
    return true
  }
  fun scanConventionSymbol(element: PsiElement, symbol: KaSymbol?): Boolean {
    if (symbol == null) return false
    cancelChecker.abortIfCancelled()
    if (!session.symbolPointerMatches(symbol, plan)) return false
    observer.referenceResolved(path)
    locationForReferenceElement(path, element, lineIndex, textLength)?.let { location ->
      hits.putIfAbsent(
          ReferenceKey.of(location),
          ReferenceHit(location, ReferenceRole.USAGE),
      )
    }
    return true
  }
  val visitor =
      object : KtTreeVisitorVoid() {
        override fun visitElement(element: PsiElement) {
          cancelChecker.abortIfCancelled()
          super.visitElement(element)
        }

        override fun visitImportAlias(importAlias: KtImportAlias) {
          cancelChecker.abortIfCancelled()
        }

        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
          cancelChecker.abortIfCancelled()
          if (!scanAllNames) {
            val referencedName = expression.getReferencedName()
            if (referencedName == null || referencedName !in plan.searchNames) {
              super.visitSimpleNameExpression(expression)
              return
            }
          }
          if (isDeclarationSite(plan, path, expression)) {
            super.visitSimpleNameExpression(expression)
            return
          }
          val reference = expression.reference as? KtReference
          if (reference != null &&
              session.referenceMatches(reference, plan, expression.getReferencedName())) {
            observer.referenceResolved(path)
            locationForReferenceElement(path, expression, lineIndex, textLength)?.let { location ->
              hits.putIfAbsent(
                  ReferenceKey.of(location),
                  ReferenceHit(location, ReferenceRole.USAGE),
              )
            }
          }
          super.visitSimpleNameExpression(expression)
        }

        override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
          if (plan.requiredConventions and ReferenceConventions.ARRAY_ACCESS != 0) {
            if (!scanConvention(expression, expression.reference as? KtReference)) {
              scanConventionSymbol(expression, session.arrayAccessSymbol(expression))
            }
          }
          super.visitArrayAccessExpression(expression)
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression) {
          if (plan.requiredConventions and ReferenceConventions.OPERATOR != 0 &&
            expression.operationToken != KtTokens.EQ) {
            if (expression.operationToken in KtTokens.AUGMENTED_ASSIGNMENTS) {
              scanConventionSymbol(
                  expression.operationReference,
                  session.callableSymbolOf(expression),
              )
            } else {
              scanConvention(
                  expression.operationReference,
                  expression.operationReference.reference as? KtReference,
              )
            }
          }
          super.visitBinaryExpression(expression)
        }

        override fun visitUnaryExpression(expression: KtUnaryExpression) {
          if (plan.requiredConventions and ReferenceConventions.OPERATOR != 0) {
            if (!scanConvention(
                expression.operationReference,
                expression.operationReference.reference as? KtReference,
            )) {
              scanConventionSymbol(
                  expression.operationReference,
                  session.callableSymbolOf(expression),
              )
            }
          }
          super.visitUnaryExpression(expression)
        }

        override fun visitCallExpression(expression: KtCallExpression) {
          if (plan.requiredConventions and ReferenceConventions.INVOKE != 0) {
            scanConvention(expression, expression.reference as? KtReference)
          }
          super.visitCallExpression(expression)
        }

        override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
          if (plan.requiredConventions and ReferenceConventions.DESTRUCTURING != 0) {
            scanConvention(entry, entry.reference as? KtReference)
          }
          super.visitDestructuringDeclarationEntry(entry)
        }

        override fun visitForExpression(expression: KtForExpression) {
          if (plan.requiredConventions and ReferenceConventions.FOR_LOOP != 0) {
            val matched = scanConvention(expression, expression.reference as? KtReference)
            if (!matched && plan.searchNames.any { it == "hasNext" || it == "next" }) {
              session.forLoopProtocolSymbols(expression).forEach { symbol ->
                scanConventionSymbol(expression, symbol)
              }
            }
          }
          super.visitForExpression(expression)
        }

        override fun visitPropertyDelegate(delegate: KtPropertyDelegate) {
          if (plan.requiredConventions and ReferenceConventions.DELEGATE != 0) {
            scanConvention(delegate, delegate.reference as? KtReference)
          }
          super.visitPropertyDelegate(delegate)
        }
      }
  file.accept(visitor)
  return hits.values.toList()
}

private fun KaSession.referenceMatches(
    reference: KtReference,
    plan: ReferenceSearchPlan,
    writtenName: String?,
): Boolean =
    reference.resolveToSymbols().any { symbol ->
      val normalized = (symbol as? KaCallableSymbol)?.fakeOverrideOriginal ?: symbol
      val pointerMatch =
          plan.matchPointers.any { pointer ->
            normalized.createPointer().pointsToTheSameSymbolAs(pointer)
          }
      pointerMatch || isAliasConstructorMatch(symbol, plan, writtenName)
    }

private fun isProjectSourcePath(
    path: Path,
    environment: LiveAnalysisEnvironment,
): Boolean {
  val normalized = path.normalize()
  if (environment.liveIndex.currentIdentity(normalized) != null) return true
  return environment.projectContext.modules.any { module ->
    (module.javaSourceRoots + module.generatedSourceRoots).any { root ->
      val normalizedRoot = root.normalize()
      normalized == normalizedRoot || normalized.startsWith(normalizedRoot)
    }
  }
}

private fun KaSession.symbolPointerMatches(
    symbol: KaSymbol,
    plan: ReferenceSearchPlan,
): Boolean {
  val normalized = (symbol as? KaCallableSymbol)?.fakeOverrideOriginal ?: symbol
  return plan.matchPointers.any { pointer ->
    normalized.createPointer().pointsToTheSameSymbolAs(pointer)
  }
}

private fun KaSession.arrayAccessSymbol(element: KtArrayAccessExpression): KaSymbol? =
    element.resolveSymbol()

private fun KaSession.forLoopProtocolSymbols(expression: KtForExpression): List<KaSymbol> {
  val reference = expression.reference as? KtReference ?: return emptyList()
  val iterator =
      reference.resolveToSymbols().filterIsInstance<KaCallableSymbol>().firstOrNull()
          ?: return emptyList()
  val classType = iterator.returnType as? KaClassType ?: return emptyList()
  val classSymbol = classType.symbol as? KaClassSymbol ?: return emptyList()
  val names = listOf(Name.identifier("hasNext"), Name.identifier("next"))
  return classSymbol.declaredMemberScope.callables(names).toList()
}

private fun KaSession.callableSymbolOf(expression: KtExpression): KaSymbol? {
  val call = expression.resolveToCall()?.successfulCallOrNull<KaCall>() ?: return null
  return when (call) {
    is KaCompoundAccessCall -> call.operationCall.callableSymbol
    is KaFunctionCall<*> -> call.callableSymbol
    else -> null
  }
}

private fun isAliasConstructorMatch(
    symbol: KaSymbol,
    plan: ReferenceSearchPlan,
    writtenName: String?,
): Boolean =
    symbol is KaConstructorSymbol &&
        plan.aliasWrittenName != null &&
        writtenName == plan.aliasWrittenName &&
        symbol.containingClassId?.let { it in plan.aliasConstructorClassIds } == true

private fun KaSession.normalizeReferenceTarget(symbol: KaSymbol): KaSymbol {
  val property =
      if (symbol is KaPropertyAccessorSymbol) {
        symbol.containingDeclaration as? KaPropertySymbol
      } else {
        null
      }
  val base = property ?: symbol
  return (base as? KaCallableSymbol)?.fakeOverrideOriginal ?: base
}

private fun selectAmbiguousTarget(
    symbols: List<KaSymbol>,
    file: KtFile,
    offset: Int,
): KaSymbol? {
  val constructor = symbols.filterIsInstance<KaConstructorSymbol>().singleOrNull()
  if (constructor != null && isConstructorCallCallee(file, offset)) return constructor
  return singleClassFamily(symbols)
}

private fun isConstructorCallCallee(file: KtFile, offset: Int): Boolean {
  var element = file.findElementAt(offset.coerceIn(0, file.textLength))
  while (element != null && element !is KtFile) {
    if (element is KtSimpleNameExpression) {
      val call = element.parent as? KtCallExpression
      if (call?.calleeExpression == element) return true
    }
    element = element.parent
  }
  return false
}

private fun singleClassFamily(symbols: List<KaSymbol>): KaSymbol? {
  val classIds = symbols.mapNotNull(::classIdOf)
  if (classIds.size != symbols.size || classIds.distinct().size != 1) return null
  return symbols.filterIsInstance<KaClassSymbol>().singleOrNull()
}

private fun classIdOf(symbol: KaSymbol): ClassId? =
    when (symbol) {
      is KaClassSymbol -> symbol.classId
      is KaConstructorSymbol -> symbol.containingClassId
      else -> null
    }

private fun targetFqNames(symbol: KaSymbol): Set<String> =
    buildSet {
      when (symbol) {
        is KaClassSymbol -> symbol.classId?.asFqNameString()?.let { add(it) }
        is KaTypeAliasSymbol -> symbol.classId?.asFqNameString()?.let { add(it) }
        is KaConstructorSymbol -> symbol.containingClassId?.asFqNameString()?.let { add(it) }
        is KaCallableSymbol -> symbol.callableId?.asSingleFqName()?.asString()?.let { add(it) }
        else -> Unit
      }
    }

private fun isDeclarationSite(
    plan: ReferenceSearchPlan,
    path: Path,
    expression: KtSimpleNameExpression,
): Boolean {
  val site = plan.declaration ?: return false
  val range = expression.textRange ?: return false
  return site.path == path &&
      site.startOffset == range.startOffset &&
      site.endOffset == range.endOffset
}

private fun locationForReferenceElement(
    path: Path,
    element: PsiElement,
    lineIndex: LineIndex,
    textLength: Int,
): Location? {
  val range = element.textRange ?: return null
  val start = range.startOffset.coerceIn(0, textLength)
  val end = range.endOffset.coerceIn(start, textLength)
  return Location(
      path.normalize(),
      Range(lineIndex.indexToPosition(start), lineIndex.indexToPosition(end)),
  )
}

private fun locationForReferenceRange(
    path: Path,
    element: PsiElement,
    reference: KtReference,
    lineIndex: LineIndex,
    textLength: Int,
): Location? {
  val elementRange = element.textRange ?: return null
  val relative = reference.rangeInElement
  val start = (elementRange.startOffset + relative.startOffset).coerceIn(0, textLength)
  val end = (elementRange.startOffset + relative.endOffset).coerceIn(start, textLength)
  return Location(
      path.normalize(),
      Range(lineIndex.indexToPosition(start), lineIndex.indexToPosition(end)),
  )
}

private fun toReferenceResult(hits: List<ReferenceHit>): ReferenceResult {
  val merged = LinkedHashMap<ReferenceKey, ReferenceHit>()
  hits.forEach { hit ->
    val key = ReferenceKey.of(hit.location)
    val current = merged[key]
    if (current == null || hit.role == ReferenceRole.DEFINITION) {
      merged[key] = hit
    }
  }
  val sorted =
      merged.values.sortedWith(
          compareBy<ReferenceHit>(
              { it.location.file.toString() },
              { it.location.range.start.line },
              { it.location.range.start.column },
              { it.location.range.end.line },
              { it.location.range.end.column },
          )
      )
  return ReferenceResult(sorted.map { it.location }, sorted.map { it.role })
}

private data class ReferenceKey(
    val file: Path,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
) {
  companion object {
    fun of(location: Location): ReferenceKey =
        ReferenceKey(
            location.file.normalize(),
            location.range.start.line,
            location.range.start.column,
            location.range.end.line,
            location.range.end.column,
        )
  }
}

private fun Throwable.isReferenceFatal(): Boolean =
    this is CancellationException ||
        this is ControlFlowException ||
        this is IndexNotReadyException
