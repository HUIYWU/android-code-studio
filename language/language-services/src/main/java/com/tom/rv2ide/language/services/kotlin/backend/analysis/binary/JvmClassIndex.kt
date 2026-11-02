@file:OptIn(kotlin.metadata.ExperimentalContextReceivers::class)
@file:Suppress("DEPRECATION_ERROR")

package com.tom.rv2ide.language.services.kotlin.backend.analysis.binary

import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassHeader
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import org.objectweb.asm.AnnotationVisitor
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

internal enum class JvmCallableKind {
  FUNCTION,
  PROPERTY,
}

internal data class JvmCallableEntry(
    val packageName: String,
    val name: String,
    val kind: JvmCallableKind,
)

internal class JvmClassIndex : AutoCloseable {

  private class JarTable(
      val names: Array<String>,
      val simpleOrder: IntArray,
      val kinds: MutableMap<String, JvmClassKind?> = HashMap(),
  )

  private class CallableTable(val entries: List<JvmCallableEntry>)

  private val tables = HashMap<Path, JarTable?>()
  private val tableLocks = HashMap<Path, Any>()
  private val callableTables = HashMap<Path, CallableTable?>()
  private val callableLocks = HashMap<Path, Any>()

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

  fun classesBySimpleNamePrefix(
      prefix: String,
      sources: Collection<Path>,
      limit: Int,
  ): List<JvmClassEntry> {
    if (sources.isEmpty() || limit <= 0) return emptyList()
    val result = LinkedHashMap<String, JvmClassEntry>()
    val scanLimit = limit * 8
    for (source in sources) {
      val table = tableFor(source) ?: continue
      var index = simpleLowerBound(table, prefix)
      val candidates = ArrayList<String>()
      var scanned = 0
      while (index < table.simpleOrder.size && scanned < scanLimit && result.size < limit) {
        val name = table.names[table.simpleOrder[index]]
        if (!simpleNameMatchesPrefix(name, prefix)) break
        candidates += name
        index++
        scanned++
      }
      if (candidates.isEmpty()) continue
      val kinds = resolveKinds(source, table, candidates)
      for (name in candidates) {
        val kind = kinds[name] ?: continue
        val lastDot = name.lastIndexOf('.')
        val packageName = if (lastDot <= 0) "" else name.substring(0, lastDot)
        val simpleName = if (lastDot < 0) name else name.substring(lastDot + 1)
        result.getOrPut(name) { JvmClassEntry(packageName, simpleName, kind) }
        if (result.size >= limit) break
      }
    }
    return result.values.toList()
  }

  fun callablesBySimpleNamePrefix(
      prefix: String,
      sources: Collection<Path>,
      limit: Int,
  ): List<JvmCallableEntry> {
    if (sources.isEmpty() || limit <= 0) return emptyList()
    val result = LinkedHashMap<String, JvmCallableEntry>()
    for (source in sources) {
      val table = callableTableFor(source) ?: continue
      var index = callableLowerBound(table.entries, prefix)
      while (index < table.entries.size && result.size < limit) {
        val entry = table.entries[index]
        if (!nameMatchesPrefix(entry.name, prefix)) break
        result.getOrPut("${entry.kind}:${entry.packageName}.${entry.name}") { entry }
        index++
      }
    }
    return result.values.toList()
  }

  override fun close() {
    synchronized(tables) {
      tables.clear()
      tableLocks.clear()
    }
    synchronized(callableTables) {
      callableTables.clear()
      callableLocks.clear()
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
    val namesArray = names.toTypedArray()
    return JarTable(namesArray, buildSimpleOrder(namesArray))
  }

  private fun callableTableFor(source: Path): CallableTable? {
    val normalized = source.normalize()
    synchronized(callableTables) {
      if (callableTables.containsKey(normalized)) return callableTables[normalized]
    }
    val lock = synchronized(callableTables) { callableLocks.getOrPut(normalized) { Any() } }
    synchronized(lock) {
      synchronized(callableTables) {
        if (callableTables.containsKey(normalized)) return callableTables[normalized]
      }
      val built =
          runCatching { buildCallableTable(normalized) }.onFailure {
            KlsLogs.warn("JVM callable index: unable to read {}", normalized, it)
          }.getOrNull()
      synchronized(callableTables) { callableTables[normalized] = built }
      return built
    }
  }

  private fun buildCallableTable(source: Path): CallableTable? {
    if (!Files.isRegularFile(source)) return null
    val started = System.nanoTime()
    val entries = readFacadeCallables(source) ?: return null
    val sorted =
        entries.sortedWith(
            compareBy<JvmCallableEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.packageName }
                .thenBy { it.kind.name }
        )
    KlsLogs.info(
        "JVM callable index: jar={}, callables={}, durationMs={}",
        source,
        sorted.size,
        (System.nanoTime() - started) / 1_000_000,
    )
    return CallableTable(sorted)
  }

