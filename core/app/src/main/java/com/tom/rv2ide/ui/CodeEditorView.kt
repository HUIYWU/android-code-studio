package com.tom.rv2ide.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.isVisible
import com.blankj.utilcode.util.SizeUtils
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.BaseEditorActivity
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.editor.databinding.LayoutCodeEditorBinding
import com.tom.rv2ide.editor.ui.EditorSearchLayout
import com.tom.rv2ide.editor.ui.IDEEditor
import com.tom.rv2ide.editor.ui.IDEEditor.Companion.createInputTypeFlags
import com.tom.rv2ide.editor.ui.cleanupCompletionTooltips
import com.tom.rv2ide.editor.ui.cleanupHoverTooltips
import com.tom.rv2ide.editor.ui.clearDiagnostics
import com.tom.rv2ide.editor.ui.initCompletionTooltips
import com.tom.rv2ide.editor.ui.initDiagnosticHandling
import com.tom.rv2ide.editor.ui.initHoverTooltips
import com.tom.rv2ide.editor.ui.setHoverTooltipsVisible
import com.tom.rv2ide.editor.ui.updateEditorDiagnostics
import com.tom.rv2ide.editor.utils.ContentReadWrite.readContent
import com.tom.rv2ide.editor.utils.ContentReadWrite.writeTo
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.preferences.PreferenceChangeEvent
import com.tom.rv2ide.lsp.IDELanguageClientImpl
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.ILanguageServerRegistry
import com.tom.rv2ide.language.services.java.JavaLanguageServer
import com.tom.rv2ide.language.services.clang.ClangLanguageServer
import com.tom.rv2ide.language.services.kotlin.KotlinLanguageServer
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.xml.XMLLanguageServer
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import com.tom.rv2ide.tasks.cancelIfActive
import com.tom.rv2ide.tasks.runOnUiThread
import com.tom.rv2ide.utils.EditorFontResolver
import com.tom.rv2ide.utils.LargeFileOptimizationHelper
import com.tom.rv2ide.artificial.completion.SuggestionView
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.Magnifier
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import com.tom.rv2ide.managers.PreferenceManager
import android.graphics.Typeface

