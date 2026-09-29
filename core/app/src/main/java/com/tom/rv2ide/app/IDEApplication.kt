/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.tom.rv2ide.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.blankj.utilcode.util.ThrowableUtils.getFullStackTrace
import com.termux.app.TermuxApplication
import com.termux.shared.reflection.ReflectionUtils
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.activities.CrashHandlerActivity
import com.tom.rv2ide.projects.util.AnrWatchdog
import com.tom.rv2ide.projects.util.RuntimeProbe
import com.tom.rv2ide.activities.editor.IDELogcatReader
import com.tom.rv2ide.buildinfo.BuildInfo
import com.tom.rv2ide.common.logging.IdeLogConfig
import com.tom.rv2ide.editor.schemes.IDEColorSchemeProvider
import com.tom.rv2ide.eventbus.events.preferences.PreferenceChangeEvent
import com.tom.rv2ide.events.AppEventsIndex
import com.tom.rv2ide.events.EditorEventsIndex
import com.tom.rv2ide.events.LspApiEventsIndex
import com.tom.rv2ide.events.LspJavaEventsIndex
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.preferences.internal.StatPreferences
import com.tom.rv2ide.resources.localization.LocaleProvider
import com.tom.rv2ide.stats.AndroidIDEStats
import com.tom.rv2ide.stats.StatUploadWorker
import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.treesitter.TreeSitter
import com.tom.rv2ide.utils.ChartMemoryCleanupTask
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.MemoryManager
import com.tom.rv2ide.utils.RecyclableObjectPool
import com.tom.rv2ide.utils.VMUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.MemoryProfiler
import io.github.miyazkaori.silentinstaller.SilentInstaller
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.File
import java.io.FileOutputStream
import java.lang.Thread.UncaughtExceptionHandler
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.tom.rv2ide.utils.StopWatch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

class IDEApplication : TermuxApplication() {

  private var uncaughtExceptionHandler: UncaughtExceptionHandler? = null
  private var anrWatchdog: AnrWatchdog? = null
  private var ideLogcatReader: IDELogcatReader? = null
  private var memoryManager: MemoryManager? = null
  private var chartCleanupTask: ChartMemoryCleanupTask? = null
  private var memoryProfiler: MemoryProfiler? = null

