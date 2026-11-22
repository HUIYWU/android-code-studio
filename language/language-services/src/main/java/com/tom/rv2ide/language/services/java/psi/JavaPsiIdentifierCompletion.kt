package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.providers.completion.JavaCompletionTypeIndex
import com.tom.rv2ide.language.services.java.providers.completion.javaSnippetItem
import com.tom.rv2ide.language.services.java.providers.completion.methodBodyKeywords
import com.tom.rv2ide.language.services.java.providers.snippet.JavaSnippetRepository
import com.tom.rv2ide.language.services.java.providers.snippet.JavaSnippetScope
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.ModuleProject
import java.nio.file.Path
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiCaseLabelElementList
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassInitializer
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStaticStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiMember
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.ResolveState
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.JavaResolveUtil
import org.jetbrains.kotlin.com.intellij.psi.scope.ElementClassHint
import org.jetbrains.kotlin.com.intellij.psi.scope.JavaScopeProcessorEvent
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor
import org.jetbrains.kotlin.com.intellij.psi.scope.util.PsiScopesUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil

internal object JavaPsiIdentifierCompletion {
  fun collect(file: PsiJavaFile, offset: Int, recognizeKotlin: Boolean, cancelChecker: ICancelChecker): Candidates? {
    val context = contextAt(file, offset, cancelChecker) ?: return null
    val processor = ScopeProcessor(context, recognizeKotlin, cancelChecker)
    PsiScopesUtil.treeWalkUp(processor, context.place, file)
    for (group in processor.methods.values) {
      cancelChecker.abortIfCancelled()
      processor.items += group.method.item(context.prefix, addParentheses = !context.hasParenthesis)
    }
    val imports = file.importList?.importStatements.orEmpty().mapNotNullTo(HashSet()) { statement ->
      statement.qualifiedName?.let { if (statement.isOnDemand) "$it.*" else it }
    }
    return Candidates(context.prefix, context.local, context.typesOnly, processor.items, imports, processor.typeNames)
  }

  private fun contextAt(file: PsiJavaFile, offset: Int, cancelChecker: ICancelChecker): Context? {
    if (offset !in 1..file.textLength) return null
    val place = file.findElementAt(offset - 1) ?: return null
    val token = place as? PsiJavaToken
    val previous = if (place is PsiWhiteSpace) PsiTreeUtil.prevCodeLeaf(place) as? PsiJavaToken else token
    when (previous?.tokenType) {
      JavaTokenType.DOT, JavaTokenType.DOUBLE_COLON, JavaTokenType.CASE_KEYWORD, JavaTokenType.DEFAULT_KEYWORD -> return null
    }
    val prefix = if (token?.tokenType == JavaTokenType.IDENTIFIER) {
      token.text.substring(0, offset - token.textRange.startOffset)
    } else if (place is PsiWhiteSpace) {
      ""
    } else {
      return null
    }
    val hasParenthesis = (PsiTreeUtil.nextCodeLeaf(place) as? PsiJavaToken)?.tokenType == JavaTokenType.LPARENTH
    var typesOnly = previous?.tokenType == JavaTokenType.NEW_KEYWORD
    var element = place.parent
    while (element != null) {
      cancelChecker.abortIfCancelled()
      if (element is PsiNameIdentifierOwner && element.nameIdentifier === place) return null
      when (element) {
        is PsiCaseLabelElementList, is PsiMethodReferenceExpression, is PsiComment, is PsiLiteralExpression -> return null
        is PsiJavaCodeReferenceElement -> if (element.referenceNameElement === place) {
          if (element.isQualified) return null
          typesOnly = element !is PsiReferenceExpression
        }
        is PsiCodeBlock -> {
          val owner = element.parent
          return Context(place, prefix, hasParenthesis, typesOnly, local = owner !is PsiClassInitializer)
        }
        is PsiField -> {
          val initializer = element.initializer
          if (initializer != null && PsiTreeUtil.isAncestor(initializer, place, false)) {
            return Context(place, prefix, hasParenthesis, typesOnly, local = false)
          }
          return null
        }
        is PsiClass, is PsiMethod, is PsiJavaFile -> return null
      }
      element = element.parent
    }
    return null
  }

