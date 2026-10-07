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
package com.tom.rv2ide.lsp.java.kotlin;

import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.projects.android.AndroidModule;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Locates existing Kotlin JVM class directories for the selected Android variant. */
public final class KotlinClassOutputProvider {

  private static final Logger LOG = LoggerFactory.getLogger(KotlinClassOutputProvider.class);
  private static final Map<String, CachedTypes> TYPE_CACHE = new ConcurrentHashMap<>();

  private KotlinClassOutputProvider() {}

  /**
   * Returns only materialized outputs. This method never creates directories and never invokes
   * Gradle/Kotlin compilation.
   */
  public static Set<File> findCompileOutputs(ModuleProject module) {
    final Set<File> outputs = new LinkedHashSet<>(findModuleCompileOutputs(module));
    outputs.addAll(findDependencyCompileOutputs(module));
    return outputs;
  }

  /** Returns materialized Kotlin outputs belonging to {@code module} itself. */
  public static Set<File> findModuleCompileOutputs(ModuleProject module) {
    final Set<File> outputs = new LinkedHashSet<>();
    if (module != null) {
      addModuleOutputs(module, outputs);
    }
    return outputs;
  }

  /**
   * Returns materialized Kotlin outputs of compile dependencies, excluding {@code module} itself.
   *
   * <p>The current module's Kotlin source is represented by in-memory ABI stubs so unsaved edits,
   * additions and deletions take precedence over stale build output. Dependencies do not have that
   * live source view in the current Java compiler and therefore remain classpath-backed.
   */
  public static Set<File> findDependencyCompileOutputs(ModuleProject module) {
    final Set<File> outputs = new LinkedHashSet<>();
    if (module == null) {
      return outputs;
    }
    for (ModuleProject dependency : module.getCompileModuleProjects()) {
      addModuleOutputs(dependency, outputs);
    }
    return outputs;
  }

  /**
   * Lists top-level JVM types from directory-form Kotlin compiler outputs.
   *
   * <p>The project classpath index reads jars only. javac can consume these directories directly,
   * but completion needs this parallel directory scan to expose the same Kotlin classes.
   */
  public static Set<String> publicTopLevelTypes(ModuleProject module) {
    final Set<String> types = new LinkedHashSet<>();
    for (File output : findCompileOutputs(module)) {
      types.addAll(typesInDirectory(output));
    }
    return types;
  }

  /** Lists top-level types from dependency Kotlin output only; see {@link #findDependencyCompileOutputs}. */
  public static Set<String> publicDependencyTopLevelTypes(ModuleProject module) {
    final Set<String> types = new LinkedHashSet<>();
    for (File output : findDependencyCompileOutputs(module)) {
      types.addAll(typesInDirectory(output));
    }
    return types;
  }

  public static void clearCache() {
    TYPE_CACHE.clear();
  }

  private static Set<String> typesInDirectory(File output) {
    final String cacheKey = output.getAbsolutePath();
    final long lastModified = output.lastModified();
    final CachedTypes cached = TYPE_CACHE.get(cacheKey);
    if (cached != null && cached.lastModified == lastModified) {
      return cached.types;
    }

    final Set<String> types = new LinkedHashSet<>();
    final Path root = output.toPath();
    try (Stream<Path> files = Files.walk(root)) {
      files.filter(path -> path.getFileName().toString().endsWith(".class"))
          .map(path -> binaryName(root, path))
          .filter(name -> name != null && name.indexOf('$') < 0)
          .forEach(types::add);
    } catch (IOException error) {
      LOG.debug("Unable to index Kotlin class output {}", output, error);
    }

    final Set<String> immutable = Collections.unmodifiableSet(types);
    TYPE_CACHE.put(cacheKey, new CachedTypes(lastModified, immutable));
    return immutable;
  }

  private static String binaryName(Path root, Path classFile) {
    final String name = root.relativize(classFile).toString().replace(File.separatorChar, '.');
    return name.substring(0, name.length() - ".class".length());
  }

  private static void addModuleOutputs(ModuleProject module, Set<File> outputs) {
    if (!(module instanceof AndroidModule)) {
      return;
    }
    final AndroidModule androidModule = (AndroidModule) module;
    final com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata variant =
        androidModule.getSelectedVariant();
    if (variant == null) {
      return;
    }
    final String variantName = variant.getName();
    if (variantName == null || variantName.isEmpty()) {
      return;
    }
    final File buildDir = androidModule.getBuildDir();
    addIfDirectory(outputs, new File(buildDir, "tmp/kotlin-classes/" + variantName));
    addIfDirectory(outputs, new File(buildDir, "tmp/kapt3/classes/" + variantName));
  }

  private static void addIfDirectory(Set<File> outputs, File candidate) {
    if (candidate.isDirectory()) {
      outputs.add(candidate);
    }
  }

  private static final class CachedTypes {
    final long lastModified;
    final Set<String> types;

    CachedTypes(long lastModified, Set<String> types) {
      this.lastModified = lastModified;
      this.types = types;
    }
  }
}