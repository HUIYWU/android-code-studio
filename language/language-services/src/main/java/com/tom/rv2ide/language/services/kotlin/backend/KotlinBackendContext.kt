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
package com.tom.rv2ide.language.services.kotlin.backend

import com.tom.rv2ide.language.services.kotlin.classpath.KotlinProjectClasspathProvider
import com.tom.rv2ide.language.services.kotlin.logging.KlsLogs
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import java.io.File

class KotlinBackendContext(
    val workspace: IWorkspace,
    val classpathProvider: KotlinProjectClasspathProvider,
) {

  fun findMainAndroidModule(): ModuleProject? {
    val subProjects = workspace.getSubProjects()

    for (subProject in subProjects) {
      if (subProject is AndroidModule && subProject.isApplication) {
        return subProject
      }
    }

    for (subProject in subProjects) {
      if (subProject is AndroidModule) {
        return subProject
      }
    }

    return null
  }

  fun mainModuleWorkspaceRoot(backendId: KotlinBackendId): File {
    val mainModule = findMainAndroidModule()
    if (mainModule != null) {
      val moduleDir = mainModule.projectDir
      if (moduleDir.exists() && moduleDir.isDirectory) {
        KlsLogs.info(
            "Using main Android module as KLS workspace root: {} (gradlePath={}, backend={})",
            moduleDir.absolutePath,
            mainModule.path,
            backendId.name.lowercase(),
        )
        return moduleDir
      }

      KlsLogs.warn(
          "Main Android module directory is invalid, fallback to project root: dir={}, gradlePath={}, backend={}",
          moduleDir.absolutePath,
          mainModule.path,
          backendId.name.lowercase(),
      )
    }

    return workspace.getProjectDir()
  }
}
