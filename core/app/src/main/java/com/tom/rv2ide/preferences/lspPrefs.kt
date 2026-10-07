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

package com.tom.rv2ide.preferences

import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.KotlinLspBackendId
import com.tom.rv2ide.language.services.kotlin.settings.KotlinLspSettings
import com.tom.rv2ide.preferences.internal.LSPPreferences
import com.tom.rv2ide.preferences.internal.LSPPreferences.ACS_KOTLIN_LSP_BACKEND
import com.tom.rv2ide.preferences.internal.LSPPreferences.ACS_CLANG_LSP_ENABLED
import com.tom.rv2ide.preferences.internal.LSPPreferences.ACS_KOTLIN_LSP_ENABLED
import com.tom.rv2ide.preferences.internal.LSPPreferences.ACS_KOTLIN_LSP_FORMAT_STYLE

import com.tom.rv2ide.resources.R.drawable
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.language.setup.Setup
import com.tom.rv2ide.language.setup.servers.Clang
import com.tom.rv2ide.language.setup.servers.Kotlin
import com.tom.rv2ide.utils.AppRestartDialog
import com.tom.rv2ide.utils.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.widget.Toast
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

@Parcelize
class LSPPreferencesScreen(
    override val key: String = "idepref_editor_lsp",
    override val title: Int = string.language_servers,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(KotlinCategory())
    addPreference(ClangCategory())
  }
}

// New Kotlin Category
@Parcelize
private class KotlinCategory(
    override val key: String = "lsp_kotlin_category",
    override val title: Int = string.kotlin,
    override val summary: Int? = string.kotlin_lsp_category_summary,
    override val icon: Int? = drawable.ic_kotlin,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(KotlinLSP())
    addPreference(KotlinLspEnabled())
    addPreference(KotlinBackend())
    addPreference(KotlinFormatStyle())
    addPreference(KotlinIndexingNotification())
  }
}

@Parcelize
private class ClangCategory(
    override val key: String = "lsp_clang_category",
    override val title: Int = string.clang,
    override val summary: Int? = string.clang_lsp_category_summary,
    override val icon: Int? = drawable.ic_clangd,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(ClangLSP())
    addPreference(ClangLspEnabled())
  }
}

@Parcelize
private class ClangLspEnabled(
    override val key: String = ACS_CLANG_LSP_ENABLED,

    override val title: Int = string.clang_lsp_enabled_title,
    override val summary: Int? = string.clang_lsp_enabled_summary,
    override val icon: Int? = drawable.ic_flick,
) :
    SwitchPreference(
        setValue = LSPPreferences::clangLspEnabled::set,
        getValue = LSPPreferences::clangLspEnabled::get,
    )

@Parcelize
private class ClangLSP(
    override val key: String = "lsp_clang_server",
    override val title: Int = string.lsp_options_clang_title,
    override val summary: Int? = string.lsp_options_clang_summary,
    override val icon: Int? = drawable.ic_server,
) :
    LSPPreference(
        hint = string.server_status,
        getValue = { getStatus(isClangServerInstalled()) },
        serverId = { "clang" },
        isInstalled = ::isClangServerInstalled,
    ) {

  override fun onRequestDownload(context: android.content.Context, serverId: String) {
    Setup(context).installLanguageServer("clang")
  }
  override fun onRequestUninstall(context: android.content.Context, serverId: String) {
    showClangUninstallConfirmation(context)
  }

  private fun showClangUninstallConfirmation(context: android.content.Context) {
    MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(string.lsp_server_uninstall_title))
        .setMessage(context.getString(string.lsp_server_uninstall_message, "clang"))
        .setPositiveButton(string.lsp_server_uninstall) { _, _ ->
          Setup(context).uninstallLanguageServer(
            title = context.getString(string.lsp_server_uninstalling, "clang"),
            initialStep = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_prepare_clang_removal),
            successStep = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_completed),
            failureStep = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_failed),
            successTail = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_completed_success),
            failureTail = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_failed_detail),
            onComplete = { success ->
              if (success) {
                AppRestartDialog.show(context)
              }
            },
          ) { log ->
            Clang(context).uninstall(log)
          }
        }
        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
        .show()
  }
}

