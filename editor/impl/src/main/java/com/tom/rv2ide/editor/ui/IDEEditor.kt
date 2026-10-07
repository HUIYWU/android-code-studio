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

package com.tom.rv2ide.editor.ui

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.SizeUtils
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.editor.R.string
import com.tom.rv2ide.editor.adapters.CompletionListAdapter
import com.tom.rv2ide.editor.api.IEditor
import com.tom.rv2ide.editor.api.ILspEditor
import com.tom.rv2ide.editor.language.IDELanguage
import com.tom.rv2ide.editor.language.groovy.GroovyLanguage
import com.tom.rv2ide.editor.language.treesitter.TreeSitterLanguage
import com.tom.rv2ide.editor.language.treesitter.TreeSitterLanguageProvider
import com.tom.rv2ide.editor.schemes.IDEColorScheme
import com.tom.rv2ide.editor.schemes.IDEColorSchemeProvider
import com.tom.rv2ide.editor.snippets.AbstractSnippetVariableResolver
import com.tom.rv2ide.editor.snippets.FileVariableResolver
import com.tom.rv2ide.editor.snippets.WorkspaceVariableResolver
import com.tom.rv2ide.eventbus.events.editor.ChangeType
import com.tom.rv2ide.eventbus.events.editor.ColorSchemeInvalidatedEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.eventbus.events.editor.LazyDocumentTextProvider
import com.tom.rv2ide.flashbar.Flashbar
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.language.services.java.utils.CancelChecker
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.ShowDocumentParams
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.preferences.internal.EditorPreferences
import com.tom.rv2ide.progress.ICancelChecker
import com.tom.rv2ide.syntax.colorschemes.DynamicColorScheme
import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import com.tom.rv2ide.tasks.JobCancelChecker
import com.tom.rv2ide.tasks.cancelIfActive
import com.tom.rv2ide.tasks.launchAsyncWithProgress
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashInfo
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.IDEEditorSearcher
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.EditorBuiltinComponent
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import java.io.File
import java.lang.reflect.Field
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * [CodeEditor] implementation for the IDE.
 *
 * @author Akash Yadav
 */
