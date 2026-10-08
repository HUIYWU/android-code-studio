/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.language.services.kotlin.backend.fwcd

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendState
import com.tom.rv2ide.language.services.kotlin.backend.KotlinBackendConnection
import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.document.kotlinDocumentKind
import com.tom.rv2ide.language.services.kotlin.document.KotlinDocumentKind
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.lsp.models.DiagnosticResult
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable stdio JSON-RPC transport base for Kotlin LSP backends.
 *
 * Subclasses only need to provide a started [Process]. Request tracking, message
 * framing, reader threads, JSON parsing and diagnostics dispatch stay shared.
 */
abstract class BaseStdioKotlinBackendConnection : KotlinBackendConnection {
  companion object {
    private const val BUFFER_SIZE = 32768
    private const val MAX_CONTENT_LENGTH = 10485760
  }

  private val gson = Gson()
  private var process: Process? = null
  private var writer: BufferedWriter? = null
  private var input: BufferedInputStream? = null
  @Volatile private var ready = false
  @Volatile private var initialized = false
  @Volatile private var failed = false
  @Volatile private var closed = false
  @Volatile private var backendGeneration = 0L

  override val isReady: Boolean
    get() = ready && !failed && !closed

  override val state: KotlinBackendState
    get() = when {
      closed -> KotlinBackendState.CLOSED
      failed -> KotlinBackendState.FAILED
      initialized -> KotlinBackendState.READY
      ready -> KotlinBackendState.STARTING
      else -> KotlinBackendState.NEW
    }

  override val generation: Long
    get() = backendGeneration

  override val isInitialized: Boolean
    get() = initialized

  override fun markInitialized() {
    if (isReady) {
      failed = false
      initialized = true
    }
  }

  override fun markInitializationFailed() {
    initialized = false
    ready = false
    failed = true
    try {
      writer?.close()
    } catch (_: Exception) {}
    try {
      input?.close()
    } catch (_: Exception) {}
    try {
      process?.destroy()
    } catch (_: Exception) {}
    process = null
    writer = null
    input = null
    pendingRequests.values.forEach { callback ->
      try {
        callback.invoke(null)
      } catch (e: Exception) {
        KlsLogs.warn("Failed to notify pending Kotlin LSP request about initialization failure", e)
      }
    }
    pendingRequests.clear()
  }

  override fun refreshEnvironment(classpathProvider: KotlinProjectClasspathProvider): Boolean {
    if (!isReady || !initialized) return false
    backendGeneration++
    return true
  }

  private val nextId = AtomicInteger(1)
  private val pendingRequests = ConcurrentHashMap<Int, (JsonObject?) -> Unit>()
  private val notificationHandler = KotlinNotificationHandler()
  private val executorService = Executors.newFixedThreadPool(2)

  protected abstract fun startProcess(classpathProvider: KotlinProjectClasspathProvider): Process?

  protected open fun onProcessStarted(process: Process, classpathProvider: KotlinProjectClasspathProvider) = Unit

  protected open fun onProcessStartFailed(error: Exception) {
    KlsLogs.error("Failed to start Kotlin LSP backend process", error)
  }

  protected open fun logPrefix(): String = "Kotlin LSP backend"

  protected fun currentProcess(): Process? = process

