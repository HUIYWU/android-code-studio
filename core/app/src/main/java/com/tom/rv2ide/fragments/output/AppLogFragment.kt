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

package com.tom.rv2ide.fragments.output

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.blankj.utilcode.util.ThreadUtils
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.FragmentLogViewerBinding
import com.tom.rv2ide.editor.language.treesitter.LogLanguage
import com.tom.rv2ide.editor.language.treesitter.TreeSitterLanguageProvider
import com.tom.rv2ide.editor.schemes.IDEColorScheme
import com.tom.rv2ide.editor.schemes.IDEColorSchemeProvider
import com.tom.rv2ide.fragments.EmptyStateFragment
import com.tom.rv2ide.models.LogLine
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.services.log.ConnectionObserverParams
import com.tom.rv2ide.services.log.LogReceiverImpl
import com.tom.rv2ide.services.log.LogReceiverService
import com.tom.rv2ide.services.log.LogReceiverServiceConnection
import com.tom.rv2ide.services.log.lookupLogService
import com.tom.rv2ide.utils.jetbrainsMono
import io.github.rosemoe.sora.widget.style.CursorAnimator
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import org.slf4j.LoggerFactory

/**
 * Fragment to show application logs from LogReceiverService.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class AppLogFragment :
    EmptyStateFragment<FragmentLogViewerBinding>(R.layout.fragment_log_viewer, FragmentLogViewerBinding::bind),
    ShareableOutputFragment {

    companion object {
        private val log = LoggerFactory.getLogger(AppLogFragment::class.java)

        const val MAX_CHUNK_SIZE = 10000
        const val LOG_FREQUENCY = 50L
        const val LOG_DELAY = 100L
        const val TRIM_ON_LINE_COUNT = 5000
        const val MAX_LINE_COUNT = TRIM_ON_LINE_COUNT - 300

        fun newInstance() = AppLogFragment()
    }

    private data class AppLogEntry(
        val formatted: String,
        val level: String,
        val tag: String,
        val message: String,
    )

    private val isBoundToLogReceiver = AtomicBoolean(false)
    private var isObservingLogConnections = false
    private var logServiceConnection: LogReceiverServiceConnection? = null
    private var logReceiverImpl: LogReceiverImpl? = null
    private var filterSystemLogs = true
    private var filterText: String? = null
    private var filterByLevel = false
    private var filterByTag = false
    private var filterByMessage = false

    private val allLogs = mutableListOf<AppLogEntry>()

    private var lastLog = -1L
    private val cacheLock = ReentrantLock()
    private val cache = StringBuilder()
    private var cacheLineTrack = ArrayBlockingQueue<Int>(MAX_LINE_COUNT, true)
    private val isTrimming = AtomicBoolean(false)

    private val logHandler = Handler(Looper.getMainLooper())
    private val logRunnable =
        object : Runnable {
            override fun run() {
                cacheLock.withLock {
                    if (cacheLineTrack.size == MAX_LINE_COUNT) {
                        cache.delete(0, cacheLineTrack.poll()!!)
                    }

                    cacheLineTrack.clear()

                    if (cache.length < MAX_CHUNK_SIZE) {
                        append(cache)
                        cache.clear()
                    } else {
                        val length = min(cache.length, MAX_CHUNK_SIZE)
                        append(cache.subSequence(0, length))
                        cache.delete(0, length)
                    }

                    if (cache.isNotEmpty()) {
                        logHandler.removeCallbacks(this)
                        logHandler.postDelayed(this, LOG_DELAY)
                    } else {
                        trimLinesAtStart()
                    }
                }
            }
        }

    private val systemTags = setOf(
        "VRI", "InputMethodManager", "InputEventReceiver", "ImeFocusController",
        "SurfaceControl", "BufferQueueProducer", "BufferQueueConsumer",
        "RenderService", "libEGL", "HwViewRootImpl", "RmeSchedManager",
        "RtgSchedEvent", "RtgSchedIpcFile", "RtgSched", "PhoneWindow",
        "FullScreenUtils", "DecorView", "HWUI", "skia", "AwareBitmapCacher",
        "Resource", "ProfileInstaller", "ZrHung", "libc", "dalvikvm",
        "art", "Choreographer"

    )

    private val logServiceConnectionObserver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LogReceiverService.ACTION_CONNECTION_UPDATE) {
                return
            }

            val params = ConnectionObserverParams.from(intent) ?: return
            if (!isBoundToLogReceiver.get() && params.totalConnections > 0) {
                bindToLogReceiver()
            } else if (isBoundToLogReceiver.get() && params.totalConnections == 0) {
                unbindFromLogReceiver()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupEditor()
        setupMenu()
        if (DevOpsPreferences.logsenderEnabled) {
            registerLogConnectionObserver()
            bindToLogReceiver()
            refreshDisplay()
        } else {
            showLogSenderDisabledMessage()
        }
    }

    override fun onResume() {
        super.onResume()

        if (DevOpsPreferences.logsenderEnabled && isEmpty) {
            registerLogConnectionObserver()
            bindToLogReceiver()
            refreshDisplay()
        } else if (!DevOpsPreferences.logsenderEnabled && !isEmpty) {
            unbindFromLogReceiver()
            showLogSenderDisabledMessage()
        }
    }

    private fun setupMenu() {
        updateSystemFilterLabel()
        binding.btnFilterSystem.setOnClickListener {
            toggleSystemLogsFilter()
            updateSystemFilterLabel()
        }

        TooltipCompat.setTooltipText(binding.btnFilterLog, getString(R.string.title_log_filter))
        binding.btnFilterLog.setOnClickListener {
            showLogFilterDialog()
        }
    }

    private fun updateSystemFilterLabel() {
        val label = systemLogsFilterLabel()
        binding.btnFilterSystem.contentDescription = label
        TooltipCompat.setTooltipText(binding.btnFilterSystem, label)
    }

    private fun systemLogsFilterLabel(): CharSequence =
        getString(
            if (filterSystemLogs) {
                R.string.action_show_system_logs
            } else {
                R.string.action_hide_system_logs
            }
        )

    private fun setupEditor() {
        val editor = this.binding.logEditor
        editor.props.autoIndent = false
        editor.isEditable = false
        editor.dividerWidth = 0f
        editor.isWordwrap = false
        editor.isUndoEnabled = false
        editor.typefaceLineNumber = jetbrainsMono()
        editor.setTextSize(8f)
        editor.typefaceText = jetbrainsMono()
        editor.inputType = InputType.TYPE_NULL
        IDEColorSchemeProvider.readSchemeAsync(
            context = requireContext(),
            coroutineScope = editor.editorScope,
            type = LogLanguage.TS_TYPE,
        ) { scheme ->
            val language =
                checkNotNull(TreeSitterLanguageProvider.forType(LogLanguage.TS_TYPE, requireContext())) {
                    "No TreeSitterLanguage found for type ${LogLanguage.TS_TYPE}"
                }
            if (scheme is IDEColorScheme) {
                language.setupWith(scheme)
            }
            editor.applyTreeSitterLang(language, LogLanguage.TS_TYPE, scheme)
        }
        editor.cursorAnimator =
            object : CursorAnimator {
                override fun markStartPos() {}
                override fun markEndPos() {}
                override fun start() {}
                override fun cancel() {}
                override fun isRunning(): Boolean = false
                override fun animatedX(): Float = 0f
                override fun animatedY(): Float = 0f
                override fun animatedLineHeight(): Float = 0f
                override fun animatedLineBottom(): Float = 0f
            }
    }

    private fun append(chars: CharSequence?) {
        chars?.let {
            ThreadUtils.runOnUiThread {
                val editor = _binding?.logEditor ?: return@runOnUiThread
                val existingText = editor.text?.toString().orEmpty()
                val updatedText = existingText + chars
                editor.setText(updatedText)
                updateLogsView(updatedText.isNotEmpty())
            }
        }
    }

    private fun registerLogConnectionObserver() {
        if (isObservingLogConnections) {
            return
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            logServiceConnectionObserver,
            IntentFilter(LogReceiverService.ACTION_CONNECTION_UPDATE),
        )
        isObservingLogConnections = true
    }

    private fun appendLog(log: LogLine) {
        val entry = AppLogEntry(
            formatted = log.toString(),
            level = log.level?.levelChar?.toString().orEmpty(),
            tag = log.tag.orEmpty(),
            message = log.message.orEmpty(),
        )
        log.recycle()

        ThreadUtils.runOnUiThread {
            allLogs.add(entry)
            if (shouldDisplayLog(entry)) {
                appendLogToEditor(entry)
            }
        }
    }

    private fun bindToLogReceiver() {
        if (isBoundToLogReceiver.get() || !DevOpsPreferences.logsenderEnabled) {
            return
        }

        val context = context ?: return
        val intent = Intent(context, LogReceiverService::class.java).setAction(
            LogReceiverService.ACTION_CONNECT_LOG_CONSUMER,
        )
        val connection = logServiceConnection ?: LogReceiverServiceConnection { binder ->
            logReceiverImpl = binder
            lookupLogService()?.setConsumer(this::appendLog)
        }.also { logServiceConnection = it }

        if (context.bindService(intent, connection, Context.BIND_IMPORTANT)) {
            isBoundToLogReceiver.set(true)
        }
    }

    private fun unbindFromLogReceiver() {
        if (!isBoundToLogReceiver.getAndSet(false)) {
            return
        }
        val context = context ?: return
        lookupLogService()?.releaseConsumer()

        logServiceConnection?.let {
            runCatching { context.unbindService(it) }
        }
        logReceiverImpl = null
        logServiceConnection?.onConnected = null
        logServiceConnection = null
    }

    private fun shouldDisplayLog(log: AppLogEntry): Boolean {
        if (filterSystemLogs && isSystemLog(log)) {
            return false
        }

        val query = filterText ?: return true
        return matchesFilter(log, query)
    }

    private fun matchesFilter(log: AppLogEntry, query: String): Boolean {
        val fields =
            if (filterByLevel || filterByTag || filterByMessage) {
                buildList {
                    if (filterByLevel) add(log.level)
                    if (filterByTag) add(log.tag)
                    if (filterByMessage) add(log.message)
                }
            } else {
                listOf(log.level, log.tag, log.message)
            }

        return fields.any { it.contains(query, ignoreCase = true) }
    }

    private fun isSystemLog(log: AppLogEntry): Boolean {
        if (systemTags.any { log.tag.startsWith(it) }) return true
        if (log.tag.startsWith(".")) return true

        val message = log.message
        if (message.contains("type=1400 audit")) return true
        if (message.contains("RCS is disable")) return true
        if (message.contains("Compiler allocated")) return true
        if (message.contains("denied")) return true
        if (message.contains("Access denied")) return true

        return false
    }

    private fun appendLogToEditor(log: AppLogEntry) {
        appendLine(log.formatted)
    }

    private fun appendLine(line: String) {
        var lineStr = line
        if (!lineStr.endsWith("\n")) {
            lineStr += "\n"
        }

        if (
            isTrimming.get() ||
            cache.isNotEmpty() ||
            System.currentTimeMillis() - lastLog <= LOG_FREQUENCY
        ) {
            cacheLock.withLock {
                logHandler.removeCallbacks(logRunnable)

                cache.append(lineStr)
                logHandler.postDelayed(logRunnable, LOG_DELAY)

                lastLog = System.currentTimeMillis()

                val length = cache.length + 1
                if (!cacheLineTrack.offer(length)) {
                    cacheLineTrack.poll()
                    cacheLineTrack.offer(length)
                }
            }
            return
        }

        lastLog = System.currentTimeMillis()

        append(lineStr)
        trimLinesAtStart()
    }

    private fun trimLinesAtStart() {
        if (isTrimming.get()) {
            return
        }

        ThreadUtils.runOnUiThread {
            val editor = _binding?.logEditor ?: return@runOnUiThread
            val text = editor.text?.toString().orEmpty()
            val lines = text.lines()
            if (lines.size <= TRIM_ON_LINE_COUNT) {
                isTrimming.set(false)
                return@runOnUiThread
            }

            isTrimming.set(true)
            val trimmed = lines.takeLast(MAX_LINE_COUNT).joinToString("\n")
            editor.setText(trimmed)
            isTrimming.set(false)
        }
    }

    private fun refreshDisplay() {
        logHandler.removeCallbacks(logRunnable)
        cacheLock.withLock {
            cache.clear()
            cacheLineTrack.clear()
        }

        ThreadUtils.runOnUiThread {
            val content = buildString {
                for (log in allLogs) {
                    if (shouldDisplayLog(log)) {
                        append(log.formatted)
                        if (!endsWith("\n")) {
                            append("\n")
                        }
                    }
                }
            }
            _binding?.logEditor?.setText(content)
            updateLogsView(content.isNotEmpty())
        }
    }

    private fun toggleSystemLogsFilter() {
        filterSystemLogs = !filterSystemLogs
        refreshDisplay()
    }

    private fun showLogSenderDisabledMessage() {
        emptyStateViewModel.emptyMessage.value = getString(R.string.msg_logsender_disabled)
        emptyStateViewModel.isEmpty.value = true
    }

    private fun updateLogsView(hasLogs: Boolean) {
        emptyStateViewModel.isEmpty.value = false
        _binding?.emptyLogMessage?.isVisible = !hasLogs
        _binding?.logEditor?.isVisible = hasLogs
    }

    override fun clearOutput() {
        logHandler.removeCallbacks(logRunnable)

        cacheLock.withLock {
            cache.clear()
            cacheLineTrack.clear()
        }

        allLogs.clear()

        ThreadUtils.runOnUiThread {
            _binding?.logEditor?.setText("")
            if (DevOpsPreferences.logsenderEnabled) {
                updateLogsView(false)
            }
        }
    }

    private fun showLogFilterDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_log_filter, null)
        val filterInput = content.findViewById<TextInputEditText>(R.id.filterTextInput)
        val levelChip = content.findViewById<Chip>(R.id.chipFilterLevel)
        val tagChip = content.findViewById<Chip>(R.id.chipFilterTag)
        val messageChip = content.findViewById<Chip>(R.id.chipFilterMessage)

        filterInput.setText(filterText.orEmpty())
        levelChip.isChecked = filterByLevel
        tagChip.isChecked = filterByTag
        messageChip.isChecked = filterByMessage

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_log_filter)
            .setView(content)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                filterText = filterInput.text.toString().trim().ifEmpty { null }
                filterByLevel = levelChip.isChecked
                filterByTag = tagChip.isChecked
                filterByMessage = messageChip.isChecked
                refreshDisplay()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.action_clear_filter) { _, _ ->
                filterText = null
                filterByLevel = false
                filterByTag = false
                filterByMessage = false
                refreshDisplay()
            }
            .show()
    }

    override fun onDestroyView() {
        if (isBoundToLogReceiver.get()) {
            unbindFromLogReceiver()
        }
        if (isObservingLogConnections) {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(
                logServiceConnectionObserver,
            )
            isObservingLogConnections = false
        }
        _binding?.logEditor?.release()
        logHandler.removeCallbacks(logRunnable)
        super.onDestroyView()
    }

    override fun getContent(): String {
        return this._binding?.logEditor?.text?.toString() ?: ""
    }

    override fun getFilename(): String = "app_logs"
}
