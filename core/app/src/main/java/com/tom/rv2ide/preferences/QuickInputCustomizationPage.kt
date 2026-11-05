package com.tom.rv2ide.preferences

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R as AppR
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.utils.EditorQuickInputPreferences
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

internal const val QUICK_INPUT_CUSTOMIZE_SCREEN_KEY = "idepref_editor_quickInputCustomize"
internal const val QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT = "quickInputCustomizeRefresh"

enum class QuickInputTextType(val displayName: String, val titleRes: Int) {
  PLAIN_TEXT("Plain text", string.idepref_editor_quickInputType_plainText),
  JAVA_JVM("Java / JVM", string.idepref_editor_quickInputType_javaJvm),
  XML("XML", string.idepref_editor_quickInputType_xml)
}

internal fun quickInputCustomizeChildren(): List<IPreference> {
  val type = selectedQuickInputTextType()
  val items = EditorQuickInputPreferences.customizationItems(type)
  return buildList {
    add(QuickInputTextTypeSelector())
    add(ResetQuickInputTextType())
    add(AddQuickInputItem(items.size))
    add(QuickInputItemsPreference(type, items))
  }
}

internal fun selectedQuickInputTextType(): QuickInputTextType = runCatching {
  QuickInputTextType.valueOf(EditorPreferences.quickInputCustomizeTextType)
}.getOrDefault(QuickInputTextType.PLAIN_TEXT)

@Parcelize
private class QuickInputTextTypeSelector(
    override val key: String = "idepref_editor_quickInputTextType",
    override val title: Int = string.idepref_editor_quickInputTextType_title,
    override val summary: Int? = string.idepref_editor_quickInputTextType_summary,
) : SingleChoicePreference() {
  override fun getEntries(preference: Preference) = QuickInputTextType.entries.map {
    PreferenceChoices.Entry(it.displayName, it == selectedQuickInputTextType(), it)
  }.toTypedArray()

  override fun onChoiceConfirmed(preference: Preference, entry: PreferenceChoices.Entry?, position: Int) {
    val type = entry?.data as? QuickInputTextType ?: return
    EditorPreferences.quickInputCustomizeTextType = type.name
    requestPageRefresh(preference.context)
  }
}

@Parcelize
private class ResetQuickInputTextType(
    override val key: String = "idepref_editor_quickInputResetType",
    override val title: Int = string.idepref_editor_quickInputReset_title,
    override val summary: Int? = string.idepref_editor_quickInputReset_summary,
) : BasePreference() {
  override fun onCreatePreference(context: Context) = Preference(context)
  override fun onPreferenceClick(preference: Preference): Boolean {
    MaterialAlertDialogBuilder(preference.context)
        .setTitle(string.idepref_editor_quickInputReset_title)
        .setMessage(string.idepref_editor_quickInputReset_confirm)
        .setPositiveButton(string.reset) { _, _ ->
          EditorQuickInputPreferences.resetItems(selectedQuickInputTextType())
          requestPageRefresh(preference.context)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
  }
}

@Parcelize
private class QuickInputItemsPreference(
    private val type: QuickInputTextType,
    private val items: @RawValue List<EditorQuickInputPreferences.StoredItem>,
    override val key: String = "idepref_editor_quickInputItems:${type.name}",
    override val title: Int = string.idepref_editor_quickInputItem_title,
) : IPreference() {
  override fun onCreateView(context: Context): Preference = object : Preference(context) {
    init {
      layoutResource = AppR.layout.preference_quick_input_items
      isSelectable = false
      isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
      super.onBindViewHolder(holder)
      holder.itemView.findViewById<TextView>(AppR.id.quick_input_items_title).setText(type.titleRes)
      val recyclerView = holder.itemView.findViewById<RecyclerView>(AppR.id.quick_input_items_list)
      recyclerView.layoutManager = LinearLayoutManager(context)
      recyclerView.isNestedScrollingEnabled = false
      recyclerView.itemAnimator = DefaultItemAnimator().apply {
        moveDuration = 180
        addDuration = 0
        removeDuration = 0
        changeDuration = 0
        supportsChangeAnimations = false
      }
      recyclerView.adapter = QuickInputItemsAdapter(context, type, items.toMutableList())
    }
  }
}

private class QuickInputItemsAdapter(
    private val context: Context,
    private val type: QuickInputTextType,
    private val items: MutableList<EditorQuickInputPreferences.StoredItem>,
) : RecyclerView.Adapter<QuickInputItemsAdapter.ViewHolder>() {
  private val dragHelper: ItemTouchHelper

  init {
    setHasStableIds(true)
    dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
      override fun isLongPressDragEnabled() = false

      override fun onMove(
          recyclerView: RecyclerView,
          source: RecyclerView.ViewHolder,
          target: RecyclerView.ViewHolder,
      ): Boolean {
        val from = source.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
      }

      override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

      override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        EditorQuickInputPreferences.saveItems(type, items)
      }
    })
  }

  override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
    super.onAttachedToRecyclerView(recyclerView)
    dragHelper.attachToRecyclerView(recyclerView)
  }

  override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
    dragHelper.attachToRecyclerView(null)
    super.onDetachedFromRecyclerView(recyclerView)
  }

  override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
      LayoutInflater.from(parent.context).inflate(AppR.layout.item_quick_input_customization, parent, false),
  )

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = items[position]
    holder.title.text = item.label
    holder.summary.text = item.actionId?.let { EditorQuickInputPreferences.labelForActionId(context, it) }
        ?: context.getString(string.idepref_editor_quickInputEdit_insert)
    holder.itemView.setOnClickListener {
      val currentPosition = holder.bindingAdapterPosition
      if (currentPosition != RecyclerView.NO_POSITION) {
        showItemEditor(context, currentPosition, items[currentPosition])
      }
    }
    holder.handle.setOnTouchListener { _, event ->
      if (event.actionMasked == MotionEvent.ACTION_DOWN) dragHelper.startDrag(holder)
      true
    }
  }

  override fun getItemCount() = items.size

  class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val title: TextView = view.findViewById(AppR.id.quick_input_item_title)
    val summary: TextView = view.findViewById(AppR.id.quick_input_item_summary)
    val handle: View = view.findViewById(AppR.id.quick_input_drag_handle)
  }
}

