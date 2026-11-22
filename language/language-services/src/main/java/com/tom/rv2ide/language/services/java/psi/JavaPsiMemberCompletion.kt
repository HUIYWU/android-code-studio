package com.tom.rv2ide.language.services.java.psi

import com.tom.rv2ide.language.services.java.models.JavaCompletionItem
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.FieldCompletionData
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.MethodCompletionData
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStatementBase
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaToken
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiPackageStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor
import org.jetbrains.kotlin.com.intellij.psi.PsiSuperExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiClassImplUtil
import org.jetbrains.kotlin.com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.com.intellij.psi.util.MethodSignatureUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

internal object JavaPsiMemberCompletion {
  fun complete(
      file: PsiJavaFile,
      offset: Int,
      content: CharSequence?,
      recognizeKotlin: Boolean,
      cancelChecker: ICancelChecker,
  ): CompletionResult? {
    if (offset !in 1..file.textLength) return null
    val leaf = file.findElementAt(offset - 1) ?: return null
    val token = (if (leaf is PsiWhiteSpace) PsiTreeUtil.prevVisibleLeaf(leaf) else leaf)
        as? PsiJavaToken ?: return null
    val context = contextAt(token, offset, cancelChecker) ?: return null
    if (content != null && !file.viewProvider.contents.contentEquals(content)) return CompletionResult.EMPTY
    cancelChecker.abortIfCancelled()
    val qualifier = context.qualifier
    val target = (qualifier as? PsiJavaCodeReferenceElement)?.resolve()
    if (target is PsiClass && context.construct) return CompletionResult.EMPTY
    val staticContext = target is PsiClass
    val type = if (staticContext) null else (qualifier as? PsiExpression)?.type
    val items = ArrayList<CompletionItem>()
    if (type is PsiArrayType && !context.construct) {
      keyword(items, "length", context.prefix)
      return CompletionResult(items)
    }
    val qualifierType = (type as? PsiClassType)?.resolveGenerics()
    val owner = target as? PsiClass ?: qualifierType?.element ?: return CompletionResult.EMPTY
    if (!recognizeKotlin && !isJavaOwner(owner)) return CompletionResult.EMPTY
    val accessClass = if (qualifier is PsiSuperExpression) null else owner
    val substitutors = HashMap<PsiClass, PsiSubstitutor>()
    fun substitutor(declaringClass: PsiClass): PsiSubstitutor =
        if (staticContext) PsiSubstitutor.EMPTY else substitutors.getOrPut(declaringClass) {
          TypeConversionUtil.getSuperClassSubstitutor(
              declaringClass, owner, qualifierType?.substitutor ?: PsiSubstitutor.EMPTY,
          )
        }

    if (!context.construct && !context.typesOnly) {
      val methods = LinkedHashMap<String, MethodGroup>()
      for (method in PsiClassImplUtil.getAllMethods(owner)) {
        cancelChecker.abortIfCancelled()
        if (method.isConstructor || method.hasModifierProperty(PsiModifier.STATIC) != staticContext) continue
        val declaringClass = method.containingClass ?: continue
        if (staticContext && declaringClass.isInterface && declaringClass != owner) continue
        if (!recognizeKotlin && !isJavaOwner(declaringClass)) continue
        val name = method.name
        if (name.startsWith('<')) continue
        val match = methods[name]?.match ?: CompletionItem.matchLevel(name, context.prefix)
        if (match == MatchLevel.NO_MATCH || !PsiUtil.isAccessible(method, token, accessClass)) continue
        val substitution = substitutor(declaringClass)
        methods.getOrPut(name) { MethodGroup(method, declaringClass, substitution, match) }
            .signatures.add(method.getSignature(substitution))
      }
      for ((name, group) in methods) {
        cancelChecker.abortIfCancelled()
        items += methodItem(name, group, context)
      }
      val fieldNames = HashSet<String>()
      for (field in PsiClassImplUtil.getAllFields(owner)) {
        cancelChecker.abortIfCancelled()
        val declaringClass = field.containingClass ?: continue
        val name = field.name
        if (!fieldNames.add(name) || field.hasModifierProperty(PsiModifier.STATIC) != staticContext) continue
        val match = CompletionItem.matchLevel(name, context.prefix)
        if (match == MatchLevel.NO_MATCH || (!recognizeKotlin && !isJavaOwner(declaringClass)) ||
            !PsiUtil.isAccessible(field, token, accessClass)) continue
        val declaredType = field.type
        val fieldType = substitutor(declaringClass).substitute(declaredType) ?: declaredType
        val typeLabel = fieldType.presentableText
        items += JavaCompletionItem().apply {
          ideLabel = name
          completionKind = if (field is PsiEnumConstant) CompletionItemKind.ENUM_MEMBER else CompletionItemKind.FIELD
          detail = typeLabel
          ideSortText = name
          matchLevel = match
          overrideTypeText = typeLabel
          data = FieldCompletionData(name, classInfo(declaringClass))
        }
      }
    }
    if (staticContext || context.construct) {
      val classNames = HashSet<String>()
      for (member in PsiClassImplUtil.getAllInnerClasses(owner)) {
        cancelChecker.abortIfCancelled()
        val name = member.name ?: continue
        val match = CompletionItem.matchLevel(name, context.prefix)
        if (match == MatchLevel.NO_MATCH || !classNames.add(name)) continue
        if (!recognizeKotlin && !isJavaOwner(member) || !PsiUtil.isAccessible(member, token, owner)) continue
        if (context.construct && (member.hasModifierProperty(PsiModifier.STATIC) || member.isInterface ||
                member.isEnum || member.isRecord)) continue
        val info = classInfo(member)
        items += JavaCompletionItem().apply {
          ideLabel = name
          completionKind = when {
            member.isAnnotationType -> CompletionItemKind.ANNOTATION_TYPE
            member.isEnum -> CompletionItemKind.ENUM
            member.isInterface -> CompletionItemKind.INTERFACE
            else -> CompletionItemKind.CLASS
          }
          detail = info.className
          ideSortText = name
          matchLevel = match
          data = info
          if (context.construct) {
            val insertedName = context.insertionPrefix + name
            if (context.hasParenthesis) {
              insertText = insertedName
            } else {
              insertCall(this, insertedName, context.replacedPrefix, needsArguments = true)
            }
          }
        }
      }
    }
    if (staticContext && !context.typesOnly && owner !is PsiTypeParameter) {
      keyword(items, "class", context.prefix)
      val enclosingOwner = PsiTreeUtil.getParentOfType(token, PsiClass::class.java)
      val enclosing = generateSequence(enclosingOwner) { PsiTreeUtil.getParentOfType(it, PsiClass::class.java) }
          .firstOrNull { it.isEquivalentTo(owner) }
      if (enclosing != null && PsiUtil.getEnclosingStaticElement(token, enclosing) == null) {
        keyword(items, "this", context.prefix)
        if (!owner.isInterface) keyword(items, "super", context.prefix)
      }
    }
    return CompletionResult(items)
  }

