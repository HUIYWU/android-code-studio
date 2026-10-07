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

package com.tom.rv2ide.language.setup.updater.lsp

import android.content.Context
import com.tom.rv2ide.preferences.internal.LSPPreferences
import android.widget.Toast
import com.tom.rv2ide.language.setup.R
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.language.setup.updater.LspUpdateDialog
import com.tom.rv2ide.language.setup.updater.lsp.data.LSPProperties
import com.tom.rv2ide.utils.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the update workflow for the Kotlin Language Server Protocol (LSP) component.
 *
 * This class is responsible for:
 * - Checking for available updates by fetching a remote manifest
 * - Comparing remote versions against the current installed version
 * - Downloading update packages when updates are available
 * - Cleaning up existing installations before extracting new versions
 * - Extracting the downloaded ZIP archive to the designated server directory
 * - Cleaning up temporary download files after successful extraction
 * - Updating the version information in the properties file upon successful installation
 * - Displaying user dialogs throughout the update process
 *
 * The updater fetches version metadata from a GitHub-hosted manifest file and
 * coordinates the download and installation of new LSP server versions.
 *
 * @property context The Android context used for displaying UI elements and accessing file storage.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class KotlinLspUpdater(private val context: Context) {

    private val manifestUrl = "https://raw.githubusercontent.com/HUIYWU/huiywu.language-servers/refs/heads/main/servers-manifest.json"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Checks for available updates by fetching the remote manifest and comparing versions.
     *
     * This function performs a network request to retrieve the latest version information
     * from the remote manifest. If an update is available, it displays an update dialog
     * to the user.
     *
     * @param currentVersion The currently installed version of the Kotlin LSP server.
     * @param onResult Optional callback invoked with the update availability status and
     * the remote version string. The first parameter indicates whether an update is available,
     * and the second parameter contains the remote version string if available.
     */
    fun checkForUpdates(currentVersion: String, onResult: ((Boolean, String?) -> Unit)? = null) {
        val activeBackend = LSPPreferences.kotlinLspBackend.trim().lowercase()
        if (activeBackend == LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS) {
            onResult?.invoke(false, null)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonString = URL(manifestUrl).readText()
                val manifest = json.decodeFromString<Manifest>(jsonString)
                val serverItem = selectServerItem(manifest)

                if (serverItem != null) {
                    val updateAvailable = isUpdateAvailable(serverItem.version, currentVersion)
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(updateAvailable, serverItem.version)
                        if (updateAvailable) {
                            showUpdateDialog(serverItem.version, serverItem.artifact?.url ?: serverItem.link, serverItem)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, null)
                    Toast.makeText(context, context.getString(R.string.lsp_update_check_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Determines whether an update is available by comparing version strings.
     *
     * @param remoteVersion The version string from the remote manifest.
     * @param currentVersion The currently installed version string.
     * @return `true` if the remote version greater than the current version, `false` otherwise.
     */
    private fun isUpdateAvailable(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = remoteVersion.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(remoteParts.size, currentParts.size)

        for (i in 0 until maxLength) {
            val remotePart = remoteParts.getOrNull(i) ?: 0
            val currentPart = currentParts.getOrNull(i) ?: 0

            if (remotePart > currentPart) {
                return true
            } else if (remotePart < currentPart) {
                return false
            }
        }

        return false
    }

    private fun selectServerItem(manifest: Manifest): ServerItem? {
        val backendId = activeBackendManifestId()
        return manifest.servers.firstOrNull {
            it.id.equals(backendId, ignoreCase = true) &&
                it.language.equals("kotlin", ignoreCase = true)
        }
            ?: manifest.servers.firstOrNull {
                it.backend?.equals(backendId, ignoreCase = true) == true &&
                    it.language.equals("kotlin", ignoreCase = true)
            }
            ?: manifest.servers.firstOrNull { it.language.equals("kotlin", ignoreCase = true) }
            ?: manifest.legacyServers.firstOrNull {
                it.id.equals(backendId, ignoreCase = true) ||
                    it.backend?.equals(backendId, ignoreCase = true) == true
            }
            ?: manifest.legacyServers.firstOrNull()
    }

    private fun activeBackendManifestId(): String {
        return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
            LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> "stub"
            LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> "analysis"
            else -> "fwcd"
        }
    }

    /**
     * Displays the initial update dialog prompting the user to download the new version.
     *
     * This dialog presents the user with options to proceed with the update or cancel.
     * If the user chooses to update, the download process begins and progress is shown.
     *
     * @param fetchedVersion The version string of the available update.
     * @param downloadUrl The URL from which to download the update package, or `null` if unavailable.
     */
    private fun showUpdateDialog(fetchedVersion: String, downloadUrl: String?, serverItem: ServerItem? = null) {
        LspUpdateDialog(context)
            .setTitle(context.getString(R.string.lsp_update_available_title))
            .setDescription(context.getString(R.string.lsp_update_available_description, kotlinBackendDisplayName(), fetchedVersion))
            .setPrimaryButton(context.getString(R.string.lsp_update_button)) { dialog ->
                if (downloadUrl == null) {
                    Toast.makeText(context, context.getString(R.string.lsp_download_url_unavailable), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPrimaryButton
                }

                dialog.operate { updater ->
                    updater.updateTitle(context.getString(R.string.lsp_downloading_title))
                    updater.updateDescription(context.getString(R.string.lsp_initializing_download))
                    updater.updateProgress(0)

                    try {
                        val url = URL(downloadUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connect()

                        val fileLength = connection.contentLength
                        val fileName = downloadUrl.substringAfterLast("/")
                        val downloadDir = context.getExternalFilesDir(null)
                        val file = File(downloadDir, fileName)

                        connection.inputStream.use { input ->
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalBytesRead: Long = 0

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalBytesRead += bytesRead

                                    if (fileLength > 0) {
                                        val progress = ((totalBytesRead * 100) / fileLength).toInt()
                                        updater.updateProgress(progress)
                                        updater.updateDescription(context.getString(R.string.lsp_downloading_progress, progress))
                                    }
                                }
                            }
                        }

                        updater.updateProgress(100)
                        updater.updateTitle(context.getString(R.string.lsp_download_complete_title))
                        updater.updateDescription(context.getString(R.string.lsp_cleaning_old_installation))

                        cleanupOldInstallation(updater, serverItem)

                        updater.updateDescription(context.getString(R.string.lsp_extracting_files))

                        extractZipFile(file.absolutePath, updater, serverItem)

                        file.delete()

                        updateVersionInProperties(fetchedVersion)

                        updater.updateTitle(context.getString(R.string.lsp_installation_complete_title))
                        updater.updateDescription(context.getString(R.string.lsp_installation_complete_description, kotlinBackendDisplayName(), fetchedVersion))

                        kotlinx.coroutines.delay(1500)

                        withContext(Dispatchers.Main) {
                            showSuccessDialog(fetchedVersion)
                        }

                    } catch (e: Exception) {
                        updater.updateTitle(context.getString(R.string.lsp_installation_failed_title))
                        updater.updateDescription(context.getString(R.string.lsp_error_message, e.message))

                        kotlinx.coroutines.delay(2000)

                        withContext(Dispatchers.Main) {
                            showErrorDialog(e.message ?: context.getString(R.string.lsp_unknown_error), fetchedVersion, downloadUrl, serverItem)
                        }
                    }
                }
            }
            .setSecondaryButton(context.getString(R.string.lsp_cancel_button)) { dialog ->
                dialog.dismiss()
            }
            .reveal()
    }

    /**
     * Cleans up the existing Kotlin LSP installation directory before installing a new version.
     *
     * This ensures that no residual files from previous installations remain, which could
     * cause conflicts or unexpected behavior with the new version.
     *
     * @param updater The dialog updater for displaying cleanup progress.
     */
    private fun cleanupOldInstallation(updater: LspUpdateDialog.ProgressUpdater, serverItem: ServerItem? = null) {
        val targetDir = kotlinInstallRoot(serverItem)

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
    }

    private fun kotlinInstallRoot(serverItem: ServerItem? = null): File {
        val install = serverItem?.install
        val targetRelativeTo = install?.targetRelativeTo?.trim()
        val targetSubdir = install?.targetSubdir?.trim().orEmpty()

        val baseDir = when (targetRelativeTo) {
            "SERVERS_KOTLIN_DIR" -> Environment.SERVERS_KOTLIN_DIR
            "SERVERS_DIR" -> Environment.SERVERS_DIR
            "HOME" -> Environment.HOME
            else -> null
        }

        if (baseDir != null) {
            return if (targetSubdir.isNotEmpty()) File(baseDir, targetSubdir) else baseDir
        }

        return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
            LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> File(Environment.SERVERS_KOTLIN_DIR, "stub")
            LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> File(Environment.SERVERS_KOTLIN_DIR, "analysis")
            else -> File(Environment.SERVERS_KOTLIN_DIR, "fwcd")
        }
    }

    /**
     * Extracts the contents of a ZIP file to the Kotlin LSP server directory.
     *
     * The extraction target follows the active Kotlin backend install root.

     * is created if it does not exist.
     *
     * @param zipFilePath The path to the ZIP file to extract.
     * @param updater The dialog updater for displaying extraction progress.
     * @throws Exception if extraction fails.
     */
    private suspend fun extractZipFile(zipFilePath: String, updater: LspUpdateDialog.ProgressUpdater, serverItem: ServerItem? = null) {
        val targetDir = kotlinInstallRoot(serverItem)
        targetDir.mkdirs()

        ZipInputStream(File(zipFilePath).inputStream()).use { zipInputStream ->
            var entry = zipInputStream.nextEntry
            var fileCount = 0

            while (entry != null) {
                val entryFile = File(targetDir, entry.name)

                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    entryFile.parentFile?.mkdirs()
                    FileOutputStream(entryFile).use { output ->
                        zipInputStream.copyTo(output)
                    }
                    fileCount++
                    updater.updateDescription(context.getString(R.string.lsp_extracting_progress, fileCount))
                }

                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }
    }

    /**
     * Updates the Kotlin LSP version in the properties file after a successful installation.
     *
     * This function writes or overwrites the `KotlinLspVersion` key in the properties file
     * located at `Environment.ACSIDE`. If the key already exists, its value is updated;
     * otherwise, the key is created with the new version value.
     *
     * @param version The new version string to write to the properties file.
     */
    private fun updateVersionInProperties(version: String) {
        try {
            LSPProperties.writePropertyValue(
                filePath = Environment.ACSIDE.toString(),
                key = kotlinVersionPropertyKey(),
                value = version
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun kotlinVersionPropertyKey(): String {
        return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
            LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> "KotlinLspVersion.stub"
            LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> "KotlinLspVersion.analysis"
            else -> "KotlinLspVersion.fwcd"
        }
    }

    private fun kotlinBackendDisplayName(): String {
        return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
            LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> "Kotlin language server (stub)"
            LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> "Kotlin language server (Analysis API)"
            else -> "Kotlin language server (fwcd)"
        }
    }

    /**
     * Displays a success dialog after the update has been downloaded, extracted, and installed successfully.
     *
     * @param version The version string of the successfully installed update.
     */
    private fun showSuccessDialog(version: String) {
        LspUpdateDialog(context)
            .setTitle(context.getString(R.string.lsp_update_success_title))
            .setDescription(context.getString(R.string.lsp_update_success_description, kotlinBackendDisplayName(), version))
            .setPrimaryButton(context.getString(R.string.lsp_ok_button)) { dialog ->
                dialog.dismiss()
            }
            .reveal()
    }

    /**
     * Displays an error dialog when the download or installation fails.
     *
     * This dialog provides the user with an option to retry the download or cancel.
     *
     * @param error The error message describing why the installation failed.
     * @param version The version string of the update that failed to install.
     * @param downloadUrl The URL that was used for the failed download attempt.
     */
    private fun showErrorDialog(error: String, version: String, downloadUrl: String, serverItem: ServerItem? = null) {
        LspUpdateDialog(context)
            .setTitle(context.getString(R.string.lsp_installation_failed_title))
            .setDescription(context.getString(R.string.lsp_installation_failed_description, error))
            .setPrimaryButton(context.getString(R.string.lsp_retry_button)) { dialog ->
                dialog.dismiss()
                showUpdateDialog(version, downloadUrl, serverItem)
            }
            .setSecondaryButton(context.getString(R.string.lsp_cancel_button)) { dialog ->
                dialog.dismiss()
            }
            .reveal()
    }

    /**
     * Data class representing the structure of the remote manifest JSON.
     *
     * Supports both the new `servers` schema and the legacy `Servers` schema
     * during the migration period.
     */
    @Serializable
    data class Manifest(
        val servers: List<ServerItem> = emptyList(),
        @SerialName("Servers") val legacyServers: List<ServerItem> = emptyList()
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
        val runtime: Runtime? = null,
        val link: String? = null
    )

    @Serializable
    data class Artifact(
        val type: String? = null,
        val entry: String? = null,
        val url: String? = null
    )

    @Serializable
    data class Install(
        val layout: String? = null,
        val targetRelativeTo: String? = null,
        val targetSubdir: String? = null
    )

    @Serializable
    data class Runtime(
        val launcherCandidates: List<String> = emptyList(),
        val configHome: String? = null,
        val cacheHome: String? = null
    )
}