@Parcelize
private class AddQuickInputItem(
    private val itemCount: Int,
    override val key: String = "idepref_editor_quickInputAddItem",
    override val title: Int = string.idepref_editor_quickInputAdd_title,
    override val summary: Int? = string.idepref_editor_quickInputAdd_summary,
) : BasePreference() {
  override fun onCreatePreference(context: Context) = Preference(context)
  override fun onPreferenceClick(preference: Preference): Boolean {
    if (itemCount >= EditorQuickInputPreferences.MAX_ITEMS) {
      Toast.makeText(preference.context, string.idepref_editor_quickInputLimit_message, Toast.LENGTH_SHORT).show()
    } else showItemEditor(preference.context, null, null)
    return true
  }
}

// TextInputLayout.helperText adds an indicator subtree that destabilizes dialog entrance animation.
private fun createQuickInputHelper(context: Context, text: Int): TextView {
  val horizontalInset = (12 * context.resources.displayMetrics.density).toInt()
  return TextView(context).apply {
    setText(text)
    setTextAppearance(AppR.style.TextAppearance_Material3_BodySmall)
    includeFontPadding = false
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
      marginStart = horizontalInset
      marginEnd = horizontalInset
    }
  }
}

private fun showItemEditor(context: Context, index: Int?, existing: EditorQuickInputPreferences.StoredItem?) {
  val padding = (20 * context.resources.displayMetrics.density).toInt()
  val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
  val name = EditText(context).apply { setText(existing?.label.orEmpty()) }
  val nameLayout = TextInputLayout(context).apply {
    hint = context.getString(string.idepref_editor_quickInputEdit_name)
    addView(name)
  }
  val nameHelper = createQuickInputHelper(
    context,
    string.idepref_editor_quickInputEdit_name_hint,
  )
  val text = EditText(context).apply {
    setText(existing?.let(::displayInsertionText).orEmpty()); minLines = 2
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
  }
  val textLayout = TextInputLayout(context).apply {
    hint = context.getString(string.idepref_editor_quickInputEdit_text)
    addView(text)
  }
  val textHelper = createQuickInputHelper(
    context,
    string.idepref_editor_quickInputEdit_text_hint,
  )
  val functionOptions = buildList {
    add(context.getString(string.idepref_editor_quickInputEdit_insert))
    addAll(EditorQuickInputPreferences.supportedActions.map { context.getString(it.labelRes) })
  }
  val selectedFunctionIndex = EditorQuickInputPreferences.supportedActions
      .indexOfFirst { it.id == existing?.actionId }
      .let { if (it < 0) 0 else it + 1 }
  val functionInput = MaterialAutoCompleteTextView(context).apply {
    keyListener = null
    inputType = InputType.TYPE_NULL
    isFocusable = false
    isClickable = true
    setText(functionOptions[selectedFunctionIndex], false)
  }
  val functionLayout = TextInputLayout(context).apply {
    hint = context.getString(string.idepref_editor_quickInputEdit_function)
    endIconMode = TextInputLayout.END_ICON_CUSTOM
    endIconDrawable = ContextCompat.getDrawable(context, AppR.drawable.ic_dropdown_arrow)
    addView(functionInput)
  }
  var selectedFunction = selectedFunctionIndex
  var functionPopup: ListPopupWindow? = null
  var dialogClosing = false
  fun updateFunctionSelection() {
    val showTextInput = selectedFunction == 0
    textLayout.isVisible = showTextInput
    textHelper.isVisible = showTextInput
  }
  fun dismissFunctionPopup() {
    functionPopup?.dismiss()
    functionPopup = null
  }
  fun showFunctionPopup() {
    if (dialogClosing) return
    dismissFunctionPopup()
    functionPopup = ListPopupWindow(context).apply {
      anchorView = functionInput
      isModal = true
      width = functionInput.width
      setAdapter(ArrayAdapter(context, AppR.layout.item_atc_dropdown, functionOptions))
      setBackgroundDrawable(ContextCompat.getDrawable(context, AppR.drawable.bg_atc_dropdown_popup))
      setOnItemClickListener { _, _, position, _ ->
        selectedFunction = position
        functionInput.setText(functionOptions[position], false)
        updateFunctionSelection()
        dismissFunctionPopup()
      }
      setOnDismissListener { functionPopup = null }
      show()
    }
  }
  functionInput.setOnClickListener { showFunctionPopup() }
  functionLayout.setEndIconOnClickListener { showFunctionPopup() }
  updateFunctionSelection()
  content.addView(nameLayout)
  content.addView(nameHelper)
  content.addView(functionLayout)
  content.addView(textLayout)
  content.addView(textHelper)
  val view = ScrollView(context).apply { addView(content, ViewGroup.LayoutParams(-1, -2)) }
  val dialog = MaterialAlertDialogBuilder(context).setTitle(string.idepref_editor_quickInputEdit_title)
      .setView(view).setPositiveButton(string.action_save, null)
      .setNegativeButton(android.R.string.cancel, null)
      .apply {
        if (existing != null) {
          setNeutralButton(string.delete) { _, _ ->
            val type = selectedQuickInputTextType()
            val items = EditorQuickInputPreferences.customizationItems(type).toMutableList()
            items.removeAt(index!!)
            EditorQuickInputPreferences.saveItems(type, items)
            requestPageRefresh(context)
          }
        }
      }
      .create()
  dialog.setOnShowListener {
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
      val label = name.text.toString().trim()
      val actionId = EditorQuickInputPreferences.supportedActions
          .getOrNull(selectedFunction - 1)
          ?.id
      val saved = when {
        label.isEmpty() -> null
        actionId != null -> EditorQuickInputPreferences.StoredItem(existing?.id ?: EditorQuickInputPreferences.newItemId(), label, actionId = actionId)
        else -> parseInsertion(label, text.text.toString(), existing?.id)
      }
      val nameValid = label.isNotEmpty() && isCompactLabel(label)
      val textValid = actionId != null || !saved?.text.isNullOrEmpty()
      nameLayout.error = if (nameValid) null else context.getString(string.idepref_editor_quickInputEdit_name_error)
      textLayout.error = if (textValid) null else context.getString(string.idepref_editor_quickInputEdit_text_error)
      if (!nameValid || !textValid || saved == null) {
        return@setOnClickListener
      }
      val type = selectedQuickInputTextType()
      val items = EditorQuickInputPreferences.customizationItems(type).toMutableList()
      if (index == null) items.add(saved) else items[index] = saved
      EditorQuickInputPreferences.saveItems(type, items)
      dialog.dismiss(); requestPageRefresh(context)
    }
  }
  fun closeFunctionPopup() {
    dialogClosing = true
    functionInput.isEnabled = false
    dismissFunctionPopup()
  }
  dialog.setOnCancelListener { closeFunctionPopup() }
  dialog.setOnDismissListener { closeFunctionPopup() }
  dialog.show()
}

