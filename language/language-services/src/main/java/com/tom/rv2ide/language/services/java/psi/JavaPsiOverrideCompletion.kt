package com.tom.rv2ide.language.services.java.psi

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
  fun complete(
      file: PsiJavaFile,
      offset: Int,
      recognizeKotlin: Boolean,
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
        if (!recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(declaringClass)) continue
        val match = CompletionItem.matchLevel(method.name, context.prefix)
        if (match == MatchLevel.NO_MATCH || !seen.add(signature)) continue
        candidates += Candidate(method, declaringClass, resolved.substitutor, signature.isRaw, match)
      }
    }
    if (candidates.isEmpty()) return null

    candidates.sortWith(compareBy<Candidate> { it.match.ordinal }.thenBy { it.method.name })
    val count = if (CompletionResult.TRIM_TO_MAX) minOf(candidates.size, CompletionResult.MAX_ITEMS) else candidates.size
    val items = ArrayList<CompletionItem>(count)
    for (index in 0 until count) {
      cancelChecker.abortIfCancelled()
      val candidate = candidates[index]
      val stub = Stub(candidate, ImportContext(file, context), cancelChecker)

      val text = stub.render() ?: continue
      items += JavaPsiCompletionItems.method(candidate.method, candidate.owner, candidate.substitutor, candidate.match).apply {
        insertText = text
        insertTextFormat = InsertTextFormat.SNIPPET
        snippetDescription = describeSnippet(context.prefix)
        stub.importEdit()?.let { additionalTextEdits = listOf(it) }
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

    fun render(): String? {
      val returnType = substitute(method.returnType ?: return null)
      val parameters = method.parameterList.parameters
      val parameterNames = parameters.mapIndexed { index, parameter -> parameter.name ?: "p$index" }
      val parameterTypes = parameters.map { substitute(it.type) }
      val bounds = methodTypeParameters.map { parameter -> parameter.extendsList.referencedTypes.map(::substitute) }
      val abstractMethod = method.hasModifierProperty(PsiModifier.ABSTRACT)
      val delegate = !context.context.owner.isInterface && !candidate.owner.isInterface && !abstractMethod
      val throwsTypes = method.throwsList.referencedTypes.map(::substitute)
      (context.context.owner.typeParameters.asList() + methodTypeParameters).mapNotNullTo(names) { it.name }
      val annotation = "@java.lang.Override"
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
        "$annotation\n$signature;"
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
        "$annotation\n$declaration {\n    $body\n}"
      }
      return text
    }

    private fun typeText(type: PsiType): String {
      cancelChecker.abortIfCancelled()
      return when (type) {
        is PsiArrayType -> "${typeText(type.componentType)}[]"
        is PsiWildcardType -> type.bound?.let { "? ${if (type.isExtends) "extends" else "super"} ${typeText(it)}" } ?: "?"
        is PsiClassType -> {
          val resolved = type.resolve()
          val qualifiedName = resolved?.qualifiedName
          val name = resolved?.name
          if (resolved is PsiTypeParameter || qualifiedName == null || name == null) {
            type.canonicalText
          } else if (resolved.containingClass != null && !resolved.hasModifierProperty(PsiModifier.STATIC)) {
            type.canonicalText
          } else {
            val renderedName = if (name in names) qualifiedName else context.referenceName(name, qualifiedName)
            val arguments = type.parameters
            renderedName + if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">", transform = ::typeText)
          }
        }
        else -> type.canonicalText
      }
    }

    fun importEdit(): TextEdit? = context.edit()
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
    private val existingImports = file.importList?.importStatements.orEmpty().mapNotNull { statement ->
      statement.qualifiedName?.let { if (statement.isOnDemand) "$it.*" else it }
    }
    private val packageName = file.packageName
    private val contents = file.viewProvider.contents
    private val position by lazy(LazyThreadSafetyMode.NONE) {
      val anchor = file.importList?.allImportStatements?.lastOrNull() ?: file.packageStatement
      val offset = anchor?.textRange?.endOffset ?: 0
      LineIndex.from(contents).indexToPosition(offset)
    }

    fun visibleClass(name: String): PsiClass? {
      if (!visible.containsKey(name)) visible[name] = resolveHelper.resolveReferencedClass(name, context.token)
      return visible[name]
    }

    fun referenceName(name: String, qualifiedName: String): String {
      val existing = imports[name]
      if (existing != null) return if (existing == qualifiedName) name else qualifiedName
      if (qualifiedName.startsWith("java.lang.") ||
          (packageName.isNotEmpty() && qualifiedName.startsWith("$packageName.")) ||
          existingImports.any { it == qualifiedName || it == qualifiedName.substringBeforeLast('.') + ".*" }) return name
      val visibleClass = visibleClass(name)
      if (visibleClass != null && !visibleClass.isEquivalentTo(resolveHelper.resolveReferencedClass(qualifiedName, context.token))) {
        return qualifiedName
      }
      imports[name] = qualifiedName
      return name
    }

    fun edit(): TextEdit? {
      val required = imports.values
      if (required.isEmpty()) return null
      val offset = position.requireIndex()
      val text = required.sorted().joinToString("\n", if (offset == 0) "" else "\n", if (offset == 0) "\n\n" else "") {
        "import $it;"
      }
      return TextEdit(Range(position, position), text)
    }
  }

  private class Context(val owner: PsiClass, val token: PsiJavaToken, val prefix: String)
  private class Candidate(
      val method: PsiMethod,
      val owner: PsiClass,
      val substitutor: PsiSubstitutor,
      val raw: Boolean,
      val match: MatchLevel,
  )

  private val memberBoundaries = setOf(JavaTokenType.LBRACE, JavaTokenType.RBRACE, JavaTokenType.SEMICOLON)
}