open class IDEEditor
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
    private val editorFeatures: EditorFeatures = EditorFeatures(),
) : CodeEditor(context, attrs, defStyleAttr, defStyleRes), IEditor by editorFeatures, ILspEditor {

  @Suppress("PropertyName") internal var _file: File? = null

  private var _actionsMenu: EditorActionsMenu? = null
  private var _signatureHelpWindow: SignatureHelpWindow? = null
  private var _diagnosticWindow: DiagnosticWindow? = null
  private var _hoverWindow: HoverWindow? = null
  internal var imeBottomInset: Int = 0
    private set
  private var imePopupRefreshPosted = false
  private var fileVersion = 0
  // Event-provided stamp, kept locally so editor:impl remains independent from core:projects.
  private var documentRevision = -1L
  internal var isModified = false

  private val selectionChangeHandler = Handler(Looper.getMainLooper())
  private var selectionChangeRunner: Runnable? = Runnable {
    val languageClient = languageClient ?: return@Runnable
    val cursor = this.cursor ?: return@Runnable

    if (cursor.isSelected || _signatureHelpWindow?.isShowing == true) {
      return@Runnable
    }

    diagnosticWindow.showDiagnostic(
        languageClient.getDiagnosticAt(file, cursor.leftLine, cursor.leftColumn)
    )

    // Hover tooltip UI is handled by HoverTooltipManager.
    // Do not issue a duplicate hover request here or render hover content via Flashbar.
  }

  /**
   * The [CoroutineScope] for the editor.
   *
   * All the jobs in this scope are cancelled when the editor is released.
   */
  val editorScope = CoroutineScope(Dispatchers.Default + CoroutineName("IDEEditor"))

  protected val eventDispatcher = EditorEventDispatcher()

  private var setupTsLanguageJob: Job? = null
  private var sigHelpCancelChecker: ICancelChecker? = null

  var languageServer: ILanguageServer? = null
    private set

  var languageClient: ILanguageClient? = null
    private set

  /** Whether the cursor position change animation is enabled for the editor. */
  var isEnsurePosAnimEnabled = true

  /** The text searcher for the editor. */
  lateinit var searcher: IDEEditorSearcher

  /** The signature help window for the editor. */
  val signatureHelpWindow: SignatureHelpWindow
    get() {
      return _signatureHelpWindow ?: SignatureHelpWindow(this).also { _signatureHelpWindow = it }
    }

  /** The diagnostic window for the editor. */
  val diagnosticWindow: DiagnosticWindow
    get() {
      return _diagnosticWindow ?: DiagnosticWindow(this).also { _diagnosticWindow = it }
    }

  /** The language-server hover window for the editor. */
  val hoverWindow: HoverWindow
    get() {
      return _hoverWindow ?: HoverWindow(this).also { _hoverWindow = it }
    }
  companion object {

    private const val SELECTION_CHANGE_DELAY = 500L
    private const val LARGE_DOCUMENT_EVENT_TEXT_THRESHOLD = 256 * 1024

    internal val log = LoggerFactory.getLogger(IDEEditor::class.java)
    private val diagnosticsRegionsField: Field? = runCatching {
      DiagnosticsContainer::class.java.getDeclaredField("regions").apply { isAccessible = true }
    }.getOrNull()

    /** Create input type flags for the editor. */
    fun createInputTypeFlags(): Int {
      if (EditorPreferences.keyboardSuggestions) {
        return EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
      } else {
        var flags =
            EditorInfo.TYPE_CLASS_TEXT or
                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (EditorPreferences.visiblePasswordFlag) {
          flags = flags or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        return flags
      }
    }
    
  }

  init {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      updateImeBottomInset(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
      insets
    }
    ViewCompat.setWindowInsetsAnimationCallback(
        this,
        object : WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        ) {
          override fun onProgress(
              insets: WindowInsetsCompat,
              runningAnimations: MutableList<WindowInsetsAnimationCompat>,
          ): WindowInsetsCompat {
            updateImeBottomInset(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            return insets
          }
        },
    )

    ViewCompat.requestApplyInsets(this)

    run {
      editorFeatures.editor = this
      eventDispatcher.editor = this
      eventDispatcher.init(editorScope)
      initEditor()
    }
  }

  internal fun updateImeBottomInset(bottom: Int) {
    val normalizedBottom = bottom.coerceAtLeast(0)
    if (imeBottomInset == normalizedBottom) {
      return
    }
    imeBottomInset = normalizedBottom
    if (!isAttachedToWindow || imePopupRefreshPosted) {
      return
    }
    imePopupRefreshPosted = true

    post {
      imePopupRefreshPosted = false
      if (isReleased) {
        return@post
      }
      if (_hoverWindow?.isShowing == true) {
        _hoverWindow?.displayWindow()
      }
      if (_signatureHelpWindow?.isShowing == true) {
        _signatureHelpWindow?.displayWindow()
      }
      if (_diagnosticWindow?.isShowing == true) {
        _diagnosticWindow?.displayWindow()
      }
      if (_actionsMenu?.isShowing == true) {
        _actionsMenu?.displayWindow()
      }
      runCatching {
        val completion = getComponent(EditorAutoCompletion::class.java)
        if (completion.isShowing) {
          (completion as? EditorCompletionWindow)?.updateCompletionWindowPosition(false)
        }
      }
    }
  }

  /** Set the file for this editor. */
  fun setFile(file: File?) {
    if (isReleased) {
      return
    }

    this._file = file
    file?.also { dispatchDocumentOpenEvent() }
  }

  override fun setLanguageServer(server: ILanguageServer?) {
    if (isReleased) {
      return
    }
    this.languageServer = server
    server?.also {
      this.languageClient = it.client
      snippetController.apply {
        fileVariableResolver = FileVariableResolver(this@IDEEditor)
        workspaceVariableResolver = WorkspaceVariableResolver()
      }
    }
  }

  override fun setLanguageClient(client: ILanguageClient?) {
    if (isReleased) {
      return
    }
    this.languageClient = client
  }

  override fun executeCommand(command: Command?) {
    if (isReleased) {
      return
    }
    if (command == null) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn("Cannot execute command in editor. Command is null.")
      }
      return
    }

    if (IdeLogConfig.shouldLogInfo()) {
      log.info(String.format("Executing command '%s' for completion item.", command.title))
    }
    when (command.command) {
      Command.TRIGGER_COMPLETION -> {
        val completion = getComponent(EditorAutoCompletion::class.java)
        completion.requireCompletion()
      }

      Command.TRIGGER_PARAMETER_HINTS -> signatureHelp()
      Command.FORMAT_CODE -> formatCodeAsync()
    }
  }

  override fun signatureHelp() {
    if (isReleased) {
      return
    }
    val languageServer = this.languageServer ?: return
    val file = this.file ?: return

    this.languageClient ?: return

    sigHelpCancelChecker?.also { it.cancel() }

    val cancelChecker = JobCancelChecker().also { this.sigHelpCancelChecker = it }
    val requestFile = file.toPath()
    val requestVersion = fileVersion
    val requestRevision = documentRevision
    val requestPosition = cursorLSPPosition
    val requestContent = text.toString()

    editorScope
        .launch(Dispatchers.Default) {
          cancelChecker.job = coroutineContext[Job]

          val help =
              safeGet("signature help request") {
                val params =
                    SignatureHelpParams(
                        file = requestFile,
                        position = requestPosition,
                        content = requestContent,
                        cancelChecker = cancelChecker,
                    )
                        .apply {
                          documentVersion = requestVersion
                          documentRevision = requestRevision
                        }
                languageServer.signatureHelp(params)
              }

          withContext(Dispatchers.Main) {
            if (
                !isReleased &&
                    this@IDEEditor.file?.toPath() == requestFile &&
                    fileVersion == requestVersion &&
                    documentRevision == requestRevision
            ) {
              showSignatureHelp(help)
            }
          }
        }
        .logError("signature help request")
  }

  override fun showSignatureHelp(help: SignatureHelp?) {
    if (isReleased) {
      return
    }
    signatureHelpWindow.setupAndDisplay(help)
  }

  override fun findDefinition() {
    if (isReleased) {
      return
    }
    val languageServer = this.languageServer ?: return
    val file = file ?: return

    launchCancellableAsyncWithProgress(string.msg_finding_definition) { _, cancelChecker ->
          val result =
              safeGet("definition request") {
                val params = DefinitionParams(file.toPath(), cursorLSPPosition, cancelChecker)
                languageServer.findDefinition(params)
              }

          onFindDefinitionResult(result)
        }
        ?.logError("definition request")
  }

  override fun findReferences() {
    if (isReleased) {
      return
    }
    val languageServer = this.languageServer ?: return
    val file = file ?: return

    launchCancellableAsyncWithProgress(string.msg_finding_references) { _, cancelChecker ->
          val result =
              safeGet("references request") {
                val params = ReferenceParams(file.toPath(), cursorLSPPosition, true, cancelChecker)
                languageServer.findReferences(params)
              }

          onFindReferencesResult(result)
        }
        ?.logError("references request")
  }

  override fun expandSelection() {
    if (isReleased) {
      return
    }
    val languageServer = this.languageServer ?: return
    val file = file ?: return

    launchCancellableAsyncWithProgress(string.please_wait) { _, _ ->
          val initialRange = cursorLSPRange
          val result =
              safeGet("expand selection request") {
                val params = ExpandSelectionParams(file.toPath(), initialRange)
                languageServer.expandSelection(params)
              } ?: initialRange

          withContext(Dispatchers.Main) { setSelection(result) }
        }
        ?.logError("expand selection request")
  }

  internal fun canShowHoverWindow(): Boolean {
    return _diagnosticWindow?.isShowing != true &&
        _signatureHelpWindow?.isShowing != true &&
        _actionsMenu?.isShowing != true
  }

  internal fun dismissHoverWindow() {
    _hoverWindow?.dismissHover()
  }

  override fun ensureWindowsDismissed() {
    if (_diagnosticWindow?.isShowing == true) {
      _diagnosticWindow?.dismiss()
    }

    if (_signatureHelpWindow?.isShowing == true) {
      _signatureHelpWindow?.dismiss()
    }

    if (_hoverWindow?.isShowing == true) {
      _hoverWindow?.dismiss()
    }

    if (_actionsMenu?.isShowing == true) {
      _actionsMenu?.dismiss()
    }
  }

  // not overridable!
  final override fun <T : EditorBuiltinComponent?> replaceComponent(
      clazz: Class<T>,
      replacement: T & Any,
  ) {
    super.replaceComponent(clazz, replacement)
  }

  // not overridable
  final override fun <T : EditorBuiltinComponent?> getComponent(clazz: Class<T>): T & Any {
    return super.getComponent(clazz)
  }

  override fun release() {
    imePopupRefreshPosted = false
    ensureWindowsDismissed()
    cleanupHoverTooltips()

    if (isReleased) {
      return
    }

    super.release()

    snippetController.apply {
      (fileVariableResolver as? AbstractSnippetVariableResolver?)?.close()
      (workspaceVariableResolver as? AbstractSnippetVariableResolver?)?.close()

      fileVariableResolver = null
      workspaceVariableResolver = null
    }

    _actionsMenu?.destroy()

    _actionsMenu = null
    _signatureHelpWindow = null
    _diagnosticWindow = null
    _hoverWindow = null

    languageServer = null
    languageClient = null

    _file = null
    fileVersion = 0
    documentRevision = -1L
    markUnmodified()

    editorFeatures.editor = null
    eventDispatcher.editor = null

    eventDispatcher.destroy()

    selectionChangeRunner?.also { selectionChangeHandler.removeCallbacks(it) }
    selectionChangeRunner = null

    setupTsLanguageJob?.cancel("Editor is releasing resources.")
    sigHelpCancelChecker?.cancel()
    sigHelpCancelChecker = null

    if (editorScope.isActive) {
      editorScope.cancelIfActive("Editor is releasing resources.")
    }
    
    try {
      if (EventBus.getDefault().isRegistered(this)) {
        EventBus.getDefault().unregister(this)
      }
    } catch (e: Exception) {
      log.debug("EventBus unregister failed", e)
    }
  }

  override fun getSearcher(): EditorSearcher {
    return this.searcher
  }

  override fun getExtraArguments(): Bundle {
    return super.getExtraArguments().apply {
      putString(IEditor.KEY_FILE, file?.absolutePath)
      putInt(IEditor.KEY_DOCUMENT_VERSION, fileVersion)
      putLong(IEditor.KEY_DOCUMENT_REVISION, documentRevision)
    }
  }

  override fun ensurePositionVisible(line: Int, column: Int, noAnimation: Boolean) {
    super.ensurePositionVisible(line, column, !isEnsurePosAnimEnabled || noAnimation)
  }

  override fun getTabWidth(): Int {
    return EditorPreferences.tabSize
  }

  override fun beginSearchMode() {
    throw UnsupportedOperationException(
        "Search ActionMode is not supported. Use CodeEditorView.beginSearch() instead."
    )
  }

  override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    if (!gainFocus) {
      ensureWindowsDismissed()
    }
  }

  /** Analyze the opened file and publish the diagnostics result. */
  open fun analyze() {
    if (isReleased) {
      return
    }
    if (editorLanguage !is IDELanguage) {
      return
    }

    val languageServer = languageServer ?: return
    val file = file ?: return

    editorScope
        .launch {
          val result = safeGet("LSP file analysis") { languageServer.analyze(file.toPath()) }
          languageClient?.publishDiagnostics(result)
        }
        .logError("LSP file analysis")
  }

  /** Mark this editor as NOT modified. */
  open fun markUnmodified() {
    this.isModified = false
  }

  /** Mark this editor as modified. */
  open fun markModified() {
    this.isModified = true
  }

  /** Notify the language server that the file in this editor is about to be closed. */
  open fun notifyClose() {
    if (isReleased) {
      return
    }
    file
        ?: run {
          if (IdeLogConfig.shouldLogInfo()) {
            log.info("Cannot notify language server. File is null.")
          }
          return
        }

    dispatchDocumentCloseEvent()

    _actionsMenu?.unsubscribeEvents()
    selectionChangeRunner?.also { selectionChangeHandler.removeCallbacks(it) }

    selectionChangeRunner = null

    ensureWindowsDismissed()
  }

  /** Called when this editor is selected and visible to the user. */
  open fun onEditorSelected() {
    if (isReleased) {
      return
    }

    file ?: return
    dispatchDocumentSelectedEvent()
  }

  /** Dispatches the [DocumentSaveEvent] for this editor. */
  open fun dispatchDocumentSaveEvent(buildWillFollow: Boolean = false) {
    markUnmodified()
    if (isReleased) {
      return
    }
    if (file == null) {
      return
    }
    eventDispatcher.dispatch(DocumentSaveEvent(file!!.toPath(), buildWillFollow))
  }

  /**
   * Called when the color scheme has been invalidated. This usually happens when the user reloads
   * the color schemes.
   */
  @Suppress("unused")
  @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
  open fun onColorSchemeInvalidated(event: ColorSchemeInvalidatedEvent?) {
    val file = file ?: return
    setupLanguage(file)
  }

  /**
   * Setup the editor language for the given [file].
   *
   * This applies a proper [Language] and the color scheme to the editor.
   */
  open fun setupLanguage(file: File?) {
    if (isReleased) {
      return
    }
    if (file == null) {
      return
    }

    createLanguage(file) { language ->
      val extension = file.extension
      if (language is TreeSitterLanguage) {
        IDEColorSchemeProvider.readSchemeAsync(
            context = context,
            coroutineScope = editorScope,
            type = extension,
        ) { scheme ->
          applyTreeSitterLang(language, extension, scheme)
        }
      } else {
        setEditorLanguage(language)
      }
    }
  }

  /**
   * Applies the given [TreeSitterLanguage] and the [color scheme][scheme] for the given
   * [file type][type].
   */
  open fun applyTreeSitterLang(
      language: TreeSitterLanguage,
      type: String,
      scheme: SchemeAndroidIDE?,
  ) {
    applyTreeSitterLangInternal(language, type, scheme)
  }

  private fun applyTreeSitterLangInternal(
      language: TreeSitterLanguage,
      type: String,
      scheme: SchemeAndroidIDE?,
  ) {
    if (isReleased) {
      return
    }
    var finalScheme =
        if (scheme != null) {
          scheme
        } else {
          log.error("Failed to read current color scheme")
          SchemeAndroidIDE.newInstance(context)
        }

    if (finalScheme is IDEColorScheme) {

      language.setupWith(finalScheme)

      if (finalScheme.getLanguageScheme(type) == null) {
        log.warn("Color scheme does not support file type '{}'", type)
        finalScheme = SchemeAndroidIDE.newInstance(context)
      }
    }

    if (finalScheme is DynamicColorScheme) {
      finalScheme.apply(context)
    }

    colorScheme = finalScheme!!
    setEditorLanguage(language)
  }

  private inline fun createLanguage(file: File, crossinline callback: (Language?) -> Unit) {

    // 1 -> If the given File object does not represent a file, return emtpy language
    if (!file.isFile) {
      return callback(EmptyLanguage())
    }

    // 2 -> In case a TreeSitterLanguage has been registered for this file type,
    //      Initialize the TreeSitterLanguage asynchronously and then invoke the callback
    if (TreeSitterLanguageProvider.hasTsLanguage(file)) {

      // lazily create TS languages as they need to read files from assets
      setupTsLanguageJob =
          editorScope
              .launch { callback(TreeSitterLanguageProvider.forFile(file, context)) }
              .also { job ->
                job.invokeOnCompletion { err ->
                  if (err != null) {
                    log.error("Failed to setup tree sitter language for file: {}", file, err)
                  }

                  setupTsLanguageJob = null
                }
              }

      return
    }

    // 3 -> Check if we have ANTLR4 lexer-based languages for this file,
    //      return the language if we do, otherwise return an empty language
    val lang =
        when (FileUtils.getFileExtension(file)) {
          "gradle" -> GroovyLanguage()
          // "c", "h", "cc", "cpp", "cxx" -> CppLanguage()
          else -> EmptyLanguage()
        }

    callback(lang)
  }

  private inline fun <R> measureEditorInitStage(stage: String, action: () -> R): R {
    return action()
  }

  /** Initialize the editor. */
  protected open fun initEditor() {

    measureEditorInitStage("lineNumberMargin") {
      lineNumberMarginLeft = SizeUtils.dp2px(2f).toFloat()
    }

    measureEditorInitStage("markUnmodified") {
      markUnmodified()
    }

    measureEditorInitStage("createSearcher") {
      searcher = IDEEditorSearcher(this)
    }
    measureEditorInitStage("initHoverTooltips") {
      initHoverTooltips()
    }
    measureEditorInitStage("createColorScheme") {
      colorScheme = SchemeAndroidIDE.newInstance(context)
    }
    measureEditorInitStage("createInputTypeFlags") {
      inputType = createInputTypeFlags()
    }
    measureEditorInitStage("applyBackground") {
      val backgroundColor = getSystemBackgroundColor()
      setBackgroundColor(backgroundColor)
    }

    measureEditorInitStage("subscribeContentChange") {
      subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
        if (isReleased) {
          return@subscribeEvent
        }

        markModified()
        file ?: return@subscribeEvent

        // FileManager and completion requests must observe the same version as this editor edit.
        // Do not defer document-event construction to a coroutine: a later keystroke could then
        // pair this event's range/version with newer text and make every completion stale.
        dispatchDocumentChangeEvent(event)
        editorScope.launch { checkForSignatureHelp(event) }
      }
    }
