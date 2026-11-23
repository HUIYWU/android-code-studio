/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.language.services.java.actions

import android.content.Context
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.newDialogBuilder
import com.tom.rv2ide.actions.requirePath
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsParams
import com.tom.rv2ide.language.services.java.interop.JavaPsiFieldsResult
import com.tom.rv2ide.language.services.java.interop.JavaPsiServiceInterop
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.models.DocumentSnapshotIdentity
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.flashInfo
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Any action that has to work with fields in the current class can inherit this action.
 *
 * @author Akash Yadav
 */
abstract class FieldBasedAction : BaseJavaCodeAction() {
  companion object {
    private object StaleFieldsResult
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)

    if (
        !visible ||
            !data.hasRequiredData(
                Range::class.java,
                CodeEditor::class.java,
            ) ||
            IProjectManager.getInstance().getWorkspace() == null
    ) {
      markInvisible()
      return
    }

    visible = true
    enabled = JavaPsiServiceInterop.isAvailable()
  }

  override suspend fun execAction(data: ActionData): Any {
    val range = data[Range::class.java]!!
    val file = data.requirePath()
    val identity = data[DocumentSnapshotIdentity::class.java] ?: return StaleFieldsResult
    val snapshot = FileManager.getActiveDocumentSnapshot(file) ?: return StaleFieldsResult
    if (identity.file.normalize() != file.normalize() || identity.version != snapshot.version ||
        identity.revision != snapshot.revision) return StaleFieldsResult
    return JavaPsiServiceInterop.fields(
        JavaPsiFieldsParams(
            file,
            range,
            snapshot.version,
            snapshot.revision,
            ICancelChecker.NOOP,
        ),
    ) ?: StaleFieldsResult
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result === StaleFieldsResult) return
    val fields = result as JavaPsiFieldsResult
    val snapshot = FileManager.getActiveDocumentSnapshot(fields.file) ?: return
    if (snapshot.version != fields.documentVersion || snapshot.revision != fields.documentRevision) return
    if (fields.fields.isEmpty()) {
      flashInfo(data[Context::class.java]!!.getString(R.string.msg_no_fields_found))
      return
    }
    onGetPsiFields(fields, data)
  }

  protected abstract fun onGetPsiFields(result: JavaPsiFieldsResult, data: ActionData)

  protected fun showFieldSelector(
      labels: List<String>,
      data: ActionData,
      fields: List<String>,
      listener: OnFieldsSelectedListener?,
  ) {
    val names = labels.toTypedArray()
    val checkedNames = mutableSetOf<String>()
    val builder = newDialogBuilder(data)
    builder.setTitle(data[Context::class.java]!!.getString(R.string.msg_select_fields))
    builder.setMultiChoiceItems(names, BooleanArray(fields.size)) { _, which, checked ->
      checkedNames.apply {
        val item = fields[which]
        if (checked) {
          add(item)
        } else {
          remove(item)
        }
      }
    }

    builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
      dialog.dismiss()

      if (checkedNames.isEmpty()) {
        flashInfo(data[Context::class.java]!!.getString(R.string.msg_no_fields_selected))
        return@setPositiveButton
      }

      listener?.onFieldsSelected(checkedNames)
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  /** Listener to get callback when fields are selected by the user. */
  fun interface OnFieldsSelectedListener {

    /**
     * Called when the user is done selecting fields.
     *
     * @param fields The selected field names.
     */
    fun onFieldsSelected(fields: MutableSet<String>)
  }
}
