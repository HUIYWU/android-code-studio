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

import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.utils.DocumentUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A lightweight Kotlin-as-Java type index used by the Java language server.
 *
 * <p>This deliberately does not expose Kotlin files through javac's {@code SOURCE_PATH}. javac
 * cannot parse Kotlin source. It only supplies JVM-visible top-level names to Java completion and
 * import-oriented features. A future ABI/stub provider can replace this scanner without changing
 * those consumers.
 */
public final class KotlinJvmTypeIndex {

  private static final Logger LOG = LoggerFactory.getLogger(KotlinJvmTypeIndex.class);
  private static final AliasCacheStore<ModuleProject, RevisionSnapshotStore<Set<String>>> CACHE =
      new AliasCacheStore<>();
  private static final AliasCacheStore<ModuleProject, RevisionSnapshotStore<CachedAliases>> ALIAS_CACHE =
      new AliasCacheStore<>();
  private static final AliasCacheStore<ModuleProject, RevisionSnapshotStore<NavigationCandidateIndex>>
      NAVIGATION_CACHE = new AliasCacheStore<>();
  private static final AliasCacheStore<ModuleProject, RevisionSnapshotStore<List<Path>>>
      SOURCE_FILE_CACHE = new AliasCacheStore<>();

  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)");
  private static final Pattern FILE_JVM_NAME_PATTERN =
      Pattern.compile("(?m)^\\s*@file:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)");
  private static final Pattern FILE_JVM_MULTIFILE_PATTERN =
      Pattern.compile("(?m)^\\s*@file:JvmMultifileClass(?:\\s|$)");
  private static final Pattern IMPORT_PATTERN = Pattern.compile(
      "(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(?:\\s+as\\s+([A-Za-z_$][\\w$]*))?\\s*$");
  private static final Pattern TYPE_ALIAS_PATTERN = Pattern.compile(
      "(?m)^\\s*((?:(?:public|internal|private)\\s+)*)typealias\\s+([A-Za-z_][\\w]*)\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern GENERIC_TYPE_ALIAS_PATTERN = Pattern.compile(
      "(?m)^\\s*((?:(?:public|internal|private)\\s+)*)typealias\\s+([A-Za-z_][\\w]*)\\s*<([^<>]+)>\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern TYPE_NAME_PATTERN =
      Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");

  private static final Pattern TYPE_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|abstract|sealed|data|enum|"
              + "annotation|value|expect|actual)\\s+)*)"
              + "(?:class|interface|object)\\s+([A-Za-z_][\\w]*)");
  private static final Pattern TOP_LEVEL_MEMBER_PATTERN =
      Pattern.compile("^\\s*(?:(?:public|private|protected|internal|inline|suspend|const|lateinit|"
          + "tailrec|operator|infix|external|override)\\s+)*(?:fun|val|var)\\s+");

  private KotlinJvmTypeIndex() {}

  /** Returns Kotlin declarations which Java can use as top-level class names or file facades. */
  public static Set<String> publicTopLevelTypes(ModuleProject module) {
    if (!isRecognitionEnabled() || module == null) {
      return Collections.emptySet();
    }

    while (true) {
      final CompileSourceRevision revision = compileSourceRevision(module);
      final RevisionSnapshotStore<Set<String>> store = topLevelTypeSnapshotStore(module);
      final Set<String> cached = store.get(revision);
      if (cached != null) {
        LOG.debug("Reused Kotlin JVM top-level type index for module {} revision {}: types={}",
            module.getPath(), revision, cached.size());
        return cached;
      }

      // Scanning every Kotlin source file is a cold/revision-change operation. Keep it per-module
      // so concurrent completion/import requests share one immutable published result.
      synchronized (module) {
        final CompileSourceRevision lockedRevision = compileSourceRevision(module);
        final Set<String> lockedCached = store.get(lockedRevision);
        if (lockedCached != null) return lockedCached;

        final long startedAt = System.nanoTime();
        final Set<String> indexed = new LinkedHashSet<>();
        for (Path path : kotlinSourceFilesForRevision(module, lockedRevision)) {
          indexFile(path, indexed);
        }

        final long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        if (!lockedRevision.equals(compileSourceRevision(module))) {
          LOG.debug("Discarded Kotlin JVM top-level type index for module {} revision {} after {} ms; compile source revision changed during construction", module.getPath(), lockedRevision, elapsedMillis);
          continue;
        }
        final Set<String> immutable = Collections.unmodifiableSet(indexed);
        store.replace(lockedRevision, immutable);
        LOG.debug("Built Kotlin JVM top-level type index for module {} revision {}: types={} in {} ms", module.getPath(), lockedRevision, immutable.size(), elapsedMillis);
        return immutable;
      }
    }
  }

  /**
   * Finds the source declaration for a JVM-visible Kotlin top-level type or file facade.
   * This is intentionally used only by explicit navigation requests; completion continues to use
   * the cached name-only index above.
   */
  public static List<KotlinTypeDeclaration> findMultifileDeclarations(
      ModuleProject module, String qualifiedName) {
    if (!isRecognitionEnabled() || module == null || qualifiedName == null || qualifiedName.isEmpty()) {
      return Collections.emptyList();
    }
    return navigationCandidatesForRevision(module).multifileDeclarations(qualifiedName);
  }

  public static java.util.Map<String, GenericTypeAlias> visibleGenericTypeAliases(
      ModuleProject module, Path consumerFile) {
    if (!isRecognitionEnabled() || module == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = sourceForIndexedPath(consumerFile);
    if (consumerSource == null) return Collections.emptyMap();
    final CachedAliases cached = aliasCacheWithDeclarationIndexForRevision(module);
    final java.util.Map<String, GenericTypeAlias> existing =
        cached.genericAliases.get(consumerFile, consumerSource);
    if (existing != null) {
      LOG.debug("Reused Kotlin generic alias visibility for module {} revision {} consumer {}: aliases={}",
          module.getPath(), compileSourceRevision(module), normalizedPath(consumerFile), existing.size());
      return existing;
    }
    final java.util.Map<String, GenericTypeAlias> indexed =
        cached.declarationIndex.visibleGenericAliases(consumerFile, consumerSource);
    cached.genericAliases.put(consumerFile, consumerSource, indexed);
    LOG.debug("Computed Kotlin generic alias visibility for module {} revision {} consumer {}: aliases={}",
        module.getPath(), compileSourceRevision(module), normalizedPath(consumerFile), indexed.size());
    return indexed;
  }

  static java.util.Map<String, GenericTypeAlias> visibleGenericTypeAliases(
      Iterable<java.io.File> sourceRoots, Path consumerFile) {
    if (sourceRoots == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = FileManager.INSTANCE.getDocumentContents(consumerFile).toString();
    // Keep this compatibility entry point semantically identical to the revision-indexed path,
    // including Kotlin import aliases and conservative conflict handling.
    return AliasDeclarationIndex.build(sourceRoots)
        .visibleGenericAliases(consumerFile, consumerSource);
  }

  public static java.util.Map<String, String> visibleDirectTypeAliases(
      ModuleProject module, Path consumerFile) {
    if (!isRecognitionEnabled() || module == null || consumerFile == null) {
      return Collections.emptyMap();
    }
    final String consumerSource = sourceForIndexedPath(consumerFile);
    if (consumerSource == null) return Collections.emptyMap();
    final CachedAliases cached = aliasCacheWithDeclarationIndexForRevision(module);
    final java.util.Map<String, String> existing =
        cached.directAliases.get(consumerFile, consumerSource);
    if (existing != null) {
      LOG.debug("Reused Kotlin direct alias visibility for module {} revision {} consumer {}: aliases={}",
          module.getPath(), compileSourceRevision(module), normalizedPath(consumerFile), existing.size());
      return existing;
    }
    final java.util.Map<String, String> indexed =
        cached.declarationIndex.visibleDirectAliases(consumerFile, consumerSource);
    cached.directAliases.put(consumerFile, consumerSource, indexed);
    LOG.debug("Computed Kotlin direct alias visibility for module {} revision {} consumer {}: aliases={}",
        module.getPath(), compileSourceRevision(module), normalizedPath(consumerFile), indexed.size());
    return indexed;
  }

  static java.util.Map<String, String> visibleDirectTypeAliases(
      Iterable<java.io.File> sourceRoots, Path consumerFile, String consumerSource) {
    if (sourceRoots == null || consumerFile == null || consumerSource == null) {
      return Collections.emptyMap();
    }
    // Keep this compatibility entry point semantically identical to the revision-indexed path,
    // including Kotlin import aliases and conservative conflict handling.
    return AliasDeclarationIndex.build(sourceRoots)
        .visibleDirectAliases(consumerFile, consumerSource);
  }

  public static KotlinTypeDeclaration findDeclaration(ModuleProject module, String qualifiedName) {
    if (!isRecognitionEnabled() || module == null || qualifiedName == null || qualifiedName.isEmpty()) {
      return null;
    }
    return navigationCandidatesForRevision(module).declaration(qualifiedName);
  }

  /** Kotlin source discovery is opt-in; callers must not create scanner/cache work while disabled. */
  private static boolean isRecognitionEnabled() {
    return JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled();
  }

  private static CachedAliases aliasCacheWithDeclarationIndexForRevision(ModuleProject module) {
    while (true) {
      final CompileSourceRevision revision = compileSourceRevision(module);
      final RevisionSnapshotStore<CachedAliases> store = aliasSnapshotStore(module);
      final CachedAliases existing = store.get(revision);
      if (existing != null) {
        LOG.debug("Reused Kotlin typealias declaration index for module {} revision {}: direct={} generic={}",
            module.getPath(), revision, existing.declarationIndex.directAliasCount(),
            existing.declarationIndex.genericAliasCount());
        return existing;
      }
      // Index construction reads many source files. Serialize only this cold/revision-change path
      // per module so concurrent first consumers publish one complete immutable snapshot.
      synchronized (module) {
        final CompileSourceRevision lockedRevision = compileSourceRevision(module);
        final CachedAliases lockedExisting = store.get(lockedRevision);
        if (lockedExisting != null) return lockedExisting;
        final long startedAt = System.nanoTime();
        final AliasDeclarationIndex indexed = AliasDeclarationIndex.buildFromPaths(
            kotlinSourceFilesForRevision(module, lockedRevision));
        final long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        // Do not publish a snapshot that was built while the module source revision changed.
        if (!lockedRevision.equals(compileSourceRevision(module))) {
          LOG.debug("Discarded Kotlin typealias declaration index for module {} revision {} after {} ms; compile source revision changed during construction", module.getPath(), lockedRevision, elapsedMillis);
          continue;
        }
        final CachedAliases replacement = new CachedAliases(indexed);
        store.replace(lockedRevision, replacement);
        LOG.debug("Built Kotlin typealias declaration index for module {} revision {}: direct={} generic={} in {} ms", module.getPath(), lockedRevision, indexed.directAliasCount(), indexed.genericAliasCount(), elapsedMillis);
        return replacement;
      }
    }
  }

  private static NavigationCandidateIndex navigationCandidatesForRevision(ModuleProject module) {
    while (true) {
      final CompileSourceRevision revision = compileSourceRevision(module);
      final RevisionSnapshotStore<NavigationCandidateIndex> store = navigationSnapshotStore(module);
      final NavigationCandidateIndex existing = store.get(revision);
      if (existing != null) {
        LOG.debug("Reused Kotlin JVM navigation candidate index for module {} revision {}: declarations={} multifileDeclarations={}",
            module.getPath(), revision, existing.declarationCount(), existing.multifileDeclarationCount());
        return existing;
      }

      synchronized (module) {
        final CompileSourceRevision lockedRevision = compileSourceRevision(module);
        final NavigationCandidateIndex lockedExisting = store.get(lockedRevision);
        if (lockedExisting != null) return lockedExisting;

        final long startedAt = System.nanoTime();
        final NavigationCandidateIndex indexed = NavigationCandidateIndex.buildFromPaths(
            kotlinSourceFilesForRevision(module, lockedRevision));
        final long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        if (!lockedRevision.equals(compileSourceRevision(module))) {
          LOG.debug("Discarded Kotlin JVM navigation candidate index for module {} revision {} after {} ms; compile source revision changed during construction", module.getPath(), lockedRevision, elapsedMillis);
          continue;
        }
        store.replace(lockedRevision, indexed);
        LOG.debug("Built Kotlin JVM navigation candidate index for module {} revision {}: declarations={} multifileDeclarations={} in {} ms", module.getPath(), lockedRevision, indexed.declarationCount(), indexed.multifileDeclarationCount(), elapsedMillis);
        return indexed;
      }
    }
  }

  /**
   * Returns a deterministic revision key for every module whose own source directory can enter
   * {@link ModuleProject#getCompileSourceDirectories()}. A consumer module may index Kotlin from
   * transitive compile dependencies, so its own revision alone is not a sufficient cache gate.
   */
  static CompileSourceRevision compileSourceRevision(ModuleProject module) {
    if (module == null) return CompileSourceRevision.empty();
    final java.util.LinkedHashMap<String, Long> revisions = new java.util.LinkedHashMap<>();
    collectCompileSourceRevision(module, revisions);
    try {
      for (ModuleProject dependency : module.getCompileModuleProjects()) {
        collectCompileSourceRevision(dependency, revisions);
      }
    } catch (RuntimeException error) {
      // A transient workspace/dependency-model failure must not block Java LSP. The consumer's own
      // revision remains a safe lower bound and the next request can obtain the complete graph.
      LOG.debug("Unable to enumerate compile source dependencies for module {}", module.getPath(), error);
    }
    return CompileSourceRevision.from(revisions);
  }

  private static void collectCompileSourceRevision(
      ModuleProject module, java.util.Map<String, Long> revisions) {
    if (module == null || module.getPath() == null) return;
    revisions.put(module.getPath(), module.getSourceIndexVersion());
  }

  /** Immutable, collision-free compile-source revision vector, ordered by Gradle module path. */
  static final class CompileSourceRevision {
    private static final CompileSourceRevision EMPTY = new CompileSourceRevision(Collections.emptyList());
    private final List<ModuleRevision> modules;

    private CompileSourceRevision(List<ModuleRevision> modules) {
      this.modules = modules;
    }

    static CompileSourceRevision empty() {
      return EMPTY;
    }

    static CompileSourceRevision from(java.util.Map<String, Long> revisions) {
      if (revisions == null || revisions.isEmpty()) return EMPTY;
      final ArrayList<ModuleRevision> result = new ArrayList<>();
      for (java.util.Map.Entry<String, Long> entry : revisions.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          result.add(new ModuleRevision(entry.getKey(), entry.getValue()));
        }
      }
      result.sort(Comparator.comparing((ModuleRevision entry) -> entry.path));
      return result.isEmpty() ? EMPTY : new CompileSourceRevision(
          Collections.unmodifiableList(result));
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CompileSourceRevision
          && modules.equals(((CompileSourceRevision) other).modules);
    }

    @Override
    public int hashCode() {
      return modules.hashCode();
    }

    @Override
    public String toString() {
      return modules.toString();
    }
  }

  private static final class ModuleRevision {
    final String path;
    final long revision;

    ModuleRevision(String path, long revision) {
      this.path = path;
      this.revision = revision;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof ModuleRevision)) return false;
      final ModuleRevision that = (ModuleRevision) other;
      return revision == that.revision && path.equals(that.path);
    }

    @Override
    public int hashCode() {
      return 31 * path.hashCode() + Long.hashCode(revision);
    }

    @Override
    public String toString() {
      return path + "@" + revision;
    }
  }

  private static List<Path> kotlinSourceFilesForRevision(
      ModuleProject module, CompileSourceRevision revision) {
    final RevisionSnapshotStore<List<Path>> store = sourceFileSnapshotStore(module);
    final List<Path> existing = store.get(revision);
    if (existing != null) {
      LOG.debug("Reused Kotlin source-file snapshot for module {} revision {}: files={}",
          module.getPath(), revision, existing.size());
      return existing;
    }

    // Callers already hold the module lock while building a derived snapshot. Keeping this method
    // lock-free prevents lock-order surprises and lets all derived indexes share one file listing.
    final long startedAt = System.nanoTime();
    final List<Path> immutable = kotlinSourceFiles(module.getCompileSourceDirectories());
    final long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
    if (revision.equals(compileSourceRevision(module))) {
      store.replace(revision, immutable);
      LOG.debug("Built Kotlin source-file snapshot for module {} revision {}: files={} in {} ms",
          module.getPath(), revision, immutable.size(), elapsedMillis);
    } else {
      LOG.debug("Discarded Kotlin source-file snapshot for module {} revision {} after {} ms; source revision changed during construction",
          module.getPath(), revision, elapsedMillis);
    }
    return immutable;
  }

  static List<Path> kotlinSourceFiles(Iterable<java.io.File> sourceRoots) {
    final Set<Path> files = new LinkedHashSet<>();
    if (sourceRoots != null) {
      for (java.io.File root : sourceRoots) {
        if (root == null || !root.isDirectory()) continue;
        try (Stream<Path> paths = Files.walk(root.toPath())) {
          paths.filter(DocumentUtils::isKotlinFile)
              .filter(path -> path.getFileName().toString().endsWith(".kt"))
              .map(KotlinJvmTypeIndex::normalizedPath)
              .forEach(files::add);
        } catch (IOException | SecurityException error) {
          // A generated, detached, or permission-restricted source root must not prevent the
          // remaining module roots from supplying Java-visible Kotlin declarations.
          LOG.debug("Unable to list Kotlin source root {}", root, error);
        }
      }
    }
    if (files.isEmpty()) return Collections.emptyList();
    final java.util.ArrayList<Path> ordered = new java.util.ArrayList<>(files);
    Collections.sort(ordered, (left, right) -> left.toString().compareTo(right.toString()));
    return Collections.unmodifiableList(ordered);
  }

  private static RevisionSnapshotStore<List<Path>> sourceFileSnapshotStore(ModuleProject module) {
    RevisionSnapshotStore<List<Path>> store = SOURCE_FILE_CACHE.get(module);
    if (store != null) return store;
    final RevisionSnapshotStore<List<Path>> created = new RevisionSnapshotStore<>();
    SOURCE_FILE_CACHE.putIfAbsent(module, created);
    final RevisionSnapshotStore<List<Path>> published = SOURCE_FILE_CACHE.get(module);
    return published == null ? created : published;
  }

  private static RevisionSnapshotStore<NavigationCandidateIndex> navigationSnapshotStore(
      ModuleProject module) {
    RevisionSnapshotStore<NavigationCandidateIndex> store = NAVIGATION_CACHE.get(module);
    if (store != null) return store;
    final RevisionSnapshotStore<NavigationCandidateIndex> created = new RevisionSnapshotStore<>();
    NAVIGATION_CACHE.putIfAbsent(module, created);
    final RevisionSnapshotStore<NavigationCandidateIndex> published = NAVIGATION_CACHE.get(module);
    return published == null ? created : published;
  }

  private static RevisionSnapshotStore<Set<String>> topLevelTypeSnapshotStore(ModuleProject module) {
    RevisionSnapshotStore<Set<String>> store = CACHE.get(module);
    if (store != null) return store;
    final RevisionSnapshotStore<Set<String>> created = new RevisionSnapshotStore<>();
    CACHE.putIfAbsent(module, created);
    final RevisionSnapshotStore<Set<String>> published = CACHE.get(module);
    return published == null ? created : published;
  }

  private static RevisionSnapshotStore<CachedAliases> aliasSnapshotStore(ModuleProject module) {
    RevisionSnapshotStore<CachedAliases> store = ALIAS_CACHE.get(module);
    if (store != null) return store;
    final RevisionSnapshotStore<CachedAliases> created = new RevisionSnapshotStore<>();
    ALIAS_CACHE.putIfAbsent(module, created);
    final RevisionSnapshotStore<CachedAliases> published = ALIAS_CACHE.get(module);
    return published == null ? created : published;
  }

  /**
   * Revision gate shared by the module cache path and unit tests. It never returns a snapshot from
   * a different source revision, so a caller must replace rather than mutate stale state.
   */
  static final class RevisionSnapshotStore<T> {
    // Publish revision and snapshot as one immutable state. Separate volatile fields could expose a
    // new revision together with the previous snapshot to a concurrent reader between writes.
    private volatile RevisionSnapshot<T> current;

    T get(Object requestedRevision) {
      final RevisionSnapshot<T> snapshot = current;
      return snapshot != null && snapshot.revision.equals(requestedRevision) ? snapshot.value : null;
    }

    synchronized T replace(Object requestedRevision, T replacement) {
      if (requestedRevision == null) throw new IllegalArgumentException("requestedRevision == null");
      if (replacement == null) throw new IllegalArgumentException("replacement == null");
      current = new RevisionSnapshot<>(requestedRevision, replacement);
      return replacement;
    }

    synchronized void clear() {
      current = null;
    }
  }

  private static final class RevisionSnapshot<T> {
    final Object revision;
    final T value;

    RevisionSnapshot(Object revision, T value) {
      this.revision = revision;
      this.value = value;
    }
  }

  /** Removes cached Kotlin source symbols and alias visibility results for a module. */
  public static void invalidate(ModuleProject module) {
    if (module != null) {
      CACHE.remove(module);
      ALIAS_CACHE.remove(module);
      NAVIGATION_CACHE.remove(module);
      SOURCE_FILE_CACHE.remove(module);
    }
  }

  public static void clear() {
    CACHE.clear();
    ALIAS_CACHE.clear();
    NAVIGATION_CACHE.clear();
    SOURCE_FILE_CACHE.clear();
  }

  private static String sourceForIndexedPath(Path path) {
    if (path == null) return null;
    try {
      if (!Files.isRegularFile(path)) return null;
      return FileManager.INSTANCE.getDocumentContents(path).toString();
    } catch (RuntimeException error) {
      // A file can disappear after the immutable path snapshot is published. Preserve useful
      // results from the other files; a source-index revision will rebuild this snapshot later.
      LOG.debug("Unable to read Kotlin source path {} while building index", path, error);
      return null;
    }
  }

  private static void indexFile(Path path, Set<String> result) {
    final String source = sourceForIndexedPath(path);
    if (source == null) return;

    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final List<KotlinJvmSyntaxParser.TopLevelTypeSyntax> syntaxTypes =
        KotlinJvmSyntaxParser.findTopLevelTypes(source);
    final List<KotlinJvmSyntaxParser.MemberSyntax> syntaxMembers =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    final boolean hasTopLevelMember;

    if (syntaxTypes != null && syntaxMembers != null) {
      for (KotlinJvmSyntaxParser.TopLevelTypeSyntax type : syntaxTypes) {
        if (!type.privateType && type.name != null && !type.name.isEmpty()) {
          result.add(qualifiedName(packageName, type.name));
        }
      }
      hasTopLevelMember = hasPublicTopLevelMember(syntaxMembers);
    } else {
      // Retain the previous scanner only for devices where the native grammar is unavailable.
      hasTopLevelMember = indexFileFallback(source, packageName, result);
    }

    if (hasTopLevelMember) {
      final Matcher jvmNameMatcher = FILE_JVM_NAME_PATTERN.matcher(source);
      final String facade = jvmNameMatcher.find()
          ? jvmNameMatcher.group(1)
          : path.getFileName().toString().substring(0, path.getFileName().toString().length() - 3) + "Kt";
      result.add(qualifiedName(packageName, facade));
    }
  }

  private static void collectVisibleGenericAliases(
      Path path,
      String consumerPackage,
      Set<String> explicitlyImported,
      java.util.Map<String, GenericTypeAlias> result) {
    final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Matcher matcher = GENERIC_TYPE_ALIAS_PATTERN.matcher(source);
    while (matcher.find()) {
      final String modifiers = matcher.group(1);
      final String name = matcher.group(2);
      final String qualifiedAlias = qualifiedName(packageName, name);
      final String target = matcher.group(4).trim();
      if (modifiers.contains("private") || modifiers.contains("internal")
          || result.containsKey(name)
          || !(packageName.equals(consumerPackage) || explicitlyImported.contains(qualifiedAlias))
          || target.endsWith("?") || target.indexOf("->") >= 0 || target.indexOf('&') >= 0
          || target.indexOf('|') >= 0) {
        continue;
      }
      final java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
      boolean valid = true;
      for (String parameter : KotlinJvmTypeProjection.splitTopLevelArguments(matcher.group(3))) {
        final String trimmed = parameter.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) { valid = false; break; }
        parameters.add(trimmed);
      }
      final int open = target.indexOf('<');
      if (!valid || parameters.isEmpty() || open < 1 || !target.endsWith(">")) continue;
      final String raw = target.substring(0, open).trim();
      if (raw.indexOf('.') >= 0) continue;
      final java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
      for (String argument : KotlinJvmTypeProjection.splitTopLevelArguments(
          target.substring(open + 1, target.length() - 1))) {
        final String trimmed = argument.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) { valid = false; break; }
        arguments.add(trimmed);
      }
      if (valid) result.put(name, new GenericTypeAlias(parameters, raw, arguments));
    }
  }

  // Generic argument splitting is shared with generation and navigation.

  private static void collectVisibleAliases(
      Path path,
      String consumerPackage,
      Set<String> explicitlyImported,
      java.util.Map<String, String> result) {
    final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final Matcher aliasMatcher = TYPE_ALIAS_PATTERN.matcher(source);
    while (aliasMatcher.find()) {
      final String modifiers = aliasMatcher.group(1);
      final String name = aliasMatcher.group(2);
      final String target = aliasMatcher.group(3).trim();
      final String qualifiedAlias = qualifiedName(packageName, name);
      if (modifiers.contains("private") || modifiers.contains("internal") || result.containsKey(name)
          || !(packageName.equals(consumerPackage) || explicitlyImported.contains(qualifiedAlias))
          || result.containsKey(target) || !isDirectAliasTarget(target)) {
        continue;
      }
      result.put(name, target);
    }
  }

  private static boolean isVisibleGenericAlias(
      String modifiers,
      String packageName,
      String consumerPackage,
      Set<String> explicitlyImported,
      String qualifiedAlias,
      String target,
      boolean duplicate) {
    return !modifiers.contains("private") && !modifiers.contains("internal") && !duplicate
        && (packageName.equals(consumerPackage) || explicitlyImported.contains(qualifiedAlias))
        && !target.endsWith("?") && target.indexOf("->") < 0
        && target.indexOf('&') < 0 && target.indexOf('|') < 0;
  }

  private static boolean isDirectAliasTarget(String target) {
    return !target.endsWith("?") && target.indexOf("->") < 0 && target.indexOf('&') < 0
        && target.indexOf('|') < 0 && target.indexOf('(') < 0 && target.indexOf(')') < 0;
  }

  private static KotlinTypeDeclaration findDeclarationInFile(Path path, String qualifiedName) {
    final String source = FileManager.INSTANCE.getDocumentContents(path).toString();
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final List<KotlinJvmSyntaxParser.TopLevelTypeSyntax> syntaxTypes =
        KotlinJvmSyntaxParser.findTopLevelTypes(source);
    final List<KotlinJvmSyntaxParser.MemberSyntax> syntaxMembers =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    final boolean hasTopLevelMember;

    if (syntaxTypes != null && syntaxMembers != null) {
      for (KotlinJvmSyntaxParser.TopLevelTypeSyntax type : syntaxTypes) {
        if (!type.privateType && qualifiedName.equals(qualifiedName(packageName, type.name))) {
          return new KotlinTypeDeclaration(path, type.nameOffset, type.nameLength);
        }
      }
      hasTopLevelMember = hasPublicTopLevelMember(syntaxMembers);
    } else {
      final KotlinTypeDeclaration fallback = findTypeDeclarationFallback(
          path, source, packageName, qualifiedName);
      if (fallback != null) {
        return fallback;
      }
      hasTopLevelMember = hasPublicTopLevelMemberFallback(source);
    }

    if (!hasTopLevelMember) {
      return null;
    }
    final Matcher jvmNameMatcher = FILE_JVM_NAME_PATTERN.matcher(source);
    final String facade = jvmNameMatcher.find()
        ? jvmNameMatcher.group(1)
        : path.getFileName().toString().substring(0, path.getFileName().toString().length() - 3) + "Kt";
    if (!qualifiedName.equals(qualifiedName(packageName, facade))) {
      return null;
    }
    final int declarationOffset = jvmNameMatcher.find(0) ? jvmNameMatcher.start(1) : 0;
    return new KotlinTypeDeclaration(path, declarationOffset, facade.length());
  }

  /**
   * Immutable module-revision navigation metadata. It stores only file paths and declaration
   * ranges, never source text or syntax trees. This preserves exact navigation lookup without
   * repeating a source-root walk for each Java definition request.
   */
  static final class NavigationCandidateIndex {
    private final java.util.Map<String, KotlinTypeDeclaration> declarations;
    private final java.util.Map<String, List<KotlinTypeDeclaration>> multifileDeclarations;

    private NavigationCandidateIndex(
        java.util.Map<String, KotlinTypeDeclaration> declarations,
        java.util.Map<String, List<KotlinTypeDeclaration>> multifileDeclarations) {
      this.declarations = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(declarations));
      final java.util.Map<String, List<KotlinTypeDeclaration>> immutableMultifile =
          new java.util.LinkedHashMap<>();
      for (java.util.Map.Entry<String, List<KotlinTypeDeclaration>> entry
          : multifileDeclarations.entrySet()) {
        immutableMultifile.put(entry.getKey(), Collections.unmodifiableList(
            new java.util.ArrayList<>(entry.getValue())));
      }
      this.multifileDeclarations = Collections.unmodifiableMap(immutableMultifile);
    }

    static NavigationCandidateIndex build(Iterable<java.io.File> sourceRoots) {
      return buildFromPaths(kotlinSourceFiles(sourceRoots));
    }

    static NavigationCandidateIndex buildFromPaths(Iterable<Path> paths) {
      final java.util.Map<String, KotlinTypeDeclaration> declarations = new java.util.LinkedHashMap<>();
      final java.util.Map<String, List<KotlinTypeDeclaration>> multifile = new java.util.LinkedHashMap<>();
      final Set<String> ambiguousDeclarations = new LinkedHashSet<>();
      if (paths != null) {
        for (Path path : paths) {
          if (path != null) collect(path, declarations, multifile, ambiguousDeclarations);
        }
      }
      // A qualified JVM name that maps to multiple normal Kotlin declarations is not safe to
      // navigate. Do not make the answer depend on source-root traversal order.
      ambiguousDeclarations.forEach(declarations::remove);
      // A normal top-level type/file facade and a multifile facade must also never share a JVM
      // name. Neither navigation API has a uniquely provable target in that situation.
      final Set<String> normalMultifileConflicts = new LinkedHashSet<>(declarations.keySet());
      normalMultifileConflicts.retainAll(multifile.keySet());
      normalMultifileConflicts.forEach(declarations::remove);
      normalMultifileConflicts.forEach(multifile::remove);
      return new NavigationCandidateIndex(declarations, multifile);
    }

    private static void collect(
        Path path,
        java.util.Map<String, KotlinTypeDeclaration> declarations,
        java.util.Map<String, List<KotlinTypeDeclaration>> multifile,
        Set<String> ambiguousDeclarations) {
      final String source = sourceForIndexedPath(path);
      if (source == null) return;
      final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
      final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
      final List<KotlinJvmSyntaxParser.TopLevelTypeSyntax> syntaxTypes =
          KotlinJvmSyntaxParser.findTopLevelTypes(source);
      final List<KotlinJvmSyntaxParser.MemberSyntax> syntaxMembers =
          KotlinJvmSyntaxParser.findTopLevelMembers(source);
      final boolean hasTopLevelMember;
      if (syntaxTypes != null && syntaxMembers != null) {
        for (KotlinJvmSyntaxParser.TopLevelTypeSyntax type : syntaxTypes) {
          if (!type.privateType && type.name != null && !type.name.isEmpty()) {
            putUniqueDeclaration(declarations, ambiguousDeclarations, qualifiedName(packageName, type.name),
                new KotlinTypeDeclaration(path, type.nameOffset, type.nameLength));
          }
        }
        hasTopLevelMember = hasPublicTopLevelMember(syntaxMembers);
      } else {
        collectFallbackDeclarations(path, source, packageName, declarations, ambiguousDeclarations);
        hasTopLevelMember = hasPublicTopLevelMemberFallback(source);
      }
      if (!hasTopLevelMember) return;
      final String facade = facadeName(source, path);
      final String qualifiedFacade = qualifiedName(packageName, facade);
      final KotlinTypeDeclaration facadeDeclaration = new KotlinTypeDeclaration(
          path, fileJvmNameOffset(source), facade.length());
      final boolean multifileFacade = isMultifileFacade(source, path, qualifiedFacade);
      if (!multifileFacade) {
        putUniqueDeclaration(declarations, ambiguousDeclarations, qualifiedFacade, facadeDeclaration);
      }
      if (multifileFacade) {
        List<KotlinTypeDeclaration> entries = multifile.get(qualifiedFacade);
        if (entries == null) {
          entries = new java.util.ArrayList<>();
          multifile.put(qualifiedFacade, entries);
        }
        entries.add(facadeDeclaration);
      }
    }

    private static void putUniqueDeclaration(
        java.util.Map<String, KotlinTypeDeclaration> declarations,
        Set<String> ambiguousDeclarations,
        String qualifiedName,
        KotlinTypeDeclaration declaration) {
      if (ambiguousDeclarations.contains(qualifiedName)) return;
      if (declarations.putIfAbsent(qualifiedName, declaration) != null) {
        ambiguousDeclarations.add(qualifiedName);
      }
    }

    KotlinTypeDeclaration declaration(String qualifiedName) {
      return declarations.get(qualifiedName);
    }

    List<KotlinTypeDeclaration> multifileDeclarations(String qualifiedName) {
      final List<KotlinTypeDeclaration> result = multifileDeclarations.get(qualifiedName);
      return result == null ? Collections.emptyList() : result;
    }

    int declarationCount() {
      return declarations.size();
    }

    int multifileDeclarationCount() {
      int count = 0;
      for (List<KotlinTypeDeclaration> entries : multifileDeclarations.values()) count += entries.size();
      return count;
    }
  }

  /**
   * Immutable module-revision snapshot of project alias declarations. It stores only compact
   * projection metadata, never source text or syntax trees. Consumer queries merely parse the
   * consumer package/imports and filter these maps; they do not walk source roots again.
   */
  static final class AliasDeclarationIndex {
    private final java.util.Map<String, java.util.List<DirectAliasDeclaration>> directByPackage;
    private final java.util.Map<String, java.util.List<GenericAliasDeclaration>> genericByPackage;

    private AliasDeclarationIndex(
        java.util.Map<String, java.util.List<DirectAliasDeclaration>> directByPackage,
        java.util.Map<String, java.util.List<GenericAliasDeclaration>> genericByPackage) {
      this.directByPackage = directByPackage;
      this.genericByPackage = genericByPackage;
    }

    static AliasDeclarationIndex build(Iterable<java.io.File> sourceRoots) {
      return buildFromPaths(kotlinSourceFiles(sourceRoots));
    }

    static AliasDeclarationIndex buildFromPaths(Iterable<Path> paths) {
      final java.util.Map<String, java.util.List<DirectAliasDeclaration>> direct =
          new java.util.LinkedHashMap<>();
      final java.util.Map<String, java.util.List<GenericAliasDeclaration>> generic =
          new java.util.LinkedHashMap<>();
      if (paths != null) {
        for (Path path : paths) {
          if (path != null) collect(path, direct, generic);
        }
      }
      return new AliasDeclarationIndex(freeze(direct), freeze(generic));
    }

    java.util.Map<String, String> visibleDirectAliases(Path consumerFile, String consumerSource) {
      final Visibility visibility = Visibility.from(consumerSource);
      final java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
      final Set<String> conflicts = new LinkedHashSet<>();
      for (java.util.List<DirectAliasDeclaration> declarations
          : visiblePackageDeclarations(directByPackage, visibility)) {
        for (DirectAliasDeclaration declaration : declarations) {
          if (normalizedPath(declaration.file).equals(normalizedPath(consumerFile))) continue;
          final String visibleName = visibility.visibleName(
              declaration.packageName, declaration.qualifiedName, declaration.name);
          if (visibleName == null) continue;
          if (result.putIfAbsent(visibleName, declaration.target) != null) {
            conflicts.add(visibleName);
          }
        }
      }
      conflicts.forEach(result::remove);
      return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    }

    int directAliasCount() {
      return declarationCount(directByPackage);
    }

    int genericAliasCount() {
      return declarationCount(genericByPackage);
    }

    private static <T> List<java.util.List<T>> visiblePackageDeclarations(
        java.util.Map<String, java.util.List<T>> declarations,
        Visibility visibility) {
      final java.util.ArrayList<java.util.List<T>> result = new java.util.ArrayList<>();
      final Set<String> packages = visibility.referencedPackages();
      for (String packageName : packages) {
        final java.util.List<T> entries = declarations.get(packageName);
        if (entries != null && !entries.isEmpty()) result.add(entries);
      }
      return result;
    }

    private static int declarationCount(java.util.Map<String, ? extends java.util.List<?>> declarations) {
      int count = 0;
      for (java.util.List<?> aliases : declarations.values()) count += aliases.size();
      return count;
    }

    java.util.Map<String, GenericTypeAlias> visibleGenericAliases(Path consumerFile, String consumerSource) {
      final Visibility visibility = Visibility.from(consumerSource);
      final java.util.Map<String, GenericTypeAlias> result = new java.util.LinkedHashMap<>();
      final Set<String> conflicts = new LinkedHashSet<>();
      for (java.util.List<GenericAliasDeclaration> declarations
          : visiblePackageDeclarations(genericByPackage, visibility)) {
        for (GenericAliasDeclaration declaration : declarations) {
          if (normalizedPath(declaration.file).equals(normalizedPath(consumerFile))) continue;
          final String visibleName = visibility.visibleName(
              declaration.packageName, declaration.qualifiedName, declaration.name);
          if (visibleName == null) continue;
          if (result.putIfAbsent(visibleName, declaration.alias) != null) {
            conflicts.add(visibleName);
          }
        }
      }
      conflicts.forEach(result::remove);
      return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    }

    private static void collect(
        Path path,
        java.util.Map<String, java.util.List<DirectAliasDeclaration>> direct,
        java.util.Map<String, java.util.List<GenericAliasDeclaration>> generic) {
      final String source = sourceForIndexedPath(path);
      if (source == null) return;
      final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
      final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
      final Matcher directMatcher = TYPE_ALIAS_PATTERN.matcher(source);
      while (directMatcher.find()) {
        final String modifiers = directMatcher.group(1);
        final String target = directMatcher.group(3).trim();
        if (modifiers.contains("private") || modifiers.contains("internal") || !isDirectAliasTarget(target)) continue;
        final String name = directMatcher.group(2);
        direct.computeIfAbsent(packageName, ignored -> new java.util.ArrayList<>()).add(
            new DirectAliasDeclaration(path, packageName, name, qualifiedName(packageName, name), target));
      }
      final Matcher genericMatcher = GENERIC_TYPE_ALIAS_PATTERN.matcher(source);
      while (genericMatcher.find()) {
        final String modifiers = genericMatcher.group(1);
        final String target = genericMatcher.group(4).trim();
        final java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
        boolean valid = !modifiers.contains("private") && !modifiers.contains("internal")
            && !target.endsWith("?") && target.indexOf("->") < 0 && target.indexOf('&') < 0
            && target.indexOf('|') < 0;
        for (String parameter : KotlinJvmTypeProjection.splitTopLevelArguments(genericMatcher.group(3))) {
          final String trimmed = parameter.trim();
          if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) valid = false;
          else parameters.add(trimmed);
        }
        final int open = target.indexOf('<');
        if (!valid || parameters.isEmpty() || open < 1 || !target.endsWith(">")) continue;
        final String raw = target.substring(0, open).trim();
        if (raw.indexOf('.') >= 0) continue;
        final java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
        for (String argument : KotlinJvmTypeProjection.splitTopLevelArguments(target.substring(open + 1, target.length() - 1))) {
          final String trimmed = argument.trim();
          if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) valid = false;
          else arguments.add(trimmed);
        }
        if (!valid) continue;
        final String name = genericMatcher.group(2);
        generic.computeIfAbsent(packageName, ignored -> new java.util.ArrayList<>()).add(
            new GenericAliasDeclaration(path, packageName, name, qualifiedName(packageName, name),
                new GenericTypeAlias(parameters, raw, arguments)));
      }
    }

    private static <T> java.util.Map<String, java.util.List<T>> freeze(
        java.util.Map<String, java.util.List<T>> source) {
      final java.util.Map<String, java.util.List<T>> result = new java.util.LinkedHashMap<>();
      for (java.util.Map.Entry<String, java.util.List<T>> entry : source.entrySet()) {
        result.put(entry.getKey(), Collections.unmodifiableList(new java.util.ArrayList<>(entry.getValue())));
      }
      return Collections.unmodifiableMap(result);
    }
  }

  private static final class Visibility {
    final String consumerPackage;
    // Qualified alias declaration name -> name visible in this consumer. A plain import maps to
    // its declared simple name; `import a.Name as Local` maps to Local.
    final java.util.Map<String, String> explicitlyImported;

    private Visibility(String consumerPackage, java.util.Map<String, String> explicitlyImported) {
      this.consumerPackage = consumerPackage;
      this.explicitlyImported = explicitlyImported;
    }

    static Visibility from(String source) {
      final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
      final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
      final java.util.Map<String, String> imports = new java.util.LinkedHashMap<>();
      final Matcher importMatcher = IMPORT_PATTERN.matcher(source);
      while (importMatcher.find()) {
        final String qualifiedName = importMatcher.group(1);
        final int separator = qualifiedName.lastIndexOf('.');
        final String visibleName = importMatcher.group(2) != null
            ? importMatcher.group(2)
            : separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
        imports.put(qualifiedName, visibleName);
      }
      return new Visibility(packageName, imports);
    }

    Set<String> referencedPackages() {
      final Set<String> packages = new LinkedHashSet<>();
      packages.add(consumerPackage);
      for (String imported : explicitlyImported.keySet()) {
        final int separator = imported.lastIndexOf('.');
        if (separator >= 0) packages.add(imported.substring(0, separator));
      }
      return packages;
    }

    String visibleName(String declarationPackage, String qualifiedName, String declarationName) {
      if (declarationPackage.equals(consumerPackage)) return declarationName;
      return explicitlyImported.get(qualifiedName);
    }
  }

  private static final class DirectAliasDeclaration {
    final Path file;
    final String packageName;
    final String name;
    final String qualifiedName;
    final String target;

    DirectAliasDeclaration(Path file, String packageName, String name, String qualifiedName, String target) {
      this.file = file;
      this.packageName = packageName;
      this.name = name;
      this.qualifiedName = qualifiedName;
      this.target = target;
    }
  }

  private static final class GenericAliasDeclaration {
    final Path file;
    final String packageName;
    final String name;
    final String qualifiedName;
    final GenericTypeAlias alias;

    GenericAliasDeclaration(Path file, String packageName, String name, String qualifiedName,
        GenericTypeAlias alias) {
      this.file = file;
      this.packageName = packageName;
      this.name = name;
      this.qualifiedName = qualifiedName;
      this.alias = alias;
    }
  }

  public static final class GenericTypeAlias {
    public final List<String> parameters;
    public final String targetRawType;
    public final List<String> targetArguments;

    GenericTypeAlias(List<String> parameters, String targetRawType, List<String> targetArguments) {
      this.parameters = Collections.unmodifiableList(new java.util.ArrayList<>(parameters));
      this.targetRawType = targetRawType;
      this.targetArguments = Collections.unmodifiableList(new java.util.ArrayList<>(targetArguments));
    }
  }

  public static final class KotlinTypeDeclaration {
    public final Path file;
    public final int offset;
    public final int length;

    KotlinTypeDeclaration(Path file, int offset, int length) {
      this.file = file;
      this.offset = offset;
      this.length = length;
    }
  }

  private static boolean isMultifileFacade(String source, Path path, String qualifiedName) {
    if (!FILE_JVM_MULTIFILE_PATTERN.matcher(source).find()) {
      return false;
    }
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    return qualifiedName.equals(qualifiedName(packageName, facadeName(source, path)));
  }

  private static String facadeName(String source, Path path) {
    final Matcher jvmNameMatcher = FILE_JVM_NAME_PATTERN.matcher(source);
    return jvmNameMatcher.find()
        ? jvmNameMatcher.group(1)
        : path.getFileName().toString().substring(0, path.getFileName().toString().length() - 3) + "Kt";
  }

  private static int fileJvmNameOffset(String source) {
    final Matcher matcher = FILE_JVM_NAME_PATTERN.matcher(source);
    return matcher.find() ? matcher.start(1) : 0;
  }

  private static boolean hasPublicTopLevelMember(
      List<KotlinJvmSyntaxParser.MemberSyntax> members) {
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (!member.privateMember) {
        return true;
      }
    }
    return false;
  }

  private static boolean indexFileFallback(
      String source, String packageName, Set<String> result) {
    final String[] lines = source.split("\\R");
    int braceDepth = 0;
    boolean hasTopLevelMember = false;
    for (String line : lines) {
      if (braceDepth == 0) {
        final Matcher typeMatcher = TYPE_PATTERN.matcher(line);
        if (typeMatcher.find() && !containsPrivateModifier(typeMatcher.group(1))) {
          result.add(qualifiedName(packageName, typeMatcher.group(2)));
        }
        if (TOP_LEVEL_MEMBER_PATTERN.matcher(line).find() && !line.matches("^\\s*private\\s+.*")) {
          hasTopLevelMember = true;
        }
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
    }
    return hasTopLevelMember;
  }

  private static void collectFallbackDeclarations(
      Path path,
      String source,
      String packageName,
      java.util.Map<String, KotlinTypeDeclaration> declarations,
      Set<String> ambiguousDeclarations) {
    final String[] lines = source.split("\\R", -1);
    int braceDepth = 0;
    int offset = 0;
    for (String line : lines) {
      if (braceDepth == 0) {
        final Matcher typeMatcher = TYPE_PATTERN.matcher(line);
        if (typeMatcher.find() && !containsPrivateModifier(typeMatcher.group(1))) {
          final String name = typeMatcher.group(2);
          NavigationCandidateIndex.putUniqueDeclaration(declarations, ambiguousDeclarations,
              qualifiedName(packageName, name),
              new KotlinTypeDeclaration(path, offset + typeMatcher.start(2), name.length()));
        }
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
      offset += line.length() + 1;
    }
  }

  private static KotlinTypeDeclaration findTypeDeclarationFallback(
      Path path, String source, String packageName, String qualifiedName) {
    final String[] lines = source.split("\\R", -1);
    int braceDepth = 0;
    int offset = 0;
    for (String line : lines) {
      if (braceDepth == 0) {
        final Matcher typeMatcher = TYPE_PATTERN.matcher(line);
        if (typeMatcher.find() && !containsPrivateModifier(typeMatcher.group(1))) {
          final String name = typeMatcher.group(2);
          if (qualifiedName.equals(qualifiedName(packageName, name))) {
            return new KotlinTypeDeclaration(path, offset + typeMatcher.start(2), name.length());
          }
        }
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
      offset += line.length() + 1;
    }
    return null;
  }

  private static boolean hasPublicTopLevelMemberFallback(String source) {
    final String[] lines = source.split("\\R");
    int braceDepth = 0;
    for (String line : lines) {
      if (braceDepth == 0
          && TOP_LEVEL_MEMBER_PATTERN.matcher(line).find()
          && !line.matches("^\\s*private\\s+.*")) {
        return true;
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
    }
    return false;
  }

  private static boolean containsPrivateModifier(String modifiers) {
    return modifiers != null && Pattern.compile("(?:^|\\s)private(?:\\s|$)").matcher(modifiers).find();
  }

  private static String qualifiedName(String packageName, String simpleName) {
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }

  // This intentionally stays conservative. It is only used to avoid publishing clearly nested
  // declarations as package-level classes; ABI-accurate nested declarations belong to a later stub phase.
  private static int braceDelta(String line) {
    boolean quoted = false;
    boolean escaped = false;
    int delta = 0;
    for (int i = 0; i < line.length(); i++) {
      char current = line.charAt(i);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
        continue;
      }
      if (current == '"') {
        quoted = true;
      } else if (current == '{') {
        delta++;
      } else if (current == '}') {
        delta--;
      }
    }
    return delta;
  }

  private static Path normalizedPath(Path file) {
    return file.toAbsolutePath().normalize();
  }

  static final class ConsumerAliasCache<T> {
    // Alias visibility parsing is inexpensive compared with retaining an arbitrarily large editor
    // document. Large consumers remain correct but deliberately bypass this exact-source cache.
    static final int MAX_CACHED_CONSUMER_SOURCE_CHARS = 256 * 1024;
    // There are independent direct and generic caches per module revision. Bound each one so a
    // long editor session opening many distinct consumers cannot retain unbounded source strings.
    static final int MAX_CACHED_CONSUMERS = 1024;
    private final ConcurrentHashMap<Path, CachedAliasResult<T>> results = new ConcurrentHashMap<>();

    java.util.Map<String, T> get(Path file, String consumerSource) {
      if (!isCacheable(file, consumerSource)) return null;
      final CachedAliasResult<T> result = results.get(normalizedPath(file));
      return result != null && result.consumerSource.equals(consumerSource) ? result.aliases : null;
    }

    void put(Path file, String consumerSource, java.util.Map<String, T> aliases) {
      if (file == null || consumerSource == null) return;
      if (!isCacheable(file, consumerSource)) {
        // A previously cached small revision must not keep consuming memory after this editor
        // document grows beyond the retention limit.
        results.remove(normalizedPath(file));
        return;
      }
      if (aliases == null) return;
      final Path normalizedFile = normalizedPath(file);
      if (!results.containsKey(normalizedFile) && results.size() >= MAX_CACHED_CONSUMERS) {
        // Keep the cache strictly bounded without introducing LRU bookkeeping or a lossy source
        // hash. A full eviction only costs later cache misses; it cannot change projection output.
        results.clear();
      }
      results.put(normalizedFile, new CachedAliasResult<>(consumerSource, aliases));
    }

    private static boolean isCacheable(Path file, String consumerSource) {
      return file != null && consumerSource != null
          && consumerSource.length() <= MAX_CACHED_CONSUMER_SOURCE_CHARS;
    }

    int size() {
      return results.size();
    }
  }

  /** Small concurrent cache facade so lifecycle semantics remain testable without ModuleProject. */
  static final class AliasCacheStore<K, V> {
    private final ConcurrentHashMap<K, V> entries = new ConcurrentHashMap<>();

    V get(K key) {
      return entries.get(key);
    }

    void put(K key, V value) {
      entries.put(key, value);
    }

    V putIfAbsent(K key, V value) {
      return entries.putIfAbsent(key, value);
    }

    void remove(K key) {
      entries.remove(key);
    }

    void clear() {
      entries.clear();
    }

    int size() {
      return entries.size();
    }
  }

  private static final class CachedAliasResult<T> {
    final String consumerSource;
    final java.util.Map<String, T> aliases;

    CachedAliasResult(String consumerSource, java.util.Map<String, T> aliases) {
      this.consumerSource = consumerSource;
      this.aliases = aliases;
    }
  }

  private static final class CachedAliases {
    final ConsumerAliasCache<String> directAliases = new ConsumerAliasCache<>();
    final ConsumerAliasCache<GenericTypeAlias> genericAliases = new ConsumerAliasCache<>();
    final AliasDeclarationIndex declarationIndex;

    CachedAliases(AliasDeclarationIndex declarationIndex) {
      this.declarationIndex = declarationIndex;
    }
  }
}
