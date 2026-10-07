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

package com.tom.rv2ide.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.button.MaterialButton
import com.tom.rv2ide.setup.R
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.setup.servers.ILanguageServerInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Setup(private val context: Context) {
  
  private val cppExtensions = setOf("c", "cpp", "cc", "cxx", "h", "hpp", "hh", "hxx")
  private val kotlinExtensions = setOf("kt", "kts")
  private val scope = CoroutineScope(Dispatchers.Main)
  
  fun scanProjectForLanguageServers(projectDir: File, onComplete: ((Boolean) -> Unit)? = null) {
    scope.launch {
      withContext(Dispatchers.IO) {
        val serversToInstall = mutableListOf<Pair<String, String>>()
        
        val hasCppFiles = checkForFiles(projectDir, cppExtensions)
        if (hasCppFiles && !isServerInstalled("clang")) {
          serversToInstall.add("clang" to context.getString(R.string.project_contians_clang_title))
        }
        
        val hasKotlinFiles = checkForFiles(projectDir, kotlinExtensions)
        if (hasKotlinFiles && !isServerInstalled("kotlin")) {
          serversToInstall.add("kotlin" to context.getString(R.string.project_contians_kotlin_title))
        }
        
        serversToInstall
      }.let { servers ->
        if (servers.isNotEmpty()) {
          showLanguageServerDialogs(servers, 0, false, onComplete)
        } else {
          onComplete?.invoke(false)
        }
      }
    }
  }
  
  private fun checkForFiles(directory: File, extensions: Set<String>): Boolean {
    if (!directory.exists() || !directory.isDirectory) {
      return false
    }
    
    return directory.walkTopDown().any { file ->
      file.isFile && extensions.contains(file.extension.lowercase())
    }
  }
  
  private fun isServerInstalled(serverId: String): Boolean {
    return try {
      val installer = getLanguageServerInstaller(serverId)
      installer.isInstalled()
    } catch (e: Exception) {
      false
    }
  }
  
  private fun showLanguageServerDialogs(
    servers: List<Pair<String, String>>,
    index: Int,
    anyInstalled: Boolean,
    onComplete: ((Boolean) -> Unit)?
  ) {
    if (index >= servers.size) {
      onComplete?.invoke(anyInstalled)
      return
    }
    
    val (serverId, message) = servers[index]
    showLanguageServerDialog(serverId, message) { isSuccessful ->
      showLanguageServerDialogs(servers, index + 1, anyInstalled || isSuccessful, onComplete)
    }
  }
  
  private fun showLanguageServerDialog(serverId: String, message: String, onComplete: ((Boolean) -> Unit)?) {
    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_language_server_setup, null)
    
    dialogView.findViewById<MaterialTextView>(R.id.tv_message).text = message
    
    val dialog = MaterialAlertDialogBuilder(context)
      .setView(dialogView)
      .setCancelable(true)
      .setOnDismissListener {
        onComplete?.invoke(false)
      }
      .create()
    
    dialogView.findViewById<MaterialButton>(R.id.btn_install).setOnClickListener {
      dialog.setOnDismissListener(null)
      dialog.dismiss()
      installLanguageServer(serverId, onComplete)
    }
    
    dialogView.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
      dialog.setOnDismissListener(null)
      dialog.dismiss()
      onComplete?.invoke(false)
    }
    
    dialog.show()
  }
  
  fun installLanguageServer(serverId: String, onComplete: ((Boolean) -> Unit)? = null) {
    runLanguageServerTask(
      title = context.getString(R.string.installing_lsp),
      initialStep = context.getString(R.string.lsp_task_prepare_installation),
      successStep = context.getString(R.string.lsp_task_installation_completed),
      failureStep = context.getString(R.string.lsp_task_installation_failed),
      successTail = context.getString(R.string.lsp_task_installation_completed_success),
      failureTail = context.getString(R.string.lsp_task_installation_failed_detail),
      onComplete = onComplete,
    ) { log ->
      val installer = getLanguageServerInstaller(serverId)
      installer.install(log)
    }
  }

  fun uninstallLanguageServer(
    title: String,
    initialStep: String,
    successStep: String,
    failureStep: String,
    successTail: String,
    failureTail: String,
    onComplete: ((Boolean) -> Unit)? = null,
    action: (log: (String) -> Unit) -> Boolean,
  ) {
    runLanguageServerTask(
      title = title,
      initialStep = initialStep,
      successStep = successStep,
      failureStep = failureStep,
      successTail = successTail,
      failureTail = failureTail,
      onComplete = onComplete,
      action = action,
    )
  }

  private fun runLanguageServerTask(
    title: String,
    initialStep: String,
    successStep: String,
    failureStep: String,
    successTail: String,
    failureTail: String,
    onComplete: ((Boolean) -> Unit)? = null,
    action: (log: (String) -> Unit) -> Boolean,
  ) {
    val progressView = LayoutInflater.from(context).inflate(R.layout.dialog_installation_progress, null)

    val progressBar = progressView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_bar)
    val titleText = progressView.findViewById<MaterialTextView>(R.id.tv_title)
    val stepText = progressView.findViewById<MaterialTextView>(R.id.tv_step)
    val progressText = progressView.findViewById<MaterialTextView>(R.id.tv_progress)
    val outputText = progressView.findViewById<MaterialTextView>(R.id.tv_output)
    val logScroll = progressView.findViewById<ScrollView>(R.id.log_scroll)
    val copyButton = progressView.findViewById<MaterialButton>(R.id.btn_copy)
    val closeButton = progressView.findViewById<MaterialButton>(R.id.btn_close)

    val progressDialog = MaterialAlertDialogBuilder(context)
      .setView(progressView)
      .setCancelable(false)
      .create()

    val outputBuilder = StringBuilder()
    val taskResult = arrayOf(false)
    var currentStepText = initialStep

    copyButton.visibility = View.GONE
    closeButton.visibility = View.GONE

    titleText.text = title
    stepText.text = currentStepText
    progressText.text = context.getString(R.string.lsp_task_working)
    progressBar.isIndeterminate = true

    copyButton.setOnClickListener {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clip = ClipData.newPlainText(context.getString(R.string.lsp_task_output_label), outputBuilder.toString())
      clipboard.setPrimaryClip(clip)
      Toast.makeText(context, context.getString(R.string.lsp_task_output_copied), Toast.LENGTH_SHORT).show()
    }

    closeButton.setOnClickListener {
      progressDialog.dismiss()
      onComplete?.invoke(taskResult[0])
    }

    progressDialog.show()

    scope.launch {
      try {
        taskResult[0] = withContext(Dispatchers.IO) {
          action { output ->
            scope.launch(Dispatchers.Main) {
              outputBuilder.append(output).append("\n")
              outputText.text = outputBuilder.toString()
              val latestStep = output.lineSequence().lastOrNull()
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.takeIf { it.isNotEmpty() }
                ?: initialStep
              if (latestStep != currentStepText) {
                currentStepText = latestStep
                stepText.text = currentStepText
              }
              logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
          }
        }

        progressBar.isIndeterminate = false
        progressBar.progress = 100
        progressText.text = "100%"

        if (taskResult[0]) {
          currentStepText = successStep
          stepText.text = currentStepText
          outputBuilder.append("\n").append(successTail)
        } else {
          currentStepText = failureStep
          stepText.text = currentStepText
          outputBuilder.append("\n").append(failureTail)
          copyButton.visibility = View.VISIBLE
        }

        outputText.text = outputBuilder.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        closeButton.visibility = View.VISIBLE
      } catch (e: Exception) {
        taskResult[0] = false
        progressBar.isIndeterminate = false
        progressBar.progress = 100
        progressText.text = context.getString(R.string.lsp_task_failed)
        currentStepText = failureStep
        stepText.text = currentStepText
        outputBuilder.append("\nError: ${e.message}")
        outputText.text = outputBuilder.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        copyButton.visibility = View.VISIBLE
        closeButton.visibility = View.VISIBLE
        e.printStackTrace()
      }
    }
  }
  
  private fun getLanguageServerInstaller(serverId: String): ILanguageServerInstaller {
    val className = "com.tom.rv2ide.setup.servers.${serverId.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
    val clazz = Class.forName(className)
    val constructor = clazz.getDeclaredConstructor(Context::class.java)
    return constructor.newInstance(context) as ILanguageServerInstaller
  }
}