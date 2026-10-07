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

package com.tom.rv2ide.setup.servers

import android.content.Context
import com.tom.rv2ide.preferences.internal.LSPPreferences
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Kotlin(private val context: Context) : ILanguageServerInstaller {
  
  companion object {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/HUIYWU/huiywu.language-servers/refs/heads/main/servers-manifest.json"
  }

  private val json = Json { ignoreUnknownKeys = true }
  
  override fun isInstalled(): Boolean {
    val backendId = activeBackendManifestId()
    val manifest = runCatching { json.decodeFromString<Manifest>(URL(MANIFEST_URL).readText()) }.getOrNull()
    val server = manifest?.let { selectServerItem(it, backendId) }
    val serverHome = installRootFor(backendId, server)

    val candidates =
        buildList {
          add(File(serverHome, "bin/kotlin-language-server"))
          add(File(serverHome, "bin/kotlin-language-server.sh"))
          add(File(serverHome, "kotlin-language-server"))
          add(File(serverHome, "kotlin-language-server.sh"))
          add(File(serverHome, "server/bin/kotlin-language-server"))
          add(File(serverHome, "server/bin/kotlin-language-server.sh"))
        }

    val hasLauncher = candidates.any { it.exists() && it.isFile }
    val hasJar =
        File(serverHome, "lib").listFiles()?.any { it.isFile && it.extension == "jar" } == true ||
            File(serverHome, "server/lib").listFiles()?.any { it.isFile && it.extension == "jar" } == true

    return when (backendId) {
      "stub" -> true
      "analysis" -> true
      else -> hasLauncher
    }
  }
  
  override fun install(onOutput: (String) -> Unit): Boolean {
    return try {
      onOutput("Fetching Kotlin language server information...")
      
      val manifest = json.decodeFromString<Manifest>(URL(MANIFEST_URL).readText())
      val backendId = activeBackendManifestId()

      if (backendId == "analysis") {
        onOutput("Analysis API backend does not use the download-based installer.")
        return true
      }

      val server = selectServerItem(manifest, backendId)
      val downloadLink = server?.artifact?.url ?: server?.link
      val version = server?.version
      
      if (downloadLink == null) {
        onOutput("\nError: No download link available for Kotlin language server")
        onOutput("Backend searched: $backendId")
        return false
      }
      
      onOutput("Found Kotlin language server version: $version")
      onOutput("Download URL: $downloadLink")
      
      val serverDir = installRootFor(backendId, server)
      serverDir.mkdirs()
      
      onOutput("\nConnecting to download server...")
      
      val connection = URL(downloadLink).openConnection()
      connection.connect()
      
      val fileLength = connection.contentLength
      val inputStream = connection.getInputStream()
      
      val tempFile = File(serverDir.parentFile ?: serverDir, "temp_${backendId}.zip")
      val outputStream = FileOutputStream(tempFile)
      
      onOutput("Downloading Kotlin language server...")
      if (fileLength > 0) {
        onOutput("File size: ${fileLength / 1024 / 1024} MB")
      }
      
      val buffer = ByteArray(8192)
      var totalRead = 0L
      var len: Int
      var lastProgress = 0
      
      while (inputStream.read(buffer).also { len = it } > 0) {
        outputStream.write(buffer, 0, len)
        totalRead += len
        
        if (fileLength > 0) {
          val progress = ((totalRead * 100) / fileLength).toInt()
          if (progress != lastProgress && progress % 10 == 0) {
            onOutput("Download progress: $progress%")
            lastProgress = progress
          }
        }
      }
      
      outputStream.close()
      inputStream.close()
      
      onOutput("\nDownload completed!")
      onOutput("Extracting files...")
      
      val zipInputStream = ZipInputStream(tempFile.inputStream())
      var zipEntry = zipInputStream.nextEntry
      var extractedCount = 0
      
      while (zipEntry != null) {
        val file = File(serverDir, zipEntry.name)
        
        if (zipEntry.isDirectory) {
          file.mkdirs()
        } else {
          file.parentFile?.mkdirs()
          val fileOutputStream = FileOutputStream(file)
          val extractBuffer = ByteArray(8192)
          var extractLen: Int
          while (zipInputStream.read(extractBuffer).also { extractLen = it } > 0) {
            fileOutputStream.write(extractBuffer, 0, extractLen)
          }
          fileOutputStream.close()
          
          if (file.extension.isEmpty() || file.name.endsWith(".sh")) {
            file.setExecutable(true)
          }
          
          extractedCount++
          
          if (extractedCount % 50 == 0) {
            onOutput("Extracted $extractedCount files...")
          }
        }
        
        zipInputStream.closeEntry()
        zipEntry = zipInputStream.nextEntry
      }
      
      zipInputStream.close()
      tempFile.delete()
      
      onOutput("\nExtraction completed!")
      onOutput("Total files extracted: $extractedCount")
      
      onOutput("\nVerifying installation...")
      if (isInstalled()) {
        onOutput("Kotlin language server binaries found!")
        true
      } else {
        onOutput("Installation verification failed. Server binaries not found.")
        false
      }
      
    } catch (e: Exception) {
      onOutput("\nError during installation: ${e.message}")
      e.printStackTrace()
      false
    }
  }

  fun uninstall(onOutput: (String) -> Unit): Boolean {
    return try {
      val backendId = activeBackendManifestId()
      onOutput("Resolving Kotlin language server installation...")

      if (backendId == "analysis") {
        onOutput("Analysis API backend does not require uninstall.")
        return true
      }

      if (backendId == "stub") {
        onOutput("Stub backend does not require uninstall.")
        return true
      }

      val manifest = runCatching { json.decodeFromString<Manifest>(URL(MANIFEST_URL).readText()) }.getOrNull()
      val server = manifest?.let { selectServerItem(it, backendId) }
      val serverDir = installRootFor(backendId, server)

      onOutput("Target directory: ${serverDir.absolutePath}")

      if (!serverDir.exists()) {
        onOutput("Kotlin language server files not found.")
        return true
      }

      onOutput("Removing Kotlin language server files...")
      val deleted = serverDir.deleteRecursively()
      if (!deleted) {
        onOutput("Failed to remove Kotlin language server files.")
        return false
      }

      onOutput("Verifying removal...")
      val removed = !serverDir.exists()
      if (removed) {
        onOutput("Kotlin language server removed.")
      } else {
        onOutput("Removal verification failed.")
      }
      removed
    } catch (e: Exception) {
      onOutput("\nError during uninstall: ${e.message}")
      e.printStackTrace()
      false
    }
  }

  private fun activeBackendManifestId(): String =
      when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
        LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> "stub"
        LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> "analysis"
        else -> "fwcd"
      }

  private fun selectServerItem(manifest: Manifest, serverId: String): ServerItem? {
    return manifest.servers.firstOrNull {
      it.id.equals(serverId, ignoreCase = true) &&
          it.language.equals("kotlin", ignoreCase = true)
    }
        ?: manifest.servers.firstOrNull {
          it.backend?.equals(serverId, ignoreCase = true) == true &&
              it.language.equals("kotlin", ignoreCase = true)
        }
        ?: manifest.servers.firstOrNull { it.language.equals("kotlin", ignoreCase = true) }
        ?: manifest.legacyServers.firstOrNull {
          it.id.equals(serverId, ignoreCase = true) ||
              it.backend?.equals(serverId, ignoreCase = true) == true
        }
        ?: manifest.legacyServers.firstOrNull()
  }

  private fun installRootFor(serverId: String, server: ServerItem?): File {
    val install = server?.install
    val targetRelativeTo = install?.targetRelativeTo?.trim()
    val targetSubdir = install?.targetSubdir?.trim().orEmpty()

    val baseDir =
        when (targetRelativeTo) {
          "SERVERS_KOTLIN_DIR" -> Environment.SERVERS_KOTLIN_DIR
          "SERVERS_DIR" -> Environment.SERVERS_DIR
          "HOME" -> Environment.HOME
          else -> null
        }

    if (baseDir != null) {
      return if (targetSubdir.isNotEmpty()) File(baseDir, targetSubdir) else baseDir
    }

    return when (serverId.trim().lowercase()) {
      "fwcd" -> File(Environment.SERVERS_KOTLIN_DIR, "fwcd")
      "stub" -> File(Environment.SERVERS_KOTLIN_DIR, "stub")
      else -> File(Environment.HOME, "acs/servers/${serverId.lowercase()}")
    }
  }

  @Serializable
  data class Manifest(
      val servers: List<ServerItem> = emptyList(),
      @SerialName("Servers") val legacyServers: List<ServerItem> = emptyList(),
  )

  @Serializable
  data class ServerItem(
      val id: String,
      val language: String = "kotlin",
      val name: String? = null,
      val backend: String? = null,
      val version: String,
      val artifact: Artifact? = null,
      val install: Install? = null,
      val link: String? = null,
  )

  @Serializable
  data class Artifact(
      val type: String? = null,
      val entry: String? = null,
      val url: String? = null,
  )

  @Serializable
  data class Install(
      val layout: String? = null,
      val targetRelativeTo: String? = null,
      val targetSubdir: String? = null,
  )
}
