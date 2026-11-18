package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.FieldCompletionData
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.lsp.models.MethodCompletionData
import com.tom.rv2ide.progress.ICancelChecker
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiMember
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiPrimitiveType
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiClassImplUtil
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/**
 * Collects the Java-visible members of a Kotlin light class for Java completion.
 *
 * Callers must hold an analysis read lock and an application read action. Java classes stay with
 * javac; only Kotlin source classes are answered here.
 */
internal fun collectKotlinMembers(
    project: Project,
    javaFile: PsiJavaFile,
    offset: Int,
    ownerQualifiedName: String,
    staticContext: Boolean,
    prefix: String,
    addParentheses: Boolean,
    cancelChecker: ICancelChecker,
): List<CompletionItem> {
  val owner = findKotlinLightClass(project, ownerQualifiedName) ?: return emptyList()
  val place = javaFile.findElementAt((offset - 1).coerceAtLeast(0)) ?: javaFile
  val items = ArrayList<CompletionItem>()
  appendMethods(owner, place, staticContext, prefix, addParentheses, cancelChecker, items)
  appendFields(owner, place, staticContext, prefix, cancelChecker, items)
  if (staticContext) {
    appendMemberClasses(owner, place, prefix, true, cancelChecker, items)
  }
  return items
}

internal fun collectKotlinInnerClasses(
    project: Project,
    javaFile: PsiJavaFile,
    offset: Int,
    ownerQualifiedName: String,
    prefix: String,
    cancelChecker: ICancelChecker,
): List<CompletionItem> {
  val owner = findKotlinLightClass(project, ownerQualifiedName) ?: return emptyList()
  val place = javaFile.findElementAt((offset - 1).coerceAtLeast(0)) ?: javaFile
  val items = ArrayList<CompletionItem>()
  appendMemberClasses(owner, place, prefix, false, cancelChecker, items)
  return items
}

private fun findKotlinLightClass(project: Project, qualifiedName: String): PsiClass? {
  val owner =
      JavaPsiFacade.getInstance(project)
          .findClass(qualifiedName, GlobalSearchScope.allScope(project))
          ?: return null
  val navigation = owner.navigationElement
  if (navigation !is KtClassOrObject && navigation !is KtFile) return null
  return owner
}

private fun appendMethods(
    owner: PsiClass,
    place: PsiElement,
    staticContext: Boolean,
    prefix: String,
    addParentheses: Boolean,
    cancelChecker: ICancelChecker,
    items: MutableList<CompletionItem>,
) {
  val grouped = LinkedHashMap<String, MutableList<PsiMethod>>()
  for (method in PsiClassImplUtil.getAllMethods(owner)) {
    cancelChecker.abortIfCancelled()
    if (method.isConstructor) continue
    val name = method.name ?: continue
    if (name.startsWith("<")) continue
    if (method.hasModifierProperty(PsiModifier.STATIC) != staticContext) continue
    if (!matchesPrefix(name, prefix)) continue
    if (!isAccessible(method, place, owner)) continue
    grouped.getOrPut(name) { ArrayList() }.add(method)
  }
  for ((name, overloads) in grouped) {
    items += methodItem(owner, name, overloads, prefix, addParentheses)
  }
}

private fun methodItem(
    owner: PsiClass,
    name: String,
    overloads: List<PsiMethod>,
    prefix: String,
    addParentheses: Boolean,
): CompletionItem {
  val first = overloads.first()
  val parameters = first.parameterList.parameters
  return CompletionItem().apply {
    ideLabel = name
    completionKind = CompletionItemKind.METHOD
    detail =
        buildString {
          append(name)
          append('(')
          parameters.forEachIndexed { index, parameter ->
            if (index > 0) append(", ")
            append(parameter.type.presentableText)
          }
          append(')')
        }
    ideSortText = name
    matchLevel = CompletionItem.matchLevel(name, prefix)
    overrideTypeText = first.returnType?.presentableText
    data =
        MethodCompletionData(
            name,
            classInfo(first.containingClass ?: owner),
            parameters.map { it.type.presentableText },
            parameters.map { erasedTypeName(it.type) },
            overloads.size - 1,
        )
    if (addParentheses) {
      if (overloads.size == 1 && parameters.isEmpty()) {
        insertText = "$name()\$0"
      } else {
        insertText = "$name(\$0)"
        command = Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS)
      }
      insertTextFormat = InsertTextFormat.SNIPPET
      snippetDescription = describeSnippet(prefix, allowCommandExecution = true)
    }
  }
}

