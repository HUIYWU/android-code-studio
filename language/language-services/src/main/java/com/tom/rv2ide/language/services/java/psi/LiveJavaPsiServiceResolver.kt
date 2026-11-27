package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.interop.JavaPsiAccessorsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiAccessorsResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiDefinitionTarget
import com.tom.rv2ide.language.services.java.interop.JavaPsiConstructorResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiConstructorResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldInfo
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateAccessorsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateConstructorParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateToStringParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiToStringResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiToStringResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateOverridesParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiImplementAbstractMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiAddUnhandledExceptionsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiImportCandidate
import com.tom.rv2ide.language.services.java.interop.JavaPsiFixImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiFixImportsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiResolveImportCandidatesParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiRemoveUnusedThrowsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiRemoveUnusedImportsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiSuppressUncheckedWarningParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiUnresolvedMethodParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiHoverTarget
import com.tom.rv2ide.language.services.java.interop.JavaPsiProjectAccess
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceResolver
import com.tom.rv2ide.language.services.java.providers.completion.JavaCompletionTypeIndex
import com.tom.rv2ide.language.services.java.providers.definition.expandSelectionInJavaPsi
import com.tom.rv2ide.language.services.kotlin.backend.analysis.source.withJavaDiagnosticProgress
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.ModuleProject
import java.nio.file.Path
import java.util.LinkedHashMap
import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiPolyVariantReference
import org.jetbrains.kotlin.com.intellij.psi.PsiReference
import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiSuperExpression
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

