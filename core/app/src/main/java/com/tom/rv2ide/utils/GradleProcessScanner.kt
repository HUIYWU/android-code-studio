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

package com.tom.rv2ide.utils

import java.io.File
import org.slf4j.LoggerFactory

/**
 * Discovers Gradle build processes (daemon, workers and the Kotlin compile daemon) so that they can
 * be watched for memory usage.
 *
 * The Gradle daemon is spawned in the background by the Gradle wrapper and no public API exposes
 * its process id, so the process list has to be inspected directly. Only `/proc/<pid>/cmdline` is
 * read, which is accessible for processes that run under the same UID as the IDE.
 */
internal object GradleProcessScanner {

  const val NAME_GRADLE_DAEMON = "Gradle Daemon"
  const val NAME_GRADLE_WORKER = "Gradle Worker"
  const val NAME_KOTLIN_DAEMON = "Kotlin Daemon"

  data class FoundProcess(val pid: Int, val name: String)

  private const val PROC_DIR = "/proc"
  private const val CMDLINE = "cmdline"

  private val log = LoggerFactory.getLogger(GradleProcessScanner::class.java)

  private var loggedProcFailure = false

  fun scan(): List<FoundProcess> {
    val entries =
        File(PROC_DIR).listFiles()
            ?: run {
              if (!loggedProcFailure) {
                loggedProcFailure = true
                log.warn(
                    "Cannot list {} directory - Gradle process discovery is disabled",
                    PROC_DIR,
                )
              }
              return emptyList()
            }

    val processes = ArrayList<FoundProcess>()
    for (entry in entries) {
      val pid = entry.name.toIntOrNull() ?: continue
      if (!entry.isDirectory) {
        continue
      }

      val cmdline = readCmdline(File(entry, CMDLINE)) ?: continue
      val name = nameFor(cmdline) ?: continue
      processes.add(FoundProcess(pid, name))
    }

    return processes
  }

  private fun readCmdline(file: File): String? {
    return try {
      file.readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
    } catch (e: Exception) {
      null
    }
  }

  private fun nameFor(cmdline: String): String? {
    return when {
      cmdline.contains("GradleDaemon") -> NAME_GRADLE_DAEMON
      cmdline.contains("GradleWorkerMain") -> NAME_GRADLE_WORKER
      cmdline.contains("KotlinCompileDaemon") -> NAME_KOTLIN_DAEMON
      else -> null
    }
  }
}
