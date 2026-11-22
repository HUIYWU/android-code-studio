package com.tom.rv2ide.language.services.java.providers.completion

import com.tom.rv2ide.language.services.interop.JavaPsiInterop
import com.tom.rv2ide.language.services.java.edits.ClassImportEditHandler
import com.tom.rv2ide.language.services.java.kotlin.KotlinClassOutputProvider
import com.tom.rv2ide.language.services.java.models.JavaCompletionItem
import com.tom.rv2ide.lsp.models.ClassCompletionData
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.MatchLevel
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.projects.util.BootClasspathProvider
import com.tom.rv2ide.utils.ClassTrie
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.nio.file.Path
import java.util.PriorityQueue

internal object JavaCompletionTypeIndex {
  private val typeOrder = compareBy<IndexedType>({ it.match }, { it.name }, { it.qualifiedName })

  @JvmStatic
  fun bootClasspaths(module: ModuleProject?): Set<String> = when (module) {
    null -> setOfNotNull(Environment.ANDROID_JAR?.absolutePath)
    is AndroidModule -> module.bootClassPaths.mapTo(LinkedHashSet(), File::getPath)
    is JavaModule -> {
      val classpaths = LinkedHashSet<String>()
      for (dependency in module.getCompileModuleProjects()) {
        if (dependency is AndroidModule) dependency.bootClassPaths.mapTo(classpaths, File::getPath)
      }
      if (classpaths.isEmpty()) module.inheritedBootClassPaths.filterNotNull().mapTo(classpaths, File::getPath)
      if (classpaths.isEmpty()) {
        for (path in module.getCompileClasspaths()) {
          if (path.name == "android.jar") classpaths.add(path.path)
        }
      }
      classpaths
    }
    else -> emptySet()
  }

  private fun kotlinTypes(module: ModuleProject, cancelChecker: ICancelChecker): Sequence<String> = sequence {
    yieldAll(JavaPsiInterop.kotlinTopLevelTypesSnapshot(module.path, cancelChecker).types.orEmpty())
    yieldAll(KotlinClassOutputProvider.publicDependencyTopLevelTypes(module, cancelChecker))
  }

  private fun classTries(module: ModuleProject?): Sequence<ClassTrie> = sequence {
    if (module != null) {
      yield(module.compileJavaSourceClasses)
      yield(module.compileClasspathClasses)
    }
    yieldAll(BootClasspathProvider.getTopLevelClassTries(bootClasspaths(module)))
  }

  fun forEachImportChild(
      module: ModuleProject?,
      packageName: String,
      recognizeKotlin: Boolean,
      cancelChecker: ICancelChecker,
      accept: (name: String, qualifiedName: String, isType: Boolean) -> Unit,
  ) {
    for (trie in classTries(module)) {
      cancelChecker.abortIfCancelled()
      val node = if (packageName.isEmpty()) trie.root else trie.findNode(packageName) ?: continue
      if (node.isClass) continue
      for (child in node.children.values) {
        cancelChecker.abortIfCancelled()
        if (packageName.isNotEmpty() || !child.isClass) accept(child.name, child.qualifiedName, child.isClass)
      }
    }
    if (module == null || !recognizeKotlin) return
    val prefix = if (packageName.isEmpty()) "" else "$packageName."
    for (type in kotlinTypes(module, cancelChecker)) {
      cancelChecker.abortIfCancelled()
      if (!type.startsWith(prefix)) continue
      val separator = type.indexOf('.', prefix.length)
      if (separator >= 0) {
        accept(type.substring(prefix.length, separator), type.substring(0, separator), false)
      } else if (packageName.isNotEmpty() && type.length > prefix.length) {
        accept(type.substring(prefix.length), type, true)
      }
    }
  }

  fun addImportableItems(
      items: MutableList<CompletionItem>,
      file: Path,
      imports: Set<String>,
      visibleTypeNames: Set<String>,
      module: ModuleProject?,
      prefix: String,
      recognizeKotlin: Boolean,
      cancelChecker: ICancelChecker,
  ): Boolean {
    val maxItems = CompletionResult.MAX_ITEMS
    val best = PriorityQueue(maxItems, typeOrder.reversed())
    val qualifiedNames = HashSet<String>()
    var incomplete = false
    fun offer(name: String, qualifiedName: String) {
      if (name in visibleTypeNames || qualifiedName in qualifiedNames) return
      val match = CompletionItem.matchLevel(name, prefix)
      if (match == MatchLevel.NO_MATCH) return
      if (best.size == maxItems) {
        incomplete = true
        val worst = best.element()
        if (match > worst.match || match == worst.match &&
            (name > worst.name || name == worst.name && qualifiedName >= worst.qualifiedName)) return
        qualifiedNames.remove(best.remove().qualifiedName)
      }
      qualifiedNames.add(qualifiedName)
      best.add(IndexedType(name, qualifiedName, match))
    }

    fun visit(node: ClassTrie.Node) {
      cancelChecker.abortIfCancelled()
      if (node.isClass) offer(node.name, node.qualifiedName)
      for (child in node.children.values) visit(child)
    }

    for (trie in classTries(module)) visit(trie.root)
    if (module != null && recognizeKotlin) {
      for (name in kotlinTypes(module, cancelChecker)) {
        cancelChecker.abortIfCancelled()
        offer(name.substringAfterLast('.'), name)
      }
    }
    if (best.isEmpty()) return false
    val importEdit = ClassImportEditHandler(imports, file)
    for (type in best.sortedWith(typeOrder)) {
      cancelChecker.abortIfCancelled()
      items += indexedClassItem(type.qualifiedName, type.match, importEdit)
    }
    return incomplete
  }

  private class IndexedType(val name: String, val qualifiedName: String, val match: MatchLevel)
}

internal fun indexedClassItem(className: String, match: MatchLevel, importEdit: ClassImportEditHandler?) =
    JavaCompletionItem().apply {
      ideLabel = className.substringAfterLast('.')
      completionKind = CompletionItemKind.CLASS
      detail = className.substringBeforeLast('.', className)
      ideSortText = ideLabel
      matchLevel = match
      data = ClassCompletionData(className)
      additionalEditHandler = importEdit
    }
