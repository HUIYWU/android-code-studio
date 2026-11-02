package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassEntry
import com.tom.rv2ide.language.services.kotlin.backend.analysis.binary.JvmClassKind
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.nio.file.Files
import java.nio.file.Path

internal class JavaSourceIndex(
    private val rootsForModule: (String) -> List<Path>,
) : AutoCloseable {

  private class ModuleTable(
      val byPackage: Map<String, List<JvmClassEntry>>,
      val byName: List<JvmClassEntry>,
  )

  private val tables = HashMap<String, ModuleTable?>()
  private val locks = HashMap<String, Any>()

  fun classesInPackage(modulePath: String?, packageName: String): List<JvmClassEntry> {
    val table = tableFor(modulePath) ?: return emptyList()
    return table.byPackage[packageName].orEmpty()
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
      synchronized(tables) {
        if (tables.containsKey(modulePath)) return tables[modulePath]
      }
      val built =
          runCatching { buildTable(modulePath) }.onFailure {
            KlsLogs.warn("Java source index: unable to index module {}", modulePath, it)
          }.getOrNull()
      synchronized(tables) { tables[modulePath] = built }
      return built
    }
  }

  private fun buildTable(modulePath: String): ModuleTable {
    val roots = rootsForModule(modulePath)
    val started = System.nanoTime()
    val files = ArrayList<Path>()
    for (root in roots) {
      if (!Files.isDirectory(root)) continue
      runCatching {
            Files.walk(root).use { stream ->
              stream.iterator().asSequence()
                  .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                  .forEach { files.add(it) }
            }
          }
          .onFailure {
            KlsLogs.warn("Java source index: unable to walk root {}", root, it)
          }
    }
    val entries = LinkedHashSet<JvmClassEntry>()
    for (file in files) {
      val text =
          runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull() ?: continue
      val unit = runCatching { StaticJavaParser.parse(text) }.getOrNull() ?: continue
      val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
      for (type in unit.types) {
        if (!type.isPublic) continue
        val name = type.nameAsString
        val kind =
            when (type) {
              is ClassOrInterfaceDeclaration ->
                  if (type.isInterface) JvmClassKind.INTERFACE else JvmClassKind.CLASS
              is EnumDeclaration -> JvmClassKind.ENUM
              is AnnotationDeclaration -> JvmClassKind.ANNOTATION
              else -> JvmClassKind.CLASS
            }
        entries.add(JvmClassEntry(packageName, name, kind))
      }
    }
    val byName =
        entries.sortedWith(
            compareBy<JvmClassEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.simpleName }
                .thenBy { it.packageName }
        )
    KlsLogs.info(
        "Java source index: module={}, roots={}, classes={}, durationMs={}",
        modulePath,
        roots.size,
        entries.size,
        (System.nanoTime() - started) / 1_000_000,
    )
    return ModuleTable(entries.groupBy { it.packageName }, byName)
  }
}