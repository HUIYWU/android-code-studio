@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.language.services.interop.KotlinSourceResolver
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.LiveAnalysisEnvironment
import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceClassifierVisibility
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceDeclarationKind
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceDeclarationKey
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.liveBackingPath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.scheduler.AnalysisApiGuard
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.buildReferencePlan
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.collectReferences
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.definitionTargetPsi
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.documentationText
import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.parameterLabel
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.MarkupKind
import com.tom.rv2ide.lsp.models.ParameterInformation
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureInformation
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.types.Variance

/**
 * Answers Java-side questions about Kotlin declarations from the Analysis PSI project.
 *
 * Only Kotlin source is reported. References that resolve inside Java stay with the Java service so
 * that both sides never answer the same request twice. Facade classes for top-level declarations
 * are reported as their backing Kotlin file.
 */
internal class LiveJavaPsiResolver(
    private val environment: LiveAnalysisEnvironment,
) : KotlinSourceResolver {

  override fun kotlinDefinition(
      javaFile: Path,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): Location? = withJavaPsi(javaFile, offset, cancelChecker) { target ->
    val ktFile = target.ktFile ?: return@withJavaPsi null
    val path = sourcePath(ktFile) ?: return@withJavaPsi null
    when (target) {
      is KotlinTarget.FacadeFile -> Location(path, Range(Position(0, 0, 0), Position(0, 0, 0)))
      is KotlinTarget.Declaration -> {
        val range = definitionTargetPsi(target.declaration).textRange
            ?: return@withJavaPsi null
        val index = LineIndex.from(ktFile.text)
        Location(
            path,
            Range(index.indexToPosition(range.startOffset), index.indexToPosition(range.endOffset)),
        )
      }
    }
  }

  override fun kotlinHover(
      javaFile: Path,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): MarkupContent? = withJavaPsi(javaFile, offset, cancelChecker) { target ->
    val declaration =
        (target as? KotlinTarget.Declaration)?.declaration ?: return@withJavaPsi null
    try {
      analyze(declaration) {
        val symbol = declaration.symbol as? KaDeclarationSymbol ?: return@analyze null
        val signature = symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES).trim()
        if (signature.isEmpty()) return@analyze null
        val documentation = documentationText(symbol, declaration)
        MarkupContent(
            buildString {
              append("```kotlin\n")
              append(signature)
              append("\n```")
              if (documentation.isNotEmpty()) {
                append("\n\n---\n\n")
                append(documentation)
              }
            },
            MarkupKind.MARKDOWN,
        )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (cancelled: ProcessCanceledException) {
      throw cancelled
    } catch (error: Exception) {
      KlsLogs.warn("Java interop hover failed for declaration: {}", declaration.textRange, error)
      null
    }
  }

  fun renderSignature(
      declaration: KtDeclaration,
      jvmParameterCount: Int?,
      argumentIndex: Int,
      cancelChecker: ICancelChecker,
  ): SignatureHelp? {
    cancelChecker.abortIfCancelled()
    val result = analyze(declaration) { signatureFor(declaration, jvmParameterCount, argumentIndex) }
    cancelChecker.abortIfCancelled()
    return result
  }

  override fun kotlinReferences(
      javaFile: Path,
      offset: Int,
      includeDeclaration: Boolean,
      cancelChecker: ICancelChecker,
  ): ReferenceResult? {
    val plan =
        try {
          withJavaFile(javaFile, offset, cancelChecker) { _, leaf ->
            val target = resolveTarget(leaf, javaFile) ?: return@withJavaFile null
            val declaration =
                (target as? KotlinTarget.Declaration)?.declaration
                    ?: return@withJavaFile null
            val ktFile = declaration.containingFile as? KtFile ?: return@withJavaFile null
            val path = sourcePath(ktFile) ?: return@withJavaFile null
            val declarationOffset =
                definitionTargetPsi(declaration).textRange?.startOffset
                    ?: return@withJavaFile null
            val snapshot = FileManager.getActiveDocumentSnapshot(path)
            analyze(declaration) {
              buildReferencePlan(
                  requestPath = path,
                  file = ktFile,
                  offset = declarationOffset,
                  includeDeclaration = includeDeclaration,
                  requestVersion = snapshot?.version ?: -1,
                  requestRevision = snapshot?.revision ?: -1L,
                  environment = environment,
              )
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (cancelled: ProcessCanceledException) {
          throw cancelled
        } catch (error: Exception) {
          KlsLogs.warn("Java interop references failed for {}", javaFile, error)
          null
        } ?: return null
    return collectReferences(plan, environment, cancelChecker)
  }

  override fun kotlinTopLevelTypes(
      modulePath: String,
      cancelChecker: ICancelChecker,
  ): Set<String>? {
    if (environment.sourceModulesByPath[modulePath] == null) return null
    val metadataIndex = environment.metadataIndex
    val visibleModules =
        buildSet {
          add(modulePath)
          addAll(environment.runtimeGraph.dependenciesByModule[modulePath].orEmpty())
        }
    val types = LinkedHashSet<String>()
    for (path in metadataIndex.paths()) {
      cancelChecker.abortIfCancelled()
      val metadata = metadataIndex.metadata(path) ?: continue
      val ownerModulePath = metadata.ownerModulePath ?: continue
      if (ownerModulePath !in visibleModules) continue
      val packageName = metadata.packageName
      val classNames =
          metadata.declarations.mapNotNullTo(HashSet()) { declaration ->
            if (declaration.kind != KotlinSourceDeclarationKind.CLASS_LIKE) {
              return@mapNotNullTo null
            }
            val classId =
                (declaration.key as? KotlinSourceDeclarationKey.ClassLike)?.classId
                    ?: return@mapNotNullTo null
            if (classId.isNestedClass) {
              return@mapNotNullTo null
            }
            declaration.name
          }
      for (name in metadata.topLevelClassifierNames) {
        if (name !in classNames) continue
        val visibility =
            metadata.topLevelClassifierVisibility[name]
                ?: KotlinSourceClassifierVisibility.PUBLIC
        if (visibility == KotlinSourceClassifierVisibility.PRIVATE) continue
        types.add(if (packageName.isEmpty()) name else "$packageName.$name")
      }
      val hasVisibleCallable =
          metadata.topLevelCallableVisibility.values.any {
            it != KotlinSourceClassifierVisibility.PRIVATE
          } ||
              metadata.topLevelExtensions.any {
                it.visibility != KotlinSourceClassifierVisibility.PRIVATE
              }
      if (!hasVisibleCallable) continue
      metadata.declarations.forEach { declaration ->
        if (declaration.kind == KotlinSourceDeclarationKind.FACADE) types.add(declaration.name)
      }
    }
    if (types.isEmpty()) return null
    return types
  }

  override fun metadataRevision(): Long = environment.metadataIndex.revision()

  override fun kotlinTypeSourceFiles(modulePath: String, qualifiedName: String): List<Path> {
    if (environment.sourceModulesByPath[modulePath] == null) return emptyList()
    val metadataIndex = environment.metadataIndex
    val visibleModules =
        buildSet {
          add(modulePath)
          addAll(environment.runtimeGraph.dependenciesByModule[modulePath].orEmpty())
        }
    fun visible(path: Path): Boolean =
        metadataIndex.metadata(path)?.ownerModulePath?.let { it in visibleModules } == true
    val fqName = runCatching { FqName(qualifiedName) }.getOrNull() ?: return emptyList()
    val classPaths =
        runCatching { metadataIndex.classLikeDeclarations(ClassId.topLevel(fqName)) }
            .getOrDefault(emptyList())
            .filter { visible(it.path) }
            .map { it.path }
    val facadePaths =
        metadataIndex.facadeDeclarations(fqName).filter { visible(it.path) }.map { it.path }
    return (classPaths + facadePaths).distinct()
  }

  private fun KaSession.signatureFor(
      declaration: KtDeclaration,
      jvmParameterCount: Int?,
      argumentIndex: Int,
  ): SignatureHelp? =
      when (declaration) {
        is KtConstructor<*>, is KtClassOrObject -> constructorSignature(declaration, argumentIndex)
        is KtNamedFunction -> jvmParameterCount?.let { functionSignature(declaration, it, argumentIndex) }
        else -> null
      }

  private fun KaSession.functionSignature(
      declaration: KtNamedFunction,
      jvmParameterCount: Int,
      argumentIndex: Int,
  ): SignatureHelp? {
    val symbol = declaration.symbol as? KaNamedFunctionSymbol ?: return null
    if (symbol.isSuspend) return null
    val isExtension = symbol.receiverParameter != null
    val expected = symbol.valueParameters.size + if (isExtension) 1 else 0
    if (jvmParameterCount > expected) return null
    val name = symbol.callableId?.let { it.callableName.asString() } ?: return null
    val candidates = listOf(declaration) + declaration.overloads(name).filter { it !== declaration }
    val entries = candidates.mapNotNull { candidate ->
      val candidateSymbol = candidate.symbol as? KaNamedFunctionSymbol ?: return@mapNotNull null
      if (candidateSymbol.isSuspend) return@mapNotNull null
      if ((candidateSymbol.receiverParameter != null) != isExtension) return@mapNotNull null
      if (candidateSymbol.visibility == KaSymbolVisibility.PRIVATE) return@mapNotNull null
      val labels = signatureLabels(candidateSymbol)
      signatureEntry(name, labels, candidateSymbol.valueParameters.lastOrNull()?.isVararg == true)
    }.distinctBy { it.information.label }
    return signatureHelp(entries, argumentIndex)
  }

  private fun KaSession.signatureLabels(symbol: KaFunctionSymbol): List<String> =
      buildList {
        symbol.receiverParameter?.let { receiver ->
          add(receiver.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT))
        }
        symbol.valueParameters.forEach { parameter ->
          add(parameterLabel(parameter.name.asString(), parameter.returnType, parameter.isVararg))
        }
      }

  private fun KaSession.constructorSignature(
      declaration: KtDeclaration,
      argumentIndex: Int,
  ): SignatureHelp? {
    val owner = when (declaration) {
      is KtConstructor<*> -> declaration.containingClassOrObject ?: return null
      is KtClassOrObject -> declaration
      else -> return null
    }
    val className = owner.name ?: return null
    val declared: List<KtDeclaration> = buildList {
      owner.primaryConstructor?.let { add(it) }
      addAll(owner.secondaryConstructors)
    }
    if (declared.isEmpty()) {
      return signatureHelp(listOf(signatureEntry(className, emptyList(), false)), argumentIndex)
    }
    // The declaration that the Java call already resolved to stays first so it wins as active.
    val entries = declared.sortedBy { if (it === declaration) 0 else 1 }.mapNotNull { constructor ->
      val symbol = constructor.symbol as? KaConstructorSymbol ?: return@mapNotNull null
      if (symbol.visibility == KaSymbolVisibility.PRIVATE) return@mapNotNull null
      signatureEntry(
          className,
          symbol.valueParameters.map { parameter ->
            parameterLabel(parameter.name.asString(), parameter.returnType, parameter.isVararg)
          },
          symbol.valueParameters.lastOrNull()?.isVararg == true,
      )
    }
    return signatureHelp(entries, argumentIndex)
  }

  private fun signatureEntry(
      name: String,
      labels: List<String>,
      varargTail: Boolean,
  ): SignatureEntry =
      SignatureEntry(
          SignatureInformation(
              labels.joinToString(", ", prefix = "$name(", postfix = ")"),
              MarkupContent(),
              labels.map { ParameterInformation(it, MarkupContent()) },
          ),
          labels.size,
          varargTail,
      )

  private fun signatureHelp(entries: List<SignatureEntry>, argumentIndex: Int): SignatureHelp? {
    if (entries.isEmpty()) return null
    val active = entries.indexOfFirst { it.accepts(argumentIndex) }.coerceAtLeast(0)
    return SignatureHelp(
        entries.map { it.information },
        active,
        entries[active].activeParameter(argumentIndex),
    )
  }

  private fun KtNamedFunction.overloads(name: String): List<KtNamedFunction> {
    val container: KtDeclarationContainer =
        containingClassOrObject ?: containingFile as? KtFile ?: return emptyList()
    return container.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name }
  }

  private fun <T> withJavaPsi(
      javaFile: Path,
      offset: Int,
      cancelChecker: ICancelChecker,
      action: (KotlinTarget) -> T?,
  ): T? =
      withJavaFile(javaFile, offset, cancelChecker) { _, leaf ->
        val target = resolveTarget(leaf, javaFile) ?: return@withJavaFile null
        action(target)
      }

  private fun <T> withJavaFile(
      javaFile: Path,
      offset: Int,
      cancelChecker: ICancelChecker,
      action: (PsiFile, PsiElement) -> T?,
  ): T? =
      AnalysisApiGuard.withAccess(cancelChecker) {
        ApplicationManager.getApplication().runReadAction(Computable {
          environment.project.analysisRead {
            val file = environment.javaFiles.currentFile(javaFile.normalize())
                ?: return@analysisRead null
            if (offset !in 0 until file.textLength) return@analysisRead null
            cancelChecker.abortIfCancelled()
            val leaf = file.findElementAt(offset) ?: return@analysisRead null
            action(file, leaf)
          }
        })
      }

  private fun resolveTarget(leaf: PsiElement, javaFile: Path): KotlinTarget? {
    val reference =
        PsiTreeUtil.getParentOfType(leaf, PsiJavaCodeReferenceElement::class.java, false)
            ?: return null
    val navigation = resolving { reference.resolve()?.navigationElement } ?: return null
    return when (navigation) {
      is KtFile -> KotlinTarget.FacadeFile(navigation)
      is KtDeclaration ->
          if (isKotlinSourceDeclaration(navigation, javaFile)) {
            KotlinTarget.Declaration(navigation)
          } else {
            null
          }
      else -> null
    }
  }

  private fun isKotlinSourceDeclaration(declaration: KtDeclaration, javaFile: Path): Boolean {
    val ktFile = declaration.containingFile as? KtFile ?: return false
    return !sameFile(ktFile, javaFile)
  }

  private fun sameFile(ktFile: KtFile, javaFile: Path): Boolean {
    val path = sourcePath(ktFile) ?: return false
    return path.normalize() == javaFile.normalize()
  }

  private inline fun <T> resolving(action: () -> T?): T? =
      try {
        action()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (cancelled: ProcessCanceledException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }

  private fun sourcePath(ktFile: KtFile): Path? =
      ktFile.liveBackingPath
          ?: ktFile.virtualFile?.takeIf { it.isInLocalFileSystem }?.path?.let(Paths::get)

  private sealed interface KotlinTarget {
    val ktFile: KtFile?

    class Declaration(val declaration: KtDeclaration) : KotlinTarget {
      override val ktFile: KtFile?
        get() = declaration.containingFile as? KtFile
    }

    class FacadeFile(override val ktFile: KtFile) : KotlinTarget
  }

  private class SignatureEntry(
      val information: SignatureInformation,
      private val parameterCount: Int,
      private val varargTail: Boolean,
  ) {
    fun accepts(argumentIndex: Int): Boolean =
        when {
          parameterCount == 0 -> argumentIndex == 0
          argumentIndex < parameterCount -> true
          else -> varargTail
        }

    fun activeParameter(argumentIndex: Int): Int =
        when {
          parameterCount == 0 -> 0
          varargTail && argumentIndex >= parameterCount -> parameterCount - 1
          else -> argumentIndex.coerceIn(0, parameterCount - 1)
        }
  }
}