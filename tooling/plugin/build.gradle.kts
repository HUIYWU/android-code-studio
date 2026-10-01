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

import com.tom.rv2ide.build.config.AGP_VERSION_MINIMUM
import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.build.config.ProjectConfig
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("java-gradle-plugin")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

description = "Gradle Plugin for projects that are built with AndroidCS"

group = "${BuildConfig.mavenGroupId}.tooling"

configurations {
    val androidBuildTool = create("androidBuildTool")

    compileOnly {
        extendsFrom(androidBuildTool)
    }
    testImplementation {
        extendsFrom(androidBuildTool)
    }
}

dependencies {
    compileOnly(projects.tooling.pluginConfig)
    compileOnly(projects.utilities.buildInfo)
    add("androidBuildTool", "com.android.tools.build:gradle:${AGP_VERSION_MINIMUM}")
}

evaluationDependsOn(":tooling:plugin-config")
evaluationDependsOn(":utilities:build-info")

val bundledClasses =
    listOf(":tooling:plugin-config", ":utilities:build-info").map { path ->
        project(path).extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main").output
    }

tasks.named<Jar>("jar") {
    from(bundledClasses)
}

gradlePlugin {
    website.set(ProjectConfig.REPO_URL)
    vcsUrl.set(ProjectConfig.REPO_URL)

    plugins {
        create("gradlePlugin") {
            id = BuildConfig.packageName
            implementationClass = "${BuildConfig.packageName}.gradle.AndroidIDEGradlePlugin"
        }
        create("logsenderPlugin") {
          id = "${BuildConfig.packageName}.logsender"
          implementationClass = "${BuildConfig.packageName}.gradle.LogSenderPlugin"
          displayName = "AndroidIDE LogSender Gradle Plugin"
          description = "Gradle plugin for applying LogSender-specific configuration to projects that are built with AndroidIDE"
          tags.set(setOf("androidide", "logsender"))
        }
    }
    
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/HUIYWU/android-code-studio")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                    ?: ""
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.token").orNull
                    ?: ""
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = BuildConfig.gradlePluginArtifact
        }
    }
}

// Module-creation init support is packaged with tooling-api-all.jar.