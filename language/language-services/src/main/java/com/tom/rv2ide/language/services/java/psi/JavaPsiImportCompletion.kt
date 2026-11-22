package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.providers.completion.JavaCompletionTypeIndex
import com.tom.rv2ide.language.services.java.providers.completion.indexedClassItem
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.ModuleProject
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiImportModuleStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiClassImplUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil

internal object JavaPsiImportCompletion {
  fun collect(
      file: PsiJavaFile,
      offset: Int,
      recognizeKotlin: Boolean,
      cancelChecker: ICancelChecker,
  ): Candidates? {
    val context = contextAt(file, offset, cancelChecker) ?: return null
    val facade = JavaPsiFacade.getInstance(file.project)
    val scope = context.statement.resolveScope
    if (context.qualifier.isNotEmpty()) {
      val owner = facade.findClass(context.qualifier, scope)
      if (owner != null) {
        val visible = generateSequence(owner) { it.containingClass }.all {
          cancelChecker.abortIfCancelled()
          (recognizeKotlin || JavaPsiCompletionItems.isJavaOwner(it)) && PsiUtil.isAccessible(it, context.statement, null)
        }
        val items = if (visible) typeMembers(owner, context, recognizeKotlin, cancelChecker) else ArrayList()
        return Candidates(context.prefix, items)
      }
    }

    val entries = LinkedHashMap<String, CompletionItem?>()
    val pkg = facade.findPackage(context.qualifier)
    if (pkg != null) {
      if (context.qualifier.isNotEmpty()) {
        for (type in pkg.getClasses(scope)) {
          cancelChecker.abortIfCancelled()
          val name = type.name ?: continue
          val match = CompletionItem.matchLevel(name, context.prefix)
          if (match == MatchLevel.NO_MATCH || entries[name] != null) continue
          entries[name] = if ((recognizeKotlin || JavaPsiCompletionItems.isJavaOwner(type)) &&
              PsiUtil.isAccessible(type, context.statement, null)) JavaPsiCompletionItems.type(type, name, match) else null
        }
      }
      for (child in pkg.getSubPackages(scope)) {
        cancelChecker.abortIfCancelled()
        val name = child.name ?: continue
        val match = CompletionItem.matchLevel(name, context.prefix)
        if (match != MatchLevel.NO_MATCH && name !in entries) {
          entries[name] = packageItem(name, child.qualifiedName, match)
        }
      }
    }
    val keywords = ArrayList<CompletionItem>(1)
    if (!context.isStatic && context.qualifier.isEmpty()) {
      JavaPsiCompletionItems.keyword(keywords, "static", context.prefix)
    }
    return Candidates(context.prefix, keywords, context.qualifier, entries)
  }

  private fun contextAt(file: PsiJavaFile, offset: Int, cancelChecker: ICancelChecker): Context? {
    if (offset !in 1..file.textLength) return null
    val leaf = file.findElementAt(offset - 1) ?: return null
    val token = (if (leaf is PsiWhiteSpace) PsiTreeUtil.prevCodeLeaf(leaf) else leaf) as? PsiJavaToken ?: return null
    val statement = PsiTreeUtil.getParentOfType(token, PsiImportStatementBase::class.java) ?: return null
    if (statement is PsiImportModuleStatement) return null
    if (leaf is PsiWhiteSpace && !PsiTreeUtil.isAncestor(statement, leaf, true)) {
      val whitespace = leaf.text.substring(0, offset - leaf.textRange.startOffset)
      if (whitespace.any { it == '\n' || it == '\r' }) return null
    }

    val prefix: String
    var separator: PsiJavaToken?
    when (token.tokenType) {
      JavaTokenType.IDENTIFIER -> {
        if (leaf !== token) return null
        prefix = token.text.substring(0, offset - token.textRange.startOffset)
        separator = previousToken(token, statement)
      }
      JavaTokenType.DOT, JavaTokenType.IMPORT_KEYWORD, JavaTokenType.STATIC_KEYWORD -> {
        if (token.tokenType != JavaTokenType.DOT && leaf === token) return null
        prefix = ""
        separator = token
      }
      else -> return null
    }

    val qualifier = ArrayList<String>()
    while (separator != null && separator.tokenType == JavaTokenType.DOT) {
      cancelChecker.abortIfCancelled()
      val identifier = previousToken(separator, statement) ?: return null
      if (identifier.tokenType != JavaTokenType.IDENTIFIER) return null
      qualifier.add(identifier.text)
      separator = previousToken(identifier, statement)
    }
    val isStatic = when (separator?.tokenType) {
      JavaTokenType.IMPORT_KEYWORD -> false
      JavaTokenType.STATIC_KEYWORD -> true
      else -> return null
    }
    return Context(statement, qualifier.asReversed().joinToString("."), prefix, isStatic)
  }

