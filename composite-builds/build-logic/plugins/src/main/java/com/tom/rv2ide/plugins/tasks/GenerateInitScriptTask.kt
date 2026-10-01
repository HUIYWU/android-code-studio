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

package com.tom.rv2ide.plugins.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Generates the Gradle init script for AndroidIDE. */
abstract class GenerateInitScriptTask : DefaultTask() {

  @get:Input abstract val downloadVersion: Property<String>

  @get:Input abstract val mavenGroupId: Property<String>

  @get:Input abstract val pluginArtifact: Property<String>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {

    val outFile =
        this.outputDir.file("data/common/androidide.init.gradle").also {
          it.get().asFile.parentFile.mkdirs()
        }

    outFile.get().asFile.bufferedWriter().use {
      it.write(
          """
             initscript {
                 def gprProps = new Properties()
                 def gprUserHome = System.getenv('GRADLE_USER_HOME')
                 if (!gprUserHome) {
                     gprUserHome = new File(System.getProperty('user.home'), '.gradle').absolutePath
                 }
                 def gprPropsFile = new File(gprUserHome, 'gradle.properties')
                 if (gprPropsFile.isFile()) {
                     gprPropsFile.withInputStream { gprProps.load(it) }
                 }
                 def gprUser = System.getProperty('gpr.user') ?: gprProps.getProperty('gpr.user') ?: System.getenv('GPR_USER') ?: System.getenv('GITHUB_ACTOR')
                 def gprToken = System.getProperty('gpr.token') ?: gprProps.getProperty('gpr.token') ?: System.getenv('GPR_TOKEN') ?: System.getenv('GITHUB_TOKEN')

                 repositories {
                     maven { url '${com.tom.rv2ide.build.config.VersionUtils.SONATYPE_SNAPSHOTS_REPO}' }
                     maven { url '${com.tom.rv2ide.build.config.VersionUtils.SONATYPE_PUBLIC_REPO}' }
                     maven {
                         url '${com.tom.rv2ide.build.config.VersionUtils.GITHUB_PACKAGES_REPO}'
                         if (gprUser && gprToken) {
                             credentials {
                                 username gprUser
                                 password gprToken
                             }
                         }
                     }
                     mavenCentral()
                     google()
                 }
 
                 dependencies {
                     classpath('${mavenGroupId.get()}.tooling:${pluginArtifact.get()}:${downloadVersion.get()}') {
                         setChanging(false)
                     }
                     def toolingApiJar = System.getProperty('androidide.tooling.api.jar')
                     if (toolingApiJar == null || toolingApiJar.trim().isEmpty()) {
                         throw new GradleException('AndroidIDE Tooling API JAR path is unavailable')
                     }
                     classpath(files(toolingApiJar))
                 }
             }
 
             apply plugin: com.tom.rv2ide.gradle.AndroidIDEInitScriptPlugin
             apply plugin: com.tom.rv2ide.gradle.ModuleCreationInitScriptPlugin
          """
              .trimIndent()
      )
    }
  }
}
