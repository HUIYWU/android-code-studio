package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassEntry
import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassKind
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Files
import java.nio.file.Path

internal class JavaSourceIndex(
    private val rootsForModule: (String) -> List<Path>,
) : AutoCloseable {

  private class ModuleTable(
      val byPackage: Map<String, List<JvmClassEntry>>,
      val byName: List<JvmClassEntry>,
      val files: List<Path>,
      val entriesByFile: Map<Path, List<JvmClassEntry>>,
  )

  private val tables = HashMap<String, ModuleTable?>()
  private val locks = HashMap<String, Any>()

  // Guarded by [tables]. Incremented whenever a build that started earlier may have read stale
  // inputs, so its result must not be published.
  private var generation = 0L

  /**
   * Drops every cached module table so the next query re-walks the source roots.
   *
   * A table is only produced by a full disk walk, so a Java file created, removed or moved after
   * the first query stays invisible until this is called. Callers own the decision of when the
   * path set changed; [refreshFile] handles content changes of a file the table already lists.
   */
  fun invalidate() {
    synchronized(tables) {
      generation++
      tables.clear()
    }
  }

  /**
   * Re-parses one Java file inside every cached module table that already lists it.
   *
   * The parse reads the FileManager snapshot, so an active document contributes its unsaved text
   * and closing it falls back to disk. Saves and document events are frequent, so this keeps the
   * rest of the table instead of forcing a full walk and parse of the module. A file that no cached
   * table lists needs no action: the next build reads its current content. An unreadable or
   * unparsable revision keeps the previously published entries, so a syntactically incomplete
   * editor state does not make unrelated types disappear.
   */
  fun refreshFile(path: Path) {
    val normalized = path.normalize()
    synchronized(tables) {
      val affected =
          tables.entries
              .filter { (_, table) -> table?.let { normalized in it.files } == true }
              .map { it.key }
      if (affected.isEmpty()) return
      val entries = parseFileEntries(normalized)
      if (entries == null) {
        // An intermediate edit can be unparsable; keep the last usable types instead of dropping
        // them, and leave the published table (and its generation) untouched.
        KlsLogs.debug("Java source index: keeping cached types for unparsable file {}", normalized)
        return
      }
      for (modulePath in affected) {
        val table = tables[modulePath] ?: continue
        val entriesByFile = LinkedHashMap(table.entriesByFile)
        entriesByFile[normalized] = entries
        tables[modulePath] = assembleTable(entriesByFile, table.files)
      }
      // A build that started before this refresh must not republish the content it read.
      generation++
    }
  }

  fun classesInPackage(modulePath: String?, packageName: String): List<JvmClassEntry> {
    val table = tableFor(modulePath) ?: return emptyList()
    return table.byPackage[packageName].orEmpty()
  }

  /**
   * Returns the Java source files visited while building the type table for [modulePath].
   *
   * The table is built on first use for a module, so a cold call walks the module roots like any
   * other query. Callers only get paths: document contents (active or disk) and reference
   * resolution stay their responsibility.
   */
  fun sourceFiles(modulePath: String?): List<Path> {
    val table = tableFor(modulePath) ?: return emptyList()
    return table.files
  }

  fun classesBySimpleNamePrefix(
      modulePath: String?,
      prefix: String,
      limit: Int,
  ): List<JvmClassEntry> {
    if (limit <= 0) return emptyList()
    val table = tableFor(modulePath) ?: return emptyList()
    val result = ArrayList<JvmClassEntry>(minOf(limit, 16))
    for (entry in table.byName) {
      if (!entry.simpleName.startsWith(prefix, ignoreCase = true)) continue
      result += entry
      if (result.size >= limit) break
    }
    return result
  }

  override fun close() {
    synchronized(tables) {
      generation++
      tables.clear()
      locks.clear()
    }
  }

  private fun tableFor(modulePath: String?): ModuleTable? {
    if (modulePath == null) return null
    synchronized(tables) {
      if (tables.containsKey(modulePath)) return tables[modulePath]
    }
    val lock = synchronized(tables) { locks.getOrPut(modulePath) { Any() } }
    synchronized(lock) {
      val generationAtBuild =
          synchronized(tables) {
            if (tables.containsKey(modulePath)) return tables[modulePath]
            generation
          }
      val built =
          runCatching { buildTable(modulePath) }.onFailure {
            KlsLogs.warn("Java source index: unable to index module {}", modulePath, it)
          }.getOrNull()
      synchronized(tables) {
        // A build that raced with invalidate() must not republish the path set it walked, nor
        // answer this request with it. The next query rebuilds against the current roots.
        if (generation != generationAtBuild) return null
        tables[modulePath] = built
      }
      return built
    }
  }

  private fun buildTable(modulePath: String): ModuleTable {
    val roots = rootsForModule(modulePath)
    val started = System.nanoTime()
    val files = LinkedHashSet<Path>()
    for (root in roots) {
      if (!Files.isDirectory(root)) continue
      runCatching {
            Files.walk(root).use { stream ->
              stream.iterator().asSequence()
                  .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                  .forEach { files.add(it.normalize()) }
            }
          }
          .onFailure {
            KlsLogs.warn("Java source index: unable to walk root {}", root, it)
          }
    }
    val entriesByFile = LinkedHashMap<Path, List<JvmClassEntry>>()
    for (file in files) {
      entriesByFile[file] = parseFileEntries(file).orEmpty()
    }
    val table = assembleTable(entriesByFile, files.toList())
    KlsLogs.info(
        "Java source index: module={}, roots={}, classes={}, durationMs={}",
        modulePath,
        roots.size,
        table.byName.size,
        (System.nanoTime() - started) / 1_000_000,
    )
    return table
  }

  private fun parseFileEntries(file: Path): List<JvmClassEntry>? {
    val text =
        runCatching { FileManager.getDocumentContents(file) }.getOrNull() ?: return null
    val unit = runCatching { StaticJavaParser.parse(text) }.getOrNull() ?: return null
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return unit.types.mapNotNull { type ->
      if (!type.isPublic) return@mapNotNull null
      val kind =
          when (type) {
            is ClassOrInterfaceDeclaration ->
                if (type.isInterface) JvmClassKind.INTERFACE else JvmClassKind.CLASS
            is EnumDeclaration -> JvmClassKind.ENUM
            is AnnotationDeclaration -> JvmClassKind.ANNOTATION
            else -> JvmClassKind.CLASS
          }
      JvmClassEntry(packageName, type.nameAsString, kind)
    }
  }

  private fun assembleTable(
      entriesByFile: Map<Path, List<JvmClassEntry>>,
      files: List<Path>,
  ): ModuleTable {
    val entries = LinkedHashSet<JvmClassEntry>()
    entriesByFile.values.forEach { entries.addAll(it) }
    val byName =
        entries.sortedWith(
            compareBy<JvmClassEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.simpleName }
                .thenBy { it.packageName }
        )
    return ModuleTable(entries.groupBy { it.packageName }, byName, files, entriesByFile)
  }
}