internal class LiveJavaPsiServiceResolver(
    private val projectAccess: JavaPsiProjectAccess,
    private val kotlinSignature: (KtDeclaration, Int?, Int, ICancelChecker) -> SignatureHelp?,
) : JavaPsiServiceResolver {
  override fun fields(params: JavaPsiFieldsParams): JavaPsiFieldsResult? =
      projectAccess.withCurrentJavaFile(
          params.file,
          params.documentVersion,
          params.documentRevision,
          params.cancelChecker,
      ) { file ->
        val index = LineIndex.from(file.text)
        val start = index.lineColumnToIndex(params.selection.start.line, params.selection.start.column)
        val end = index.lineColumnToIndex(params.selection.end.line, params.selection.end.column)
        val owner = classAt(file, start) ?: classAt(file, end)
        val fields = owner?.fields.orEmpty().mapNotNull { field ->
          params.cancelChecker.abortIfCancelled()
          if (field.hasModifierProperty(PsiModifier.STATIC)) return@mapNotNull null
          JavaPsiFieldInfo(
              field.name,
              field.type.presentableText,
              field.hasModifierProperty(PsiModifier.FINAL),
          )
        }
        JavaPsiFieldsResult(
            params.file.normalize(), params.documentVersion, params.documentRevision,
            fields,
            params.selection.start.line, params.selection.start.column,
            params.selection.end.line, params.selection.end.column,
        )
      }

  override fun generateConstructor(
      params: JavaPsiGenerateConstructorParams,
  ): JavaPsiConstructorResult? = projectAccess.withCurrentJavaFile(
      params.file,
      params.documentVersion,
      params.documentRevision,
      params.cancelChecker,
  ) { file ->
    val content = file.text
    val index = LineIndex.from(content)
    val start = index.lineColumnToIndex(params.selection.start.line, params.selection.start.column)
    val end = index.lineColumnToIndex(params.selection.end.line, params.selection.end.column)
    val owner = classAt(file, start) ?: classAt(file, end)
        ?: return@withCurrentJavaFile JavaPsiConstructorResult(JavaPsiConstructorResultStatus.UNSUPPORTED, null)
    JavaPsiConstructorGeneration.generate(content, index, owner, params)
  }

  override fun generateAccessors(
      params: JavaPsiGenerateAccessorsParams,
  ): JavaPsiAccessorsResult? = projectAccess.withCurrentJavaFile(
      params.file,
      params.documentVersion,
      params.documentRevision,
      params.cancelChecker,
  ) { file ->
    val content = file.text
    val index = LineIndex.from(content)
    val start = index.lineColumnToIndex(params.selection.start.line, params.selection.start.column)
    val end = index.lineColumnToIndex(params.selection.end.line, params.selection.end.column)
    val owner = classAt(file, start) ?: classAt(file, end)
        ?: return@withCurrentJavaFile JavaPsiAccessorsResult(JavaPsiAccessorsResultStatus.UNSUPPORTED, null)
    JavaPsiAccessorsGeneration.generate(content, index, owner, params)
  }

  override fun generateToString(
      params: JavaPsiGenerateToStringParams,
  ): JavaPsiToStringResult? = projectAccess.withCurrentJavaFile(
      params.file,
      params.documentVersion,
      params.documentRevision,
      params.cancelChecker,
  ) { file ->
    val content = file.text
    val index = LineIndex.from(content)
    val start = index.lineColumnToIndex(params.selection.start.line, params.selection.start.column)
    val end = index.lineColumnToIndex(params.selection.end.line, params.selection.end.column)
    val owner = classAt(file, start) ?: classAt(file, end)
        ?: return@withCurrentJavaFile JavaPsiToStringResult(JavaPsiToStringResultStatus.UNSUPPORTED, null)
    JavaPsiToStringGeneration.generate(content, index, owner, params)
  }

  override fun overrideMethods(
      params: JavaPsiOverrideMethodsParams,
  ): JavaPsiOverrideMethodsResult? = projectAccess.withCurrentJavaFile(
      params.file,
      params.documentVersion,
      params.documentRevision,
      params.cancelChecker,
  ) { file ->
    JavaPsiOverrideCompletion.overrideMethods(file, params)
  }

  override fun generateOverrides(
      params: JavaPsiGenerateOverridesParams,
  ): JavaPsiOverridesResult? = projectAccess.withCurrentJavaFile(
      params.file,
      params.documentVersion,
      params.documentRevision,
      params.cancelChecker,
  ) { file ->
    JavaPsiOverrideCompletion.generateOverrides(file, params)
  }

  override fun implementAbstractMethods(
      params: JavaPsiImplementAbstractMethodsParams,
  ): JavaPsiOverridesResult? {
    val target = params.diagnostic
    if (target.documentVersion < 0 || target.documentRevision < 0L) return null
    return projectAccess.withCurrentJavaFile(
        target.file, target.documentVersion, target.documentRevision, params.cancelChecker,
    ) { file ->
      JavaPsiOverrideCompletion.implementAbstractMethods(file, params)
    }
  }
  override fun addUnhandledExceptions(
      params: JavaPsiAddUnhandledExceptionsParams,
  ): List<JavaPsiTextEdit>? {
    val target = params.diagnostic
    if (target.documentVersion < 0 || target.documentRevision < 0L) return null
    return projectAccess.withCurrentJavaFile(
        target.file, target.documentVersion, target.documentRevision, params.cancelChecker,
    ) { file ->
      JavaPsiUnhandledExceptionGeneration.generate(file, target, params.cancelChecker)
    }
  }

  override fun removeUnusedThrows(params: JavaPsiRemoveUnusedThrowsParams): JavaPsiTextEdit? {
    val target = params.diagnostic
    if (target.documentVersion < 0 || target.documentRevision < 0L) return null
    return projectAccess.withCurrentJavaFile(
        target.file, target.documentVersion, target.documentRevision, params.cancelChecker,
    ) { file ->
      JavaPsiUnusedThrowsGeneration.generate(file, target, params.cancelChecker)
    }
  }

  override fun removeUnusedImports(params: JavaPsiRemoveUnusedImportsParams): List<JavaPsiTextEdit>? {
    if (params.documentVersion < 0 || params.documentRevision < 0L) return null
    return projectAccess.withCurrentJavaFile(
        params.file, params.documentVersion, params.documentRevision, params.cancelChecker,
    ) { file ->
      withJavaDiagnosticProgress(params.cancelChecker) {
        JavaPsiUnusedImportsGeneration.generate(file, params)
      }
    }
  }

  override fun createMissingMethod(params: JavaPsiUnresolvedMethodParams): JavaPsiTextEdit? {
    val target = params.diagnostic
    if (target.documentVersion < 0 || target.documentRevision < 0L) return null
    return projectAccess.withCurrentJavaFile(
        target.file, target.documentVersion, target.documentRevision, params.cancelChecker,
    ) { file ->
      JavaPsiMissingMethodGeneration.generate(file, target, params)
    }
  }

  override fun suppressUncheckedWarning(params: JavaPsiSuppressUncheckedWarningParams): JavaPsiTextEdit? {
    val target = params.diagnostic
    if (target.documentVersion < 0 || target.documentRevision < 0L) return null
    return projectAccess.withCurrentJavaFile(
        target.file, target.documentVersion, target.documentRevision, params.cancelChecker,
    ) { file ->
      JavaPsiUncheckedWarningGeneration.generate(file, target, params.cancelChecker)
    }
  }

  override fun resolveImportCandidates(
      params: JavaPsiResolveImportCandidatesParams,
  ): List<JavaPsiImportCandidate>? {
    val target = params.diagnostic
    if (target.documentVersion < 0 || target.documentRevision < 0L) return null
    val qualifiedNames = JavaCompletionTypeIndex.qualifiedNames(params.module, target.simpleName, params.cancelChecker)
    return projectAccess.withCurrentJavaFile(
        target.file, target.documentVersion, target.documentRevision, params.cancelChecker,
    ) { file ->
      val reference = JavaPsiImports.reference(file, target) ?: return@withCurrentJavaFile null
      withJavaDiagnosticProgress(params.cancelChecker) {
        val candidates = JavaPsiImports.candidates(
            file, target.simpleName, listOf(reference), qualifiedNames, params.cancelChecker,
        )
        JavaPsiImports.candidatesWithEdits(file, target, candidates)
      }
    }
  }

  override fun fixImports(params: JavaPsiFixImportsParams): JavaPsiFixImportsResult? {
    if (params.documentVersion < 0 || params.documentRevision < 0L) return null
    val targets = projectAccess.withCurrentJavaFile(
        params.file, params.documentVersion, params.documentRevision, params.cancelChecker,
    ) { file ->
      withJavaDiagnosticProgress(params.cancelChecker) { JavaPsiImports.unresolved(file, params) }
    } ?: return null
    if (targets.types.isEmpty() && targets.members.isEmpty()) {
      return JavaPsiFixImportsResult(emptyList(), emptyList())
    }
    val typeNames = targets.types.mapTo(LinkedHashSet()) { it.simpleName }
    val nestedNames = if (typeNames.isEmpty()) emptyMap() else projectAccess.nestedTypeNames(
        params.file, params.documentVersion, params.documentRevision, typeNames, params.cancelChecker,
    ) ?: return null
    val topLevelNames = if (typeNames.isEmpty()) emptyMap() else
      JavaCompletionTypeIndex.qualifiedNames(params.module, typeNames, params.cancelChecker)
    val staticNames = targets.members.mapTo(LinkedHashSet()) { it.simpleName }
    val staticOwners = if (staticNames.isEmpty()) emptyMap() else projectAccess.staticMemberOwnerNames(
        params.file, params.documentVersion, params.documentRevision, staticNames, params.cancelChecker,
    ) ?: return null
    return projectAccess.withCurrentJavaFile(
        params.file, params.documentVersion, params.documentRevision, params.cancelChecker,
    ) { file ->
      withJavaDiagnosticProgress(params.cancelChecker) {
        JavaPsiImports.choices(file, targets.types, targets.members, topLevelNames, nestedNames, staticOwners, params.cancelChecker)
      }
    }
  }

  override fun generateImports(params: JavaPsiGenerateImportsParams): List<JavaPsiTextEdit>? {
    if (params.documentVersion < 0 || params.documentRevision < 0L) return null
    return projectAccess.withCurrentJavaFile(
        params.file, params.documentVersion, params.documentRevision, params.cancelChecker,
    ) { file ->
      withJavaDiagnosticProgress(params.cancelChecker) { JavaPsiImports.generate(file, params) }
    }
  }

  private fun classAt(file: PsiJavaFile, offset: Int): PsiClass? {
    if (offset < 0 || offset >= file.textLength) return null
    val element = file.findElementAt(offset) ?: return null
    val owner = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false) ?: return null
    return owner.takeIf { offset >= it.textRange.startOffset && offset < it.textRange.endOffset }
  }

  override fun expandSelection(params: ExpandSelectionParams): Range? =
      projectAccess.withCurrentJavaFile(
          params.file,
          params.documentVersion,
          params.documentRevision,
          params.cancelChecker,
      ) { file ->
        expandSelectionInJavaPsi(file, params.selection, params.cancelChecker)
      }

  override fun definition(params: DefinitionParams): JavaPsiDefinitionTarget? =
      projectAccess.withCurrentJavaFile(
          params.file,
          params.documentVersion,
          params.documentRevision,
          params.cancelChecker,
      ) { file ->
        val index = LineIndex.from(file.text)
        val offset = index.lineColumnToIndex(params.position.line, params.position.column)
        val target = targetAt(file, offset, params.cancelChecker) ?: return@withCurrentJavaFile null
        definitionTarget(target, params.file, params.cancelChecker)
      }

  override fun hover(params: DefinitionParams): JavaPsiHoverTarget? =
      projectAccess.withCurrentJavaFile(
          params.file,
          params.documentVersion,
          params.documentRevision,
          params.cancelChecker,
      ) { file ->
        val offset = LineIndex.from(file.text)
            .lineColumnToIndex(params.position.line, params.position.column)
        val target = targetAt(file, offset, params.cancelChecker) ?: return@withCurrentJavaFile null
        if (isKotlinSource(target.navigationElement, params.file)) {
          return@withCurrentJavaFile JavaPsiHoverTarget(
              JavaPsiHoverTarget.Kind.KOTLIN_SOURCE,
              MarkupContent(),
          )
        }
        val content = JavaPsiHover.render(target) ?: return@withCurrentJavaFile null
        JavaPsiHoverTarget(JavaPsiHoverTarget.Kind.JAVA, content)
      }

  override fun complete(
      params: CompletionParams,
      module: ModuleProject?,
      matchAllLowerCase: Boolean,
  ): CompletionResult? {
    val snapshot = FileManager.getActiveDocumentSnapshot(params.file) ?: return null
    if ((params.documentVersion >= 0 && params.documentVersion != snapshot.version) ||
        (params.documentRevision >= 0 && params.documentRevision != snapshot.revision)) return null
    val cancelChecker = params.cancelChecker
    val finish = projectAccess.withCurrentJavaFile<() -> CompletionResult>(
        params.file,
        snapshot.version,
        snapshot.revision,
        cancelChecker,
    ) { file ->
      val content = params.content
      if (content != null && !file.viewProvider.contents.contentEquals(content)) {
        return@withCurrentJavaFile { CompletionResult.EMPTY }
      }
      val offset = params.position.requireIndex()
      val imports = JavaPsiImportCompletion.collect(file, offset, cancelChecker)
      if (imports != null) return@withCurrentJavaFile { imports.finish(module, cancelChecker) }
      val methodReference = JavaPsiMethodReferenceCompletion.complete(file, offset, cancelChecker)
      if (methodReference != null) return@withCurrentJavaFile { methodReference }
      val member = JavaPsiMemberCompletion.complete(file, offset, cancelChecker)
      if (member != null) return@withCurrentJavaFile { member }
      val switchConstants = JavaPsiSwitchCompletion.complete(file, offset, cancelChecker)
      if (switchConstants != null) return@withCurrentJavaFile { switchConstants }
      val identifier = JavaPsiIdentifierCompletion.collect(file, offset, cancelChecker)
      if (identifier != null) return@withCurrentJavaFile {
        identifier.finish(params.file, module, matchAllLowerCase, cancelChecker)
      }
      val overrides = JavaPsiOverrideCompletion.complete(file, offset, cancelChecker)
          ?: return@withCurrentJavaFile null
      return@withCurrentJavaFile { overrides }
    }
    val result = finish?.invoke() ?: return null
    cancelChecker.abortIfCancelled()
    val current = FileManager.getActiveDocumentSnapshot(params.file) ?: return null
    return if (current.version == snapshot.version && current.revision == snapshot.revision) result else null
  }

  override fun signatureHelp(params: SignatureHelpParams): SignatureHelp? =
      projectAccess.withCurrentJavaFile(
          params.file,
          params.documentVersion,
          params.documentRevision,
          params.cancelChecker,
      ) { file ->
        val content = params.content
        if (content != null && !file.text.contentEquals(content)) return@withCurrentJavaFile null
        val offset = if (params.position.index >= 0) {
          params.position.index
        } else {
          LineIndex.from(file.text).lineColumnToIndex(params.position.line, params.position.column)
        }
        JavaPsiSignatureHelp.find(file, offset, params.cancelChecker) { declaration, jvmParameterCount, argumentIndex ->
          kotlinSignature(declaration, jvmParameterCount, argumentIndex, params.cancelChecker)
        }
      }

  override fun references(params: ReferenceParams): ReferenceResult? {
    val target = projectAccess.withCurrentJavaFile(
        params.file,
        params.documentVersion,
        params.documentRevision,
        params.cancelChecker,
    ) { requestFile ->
      val index = LineIndex.from(requestFile.text)
      val offset = index.lineColumnToIndex(params.position.line, params.position.column)
      val element = targetAt(requestFile, offset, params.cancelChecker) ?: return@withCurrentJavaFile null
      if (element !is PsiField && element !is PsiMethod && element !is PsiClass &&
          element !is PsiEnumConstant && element !is PsiParameter && element !is PsiLocalVariable) {
        return@withCurrentJavaFile null
      }
      val navigation = element.navigationElement
      if (isKotlinSource(navigation, params.file)) return@withCurrentJavaFile null
      val declarationFile = navigation.containingFile as? PsiJavaFile ?: return@withCurrentJavaFile null
      val declarationPath = projectAccess.sourcePath(declarationFile) ?: return@withCurrentJavaFile null
      if (!projectAccess.isProjectSource(declarationPath)) return@withCurrentJavaFile null
      if (element is PsiLocalVariable && declarationPath.normalize() != params.file.normalize()) {
        return@withCurrentJavaFile null
      }
      referenceTarget(element, navigation, declarationPath)
    } ?: return null

    val sourceFiles = projectAccess.sourceFilesFor(target.path)
    val hits = LinkedHashMap<JavaReferenceKey, JavaReferenceHit>()
    val completed = projectAccess.withJavaSourceFiles(
        params.file,
        params.documentVersion,
        params.documentRevision,
        sourceFiles,
        params.cancelChecker,
    ) { files ->
      val declarationFile = files.file(target.path) ?: return@withJavaSourceFiles null
      val declarationLeaf = declarationFile.findElementAt(target.declarationOffset)
          ?: return@withJavaSourceFiles null
      val declaredAtName = declarationAt(
          declarationFile,
          declarationLeaf,
          target.declarationOffset,
      ) ?: return@withJavaSourceFiles null
      val currentDeclaration = if (target.constructor) {
        val ownerClass = declaredAtName as? PsiClass ?: return@withJavaSourceFiles null
        val ownerName = ownerClass.name ?: return@withJavaSourceFiles null
        ownerClass.findMethodsByName(ownerName, true)
            .firstOrNull { it.isConstructor && referenceIdentity(it) == target.identity }
            ?: return@withJavaSourceFiles null
      } else {
        declaredAtName
      }
      if (referenceIdentity(currentDeclaration) != target.identity) return@withJavaSourceFiles null
      val currentNavigation = currentDeclaration.navigationElement
      val currentName = declarationNameElement(currentDeclaration, currentNavigation) ?: currentNavigation
      val declarationRange = currentName.textRange
      if (params.includeDeclaration) {
        val location = location(target.path, declarationFile, currentName)
        hits[JavaReferenceKey.of(location)] = JavaReferenceHit(location, ReferenceRole.DEFINITION)
      }
      files.forEach(
          include = { path, content ->
            path.normalize() == params.file.normalize() || referenceNames(currentDeclaration).any(content::contains)
          },
          action = { path, psiFile ->
            scanReferences(
                path,
                psiFile,
                target.path,
                declarationRange.startOffset,
                declarationRange.endOffset,
                currentDeclaration,
                hits,
                params.cancelChecker,
            )
          },
      )
    } ?: return null
    if (!completed) return null
    val sorted = hits.values.sortedWith(
        compareBy<JavaReferenceHit>(
            { it.location.file.toString() },
            { it.location.range.start.line },
            { it.location.range.start.column },
            { it.location.range.end.line },
            { it.location.range.end.column },
        ),
    )
    return ReferenceResult(sorted.map { it.location }, sorted.map { it.role })
  }

  private fun referenceTarget(
      target: PsiElement,
      navigation: PsiElement,
      path: Path,
  ): ReferenceTarget? {
    val name = declarationNameElement(target, navigation) ?: return null
    return ReferenceTarget(
        path.normalize(),
        name.textRange.startOffset,
        target is PsiMethod && target.isConstructor,
        referenceIdentity(target),
    )
  }

  private fun referenceIdentity(target: PsiElement): String = when (target) {
    is PsiClass -> "class:${target.navigationElement.let { (it as? PsiClass)?.qualifiedName ?: target.name }}"
    is PsiMethod -> {
      val method = target.navigationElement as? PsiMethod ?: target
      val owner = method.containingClass?.navigationElement as? PsiClass ?: method.containingClass
      val name = if (method.isConstructor) "<init>" else method.name
      val parameters = method.parameterList.parameters.joinToString(",") { it.type.canonicalText }
      "method:${owner?.qualifiedName}:$name($parameters)"
    }
    is PsiEnumConstant -> "enum:${target.containingClass?.qualifiedName}:${target.name}"
    is PsiField -> "field:${target.containingClass?.qualifiedName}:${target.name}"
    is PsiParameter -> "parameter:${target.name}:${target.type.canonicalText}:${target.textRange.startOffset}"
    is PsiLocalVariable -> "local:${target.name}:${target.type.canonicalText}:${target.textRange.startOffset}"
    else -> "unsupported:${target.javaClass.name}"
  }

  private data class ReferenceTarget(
      val path: Path,
      val declarationOffset: Int,
      val constructor: Boolean,
      val identity: String,
  )


  private fun referenceNames(target: PsiElement): Set<String> {
    val names = linkedSetOf<String>()
    when (target) {
      is PsiClass -> target.name?.let(names::add)
      is PsiMethod -> {
        target.name?.let(names::add)
        target.containingClass?.name?.let(names::add)
      }
      is PsiField -> target.name?.let(names::add)
      is PsiParameter -> target.name?.let(names::add)
      is PsiLocalVariable -> target.name?.let(names::add)
      is PsiEnumConstant -> target.name?.let(names::add)
    }
    return names
  }

  private fun scanReferences(
      path: Path,
      file: PsiJavaFile,
      declarationPath: Path,
      declarationStart: Int,
      declarationEnd: Int,
      target: PsiElement,
      hits: MutableMap<JavaReferenceKey, JavaReferenceHit>,
      cancelChecker: ICancelChecker,
  ) {
    val lineIndex = LineIndex.from(file.text)
    val textLength = file.textLength
    val targetIdentity = referenceIdentity(target)
    file.accept(
        object : JavaRecursiveElementVisitor() {
          override fun visitElement(element: PsiElement) {
            cancelChecker.abortIfCancelled()
            super.visitElement(element)
          }

          override fun visitReferenceExpression(expression: PsiReferenceExpression) {
            record(expression, expression.reference, expression.referenceNameElement)
            super.visitReferenceExpression(expression)
          }

          override fun visitNewExpression(expression: PsiNewExpression) {
            recordResolved(
                expression,
                expression.resolveConstructor(),
                expression.classReference?.referenceNameElement,
            )
            super.visitNewExpression(expression)
          }

          override fun visitReferenceElement(element: PsiJavaCodeReferenceElement) {
            record(element, element.reference, element.referenceNameElement)
            super.visitReferenceElement(element)
          }

          private fun record(
              owner: PsiElement,
              reference: PsiReference?,
              nameElement: PsiElement?,
          ) {
            cancelChecker.abortIfCancelled()
            recordResolved(owner, reference?.resolve(), nameElement)
          }

          private fun recordResolved(
              owner: PsiElement,
              resolved: PsiElement?,
              nameElement: PsiElement?,
          ) {
            cancelChecker.abortIfCancelled()
            if (resolved == null || referenceIdentity(resolved) != targetIdentity) return
            val resolvedNavigation = resolved.navigationElement
            val resolvedFile = resolvedNavigation.containingFile as? PsiJavaFile ?: return
            if (projectAccess.sourcePath(resolvedFile)?.normalize() != declarationPath.normalize()) return
            val resolvedName = declarationNameElement(resolved, resolvedNavigation) ?: resolvedNavigation
            val resolvedRange = resolvedName.textRange
            if (resolvedRange.startOffset != declarationStart || resolvedRange.endOffset != declarationEnd) return
            val referenceName = nameElement ?: owner
            val range = referenceName.textRange
            val start = range.startOffset.coerceIn(0, textLength)
            val end = range.endOffset.coerceIn(start, textLength)
            val location = Location(
                path.normalize(),
                Range(lineIndex.indexToPosition(start), lineIndex.indexToPosition(end)),
            )
            val key = JavaReferenceKey.of(location)
            hits.putIfAbsent(key, JavaReferenceHit(location, ReferenceRole.USAGE))
          }
        },
    )
  }

  private fun targetAt(file: PsiJavaFile, offset: Int, cancelChecker: ICancelChecker): PsiElement? {
    if (file.textLength == 0) return null
    val adjustedOffset = offset.coerceIn(0, file.textLength - 1)
    val leaf = file.findElementAt(adjustedOffset) ?: return null
    val reference = referenceAt(file, offset, adjustedOffset)
    val resolved = reference?.let { resolve(it, cancelChecker) }
    val target = adjustConstructorTarget(reference, resolved, cancelChecker)
        ?: adjustThisOrSuperTarget(leaf, cancelChecker)
        ?: resolved
        ?: declarationAt(file, leaf, offset)
        ?: return null
    return target
  }

  private fun referenceAt(file: PsiJavaFile, offset: Int, adjustedOffset: Int): PsiReference? {
    val reference = file.findReferenceAt(adjustedOffset) ?: return null
    val elementRange = reference.element.textRange
    val referenceRange = reference.rangeInElement.shiftRight(elementRange.startOffset)
    return reference.takeIf { referenceRange.containsOffset(offset) }
  }

  private fun resolve(reference: PsiReference, cancelChecker: ICancelChecker): PsiElement? {
    cancelChecker.abortIfCancelled()
    if (reference is PsiPolyVariantReference) {
      val results = reference.multiResolve(false).filter { it.isValidResult }.mapNotNull { it.element }
      if (results.size == 1) return results.single()
      if (results.isNotEmpty()) return results.first()
    }
    return reference.resolve()
  }

  private fun adjustConstructorTarget(
      reference: PsiReference?,
      resolved: PsiElement?,
      cancelChecker: ICancelChecker,
  ): PsiElement? {
    if (reference == null || resolved == null) return null
    val referenceElement = reference.element
    val parent = referenceElement.parent
    val newExpression = when {
      parent is PsiNewExpression -> parent
      parent.parent is PsiNewExpression -> parent.parent as PsiNewExpression
      else -> null
    } ?: return null
    if (newExpression.isArrayCreation || referenceElement === newExpression.qualifier) return null
    cancelChecker.abortIfCancelled()
    val constructor = newExpression.resolveConstructor()
    if (constructor != null) return constructor
    return resolved.takeUnless { it is PsiClass }
  }

  private fun adjustThisOrSuperTarget(leaf: PsiElement, cancelChecker: ICancelChecker): PsiElement? {
    val expression = leaf.parent
    val isThis = expression is PsiThisExpression
    if (!isThis && expression !is PsiSuperExpression) return null
    val qualifier = when (expression) {
      is PsiThisExpression -> expression.qualifier
      is PsiSuperExpression -> expression.qualifier
      else -> null
    }
    qualifier?.resolve()?.let { return it }
    cancelChecker.abortIfCancelled()
    val owner = PsiTreeUtil.getParentOfType(expression, PsiClass::class.java, false) ?: return null
    return if (isThis) owner else owner.superClass
  }

  private fun declarationAt(file: PsiJavaFile, leaf: PsiElement, offset: Int): PsiElement? {
    var element: PsiElement? = leaf
    while (element != null) {
      if (element is PsiNameIdentifierOwner) {
        val identifierRange = element.nameIdentifier?.textRange
        if (identifierRange != null && identifierRange.containsOffset(offset)) return element
      }
      element = element.parent
    }
    if (file.packageStatement?.textRange?.containsOffset(offset) == true) return file.packageStatement
    return null
  }

  private fun definitionTarget(
      target: PsiElement,
      requestPath: Path,
      cancelChecker: ICancelChecker,
  ): JavaPsiDefinitionTarget? {
    val navigation = target.navigationElement
    if (isKotlinSource(navigation, requestPath)) {
      return JavaPsiDefinitionTarget(JavaPsiDefinitionTarget.Kind.KOTLIN_SOURCE, emptyList())
    }
    val javaFile = navigation.containingFile as? PsiJavaFile ?: return null
    val path = projectAccess.sourcePath(javaFile) ?: return null
    val nameElement = declarationNameElement(target, navigation) ?: navigation
    if (navigation is PsiEnumConstant || target is PsiEnumConstant) {
      return JavaPsiDefinitionTarget(
          JavaPsiDefinitionTarget.Kind.JAVA_SOURCE,
          listOf(location(path, javaFile, nameElement)),
      )
    }
    if (target is PsiParameter) {
      return JavaPsiDefinitionTarget(
          JavaPsiDefinitionTarget.Kind.JAVA_SOURCE,
          listOf(location(path, javaFile, nameElement)),
      )
    }
    if (target is PsiField || target is PsiMethod || target is PsiClass) {
      val targetClass = when (target) {
        is PsiClass -> target
        is PsiMethod -> target.containingClass
        else -> (target as PsiField).containingClass
      }
      if (targetClass != null && targetClass.qualifiedName != null && isInheritorMember(target)) {
        val sourceClass = targetClass.navigationElement as? PsiClass
        val declaredMember = sourceClass?.let { declarationForMember(it, target) }
        if (declaredMember != null) {
          val sourceFile = declaredMember.containingFile as? PsiJavaFile ?: javaFile
          val sourcePath = projectAccess.sourcePath(sourceFile) ?: path
          return JavaPsiDefinitionTarget(
              JavaPsiDefinitionTarget.Kind.JAVA_SOURCE,
              listOf(location(sourcePath, sourceFile, declarationNameElement(declaredMember, declaredMember) ?: declaredMember)),
          )
        }
      }
    }
    cancelChecker.abortIfCancelled()
    return JavaPsiDefinitionTarget(
        JavaPsiDefinitionTarget.Kind.JAVA_SOURCE,
        listOf(location(path, javaFile, nameElement)),
    )
  }

  private fun isKotlinSource(element: PsiElement, requestPath: Path): Boolean {
    val ktFile = element.containingFile as? KtFile ?: return false
    return projectAccess.sourcePath(ktFile)?.normalize() != requestPath.normalize()
  }

  private fun isInheritorMember(element: PsiElement): Boolean {
    val containingClass = when (element) {
      is PsiMethod -> element.containingClass
      is PsiField -> element.containingClass
      else -> null
    } ?: return false
    return containingClass.navigationElement !== containingClass
  }

  private fun declarationForMember(sourceClass: PsiClass, target: PsiElement): PsiElement? = when (target) {
    is PsiMethod -> sourceClass.findMethodsByName(target.name, false)
        .firstOrNull { sameMethodSignature(it, target) }
    is PsiField -> sourceClass.findFieldByName(target.name, false)
    else -> null
  }

  private fun sameMethodSignature(first: PsiMethod, second: PsiMethod): Boolean {
    val firstParameters = first.parameterList.parameters
    val secondParameters = second.parameterList.parameters
    return firstParameters.size == secondParameters.size &&
        firstParameters.indices.all { firstParameters[it].type == secondParameters[it].type }
  }

  private fun declarationNameElement(target: PsiElement, navigation: PsiElement): PsiElement? {
    if (target is PsiMethod && target.isConstructor) {
      return (target.containingClass?.navigationElement as? PsiNameIdentifierOwner)?.nameIdentifier
    }
    return (navigation as? PsiNameIdentifierOwner)?.nameIdentifier
  }

  private fun location(path: Path, file: PsiJavaFile, element: PsiElement): Location {
    val range = element.textRange
    val index = LineIndex.from(file.text)
    return Location(
        path.normalize(),
        Range(index.indexToPosition(range.startOffset), index.indexToPosition(range.endOffset)),
    )
  }

  private data class JavaReferenceKey(
      val path: Path,
      val startLine: Int,
      val startColumn: Int,
      val endLine: Int,
      val endColumn: Int,
  ) {
    companion object {
      fun of(location: Location): JavaReferenceKey = JavaReferenceKey(
          location.file.normalize(),
          location.range.start.line,
          location.range.start.column,
          location.range.end.line,
          location.range.end.column,
      )
    }
  }

  private data class JavaReferenceHit(
      val location: Location,
      val role: ReferenceRole,
  )
}