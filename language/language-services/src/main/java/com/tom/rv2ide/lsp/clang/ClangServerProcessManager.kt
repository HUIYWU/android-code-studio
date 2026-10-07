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
package com.tom.rv2ide.lsp.clang

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.utils.Environment
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ClangServerProcessManager(private val context: Context) {

  private val gson = Gson()
  private var process: Process? = null
  private var outputStream: OutputStream? = null
  private var inputStream: InputStream? = null
  private var readerThread: Thread? = null
  private var errorReaderThread: Thread? = null
  private val nextId = AtomicInteger(1)
  private val pendingRequests = ConcurrentHashMap<Int, CompletableFuture<JsonElement?>>()
  private val requestTimestamps = ConcurrentHashMap<Int, Long>()
  private var diagnosticsCallback: ((DiagnosticResult) -> Unit)? = null
  private var notificationHandler = ClangNotificationHandler()

  fun setNotificationHandler(handler: ClangNotificationHandler) {
    notificationHandler = handler
    diagnosticsCallback?.let { notificationHandler.setDiagnosticsCallback(it) }
  }

  fun setDiagnosticsCallback(callback: (DiagnosticResult) -> Unit) {
    diagnosticsCallback = callback
    notificationHandler.setDiagnosticsCallback(callback)
  }

  fun startServer(compileCommandsPath: String?) {
    if (process?.isAlive == true) {
      ClangLogs.debug("Server already running")
      return
    }

    ClangLogs.info("Starting Clang Language Server...")

    val clangdExec = File(Environment.PREFIX, "bin/clangd")

    if (!clangdExec.exists()) {
      ClangLogs.error("Clangd not found at: {}", clangdExec.absolutePath)
      return
    }

    val command =
        mutableListOf(
            clangdExec.absolutePath,
            "--background-index",
            "--clang-tidy",
            "--completion-style=detailed",
            "--header-insertion=iwyu",
            "--pch-storage=memory",
        )

    if (compileCommandsPath != null) {
      command.add("--compile-commands-dir=$compileCommandsPath")
    }

    val processBuilder = ProcessBuilder(command).apply { redirectErrorStream(false) }

    try {
      process = processBuilder.start()
      outputStream = process!!.outputStream
      inputStream = BufferedInputStream(process!!.inputStream)

      startReaderThread()
      startErrorReaderThread()

      ClangLogs.info("Clangd started successfully")
    } catch (e: Exception) {
      ClangLogs.error("Failed to start clangd", e)
      failAllPending(e)
    }
  }

  fun sendRequest(method: String, params: JsonObject): CompletableFuture<JsonElement?> {
    val id = nextId.getAndIncrement()
    val future = CompletableFuture<JsonElement?>()
    pendingRequests[id] = future
    requestTimestamps[id] = System.currentTimeMillis()

    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("id", id)
          addProperty("method", method)
          add("params", params)
        }

    ClangLogs.debug(">>> Sending request ID {} ({})", id, method)
    ClangLogs.debug("Request payload: {}", gson.toJson(payload).take(500))

    try {
      sendMessage(payload)
    } catch (e: Exception) {
      pendingRequests.remove(id)
      requestTimestamps.remove(id)
      future.completeExceptionally(e)
    }

    return future
  }

  fun sendNotification(method: String, params: JsonObject) {
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("method", method)
          add("params", params)
        }

    ClangLogs.debug(">>> Sending notification: {}", method)
    try {
      sendMessage(payload)
    } catch (e: Exception) {
      ClangLogs.error("Failed to send notification: {}", method, e)
    }
  }

  fun sendNotificationOrThrow(method: String, params: JsonObject) {
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("method", method)
          add("params", params)
        }

    ClangLogs.debug(">>> Sending notification (throwing): {}", method)
    sendMessage(payload)
  }

  private fun sendMessage(payload: JsonObject) {
    val stream = outputStream ?: throw IllegalStateException("Cannot send message: output stream is null")
    val bodyBytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
    val headerBytes = "Content-Length: ${bodyBytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

    synchronized(stream) {
      stream.write(headerBytes)
      stream.write(bodyBytes)
      stream.flush()
    }
  }

  private fun startReaderThread() {
    val stream = inputStream ?: return
    readerThread =
        Thread(
                {
                  try {
                    while (!Thread.currentThread().isInterrupted) {
                      val headers = readHeaders(stream) ?: break
                      val contentLength = headers["content-length"]?.toIntOrNull()

                      if (contentLength == null || contentLength < 0) {
                        ClangLogs.warn("Skipping message with invalid Content-Length: {}", headers)
                        continue
                      }

                      val body = readBody(stream, contentLength)
                      handleMessage(body)
                    }
                  } catch (e: EOFException) {
                    ClangLogs.info("Clangd stdout closed")
                  } catch (e: Exception) {
                    ClangLogs.error("Reader thread terminated", e)
                    failAllPending(e)
                  }
                },
                "clangd-stdio-reader",
            )
            .apply { isDaemon = true }
    readerThread?.start()
  }

  private fun readHeaders(stream: InputStream): Map<String, String>? {
    val headers = linkedMapOf<String, String>()
    var lineBuffer = ByteArrayOutputStream()
    var lastWasCr = false

    while (true) {
      val value = stream.read()
      if (value == -1) {
        return if (headers.isEmpty() && lineBuffer.size() == 0) null else throw EOFException("Unexpected EOF while reading headers")
      }

      val b = value.toByte()
      if (lastWasCr && b.toInt() == '\n'.code) {
        val line = lineBuffer.toString(StandardCharsets.US_ASCII.name())
        lineBuffer = ByteArrayOutputStream()
        lastWasCr = false

        if (line.isEmpty()) {
          return headers
        }

        val separatorIndex = line.indexOf(':')
        if (separatorIndex > 0) {
          val name = line.substring(0, separatorIndex).trim().lowercase()
          val headerValue = line.substring(separatorIndex + 1).trim()
          headers[name] = headerValue
        }
      } else {
        if (lastWasCr) {
          lineBuffer.write('\r'.code)
          lastWasCr = false
        }

        if (b.toInt() == '\r'.code) {
          lastWasCr = true
        } else {
          lineBuffer.write(value)
        }
      }
    }
  }

  private fun readBody(stream: InputStream, contentLength: Int): String {
    val buffer = ByteArray(contentLength)
    var offset = 0
    while (offset < contentLength) {
      val read = stream.read(buffer, offset, contentLength - offset)
      if (read == -1) {
        throw EOFException("Unexpected EOF while reading body")
      }
      offset += read
    }
    return String(buffer, StandardCharsets.UTF_8)
  }

  private fun handleMessage(json: String) {
    try {
      val obj = gson.fromJson(json, JsonObject::class.java)
      when {
        obj.has("id") -> handleResponse(obj)
        obj.has("method") -> handleNotification(obj)
        else -> ClangLogs.warn("Message has neither id nor method")
      }
    } catch (e: Exception) {
      ClangLogs.error("Failed to handle message", e)
      ClangLogs.debug("Message preview: {}", json.take(200))
    }
  }

  private fun handleResponse(obj: JsonObject) {
    val id = obj.get("id")?.asInt ?: return
    val future = pendingRequests.remove(id)
    requestTimestamps.remove(id)

    if (future == null) {
      ClangLogs.warn("No pending request for response ID {}", id)
      return
    }

    when {
      obj.has("error") -> {
        val error = obj.getAsJsonObject("error")
        val code = error.get("code")?.asInt
        val message = error.get("message")?.asString ?: "Unknown LSP error"
        future.completeExceptionally(IllegalStateException("LSP error $code: $message"))
      }
      obj.has("result") -> future.complete(obj.get("result"))
      else -> future.complete(null)
    }
  }

  private fun handleNotification(obj: JsonObject) {
    val method = obj.get("method")?.asString
    ClangLogs.debug("<<< Notification: {}", method)
    try {
      notificationHandler.handle(obj)
    } catch (e: Exception) {
      ClangLogs.error("Error handling notification: {}", method, e)
    }
  }

  private fun failAllPending(error: Throwable) {
    pendingRequests.forEach { (_, future) ->
      if (!future.isDone) {
        future.completeExceptionally(error)
      }
    }
    pendingRequests.clear()
    requestTimestamps.clear()
  }

  private fun startErrorReaderThread() {
    val currentProcess = process ?: return
    val errorReader = BufferedReader(InputStreamReader(currentProcess.errorStream, StandardCharsets.UTF_8))
    errorReaderThread =
        Thread(
                {
                  try {
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                      if (!line.isNullOrBlank()) {
                        ClangLogs.debug("clangd stderr: {}", line)
                      }
                    }
                  } catch (e: Exception) {
                    ClangLogs.error("Error reader thread terminated", e)
                  }
                },
                "clangd-error-reader",
            )
            .apply { isDaemon = true }
    errorReaderThread?.start()
  }

  fun shutdown() {
    ClangLogs.info("Shutting down clangd process...")

    try {
      if (process?.isAlive == true) {
        try {
          sendRequest("shutdown", JsonObject()).get()
        } catch (e: Exception) {
          ClangLogs.warn("Failed to send shutdown request", e)
        }

        try {
          sendNotification("exit", JsonObject())
        } catch (e: Exception) {
          ClangLogs.warn("Failed to send exit notification", e)
        }
      }
    } catch (e: Exception) {
      ClangLogs.error("Error during shutdown", e)
    }

    failAllPending(IllegalStateException("Clangd process shutting down"))

    try {
      outputStream?.close()
    } catch (_: Exception) {}
    try {
      inputStream?.close()
    } catch (_: Exception) {}

    readerThread?.interrupt()
    errorReaderThread?.interrupt()

    try {
      readerThread?.join(500)
    } catch (_: Exception) {}
    try {
      errorReaderThread?.join(500)
    } catch (_: Exception) {}

    try {
      process?.destroy()
    } catch (_: Exception) {}

    process = null
    outputStream = null
    inputStream = null
    readerThread = null
    errorReaderThread = null
    ClangLogs.info("Clangd shutdown complete")
  }
}