  private fun readFacadeCallables(source: Path): List<JvmCallableEntry>? {
    val result = LinkedHashMap<String, JvmCallableEntry>()
    var metadataFailureLogged = false
    val completed =
        runCatching {
              JarFile(source.toFile()).use { jar ->
                for (entry in jar.entries()) {
                  if (entry.isDirectory) continue
                  val entryName = entry.name
                  if (!entryName.endsWith("Kt.class")) continue
                  if (entryName.startsWith("META-INF/")) continue
                  val fileName = entryName.substringAfterLast('/')
                  if (fileName.contains('$')) continue
                  val header =
                      jar.getInputStream(entry).use { input -> readMetadataHeader(input) } ?: continue
                  val metadata =
                      runCatching { KotlinClassMetadata.readStrict(header) }
                          .onFailure {
                            if (!metadataFailureLogged) {
                              metadataFailureLogged = true
                              KlsLogs.warn(
                                  "JVM callable index: metadata parse failed for {} in {}",
                                  entryName,
                                  source,
                              )
                            }
                          }
                          .getOrNull() ?: continue
                  val kmPackage =
                      when (metadata) {
                        is KotlinClassMetadata.FileFacade -> metadata.kmPackage
                        is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage
                        else -> null
                      } ?: continue
                  val packageName =
                      entryName.removeSuffix(".class").substringBeforeLast('/', "").replace('/', '.')
                  for (function in kmPackage.functions) {
                    if (function.visibility != Visibility.PUBLIC) continue
                    if (function.receiverParameterType != null) continue
                    if (function.contextReceiverTypes.isNotEmpty()) continue
                    val name = function.name
                    if (name.isEmpty()) continue
                    result.getOrPut("F:$packageName.$name") {
                      JvmCallableEntry(packageName, name, JvmCallableKind.FUNCTION)
                    }
                  }
                  for (property in kmPackage.properties) {
                    if (property.visibility != Visibility.PUBLIC) continue
                    if (property.receiverParameterType != null) continue
                    if (property.contextReceiverTypes.isNotEmpty()) continue
                    val name = property.name
                    if (name.isEmpty()) continue
                    result.getOrPut("P:$packageName.$name") {
                      JvmCallableEntry(packageName, name, JvmCallableKind.PROPERTY)
                    }
                  }
                }
              }
            }
            .onFailure {
              KlsLogs.warn("JVM callable index: unable to read callables from {}", source, it)
            }
            .isSuccess
    return if (completed) result.values.toList() else null
  }