  override fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    notificationHandler.setDiagnosticsCallback(callback)
  }

  override fun supportsDocument(path: java.nio.file.Path): Boolean =
      path.kotlinDocumentKind(isGradleScript = true) != KotlinDocumentKind.UNSUPPORTED

  override fun initialize(params: JsonObject, callback: (JsonObject?) -> Unit) {
    sendRequest("initialize", params, callback)
  }

  override fun startBackend(classpathProvider: KotlinProjectClasspathProvider): Boolean {
    if (closed) return false
    if (ready && process?.isAlive == true) {
      failed = false
      KlsLogs.debugThrottled("kls:already-running", 3000L, "{} already running", logPrefix())
      return true
    }

    ready = false
    initialized = false
    failed = false
    try {
      val startedProcess = startProcess(classpathProvider) ?: run {
        KlsLogs.error("{} launch did not produce a process; initialize will not be sent", logPrefix())
        failed = true
        return false
      }
      process = startedProcess
      writer =
          BufferedWriter(
              OutputStreamWriter(startedProcess.outputStream, StandardCharsets.UTF_8),
              BUFFER_SIZE,
          )
      input = BufferedInputStream(startedProcess.inputStream, BUFFER_SIZE)

      startReaderThread()
      startErrorReaderThread(startedProcess)
      startExitWatcher(startedProcess)
      onProcessStarted(startedProcess, classpathProvider)
      if (startedProcess.waitFor(150, TimeUnit.MILLISECONDS)) {
        KlsLogs.error(
            "{} exited during launch readiness check: processId={}, exitCode={}; initialize will not be sent",
            logPrefix(),
            processId(startedProcess),
            startedProcess.exitValue(),
        )
        ready = false
        failed = true
        return false
      }
      backendGeneration++
      ready = true
      KlsLogs.info("{} transport ready: processId={}", logPrefix(), processId(startedProcess))
      return true
    } catch (e: Exception) {
      ready = false
      failed = true
      onProcessStartFailed(e)
      return false
    }
  }

  override fun sendRequest(method: String, params: JsonObject, callback: (JsonObject?) -> Unit) {
    if (!isReady && method != "initialize" && method != "shutdown") {
      callback.invoke(null)
      return
    }
    val id = nextId.getAndIncrement()
    pendingRequests[id] = callback

    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("id", id)
          addProperty("method", method)
          add("params", params)
        }

    KlsLogs.debugThrottled("kls:request:$method", 1500L, "Sending request: {}", method)
    try {
      sendMessageOrThrow(payload)
    } catch (e: Exception) {
      pendingRequests.remove(id)
      ready = false
      KlsLogs.error("Failed to send request: {}", method, e)
      callback.invoke(null)
    }
  }

  override fun sendNotification(method: String, params: JsonObject) {
    if (!isReady && method != "exit") {
      return
    }
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("method", method)
          add("params", params)
        }

    KlsLogs.debugThrottled("kls:notification:$method", 1500L, "Sending notification: {}", method)
    sendMessage(payload)
  }

  override fun sendNotificationOrThrow(method: String, params: JsonObject) {
    if (!isReady && method != "exit") {
      throw IllegalStateException("${logPrefix()} is not ready")
    }
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("method", method)
          add("params", params)
        }

    KlsLogs.debugThrottled("kls:notification-throw:$method", 1500L, "Sending notification: {}", method)
    sendMessageOrThrow(payload)
  }

  protected fun sendMessage(payload: JsonObject) {
    try {
      sendMessageOrThrow(payload)
    } catch (e: Exception) {
      ready = false
      KlsLogs.error("Failed to send message", e)
    }
  }

  protected fun sendMessageOrThrow(payload: JsonObject) {
    val data = gson.toJson(payload)
    val w = writer ?: throw IllegalStateException("Cannot send message: writer is null")

    try {
      synchronized(w) {
        val contentBytes = data.toByteArray(StandardCharsets.UTF_8)
        w.write("Content-Length: ${contentBytes.size}\r\n\r\n")
        w.write(data)
        w.flush()
      }
    } catch (e: Exception) {
      ready = false
      throw e
    }
  }

  private fun startReaderThread() {
    val stream = input ?: return
    Thread(
            {
              try {
                while (true) {
                  var contentLength = -1
                  while (true) {
                    val line = readAsciiHeaderLine(stream) ?: return@Thread
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                      contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                  }

                  if (contentLength <= 0 || contentLength > MAX_CONTENT_LENGTH) {
                    KlsLogs.warn("Invalid content length: {}", contentLength)
                    continue
                  }

                  val payload = ByteArray(contentLength)
                  var totalRead = 0
                  while (totalRead < contentLength) {
                    val read = stream.read(payload, totalRead, contentLength - totalRead)
                    if (read < 0) break
                    totalRead += read
                  }
                  if (totalRead != contentLength) {
                    KlsLogs.warn(
                        "Incomplete JSON-RPC payload: expected={}, actual={}",
                        contentLength,
                        totalRead,
                    )
                    return@Thread
                  }

                  val json = String(payload, StandardCharsets.UTF_8)
                  executorService.submit { handleMessage(json) }
                }
              } catch (e: Exception) {
                KlsLogs.error("Error in reader thread", e)
              }
            },
            "kls-jsonrpc-reader",
        )
        .apply { priority = Thread.MAX_PRIORITY }
        .start()
  }

  private fun readAsciiHeaderLine(stream: InputStream): String? {
    val line = StringBuilder()
    while (true) {
      val next = stream.read()
      if (next < 0) return if (line.isEmpty()) null else line.toString()
      when (next) {
        '\n'.code -> return line.toString()
        '\r'.code -> Unit
        else -> line.append(next.toChar())
      }
    }
  }

  private fun processId(process: Process): String = Integer.toHexString(System.identityHashCode(process))

  private fun startExitWatcher(startedProcess: Process) {
    Thread(
            {
              try {
                val exitCode = startedProcess.waitFor()
                if (process === startedProcess) {
                   ready = false
                   initialized = false
                   failed = !closed
                   process = null
                   writer = null
                   input = null
                   pendingRequests.values.forEach { callback ->
                     try {
                       callback.invoke(null)
                     } catch (e: Exception) {
                       KlsLogs.warn("Failed to notify pending Kotlin LSP request about process exit", e)
                     }
                   }
                   pendingRequests.clear()
                 }
                KlsLogs.error(
                    "{} process exited: processId={}, exitCode={}. Review preceding '{} stderr' lines for the root cause.",
                    logPrefix(),
                    processId(startedProcess),
                    exitCode,
                    logPrefix(),
                )
              } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
              } catch (e: Exception) {
                KlsLogs.error("Failed while waiting for ${logPrefix()} process exit", e)
              }
            },
            "kls-process-exit-watcher",
        )
        .apply { isDaemon = true }
        .start()
  }

  private fun startErrorReaderThread(startedProcess: Process) {
    val errorReader =
        BufferedReader(
            InputStreamReader(startedProcess.errorStream, StandardCharsets.UTF_8),
            BUFFER_SIZE,
        )
    Thread(
            {
              try {
                var line: String?
                while (errorReader.readLine().also { line = it } != null) {
                  val stderrLine = line ?: continue
                  if (stderrLine.isBlank()) continue
                  KlsLogs.warn(
                      "{} stderr: {}",
                      logPrefix(),
                      stderrLine.take(500),
                  )
                }
              } catch (e: Exception) {
                KlsLogs.error("Error in error reader thread", e)
              }
            },
            "kls-error-reader",
        )
        .start()
  }

  private fun handleMessage(json: String) {
    try {
      val obj = gson.fromJson(json, JsonObject::class.java)

      if (obj.has("id")) {
        val id = obj.get("id").asInt
        val callback = pendingRequests.remove(id)

        if (callback == null) {
          KlsLogs.warn("No callback found for request ID: {}", id)
          return
        }

        if (obj.has("error")) {
          val error = obj.getAsJsonObject("error")
          val errorMsg = error.get("message")?.asString ?: "Unknown error"
          val errorCode = error.get("code")?.asInt ?: -1
          KlsLogs.error("LSP error response for request {}: [{}] {}", id, errorCode, errorMsg)
          callback.invoke(null)
        } else if (obj.has("result")) {
          val result = obj.get("result")
          when {
            result.isJsonNull -> callback.invoke(null)
            result.isJsonArray -> callback.invoke(JsonObject().apply { add("result", result) })
            result.isJsonObject -> callback.invoke(result.asJsonObject)
            result.isJsonPrimitive -> callback.invoke(JsonObject().apply { add("result", result) })
            else -> callback.invoke(null)
          }
        } else {
          KlsLogs.warn("Request {} has neither result nor error", id)
          callback.invoke(null)
        }
      } else if (obj.has("method")) {
        notificationHandler.handle(obj)
      } else {
        KlsLogs.warn("Message has neither id nor method: {}", json.take(200))
      }
    } catch (e: Exception) {
      KlsLogs.error("Error handling message: {}", json.take(200), e)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    initialized = false
    ready = false
    try {
      sendRequest("shutdown", JsonObject()) {}
      sendNotification("exit", JsonObject())
      Thread.sleep(500)
    } catch (e: Exception) {
      KlsLogs.error("Error during shutdown", e)
    }

    executorService.shutdown()

    try {
      writer?.close()
    } catch (_: Exception) {}
    try {
      input?.close()
    } catch (_: Exception) {}
    try {
      process?.destroy()
    } catch (_: Exception) {}

    process = null
    pendingRequests.clear()
  }
}
