package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.diagnostics.JavaAbstractMethodsDiagnostic
import com.tom.rv2ide.language.services.java.interop.JavaPsiGenerateOverridesParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiImplementAbstractMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodInfo
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverrideMethodsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiOverridesResultStatus
import com.tom.rv2ide.language.services.java.interop.JavaPsiTextEdit
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.LineIndex
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.TextEdit
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.codeInsight.generation.OverrideImplementExploreUtil
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassInitializer
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiPrimitiveType
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType
import org.jetbrains.kotlin.com.intellij.psi.util.MethodSignatureUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil

internal object JavaPsiOverrideCompletion {
  fun overrideMethods(
      file: PsiJavaFile,
      params: JavaPsiOverrideMethodsParams,
  ): JavaPsiOverrideMethodsResult? {
    val cancelChecker = params.cancelChecker
    val index = LineIndex.from(file.text)
    val start = index.lineColumnToIndex(params.selection.start.line, params.selection.start.column)
    val end = index.lineColumnToIndex(params.selection.end.line, params.selection.end.column)
    val owner = classAt(file, start) ?: classAt(file, end)
    val methods = if (owner == null || owner.isAnnotationType || owner.rBrace == null) emptyList() else {
      overrideCandidates(owner, cancelChecker, excludeObjectMethods = true)
          .map { candidate ->
            cancelChecker.abortIfCancelled()
            JavaPsiOverrideMethodInfo(candidate.id(), candidate.label())
          }
          .sortedWith(compareBy<JavaPsiOverrideMethodInfo> { it.label }.thenBy { it.id })
    }
    return JavaPsiOverrideMethodsResult(
        params.file.normalize(), params.documentVersion, params.documentRevision, methods,
        params.selection.start.line, params.selection.start.column,
        params.selection.end.line, params.selection.end.column,
    )
  }

  fun generateOverrides(
      file: PsiJavaFile,
      params: JavaPsiGenerateOverridesParams,
  ): JavaPsiOverridesResult {
    val cancelChecker = params.cancelChecker
    cancelChecker.abortIfCancelled()
    if (params.selectedMethodIds.isEmpty()) {
      return JavaPsiOverridesResult(JavaPsiOverridesResultStatus.EMPTY_SELECTION, emptyList())
    }
    val content = file.text
    val index = LineIndex.from(content)
    val start = index.lineColumnToIndex(params.selection.start.line, params.selection.start.column)
    val end = index.lineColumnToIndex(params.selection.end.line, params.selection.end.column)
    val owner = classAt(file, start) ?: classAt(file, end) ?: return unsupported()
    val candidates = overrideCandidates(owner, cancelChecker, excludeObjectMethods = true)
    val selected = candidates.filter { it.id() in params.selectedMethodIds }
    if (selected.size != params.selectedMethodIds.size) return unsupported()
    return generateAtOwner(
        file, owner, params.file.normalize(), selected, params.selectedMethodIds, params.documentVersion,
        params.documentRevision, params.indentationTabSize, params.useSoftTabs, cancelChecker,
    )
  }

  fun implementAbstractMethods(
      file: PsiJavaFile,
      params: JavaPsiImplementAbstractMethodsParams,
  ): JavaPsiOverridesResult {
    val diagnostic = params.diagnostic
    val owner = classAtDiagnostic(file, diagnostic) ?: return unsupported()
    if (owner.isAnnotationType || owner.isInterface || owner.isEnum || owner.isRecord ||
        owner.hasModifierProperty(PsiModifier.ABSTRACT)) return unsupported()
    val targetContext = if (owner is PsiAnonymousClass) owner.baseClassReference else owner.nameIdentifier ?: owner
    val candidates = implementCandidates(owner, targetContext, params.cancelChecker)
    if (candidates.isEmpty()) return JavaPsiOverridesResult(JavaPsiOverridesResultStatus.EMPTY_SELECTION, emptyList())
    return generateAtOwner(
        file, owner, diagnostic.file.normalize(), candidates, candidates.map { it.id() }.toSet(),
        diagnostic.documentVersion, diagnostic.documentRevision, params.indentationTabSize,
        params.useSoftTabs, params.cancelChecker,
    )
  }