  private fun previousToken(token: PsiJavaToken, statement: PsiImportStatementBase): PsiJavaToken? {
    val previous = PsiTreeUtil.prevCodeLeaf(token) as? PsiJavaToken ?: return null
    return previous.takeIf { PsiTreeUtil.isAncestor(statement, it, true) }
  }

  private fun typeMembers(
      owner: PsiClass,
      context: Context,
      recognizeKotlin: Boolean,
      cancelChecker: ICancelChecker,
  ): MutableList<CompletionItem> {
    val items = ArrayList<CompletionItem>()
    val classNames = HashSet<String>()
    val innerClasses = if (context.isStatic) PsiClassImplUtil.getAllInnerClasses(owner) else owner.innerClasses
    for (type in innerClasses) {
      cancelChecker.abortIfCancelled()
      val name = type.name ?: continue
      val match = CompletionItem.matchLevel(name, context.prefix)
      if (match == MatchLevel.NO_MATCH || context.isStatic && !type.hasModifierProperty(PsiModifier.STATIC) ||
          !PsiUtil.isAccessible(type, context.statement, owner) ||
          !recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(type) || !classNames.add(name)) continue
      items += JavaPsiCompletionItems.type(type, name, match)
    }
    if (!context.isStatic) return items

    val fieldNames = HashSet<String>()
    for (field in PsiClassImplUtil.getAllFields(owner)) {
      cancelChecker.abortIfCancelled()
      if (!field.hasModifierProperty(PsiModifier.STATIC)) continue
      val name = field.name
      val match = CompletionItem.matchLevel(name, context.prefix)
      if (match == MatchLevel.NO_MATCH) continue
      val declaringClass = field.containingClass ?: continue
      if ((!recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(declaringClass)) ||
          !PsiUtil.isAccessible(field, context.statement, owner) || !fieldNames.add(name)) continue
      items += JavaPsiCompletionItems.field(field, declaringClass, PsiSubstitutor.EMPTY, match)
    }

    val methods = LinkedHashMap<String, JavaPsiCompletionItems.MethodGroup>()
    for (method in PsiClassImplUtil.getAllMethods(owner)) {
      cancelChecker.abortIfCancelled()
      if (method.isConstructor || !method.hasModifierProperty(PsiModifier.STATIC)) continue
      val name = method.name
      val match = methods[name]?.match ?: CompletionItem.matchLevel(name, context.prefix)
      if (match == MatchLevel.NO_MATCH) continue
      val declaringClass = method.containingClass ?: continue
      if (declaringClass.isInterface && !declaringClass.isEquivalentTo(owner) ||
          !recognizeKotlin && !JavaPsiCompletionItems.isJavaOwner(declaringClass) ||
          !PsiUtil.isAccessible(method, context.statement, owner)) continue
      methods.getOrPut(name) { JavaPsiCompletionItems.MethodGroup(method, declaringClass, PsiSubstitutor.EMPTY, match) }
          .add(method, PsiSubstitutor.EMPTY)
    }
    for (group in methods.values) {
      cancelChecker.abortIfCancelled()
      items += group.item(context.prefix, addParentheses = false)
    }
    return items
  }

  private fun packageItem(name: String, qualifiedName: String, match: MatchLevel): CompletionItem =
      JavaPsiCompletionItems.item(name, CompletionItemKind.MODULE, qualifiedName.substringBeforeLast('.', " "), match)

  internal class Candidates(
      private val prefix: String,
      private val items: MutableList<CompletionItem>,
      private val packageName: String = "",
      private val packageEntries: MutableMap<String, CompletionItem?>? = null,
  ) {
    fun finish(module: ModuleProject?, recognizeKotlin: Boolean, cancelChecker: ICancelChecker): CompletionResult {
      val entries = packageEntries
      if (entries != null) {
        JavaCompletionTypeIndex.forEachImportChild(module, packageName, recognizeKotlin, cancelChecker) { name, qualifiedName, isType ->
          if (name !in entries) {
            val match = CompletionItem.matchLevel(name, prefix)
            if (match != MatchLevel.NO_MATCH) {
              entries[name] = if (isType) indexedClassItem(qualifiedName, match, null) else packageItem(name, qualifiedName, match)
            }
          }
        }
        entries.values.filterNotNullTo(items)
      }
      cancelChecker.abortIfCancelled()
      if (CompletionResult.TRIM_TO_MAX && items.size > CompletionResult.MAX_ITEMS) {
        items.sort()
        return CompletionResult(items.subList(0, CompletionResult.MAX_ITEMS)).apply { isIncomplete = true }
      }
      return CompletionResult(items)
    }
  }

  private class Context(
      val statement: PsiImportStatementBase,
      val qualifier: String,
      val prefix: String,
      val isStatic: Boolean,
  )
}
