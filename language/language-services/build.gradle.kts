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

import com.tom.rv2ide.build.config.BuildConfig
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


plugins {
  id("com.android.library")
  id("kotlin-android")
  id("kotlin-kapt")
}
val ktAnalysisEngineJar =
    rootProject.file("docs/misc/kotlin-android/analysis-api-standalone-embeddable-for-ide-2.3.255-SNAPSHOT.jar")


android {
  namespace = "${BuildConfig.packageName}.lsp.java"

  sourceSets {
    getByName("androidTest") {
      assets.srcDirs(rootProject.file("utilities/framework-stubs/libs"))
    }
  }

  // kotlin-compiler-embeddable and the resolved Kotlin runtime both package these builtins metadata
  // files. Keep one copy for the APK instead of excluding both copies; AGP requires explicit patterns
  // for the root and one-package layouts observed during Android test resource merging.
  packaging {
    resources.pickFirsts += "kotlin/*.kotlin_builtins"
    resources.pickFirsts += "kotlin/*/*.kotlin_builtins"
  }
}

tasks.withType<KotlinCompile>().configureEach {
  // TODO(ACS-KT-ANALYSIS-EXPERIMENT): the spike compiles against the Kotlin 2.3.x analysis engine jar
  //  while the module toolchain stays on 2.1.0.
  compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") }
}

tasks.withType<Test>().configureEach {
  // Workspace integration tests launch this standalone Tooling API artifact in a child process.
  dependsOn(":tooling:impl:jar") 
  val home = System.getenv("HOME")
  val hostNativeLibraryDir =
      rootProject.file("$home/usr/jvm-test-lib").absoluteFile
  systemProperty("java.library.path", hostNativeLibraryDir.absolutePath)
}

kapt {
  arguments {
    arg("eventBusIndex", "${BuildConfig.packageName}.events.LspJavaEventsIndex")
  }
}
dependencies {
  testImplementation(libs.tests.junit)
  testImplementation(libs.tests.robolectric)
  testImplementation(projects.testing.lspTest)

  androidTestImplementation(libs.tests.junit)
  androidTestImplementation(libs.tests.androidx.junit)
  androidTestImplementation(libs.tests.androidx.test.runner)

  kapt(projects.annotation.processors)
  kapt(libs.google.auto.service)

  api(projects.core.indexingApi)
  api(projects.core.projectdata)

  // Include the Kotlin language server modules
  // implementation(projects.server.server)
  // implementation(projects.server.shared)

  implementation(libs.androidide.ts)
  implementation(libs.androidide.ts.java)
  implementation(libs.androidide.ts.kotlin)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.common.editor)
  implementation(libs.common.javaparser)
  implementation(libs.common.utilcode)

  implementation(libs.google.auto.service.annotations)
  implementation(libs.google.guava)
  implementation(libs.google.gson)
  implementation(libs.google.material)

  implementation(projects.core.actions)
  implementation(projects.core.common)
  implementation(projects.core.lspApi)
  implementation(projects.core.resources)
  implementation(projects.editor.api)
  implementation(projects.utilities.shared)
  implementation(projects.language.compilerServices)

  implementation(libs.composite.javac)
  implementation(libs.composite.javapoet)
  implementation(libs.composite.jaxp)
  implementation(libs.composite.jdkJdeps)
  implementation(libs.composite.jdt)
  implementation(libs.composite.googleJavaFormat)

  implementation(libs.androidx.core.ktx)
  implementation(libs.common.kotlin)


  implementation(files(ktAnalysisEngineJar))
  testImplementation(libs.kotlin.compiler.embeddable)
  implementation(libs.asm)
  
  // LSP4J dependencies for kotlin-language-server integration
  implementation(libs.org.eclipse.lsp4j.lsp4j)
  implementation(libs.org.eclipse.lsp4j.jsonrpc)

}