  private fun contextAt(token: PsiJavaToken, offset: Int, cancelChecker: ICancelChecker): Context? {
    val kind = token.tokenType
    if (kind != JavaTokenType.DOT && kind != JavaTokenType.IDENTIFIER && kind != JavaTokenType.NEW_KEYWORD) return null
    val prefix = if (kind == JavaTokenType.IDENTIFIER) {
      val length = offset - token.textRange.startOffset
      if (length > token.textLength) return null
      token.text.substring(0, length)
    } else {
      ""
    }
    val hasParenthesis = (PsiTreeUtil.nextVisibleLeaf(token) as? PsiJavaToken)?.tokenType == JavaTokenType.LPARENTH
    var element: PsiElement? = token.parent
    while (element != null) {
      cancelChecker.abortIfCancelled()
      when (element) {
        is PsiNewExpression -> {
          val qualifier = element.qualifier
          val name = element.classOrAnonymousClassReference?.referenceNameElement
          if (qualifier != null && (name === token || kind == JavaTokenType.NEW_KEYWORD && token.parent === element)) {
            val replaceNew = kind == JavaTokenType.NEW_KEYWORD && offset == token.textRange.endOffset
            return Context(qualifier, prefix, hasParenthesis, construct = true,
                insertionPrefix = if (replaceNew) "new " else "",
                replacedPrefix = if (replaceNew) "new" else prefix)
          }
        }
        is PsiMethodReferenceExpression -> return null
        is PsiReferenceExpression -> {
          val qualifier = element.qualifierExpression
          if (qualifier != null && (element.referenceNameElement === token ||
                  kind == JavaTokenType.DOT && token.parent === element)) {
            return Context(qualifier, prefix, hasParenthesis)
          }
        }
        is PsiJavaCodeReferenceElement -> {
          val qualifier = element.qualifier as? PsiJavaCodeReferenceElement
          if (qualifier != null && (element.referenceNameElement === token ||
                  kind == JavaTokenType.DOT && token.parent === element)) {
            if (PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java) != null ||
                PsiTreeUtil.getParentOfType(element, PsiPackageStatement::class.java) != null) return null
            return Context(qualifier, prefix, hasParenthesis, typesOnly = true)
          }
        }
      }
      element = element.parent
    }
    return null
  }

  private fun methodItem(name: String, group: MethodGroup, context: Context): CompletionItem {
    val method = group.method
    val parameters = method.parameterList.parameters
    val labels = parameters.map {
      val type = it.type
      (group.substitutor.substitute(type) ?: type).presentableText
    }
    return JavaCompletionItem().apply {
      ideLabel = name
      completionKind = CompletionItemKind.METHOD
      detail = labels.joinToString(", ", "$name(", ")")
      ideSortText = name
      matchLevel = group.match
      overrideTypeText = method.returnType?.let { (group.substitutor.substitute(it) ?: it).presentableText }
      data = MethodCompletionData(
          name,
          classInfo(group.declaringClass),
          labels,
          parameters.map { erasedTypeName(TypeConversionUtil.erasure(it.type)) },
          group.signatures.size - 1,
      )
      if (!context.hasParenthesis) {
        insertCall(this, name, context.prefix, group.signatures.size > 1 || parameters.isNotEmpty())
      }
    }
  }

  private fun insertCall(item: CompletionItem, name: String, prefix: String, needsArguments: Boolean) {
    item.insertTextFormat = InsertTextFormat.SNIPPET
    item.snippetDescription = describeSnippet(prefix, allowCommandExecution = true)
    if (needsArguments) {
      item.insertText = "$name(\$0)"
      item.command = Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS)
    } else {
      item.insertText = "$name()\$0"
    }
  }

  private fun keyword(items: MutableList<CompletionItem>, name: String, prefix: String) {
    val match = CompletionItem.matchLevel(name, prefix)
    if (match == MatchLevel.NO_MATCH) return
    items += JavaCompletionItem().apply {
      ideLabel = name
      completionKind = CompletionItemKind.KEYWORD
      detail = "keyword"
      ideSortText = name
      matchLevel = match
    }
  }

  private fun isJavaOwner(owner: PsiClass): Boolean =
      when (owner.navigationElement) {
        is KtDeclaration, is KtFile -> false
        else -> true
      }

  private fun classInfo(psiClass: PsiClass): ClassCompletionData {
    val name = psiClass.name.orEmpty()
    val qualifiedName = psiClass.qualifiedName ?: return ClassCompletionData(name)
    val topLevel = generateSequence(psiClass) { it.containingClass }.last()
    return ClassCompletionData(
        className = qualifiedName,
        isCompleteData = true,
        flatName = ClassUtil.getJVMClassName(psiClass) ?: qualifiedName,
        simpleName = name,
        isNested = psiClass.containingClass != null,
        topLevelClass = topLevel.qualifiedName ?: qualifiedName,
    )
  }

  private fun erasedTypeName(type: PsiType): String = when (type) {
    is PsiArrayType -> erasedTypeName(type.componentType) + "[]"
    is PsiClassType -> type.resolve()?.let(ClassUtil::getJVMClassName) ?: type.canonicalText
    else -> type.canonicalText
  }

  private class MethodGroup(
      val method: PsiMethod,
      val declaringClass: PsiClass,
      val substitutor: PsiSubstitutor,
      val match: MatchLevel,
  ) {
    val signatures = MethodSignatureUtil.createErasedMethodSignatureSet()
  }

  private class Context(
      val qualifier: PsiElement,
      val prefix: String,
      val hasParenthesis: Boolean,
      val construct: Boolean = false,
      val typesOnly: Boolean = false,
      val insertionPrefix: String = "",
      val replacedPrefix: String = prefix,
  )
}
