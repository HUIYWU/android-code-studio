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
package com.tom.rv2ide.lsp.clang

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.utils.Environment
import java.io.File

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ClangWorkspaceSetup(private val context: Context, private val workspace: IWorkspace) {

  companion object {
    private val CPP_EXTENSIONS = setOf("cpp", "cc", "cxx", "c++")
    private val C_EXTENSIONS = setOf("c")
    private val SOURCE_EXTENSIONS = CPP_EXTENSIONS + C_EXTENSIONS
    private val INCLUDE_DIR_NAMES = setOf("include", "includes", "inc", "src", "cpp", "cxx")
    private val EXCLUDED_DIR_NAMES =
        setOf(
            "build",
            ".gradle",
            ".git",
            ".idea",
            ".cxx",
            ".externalNativeBuild",
            "node_modules",
        )
  }
  fun setup(processManager: ClangServerProcessManager): Boolean {
    val workspaceRoot = workspace.getProjectDir().toURI().toString()
    ClangLogs.info("Setting up clang workspace: {}", workspaceRoot)

    return try {
      val compileCommandsPath = generateCompileCommands()

      if (compileCommandsPath == null) {
        ClangLogs.warn("compile_commands dir is null; clangd will start without --compile-commands-dir")
      }

      processManager.startServer(compileCommandsPath)

      val initParams = createInitParams(workspaceRoot, compileCommandsPath)

      processManager.sendRequest("initialize", initParams).get()
      ClangLogs.info("Clang workspace initialized successfully")
      processManager.sendNotification("initialized", JsonObject())
      true
    } catch (e: Exception) {
      ClangLogs.error("Clang workspace setup failed during initialize", e)
      false
    }
  }


  private fun generateCompileCommands(): String? {
    try {
      val projectDir = workspace.getProjectDir()
      findExistingCompileCommands(projectDir)?.let { existing ->
        val source =
            if (existing.absolutePath.contains("/clanglsp/reconstructed/")) {
              "reconstructed-binary"
            } else {
              "existing"
            }
        logCompileCommandsSelection(existing, projectDir, source = source)
        return existing.parentFile?.absolutePath
      }

      val buildDir =
          File(Environment.PREFIX, "clanglsp/build").apply {
            deleteRecursively()
            mkdirs()
          }
      val compileCommandsFile = File(buildDir, "compile_commands.json")


      val sourceFiles = findSourceFiles(projectDir)

      if (sourceFiles.isEmpty()) {
        ClangLogs.warn("No C/C++ source files found in project")
        return null
      }
      val projectIncludeDirs = findProjectIncludeDirs(projectDir)

      val jsonArray = JsonArray()

      sourceFiles.forEach { file ->
        val entry =
            JsonObject().apply {
              addProperty("directory", projectDir.absolutePath)
              addProperty("command", buildClangCommand(file, projectIncludeDirs))
              addProperty("file", file.absolutePath)
            }
        jsonArray.add(entry)
      }
      compileCommandsFile.writeText(jsonArray.toString())
      logCompileCommandsSelection(compileCommandsFile, projectDir, source = "generated-fallback")

      return buildDir.absolutePath

    } catch (e: Exception) {
      ClangLogs.error("Failed to generate compile_commands.json", e)
      return null
    }
  }

  private fun logCompileCommandsSelection(
      compileCommandsFile: File,
      projectDir: File,
      source: String,
  ) {
    try {
      val compileDir = compileCommandsFile.parentFile?.absolutePath ?: "<no-parent>"
      val isFromCxx = compileCommandsFile.absolutePath.contains("/.cxx/")
      val projectSources = findSourceFiles(projectDir)
      val mainCpp = projectSources.firstOrNull { it.name.equals("main.cpp", ignoreCase = true) }
      val rootElement = JsonParser.parseString(compileCommandsFile.readText())
      val entries = rootElement.asJsonArray
      val matchingEntry =
          entries.firstOrNull { element ->
            val obj = element.asJsonObject
            val filePath = obj.get("file")?.asString ?: return@firstOrNull false
            if (mainCpp != null) {
              filePath == mainCpp.absolutePath
            } else {
              filePath.endsWith("/main.cpp") || filePath.endsWith("\\main.cpp")
            }
          }?.asJsonObject
      val preview =
          matchingEntry?.let { entry ->
            entry.get("command")?.asString ?: entry.getAsJsonArray("arguments")?.joinToString(" ") { it.asString }
          }?.replace('\n', ' ')
              ?.take(600)

      ClangLogs.info(
          "Selected compile_commands: source={}, file={}, dir={}, fromCxx={}, entries={}",
          source,
          compileCommandsFile.absolutePath,
          compileDir,
          isFromCxx,
          entries.size(),
      )
      if (mainCpp != null) {
        ClangLogs.info(
            "compile_commands main.cpp match: file={}, matched={}",
            mainCpp.absolutePath,
            matchingEntry != null,
        )
      }
      if (!preview.isNullOrBlank()) {
        ClangLogs.info("compile_commands main.cpp command preview: {}", preview)
      }
    } catch (e: Exception) {
      ClangLogs.warn("Failed to inspect compile_commands file: {}", compileCommandsFile.absolutePath, e)
    }
  }

  /**
   * Android Gradle/CMake projects usually emit the authoritative compilation database under
   * module-scoped native build directories such as `app/.cxx/<variant>/<hash>/<abi>/compile_commands.json`
   * or `.externalNativeBuild/...`.
   *
   * This lookup must not blindly reuse the normal source-tree exclusion rules that skip hidden
   * directories, otherwise `.cxx` is never visited and clangd falls back to the synthetic
   * compile_commands generated by ACS. That fallback lacks Android NDK/prefab flags and leads to
   * false `file not found` diagnostics for headers such as GameActivity/EGL/GLES.
   */
  private fun findExistingCompileCommands(projectDir: File): File? {
    val direct = File(projectDir, "compile_commands.json")
    if (direct.isFile) {
      return direct
    }

    val commonCandidates =
        listOf(
            File(projectDir, "build/compile_commands.json"),
            File(projectDir, ".cxx/compile_commands.json"),
            File(projectDir, ".externalNativeBuild/compile_commands.json"),
            File(projectDir, "app/compile_commands.json"),
        )

    commonCandidates.firstOrNull { it.isFile }?.let { return it }

    val discoveredCandidates =
        findFilesByName(
            dir = projectDir,
            name = "compile_commands.json",
            maxDepth = 8,
            includeHiddenDirs = true,
            excludedDirNames = EXCLUDED_DIR_NAMES - ".cxx" - ".externalNativeBuild",
        )
    selectBestCompileCommands(projectDir, discoveredCandidates)?.let { return it }

    val binaryCandidates =
        findFilesByName(
            dir = projectDir,
            name = "compile_commands.json.bin",
            maxDepth = 8,
            includeHiddenDirs = true,
            excludedDirNames = EXCLUDED_DIR_NAMES - ".cxx" - ".externalNativeBuild" - "build",
        )
    return reconstructCompileCommandsFromBinary(projectDir, binaryCandidates)
  }

  /**
   * Some Android Gradle ndk-build variants do not emit a text compile_commands.json, but instead
   * store equivalent compilation metadata in compile_commands.json.bin plus neighboring
   * android_gradle_build*.json files. This recovery path reconstructs a standard JSON compilation
   * database heuristically from those artifacts so clangd can still use the authoritative NDK /
   * sysroot / prefab flags instead of falling back to ACS-generated defaults.
   */
  private fun reconstructCompileCommandsFromBinary(projectDir: File, candidates: List<File>): File? {
    if (candidates.isEmpty()) {
      return null
    }

    val selected =
        candidates
            .distinctBy { it.absolutePath }
            .sortedWith(
                compareByDescending<File> { it.absolutePath.contains("/.cxx/") }
                    .thenByDescending { it.absolutePath.contains("/Debug/") }
                    .thenByDescending { it.absolutePath.contains("/RelWithDebInfo/") }
                    .thenByDescending { it.absolutePath.contains("/Release/") }
                    .thenBy { it.absolutePath.length }
            )
            .firstOrNull() ?: return null

    val reconstructed = reconstructCompileCommandsFromBinary(projectDir, selected)
    if (reconstructed != null) {
      ClangLogs.info(
          "Reconstructed compile_commands.json from ndk-build binary metadata: source={}, output={}",
          selected.absolutePath,
          reconstructed.absolutePath,
      )
    }
    return reconstructed
  }

  /**
   * Reconstruct a text compile_commands.json from ndk-build binary metadata and write it to an
   * ACS-managed derived directory under clanglsp/reconstructed/ instead of mutating project build
   * outputs. The binary metadata already encodes real compiler, target, sysroot, include and TU
   * file information; this function normalizes those fragments into standard clangd entries.
   */
  private fun reconstructCompileCommandsFromBinary(projectDir: File, binaryFile: File): File? {
    return try {
      val metadataDir = binaryFile.parentFile ?: return null
      val gradleBuildJson = File(metadataDir, "android_gradle_build.json")
      if (!gradleBuildJson.isFile) {
        ClangLogs.debug("Skipping binary compile db reconstruction because metadata json is missing: {}", gradleBuildJson.absolutePath)
        return null
      }

      val buildMetadata = JsonParser.parseString(gradleBuildJson.readText()).asJsonObject
      val toolchains = buildMetadata.getAsJsonObject("toolchains") ?: return null
      val firstToolchain = toolchains.entrySet().firstOrNull()?.value?.asJsonObject ?: return null
      val compiler = firstToolchain.get("cppCompilerExecutable")?.asString ?: return null

      val buildFiles = buildMetadata.getAsJsonArray("buildFiles")
      val directory =
          buildFiles?.firstOrNull()?.asString?.let { File(it).parentFile?.parentFile?.parentFile?.absolutePath }
              ?: projectDir.absolutePath

      val printableRuns = extractPrintableRuns(binaryFile.readBytes())
      val publicTokens = mutableListOf<String>()
      for (token in printableRuns) {
        if (token.endsWith(".cpp") || token.endsWith(".o")) {
          break
        }
        publicTokens.add(token)
      }

      val commandTokens = mutableListOf<String>()
      var started = false
      val seen = linkedSetOf<String>()
      publicTokens.forEach { token ->
        when {
          token == "C/C++ Build Metadata" -> return@forEach
          token.contains("clang++") && token.startsWith("/data/") -> {
            started = true
            if (seen.add(token)) {
              commandTokens.add(token)
            }
          }
          !started -> return@forEach
          token == "myapplication" -> return@forEach
          token.startsWith("/data/") -> {
            if (
                token.contains("sysroot") ||
                    token.contains("/src/main/cpp") ||
                    token.contains("/prefab/modules/")
            ) {
              val normalized =
                  if (token.contains("/src/main/cpp") || token.contains("/prefab/modules/")) {
                    "-I$token"
                  } else {
                    token
                  }
              if (seen.add(normalized)) {
                commandTokens.add(normalized)
              }
            }
          }
          else -> {
            if (seen.add(token)) {
              commandTokens.add(token)
            }
          }
        }
      }

      if (commandTokens.isEmpty()) {
        commandTokens.add(compiler)
      }

      val sourceFiles =
          printableRuns
              .filter { it.endsWith(".cpp") && it.contains("/src/main/cpp/") }
              .distinct()
      if (sourceFiles.isEmpty()) {
        return null
      }

      val entries = JsonArray()
      sourceFiles.forEach { sourceFile ->
        entries.add(
            JsonObject().apply {
              addProperty("directory", directory)
              addProperty("file", sourceFile)
              addProperty("command", (commandTokens + listOf("-c", sourceFile)).joinToString(" "))
            }
        )
      }

      val outDir =
          File(Environment.PREFIX, "clanglsp/reconstructed/${binaryFile.absolutePath.hashCode()}").apply {
            mkdirs()
          }
      val output = File(outDir, "compile_commands.json")
      output.writeText(entries.toString())
      output
    } catch (e: Exception) {
      ClangLogs.warn("Failed to reconstruct compile_commands from binary metadata: {}", binaryFile.absolutePath, e)
      null
    }
  }

  private fun extractPrintableRuns(bytes: ByteArray): List<String> {
    val runs = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
      if (current.length >= 4) {
        runs.add(normalizePrintableRun(current.toString()))
      }
      current.setLength(0)
    }

    bytes.forEach { byteValue ->
      val ch = byteValue.toInt() and 0xFF
      val printable = ch in 32..126 || ch == 9 || ch == 10 || ch == 13
      if (printable) {
        current.append(ch.toChar())
      } else {
        flush()
      }
    }
    flush()
    return runs
  }

  private fun normalizePrintableRun(value: String): String {
    val trimmed = value.trim()
    val includeIndex = trimmed.indexOf("-I/")
    if (includeIndex >= 0) {
      return trimmed.substring(includeIndex)
    }

    val anchors =
        listOf(
            "/data/",
            "-target",
            "--sysroot",
            "-f",
            "-W",
            "-D",
            "-U",
            "aarch64-",
            "myapplication",
        )
    anchors.forEach { anchor ->
      val idx = trimmed.indexOf(anchor)
      if (idx >= 0) {
        return trimmed.substring(idx)
      }
    }
    return trimmed
  }

  /**
   * Prefer the native compilation database that best matches the currently opened Android C/C++
   * translation units. In practice this means prioritizing entries that contain the project's
   * `main.cpp`, then preferring Android native build outputs (`.cxx`, `.externalNativeBuild`) over
   * generic locations, and finally biasing toward app/debug variants.
   */
  private fun selectBestCompileCommands(projectDir: File, candidates: List<File>): File? {
    if (candidates.isEmpty()) {
      return null
    }

    val projectSources = findSourceFiles(projectDir)
    val mainCpp = projectSources.firstOrNull { it.name.equals("main.cpp", ignoreCase = true) }

    return candidates
        .distinctBy { it.absolutePath }
        .sortedWith(
            compareByDescending<File> { candidateContainsSourceEntry(it, mainCpp) }
                .thenByDescending { it.absolutePath.contains("/.cxx/") }
                .thenByDescending { it.absolutePath.contains("/.externalNativeBuild/") }
                .thenByDescending { it.absolutePath.contains("/app/") }
                .thenByDescending { it.absolutePath.contains("/Debug/") }
                .thenByDescending { it.absolutePath.contains("/RelWithDebInfo/") }
                .thenByDescending { it.absolutePath.contains("/Release/") }
                .thenBy { it.absolutePath.length })
        .firstOrNull()
  }

  private fun candidateContainsSourceEntry(candidate: File, sourceFile: File?): Boolean {
    if (sourceFile == null || !candidate.isFile) {
      return false
    }

    return try {
      val entries = JsonParser.parseString(candidate.readText()).asJsonArray
      entries.any { element ->
        val obj = element.asJsonObject
        val filePath = obj.get("file")?.asString ?: return@any false
        filePath == sourceFile.absolutePath
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun findFilesByName(
      dir: File,
      name: String,
      maxDepth: Int,
      currentDepth: Int = 0,
      includeHiddenDirs: Boolean = false,
      excludedDirNames: Set<String> = EXCLUDED_DIR_NAMES,
  ): List<File> {
    if (currentDepth > maxDepth || !dir.isDirectory || dir.name in excludedDirNames) {
      return emptyList()
    }

    val matches = mutableListOf<File>()
    dir.listFiles()?.forEach { file ->
      when {
        file.isFile && file.name == name -> matches.add(file)
        file.isDirectory && (includeHiddenDirs || !file.name.startsWith(".")) -> {
          matches +=
              findFilesByName(
                  dir = file,
                  name = name,
                  maxDepth = maxDepth,
                  currentDepth = currentDepth + 1,
                  includeHiddenDirs = includeHiddenDirs,
                  excludedDirNames = excludedDirNames,
              )
        }
      }
    }

    return matches
  }

  private fun findSourceFiles(dir: File, maxDepth: Int = 6, currentDepth: Int = 0): List<File> {
    if (currentDepth > maxDepth || !dir.isDirectory || dir.name in EXCLUDED_DIR_NAMES) {
      return emptyList()
    }

    val sourceFiles = mutableListOf<File>()

    dir.listFiles()?.forEach { file ->
      when {
        file.isFile && file.extension.lowercase() in SOURCE_EXTENSIONS -> {
          sourceFiles.add(file)
        }
        file.isDirectory && !file.name.startsWith(".") -> {
          sourceFiles.addAll(findSourceFiles(file, maxDepth, currentDepth + 1))
        }
      }
    }

    return sourceFiles
  }

  private fun findProjectIncludeDirs(
      dir: File,
      maxDepth: Int = 6,
      currentDepth: Int = 0,
  ): List<File> {
    if (currentDepth > maxDepth || !dir.isDirectory || dir.name in EXCLUDED_DIR_NAMES) {
      return emptyList()
    }

    val result = linkedSetOf<File>()
    val children = dir.listFiles() ?: return emptyList()

    val hasHeader =
        children.any {
          it.isFile && it.extension.lowercase() in setOf("h", "hpp", "hh", "hxx")
        }
    val hasSource = children.any { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }

    if (dir.name.lowercase() in INCLUDE_DIR_NAMES || hasHeader || hasSource) {
      result.add(dir)
    }

    children.forEach { file ->
      if (file.isDirectory && !file.name.startsWith(".")) {
        result.addAll(findProjectIncludeDirs(file, maxDepth, currentDepth + 1))
      }
    }

    return result.sortedBy { it.absolutePath.length }
  }

  private fun buildClangCommand(file: File, projectIncludeDirs: List<File>): String {
    val clangPath = File(Environment.PREFIX, "bin/clang++").absolutePath
    val flags = mutableListOf<String>()
    flags.add("\"$clangPath\"")
    flags.addAll(buildCommonFlags(file, projectIncludeDirs, forFallback = false))
    flags.add("-c")
    flags.add("\"${file.absolutePath}\"")
    return flags.joinToString(" ")
  }

  private fun buildCommonFlags(
      file: File?,
      projectIncludeDirs: List<File>,
      forFallback: Boolean,
  ): List<String> {
    val flags = mutableListOf<String>()
    flags.add(languageStandardFlag(file))

    systemIncludePaths().forEach { path ->
      flags.add("-isystem")
      flags.add(if (forFallback) path else "\"$path\"")
    }

    projectIncludeDirs.forEach { dir ->
      flags.add("-I")
      flags.add(if (forFallback) dir.absolutePath else "\"${dir.absolutePath}\"")
    }

    flags.add("-resource-dir=${File(Environment.PREFIX, "lib/clang/20").absolutePath}")
    flags.add("-target")
    flags.add(detectTargetTriple())
    flags.add("-D__ANDROID__")
    flags.add("-DANDROID")
    flags.add("-D__TERMUX__")
    flags.add("-Wno-unused-parameter")
    flags.add("-Wno-unused-variable")
    flags.add("-Wno-unknown-pragmas")
    return flags
  }

  private fun languageStandardFlag(file: File?): String {
    val extension = file?.extension?.lowercase()
    return if (extension in CPP_EXTENSIONS || extension == null) "-std=c++17" else "-std=c11"
  }
  private fun systemIncludePaths(): List<String> {
    val includePaths =
        listOf(
            File(Environment.PREFIX, "include/c++/v1").absolutePath,
            File(Environment.PREFIX, "lib/clang/20/include").absolutePath,
            File(Environment.PREFIX, "include/aarch64-linux-android").absolutePath,
            File(Environment.PREFIX, "include").absolutePath,
        )

    return includePaths.filter { File(it).exists() }
  }


  private fun detectTargetTriple(): String {
    return "aarch64-unknown-linux-android24"
  }
  private fun createInitParams(workspaceRoot: String, compilationDatabasePath: String?): JsonObject {
    val projectIncludeDirs = findProjectIncludeDirs(workspace.getProjectDir())
    val fallbackFlags =
        buildCommonFlags(
            file = null,
            projectIncludeDirs = projectIncludeDirs,
            forFallback = true,
        )

    return JsonObject().apply {

      addProperty("processId", android.os.Process.myPid())
      addProperty("rootUri", workspaceRoot)

      add(
          "capabilities",
          JsonObject().apply {
            add(
                "textDocument",
                JsonObject().apply {
                  add(
                      "completion",
                      JsonObject().apply {
                        add(
                            "completionItem",
                            JsonObject().apply {
                              addProperty("snippetSupport", true)
                              addProperty("commitCharactersSupport", true)
                              add(
                                  "documentationFormat",
                                  JsonArray().apply {
                                    add("plaintext")
                                    add("markdown")
                                  },
                              )
                              addProperty("deprecatedSupport", true)
                              addProperty("preselectSupport", true)
                            },
                        )
                        addProperty("contextSupport", true)
                      },
                  )
                  add(
                      "hover",
                      JsonObject().apply {
                        add(
                            "contentFormat",
                            JsonArray().apply {
                              add("plaintext")
                              add("markdown")
                            },
                        )
                      },
                  )
                  add(
                      "signatureHelp",
                      JsonObject().apply {
                        add(
                            "signatureInformation",
                            JsonObject().apply {
                              add(
                                  "documentationFormat",
                                  JsonArray().apply {
                                    add("plaintext")
                                    add("markdown")
                                  },
                              )
                            },
                        )
                      },
                  )
                  add("definition", JsonObject().apply { addProperty("linkSupport", true) })
                  add("references", JsonObject())
                  add("documentHighlight", JsonObject())
                  add("documentSymbol", JsonObject())
                  add("codeAction", JsonObject())
                  add("codeLens", JsonObject())
                  add("formatting", JsonObject())
                  add("rangeFormatting", JsonObject())
                  add("onTypeFormatting", JsonObject())
                  add("rename", JsonObject())
                  add(
                      "publishDiagnostics",
                      JsonObject().apply { addProperty("relatedInformation", true) },
                  )
                },
            )

            add(
                "workspace",
                JsonObject().apply {
                  addProperty("applyEdit", true)
                  add("workspaceEdit", JsonObject().apply { addProperty("documentChanges", true) })
                  add(
                      "didChangeConfiguration",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                  add(
                      "didChangeWatchedFiles",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                  add("symbol", JsonObject().apply { addProperty("dynamicRegistration", true) })
                  add(
                      "executeCommand",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                },
            )
          },
      )

      add(
          "initializationOptions",
          JsonObject().apply {
            addProperty("clangdFileStatus", true)
            addProperty("compilationDatabasePath", compilationDatabasePath ?: "${Environment.PREFIX}/clanglsp/build")

            add(
                "fallbackFlags",
                JsonArray().apply {
                  fallbackFlags.forEach { add(it) }
                },
            )

          },
      )
    }
  }
}