private fun appendFields(
    owner: PsiClass,
    place: PsiElement,
    staticContext: Boolean,
    prefix: String,
    cancelChecker: ICancelChecker,
    items: MutableList<CompletionItem>,
) {
  for (field in PsiClassImplUtil.getAllFields(owner)) {
    cancelChecker.abortIfCancelled()
    val name = field.name ?: continue
    val isStaticField = field.hasModifierProperty(PsiModifier.STATIC)
    val accessible = isAccessible(field, place, owner)
    if (isStaticField != staticContext) continue
    if (!matchesPrefix(name, prefix)) continue
    if (!accessible) continue
    val type = field.type
    items +=
        CompletionItem().apply {
          ideLabel = name
          completionKind =
              if (field is PsiEnumConstant) CompletionItemKind.ENUM_MEMBER
              else CompletionItemKind.FIELD
          detail = type.presentableText
          ideSortText = name
          matchLevel = CompletionItem.matchLevel(name, prefix)
          overrideTypeText = type.presentableText
          data = FieldCompletionData(name, classInfo(field.containingClass ?: owner))
        }
  }
}

private fun appendMemberClasses(
    owner: PsiClass,
    place: PsiElement,
    prefix: String,
    staticRequired: Boolean,
    cancelChecker: ICancelChecker,
    items: MutableList<CompletionItem>,
) {
  for (member in PsiClassImplUtil.getAllInnerClasses(owner)) {
    cancelChecker.abortIfCancelled()
    val name = member.name ?: continue
    if (member.hasModifierProperty(PsiModifier.STATIC) != staticRequired) continue
    if (!matchesPrefix(name, prefix)) continue
    if (!isAccessible(member, place, owner)) continue
    val qualifiedName = member.qualifiedName ?: continue
    items +=
        CompletionItem().apply {
          ideLabel = name
          completionKind =
              when {
                member.isAnnotationType -> CompletionItemKind.ANNOTATION_TYPE
                member.isEnum -> CompletionItemKind.ENUM
                member.isInterface -> CompletionItemKind.INTERFACE
                else -> CompletionItemKind.CLASS
              }
          detail = qualifiedName.substringBeforeLast('.', "")
          ideSortText = name
          matchLevel = CompletionItem.matchLevel(name, prefix)
          data = classInfo(member)
        }
  }
}

private fun matchesPrefix(name: String, prefix: String): Boolean =
    CompletionItem.matchLevel(name, prefix) != MatchLevel.NO_MATCH

private fun isAccessible(member: PsiMember, place: PsiElement, accessObject: PsiClass): Boolean =
    PsiUtil.isAccessible(member, place, accessObject)

private fun classInfo(psiClass: PsiClass): ClassCompletionData {
  val qualifiedName = psiClass.qualifiedName ?: psiClass.name.orEmpty()
  val topLevel = generateSequence(psiClass) { it.containingClass }.last()
  return ClassCompletionData(
      className = qualifiedName,
      isCompleteData = true,
      flatName = binaryName(psiClass) ?: qualifiedName,
      simpleName = psiClass.name ?: qualifiedName.substringAfterLast('.'),
      isNested = psiClass.containingClass != null,
      topLevelClass = topLevel.qualifiedName ?: topLevel.name.orEmpty(),
  )
}

private fun binaryName(psiClass: PsiClass): String? {
  val names = ArrayDeque<String>()
  var current: PsiClass? = psiClass
  var topLevel: PsiClass? = psiClass
  while (current != null) {
    names.addFirst(current.name ?: return null)
    topLevel = current
    current = current.containingClass
  }
  val qualified = topLevel?.qualifiedName ?: topLevel?.name ?: return null
  val packageName = qualified.substringBeforeLast('.', "")
  return if (packageName.isEmpty()) names.joinToString("\$") else "$packageName.${names.joinToString("\$")}"
}

private fun erasedTypeName(type: PsiType): String {
  val erased = TypeConversionUtil.erasure(type)
  return when (erased) {
    is PsiArrayType -> erasedTypeName(erased.componentType) + "[]"
    is PsiClassType -> erased.resolve()?.let { binaryName(it) } ?: erased.canonicalText
    is PsiPrimitiveType -> erased.presentableText
    else -> erased.canonicalText
  }
}