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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.language.services.kotlin.settings

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.managers.PreferenceManager

/**
 * Utility class for managing Kotlin Lsp features.
 *
 * This class provides methods to control and query the state of various LSP features such as hover
 * information and diagnostics through the application's preferences.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
object KotlinLspSettings {
  private const val KOTLIN_CODE_FORMAT_STYLE_KEY = "acs_kotlin_code_format_style"
  private val preferenceManager: PreferenceManager
    get() = BaseApplication.getBaseInstance().prefManager

  /** @return String of of the style */
  fun getCodeFormatStyle(): String? {
    return preferenceManager.getString(KOTLIN_CODE_FORMAT_STYLE_KEY, "google" /* default */)
  }
  /** @param style String to set code format style */
  fun setCodeFormatStyle(style: String) {
    preferenceManager.putString(KOTLIN_CODE_FORMAT_STYLE_KEY, style)
  }
}