  private fun generateAtOwner(
      file: PsiJavaFile,
      owner: PsiClass,
      path: java.nio.file.Path,
      candidates: List<Candidate>,
      selectedIds: Set<String>,
      documentVersion: Int,
      documentRevision: Long,
      indentationTabSize: Int,
      useSoftTabs: Boolean,
      cancelChecker: ICancelChecker,
  ): JavaPsiOverridesResult {
    val content = file.text
    val index = LineIndex.from(content)
    val brace = owner.rBrace ?: return unsupported()
    val imports = ImportContext(file, Context(owner, brace, ""))
    val separatorOffset = content.indexOfFirst { it == '\r' || it == '\n' }
    val newline = when {
      separatorOffset < 0 -> "\n"
      content[separatorOffset] == '\r' && content.getOrNull(separatorOffset + 1) == '\n' -> "\r\n"
      else -> content[separatorOffset].toString()
    }
    val unit = if (useSoftTabs) " ".repeat(indentationTabSize) else "\t"
    val ownerStart = owner.textRange.startOffset
    val ownerIndent = content.substring(lineStart(content, ownerStart), ownerStart).takeWhile { it == ' ' || it == '\t' }
    val memberIndent = ownerIndent + unit
    val braceOffset = brace.textRange.startOffset
    val braceLineStart = lineStart(content, braceOffset)
    val braceOnOwnLine = (braceLineStart until braceOffset).all { content[it] == ' ' || content[it] == '\t' }
    val bodyOffset = if (braceOnOwnLine) braceLineStart else braceOffset
    val byId = candidates.associateBy { it.id() }
    val methodText = buildString {
      for (id in selectedIds) {
        cancelChecker.abortIfCancelled()
        val candidate = byId[id] ?: return unsupported()
        val stub = Stub(candidate, imports, cancelChecker).render(unit, newline) ?: return unsupported()
        append(newline)
        for (line in stub.lineSequence()) append(memberIndent).append(line).append(newline)
      }
      if (!braceOnOwnLine) append(ownerIndent)
    }
    fun insertion(offset: Int, text: String): JavaPsiTextEdit {
      val position = index.indexToPosition(offset)
      return JavaPsiTextEdit(
          path, documentVersion, documentRevision,
          position.line, position.column, position.line, position.column, text,
      )
    }
    val memberEdit = insertion(bodyOffset, methodText)
    val importText = imports.editText(newline)
    val edits = if (importText == null) listOf(memberEdit)
    else listOf(memberEdit, insertion(imports.offset, importText))
    return JavaPsiOverridesResult(JavaPsiOverridesResultStatus.GENERATED, edits)
  }

  private fun overrideCandidates(
      owner: PsiClass,
      cancelChecker: ICancelChecker,
      excludeObjectMethods: Boolean = false,
  ): List<Candidate> {
    val candidates = ArrayList<Candidate>()
    val seen = MethodSignatureUtil.createErasedMethodSignatureSet()
    for (toImplement in booleanArrayOf(true, false)) {
      for ((signature, resolved) in OverrideImplementExploreUtil.getMapToOverrideImplement(owner, toImplement)) {
        cancelChecker.abortIfCancelled()
        val method = resolved.element as? PsiMethod ?: continue
        val declaringClass = method.containingClass ?: continue
        if (method.isConstructor || declaringClass == owner || !PsiUtil.canBeOverridden(method)) continue
        if (excludeObjectMethods && declaringClass.qualifiedName == "java.lang.Object") continue
        if (!seen.add(signature)) continue
        candidates += Candidate(method, declaringClass, resolved.substitutor, signature.isRaw, MatchLevel.CASE_SENSITIVE_EQUAL)
      }
    }
    return candidates
  }

