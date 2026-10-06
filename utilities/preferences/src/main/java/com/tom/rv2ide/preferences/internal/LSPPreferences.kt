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

package com.tom.rv2ide.preferences.internal

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
@Suppress("MemberVisibilityCanBePrivate")
object LSPPreferences {
  const val ACS_CLANG_LSP_ENABLED = "acs_clang_lsp_enabled"
  const val ACS_KOTLIN_LSP_FORMAT_STYLE = "acs_kotlin_lsp_format_style"
  const val ACS_KOTLIN_LSP_BACKEND = "acs_kotlin_lsp_backend"
  const val ACS_KOTLIN_LSP_ENABLED = "acs_kotlin_lsp_enabled"

  const val KOTLIN_LSP_BACKEND_FWCD = "fwcd"
  const val KOTLIN_LSP_BACKEND_STUB = "stub"
  const val KOTLIN_LSP_BACKEND_ANALYSIS = "analysis"
  const val DEFAULT_KOTLIN_LSP_BACKEND = KOTLIN_LSP_BACKEND_FWCD

  var codeFormatStyle: String

    get() = prefManager.getString(ACS_KOTLIN_LSP_FORMAT_STYLE, "google")
    set(value) {
      prefManager.putString(ACS_KOTLIN_LSP_FORMAT_STYLE, value)
    }
  var clangLspEnabled: Boolean
    get() = prefManager.getBoolean(ACS_CLANG_LSP_ENABLED, true)
    set(value) {
      prefManager.putBoolean(ACS_CLANG_LSP_ENABLED, value)
    }

  var kotlinLspEnabled: Boolean
    get() = prefManager.getBoolean(ACS_KOTLIN_LSP_ENABLED, true)
    set(value) {
      prefManager.putBoolean(ACS_KOTLIN_LSP_ENABLED, value)
    }

  var kotlinLspBackend: String

    get() {
      val stored = prefManager.getString(ACS_KOTLIN_LSP_BACKEND, DEFAULT_KOTLIN_LSP_BACKEND)
      val normalized =
          when (stored.trim().lowercase()) {
            KOTLIN_LSP_BACKEND_FWCD -> KOTLIN_LSP_BACKEND_FWCD
            KOTLIN_LSP_BACKEND_STUB -> KOTLIN_LSP_BACKEND_STUB
            KOTLIN_LSP_BACKEND_ANALYSIS -> KOTLIN_LSP_BACKEND_ANALYSIS
            else -> DEFAULT_KOTLIN_LSP_BACKEND
          }

      if (normalized != stored) {
        prefManager.putString(ACS_KOTLIN_LSP_BACKEND, normalized)
      }

      return normalized
    }
    set(value) {
      prefManager.putString(ACS_KOTLIN_LSP_BACKEND, value)
    }
}