@Parcelize
private class KotlinLspEnabled(
    override val key: String = ACS_KOTLIN_LSP_ENABLED,
    override val title: Int = string.kotlin_lsp_enabled_title,
    override val summary: Int? = string.kotlin_lsp_enabled_summary,
    override val icon: Int? = drawable.ic_flick,
) :
    SwitchPreference(
        setValue = LSPPreferences::kotlinLspEnabled::set,
        getValue = LSPPreferences::kotlinLspEnabled::get,
    )
@Parcelize
private class KotlinLSP(
    override val key: String = "lsp_kotlin_server",
    override val title: Int = string.lsp_options_kotlin_title,
    override val summary: Int? = string.lsp_options_kotlin_summary,
    override val icon: Int? = drawable.ic_server,
) :
    LSPPreference(
        hint = string.server_status,
        getValue = { getStatus(isActiveKotlinBackendInstalled()) },
        serverId = { activeKotlinBackendManifestId() },
        isInstalled = ::isActiveKotlinBackendInstalled,
    ) {

  override fun onRequestDownload(context: android.content.Context, serverId: String) {
    if (serverId == "stub" || serverId == "analysis") {
      Toast.makeText(context, context.getString(string.status_installed), Toast.LENGTH_SHORT).show()
      return
    }
    Setup(context).installLanguageServer("kotlin")
  }

  override fun onRequestUninstall(context: android.content.Context, serverId: String) {
    if (serverId == "stub" || serverId == "analysis") {
      Toast.makeText(context, context.getString(string.status_installed), Toast.LENGTH_SHORT).show()
      return
    }
    MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(string.lsp_server_uninstall_title))
        .setMessage(context.getString(string.lsp_server_uninstall_message, serverId))
        .setPositiveButton(string.lsp_server_uninstall) { _, _ ->
          Setup(context).uninstallLanguageServer(
            title = context.getString(string.lsp_server_uninstalling, serverId),
            initialStep = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_prepare_kotlin_removal),
            successStep = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_completed),
            failureStep = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_failed),
            successTail = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_completed_success),
            failureTail = context.getString(com.tom.rv2ide.language.setup.R.string.lsp_task_uninstallation_failed_detail),
            onComplete = { success ->
              if (success) {
                AppRestartDialog.show(context)
              }
            },
          ) { log ->
            Kotlin(context).uninstall(log)
          }
        }
        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
        .show()
  }
}

@Parcelize
private class KotlinBackend(
    override val key: String = ACS_KOTLIN_LSP_BACKEND,
    override val title: Int = string.kotlin_lsp_backend_title,
    override val icon: Int? = drawable.ic_backend,
) : SingleChoicePreference() {

  @IgnoredOnParcel override val dialogCancellable = true

  @IgnoredOnParcel
  override val summary: Int?
    get() = string.kotlin_lsp_backend_summary

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    return arrayOf(
        PreferenceChoices.Entry(
            "Fwcd",
            LSPPreferences.kotlinLspBackend == LSPPreferences.KOTLIN_LSP_BACKEND_FWCD,
            LSPPreferences.KOTLIN_LSP_BACKEND_FWCD,
        ),
        PreferenceChoices.Entry(
            "Stub/NA",
            LSPPreferences.kotlinLspBackend == LSPPreferences.KOTLIN_LSP_BACKEND_STUB,
            LSPPreferences.KOTLIN_LSP_BACKEND_STUB,
        ),
        PreferenceChoices.Entry(
            "Analysis API",
            LSPPreferences.kotlinLspBackend == LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS,
            LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS,
        ),
    )
  }

  override fun onSelectionChanged(
      preference: Preference,
      entry: PreferenceChoices.Entry,
      position: Int,
      isSelected: Boolean,
  ) = Unit

  override fun onChoiceConfirmed(
      preference: Preference,
      entry: PreferenceChoices.Entry?,
      position: Int,
  ) {
    val selected = entry?.data as? String ?: return
    LSPPreferences.kotlinLspBackend = selected
  }
}