  private class Context(
      val place: PsiElement,
      val prefix: String,
      val hasParenthesis: Boolean,
      val typesOnly: Boolean,
      val local: Boolean,
  )

  internal class Candidates(
      private val prefix: String,
      private val local: Boolean,
      private val typesOnly: Boolean,
      private val items: MutableList<CompletionItem>,
      private val imports: Set<String>,
      private val typeNames: Set<String>,
  ) {
    fun finish(
        path: Path,
        module: ModuleProject?,
        matchAllLowerCase: Boolean,
        recognizeKotlin: Boolean,
        cancelChecker: ICancelChecker,
    ): CompletionResult {
      val includeIndexedTypes = CompletionResult.TRIM_TO_MAX && items.size < CompletionResult.MAX_ITEMS &&
          (matchAllLowerCase || prefix.firstOrNull()?.isUpperCase() == true)
      val hasMoreIndexedTypes = includeIndexedTypes && JavaCompletionTypeIndex.addImportableItems(
          items, path, imports, typeNames, module, prefix, recognizeKotlin, cancelChecker,
      )
      if (!typesOnly) {
        val snippets = JavaSnippetRepository.snippets
        for (scope in listOf(JavaSnippetScope.GLOBAL, if (local) JavaSnippetScope.LOCAL else JavaSnippetScope.MEMBER)) {
          for (snippet in snippets[scope].orEmpty()) {
            cancelChecker.abortIfCancelled()
            val match = CompletionItem.matchLevel(snippet.prefix, prefix)
            if (match != MatchLevel.NO_MATCH) items += javaSnippetItem(snippet, match)
          }
        }
        if (prefix.isNotEmpty()) {
          for (keyword in methodBodyKeywords) JavaPsiCompletionItems.keyword(items, keyword, prefix)
        }
      }
      cancelChecker.abortIfCancelled()
      if (CompletionResult.TRIM_TO_MAX && items.size > CompletionResult.MAX_ITEMS) {
        items.sort()
        return CompletionResult(items.subList(0, CompletionResult.MAX_ITEMS)).apply { isIncomplete = true }
      }
      return CompletionResult(items).apply { isIncomplete = hasMoreIndexedTypes }
    }
  }

  private class MethodCandidate(val level: PsiClass?, val method: JavaPsiCompletionItems.MethodGroup)

