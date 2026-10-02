/*
 *  This file is part of AndroidIDE.
 */

import com.tom.rv2ide.plugins.NoDesugarPlugin
import com.tom.rv2ide.build.config.BuildConfig
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("maven-publish")
}

apply {
    plugin(NoDesugarPlugin::class.java)
}

description = "LogSender is used to read logs from applications built with AndroidIDE"

group = "${BuildConfig.mavenGroupId}.logging"

android {
    namespace = "${BuildConfig.packageName}.logsender"

    defaultConfig {
        minSdk = 16
        vectorDrawables.useSupportLibrary = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        viewBinding = false
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    // your dependencies here
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

}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "${BuildConfig.mavenGroupId}.logging"
                artifactId = BuildConfig.logsenderArtifact
                version = project.version.toString()
                from(components["release"])
            }
        }
    }
}