@Parcelize
private class KotlinFormatStyle(
    override val key: String = ACS_KOTLIN_LSP_FORMAT_STYLE,
    override val title: Int = string.acs_lsp_kotlin_code_style_title,
    override val icon: Int? = drawable.ic_format_code,
) : SingleChoicePreference() {

  @IgnoredOnParcel override val dialogCancellable = true

  @IgnoredOnParcel
  override val summary: Int?
    get() =
        if (isActiveKotlinBackendInstalled()) {
          string.acs_lsp_kotlin_code_style_summary
        } else {
          string.kotlin_server_required
        }

  companion object {
    private const val STYLE_KOTLINLANG = "kotlinlang"
    private const val STYLE_GOOGLE = "google"
    private const val STYLE_FACEBOOK = "facebook"
    private val STYLES = listOf(STYLE_GOOGLE, STYLE_KOTLINLANG, STYLE_FACEBOOK)
    private const val DEFAULT_STYLE = STYLE_GOOGLE
  }

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    val currentStyle = LSPPreferences.codeFormatStyle
    return STYLES.map { style ->
          PreferenceChoices.Entry(
              label =
                  when (style) {
                    STYLE_GOOGLE -> "Google"
                    STYLE_KOTLINLANG -> "JetBrains"
                    STYLE_FACEBOOK -> "Facebook"
                    else -> style
                  },
              _isChecked = currentStyle == style,
              data = style,
          )
        }
        .toTypedArray()
  }

  override fun onChoiceConfirmed(
      preference: Preference,
      entry: PreferenceChoices.Entry?,
      position: Int,
  ) {
    val selectedStyle = STYLES.getOrNull(position) ?: DEFAULT_STYLE
    LSPPreferences.codeFormatStyle = selectedStyle
    KotlinLspSettings.setCodeFormatStyle(selectedStyle)
  }

  override fun onCreatePreference(
      context: android.content.Context
  ): androidx.preference.Preference {
    val pref = super.onCreatePreference(context)
    pref.isEnabled = isActiveKotlinBackendInstalled()
    return pref
  }
}

/* DEPRECATED! clang lsp installation handled via terminal
@Parcelize
private class CCPPCategory(
    override val key: String = "lsp_ccpp_category",
    override val title: Int = string.c_cpp,
    override val summary: Int? = string.ccpp_lsp_category_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(CCPPLSP())
  }
}

@Parcelize
private class CCPPLSP(
    override val key: String = "lsp_c_cpp_server",
    override val title: Int = string.lsp_options_c_cpp_title,
    override val summary: Int? = string.lsp_options_c_cpp_summary,
) :
    LSPPreference(
        hint = string.server_status,
        getValue = { getStatus(isCCppServerInstalled()) },
        serverId = { "C_CPP" },
        isInstalled = ::isCCppServerInstalled,
    )
*/

private fun getStatus(installed: Boolean): String {
  val context = BaseApplication.getBaseInstance()
  return if (installed) {
    context.getString(string.status_installed)
  } else {
    context.getString(string.status_not_installed)
  }
}
private fun activeKotlinBackendId(): KotlinLspBackendId {
  return when (LSPPreferences.kotlinLspBackend.trim().lowercase()) {
    LSPPreferences.KOTLIN_LSP_BACKEND_STUB -> KotlinLspBackendId.STUB
    LSPPreferences.KOTLIN_LSP_BACKEND_ANALYSIS -> KotlinLspBackendId.ANALYSIS
    else -> KotlinLspBackendId.FWCD
  }
}

private fun activeKotlinBackendManifestId(): String =
    when (activeKotlinBackendId()) {
      KotlinLspBackendId.FWCD -> "fwcd"
      KotlinLspBackendId.STUB -> "stub"
      KotlinLspBackendId.ANALYSIS -> "analysis"
    }

private fun isActiveKotlinBackendInstalled(): Boolean =

    when (activeKotlinBackendId()) {
      KotlinLspBackendId.FWCD -> isDirectoryInstalled(File(Environment.SERVERS_KOTLIN_DIR, "fwcd"))
      KotlinLspBackendId.STUB -> true
      KotlinLspBackendId.ANALYSIS -> true
    }

private fun isDirectoryInstalled(dir: File): Boolean {
  return dir.exists() && dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
}
private fun isCCppServerInstalled(): Boolean {
  val serverDir = File(Environment.HOME, "acs/servers/c_cpp")
  return serverDir.exists() && serverDir.isDirectory && serverDir.listFiles()?.isNotEmpty() == true
}

private fun isClangServerInstalled(): Boolean {
  return Clang(BaseApplication.getBaseInstance()).isInstalled()
}

private object ClangLspState {
  var enabled: Boolean = false
}

