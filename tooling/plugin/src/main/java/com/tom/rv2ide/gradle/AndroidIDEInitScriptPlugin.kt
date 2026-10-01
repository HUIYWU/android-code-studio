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

package com.tom.rv2ide.gradle

import com.tom.rv2ide.buildinfo.BuildInfo
import com.tom.rv2ide.tooling.api.LogSenderConfig._PROPERTY_IS_TEST_ENV
import com.tom.rv2ide.tooling.api.LogSenderConfig._PROPERTY_MAVEN_LOCAL_REPOSITORY
import java.io.File
import java.net.URI
import java.util.Properties
import org.gradle.StartParameter
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging

/**
 * Plugin for the AndroidIDE's Gradle Init Script.
 *
 * @author Akash Yadav
 */
class AndroidIDEInitScriptPlugin : Plugin<Gradle> {

  companion object {
    private val logger = Logging.getLogger(AndroidIDEInitScriptPlugin::class.java)
  }

  override fun apply(target: Gradle) {
    // Fix platform encoding early
    initializeEncoding()

    target.settingsEvaluated { settings ->
      settings.addDependencyRepositories()
    }
    target.rootProject { rootProject ->
      rootProject.buildscript.apply {
        dependencies.apply {
          val gradlePluginDependency =
              rootProject.dependencies.create(
                  "${BuildInfo.MVN_GROUP_ID}.tooling:${BuildInfo.GRADLE_PLUGIN_ARTIFACT}:" +
                      BuildInfo.VERSION_NAME_DOWNLOAD
              )
          if (gradlePluginDependency is ExternalModuleDependency) {
            gradlePluginDependency.isChanging = false
          }
          add("classpath", gradlePluginDependency)
        }
        repositories.addDependencyRepositories(rootProject.gradle.startParameter)
      }
    }


    target.projectsLoaded { gradle ->
      gradle.rootProject.subprojects { sub ->
        if (!sub.buildFile.exists()) {
          return@subprojects
        }

        sub.afterEvaluate {
          logger.info("Applying AndroidIDE Gradle plugin to project '${sub.path}'")
          sub.pluginManager.apply(BuildInfo.PACKAGE_NAME)
        }
      }
    }
  }

  private fun initializeEncoding() {
    try {
      // Set system properties for encoding
      System.setProperty("file.encoding", "UTF-8")
      System.setProperty("sun.jnu.encoding", "UTF-8")
      System.setProperty("user.country", "US")
      System.setProperty("user.language", "en")

      logger.info("Platform encoding initialized to UTF-8")
    } catch (e: Exception) {
      logger.warn("Could not set encoding properties: ${e.message}")
    }
  }

  private fun Settings.addDependencyRepositories() {
    val (isTestEnv, mavenLocalRepos) = getTestEnvProps(startParameter)
    addDependencyRepositories(isTestEnv, mavenLocalRepos)
    addProjectRepositories(isTestEnv, mavenLocalRepos)
  }

  private fun Settings.addProjectRepositories(
      isMavenLocalEnabled: Boolean,
      mavenLocalRepo: String,
  ) {
    if (dependencyResolutionManagement.repositoriesMode.get() ==
        RepositoriesMode.FAIL_ON_PROJECT_REPOS) {
      logger.info("Skipping project repositories, the build resolves dependencies from settings")
      return
    }

    gradle.allprojects { project ->
      project.repositories.configureRepositories(isMavenLocalEnabled, mavenLocalRepo)
    }
  }

  @Suppress("UnstableApiUsage")
  private fun Settings.addDependencyRepositories(
      isMavenLocalEnabled: Boolean,
      mavenLocalRepo: String,
  ) {
    dependencyResolutionManagement.run {
      repositories.configureRepositories(isMavenLocalEnabled, mavenLocalRepo)
    }

    pluginManagement.apply {
      repositories.configureRepositories(isMavenLocalEnabled, mavenLocalRepo)
    }
  }

  private fun RepositoryHandler.addDependencyRepositories(startParams: StartParameter) {
    val (isTestEnv, mavenLocalRepos) = getTestEnvProps(startParams)
    configureRepositories(isTestEnv, mavenLocalRepos)
  }