  private class ScopeProcessor(
      private val context: Context,
      private val recognizeKotlin: Boolean,
      private val cancelChecker: ICancelChecker,
  ) : PsiScopeProcessor {
    val items = ArrayList<CompletionItem>()
    val typeNames = HashSet<String>()
    val methods = LinkedHashMap<String, MethodCandidate>()
    private val variableNames = HashSet<String>()
    private var staticScope = false
    private var sawEnclosingClass = false
    private var fileContext: PsiElement? = null
    private val declarationKinds = if (context.typesOnly) classesOnly else scopeDeclarations

    @Suppress("UNCHECKED_CAST")
    override fun <T> getHint(hintKey: Key<T>): T? =
        if (hintKey == ElementClassHint.KEY) declarationKinds as T else null

    companion object {
      private val classesOnly = ElementClassHint { it == ElementClassHint.DeclarationKind.CLASS }
      private val scopeDeclarations = ElementClassHint { it != ElementClassHint.DeclarationKind.PACKAGE }
    }

    override fun handleEvent(event: PsiScopeProcessor.Event, associated: Any?) {
      cancelChecker.abortIfCancelled()
      if (JavaScopeProcessorEvent.isEnteringStaticScope(event, associated)) staticScope = true
      if (event == JavaScopeProcessorEvent.SET_CURRENT_FILE_CONTEXT) {
        fileContext = associated as? PsiElement
        if (associated is PsiClass && !sawEnclosingClass) {
          sawEnclosingClass = true
          if (!context.typesOnly && !staticScope) {
            JavaPsiCompletionItems.keyword(items, "this", context.prefix)
            if (!associated.isInterface && associated.superClass != null) {
              JavaPsiCompletionItems.keyword(items, "super", context.prefix)
            }
          }
        }
      }
    }

    override fun execute(element: PsiElement, state: ResolveState): Boolean {
      cancelChecker.abortIfCancelled()
      when (element) {
        is PsiClass -> addType(element)
        is PsiMethod -> if (!context.typesOnly) addMethod(element, state)
        is PsiField -> if (!context.typesOnly) addField(element, state)
        is PsiLocalVariable, is PsiParameter -> if (!context.typesOnly) addVariable(element as PsiVariable)
      }
      return true
    }

    private fun match(name: String): MatchLevel = CompletionItem.matchLevel(name, context.prefix)

    private fun accessible(member: PsiMember, owner: PsiClass?): Boolean =
        JavaResolveUtil.isAccessible(member, owner, member.modifierList, context.place, null, fileContext)

    private fun acceptedOwner(owner: PsiClass): Boolean = recognizeKotlin || JavaPsiCompletionItems.isJavaOwner(owner)

    private fun addVariable(variable: PsiVariable) {
      val name = variable.name ?: return
      val match = match(name)
      if (match == MatchLevel.NO_MATCH || variable.isUnnamed || !variableNames.add(name)) return
      val kind = if (variable is PsiParameter) CompletionItemKind.PROPERTY else CompletionItemKind.VARIABLE
      val type = variable.type.presentableText
      items += JavaPsiCompletionItems.item(name, kind, type, match).apply { overrideTypeText = type }
    }

    private fun addField(field: PsiField, state: ResolveState) {
      val owner = field.containingClass ?: return
      val name = field.name
      val match = match(name)
      if (match == MatchLevel.NO_MATCH || name in variableNames ||
          (staticScope && !field.hasModifierProperty(PsiModifier.STATIC)) || !acceptedOwner(owner) ||
          !accessible(field, owner)) return
      variableNames += name
      items += JavaPsiCompletionItems.field(field, owner, state.get(PsiSubstitutor.KEY) ?: PsiSubstitutor.EMPTY, match)
    }

    private fun addType(type: PsiClass) {
      val name = type.name ?: return
      val match = match(name)
      if (match == MatchLevel.NO_MATCH || name in typeNames || !acceptedOwner(type)) return
      if (type is PsiTypeParameter) {
        if (staticScope && type.owner is PsiClass) return
      } else if (!accessible(type, type.containingClass)) {
        return
      }
      typeNames += name
      items += JavaPsiCompletionItems.type(type, name, match)
    }

    private fun addMethod(method: PsiMethod, state: ResolveState) {
      if (method.isConstructor) return
      val name = method.name
      val level = fileContext as? PsiClass
      val group = methods[name]
      if (group != null && group.level !== level) return
      val match = group?.method?.match ?: match(name)
      if (match == MatchLevel.NO_MATCH || name.startsWith('<')) return
      val owner = method.containingClass ?: return
      val isStatic = method.hasModifierProperty(PsiModifier.STATIC)
      if (staticScope && !isStatic || !acceptedOwner(owner) || !accessible(method, owner)) return
      if (isStatic && owner.isInterface) {
        val target = level ?: (fileContext as? PsiImportStaticStatement)?.resolveTargetClass()
        if (target == null || !owner.isEquivalentTo(target)) return
      }
      val substitutor = state.get(PsiSubstitutor.KEY) ?: PsiSubstitutor.EMPTY
      val candidate = group ?: MethodCandidate(level, JavaPsiCompletionItems.MethodGroup(method, owner, substitutor, match))
          .also { methods[name] = it }
      candidate.method.add(method, substitutor)
    }
  }
}
