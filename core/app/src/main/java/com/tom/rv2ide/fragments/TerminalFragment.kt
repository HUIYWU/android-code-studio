package com.tom.rv2ide.fragments
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences

import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.tabs.TabLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.BaseEditorActivity
import com.tom.rv2ide.fragments.terminal.*
import com.termux.app.TermuxService
import com.termux.shared.logger.Logger
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalFragment : Fragment() {
    
    private var terminalView: TerminalView? = null
    private var termuxService: TermuxService? = null
    private var navigationRail: NavigationRailView? = null
    private var mainContent: LinearLayout? = null
    private var menuButton: ImageView? = null
    private var terminalContent: View? = null
    private var settingsContent: View? = null
    private var emptyStateContent: View? = null
    private var sessionTabs: TabLayout? = null
    // The terminal content owns the full-expanded bottom slot and follows the IME by padding.
    private var terminalImeCallbackInstalled = false
    private var terminalBasePaddingBottom = 0
    
    private var serviceIsBound = false
    private lateinit var prefs: SharedPreferences
    
    // Handlers
    private lateinit var sessionManager: SessionManager
    private lateinit var extraKeysHandler: ExtraKeysHandler
    private lateinit var navigationHandler: NavigationHandler
    private lateinit var settingsHandler: TerminalSettingsHandler
    
    private companion object {
        const val PREF_NAME = "TerminalPreferences"
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName?, service: IBinder?) {
            termuxService = (service as TermuxService.LocalBinder).service
            serviceIsBound = true
        }
        
        override fun onServiceDisconnected(componentName: ComponentName?) {
            termuxService = null
            serviceIsBound = false
        }
    }
    
    private val terminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float) = scale.coerceIn(0.5f, 2.0f)
        override fun onSingleTapUp(e: MotionEvent?) {
            if (sessionManager.currentSession != null) {
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        // Keep long-press text selection focused on Copy/Paste inside the compact editor sheet terminal.
        override fun shouldShowTextSelectionMore() = false
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = extraKeysHandler.isCtrlPressed()

        override fun readAltKey() = extraKeysHandler.isAltPressed()
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {
            terminalView?.setTerminalCursorBlinkerRate(1000)
            terminalView?.setTerminalCursorBlinkerState(true, true)
        }
        override fun logError(tag: String?, message: String?) = Logger.logError(tag, message)
        override fun logWarn(tag: String?, message: String?) = Logger.logWarn(tag, message)
        override fun logInfo(tag: String?, message: String?) = Logger.logInfo(tag, message)
        override fun logDebug(tag: String?, message: String?) = Logger.logDebug(tag, message)
        override fun logVerbose(tag: String?, message: String?) = Logger.logVerbose(tag, message)
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = 
            Logger.logStackTraceWithMessage(tag, message, e)
        override fun logStackTrace(tag: String?, e: Exception?) = Logger.logStackTrace(tag, e)
    }
    
    private val terminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }
        
        override fun onTitleChanged(changedSession: TerminalSession) {
            sessionManager.updateTabTitle(changedSession)
        }
        
        override fun onSessionFinished(finishedSession: TerminalSession) {
            sessionManager.onSessionFinished(finishedSession, termuxService)
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return
            val item = clip.getItemAt(0) ?: return
            val text = item.coerceToText(requireContext())?.toString() ?: return
            if (text.isNotEmpty()) {
                terminalView?.mEmulator?.paste(text)
            }
        }
        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle() = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) = Logger.logError(tag, message)
        override fun logWarn(tag: String?, message: String?) = Logger.logWarn(tag, message)
        override fun logInfo(tag: String?, message: String?) = Logger.logInfo(tag, message)
        override fun logDebug(tag: String?, message: String?) = Logger.logDebug(tag, message)
        override fun logVerbose(tag: String?, message: String?) = Logger.logVerbose(tag, message)
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = 
            Logger.logStackTraceWithMessage(tag, message, e)
        override fun logStackTrace(tag: String?, e: Exception?) = Logger.logStackTrace(tag, e)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_terminal, container, false)
        
        prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        // Initialize views
        navigationRail = rootView.findViewById(R.id.navigation_rail)
        mainContent = rootView.findViewById(R.id.main_content)
        menuButton = rootView.findViewById(R.id.menu_button)
        terminalContent = rootView.findViewById(R.id.terminal_content)
        settingsContent = rootView.findViewById(R.id.settings_content)
        emptyStateContent = rootView.findViewById(R.id.empty_state_content)
        terminalView = rootView.findViewById(R.id.terminal_view)
        sessionTabs = rootView.findViewById(R.id.session_tabs)
        installImeContentSlot(rootView)
        
        // Initialize handlers
        sessionManager = SessionManager(
            fragment = this,
            terminalView = terminalView,
            sessionTabs = sessionTabs,
            terminalSessionClient = terminalSessionClient,
            onSessionChange = { hasSession ->
                if (hasSession) showTerminal() else showEmptyState()
            }
        )
        
        extraKeysHandler = ExtraKeysHandler(rootView, terminalView)
        
        navigationHandler = NavigationHandler(
            context = requireContext(),
            navigationRail = navigationRail,
            mainContent = mainContent,
            menuButton = menuButton,
            onMenuItemSelected = { itemId -> handleNavigationItemSelected(itemId) }
        )
        
        settingsHandler = TerminalSettingsHandler(
            context = requireContext(),
            prefs = prefs,
            terminalView = terminalView,
            onExtraKeysVisibilityChanged = { visible ->
                extraKeysHandler.setVisibility(visible)
            }
        )
        
        // Setup terminal view
        val savedTextSize = settingsHandler.getTextSize()
        terminalView?.apply {
            setTerminalViewClient(terminalViewClient)
            setTextSize(savedTextSize)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        
        // Setup all handlers
        extraKeysHandler.setup()
        sessionManager.setup()
        navigationHandler.setup()
        settingsHandler.setupSettings(rootView, savedTextSize)
        
        // Set initial extra keys visibility
        extraKeysHandler.setVisibility(settingsHandler.getExtraKeysVisibility())
        
        // Sync current session between handlers
        extraKeysHandler.currentSession = sessionManager.currentSession
        
        rootView.findViewById<MaterialButton>(R.id.create_terminal_button)?.setOnClickListener {
            bindTermuxServiceAndCreateSession()
        }
        
        return rootView
    }
    
    // Apply the IME inset to the terminal content container so the weighted terminal view and
    // the extra keys bar move together.
    private fun installImeContentSlot(rootView: View) {
        if (terminalImeCallbackInstalled) return
        val content = rootView.findViewById<View>(R.id.terminal_content) ?: return
        terminalBasePaddingBottom = content.paddingBottom
        terminalImeCallbackInstalled = true
        // The sidebar owns the IME session while its input is focused; the terminal slot must not
        // consume the IME inset in that period, otherwise the terminal content gets pushed up.
        val isRoutedToSidebar: () -> Boolean = {
            (rootView.context as? BaseEditorActivity)?.content?.bottomSheet?.isImeRoutedToSidebar() == true
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            if (isRoutedToSidebar() || terminalContent?.visibility != View.VISIBLE) {
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    terminalBasePaddingBottom,
                )
            } else {
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                val imeBottom = if (ime > 0) (ime - bars).coerceAtLeast(0) else 0
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    terminalBasePaddingBottom + imeBottom,
                )
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            content,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                var startedWhileSidebar = false
                // Whether the terminal content is already pushed up when this animation starts.
                // If so, the animation belongs to a session that began on the terminal and the
                // terminal must follow it smoothly even if the route was transferred to the
                // sidebar in the meantime.
                var pushedByIme = false

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    startedWhileSidebar = isRoutedToSidebar()
                    pushedByIme = content.paddingBottom > terminalBasePaddingBottom
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    if (startedWhileSidebar || isRoutedToSidebar()) {
                        // A sidebar-owned session must not move the terminal unless this session
                        // started on the terminal (content already pushed up) and now returns.
                        if (!pushedByIme) return insets
                    }
                    if (terminalContent?.visibility != View.VISIBLE) {
                        return insets
                    }
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                    val imeBottom = if (ime > 0) (ime - bars).coerceAtLeast(0) else 0
                    content.setPadding(
                        content.paddingLeft,
                        content.paddingTop,
                        content.paddingRight,
                        terminalBasePaddingBottom + imeBottom,
                    )
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    startedWhileSidebar = false
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) == 0) return
                    // The whole IME animation may be bypassed while the sidebar owns the session,
                    // so the resting state is only reachable here; sync the padding to the final
                    // insets instead of waiting for another onApplyWindowInsets dispatch after
                    // the exit animation ends.
                    val rootInsets =
                        content.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
                    val imeBottom =
                        rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    val bars =
                        rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
                    val offset = if (imeBottom > 0) (imeBottom - bars).coerceAtLeast(0) else 0
                    val paddingBottom =
                        if (isRoutedToSidebar() || terminalContent?.visibility != View.VISIBLE) {
                            terminalBasePaddingBottom
                        } else {
                            terminalBasePaddingBottom + offset
                        }
                    content.setPadding(
                        content.paddingLeft,
                        content.paddingTop,
                        content.paddingRight,
                        paddingBottom,
                    )
                }
            }
        )
    }

    private fun handleNavigationItemSelected(itemId: Int): Boolean {
        return when (itemId) {
            R.id.nav_toggle_rail -> {
                if (terminalContent?.visibility == View.VISIBLE) {
                    navigationHandler.hideNavigationRail()
                }
                false
            }
            R.id.nav_terminal -> {
                if (sessionManager.hasActiveSessions()) showTerminal() else showEmptyState()
                true
            }
            R.id.nav_new_session -> {
                createNewSession()
                navigationRail?.selectedItemId = R.id.nav_terminal
                false
            }
            R.id.nav_settings -> {
                showSettings()
                true
            }
            else -> false
        }
    }
    
    private fun showEmptyState() {
        if (!isAdded) return
        emptyStateContent?.visibility = View.VISIBLE
        terminalContent?.visibility = View.GONE
        settingsContent?.visibility = View.GONE
    }
    
    private fun showTerminal() {
        if (!isAdded) return
        terminalContent?.visibility = View.VISIBLE
        emptyStateContent?.visibility = View.GONE
        settingsContent?.visibility = View.GONE
    }
    
    private fun showSettings() {
        if (!isAdded) return
        terminalContent?.visibility = View.GONE
        emptyStateContent?.visibility = View.GONE
        settingsContent?.visibility = View.VISIBLE
    }
    
    override fun onResume() {
        super.onResume()
        terminalView?.setTerminalCursorBlinkerState(true, true)
    }
    
    override fun onPause() {
        super.onPause()
        terminalView?.setTerminalCursorBlinkerState(false, false)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        if (serviceIsBound) {
            try {
                context?.unbindService(serviceConnection)
                serviceIsBound = false
            } catch (e: Exception) {
                Logger.logError("TerminalFragment", "Error unbinding service: ${e.message}")
            }
        }
        terminalView = null
        navigationRail = null
        mainContent = null
        menuButton = null
        terminalContent = null
        settingsContent = null
        emptyStateContent = null
        sessionTabs = null
        terminalImeCallbackInstalled = false
        terminalBasePaddingBottom = 0
    }
    
    private fun bindTermuxServiceAndCreateSession() {
        if (!serviceIsBound) {
            val intent = Intent(requireContext(), TermuxService::class.java)
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            view?.postDelayed({ createNewSession() }, 100)
        } else {
            createNewSession()
        }
    }
    
    private fun createNewSession() {
        sessionManager.createNewSession(termuxService)
        // Sync current session to extra keys handler
        extraKeysHandler.currentSession = sessionManager.currentSession
    }
}