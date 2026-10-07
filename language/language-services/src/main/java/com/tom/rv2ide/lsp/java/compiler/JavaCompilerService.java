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

package com.tom.rv2ide.lsp.java.compiler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.javac.services.compiler.ReusableCompiler;
import com.tom.rv2ide.javac.services.partial.CompilationInfo;
import com.tom.rv2ide.javac.services.partial.MethodReparse;
import com.tom.rv2ide.javac.services.partial.PartialReparse;
import com.tom.rv2ide.lsp.java.kotlin.KotlinClassOutputProvider;
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex;
import com.tom.rv2ide.lsp.java.kotlin.KotlinSourceStubProvider;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.lsp.java.models.MethodReparsePlan;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.lsp.java.parser.ParseTask;
import com.tom.rv2ide.lsp.java.parser.Parser;
import com.tom.rv2ide.lsp.java.utils.Extractors;
import com.tom.rv2ide.lsp.java.visitors.FindTypeDeclarations;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.projects.android.AndroidModule;
import com.tom.rv2ide.projects.java.JavaModule;

import com.tom.rv2ide.projects.util.BootClasspathProvider;
import com.tom.rv2ide.projects.util.StringSearch;
import com.tom.rv2ide.utils.Cache;
import com.tom.rv2ide.utils.Environment;
import com.tom.rv2ide.utils.SourceClassTrie;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jdkx.tools.Diagnostic;
import jdkx.tools.JavaFileObject;
import jdkx.tools.StandardLocation;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.tree.MethodTree;
import openjdk.source.util.TreePath;
import openjdk.tools.javac.api.JavacTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaCompilerService implements CompilerProvider {

  public static final JavaCompilerService NO_MODULE_COMPILER = new JavaCompilerService(null);
  private static final Cache<String, Boolean> cacheContainsWord = new Cache<>();
  private static final Cache<Void, List<String>> cacheContainsType = new Cache<>();
  private static final Logger LOG = LoggerFactory.getLogger(JavaCompilerService.class);
  protected final Set<String> classPathClasses;
  protected final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();
  protected final Map<JavaFileObject, Long> cachedModified = new HashMap<>();
  protected final Cache<Void, List<String>> cacheFileImports = new Cache<>();
  protected final SynchronizedTask synchronizedTask = new SynchronizedTask();
  protected final SourceFileManager fileManager;
  protected final ModuleProject module;
  public ReusableCompiler compiler = new JCReusableCompiler();
  protected Set<String> bootClasspathClasses =
      BootClasspathProvider.getTopLevelClasses(
          Collections.singleton(Environment.ANDROID_JAR.getAbsolutePath()));
  private CompileBatch cachedCompile;
  /** Set when a failed method reparse has already mutated the cached javac task. */
  private boolean methodReparseCorruptedTask;

  // The module project must not be null
  // It is marked as nullable just for some special cases like tests
  public JavaCompilerService(@Nullable ModuleProject module) {
    this.module = module;
    if (module == null) {
      this.fileManager = SourceFileManager.NO_MODULE;
      this.classPathClasses = Collections.emptySet();
    } else {
      this.fileManager = SourceFileManager.forModule(module);
      this.classPathClasses =
          Collections.unmodifiableSet(module.compileClasspathClasses.allClassNames());
      this.bootClasspathClasses = Collections.unmodifiableSet(getBootclasspathClasses());
    }
  }
  private Set<String> getBootclasspathClasses() {
    final List<String> classpaths = new ArrayList<>();

    if (module instanceof AndroidModule) {
      classpaths.addAll(
          ((AndroidModule) module)
              .getBootClassPaths().stream().map(File::getPath).collect(Collectors.toList()));
    } else if (module instanceof JavaModule) {
      final JavaModule javaModule = (JavaModule) module;
      for (ModuleProject dependency : javaModule.getCompileModuleProjects()) {
        if (dependency instanceof AndroidModule) {
          classpaths.addAll(
              ((AndroidModule) dependency)
                  .getBootClassPaths().stream().map(File::getPath).collect(Collectors.toList()));
        }
      }

      if (classpaths.isEmpty()) {
        classpaths.addAll(
            javaModule.getInheritedBootClassPaths().stream()
                .filter(Objects::nonNull)
                .map(File::getPath)
                .distinct()
                .collect(Collectors.toList()));
      }

      if (classpaths.isEmpty()) {
        classpaths.addAll(
            module.getCompileClasspaths().stream()
                .filter(Objects::nonNull)
                .map(File::getPath)
                .filter(
                    path ->
                        path.endsWith("/android.jar")
                            || path.endsWith(File.separator + "android.jar"))
                .distinct()
                .collect(Collectors.toList()));
      }
    }

    final List<String> normalizedClasspaths =
        classpaths.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());

    if (normalizedClasspaths.isEmpty()) {
      return Collections.emptySet();
    }

    BootClasspathProvider.update(normalizedClasspaths);
    this.bootClasspathClasses =
        Collections.unmodifiableSet(BootClasspathProvider.getTopLevelClasses(normalizedClasspaths));
    return bootClasspathClasses;
  }


  private JavaCompilerService(
      @Nullable ModuleProject module,
      SourceFileManager fileManager,
      Set<String> bootClasspathClasses,
      Set<String> classPathClasses) {
    this.module = module;
    this.fileManager = fileManager;
    this.bootClasspathClasses = bootClasspathClasses;
    this.classPathClasses = classPathClasses;
  }

  public ModuleProject getModule() {
    return module;
  }

  @Override
  public TreeSet<String> publicTopLevelTypes() {
    TreeSet<String> all = new TreeSet<>();
    List<SourceClassTrie.SourceNode> sourceClasses =
        module != null ? module.compileJavaSourceClasses.allSources() : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : sourceClasses) {
      all.add(node.getQualifiedName());
    }
    final int sourceCount = all.size();
    final int classPathCount = classPathClasses.size();
    final int bootClassPathCount = bootClasspathClasses.size();
    all.addAll(classPathClasses);
    all.addAll(bootClasspathClasses);
    if (module != null && JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()) {
      all.addAll(KotlinJvmTypeIndex.publicTopLevelTypes(module));
      all.addAll(KotlinClassOutputProvider.publicDependencyTopLevelTypes(module));
    }
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "publicTopLevelTypes source={} classpath={} boot={} total={} hasString={} hasInteger={} hasDouble={}",
          sourceCount,
          classPathCount,
          bootClassPathCount,
          all.size(),
          all.contains("java.lang.String"),
          all.contains("java.lang.Integer"),
          all.contains("java.lang.Double"));
    }
    return all;
  }


  @Override
  public TreeSet<String> packagePrivateTopLevelTypes(String packageName) {
    return new TreeSet<>();
  }

  @Override
  public Optional<JavaFileObject> findAnywhere(String className) {
    Path fromSource = findTypeDeclaration(className);
    if (fromSource != NOT_FOUND) {
      return Optional.of(new SourceFileObject(fromSource));
    }
    return Optional.empty();
  }

  @Override
  public Path findTypeDeclaration(String className) {
    Path fastFind = findPublicTypeDeclaration(className);
    if (fastFind != NOT_FOUND) {
      return fastFind;
    }
    // In principle, the slow path can be skipped in many cases.
    // If we're spending a lot of time in findTypeDeclaration, this would be a good
    // optimization.
    String packageName = Extractors.packageName(className);
    String simpleName = Extractors.simpleName(className);
    List<SourceClassTrie.SourceNode> classes =
        module != null ? module.listClassesFromSourceDirs(packageName) : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : classes) {
      final Path path = node.getFile();
      if (containsWord(path, simpleName) && containsType(path, className)) {
        return path;
      }
    }
    return NOT_FOUND;
  }

  @Override
  public Path[] findTypeReferences(String className) {
    String packageName = Extractors.packageName(className);
    String simpleName = Extractors.simpleName(className);
    List<Path> candidates = new ArrayList<>();
    List<SourceClassTrie.SourceNode> sourceNodes =
        module != null ? module.compileJavaSourceClasses.allSources() : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : sourceNodes) {
      final Path path = node.getFile();
      if (containsWord(path, packageName)
          && containsImport(path, className)
          && containsWord(path, simpleName)) {
        candidates.add(path);
      }
    }

    return candidates.toArray(new Path[0]);
  }

  @Override
  public Path[] findMemberReferences(String className, String memberName) {
    List<Path> candidates = new ArrayList<>();
    List<SourceClassTrie.SourceNode> sourceNodes =
        module != null ? module.compileJavaSourceClasses.allSources() : Collections.emptyList();
    for (SourceClassTrie.SourceNode node : sourceNodes) {
      final Path path = node.getFile();
      if (containsWord(path, memberName)) {
        candidates.add(path);
      }
    }
    return candidates.toArray(new Path[0]);
  }

  @Override
  public List<String> findQualifiedNames(String simpleName, boolean onlyOne) {
    final var names = new ArrayList<String>();
    for (var name : publicTopLevelTypes()) {
      // This will be true in a test environment
      if (name.contains("/")) {
        name = name.replace('/', '.');
      }

      if (name.endsWith("." + simpleName)) {
        names.add(name);
        if (onlyOne) {
          break;
        }
      }
    }
    return names;
  }

  @Override
  public ParseTask parse(Path file) {
    Parser parser = Parser.parseFile(file);
    return new ParseTask(parser.task, parser.root);
  }

  @Override
  public ParseTask parse(JavaFileObject file) {
    Parser parser = Parser.parseJavaFileObject(file);
    return new ParseTask(parser.task, parser.root);
  }

  @Override
  public SynchronizedTask compile(final CompilationRequest request) {
    return compileBatch(request);
  }

  private SynchronizedTask compileBatch(CompilationRequest request) {
    synchronizedTask.post(
        () -> {
          if (needsCompilation(request.sources)) {
            reparseOrRecompile(request);
          }

          synchronizedTask.setTask(
              new CompileTask(cachedCompile, diagnostics, false));
        });

    return synchronizedTask;
  }
  private boolean needsCompilation(Collection<? extends JavaFileObject> sources) {
    if (cachedModified.size() != sources.size()) {
      return true;
    }
    for (JavaFileObject f : sources) {
      if (!cachedModified.containsKey(f)) {
        return true;
      }

      final Long modified = cachedModified.get(f);
      if (modified != null && f.getLastModified() != modified) {
        return true;
      }
    }
    return false;
  }


  /**
   * Advances the cached semantic task when the strict method-reparse MVP accepts the request;
   * otherwise recompiles the current request on the stable semantic path.
   *
   * <p>This boundary is intentionally conservative and treated as frozen after IDE validation.
   * Future completion-performance work must not bypass contiguous document identity, method-body
   * containment, mutation-failure isolation, or partial-diagnostic rollback.
   */
  private synchronized void reparseOrRecompile(CompilationRequest request) {
    final long strategyStartedNs = System.nanoTime();
    if (request.methodReparsePlan == null) {
      if (IdeLogConfig.shouldLogDebug()) {
        LOG.debug(
            "MethodReparse skip requestHash={} reason=NO_PLAN sourceCount={} strategyDurationUs={}",
            System.identityHashCode(request),
            request.sources.size(),
            (System.nanoTime() - strategyStartedNs) / 1_000L);
      }
      recompile(request, MethodReparseResult.NO_PLAN);
      return;
    }
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "MethodReparse attempt requestHash={} cachedTaskHash={} sourceCount={} planPresent={} cachedVersion={} requestedVersion={} cachedRevision={} requestedRevision={}",
          System.identityHashCode(request),
          cachedCompile == null ? 0 : System.identityHashCode(cachedCompile.task),
          request.sources.size(),
          request.methodReparsePlan != null,
          cachedCompile == null ? -1 : cachedCompile.documentVersion(),
          request.methodReparsePlan == null ? -1 : request.methodReparsePlan.documentVersion,
          cachedCompile == null ? -1 : cachedCompile.documentRevision(),
          request.methodReparsePlan == null ? -1 : request.methodReparsePlan.documentRevision);
    }
    final MethodReparseResult reparseResult =
        methodReparseCorruptedTask
            ? MethodReparseResult.TASK_CORRUPTED
            : tryMethodReparse(request);
    if (reparseResult == MethodReparseResult.SUCCESS) {
      if (IdeLogConfig.shouldLogDebug()) {
        LOG.debug(
            "MethodReparse success requestHash={} taskHash={} source={}",
            System.identityHashCode(request),
            cachedCompile == null ? 0 : System.identityHashCode(cachedCompile.task),
            request.methodReparsePlan.file);
        LOG.debug(
            "JAVAC_METHOD_REPARSE_SUCCESS taskHash={} source={} version={} revision={}",
            cachedCompile == null ? 0 : System.identityHashCode(cachedCompile.task),
            request.methodReparsePlan.file,
            request.methodReparsePlan.documentVersion,
            request.methodReparsePlan.documentRevision);
      }
      return;
    }
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "MethodReparse fallback requestHash={} reason={} corrupted={} cachedTaskHash={}",
          System.identityHashCode(request),
          reparseResult,
          methodReparseCorruptedTask,
          cachedCompile == null ? 0 : System.identityHashCode(cachedCompile.task));
      LOG.info(
          "JAVAC_METHOD_REPARSE_FALLBACK reason={} corrupted={} source={} version={} revision={}",
          reparseResult,
          methodReparseCorruptedTask,
          request.methodReparsePlan.file,
          request.methodReparsePlan.documentVersion,
          request.methodReparsePlan.documentRevision);
    }
    // A failed in-place mutation must never be reused. recompile() closes and replaces it.
    recompile(request, reparseResult);
  }

  private MethodReparseResult tryMethodReparse(CompilationRequest request) {
    final MethodReparsePlan plan = request.methodReparsePlan;
    if (plan == null) {
      return MethodReparseResult.NO_PLAN;
    }
    if (cachedCompile == null) {
      return MethodReparseResult.NO_CACHE;
    }
    if (cachedCompile.closed) {
      return MethodReparseResult.CACHE_CLOSED;
    }
    if (request.sources.size() != 1) {
      return MethodReparseResult.MULTIPLE_SOURCES;
    }
    if (plan.contents == null || plan.cursor < 0) {
      return MethodReparseResult.INVALID_PLAN;
    }
    final JavaFileObject requestedSource = request.sources.iterator().next();
    if (!requestedSource.toUri().normalize().equals(plan.file.toUri().normalize())) {
      return MethodReparseResult.SOURCE_MISMATCH;
    }
    final String oldContents = cachedCompile.currentSourceContents();
    if (oldContents == null) {
      return MethodReparseResult.NO_CACHED_CONTENTS;
    }
    // The MVP requires an explicit contiguous document identity. A task produced by
    // Diagnostics or another request without version metadata must be re-established first;
    // otherwise we could mutate a task whose source snapshot is only approximately known.
    if (cachedCompile.documentVersion() < 0 || plan.documentVersion < 0) {
      return MethodReparseResult.VERSION_UNKNOWN;
    }
    if (plan.documentVersion != cachedCompile.documentVersion() + 1) {
      return MethodReparseResult.VERSION_GAP;
    }
    if (cachedCompile.documentRevision() < 0 || plan.documentRevision < 0) {
      return MethodReparseResult.REVISION_UNKNOWN;
    }
    if (plan.documentRevision <= cachedCompile.documentRevision()) {
      return MethodReparseResult.REVISION_NOT_NEWER;
    }

    final SingleTextEdit edit = SingleTextEdit.compute(oldContents, plan.contents);
    if (edit == null) {
      return MethodReparseResult.INVALID_EDIT;
    }
    final CompilationUnitTree root = cachedCompile.root(plan.file);
    if (root == null) {
      return MethodReparseResult.NO_ROOT;
    }
    final List<Pair<Range, TreePath>> positions =
        cachedCompile.methodPositions.get(plan.file.toAbsolutePath().toString());
    if (positions == null) {
      return MethodReparseResult.NO_METHOD_INDEX;
    }

    Pair<Range, TreePath> candidate = null;
    for (Pair<Range, TreePath> position : positions) {
      final Range range = position.first;
      final int start = range.getStart().requireIndex();
      final int end = range.getEnd().requireIndex();
      if (plan.cursor >= start && plan.cursor <= end) {
        candidate = position;
        break;
      }
    }
    if (candidate == null) {
      return MethodReparseResult.CURSOR_OUTSIDE_METHOD;
    }
    if (!(candidate.second.getLeaf() instanceof MethodTree)) {
      return MethodReparseResult.INVALID_METHOD_PATH;
    }
    final MethodTree method = (MethodTree) candidate.second.getLeaf();
    if (method.getBody() == null) {
      return MethodReparseResult.NO_METHOD_BODY;
    }
    if (method.getName().contentEquals("<init>")) {
      return MethodReparseResult.CONSTRUCTOR;
    }

    final JavacTrees trees = JavacTrees.instance(cachedCompile.task);
    final long bodyStartLong = trees.getSourcePositions().getStartPosition(root, method.getBody());
    final long bodyEndLong = trees.getSourcePositions().getEndPosition(root, method.getBody());
    if (bodyStartLong < 0 || bodyEndLong < bodyStartLong) {
      return MethodReparseResult.INVALID_BODY_POSITION;
    }
    final int bodyStart = (int) bodyStartLong;
    final int bodyEnd = (int) bodyEndLong;
    if (edit.start <= bodyStart || edit.oldEnd >= bodyEnd) {
      return MethodReparseResult.EDIT_OUTSIDE_BODY;
    }
    final int newBodyEnd = bodyEnd + edit.delta();
    if (newBodyEnd > plan.contents.length() || newBodyEnd <= bodyStart) {
      return MethodReparseResult.INVALID_NEW_BODY_RANGE;
    }

    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final PartialReparse reparser = new MethodReparse();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final String newBody = plan.contents.substring(bodyStart, newBodyEnd);
    final boolean success = reparser.reparseMethod(info, candidate.second, newBody, plan.contents);
    if (!success) {
      if (reparser instanceof MethodReparse
          && ((MethodReparse) reparser).getTaskMutated()) {
        methodReparseCorruptedTask = true;
        return MethodReparseResult.REPARSE_FAILED_AFTER_MUTATION;
      }
      return MethodReparseResult.REPARSE_FAILED_BEFORE_MUTATION;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return MethodReparseResult.SUCCESS;
  }

  private enum MethodReparseResult {
    SUCCESS,
    NO_PLAN,
    NO_CACHE,
    CACHE_CLOSED,
    MULTIPLE_SOURCES,
    INVALID_PLAN,
    SOURCE_MISMATCH,
    NO_CACHED_CONTENTS,
    VERSION_UNKNOWN,
    VERSION_GAP,
    REVISION_UNKNOWN,
    REVISION_NOT_NEWER,
    INVALID_EDIT,
    NO_ROOT,
    NO_METHOD_INDEX,
    CURSOR_OUTSIDE_METHOD,
    INVALID_METHOD_PATH,
    NO_METHOD_BODY,
    CONSTRUCTOR,
    INVALID_BODY_POSITION,
    EDIT_OUTSIDE_BODY,
    INVALID_NEW_BODY_RANGE,
    REPARSE_FAILED_BEFORE_MUTATION,
    REPARSE_FAILED_AFTER_MUTATION,
    TASK_CORRUPTED
  }

  private static final class SingleTextEdit {
    final int start;
    final int oldEnd;
    final int newEnd;

    private SingleTextEdit(int start, int oldEnd, int newEnd) {
      this.start = start;
      this.oldEnd = oldEnd;
      this.newEnd = newEnd;
    }

    static SingleTextEdit compute(String oldText, String newText) {
      int prefix = 0;
      final int commonLength = Math.min(oldText.length(), newText.length());
      while (prefix < commonLength && oldText.charAt(prefix) == newText.charAt(prefix)) {
        prefix++;
      }
      int oldSuffix = oldText.length();
      int newSuffix = newText.length();
      while (oldSuffix > prefix && newSuffix > prefix
          && oldText.charAt(oldSuffix - 1) == newText.charAt(newSuffix - 1)) {
        oldSuffix--;
        newSuffix--;
      }
      return new SingleTextEdit(prefix, oldSuffix, newSuffix);
    }

    int delta() {
      return newEnd - oldEnd;
    }
  }

  private synchronized void recompile(
      CompilationRequest request, MethodReparseResult reason) {
    final int previousTaskHash =
        cachedCompile == null ? 0 : System.identityHashCode(cachedCompile.task);
    close();
    methodReparseCorruptedTask = false;
    final long startedNs = System.nanoTime();
    this.cachedCompile = performCompilation(request);
    updateModificationCache(request);
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.info(
          "JAVAC_FULL_COMPILE_COMPLETE reason={} previousTaskHash={} taskHash={} source={} sourceCount={} durationMs={} planPresent={}",
          reason,
          previousTaskHash,
          cachedCompile == null ? 0 : System.identityHashCode(cachedCompile.task),
          request.sources.size() == 1 ? request.sources.iterator().next().toUri() : null,
          request.sources.size(),
          (System.nanoTime() - startedNs) / 1_000_000L,
          request.methodReparsePlan != null);
    }
  }

  public synchronized void close() {
    if (cachedCompile != null) {
     cachedCompile.close();
     cachedCompile.borrow.close();
     cachedCompile = null;
   }
   methodReparseCorruptedTask = false;
 }


  private void updateModificationCache(final CompilationRequest request) {
    cachedModified.clear();
    for (JavaFileObject f : request.sources) {
      cachedModified.put(f, f.getLastModified());
    }
  }
  private CompileBatch performCompilation(CompilationRequest request) {
    Collection<? extends JavaFileObject> sources = request.sources;
    // Kotlin stubs are part of the stable full-compilation input whenever Kotlin recognition is on.
    if (module != null && JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()) {
      final Collection<JavaFileObject> kotlinStubs = KotlinSourceStubProvider.stubsFor(module, sources);
      if (!kotlinStubs.isEmpty()) {
        final List<JavaFileObject> withKotlinStubs = new ArrayList<>(sources);
        withKotlinStubs.addAll(kotlinStubs);
        sources = withKotlinStubs;
      }
    }
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "performCompilation sourcesDetail requestHash={} sourcesDetail={}",
          System.identityHashCode(request),
          sources == null ? null : CompileBatch.describeSources(sources));
    }

    if (sources.isEmpty()) {
      throw new RuntimeException("empty sources");
    }

    CompileBatch firstAttempt;
    try {
      firstAttempt = new CompileBatch(this, sources, request);
    } catch (Throwable err) {
      LOG.error(
          "performCompilation failed requestHash={} expandedSources={} currentContextPresent={} firstSource={} ",
          System.identityHashCode(request),
          sources.size(),
          compiler.currentContext != null,
          sources.iterator().hasNext() ? sources.iterator().next().toUri() : null,
          err);
      throw err;
    }
    Set<Path> addFiles = firstAttempt.needsAdditionalSources();

    if (addFiles.isEmpty()) {
      return firstAttempt;
    }

    // If the compiler needs additional source files that contain package-private files
    firstAttempt.close();
    firstAttempt.borrow.close();

    List<JavaFileObject> moreSources = new ArrayList<>(sources);
    final Set<String> sourceUris = new HashSet<>();
    for (JavaFileObject source : sources) {
      sourceUris.add(source.toUri().normalize().toString());
    }
    for (Path add : addFiles) {
      final SourceFileObject additionalSource = new SourceFileObject(add);
      if (sourceUris.add(additionalSource.toUri().normalize().toString())) {
        moreSources.add(additionalSource);
      }
    }
 
    return new CompileBatch(this, moreSources, request);
  }

  private boolean containsWord(Path file, String word) {
    if (cacheContainsWord.needs(file, word)) {
      cacheContainsWord.load(file, word, StringSearch.containsWord(file, word));
    }
    return cacheContainsWord.get(file, word);
  }

  private boolean containsImport(Path file, String className) {
    String packageName = Extractors.packageName(className);
    if (packageNameOrEmpty(file).equals(packageName)) {
      return true;
    }
    String star = packageName + ".*";
    for (String i : readImports(file)) {
      if (i.equals(className) || i.equals(star)) {
        return true;
      }
    }
    return false;
  }

  private List<String> readImports(Path file) {
    if (cacheFileImports.needs(file, null)) {
      loadImports(file);
    }
    return cacheFileImports.get(file, null);
  }

  private void loadImports(Path file) {
    List<String> list = new ArrayList<>();
    Pattern importClass = Pattern.compile("^import +([\\w.]+\\.\\w+);");
    Pattern importStar = Pattern.compile("^import +([\\w.]+\\.\\*);");
    try (BufferedReader lines = FileManager.INSTANCE.getReader(file)) {
      for (String line = lines.readLine(); line != null; line = lines.readLine()) {
        // If we reach a class declaration, stop looking for imports
        // TODO This could be a little more specific
        if (line.contains("class")) {
          break;
        }
        // import foo.bar.Doh;
        Matcher matchesClass = importClass.matcher(line);
        if (matchesClass.matches()) {
          list.add(matchesClass.group(1));
        }
        // import foo.bar.*
        Matcher matchesStar = importStar.matcher(line);
        if (matchesStar.matches()) {
          list.add(matchesStar.group(1));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    cacheFileImports.load(file, null, list);
  }

  String packageNameOrEmpty(Path file) {
    return module != null ? module.packageNameOrEmpty(file) : "";
  }

  public void destroy() {
    synchronizedTask.post(
        () -> {
          close();
          cachedCompile = null;
          cachedModified.clear();
          compiler = new ReusableCompiler();
        });
  }

  public SynchronizedTask getSynchronizedTask() {
    return synchronizedTask;
  }


  private boolean containsType(Path file, String className) {
    if (cacheContainsType.needs(file, null)) {
      CompilationUnitTree root = parse(file).root;
      List<String> types = new ArrayList<>();
      new FindTypeDeclarations().scan(root, types);
      cacheContainsType.load(file, null, types);
    }
    return cacheContainsType.get(file, null).contains(className);
  }

  private Path findPublicTypeDeclaration(String className) {
    JavaFileObject source;
    try {
      source =
          fileManager.getJavaFileForInput(
              StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    if (source == null) {
      return NOT_FOUND;
    }
    if (!source.toUri().getScheme().equals("file")) {
      return NOT_FOUND;
    }
    Path file = Paths.get(source.toUri());
    if (!containsType(file, className)) {
      return NOT_FOUND;
    }
    return file;
  }

}
