package com.tom.rv2ide.language.services.kotlin.backend.analysis.binary

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

internal enum class JvmClassKind {
  CLASS,
  INTERFACE,
  ENUM,
  ANNOTATION,
}

internal data class JvmClassEntry(
    val packageName: String,
    val simpleName: String,
    val kind: JvmClassKind,
)

internal class JvmClassIndex : AutoCloseable {

  private class JarTable(
      val names: Array<String>,
      val kinds: MutableMap<String, JvmClassKind?> = HashMap(),
  )

  private val tables = HashMap<Path, JarTable?>()
  private val tableLocks = HashMap<Path, Any>()

  fun subpackages(packageName: String, sources: Collection<Path>): List<String> {
    if (sources.isEmpty()) return emptyList()
    val prefix = if (packageName.isEmpty()) "" else "$packageName."
    val result = LinkedHashSet<String>()
    for (source in sources) {
      val table = tableFor(source) ?: continue
      var index = lowerBound(table.names, prefix)
      while (index < table.names.size && table.names[index].startsWith(prefix)) {
        val rest = table.names[index].substring(prefix.length)
        val dot = rest.indexOf('.')
        if (dot > 0) result += rest.substring(0, dot)
        index++
      }
    }
    return result.toList()
  }

  fun classesInPackage(packageName: String, sources: Collection<Path>): List<JvmClassEntry> {
    if (sources.isEmpty()) return emptyList()
    val prefix = if (packageName.isEmpty()) "" else "$packageName."
    val result = LinkedHashMap<String, JvmClassEntry>()
    for (source in sources) {
      val table = tableFor(source) ?: continue
      val names = directChildNames(table, prefix)
      if (names.isEmpty()) continue
      val kinds = resolveKinds(source, table, names)
      for (name in names) {
        val kind = kinds[name] ?: continue
        val simpleName = name.substring(prefix.length)
        result.getOrPut(name) { JvmClassEntry(packageName, simpleName, kind) }
      }
    }
    return result.values.toList()
  }

  override fun close() {
    synchronized(tables) {
      tables.clear()
      tableLocks.clear()
    }
  }

  private fun directChildNames(table: JarTable, prefix: String): List<String> {
    val result = ArrayList<String>()
    var index = lowerBound(table.names, prefix)
    while (index < table.names.size && table.names[index].startsWith(prefix)) {
      val rest = table.names[index].substring(prefix.length)
      if (rest.isNotEmpty() && rest.indexOf('.') < 0) result += table.names[index]
      index++
    }
    return result
  }

  private fun tableFor(source: Path): JarTable? {
    val normalized = source.normalize()
    synchronized(tables) { if (tables.containsKey(normalized)) return tables[normalized] }
    val lock = synchronized(tables) { tableLocks.getOrPut(normalized) { Any() } }
    synchronized(lock) {
      synchronized(tables) { if (tables.containsKey(normalized)) return tables[normalized] }
      val built = runCatching { buildTable(normalized) }.onFailure {
        KlsLogs.warn("JVM class index: unable to read {}", normalized, it)
      }.getOrNull()
      synchronized(tables) { tables[normalized] = built }
      return built
    }
  }

  private fun buildTable(source: Path): JarTable? {
    if (!Files.isRegularFile(source)) return null
    val started = System.nanoTime()
    val names = ArrayList<String>(1024)
    JarFile(source.toFile()).use { jar ->
      for (entry in jar.entries()) {
        if (entry.isDirectory) continue
        val entryName = entry.name
        if (!entryName.endsWith(".class")) continue
        if (entryName.startsWith("META-INF/")) continue
        val fileName = entryName.substringAfterLast('/')
        if (fileName == "module-info.class" || fileName == "package-info.class") continue
        if (fileName.contains('$')) continue
        names += entryName.removeSuffix(".class").replace('/', '.')
      }
    }
    names.sort()
    KlsLogs.info(
        "JVM class index: jar={}, classes={}, durationMs={}",
        source,
        names.size,
        (System.nanoTime() - started) / 1_000_000,
    )
    return JarTable(names.toTypedArray())
  }

  private fun resolveKinds(
      source: Path,
      table: JarTable,
      names: List<String>,
  ): Map<String, JvmClassKind?> {
    val result = HashMap<String, JvmClassKind?>(names.size)
    val missing = ArrayList<String>()
    synchronized(table) {
      for (name in names) {
        if (table.kinds.containsKey(name)) result[name] = table.kinds[name] else missing += name
      }
    }
    if (missing.isEmpty()) return result
    val read = readKinds(source, missing) ?: return result
    synchronized(table) {
      for (name in missing) {
        val kind = read[name]
        table.kinds[name] = kind
        result[name] = kind
      }
    }
    return result
  }

  private fun readKinds(source: Path, names: List<String>): Map<String, JvmClassKind>? {
    val result = HashMap<String, JvmClassKind>(names.size)
    val completed =
        runCatching {
              JarFile(source.toFile()).use { jar ->
                for (name in names) {
                  val entry = jar.getEntry(name.replace('.', '/') + ".class") ?: continue
                  val access =
                      jar.getInputStream(entry).use { input -> readAccessFlags(input) } ?: continue
                  if (access and Opcodes.ACC_PUBLIC == 0) continue
                  if (access and Opcodes.ACC_SYNTHETIC != 0) continue
                  result[name] = kindOf(access)
                }
              }
            }
            .onFailure { KlsLogs.warn("JVM class index: unable to read classes from {}", source, it) }
            .isSuccess
    return if (completed) result else null
  }

  private fun readAccessFlags(input: InputStream): Int? {
    var access = -1
    val reader = ClassReader(input)
    val visitor =
        object : ClassVisitor(Opcodes.ASM9) {
          override fun visit(
              version: Int,
              accessFlags: Int,
              name: String,
              signature: String?,
              superName: String?,
              interfaces: Array<out String>?,
          ) {
            access = accessFlags
          }
        }
    reader.accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return access.takeIf { it >= 0 }
  }

  private fun kindOf(access: Int): JvmClassKind =
      when {
        access and Opcodes.ACC_ANNOTATION != 0 -> JvmClassKind.ANNOTATION
        access and Opcodes.ACC_ENUM != 0 -> JvmClassKind.ENUM
        access and Opcodes.ACC_INTERFACE != 0 -> JvmClassKind.INTERFACE
        else -> JvmClassKind.CLASS
      }

  private fun lowerBound(names: Array<String>, key: String): Int {
    var low = 0
    var high = names.size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (names[mid] < key) low = mid + 1 else high = mid
    }
    return low
  }
}