  private fun Candidate.id(): String {
    val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
      val type = if (raw) TypeConversionUtil.erasure(parameter.type, substitutor)
          else substitutor.substitute(parameter.type) ?: parameter.type
      TypeConversionUtil.erasure(type).canonicalText
    }
    return "${owner.qualifiedName ?: owner.name.orEmpty()}#${method.name}($parameters)"
  }

  private fun Candidate.label(): String =
      method.parameterList.parameters.joinToString(", ", "${method.name}(", ")") { parameter ->
        val type = if (raw) TypeConversionUtil.erasure(parameter.type, substitutor)
            else substitutor.substitute(parameter.type) ?: parameter.type
        "${type.presentableText} ${parameter.name.orEmpty()}"
      }

  private fun implementCandidates(
      owner: PsiClass,
      context: PsiElement,
      cancelChecker: ICancelChecker,
  ): List<Candidate> {
    val candidates = ArrayList<Candidate>()
    val seen = MethodSignatureUtil.createErasedMethodSignatureSet()
    val map = OverrideImplementExploreUtil.getMapToOverrideImplement(owner, true)
    for ((signature, resolved) in map) {
      cancelChecker.abortIfCancelled()
      val method = resolved.element as? PsiMethod ?: continue
      val declaringClass = method.containingClass ?: continue
      if (method.isConstructor || declaringClass == owner ||
          !method.hasModifierProperty(PsiModifier.ABSTRACT) ||
          declaringClass.qualifiedName == "java.lang.Record" ||
          !PsiUtil.canBeOverridden(method) ||
          !PsiUtil.isAccessible(method, context, owner)) continue
      if (!seen.add(signature)) continue
      candidates += Candidate(
          method, declaringClass, resolved.substitutor, signature.isRaw, MatchLevel.CASE_SENSITIVE_EQUAL,
      )
    }
    return candidates
  }
  private fun classAtDiagnostic(
      file: PsiJavaFile,
      diagnostic: JavaAbstractMethodsDiagnostic,
  ): PsiClass? {
    val start = diagnostic.highlightStart
    if (diagnostic.targetOffset != start || start !in 0 until file.textLength ||
        diagnostic.highlightEnd !in start..file.textLength) return null
    val leaf = file.findElementAt(start) ?: return null
    val owner = if (diagnostic.anonymous) {
      PsiTreeUtil.getParentOfType(leaf, PsiAnonymousClass::class.java, false) ?: return null
    } else {
      PsiTreeUtil.getParentOfType(leaf, PsiClass::class.java, false) ?: return null
    }
    if (diagnostic.highlightEnd > owner.textRange.endOffset) return null
    if (diagnostic.backend == JavaAbstractMethodsDiagnostic.Backend.CODESERVER) {
      if (owner is PsiAnonymousClass) {
        val reference = owner.baseClassReference.textRange
        val baseName = owner.baseClassType.resolve()?.qualifiedName ?: return null
        if (reference.startOffset != start || reference.endOffset != diagnostic.highlightEnd ||
            baseName != diagnostic.baseTypeName) return null
      } else {
        val bodyStart = owner.lBrace?.textRange?.startOffset ?: return null
        val declarationEnd = owner.implementsList?.textRange?.endOffset
            ?: owner.nameIdentifier?.textRange?.endOffset ?: return null
        val targetName = diagnostic.targetName ?: return null
        if (owner.name != targetName && owner.qualifiedName != targetName) return null
        if (start !in owner.textRange.startOffset until bodyStart || diagnostic.highlightEnd != declarationEnd) return null
      }
      return owner
    }
    if (owner is PsiAnonymousClass) {
      val baseName = owner.baseClassType.resolve()?.qualifiedName ?: return null
      if (baseName != diagnostic.baseTypeName) return null
      return owner
    }
    val targetName = diagnostic.targetName ?: return null
    if (owner.name != targetName && owner.qualifiedName != targetName) return null
    return owner
  }


  private fun unsupported() = JavaPsiOverridesResult(JavaPsiOverridesResultStatus.UNSUPPORTED, emptyList())

  private fun classAt(file: PsiJavaFile, offset: Int): PsiClass? {
    if (offset < 0 || offset >= file.textLength) return null
    val element = file.findElementAt(offset) ?: return null
    val owner = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false) ?: return null
    return owner.takeIf { offset >= it.textRange.startOffset && offset < it.textRange.endOffset }
  }

  private fun lineStart(content: String, offset: Int): Int =
      maxOf(content.lastIndexOf('\n', offset - 1), content.lastIndexOf('\r', offset - 1)) + 1

  fun complete(
      file: PsiJavaFile,
      offset: Int,
      cancelChecker: ICancelChecker,
  ): CompletionResult? {
    val context = contextAt(file, offset) ?: return null
    val owner = context.owner
    if (owner.isAnnotationType) return null
    val candidates = ArrayList<Candidate>()
    val seen = MethodSignatureUtil.createErasedMethodSignatureSet()
    for (toImplement in booleanArrayOf(true, false)) {
      for ((signature, resolved) in OverrideImplementExploreUtil.getMapToOverrideImplement(owner, toImplement)) {
        cancelChecker.abortIfCancelled()
        val method = resolved.element as? PsiMethod ?: continue
        val declaringClass = method.containingClass ?: continue
        if (method.isConstructor || declaringClass == owner || !PsiUtil.canBeOverridden(method)) continue
        val match = CompletionItem.matchLevel(method.name, context.prefix)
        if (match == MatchLevel.NO_MATCH || !seen.add(signature)) continue
        candidates += Candidate(method, declaringClass, resolved.substitutor, signature.isRaw, match)
      }
    }
    if (candidates.isEmpty()) return null

    candidates.sortWith(compareBy<Candidate> { it.match.ordinal }.thenBy { it.method.name })
    val count = if (CompletionResult.TRIM_TO_MAX) minOf(candidates.size, CompletionResult.MAX_ITEMS) else candidates.size
    val items = ArrayList<CompletionItem>(count)
    val lineIndex by lazy(LazyThreadSafetyMode.NONE) { LineIndex.from(file.text) }
    for (index in 0 until count) {
      cancelChecker.abortIfCancelled()
      val candidate = candidates[index]
      val imports = ImportContext(file, context)
      val text = Stub(candidate, imports, cancelChecker).render() ?: continue
      items += JavaPsiCompletionItems.method(candidate.method, candidate.owner, candidate.substitutor, candidate.match).apply {
        insertText = text
        insertTextFormat = InsertTextFormat.SNIPPET
        snippetDescription = describeSnippet(context.prefix)
        imports.editText("\n")?.let { importText ->
          val position = lineIndex.indexToPosition(imports.offset)
          additionalTextEdits = listOf(TextEdit(Range.pointRange(position), importText))
        }
      }
    }
    if (items.isEmpty()) return null
    return CompletionResult(items).apply { isIncomplete = count < candidates.size }
  }

  private fun contextAt(file: PsiJavaFile, offset: Int): Context? {
    if (offset !in 1..file.textLength) return null
    val leaf = file.findElementAt(offset - 1) ?: return null
    val token = if (leaf is PsiWhiteSpace) PsiTreeUtil.prevCodeLeaf(leaf) as? PsiJavaToken else leaf as? PsiJavaToken
    val prefix = when {
      token?.tokenType == JavaTokenType.IDENTIFIER && token.textRange.startOffset < offset && offset <= token.textRange.endOffset ->
        token.text.substring(0, offset - token.textRange.startOffset)
      leaf is PsiWhiteSpace && token != null && offset >= leaf.textRange.startOffset &&
          offset <= leaf.textRange.endOffset -> ""
      leaf is PsiJavaToken && leaf.tokenType in memberBoundaries && leaf.textRange.endOffset == offset -> ""
      else -> return null
    }
    val contextToken = token ?: return null
    val place: PsiElement = if (leaf is PsiWhiteSpace) leaf else contextToken
    val owner = PsiTreeUtil.getParentOfType(place, PsiClass::class.java, false) ?: return null
    val leftBrace = owner.lBrace ?: return null
    if (offset < leftBrace.textRange.endOffset || offset > (owner.rBrace?.textOffset ?: owner.textRange.endOffset)) return null
    var element: PsiElement? = place
    while (element != null && element !== owner) {
      when (element) {
        is PsiCodeBlock -> if (element.parent is PsiMethod || element.parent is PsiClassInitializer) return null
        is PsiMethod -> return null
        is PsiField -> {
          if (element.nameIdentifier === place) return null
          if (element.initializer?.let { PsiTreeUtil.isAncestor(it, place, false) } == true) return null
        }
        is PsiClass -> return null
        is PsiJavaCodeReferenceElement -> if (element.isQualified) return null
      }
      element = element.parent
    }
    if (element !== owner) return null
    val previous: PsiJavaToken = if (prefix.isEmpty()) contextToken else PsiTreeUtil.prevCodeLeaf(place) as? PsiJavaToken
        ?: return null
    if (previous.tokenType !in memberBoundaries) return null
    val next = PsiTreeUtil.nextCodeLeaf(leaf)
    if (next != null && next !is PsiErrorElement && next !is PsiJavaToken) return null
    return Context(owner, contextToken, prefix)
  }

  private class Stub(
      private val candidate: Candidate,
      private val context: ImportContext,
      private val cancelChecker: ICancelChecker,
  ) {
    private val method = candidate.method
    private val methodTypeParameters = if (candidate.raw) PsiTypeParameter.EMPTY_ARRAY else method.typeParameters
    private val names = HashSet<String>()
    private fun substitute(type: PsiType): PsiType =
        if (candidate.raw) TypeConversionUtil.erasure(type, candidate.substitutor)
        else candidate.substitutor.substitute(type) ?: type

    fun render(unit: String = "    ", newline: String = "\n"): String? {
      val returnType = substitute(method.returnType ?: return null)
      val parameters = method.parameterList.parameters
      val parameterNames = parameters.mapIndexed { index, parameter -> parameter.name ?: "p$index" }
      val parameterTypes = parameters.map { substitute(it.type) }
      val bounds = methodTypeParameters.map { parameter -> parameter.extendsList.referencedTypes.map(::substitute) }
      val abstractMethod = method.hasModifierProperty(PsiModifier.ABSTRACT)
      val delegate = !context.context.owner.isInterface && !candidate.owner.isInterface && !abstractMethod
      val throwsTypes = method.throwsList.referencedTypes.map(::substitute)
      (context.context.owner.typeParameters.asList() + methodTypeParameters).mapNotNullTo(names) { it.name }
      val annotation = "@Override"
      val visibility = when {
        context.context.owner.isInterface || candidate.owner.isInterface || method.hasModifierProperty(PsiModifier.PUBLIC) -> "public "
        method.hasModifierProperty(PsiModifier.PROTECTED) -> "protected "
        else -> ""
      }
      val typeParameters = if (methodTypeParameters.isEmpty()) "" else {
        methodTypeParameters.indices.joinToString(", ", "<", "> ") { index ->
          val parameter = methodTypeParameters[index]
          val name = parameter.name.orEmpty()
          val constraint = bounds[index].filterNot { it.equalsToText("java.lang.Object") }
          names += name
          if (constraint.isEmpty()) name else name + constraint.joinToString(" & ", " extends ", transform = ::typeText)
        }
      }
      val args = parameters.indices.joinToString(", ") { index ->
        val parameter = parameters[index]
        val type = parameterTypes[index]
        val rendered = if (parameter.isVarArgs && type is PsiArrayType) "${typeText(type.componentType)}..." else typeText(type)
        "$rendered ${parameterNames[index]}"
      }
      val throwsClause = throwsTypes.takeIf { it.isNotEmpty() }
          ?.joinToString(", ", " throws ", transform = ::typeText).orEmpty()
      val signature = "$visibility$typeParameters${typeText(returnType)} ${method.name}($args)$throwsClause"
      val text = if (context.context.owner.isInterface && abstractMethod) {
        "$annotation$newline$signature;"
      } else {
        val isVoid = returnType == PsiTypes.voidType()
        val body = if (delegate) {
          val call = parameterNames.joinToString(", ", "super.${method.name}(", ");")
          if (isVoid) call else "return $call"
        } else if (isVoid) {
          "// TODO: Implement this method"
        } else {
          defaultBody(returnType)
        }
        val declaration = if (context.context.owner.isInterface) "default $signature" else signature
        "$annotation$newline$declaration {$newline${unit}$body$newline}"
      }
      return text
    }

    private fun typeText(type: PsiType): String {
      cancelChecker.abortIfCancelled()
      return when (type) {
        is PsiArrayType -> "${typeText(type.componentType)}[]"
        is PsiWildcardType -> type.bound?.let { "? ${if (type.isExtends) "extends" else "super"} ${typeText(it)}" } ?: "?"
        is PsiClassType -> {
          val resolved = type.resolve() ?: return type.canonicalText
          val qualifiedName = resolved.qualifiedName
          val name = resolved.name
          if (resolved is PsiTypeParameter || qualifiedName == null || name == null) {
            type.canonicalText
          } else if (resolved.containingClass != null && !resolved.hasModifierProperty(PsiModifier.STATIC)) {
            type.canonicalText
          } else {
            val renderedName = if (name in names) qualifiedName else context.referenceName(name, qualifiedName, resolved)
            val arguments = type.parameters
            renderedName + if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">", transform = ::typeText)
          }
        }
        else -> type.canonicalText
      }
    }
  }

  private fun defaultBody(type: PsiType): String = when (type) {
    PsiTypes.booleanType() -> "return false;"
    PsiTypes.charType() -> "return '\\0';"
    PsiTypes.longType() -> "return 0L;"
    PsiTypes.floatType() -> "return 0f;"
    PsiTypes.doubleType() -> "return 0d;"
    is PsiPrimitiveType -> "return 0;"
    else -> "return null;"
  }

  private class ImportContext(private val file: PsiJavaFile, val context: Context) {
    private val resolveHelper = JavaPsiFacade.getInstance(file.project).resolveHelper
    private val visible = HashMap<String, PsiClass?>()
    private val imports = LinkedHashMap<String, String>()
    private val importStatements by lazy(LazyThreadSafetyMode.NONE) { file.importList?.importStatements.orEmpty() }
    private val explicitImports by lazy(LazyThreadSafetyMode.NONE) {
      importStatements.filterNot { it.isOnDemand }.mapNotNull { it.qualifiedName }
    }
    private val onDemandImports by lazy(LazyThreadSafetyMode.NONE) {
      importStatements.filter { it.isOnDemand }.mapNotNull { it.qualifiedName }
    }
    val offset: Int by lazy(LazyThreadSafetyMode.NONE) {
      val anchor = file.importList?.allImportStatements?.lastOrNull() ?: file.packageStatement
      anchor?.textRange?.endOffset ?: 0
    }

    fun referenceName(name: String, qualifiedName: String, target: PsiClass): String {
      val pending = imports[name]
      if (pending != null) return if (pending == qualifiedName) name else qualifiedName
      if (!visible.containsKey(name)) visible[name] = resolveHelper.resolveReferencedClass(name, context.token)
      val visibleClass = visible[name]
      if (visibleClass != null) return if (visibleClass.isEquivalentTo(target)) name else qualifiedName
      val targetFile = target.containingFile as? PsiJavaFile
      if (qualifiedName.startsWith("java.lang.") ||
          (target.containingClass == null && targetFile?.packageName == file.packageName)) return name
      val explicit = explicitImports.firstOrNull { it.substringAfterLast('.') == name }
      if (explicit != null) return if (explicit == qualifiedName) name else qualifiedName
      val packageName = qualifiedName.substringBeforeLast('.')
      if (packageName in onDemandImports) return name
      imports[name] = qualifiedName
      return name
    }

    fun editText(newline: String): String? {
      val required = imports.values
      if (required.isEmpty()) return null
      return required.sorted().joinToString(
          newline,
          if (offset == 0) "" else newline,
          if (offset == 0) "$newline$newline" else "",
      ) { "import $it;" }
    }
  }

  private class Context(val owner: PsiClass, val token: PsiElement, val prefix: String)
  private class Candidate(
      val method: PsiMethod,
      val owner: PsiClass,
      val substitutor: PsiSubstitutor,
      val raw: Boolean,
      val match: MatchLevel,
  )

  private val memberBoundaries = setOf(JavaTokenType.LBRACE, JavaTokenType.RBRACE, JavaTokenType.SEMICOLON)
}