@SuppressLint("ViewConstructor")
class CodeEditorView(context: Context, file: File, selection: Range) :
    LinearLayoutCompat(context), Closeable {

  private var _binding: LayoutCodeEditorBinding? = null
  private var requestedFile: File = file
  private var _searchLayout: EditorSearchLayout? = null
  private var _suggestionView: SuggestionView? = null
  private val prefManager: PreferenceManager
    get() = BaseApplication.getBaseInstance().prefManager
    
  private val codeEditorScope =
      CoroutineScope(Dispatchers.Default + CoroutineName("CodeEditorView"))

  @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
  private val readWriteContext = newSingleThreadContext("CodeEditorView")

  private val binding: LayoutCodeEditorBinding
    get() = checkNotNull(_binding) { "Binding has been destroyed" }

  private val searchLayout: EditorSearchLayout
    get() = ensureSearchLayout()

  val suggestionView: SuggestionView?
    get() = ensureSuggestionView()

  private var analysisJob: Job? = null

  private var contentReady = false
  private val contentReadyCallbacks = mutableListOf<() -> Unit>()

  /** Run [action] after this editor has applied file content, language setup and selection. */
  fun doOnContentReady(action: () -> Unit) {
    if (contentReady) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        action()
      } else {
        post(action)
      }
      return
    }
    contentReadyCallbacks.add(action)
  }

  private fun markContentReady() {
    if (contentReady) {
      return
    }
    contentReady = true
    val callbacks = contentReadyCallbacks.toList()
    contentReadyCallbacks.clear()
    if (Looper.myLooper() == Looper.getMainLooper()) {
      callbacks.forEach { it() }
    } else {
      callbacks.forEach { post(it) }
    }
  }

  /** Get the file requested for this editor, including while its content is still loading. */
  val file: File?
    get() = editor?.file ?: requestedFile

  /** Get the [IDEEditor] instance of this editor view. */
  val editor: IDEEditor?
    get() = _binding?.editor

  /**
   * Returns whether the content of the editor has been modified.
   *
   * @see IDEEditor.isModified
   */
  val isModified: Boolean
    get() = editor?.isModified ?: false

  companion object {
    private val log = LoggerFactory.getLogger(CodeEditorView::class.java)
    private var prewarmedBinding: LayoutCodeEditorBinding? = null
    private var cachedTypefacePath: String? = null
    private var cachedTypeface: Typeface? = null
    private var cachedLineNumberTypeface: Typeface? = null

    fun prewarmEditorBinding(context: Context) {
      prewarmTextAppearanceCache()
      if (prewarmedBinding != null) {
        return
      }
      runCatching {
        prewarmedBinding = LayoutCodeEditorBinding.inflate(LayoutInflater.from(context))
      }.onFailure { err ->
        if (IdeLogConfig.shouldLogDebug()) {
          log.debug("Failed to prewarm editor binding", err)
        }
        prewarmedBinding = null
      }
    }
    fun clearPrewarmedEditorBinding() {
      prewarmedBinding?.editor?.runCatching { release() }
      prewarmedBinding = null
    }

    private fun prewarmTextAppearanceCache() {
      val fontKey = EditorFontResolver.cacheKey(EditorPreferences.selectedCustomFont)

      if (cachedTypefacePath != fontKey || cachedTypeface == null) {
        cachedTypeface =
            EditorFontResolver.resolve(
                BaseApplication.getBaseInstance(),
                EditorPreferences.selectedCustomFont,
            )
        cachedTypefacePath = fontKey
      }
      if (cachedLineNumberTypeface == null) {
        cachedLineNumberTypeface = cachedTypeface
      }
    }
  }

  private fun obtainBinding(context: Context): LayoutCodeEditorBinding {
    prewarmedBinding?.let { binding ->
      prewarmedBinding = null
      return binding
    }
    return LayoutCodeEditorBinding.inflate(LayoutInflater.from(context))
  }

  private fun ensureSearchLayout(): EditorSearchLayout {
    _binding ?: error("Binding has been destroyed")
    return _searchLayout ?: EditorSearchLayout(context, binding.editor).apply {
      setImeRouteProvider {
        (context as? BaseEditorActivity)?.content?.bottomSheet?.isImeRoutedToSidebar() == true
      }
      setOnSearchVisibilityChangeListener { isVisible ->
        val activity = context as? BaseEditorActivity ?: return@setOnSearchVisibilityChangeListener
        activity.onEditorSearchVisibilityChanged(isVisible)
      }
    }.also { layout ->
      _searchLayout = layout
      addView(layout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
  }

  private fun ensureSuggestionView(): SuggestionView? {
    // Keep the editor layout identical to upstream AndroidIDE. Inline suggestion UI is
    // intentionally disabled for now: the presentation approach (hosting, alignment, theming)
    // is still to be decided; do not mount the view here.
    return null
  }

  init {
    _binding = obtainBinding(context)

    binding.editor.apply {
      isHighlightCurrentBlock = true
      props.autoCompletionOnComposing = true
      dividerWidth = SizeUtils.dp2px(2f).toFloat()
      colorScheme = SchemeAndroidIDE.newInstance(context)
      lineSeparator = LineSeparator.LF
    }

    orientation = VERTICAL

    removeAllViews()

    addView(binding.root, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

    applyLargeFileOptimizationsBeforeRead(file)
    readFileAndApplySelection(file, selection)

    // Setup content change listener after initialization
    setupContentChangeListener()
  }

  private fun setupContentChangeListener() {
    binding.editor.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) {
        _: io.github.rosemoe.sora.event.ContentChangeEvent,
        _: Any? ->
      val editorFile = binding.editor.file
      if (editorFile != null && editorFile.extension in setOf("c", "cpp", "cc", "cxx", "h", "hpp")) {
        startDiagnosticAnalysis(editorFile)
      }
    }
  }

  /** Get the file of this editor. Throws [IllegalStateException] if no file is available. */
  fun requireFile(): File {
    return checkNotNull(file)
  }

  /**
   * Update the file of this editor. This only updates the file reference of the editor and does not
   * resets the content.
   */
  fun updateFile(file: File) {
    requestedFile = file
    val editor = _binding?.editor ?: return
    editor.file = file
    postRead(file)
  }

  /** Called when the editor has been selected and is visible to the user. */
  fun onEditorSelected() {
    _binding?.editor?.apply {
      setHoverTooltipsVisible(true)
      onEditorSelected()
    } ?: run { log.warn("onEditorSelected() called but no editor instance is available") }
  }

  fun onEditorUnselected() {
    _binding?.editor?.setHoverTooltipsVisible(false)
  }

  /** Begins search mode and shows the [search layout][EditorSearchLayout]. */
  fun beginSearch() {
    if (_binding == null) {
      log.warn("Editor layout is null; cannot begin search")
      return
    }

    searchLayout.beginSearchMode()
  }

  /** Mark this files as saved. Even if it not saved. */
  fun markAsSaved() {
    editor?.markUnmodified()
  }

  /**
   * Saves the content of the editor to the editor's file.
   *
   * @return Whether the save operation was successfully completed or not. If this method returns
   *   `false`, it means that there was an error saving the file or the content of the file was not
   *   modified and hence the save operation was skipped.
   */
  suspend fun save(buildWillFollow: Boolean = false): Boolean {
    val file = this.file ?: return false

    if (!isModified && file.exists()) {
      log.info("File was not modified. Skipping save operation for file {}", file.name)
      return false
    }

    val text =
        _binding?.editor?.text
            ?: run {
              log.error(
                  "Failed to save file. Unable to retrieve the content of editor as it is null."
              )
              return false
            }

    withContext(Dispatchers.Main.immediate) {
      withEditingDisabled {
        withContext(readWriteContext) {
          text.writeTo(file, this@CodeEditorView::updateReadWriteProgress)
        }
      }

      _binding?.rwProgress?.isVisible = false
    }

    markUnmodified()
    notifySaved(buildWillFollow)

    return true
  }

  private fun startDiagnosticAnalysis(file: File) {
    analysisJob?.cancel()

    analysisJob =
        codeEditorScope.launch {
          delay(500)

          val editor = _binding?.editor ?: return@launch
          val languageServer = editor.languageServer

          // Clang lsp
          if (
              languageServer is ClangLanguageServer &&
                  (file.extension == "cpp" ||
                      file.extension == "c" ||
                      file.extension == "cc" ||
                      file.extension == "cxx" ||
                      file.extension == "h" ||
                      file.extension == "hpp")
          ) {
            try {
              val result = languageServer.analyze(file.toPath())

              if (result != DiagnosticResult.NO_UPDATE) {
                withContext(Dispatchers.Main) {
                  editor.updateEditorDiagnostics(result.diagnostics)
                }
              }
            } catch (e: Exception) {
              log.error("Failed to analyze file for diagnostics", e)
            }
          }
        }
  }

  private fun updateReadWriteProgress(progress: Int) {
    val binding = this.binding
    runOnUiThread {
      if (progress < 0 || progress >= 100) {
        binding.rwProgress.isVisible = false
        return@runOnUiThread
      }

      if (!binding.rwProgress.isVisible) {
        binding.rwProgress.isVisible = true
      }

      binding.rwProgress.progress = progress
    }
  }

  private inline fun <R : Any?> withEditingDisabled(action: () -> R): R {
    return try {
      _binding?.editor?.isEditable = false
      action()
    } finally {
      _binding?.editor?.isEditable = true
    }
  }

  private fun applyLargeFileOptimizationsBeforeRead(file: File) {
    if (file.length() <= LargeFileOptimizationHelper.LARGE_FILE_THRESHOLD) {
      return
    }

    val largeFileHelper = LargeFileOptimizationHelper(context, binding.editor)
    if (largeFileHelper.shouldApplyOptimizations(file)) {
      // Apply editor props before file content is read and assigned. This keeps expensive Sora
      // features disabled for the first large-file setText pass, not only for later edits.
      largeFileHelper.applyLargeFileOptimizations(file)
    }
  }

  private fun readFileAndApplySelection(file: File, selection: Range) {
    val shouldShowReadProgress = file.length() > LargeFileOptimizationHelper.LARGE_FILE_THRESHOLD

    codeEditorScope.launch(Dispatchers.Main.immediate) {
      if (shouldShowReadProgress) {
        updateReadWriteProgress(0)
      }

      withEditingDisabled {
        val content =
            withContext(readWriteContext) {
              selection.validate()
              val progressConsumer: ((Int) -> Unit)? =
                  if (shouldShowReadProgress) this@CodeEditorView::updateReadWriteProgress else null
              file.readContent(progressConsumer)
            }

        initializeContent(content, file, selection)
        _binding?.rwProgress?.isVisible = false
      }
    }
  }

  private fun initializeContent(content: Content, file: File, selection: Range) {
    val ideEditor = binding.editor
    val initializeAction: () -> Unit = {
      val args = Bundle().apply { putString(IEditor.KEY_FILE, file.absolutePath) }

      configureEditorTextAppearance()

      ideEditor.setText(content, args)

      markUnmodified()
      postRead(file)

      ideEditor.validateRange(selection)
      ideEditor.setSelection(selection)

      markContentReady()

      ideEditor.postDelayed(
        {
          if (_binding == null) {
            return@postDelayed
          }
          configureEditorIfNeeded()
        },
        48,
      )

      // Display is updated synchronously by EditorHandlerActivity, following upstream AndroidIDE behavior.
    }

    if (ideEditor.isAttachedToWindow) {
      initializeAction()
    } else {
      ideEditor.postInLifecycle(initializeAction)
    }
  }

  // Editor display is updated synchronously by EditorHandlerActivity.

  private fun postRead(file: File) {
    binding.editor.setupLanguage(file)
    binding.editor.setLanguageServer(createLanguageServer(file))

    if (IDELanguageClientImpl.isInitialized()) {
      binding.editor.setLanguageClient(IDELanguageClientImpl.getInstance())
    }

    requestedFile = file
    binding.editor.file = file

    if (file.extension in setOf("c", "cpp", "cc", "cxx", "h", "hpp")) {
      binding.editor.initDiagnosticHandling()
      startDiagnosticAnalysis(file)
    }
    binding.editor.postDelayed(
      {
        if (_binding == null) {
          return@postDelayed
        }
        binding.editor.initCompletionTooltips()
        binding.editor.initHoverTooltips()

        (context as? Activity?)?.invalidateOptionsMenu()
      },
      350,
    )

    binding.editor.postDelayed(
      {
        val activity = context as? Activity
        if (_binding != null && isAttachedToWindow && activity?.isFinishing == false) {
          prewarmEditorBinding(activity)
        }
      },
      800,
    )
  }

  private fun createLanguageServer(file: File): ILanguageServer? {
    if (!file.isFile) {
      return null
    }

    val serverID: String =
        when (file.extension) {
          "java" -> JavaLanguageServer.SERVER_ID
          "xml" -> XMLLanguageServer.SERVER_ID
          "kt" -> KotlinLanguageServer.SERVER_ID
          "cpp", "c", "cc", "cxx", "h", "hpp" -> ClangLanguageServer.SERVER_ID
          else -> return null
        }
    return ILanguageServerRegistry.getDefault().getServer(serverID)
  }

  private fun configureEditorTextAppearance() {
    onCustomFontPrefChanged()
    onFontSizePrefChanged()
    onFontLigaturesPrefChanged()
  }

  private fun configureEditorIfNeeded() {
    onPrintingFlagsPrefChanged()
    onInputTypePrefChanged()
    onWordwrapPrefChanged()
    onMagnifierPrefChanged()
    onUseIcuPrefChanged()
    onDeleteEmptyLinesPrefChanged()
    onDeleteTabsPrefChanged()
    onStickyScrollEnabeldPrefChanged()
    onPinLineNumbersPrefChanged()
  }

  private fun onMagnifierPrefChanged() {
    binding.editor.getComponent(Magnifier::class.java).isEnabled = EditorPreferences.useMagnifier
  }

  private fun onWordwrapPrefChanged() {
    val enabled = EditorPreferences.wordwrap
    binding.editor.isWordwrap = enabled
  }

  private fun onInputTypePrefChanged() {
      binding.editor.inputType = createInputTypeFlags()
  }

  private fun onPrintingFlagsPrefChanged() {
    var flags = 0
    if (EditorPreferences.drawLeadingWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING
    }
    if (EditorPreferences.drawTrailingWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
    }
    if (EditorPreferences.drawInnerWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_INNER
    }
    if (EditorPreferences.drawEmptyLineWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
    }
    if (EditorPreferences.drawLineBreak) {
      flags = flags or CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    binding.editor.nonPrintablePaintingFlags = flags
  }

  private fun onFontLigaturesPrefChanged() {
    val enabled = EditorPreferences.fontLigatures
    binding.editor.isLigatureEnabled = enabled
  }

  private fun onFontSizePrefChanged() {
    var textSize = EditorPreferences.fontSize
    if (textSize < 6 || textSize > 32) {
      textSize = 14f
    }
    binding.editor.setTextSize(textSize)
  }

  private fun onUseIcuPrefChanged() {
    binding.editor.props.useICULibToSelectWords = EditorPreferences.useIcu
  }
  private fun onCustomFontPrefChanged() {
    val fontKey = EditorFontResolver.cacheKey(EditorPreferences.selectedCustomFont)

    val typeface =
        if (cachedTypefacePath == fontKey && cachedTypeface != null) {
          cachedTypeface!!
        } else {
          EditorFontResolver.resolve(context, EditorPreferences.selectedCustomFont).also {
            cachedTypefacePath = fontKey
            cachedTypeface = it
          }
        }

    val lineNumberTypeface =
        if (cachedLineNumberTypeface != null && cachedTypefacePath == fontKey) {
          cachedLineNumberTypeface!!
        } else {
          typeface.also {
            cachedLineNumberTypeface = it
          }
        }

    binding.editor.typefaceText = typeface
    binding.editor.typefaceLineNumber = lineNumberTypeface
  }

  private fun onDeleteEmptyLinesPrefChanged() {
    binding.editor.props.deleteEmptyLineFast = EditorPreferences.deleteEmptyLines
  }

  private fun onDeleteTabsPrefChanged() {
    binding.editor.props.deleteMultiSpaces = if (EditorPreferences.deleteTabsOnBackspace) -1 else 1
  }

  private fun onStickyScrollEnabeldPrefChanged() {
    binding.editor.props.stickyScroll = EditorPreferences.stickyScrollEnabled
  }

  private fun onPinLineNumbersPrefChanged() {
    binding.editor.setPinLineNumber(EditorPreferences.pinLineNumbers)
  }

  internal fun markUnmodified() {
    binding.editor.markUnmodified()
  }

  internal fun markModified() {
    binding.editor.markModified()
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  @Suppress("unused")
  fun onPreferenceChanged(event: PreferenceChangeEvent) {
    if (_binding == null) {
      return
    }

    BaseApplication.getBaseInstance().prefManager
    when (event.key) {
      EditorPreferences.FONT_SIZE -> onFontSizePrefChanged()
      EditorPreferences.FONT_LIGATURES -> onFontLigaturesPrefChanged()

      EditorPreferences.FLAG_LINE_BREAK,
      EditorPreferences.FLAG_WS_INNER,
      EditorPreferences.FLAG_WS_EMPTY_LINE,
      EditorPreferences.FLAG_WS_LEADING,
      EditorPreferences.FLAG_WS_TRAILING -> onPrintingFlagsPrefChanged()
      EditorPreferences.KEYBOARD_SUGGESTIONS -> onInputTypePrefChanged()
    
      EditorPreferences.FLAG_PASSWORD -> onInputTypePrefChanged()
      EditorPreferences.WORD_WRAP -> onWordwrapPrefChanged()
      EditorPreferences.USE_MAGNIFER -> onMagnifierPrefChanged()
      EditorPreferences.USE_ICU -> onUseIcuPrefChanged()
      EditorPreferences.USE_CUSTOM_FONT -> onCustomFontPrefChanged()
      EditorPreferences.DELETE_EMPTY_LINES -> onDeleteEmptyLinesPrefChanged()
      EditorPreferences.DELETE_TABS_ON_BACKSPACE -> onDeleteTabsPrefChanged()
      EditorPreferences.STICKY_SCROLL_ENABLED -> onStickyScrollEnabeldPrefChanged()
      EditorPreferences.PIN_LINE_NUMBERS -> onPinLineNumbersPrefChanged()
    }
  }

  private fun notifySaved(buildWillFollow: Boolean) {
    binding.editor.dispatchDocumentSaveEvent(buildWillFollow)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    EventBus.getDefault().unregister(this)
  }

  override fun close() {
    val closedFile = file?.toPath()
    if (closedFile != null) {
      val closeEvent = DocumentCloseEvent(closedFile)
      // Resource-table snapshots must no longer observe this document before subscribers schedule
      // their refresh. This matches the established rename/delete event ordering.
      FileManager.onDocumentClose(closeEvent)
      EventBus.getDefault().post(closeEvent)
    }

    analysisJob?.cancel()
    codeEditorScope.cancelIfActive("Cancellation was requested")
    _binding?.editor?.apply {
      clearDiagnostics()
      cleanupCompletionTooltips()
      cleanupHoverTooltips()
      notifyClose()
      release()
    }

    readWriteContext.use {}
    _binding = null
    contentReadyCallbacks.clear()
  }
}