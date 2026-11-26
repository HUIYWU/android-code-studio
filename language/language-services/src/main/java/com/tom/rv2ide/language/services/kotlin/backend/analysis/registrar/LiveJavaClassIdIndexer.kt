package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdDataConsumer
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdIndexer
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.id.IdTableBuilding
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.idCache.ConstantPoolParser
import org.jetbrains.kotlin.com.intellij.psi.impl.cache.impl.idCache.JvmIdentifierUtil
import org.jetbrains.kotlin.com.intellij.psi.search.UsageSearchContext
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * Fixes the official `JavaIdIndexer` constant-pool mapping, which writes
 * `startIncl` into the end list so `IdDataConsumer.addOccurrence` drops every
 * class identifier because `end == start`.
 * Official parser components are reused; only the real `(start, end)` pair is written.
 */
internal class LiveJavaClassIdIndexer : IdIndexer {
  override fun map(inputData: FileContent): Map<IdIndexEntry, Int> {
    if (inputData.file.fileType != JavaClassFileType.INSTANCE) {
      return IdTableBuilding.getFileTypeIndexer(inputData.file.fileType)?.map(inputData) ?: emptyMap()
    }
    if (!Registry.`is`(ENABLED_REGISTRY_KEY, true)) return emptyMap()
    return mapConstantPool(inputData)
  }

  // Same constant-pool parsing as the official indexer; only end uses the real bound.
  private fun mapConstantPool(inputData: FileContent): Map<IdIndexEntry, Int> {
    val consumer = IdDataConsumer()
    val starts = IntArrayList()
    val ends = IntArrayList()
    try {
      ConstantPoolParser(
          DataInputStream(ByteArrayInputStream(inputData.content)),
          ConstantPoolParser.Callback { str ->
            ProgressManager.checkCanceled()
            if (str.isNotEmpty() && str[0] != '(') {
              if (JvmIdentifierUtil.collectJvmIdentifiers(str, JvmIdentifierUtil.IdConsumer { _, start, end ->
                    starts.add(start)
                    ends.add(end)
                  })) {
                for (i in 0 until starts.size) {
                  consumer.addOccurrence(
                      str, starts.getInt(i), ends.getInt(i), UsageSearchContext.IN_CODE.toInt(),
                  )
                }
              }
            }
            starts.clear()
            ends.clear()
          },
      ).parse()
    } catch (e: IOException) {
      LOG.warn("Exception while handling file ${inputData.fileName}", e)
      return emptyMap()
    } catch (e: ConstantPoolParser.ClassFormatException) {
      LOG.warn("Exception while handling file ${inputData.fileName}", e)
      return emptyMap()
    }
    return consumer.result
  }

  private companion object {
    const val ENABLED_REGISTRY_KEY = "index.ids.from.java.sources.in.jar"
    val LOG = Logger.getInstance(LiveJavaClassIdIndexer::class.java)
  }
}