  private fun getTestEnvProps(startParameter: StartParameter): Pair<Boolean, String> {
    return startParameter.run {
      val isTestEnv =
          projectProperties.containsKey(_PROPERTY_IS_TEST_ENV) &&
              projectProperties[_PROPERTY_IS_TEST_ENV].toString().toBoolean()
      val mavenLocalRepos =
          projectProperties.getOrDefault(_PROPERTY_MAVEN_LOCAL_REPOSITORY, "").toString()

      isTestEnv to mavenLocalRepos
    }
  }

  private fun RepositoryHandler.configureRepositories(
      isMavenLocalEnabled: Boolean,
      mavenLocalRepos: String,
  ) {
    // Always add standard repositories first
    google()
    mavenCentral()
    maven { repository ->
      repository.name = "GitHub Packages"
      repository.setUrl(BuildInfo.GITHUB_PACKAGES_REPOSITORY)
      githubCredentials()?.also { (user, token) ->
        repository.credentials(
            Action<PasswordCredentials> { credentials ->
              credentials.username = user
              credentials.password = token
            }
        )
      }
    }
    gradlePluginPortal()

    if (isMavenLocalEnabled && mavenLocalRepos.isNotBlank()) {
      logger.info("Using local maven repository for classpath resolution...")

      mavenLocalRepos.split(':').forEach { mavenLocalRepo ->
        if (mavenLocalRepo.isBlank()) {
          mavenLocal()
        } else {
          logger.info("Local repository path: $mavenLocalRepo")
          val repo = File(mavenLocalRepo)
          if (repo.exists() && repo.isDirectory) {
            maven { repository -> repository.url = repo.toURI() }
          }
        }
      }
    }

    // Add custom repositories if configured
    addCustomRepositories()
  }

  private fun RepositoryHandler.addCustomRepositories() {
    try {
      val snapshotsRepo = BuildInfo.SNAPSHOTS_REPOSITORY
      if (isValidRepository(snapshotsRepo)) {
        logger.info("Adding snapshots repository: $snapshotsRepo")
        maven { repository ->
          repository.name = "AndroidIDE Snapshots"
          repository.url = URI.create(snapshotsRepo)
        }
      }

      val publicRepo = BuildInfo.PUBLIC_REPOSITORY
      if (isValidRepository(publicRepo)) {
        logger.info("Adding public repository: $publicRepo")
        maven { repository ->
          repository.name = "AndroidIDE Public"
          repository.setUrl(publicRepo)
        }
      }
    } catch (e: Exception) {
      logger.warn("Error configuring custom repositories: ${e.message}")
    }
  }

  private fun isValidRepository(repoUrl: String?): Boolean {
    return !repoUrl.isNullOrBlank() &&
        repoUrl != "null" &&
        !repoUrl.startsWith("@@") &&
        !repoUrl.endsWith("@@") &&
        (repoUrl.startsWith("http://") || repoUrl.startsWith("https://"))
  }

  private fun githubCredentials(): Pair<String, String>? {
    val properties = gradleProperties()
    val user =
        System.getProperty("gpr.user")?.takeIf(String::isNotBlank)
            ?: properties["gpr.user"]?.takeIf(String::isNotBlank)
            ?: System.getenv("GPR_USER")?.takeIf(String::isNotBlank)
            ?: System.getenv("GITHUB_ACTOR")?.takeIf(String::isNotBlank)
    val token =
        System.getProperty("gpr.token")?.takeIf(String::isNotBlank)
            ?: properties["gpr.token"]?.takeIf(String::isNotBlank)
            ?: System.getenv("GPR_TOKEN")?.takeIf(String::isNotBlank)
            ?: System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotBlank)

    if (user == null || token == null) {
      return null
    }

    return user to token
  }

  private fun gradleProperties(): Map<String, String> {
    val gradleUserHome =
        System.getenv("GRADLE_USER_HOME")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".gradle")
    val propertiesFile = File(gradleUserHome, "gradle.properties")
    if (!propertiesFile.isFile) {
      return emptyMap()
    }

    val properties = Properties()
    try {
      propertiesFile.inputStream().use { stream -> properties.load(stream) }
    } catch (e: Exception) {
      logger.warn("Unable to read '${propertiesFile.absolutePath}': ${e.message}")
      return emptyMap()
    }

    return properties.stringPropertyNames().associateWith { properties.getProperty(it) ?: "" }
  }
}
