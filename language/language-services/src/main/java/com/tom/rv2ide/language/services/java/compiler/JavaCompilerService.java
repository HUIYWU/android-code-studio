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

package com.tom.rv2ide.language.services.java.compiler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.language.compiler.javac.compiler.ReusableCompiler;
import com.tom.rv2ide.language.compiler.javac.partial.CompilationInfo;
import com.tom.rv2ide.language.compiler.javac.partial.InPlaceReparser;
import com.tom.rv2ide.language.services.java.kotlin.KotlinClassOutputProvider;
import com.tom.rv2ide.language.services.java.kotlin.KotlinJvmTypeIndex;
import com.tom.rv2ide.language.services.java.kotlin.KotlinSourceStubProvider;
import com.tom.rv2ide.language.services.java.models.CompilationRequest;
import com.tom.rv2ide.language.services.java.models.ReparsePlan;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.language.services.java.parser.ParseTask;
import com.tom.rv2ide.language.services.java.parser.Parser;
import com.tom.rv2ide.language.services.java.utils.Extractors;
import com.tom.rv2ide.language.services.java.visitors.FindTypeDeclarations;
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
import openjdk.source.tree.BlockTree;
import openjdk.source.tree.ClassTree;
import openjdk.source.tree.ExpressionTree;
import openjdk.source.tree.MethodTree;
import openjdk.source.tree.VariableTree;
import openjdk.source.util.SourcePositions;
import openjdk.source.util.TreePath;
import openjdk.tools.javac.api.JavacTrees;
import openjdk.tools.javac.tree.JCTree;
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
  /** Set when a failed in-place reparse has already mutated the cached javac task. */
  private boolean reparseCorruptedTask;

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
   * Advances the cached semantic task when the strict in-place reparse accepts the request;
   * otherwise recompiles the current request on the stable semantic path.
   *
   * <p>This boundary is intentionally conservative and treated as frozen after IDE validation.
   * Future completion-performance work must not bypass contiguous document identity, method-body
   * containment, mutation-failure isolation, or partial-diagnostic rollback.
   */
  private synchronized void reparseOrRecompile(CompilationRequest request) {
    if (request.reparsePlan == null) {
      recompile(request);
      return;
    }
    ReparseOutcome outcome =
        reparseCorruptedTask ? ReparseOutcome.FAILED_AFTER_MUTATION : tryMethodReparse(request);
    if (outcome == ReparseOutcome.NOT_APPLICABLE) {
      outcome = tryFieldInitializerReparse(request);
    }
    if (outcome == ReparseOutcome.NOT_APPLICABLE) {
      outcome = tryInitializerBlockReparse(request);
    }
    if (outcome == ReparseOutcome.NOT_APPLICABLE) {
      outcome = tryClassModifiersReparse(request);
    }
    if (outcome == ReparseOutcome.SUCCESS) {
      return;
    }
    // A failed in-place mutation must never be reused. recompile() closes and replaces it.
    recompile(request);
  }

  private ReparseOutcome tryMethodReparse(CompilationRequest request) {
    final ReparsePlan plan = request.reparsePlan;
    final String oldContents = validatedContents(request, plan);
    if (oldContents == null) {
      return ReparseOutcome.FAILED;
    }

    final CompilationUnitTree root = cachedCompile.root(plan.file);
    if (root == null) {
      return ReparseOutcome.FAILED;
    }
    final List<Pair<Range, TreePath>> positions =
        cachedCompile.methodPositions.get(plan.file.toAbsolutePath().toString());
    if (positions == null) {
      return ReparseOutcome.FAILED;
    }
    final int[] window = ReparseRangeBounds.changedRange(oldContents, plan.contents);
    if (window == null) {
      // No textual change: advance the document identity so the next request stays contiguous.
      cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
      updateModificationCache(request);
      return ReparseOutcome.SUCCESS;
    }
    Pair<Range, TreePath> candidate = null;
    for (Pair<Range, TreePath> position : positions) {
      final Range range = position.first;
      final int start = range.getStart().requireIndex();
      final int end = range.getEnd().requireIndex();
      if (ReparseRangeBounds.contains(window, start, end)) {
        candidate = position;
        break;
      }
    }
    if (candidate == null) {
      return ReparseOutcome.NOT_APPLICABLE;
    }
    if (!(candidate.second.getLeaf() instanceof MethodTree)) {
      return ReparseOutcome.FAILED;
    }
    final MethodTree method = (MethodTree) candidate.second.getLeaf();

    final JavacTrees trees = JavacTrees.instance(cachedCompile.task);
    final boolean hasBody = method.getBody() != null;
    final long bodyStartLong =
        hasBody ? trees.getSourcePositions().getStartPosition(root, method.getBody()) : -1L;
    final long bodyEndLong =
        hasBody ? trees.getSourcePositions().getEndPosition(root, method.getBody()) : -1L;
    final int bodyStart = bodyStartLong < 0 ? -1 : (int) bodyStartLong;
    final int bodyEnd = bodyEndLong < bodyStartLong ? -1 : (int) bodyEndLong;
    final long declarationStartLong = trees.getSourcePositions().getStartPosition(root, method);
    if (declarationStartLong < 0) {
      return ReparseOutcome.FAILED;
    }
    final int declarationStart = (int) declarationStartLong;
    final int headerEnd = methodHeaderEnd(trees, root, method);
    final List<? extends ExpressionTree> thrown = method.getThrows();
    int throwsStart = -1;
    int throwsEnd = -1;
    if (!thrown.isEmpty()) {
      final long throwsStartLong = trees.getSourcePositions().getStartPosition(root, thrown.get(0));
      final long throwsEndLong =
          trees.getSourcePositions().getEndPosition(root, thrown.get(thrown.size() - 1));
      if (throwsStartLong >= 0 && throwsEndLong >= throwsStartLong) {
        throwsStart = (int) throwsStartLong;
        throwsEnd = (int) throwsEndLong;
      }
    }

    final int insertionUpperBound;
    if (hasBody) {
      insertionUpperBound = bodyStart;
    } else {
      final long methodEnd = trees.getSourcePositions().getEndPosition(root, method);
      insertionUpperBound = methodEnd < 1 ? -1 : (int) methodEnd - 1;
    }
    if (thrown.isEmpty()) {
      final List<? extends VariableTree> parameters = method.getParameters();
      final int parametersEnd =
          parameters.isEmpty()
              ? headerEnd
              : (int) trees.getSourcePositions().getEndPosition(root, parameters.get(parameters.size() - 1));
      if (ReparseUnitSelector.acceptsThrowsInsertion(window, parametersEnd, insertionUpperBound)) {
        return tryThrowsClauseReparse(
            request,
            plan,
            oldContents,
            window,
            root,
            candidate.second,
            window[0],
            window[0],
            true);
      }
    }

    switch (ReparseUnitSelector.select(
        window, bodyStart, bodyEnd, declarationStart, headerEnd, throwsStart, throwsEnd)) {
      case METHOD_MODIFIERS:
        return tryMethodModifiersReparse(
            request,
            plan,
            oldContents,
            window,
            root,
            candidate.second,
            declarationStart,
            headerEnd);
      case THROWS_CLAUSE:
        return tryThrowsClauseReparse(
            request,
            plan,
            oldContents,
            window,
            root,
            candidate.second,
            throwsStart,
            throwsEnd,
            false);
      case METHOD_BODY:
        break;
      default:
        return ReparseOutcome.FAILED;
    }
    final int newBodyEnd = bodyEnd + plan.contents.length() - oldContents.length();

    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final InPlaceReparser reparser = new InPlaceReparser();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final String newBody = plan.contents.substring(bodyStart, newBodyEnd);
    final boolean success = reparser.reparseMethod(info, candidate.second, newBody, plan.contents);
    if (!success) {
      if (reparser.getTaskMutated()) {
        reparseCorruptedTask = true;
        return ReparseOutcome.FAILED_AFTER_MUTATION;
      }
      return ReparseOutcome.FAILED;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return ReparseOutcome.SUCCESS;
  }

  /**
   * Applies a modifier-only change to one method declaration in place.
   *
   * <p>Only the declaration header may change, and only flags that do not decide body legality:
   * annotations, parameters, return type, type parameters and the throws clause must stay untouched.
   * Everything else falls back to a full compilation.
   */
  private ReparseOutcome tryMethodModifiersReparse(
      CompilationRequest request,
      ReparsePlan plan,
      String oldContents,
      int[] window,
      CompilationUnitTree root,
      TreePath methodPath,
      int declarationStart,
      int headerEnd) {
    final int delta = plan.contents.length() - oldContents.length();
    final int newHeaderEnd = headerEnd + delta;
    if (newHeaderEnd < declarationStart || newHeaderEnd > plan.contents.length()) {
      return ReparseOutcome.FAILED;
    }
    final String newModifiers = plan.contents.substring(declarationStart, newHeaderEnd);

    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final InPlaceReparser reparser = new InPlaceReparser();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final boolean success =
        reparser.reparseMethodModifiers(
            info,
            methodPath,
            newModifiers,
            declarationStart,
            oldContents,
            plan.contents,
            window[0],
            window[1]);
    if (!success) {
      if (reparser.getTaskMutated()) {
        reparseCorruptedTask = true;
        return ReparseOutcome.FAILED_AFTER_MUTATION;
      }
      return ReparseOutcome.FAILED;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return ReparseOutcome.SUCCESS;
  }

  /**
   * Replaces or adds a throws clause in place.
   *
   * <p>Each new thrown type is attributed in the method environment and validated against {@code
   * java.lang.Throwable}; the method type is then rebuilt with {@code
   * Types.createMethodTypeWithThrown}. Adding a clause is only accepted as an insertion in front of
   * the body that starts with the {@code throws} keyword.
   */
  private ReparseOutcome tryThrowsClauseReparse(
      CompilationRequest request,
      ReparsePlan plan,
      String oldContents,
      int[] window,
      CompilationUnitTree root,
      TreePath methodPath,
      int throwsStart,
      int throwsEnd,
      boolean addingClause) {
    final int delta = plan.contents.length() - oldContents.length();
    final int newThrowsEnd = throwsEnd + delta;
    if (newThrowsEnd < throwsStart || newThrowsEnd > plan.contents.length()) {
      return ReparseOutcome.FAILED;
    }
    final String newThrows = plan.contents.substring(throwsStart, newThrowsEnd);

    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final InPlaceReparser reparser = new InPlaceReparser();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final boolean success =
        reparser.reparseThrowsClause(
            info,
            methodPath,
            newThrows,
            throwsStart,
            oldContents,
            plan.contents,
            window[0],
            window[1],
            addingClause);
    if (!success) {
      if (reparser.getTaskMutated()) {
        reparseCorruptedTask = true;
        return ReparseOutcome.FAILED_AFTER_MUTATION;
      }
      return ReparseOutcome.FAILED;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return ReparseOutcome.SUCCESS;
  }

  /**
   * End offset of a method declaration header: the earliest of the return type, the first type
   * parameter and the declared name.
   *
   * <p>{@code JCMethodDecl.pos} is the position of the declared name, and a method without written
   * modifiers has no position on its {@code JCModifiers} node (the parser stores
   * {@code Position.NOPOS}), so the header end must be derived from the children instead.
   */
  private static int methodHeaderEnd(JavacTrees trees, CompilationUnitTree root, MethodTree method) {
    final SourcePositions positions = trees.getSourcePositions();
    long headerEnd = ((JCTree) method).pos;
    if (method.getReturnType() != null) {
      final long returnTypeStart = positions.getStartPosition(root, method.getReturnType());
      if (returnTypeStart < 0) {
        return -1;
      }
      headerEnd = headerEnd < 0 ? returnTypeStart : Math.min(headerEnd, returnTypeStart);
    }
    if (!method.getTypeParameters().isEmpty()) {
      final long typeParameterStart = positions.getStartPosition(root, method.getTypeParameters().get(0));
      if (typeParameterStart < 0) {
        return -1;
      }
      headerEnd = headerEnd < 0 ? typeParameterStart : Math.min(headerEnd, typeParameterStart);
    }
    return headerEnd < 0 ? -1 : (int) headerEnd;
  }

  /**
   * Returns the previous document text when this request may mutate the cached task in place, or
   * {@code null} when the strict document identity is not established.
   */
  private String validatedContents(CompilationRequest request, ReparsePlan plan) {
    if (plan == null || cachedCompile == null || cachedCompile.closed) {
      return null;
    }
    if (request.sources.size() != 1 || plan.contents == null) {
      return null;
    }
    final JavaFileObject requestedSource = request.sources.iterator().next();
    if (!requestedSource.toUri().normalize().equals(plan.file.toUri().normalize())) {
      return null;
    }
    final String oldContents = cachedCompile.currentSourceContents();
    if (oldContents == null) {
      return null;
    }
    // The MVP requires an explicit contiguous document identity. A task produced by Diagnostics or
    // another request without version metadata must be re-established first.
    if (cachedCompile.documentVersion() < 0 || plan.documentVersion < 0) {
      return null;
    }
    if (plan.documentVersion != cachedCompile.documentVersion() + 1) {
      return null;
    }
    if (cachedCompile.documentRevision() < 0 || plan.documentRevision < 0) {
      return null;
    }
    return plan.documentRevision <= cachedCompile.documentRevision() ? null : oldContents;
  }

  /**
   * Replaces a class-member field initializer in place.
   *
   * The field symbol itself is unchanged, so only the initializer expression is re-attributed; the
   * class member table does not need to be re-entered.
   */
  private ReparseOutcome tryFieldInitializerReparse(CompilationRequest request) {
    final ReparsePlan plan = request.reparsePlan;
    final String oldContents = validatedContents(request, plan);
    if (oldContents == null) {
      return ReparseOutcome.FAILED;
    }
    final CompilationUnitTree root = cachedCompile.root(plan.file);
    if (root == null) {
      return ReparseOutcome.FAILED;
    }
    final List<Pair<Range, TreePath>> positions =
        cachedCompile.fieldPositions.get(plan.file.toAbsolutePath().toString());
    if (positions == null) {
      return ReparseOutcome.FAILED;
    }
    final int[] window = ReparseRangeBounds.changedRange(oldContents, plan.contents);
    if (window == null) {
      // No textual change: advance the document identity so the next request stays contiguous.
      cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
      updateModificationCache(request);
      return ReparseOutcome.SUCCESS;
    }
    Pair<Range, TreePath> candidate = null;
    for (Pair<Range, TreePath> position : positions) {
      final Range range = position.first;
      final int start = range.getStart().requireIndex();
      final int end = range.getEnd().requireIndex();
      if (ReparseRangeBounds.contains(window, start, end)) {
        candidate = position;
        break;
      }
    }
    if (candidate == null) {
      return ReparseOutcome.NOT_APPLICABLE;
    }
    if (!(candidate.second.getLeaf() instanceof VariableTree)) {
      return ReparseOutcome.FAILED;
    }
    final VariableTree field = (VariableTree) candidate.second.getLeaf();
    final JavacTrees trees = JavacTrees.instance(cachedCompile.task);
    final long declarationStartLong = trees.getSourcePositions().getStartPosition(root, field);
    if (declarationStartLong < 0) {
      return ReparseOutcome.FAILED;
    }
    final int declarationStart = (int) declarationStartLong;
    final int headerEnd = fieldHeaderEnd(trees, root, field);

    final boolean hasInitializer = field.getInitializer() != null;
    final long initStartLong =
        hasInitializer ? trees.getSourcePositions().getStartPosition(root, field.getInitializer()) : -1L;
    final long initEndLong =
        hasInitializer ? trees.getSourcePositions().getEndPosition(root, field.getInitializer()) : -1L;
    final int initStart = initStartLong < 0 ? -1 : (int) initStartLong;
    final int initEnd = initEndLong < initStartLong ? -1 : (int) initEndLong;

    switch (ReparseUnitSelector.selectField(
        window, initStart, initEnd, declarationStart, headerEnd)) {
      case FIELD_MODIFIERS:
        return tryFieldModifiersReparse(
            request,
            plan,
            oldContents,
            window,
            root,
            candidate.second,
            declarationStart,
            headerEnd);
      case FIELD_INITIALIZER:
        break;
      default:
        return ReparseOutcome.FAILED;
    }
    final int newInitEnd = initEnd + plan.contents.length() - oldContents.length();
    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final InPlaceReparser reparser = new InPlaceReparser();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final String newInitializer = plan.contents.substring(initStart, newInitEnd);
    final boolean success =
        reparser.reparseFieldInitializer(info, candidate.second, newInitializer, plan.contents);
    if (!success) {
      if (reparser.getTaskMutated()) {
        reparseCorruptedTask = true;
        return ReparseOutcome.FAILED_AFTER_MUTATION;
      }
      return ReparseOutcome.FAILED;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return ReparseOutcome.SUCCESS;
  }

  /**
   * Applies a modifier-only change to one class-member field.
   *
   * <p>Only access, {@code volatile} and {@code transient} may change: {@code final} and {@code
   * static} decide constant folding and initialization order, which this path does not recompute.
   */
  private ReparseOutcome tryFieldModifiersReparse(
      CompilationRequest request,
      ReparsePlan plan,
      String oldContents,
      int[] window,
      CompilationUnitTree root,
      TreePath fieldPath,
      int declarationStart,
      int headerEnd) {
    final int delta = plan.contents.length() - oldContents.length();
    final int newHeaderEnd = headerEnd + delta;
    if (newHeaderEnd < declarationStart || newHeaderEnd > plan.contents.length()) {
      return ReparseOutcome.FAILED;
    }
    final String newModifiers = plan.contents.substring(declarationStart, newHeaderEnd);

    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final InPlaceReparser reparser = new InPlaceReparser();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final boolean success =
        reparser.reparseFieldModifiers(
            info,
            fieldPath,
            newModifiers,
            declarationStart,
            oldContents,
            plan.contents,
            window[0],
            window[1]);
    if (!success) {
      if (reparser.getTaskMutated()) {
        reparseCorruptedTask = true;
        return ReparseOutcome.FAILED_AFTER_MUTATION;
      }
      return ReparseOutcome.FAILED;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return ReparseOutcome.SUCCESS;
  }

  /**
   * End offset of a field declaration header: the earliest of the declared name and the type.
   *
   * <p>{@code JCVariableDecl.pos} is the position of the declared name, and a field without written
   * modifiers has no position on its {@code JCModifiers} node, so the header end must be derived from
   * the declared type instead.
   */
  private static int fieldHeaderEnd(JavacTrees trees, CompilationUnitTree root, VariableTree field) {
    final SourcePositions positions = trees.getSourcePositions();
    long headerEnd = ((JCTree) field).pos;
    if (field.getType() != null) {
      final long typeStart = positions.getStartPosition(root, field.getType());
      if (typeStart < 0) {
        return -1;
      }
      headerEnd = headerEnd < 0 ? typeStart : Math.min(headerEnd, typeStart);
    }
    return headerEnd < 0 ? -1 : (int) headerEnd;
  }

  /**
   * Replaces a static or instance initializer block in place.
   *
   * The block is re-attributed in the class environment; javac gives class-level blocks their own
   * BLOCK scope, so no class member table entry changes.
   */
  private ReparseOutcome tryInitializerBlockReparse(CompilationRequest request) {
    final ReparsePlan plan = request.reparsePlan;
    final String oldContents = validatedContents(request, plan);
    if (oldContents == null) {
      return ReparseOutcome.FAILED;
    }
    final CompilationUnitTree root = cachedCompile.root(plan.file);
    if (root == null) {
      return ReparseOutcome.FAILED;
    }
    final List<Pair<Range, TreePath>> positions =
        cachedCompile.initializerBlockPositions.get(plan.file.toAbsolutePath().toString());
    if (positions == null) {
      return ReparseOutcome.FAILED;
    }
    final int[] window = ReparseRangeBounds.changedRange(oldContents, plan.contents);
    if (window == null) {
      // No textual change: advance the document identity so the next request stays contiguous.
      cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
      updateModificationCache(request);
      return ReparseOutcome.SUCCESS;
    }
    Pair<Range, TreePath> candidate = null;
    for (Pair<Range, TreePath> position : positions) {
      final Range range = position.first;
      final int start = range.getStart().requireIndex();
      final int end = range.getEnd().requireIndex();
      if (ReparseRangeBounds.contains(window, start, end)) {
        candidate = position;
        break;
      }
    }
    if (candidate == null) {
      return ReparseOutcome.NOT_APPLICABLE;
    }
    if (!(candidate.second.getLeaf() instanceof BlockTree)) {
      return ReparseOutcome.FAILED;
    }
    final BlockTree block = (BlockTree) candidate.second.getLeaf();
    final JavacTrees trees = JavacTrees.instance(cachedCompile.task);
    final long blockStartLong = trees.getSourcePositions().getStartPosition(root, block);
    final long blockEndLong = trees.getSourcePositions().getEndPosition(root, block);
    if (blockStartLong < 0 || blockEndLong < blockStartLong) {
      return ReparseOutcome.FAILED;
    }
    final int blockStart = (int) blockStartLong;
    final int blockEnd = (int) blockEndLong;
    if (!ReparseRangeBounds.contains(window, blockStart, blockEnd)) {
      return ReparseOutcome.FAILED;
    }
    final int newBlockEnd = blockEnd + plan.contents.length() - oldContents.length();
    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final InPlaceReparser reparser = new InPlaceReparser();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final String newBlock = plan.contents.substring(blockStart, newBlockEnd);
    final boolean success =
        reparser.reparseInitializerBlock(info, candidate.second, newBlock, plan.contents);
    if (!success) {
      if (reparser.getTaskMutated()) {
        reparseCorruptedTask = true;
        return ReparseOutcome.FAILED_AFTER_MUTATION;
      }
      return ReparseOutcome.FAILED;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return ReparseOutcome.SUCCESS;
  }

  /**
   * Applies a modifier-only change to one class declaration header.
   *
   * <p>Only access, {@code final}, {@code abstract}, {@code static} and {@code strictfp} may change;
   * structural bits and the legality of the resulting combination are left to a full compilation.
   */
  private ReparseOutcome tryClassModifiersReparse(CompilationRequest request) {
    final ReparsePlan plan = request.reparsePlan;
    final String oldContents = validatedContents(request, plan);
    if (oldContents == null) {
      return ReparseOutcome.FAILED;
    }
    final CompilationUnitTree root = cachedCompile.root(plan.file);
    if (root == null) {
      return ReparseOutcome.FAILED;
    }
    final List<Pair<Range, TreePath>> positions =
        cachedCompile.classPositions.get(plan.file.toAbsolutePath().toString());
    if (positions == null) {
      return ReparseOutcome.FAILED;
    }
    final int[] window = ReparseRangeBounds.changedRange(oldContents, plan.contents);
    if (window == null) {
      // No textual change: advance the document identity so the next request stays contiguous.
      cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
      updateModificationCache(request);
      return ReparseOutcome.SUCCESS;
    }
    Pair<Range, TreePath> candidate = null;
    for (Pair<Range, TreePath> position : positions) {
      final Range range = position.first;
      final int start = range.getStart().requireIndex();
      final int end = range.getEnd().requireIndex();
      if (ReparseRangeBounds.contains(window, start, end)) {
        candidate = position;
        break;
      }
    }
    if (candidate == null) {
      return ReparseOutcome.NOT_APPLICABLE;
    }
    if (!(candidate.second.getLeaf() instanceof ClassTree)) {
      return ReparseOutcome.FAILED;
    }
    final ClassTree classTree = (ClassTree) candidate.second.getLeaf();
    final JavacTrees trees = JavacTrees.instance(cachedCompile.task);
    final long declarationStartLong = trees.getSourcePositions().getStartPosition(root, classTree);
    if (declarationStartLong < 0) {
      return ReparseOutcome.FAILED;
    }
    final int declarationStart = (int) declarationStartLong;
    final int headerEnd = classHeaderEnd(classTree);
    if (ReparseUnitSelector.selectClass(window, declarationStart, headerEnd)
        != ReparseUnit.CLASS_MODIFIERS) {
      return ReparseOutcome.FAILED;
    }
    final int delta = plan.contents.length() - oldContents.length();
    final int newHeaderEnd = headerEnd + delta;
    if (newHeaderEnd < declarationStart || newHeaderEnd > plan.contents.length()) {
      return ReparseOutcome.FAILED;
    }
    final String newModifiers = plan.contents.substring(declarationStart, newHeaderEnd);

    if (request.configureContext != null) {
      request.configureContext.accept(cachedCompile.task.getContext());
    }
    final InPlaceReparser reparser = new InPlaceReparser();
    final CompilationInfo info =
        new CompilationInfo(cachedCompile.task, cachedCompile.diagnosticListener, root);
    final boolean success =
        reparser.reparseClassModifiers(
            info,
            candidate.second,
            newModifiers,
            declarationStart,
            oldContents,
            plan.contents,
            window[0],
            window[1]);
    if (!success) {
      if (reparser.getTaskMutated()) {
        reparseCorruptedTask = true;
        return ReparseOutcome.FAILED_AFTER_MUTATION;
      }
      return ReparseOutcome.FAILED;
    }
    cachedCompile.updatePositions(root, true);
    cachedCompile.updateDocumentState(plan.contents, plan.documentVersion, plan.documentRevision);
    updateModificationCache(request);
    return ReparseOutcome.SUCCESS;
  }

  /**
   * Start of the {@code class}/{@code interface}/{@code enum} keyword, which is where the modifier
   * region of a class declaration ends.
   */
  private static int classHeaderEnd(ClassTree classTree) {
    final int pos = ((JCTree) classTree).pos;
    return pos < 0 ? -1 : pos;
  }

  private enum ReparseOutcome {
    SUCCESS,
    FAILED,
    FAILED_AFTER_MUTATION,
    /** The edited window is outside every unit of this kind, so a narrower unit may take over. */
    NOT_APPLICABLE,
  }


  private synchronized void recompile(CompilationRequest request) {
    close();
    reparseCorruptedTask = false;
    this.cachedCompile = performCompilation(request);
    updateModificationCache(request);
  }

  public synchronized void close() {
    if (cachedCompile != null) {
     cachedCompile.close();
     cachedCompile.borrow.close();
    cachedCompile = null;
    }
    reparseCorruptedTask = false;
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