measureEditorInitStage("subscribeSelectionChange") {
      subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
        if (isReleased) {
          return@subscribeEvent
        }

        if (_diagnosticWindow?.isShowing == true) {
          _diagnosticWindow?.dismiss()
        }

        selectionChangeRunner?.also {
          selectionChangeHandler.removeCallbacks(it)
          selectionChangeHandler.postDelayed(it, SELECTION_CHANGE_DELAY)
        }
      }
    }

    
    

    measureEditorInitStage("eventBusRegister") {
      EventBus.getDefault().register(this)
    }

    // These components are not required to render the first editor frame. Defer them to keep
    // layout inflation/open-file hot path responsive.
    postDelayed(
      {
        if (isReleased) {
          return@postDelayed
        }
        measureEditorInitStage("deferredActionsMenu") {
          if (_actionsMenu == null) {
            _actionsMenu = EditorActionsMenu(this).also { it.init() }
          }
        }
        measureEditorInitStage("deferredCompletionWindow") {
          val window = EditorCompletionWindow(this)
          window.setAdapter(CompletionListAdapter())
          replaceComponent(EditorAutoCompletion::class.java, window)
        }
        measureEditorInitStage("deferredDisableTextActionWindow") {
          getComponent(EditorTextActionWindow::class.java).isEnabled = false
        }
      },
      96,
    )
  }

  private fun isSystemInDarkMode(): Boolean {
      val nightModeFlags = context.resources.configuration.uiMode and 
              android.content.res.Configuration.UI_MODE_NIGHT_MASK
      return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
  }
  
  private fun getSystemBackgroundColor(): Int {
      val typedValue = android.util.TypedValue()
      val theme = context.theme
      
      // Try to get the background color from Material3 attributes
      return when {
          // Material 3 background
          theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true) -> {
              typedValue.data
          }
          // Fallback to Material 2 background
          theme.resolveAttribute(com.google.android.material.R.attr.backgroundColor, typedValue, true) -> {
              typedValue.data
          }
          // Android system background
          theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true) -> {
              typedValue.data
          }
          // Ultimate fallback
          else -> {
              if (isSystemInDarkMode()) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()
          }
      }
  }

  private inline fun launchCancellableAsyncWithProgress(
      @StringRes message: Int,
      crossinline action:
          suspend CoroutineScope.(flashbar: Flashbar, cancelChecker: ICancelChecker) -> Unit,
  ): Job? {
    if (isReleased) {
      return null
    }

    return editorScope.launchAsyncWithProgress(
        configureFlashbar = { builder, cancelChecker ->
          configureFlashbar(builder, message, cancelChecker)
        },
        action = action,
    )
  }

  protected open suspend fun onFindDefinitionResult(
      result: DefinitionResult?,
  ) =
      withContext(Dispatchers.Main) {
        if (isReleased) {
          return@withContext
        }

        val languageClient =
            languageClient
                ?: run {
                  log.error("No language client found to handle the definitions result")
                  return@withContext
                }

        if (result == null) {
          log.error("Invalid definitions result from language server (null)")
          flashError(string.msg_no_definition)
          return@withContext
        }

        val locations = result.locations
        if (locations.isEmpty()) {
          log.error("No definitions found")
          flashError(string.msg_no_definition)
          return@withContext
        }

        if (locations.size != 1) {
          languageClient.showLocations(locations)
          return@withContext
        }

        val (file1, range) = locations[0]
        if (DocumentUtils.isSameFile(file1, file!!.toPath())) {
          setSelection(range)
          return@withContext
        }

        languageClient.showDocument(ShowDocumentParams(file1, range))
      }

  protected open suspend fun onFindReferencesResult(result: ReferenceResult?) =
      withContext(Dispatchers.Main) {
        if (isReleased) {
          return@withContext
        }

        val languageClient =
            languageClient
                ?: run {
                  log.error("No language client found to handle the references result")
                  return@withContext
                }

        if (result == null) {
          log.error("Invalid references result from language server (null)")
          flashError(string.msg_no_references)
          return@withContext
        }

        val locations = result.locations
        if (locations.isEmpty()) {
          log.error("No references found")
          flashError(string.msg_no_references)
          return@withContext
        }

        if (result.locations.size == 1) {
          val (file, range) = result.locations[0]

          if (DocumentUtils.isSameFile(file, getFile()!!.toPath())) {
            setSelection(range)
            return@withContext
          }
        }

        languageClient.showReferences(result.locations, result.roles)
      }
  protected open fun dispatchDocumentOpenEvent() {
    if (isReleased) {
      return
    }

    val file = this.file ?: return
    fileVersion = 0

    // Large documents are already held by the editor widget. Forcing another eager String copy into
    // the open event can briefly double memory usage and make big-file opening noticeably worse.
    // Keep the open event payload minimal for such files and let downstream readers obtain content
    // from the active document cache instead of the event body.
    val openText = if (text.length > LARGE_DOCUMENT_EVENT_TEXT_THRESHOLD) "" else text.toString()
    val openEvent = DocumentOpenEvent(file.toPath(), openText, fileVersion)
    documentRevision = openEvent.revision

    eventDispatcher.dispatch(openEvent)
  }

  private fun shouldTraceBattleActionConfig(path: Path): Boolean {
    return path.toString().contains("BattleActionConfig.java")
  }
  private fun safeTraceLine(oneBasedLine: Int): String {
    val zeroBasedLine = oneBasedLine - 1
    return if (zeroBasedLine < 0 || zeroBasedLine >= text.lineCount) {
      "<out-of-range>"
    } else {
      text.getLine(zeroBasedLine).toString().replace("\n", "\\n").replace("\r", "\\r")
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun diagnosticsRegionSnapshot(diagnostics: DiagnosticsContainer?): List<Any> {
    if (diagnostics == null) {
      return emptyList()
    }
    val field = diagnosticsRegionsField ?: return emptyList()
    return runCatching { field.get(diagnostics) as? List<Any> ?: emptyList() }.getOrElse { emptyList() }
  }

  private fun filterDiagnosticsForRendering(diagnostics: DiagnosticsContainer?): DiagnosticsContainer? {
    if (diagnostics == null) {
      return null
    }
    val regions = diagnosticsRegionSnapshot(diagnostics)
    if (regions.isEmpty()) {
      return diagnostics
    }
    val strongestSeverityByRange = mutableMapOf<Pair<Int, Int>, Short>()
    for (region in regions) {
      runCatching {
        val clazz = region.javaClass
        val startIndex = clazz.getDeclaredField("startIndex").apply { isAccessible = true }.getInt(region)
        val endIndex = clazz.getDeclaredField("endIndex").apply { isAccessible = true }.getInt(region)
        val severity = clazz.getDeclaredField("severity").apply { isAccessible = true }.getShort(region)
        if (severity.toInt() != 0) {
          val range = startIndex to endIndex
          val strongest = strongestSeverityByRange[range]
          if (strongest == null || severity > strongest) {
            strongestSeverityByRange[range] = severity
          }
        }
      }
    }

    val filtered = DiagnosticsContainer()
    var changed = false
    for (region in regions) {
      val copied = runCatching {
        val clazz = region.javaClass
        val startIndex = clazz.getDeclaredField("startIndex").apply { isAccessible = true }.getInt(region)
        val endIndex = clazz.getDeclaredField("endIndex").apply { isAccessible = true }.getInt(region)
        val severity = clazz.getDeclaredField("severity").apply { isAccessible = true }.getShort(region)
        if (severity.toInt() == 0 ||
            strongestSeverityByRange[startIndex to endIndex]?.let { severity < it } == true) {
          changed = true
          null
        } else {
          val id = clazz.getDeclaredField("id").apply { isAccessible = true }.getLong(region)
          val detail = clazz.getDeclaredField("detail").apply { isAccessible = true }.get(region) as? DiagnosticDetail
          DiagnosticRegion(startIndex, endIndex, severity, id, detail)
        }
      }.getOrNull()
      if (copied != null) {
        filtered.addDiagnostic(copied)
      }
    }
    // Sora's diagnostic indicator renderer stops iterating when a diagnostic maps to colorId=0,
    // and draws multiple equal-range indicators on top of each other. Remove NONE regions and keep
    // only the strongest severity for an identical range before handing diagnostics to rendering.
    return if (changed) filtered else diagnostics
  }

  override fun onCreateRenderer(): EditorRenderer {
    return EditorRenderer(this)
  }

  override fun setDiagnostics(diagnostics: DiagnosticsContainer?) {
    super.setDiagnostics(filterDiagnosticsForRendering(diagnostics))
  }

  protected open fun dispatchDocumentChangeEvent(event: ContentChangeEvent) {
    if (isReleased) {
      return
    }

    val file = file?.toPath() ?: return
    val shouldTraceBattleAction = shouldTraceBattleActionConfig(file)
    var type = ChangeType.INSERT
    if (event.action == ContentChangeEvent.ACTION_DELETE) {
      type = ChangeType.DELETE
    } else if (event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
      type = ChangeType.NEW_TEXT
    }
    var changeDelta = if (type == ChangeType.NEW_TEXT) 0 else event.changedText.length
    if (type == ChangeType.DELETE) {
      changeDelta = -changeDelta
    }
    val start = event.changeStart
    val end = event.changeEnd
    val changeRange =
        Range(
            Position(start.line, start.column, start.index),
            Position(end.line, end.column, end.index),
        )
    val changedText = event.changedText.toString()
    val fullTextProvider =
        if (type == ChangeType.NEW_TEXT && text.length > LARGE_DOCUMENT_EVENT_TEXT_THRESHOLD) {
          // NEW_TEXT usually means a whole-document replacement. For large buffers, eagerly
          // materializing another full snapshot here is expensive. Defer it until a consumer
          // actually requests the full text.
          LazyDocumentTextProvider { text.toString() }
        } else {
          null
        }
    val newText = if (fullTextProvider == null) text.toString() else null
    val nextVersion = ++fileVersion
    val changeEvent =
        DocumentChangeEvent(
            file,
            changedText,
            newText,
            nextVersion,
            type,
            changeDelta,
            changeRange,
            fullTextProvider,
        )
    documentRevision = changeEvent.revision
    eventDispatcher.dispatch(changeEvent)
  }


  protected open fun dispatchDocumentSelectedEvent() {
    if (isReleased) {
      return
    }
    val file = file ?: return
    eventDispatcher.dispatch(DocumentSelectedEvent(file.toPath()))
  }

  protected open fun dispatchDocumentCloseEvent() {
    if (isReleased) {
      return
    }
    val file = file ?: return

    eventDispatcher.dispatch(DocumentCloseEvent(file.toPath(), cursorLSPRange))
  }

  /**
   * Checks if the content change event should trigger signature help. Signature help trigger
   * characters are :
   * * `'('` (parentheses)
   * * `','` (comma)
   *
   * @param event The content change event.
   */
  private fun checkForSignatureHelp(event: ContentChangeEvent) {
    if (isReleased) {
      return
    }
    if (languageServer == null) {
      return
    }
    val changeLength = event.changedText.length
    if (event.action != ContentChangeEvent.ACTION_INSERT || changeLength < 1 || changeLength > 2) {
      // change length will be 1 if ',' is inserted
      // changeLength will be 2 as '(' and ')' are inserted at the same time
      return
    }

    val ch = event.changedText[0]
    if (ch == '(' || ch == ',') {
      signatureHelp()
    }
  }

  private fun configureFlashbar(
      builder: Flashbar.Builder,
      @StringRes message: Int,
      cancelChecker: ICancelChecker,
  ) {
    builder.message(message).primaryActionText(android.R.string.cancel).primaryActionTapListener {
        bar: Flashbar ->
      cancelChecker.cancel()
      bar.dismiss()
    }
  }

  private inline fun <T> safeGet(name: String, action: () -> T): T? {
    return try {
      action()
    } catch (err: Throwable) {
      logError(err, name)
      null
    }
  }

  private fun Job.logError(action: String): Job = apply {
    invokeOnCompletion { err -> logError(err, action) }
  }

  private fun logError(err: Throwable?, action: String) {
    err ?: return
    if (CancelChecker.isCancelled(err)) {
      if (IdeLogConfig.shouldLogWarn()) {
        log.warn("{} has been cancelled", action)
      }
    } else {
      log.error("{} failed", action)
    }
  }

  override fun setSelectionAround(line: Int, column: Int) {
    editorFeatures.setSelectionAround(line, column)
  }
}
