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

package com.tom.rv2ide.logging

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import ch.qos.logback.classic.pattern.ClassNameOnlyAbbreviator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.tom.rv2ide.utils.LogTagUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * An [AppenderBase] implementation to show the logs in the GUI.
 *
 * @author Akash Yadav
 */
class LifecycleAwareAppender
@JvmOverloads
constructor(
    private val requireLifecycleState: Lifecycle.State = Lifecycle.State.CREATED,
    var consumer: ((IdeLogEntry) -> Unit)? = null,
) : AppenderBase<ILoggingEvent>(), LifecycleEventObserver {

  companion object {
    private const val THREAD_WIDTH = 35
    private const val TAG_WIDTH = 25
  }

  private var currentState: Lifecycle.State? = null
  private val loggerNameAbbreviator = ClassNameOnlyAbbreviator()

  init {
    setName("LifecycleAwareAppender")
  }

  fun attachTo(lifecycleOwner: LifecycleOwner) = attachTo(lifecycleOwner.lifecycle)

  fun attachTo(lifecycle: Lifecycle) = lifecycle.addObserver(this)

  fun detachFrom(lifecycleOwner: LifecycleOwner) = detachFrom(lifecycleOwner.lifecycle)

  fun detachFrom(lifecycle: Lifecycle) = lifecycle.removeObserver(this)

  override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {

    this.currentState =
        when (event) {
          Lifecycle.Event.ON_ANY -> null
          else -> event.targetState
        }
  }

  override fun isStarted(): Boolean {
    return (super.isStarted() &&
        currentState?.isAtLeast(this.requireLifecycleState) == true &&
        consumer != null)
  }

  override fun stop() {
    super.stop()
    this.consumer = null
  }

  override fun append(eventObject: ILoggingEvent?) {
    if (eventObject == null || !isStarted) {
      return
    }

    val date =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(eventObject.timeStamp))
    val level = eventObject.level.levelStr
    val thread = formatThread(eventObject.threadName)
    val tag =
        LogTagUtils.trimTagIfNeeded(
            "${loggerNameAbbreviator.abbreviate(eventObject.loggerName)}:",
            TAG_WIDTH,
        )

    eventObject.formattedMessage.split('\n').forEach { message ->
      consumer?.invoke(
          IdeLogEntry(
              formatted =
                  String.format(
                      Locale.ROOT,
                      "%s %5s %-${THREAD_WIDTH}s %-${TAG_WIDTH}s %s",
                      date,
                      level,
                      thread,
                      tag,
                      message,
                  ),
              level = level,
              tag = tag,
              message = message,
          )
      )
    }
  }

  private fun formatThread(threadName: String): String {
    val thread = "[$threadName]"
    if (thread.length <= THREAD_WIDTH) {
      return thread
    }
    return "[${threadName.substring(0, THREAD_WIDTH - 2)}]"
  }
}
