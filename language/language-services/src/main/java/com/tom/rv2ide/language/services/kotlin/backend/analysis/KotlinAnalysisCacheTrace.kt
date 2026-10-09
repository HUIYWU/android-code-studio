package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.lang.ref.Reference
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.IdentityHashMap
import kotlin.Lazy
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.analysisContext

// TODO(XXX-XXX-EXPERIMENT): Remove passive Analysis cache inspection after source identity and invalidation are verified.
internal object KotlinAnalysisCacheTrace {
  private const val API = "org.jetbrains.kotlin.analysis.api."
  private const val LL = "org.jetbrains.kotlin.analysis.low.level.api.fir."
  private const val MAX_ENTRIES = 8

  fun capture(session: KaSession, file: KtFile, traceId: String, stage: String) {
    val reader = Reader()
    fun section(name: String, action: () -> String) {
      val summary = try {
        action()
      } catch (t: Throwable) {
        "unavailable=${failure(t)}"
      }
      KlsLogs.warn("Kotlin Analysis cache trace [$traceId] stage=$stage section=$name $summary")
    }
    val text = file.text
    val physicalContext = file.analysisContext as? KtFile
    val facade = reader.field(session, "resolutionFacade")
    val firSession = reader.invoke(session, "getFirSession\$analysis_api_fir")
    val useSiteModule = reader.invoke(session, "getUseSiteModule")
    val contextModule = reader.field(useSiteModule, "contextModule", optional = true)
    val targets = file.declarations.filterIsInstance<KtNamedDeclaration>().mapNotNull { it.name }.toMutableSet()
    PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java)
        .mapNotNullTo(targets) { it.calleeExpression?.text }
    section("psi") {
      "snapshot=${psi(file, file, text)} original=${identity(file.originalFile)} " +
          "analysisContext=${psi(physicalContext, file, text)} declarations=${declarations(file)}"
    }
    section("session") {
      "ka=${identity(session)} facade=${identity(facade)} ll=${llSession(firSession, reader)} " +
          "useSite=${module(useSiteModule, reader)} context=${module(contextModule, reader)} " +
          "scopeProvider=${identity(reader.field(session, "useSiteScopeDeclarationProvider"))}"
    }
    section("trackers") {
      val trackerFactory = reader.service(file.project, API + "platform.modification.KotlinModificationTrackerFactory")
      val psiTracker = reader.service(file.project, "org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker")
      val inBlock = reader.service(file.project, LL + "file.structure.LLFirInBlockModificationTracker")
      "factory=${identity(trackerFactory)} source=${count(reader.field(trackerFactory, "eventSourceModificationTracker"))} " +
          "library=${count(reader.field(trackerFactory, "eventLibraryModificationTracker"))} " +
          "psi=${count(reader.field(psiTracker, "myModificationCount"))} inBlock=${count(inBlock)}"
    }
    section("declaration-index") {
      val factory = reader.service(file.project, API + "platform.declarations.KotlinDeclarationProviderFactory")
      val indexData = reader.field(factory, "indexData")
      var index = reader.field(indexData, "index")
      val lazyIndex = reader.field(index, "lazyIndex", optional = true)
      if (lazyIndex != null) index = reader.initialized(lazyIndex)
      val maps = listOf("topLevelFunctionsByCallableId", "topLevelPropertiesByCallableId", "classesByClassId")
      val summaries = maps.joinToString(" ") { name ->
        val raw = reader.field(index, name)
        val map = raw as? Map<*, *>
        if (map == null) {
          "$name=${identity(raw)}"
        } else {
          val matches = map.entries.filter { entry ->
            val values = entry.value as? Collection<*> ?: emptyList<Any?>()
            values.any { value ->
              val source = (value as? PsiElement)?.containingFile
              source === file || source === physicalContext || source?.virtualFile?.path == traceIdPath(traceId)
            } || when (val key = entry.key) {
              is CallableId -> key.packageName == file.packageFqName && key.callableName.asString() in targets
              is ClassId -> key.packageFqName == file.packageFqName && key.shortClassName.asString() in targets
              else -> false
            }
          }
          val shown = matches.take(MAX_ENTRIES).joinToString { entry ->
            "${entry.key}=" + (entry.value as? Collection<*>).orEmpty().take(MAX_ENTRIES)
                .joinToString(prefix = "[", postfix = "]") { psi(it as? PsiElement, file, text) }
          }
          "$name{total=${map.size},matches=${matches.size},shown=[$shown]}"
        }
      }
      val packageFactory = reader.service(file.project, API + "platform.packages.KotlinPackageProviderFactory")
      val packageFiles = reader.field(packageFactory, "files")
      "factory=${identity(factory)} index=${identity(index)} $summaries " +
          "packageFactory=${identity(packageFactory)} packageFiles=${identity(packageFiles)} " +
          "snapshotIndexed=${(packageFiles as? Collection<*>)?.any { it === file }}"
    }
    section("ka-cache") {
      val provider = reader.service(file.project, API + "session.KaSessionProvider")
      val cache = reader.field(provider, "cache")
      val entries = reader.caffeineMap(cache)
      val storage = reader.initialized(reader.field(session, "cacheStorage\$delegate"))
      val details = listOf("resolveCallCache", "resolveSymbolCache", "resolveToSymbolsCache").joinToString { name ->
        val cachedValue = reader.initialized(reader.field(storage, "$name\$delegate"))
        val rawData = reader.field(cachedValue, "myData")
        val data = if (rawData is Reference<*>) rawData.get() else rawData
        val value = reader.field(data, "myValue")
        val mapValue = if (value is Map<*, *>) value else reader.field(value, "map")
        val map = mapValue as? Map<*, *>
        "$name{holder=${identity(cachedValue)},rawData=${identity(data)},map=${identity(mapValue)},size=${map?.size}}"
      }
      "provider=${identity(provider)} cache=${identity(cache)} size=${entries?.size} " +
          "containsCurrent=${entries?.values?.any { it === session }} storage=${identity(storage)} $details"
    }
    section("ll-cache") {
      val cache = reader.service(file.project, LL + "sessions.LLFirSessionCache")
      val storage = reader.field(cache, "storage")
      val cacheNames = listOf("sourceCache", "danglingFileSessionCache", "unstableDanglingFileSessionCache")
      val summaries = cacheNames.joinToString(" ") { name ->
        val bucket = reader.field(storage, name)
        val raw = reader.field(bucket, "backingMap")
        val map = raw as? Map<*, *>
        val matches = map?.entries?.filter { entry ->
          entry.key === useSiteModule || entry.key === contextModule ||
              (contextModule != null && reader.field(entry.key, "contextModule", optional = true) === contextModule)
        }.orEmpty()
        val shown = matches.take(MAX_ENTRIES).joinToString { entry ->
          val ref = entry.value
          val existing = if (ref is Reference<*>) ref.get() else reader.field(ref, "referent")
          "key=${module(entry.key, reader)} value=${llSession(existing, reader)} " +
              firFiles(existing, file, text, physicalContext, targets, reader)
        }
        "$name{bucket=${identity(bucket)},rawSize=${map?.size},matches=${matches.size},shown=[$shown]}"
      }
      "cache=${identity(cache)} storage=${identity(storage)} current=${firFiles(firSession, file, text, physicalContext, targets, reader)} $summaries"
    }
    if (reader.errors.isNotEmpty()) section("reflection-access") { reader.errors.joinToString(" | ") }
  }

  private fun traceIdPath(traceId: String): String = traceId.substringBefore(" version=")

  private fun firFiles(
      session: Any?, file: KtFile, text: String, physicalContext: KtFile?, targets: Set<String>, reader: Reader,
  ): String {
    if (session == null || session is Unavailable) return "files=${identity(session)}"
    val components = reader.field(session, "moduleComponents")
    val cache = reader.field(components, "cache")
    val raw = reader.field(cache, "ktFileToFirFile")
    val map = raw as? Map<*, *>
    val structureCache = reader.field(components, "fileStructureCache")
    val structures = reader.field(structureCache, "cache") as? Map<*, *>
    val matches = map?.entries?.filter { entry ->
      val ktFile = entry.key as? KtFile
      ktFile != null && (ktFile === file || ktFile === physicalContext || ktFile.name == file.name)
    }.orEmpty()
    val shown = matches.take(MAX_ENTRIES).joinToString { entry ->
      val structure = structures?.get(entry.key)
      val elements = reader.field(structure, "structureElements") as? Map<*, *>
      "psi=${psi(entry.key as? PsiElement, file, text)} fir=${fir(entry.value, file, text, reader)} " +
          "structure=${identity(structure)} structureElements=${elements?.size} " +
          "calls=${firCalls(entry.value, file, text, targets, reader)}"
    }
    return "fileCache=${identity(cache)} map=${identity(raw)} rawSize=${map?.size} " +
        "snapshotHit=${map?.containsKey(file)} matches=${matches.size} shown=[$shown]"
  }

  private fun firCalls(root: Any?, file: KtFile, text: String, targets: Set<String>, reader: Reader): String {
    if (root == null || root is Unavailable) return identity(root)
    val queue = ArrayDeque<Any>()
    val visited = IdentityHashMap<Any, Boolean>()
    val result = mutableListOf<String>()
    queue.add(root)
    val edges = listOf("declarations", "body", "statements", "result", "initializer", "argumentList", "arguments", "anonymousFunction", "block", "branches", "condition", "explicitReceiver", "selector")
    while (queue.isNotEmpty() && visited.size < 256 && result.size < MAX_ENTRIES) {
      val node = queue.removeFirst()
      if (visited.put(node, true) != null) continue
      val callee = reader.field(node, "calleeReference", optional = true)
      val name = reader.field(callee, "name", optional = true)?.toString()
      if (callee != null && name != null && name in targets) {
        val symbol = reader.field(callee, "resolvedSymbol", optional = true)
        val declaration = reader.field(symbol, "_fir")
        val diagnostic = reader.field(callee, "diagnostic", optional = true)
        result += "$name{call=${identity(node)},reference=${identity(callee)},symbol=${identity(symbol)},target=${fir(declaration, file, text, reader)},diagnostic=${identity(diagnostic)}}"
      }
      edges.forEach { edge ->
        when (val child = reader.field(node, edge, optional = true)) {
          is Collection<*> -> child.filterNotNull().forEach { queue.addLast(it) }
          null, is Unavailable -> Unit
          else -> if (child.javaClass.name.startsWith("org.jetbrains.kotlin.fir.")) queue.addLast(child)
        }
      }
    }
    return "{visited=${visited.size},pending=${queue.size},shown=$result}"
  }

  private fun fir(value: Any?, file: KtFile, text: String, reader: Reader): String {
    if (value == null || value is Unavailable) return identity(value)
    val source = reader.field(value, "source")
    val sourcePsi = reader.field(source, "psi") as? PsiElement
    val moduleData = reader.field(value, "moduleData")
    val boundSession = reader.field(moduleData, "boundSession")
    val declarations = reader.field(value, "declarations", optional = true) as? Collection<*>
    val names = declarations?.take(MAX_ENTRIES)?.map { reader.field(it, "name", optional = true)?.toString() ?: identity(it) }
    return "${identity(value)}{source=${identity(source)},psi=${psi(sourcePsi, file, text)},moduleData=${identity(moduleData)},boundLL=${llSession(boundSession, reader)},declarations=$names}"
  }

  private fun llSession(value: Any?, reader: Reader): String {
    if (value == null || value is Unavailable) return identity(value)
    return "${identity(value)}{valid=${reader.field(value, "isValid")},invalidated=${reader.field(value, "invalidationInformation")},module=${module(reader.field(value, "ktModule"), reader)},cachedStamp=${reader.field(value, "cachedModificationStamp", optional = true)}}"
  }

  private fun module(value: Any?, reader: Reader): String {
    if (value == null || value is Unavailable) return identity(value)
    return "${identity(value)}{name=${reader.field(value, "name", optional = true)},mode=${reader.field(value, "resolutionMode", optional = true)},context=${identity(reader.field(value, "contextModule", optional = true))}}"
  }

  private fun declarations(file: KtFile): String = file.declarations.take(MAX_ENTRIES).joinToString(prefix = "[", postfix = "]") {
    "${(it as? KtNamedDeclaration)?.name}@${it.textRange}"
  }

  private fun psi(value: PsiElement?, snapshot: KtFile, text: String): String {
    if (value == null) return "null"
    val file = value.containingFile ?: return "${identity(value)}{noContainingFile}"
    val fileText = file.text
    val vf = file.virtualFile
    return "${identity(value)}{range=${value.textRange},file=${identity(file)},vf=${identity(vf)},path=${vf?.path},viewVf=${identity(file.viewProvider.virtualFile)},physical=${file.isPhysical},events=${file.viewProvider.isEventSystemEnabled},valid=${file.isValid},stamp=${file.modificationStamp},length=${fileText.length},hash=${fileText.hashCode()},sameSnapshot=${file === snapshot},sameText=${fileText == text}}"
  }

  private fun count(value: Any?): String = when (value) {
    is ModificationTracker -> value.modificationCount.toString()
    else -> identity(value)
  }

  private fun identity(value: Any?): String = when (value) {
    null -> "null"
    is Unavailable -> "unavailable(${value.reason})"
    else -> "${value.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(value))}"
  }

  private fun failure(value: Throwable): String {
    val cause = if (value is InvocationTargetException) value.targetException else value
    return "${cause.javaClass.name}:${cause.message}"
  }

  private data class Unavailable(val reason: String)

  private class Reader {
    val errors = linkedSetOf<String>()

    fun field(target: Any?, name: String, optional: Boolean = false): Any? {
      if (target == null || target is Unavailable) return target
      var type: Class<*>? = target.javaClass
      while (type != null) {
        try {
          val field = type.getDeclaredField(name)
          field.isAccessible = true
          return field.get(target)
        } catch (e: NoSuchFieldException) {
          type = type.superclass
        } catch (t: Throwable) {
          return unavailable("${target.javaClass.name}.$name ${failure(t)}")
        }
      }
      return if (optional) null else unavailable("${target.javaClass.name}.$name missing")
    }

    fun initialized(value: Any?): Any? = when (value) {
      is Lazy<*> -> if (value.isInitialized()) value.value else Unavailable("lazy not initialized")
      else -> value
    }

    fun invoke(target: Any?, name: String): Any? {
      if (target == null || target is Unavailable) return target
      var type: Class<*>? = target.javaClass
      while (type != null) {
        try {
          val method = type.getDeclaredMethod(name)
          method.isAccessible = true
          return method.invoke(target)
        } catch (e: NoSuchMethodException) {
          type = type.superclass
        } catch (t: Throwable) {
          return unavailable("${target.javaClass.name}.$name() ${failure(t)}")
        }
      }
      return unavailable("${target.javaClass.name}.$name() missing")
    }

    fun service(project: Project, className: String): Any? {
      val pico = field(project, "picoContainer")
      val raw = field(pico, "componentKeyToAdapter")
      val adapters = raw as? Map<*, *> ?: return raw
      val adapter = adapters[className] ?: return Unavailable("$className not registered")
      val instance = when {
        adapter.javaClass.name.endsWith("\$InstanceComponentAdapter") -> field(adapter, "componentInstance")
        adapter.javaClass.name.endsWith("CachingConstructorInjectionComponentAdapter") -> field(adapter, "myInstance")
        else -> return unavailable("$className unsupported adapter ${adapter.javaClass.name}")
      }
      return instance ?: Unavailable("$className not instantiated")
    }

    fun caffeineMap(value: Any?): Map<*, *>? {
      if (value == null || value is Unavailable) return null
      return try {
        val api = Class.forName("com.github.benmanes.caffeine.cache.Cache", false, value.javaClass.classLoader)
        api.getMethod("asMap").invoke(value) as? Map<*, *>
      } catch (t: Throwable) {
        unavailable("${value.javaClass.name}.asMap ${failure(t)}")
        null
      }
    }

    private fun unavailable(reason: String): Unavailable {
      errors += reason
      return Unavailable(reason)
    }
  }
}