private fun displayInsertionText(item: EditorQuickInputPreferences.StoredItem): String {
  val text = item.text ?: return ""
  val cursor = item.offset.coerceIn(0, text.length)
  return buildString {
    text.forEachIndexed { index, char ->
      if (index == cursor) append('|')
      if (char == '|' || char == '\\') append('\\')
      append(char)
    }
    if (cursor == text.length) append('|')
  }
}

private fun isCompactLabel(label: String): Boolean {
  return if (label.all { it.code <= 0x7f }) label.length <= 3 else label.codePointCount(0, label.length) <= 2
}

/** First unescaped | sets the optional cursor position; \| and \\ insert literal characters. */
private fun parseInsertion(label: String, raw: String, existingId: String?): EditorQuickInputPreferences.StoredItem? {
  if (raw.isEmpty()) return null
  val output = StringBuilder(); var offset: Int? = null; var escaped = false
  raw.forEach { char -> when {
    escaped && (char == '|' || char == '\\') -> { output.append(char); escaped = false }
    escaped -> { output.append('\\').append(char); escaped = false }
    char == '\\' -> escaped = true
    char == '|' && offset == null -> offset = output.length
    else -> output.append(char)
  } }
  if (escaped) output.append('\\')
  return EditorQuickInputPreferences.StoredItem(existingId ?: EditorQuickInputPreferences.newItemId(), label, output.toString(), offset ?: output.length)
}

private fun requestPageRefresh(context: Context) {
  (context as? FragmentActivity)?.supportFragmentManager?.setFragmentResult(QUICK_INPUT_CUSTOMIZE_REFRESH_RESULT, Bundle.EMPTY)
}