  init {
    RecyclableObjectPool.DEBUG = BuildConfig.DEBUG
  }

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)

    SilentInstaller.init(this)
    // Load native libraries after base context is attached
    TreeSitter.loadLibrary()
    if (!VMUtils.isJvm()) {
      try {} catch (e: Throwable) {
        log.error("Failed to load TreeSitter library", e)
      }
    }
  }

  @OptIn(DelicateCoroutinesApi::class)
  override fun onCreate() {
    instance = this
    RuntimeProbe.init(this)
    RuntimeProbe.mark("IDEApplication.onCreate:start")
    uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, th -> handleCrash(thread, th) }

    super.onCreate()

    IdeLogConfig.setIdeLogsEnabledProvider {
      GeneralPreferences.ideLogsEnabled
    }
    IdeLogConfig.setIdeDebugLogsEnabledProvider {
      DevOpsPreferences.ideDebugLogsEnabled
    }
    StopWatch.setDebugLoggingEnabledProvider {
      IdeLogConfig.shouldLogDebug()
    }

    if (BuildConfig.DEBUG) {
      StrictMode.setVmPolicy(
          StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy()).penaltyLog().detectAll().build()
      )
      if (DevOpsPreferences.dumpLogs) {
        startLogcatReader()
      }
    // initializeMemoryProfiler()
      }

      if (RuntimeProbe.ENABLED) {
        anrWatchdog = AnrWatchdog().also { it.start() }
        log.info("ACS_RUNTIME_PROBE_WATCHDOG_STARTED dumpDir={}", RuntimeProbe.getDumpDirPath())
      }

      EventBus.builder()
        .addIndex(AppEventsIndex())
        .addIndex(EditorEventsIndex())
        .addIndex(LspApiEventsIndex())
        .addIndex(LspJavaEventsIndex())
        .installDefaultEventBus(true)

    EventBus.getDefault().register(this)

    AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)

    EditorColorScheme.setDefault(SchemeAndroidIDE.newInstance(null))

    ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
    GlobalScope.launch { IDEColorSchemeProvider.init() }

    // Initialize memory management
    // initializeMemoryManagement()
    extractJetbrainsMono()

    // DISABLED: Plugin system completely disabled to prevent Tooling API issues
    // initializePluginSystem()
  }

  fun showChangelog() {
    val intent = Intent(Intent.ACTION_VIEW)
    var version = BuildInfo.VERSION_NAME_SIMPLE
    if (!version.startsWith('v')) {
      version = "v${version}"
    }
    intent.data = Uri.parse("${BuildInfo.REPO_URL}/releases/tag/${version}")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      startActivity(intent)
    } catch (th: Throwable) {
      log.error("Unable to start activity to show changelog", th)
      flashError("Unable to start activity")
    }
  }

  fun reportStatsIfNecessary() {

    if (!StatPreferences.statOptIn) {
      log.info("Stat collection is disabled.")
      return
    }

    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request =
        PeriodicWorkRequestBuilder<StatUploadWorker>(Duration.ofHours(24))
            .setInputData(AndroidIDEStats.statData.toInputData())
            .setConstraints(constraints)
            .addTag(StatUploadWorker.WORKER_WORK_NAME)
            .build()

    val workManager = WorkManager.getInstance(this)

    log.info("reportStatsIfNecessary: Enqueuing StatUploadWorker...")
    val operation =
        workManager.enqueueUniquePeriodicWork(
            StatUploadWorker.WORKER_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )

    operation.state.observeForever(
        object : Observer<Operation.State> {
          override fun onChanged(value: Operation.State) {
            operation.state.removeObserver(this)
            log.debug("reportStatsIfNecessary: WorkManager enqueue result: {}", value)
          }
        }
    )
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onPrefChanged(event: PreferenceChangeEvent) {
    val enabled = event.value as? Boolean?
    if (
        event.key == GeneralPreferences.UI_MODE &&
            GeneralPreferences.uiMode != AppCompatDelegate.getDefaultNightMode()
    ) {
      AppCompatDelegate.setDefaultNightMode(GeneralPreferences.uiMode)
    } else if (event.key == GeneralPreferences.SELECTED_LOCALE) {

      // Use empty locale list if the locale has been reset to 'System Default'
      val selectedLocale = GeneralPreferences.selectedLocale
      val localeListCompat =
          selectedLocale?.let { LocaleListCompat.create(LocaleProvider.getLocale(selectedLocale)) }
              ?: LocaleListCompat.getEmptyLocaleList()

      AppCompatDelegate.setApplicationLocales(localeListCompat)
    }
  }

  private fun extractJetbrainsMono() {
    try {
      val fontsDir = File(Environment.HOME, ".androidide/ui")

      if (!fontsDir.exists()) {
        fontsDir.mkdirs()
      }
      val targetFont = File(fontsDir, "jetbrains-mono.ttf")
      if (targetFont.exists()) {
        return
      }

      assets.open("fonts/jetbrains-mono.ttf").use { input ->
        FileOutputStream(targetFont).use { output -> input.copyTo(output) }
      }
    } catch (e: Exception) {
      log.error("Failed to extract jetbrains-mono.ttf font", e)
    }
  }
  
  private fun handleCrash(thread: Thread, th: Throwable) {
    RuntimeProbe.mark("uncaught:${thread.name}:${th::class.java.simpleName}")
    RuntimeProbe.dump("uncaught thread=${thread.name}", th)

    // writeException(th)

    try {

      val intent = Intent()
      intent.action = CrashHandlerActivity.REPORT_ACTION
      intent.putExtra(CrashHandlerActivity.TRACE_KEY, getFullStackTrace(th))
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(intent)
      if (uncaughtExceptionHandler != null) {
        uncaughtExceptionHandler!!.uncaughtException(thread, th)
      }

      exitProcess(1)
    } catch (error: Throwable) {
      Log.e("IDEApplication", "Unable to show crash handler activity", error)
    }
  }

  private fun cancelStatUploadWorker() {
    log.info("Opted-out of stat collection. Cancelling StatUploadWorker if enqueued...")
    val operation =
        WorkManager.getInstance(this).cancelUniqueWork(StatUploadWorker.WORKER_WORK_NAME)
    operation.state.observeForever(
        object : Observer<Operation.State> {
          override fun onChanged(value: Operation.State) {
            operation.state.removeObserver(this)
            log.info("StatUploadWorker: Cancellation result state: {}", value)
          }
        }
    )
  }

  private fun startLogcatReader() {
    if (ideLogcatReader != null) {
      // already started
      return
    }

    log.info("Starting logcat reader...")
    ideLogcatReader = IDELogcatReader().also { it.start() }
  }

  private fun stopLogcatReader() {
    log.info("Stopping logcat reader...")
    ideLogcatReader?.stop()
    ideLogcatReader = null
  }

  /** Initialize memory management system. RE-ENABLED with fixes for Tooling API compatibility. */
  private fun initializeMemoryManagement() {
    try {
      memoryManager = MemoryManager.getInstance(this)
      chartCleanupTask = ChartMemoryCleanupTask()

      // Register chart cleanup task
      memoryManager?.registerCleanupTask(chartCleanupTask!!)

      // Start memory monitoring with reduced frequency to avoid Tooling API conflicts
      memoryManager?.startMonitoring()

      Log.i("IDEApplication", "Memory management system initialized with Tooling API compatibility")
    } catch (e: Exception) {
      Log.e("IDEApplication", "Failed to initialize memory management", e)
    }
  }

  /** Get the chart cleanup task for registering charts. */
  fun getChartCleanupTask(): ChartMemoryCleanupTask? {
    return chartCleanupTask
  }

  /** Get the memory manager instance. */
  fun getMemoryManager(): MemoryManager? {
    return memoryManager
  }

  private fun initializeMemoryProfiler() {
    try {
      memoryProfiler = MemoryProfiler.getInstance(this)
      memoryProfiler?.startMonitoring()
      Log.i("IDEApplication", "Memory profiler initialized and started")
    } catch (e: Exception) {
      Log.e("IDEApplication", "Failed to initialize memory profiler", e)
    }
  }

  fun getMemoryProfiler(): MemoryProfiler? {
    return memoryProfiler
  }
  companion object {

    private val log = LoggerFactory.getLogger(IDEApplication::class.java)

    @JvmStatic
    lateinit var instance: IDEApplication
      private set
  }
}