  private fun readMetadataHeader(input: InputStream): KotlinClassHeader? {
    val values = HashMap<String, Any?>()
    val arrays = HashMap<String, MutableList<Any?>>()
    val collector =
        object : AnnotationVisitor(Opcodes.ASM9) {
          override fun visit(name: String?, value: Any?) {
            if (name != null) values[name] = value
          }

          override fun visitArray(name: String?): AnnotationVisitor? {
            if (name == null) return null
            val list = ArrayList<Any?>()
            arrays[name] = list
            return object : AnnotationVisitor(Opcodes.ASM9) {
              override fun visit(n: String?, v: Any?) {
                list += v
              }
            }
          }
        }
    var found = false
    val reader = ClassReader(input)
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9) {
          override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
            if (descriptor != "Lkotlin/Metadata;") return null
            found = true
            return collector
          }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    if (!found) return null
    val kind = values["k"] as? Int
    val mv =
        (values["mv"] as? IntArray)
            ?: arrays["mv"]?.filterIsInstance<Int>()?.toIntArray()
    val d1 =
        (values["d1"] as? Array<*>)?.filterIsInstance<String>()?.toTypedArray()
            ?: arrays["d1"]?.filterIsInstance<String>()?.toTypedArray()
    val d2 =
        (values["d2"] as? Array<*>)?.filterIsInstance<String>()?.toTypedArray()
            ?: arrays["d2"]?.filterIsInstance<String>()?.toTypedArray()
    val xs = values["xs"] as? String
    val pn = values["pn"] as? String
    val xi = values["xi"] as? Int
    return KotlinClassHeader(kind, mv, d1, d2, xs, pn, xi)
  }

  private fun callableLowerBound(entries: List<JvmCallableEntry>, prefix: String): Int {
    var low = 0
    var high = entries.size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (compareNameToPrefix(entries[mid].name, prefix) < 0) low = mid + 1 else high = mid
    }
    return low
  }

  private fun compareNameToPrefix(name: String, prefix: String): Int {
    var nameIndex = 0
    var prefixIndex = 0
    while (nameIndex < name.length && prefixIndex < prefix.length) {
      val diff =
          name[nameIndex].lowercaseChar().compareTo(prefix[prefixIndex].lowercaseChar())
      if (diff != 0) return diff
      nameIndex++
      prefixIndex++
    }
    return (name.length - nameIndex) - (prefix.length - prefixIndex)
  }

  private fun nameMatchesPrefix(name: String, prefix: String): Boolean {
    if (name.length < prefix.length) return false
    for (index in prefix.indices) {
      if (name[index].lowercaseChar() != prefix[index].lowercaseChar()) return false
    }
    return true
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

  private fun simpleLowerBound(table: JarTable, prefix: String): Int {
    var low = 0
    var high = table.simpleOrder.size
    while (low < high) {
      val mid = (low + high) ushr 1
      if (compareSimpleToPrefix(table.names[table.simpleOrder[mid]], prefix) < 0) {
        low = mid + 1
      } else {
        high = mid
      }
    }
    return low
  }

  private fun compareSimpleToPrefix(name: String, prefix: String): Int {
    val start = name.lastIndexOf('.') + 1
    var nameIndex = start
    var prefixIndex = 0
    while (nameIndex < name.length && prefixIndex < prefix.length) {
      val diff =
          name[nameIndex].lowercaseChar().compareTo(prefix[prefixIndex].lowercaseChar())
      if (diff != 0) return diff
      nameIndex++
      prefixIndex++
    }
    return (name.length - nameIndex) - (prefix.length - prefixIndex)
  }

  private fun simpleNameMatchesPrefix(name: String, prefix: String): Boolean {
    val start = name.lastIndexOf('.') + 1
    if (name.length - start < prefix.length) return false
    for (index in prefix.indices) {
      if (name[start + index].lowercaseChar() != prefix[index].lowercaseChar()) return false
    }
    return true
  }

  private fun buildSimpleOrder(names: Array<String>): IntArray {
    val order = IntArray(names.size) { it }
    val aux = IntArray(names.size)
    mergeSort(names, order, aux, 0, order.size)
    return order
  }

  private fun mergeSort(names: Array<String>, order: IntArray, aux: IntArray, low: Int, high: Int) {
    if (high - low < 2) return
    val mid = (low + high) ushr 1
    mergeSort(names, order, aux, low, mid)
    mergeSort(names, order, aux, mid, high)
    var left = low
    var right = mid
    var target = low
    while (left < mid && right < high) {
      if (compareSimpleNames(names, order[left], order[right]) <= 0) {
        aux[target++] = order[left++]
      } else {
        aux[target++] = order[right++]
      }
    }
    while (left < mid) aux[target++] = order[left++]
    while (right < high) aux[target++] = order[right++]
    System.arraycopy(aux, low, order, low, high - low)
  }

  private fun compareSimpleNames(names: Array<String>, left: Int, right: Int): Int {
    val leftName = names[left]
    val rightName = names[right]
    var leftIndex = leftName.lastIndexOf('.') + 1
    var rightIndex = rightName.lastIndexOf('.') + 1
    while (leftIndex < leftName.length && rightIndex < rightName.length) {
      val diff =
          leftName[leftIndex].lowercaseChar().compareTo(rightName[rightIndex].lowercaseChar())
      if (diff != 0) return diff
      leftIndex++
      rightIndex++
    }
    val lengthDiff = (leftName.length - leftIndex) - (rightName.length - rightIndex)
    if (lengthDiff != 0) return lengthDiff
    return leftName.compareTo(rightName)
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
