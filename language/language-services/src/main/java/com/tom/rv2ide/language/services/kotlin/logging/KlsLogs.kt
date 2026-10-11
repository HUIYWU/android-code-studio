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
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.language.services.kotlin.logging

import com.tom.rv2ide.common.logging.IdeLogConfig
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.slf4j.helpers.MessageFormatter

/**
 * Centralized KLS logging control.
 *
 * - info / warn / error follow the IDE log master switch
 * - debug / trace additionally require the IDE debug log switch
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
object KlsLogs {
  private val log = LoggerFactory.getLogger(KlsLogs::class.java)
  private val throttledLogTimes = ConcurrentHashMap<String, Long>()

  fun error(message: String) {
    if (IdeLogConfig.shouldLogError()) {
      log.error(message)
    }
  }

  fun error(message: String, throwable: Throwable) {
    if (IdeLogConfig.shouldLogError()) {
      logWithThrowable(LogLevel.ERROR, message, throwable)
    }
  }

  fun warn(message: String) {
    if (IdeLogConfig.shouldLogWarn()) {
      log.warn(message)
    }
  }

  fun warn(message: String, throwable: Throwable) {
    if (IdeLogConfig.shouldLogWarn()) {
      logWithThrowable(LogLevel.WARN, message, throwable)
    }
  }

  fun info(message: String) {
    if (IdeLogConfig.shouldLogInfo()) {
      log.info(message)
    }
  }

  fun debug(message: String) {
    if (IdeLogConfig.shouldLogDebug()) {
      log.debug(message)
    }
  }

  fun trace(message: String) {
    if (IdeLogConfig.shouldLogTrace()) {
      log.trace(message)
    }
  }

  // Simple formatted message methods that handle null values
  fun error(format: String, vararg args: Any?) {
    if (IdeLogConfig.shouldLogError()) {
      logFormatted(LogLevel.ERROR, format, args)
    }
  }

  fun warn(format: String, vararg args: Any?) {
    if (IdeLogConfig.shouldLogWarn()) {
      logFormatted(LogLevel.WARN, format, args)
    }
  }

  fun info(format: String, vararg args: Any?) {
    if (IdeLogConfig.shouldLogInfo()) {
      logFormatted(LogLevel.INFO, format, args)
    }
  }

  fun debug(format: String, vararg args: Any?) {
    if (IdeLogConfig.shouldLogDebug()) {
      logFormatted(LogLevel.DEBUG, format, args)
    }
  }

  fun trace(format: String, vararg args: Any?) {
    if (IdeLogConfig.shouldLogTrace()) {
      logFormatted(LogLevel.TRACE, format, args)
    }
  }

  fun infoThrottled(key: String, intervalMs: Long, message: String) {
    if (IdeLogConfig.shouldLogInfo() && shouldLogNow(key, intervalMs)) {
      log.info(message)
    }
  }

  fun infoThrottled(key: String, intervalMs: Long, format: String, vararg args: Any?) {
    if (IdeLogConfig.shouldLogInfo() && shouldLogNow(key, intervalMs)) {
      logFormatted(LogLevel.INFO, format, args)
    }
  }

  fun debugThrottled(key: String, intervalMs: Long, message: String) {
    if (IdeLogConfig.shouldLogDebug() && shouldLogNow(key, intervalMs)) {
      log.debug(message)
    }
  }

  fun debugThrottled(key: String, intervalMs: Long, format: String, vararg args: Any?) {
    if (IdeLogConfig.shouldLogDebug() && shouldLogNow(key, intervalMs)) {
      logFormatted(LogLevel.DEBUG, format, args)
    }
  }

  private enum class LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
  }

  private fun logFormatted(level: LogLevel, format: String, args: Array<out Any?>) {
    val throwable = args.lastOrNull() as? Throwable
    val messageArgs = if (throwable == null) args else args.copyOf(args.size - 1)
    val safeArgs = messageArgs.map { it ?: "null" }.toTypedArray()
    val message = MessageFormatter.arrayFormat(format, safeArgs).message
    if (throwable == null) {
      logMessage(level, message)
    } else {
      logWithThrowable(level, message, throwable)
    }
  }

  private fun logWithThrowable(level: LogLevel, message: String, throwable: Throwable) {
    logMessage(level, message)
    throwable.stackTraceToString().lineSequence().forEach { line ->
      logMessage(level, line)
    }
  }

  private fun logMessage(level: LogLevel, message: String) {
    when (level) {
      LogLevel.ERROR -> log.error(message)
      LogLevel.WARN -> log.warn(message)
      LogLevel.INFO -> log.info(message)
      LogLevel.DEBUG -> log.debug(message)
      LogLevel.TRACE -> log.trace(message)
    }
  }

  private fun shouldLogNow(key: String, intervalMs: Long): Boolean {
    val now = System.currentTimeMillis()
    val last = throttledLogTimes[key]
    if (last != null && now - last < intervalMs) {
      return false
    }
    throttledLogTimes[key] = now
    return true
  }
}

