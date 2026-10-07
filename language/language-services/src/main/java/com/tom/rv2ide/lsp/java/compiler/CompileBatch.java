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

import static com.tom.rv2ide.javac.config.JavacConfigProvider.PROP_ANDROIDIDE_JAVA_HOME;
import static com.tom.rv2ide.javac.config.JavacConfigProvider.disableModules;
import static com.tom.rv2ide.javac.config.JavacConfigProvider.enableModules;
import static com.tom.rv2ide.javac.config.JavacConfigProvider.setLatestSourceVersion;
import static com.tom.rv2ide.javac.config.JavacConfigProvider.setLatestSupportedSourceVersion;
import static com.tom.rv2ide.utils.Environment.JAVA_HOME;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import com.tom.rv2ide.builder.model.IJavaCompilerSettings;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.lsp.java.kotlin.KotlinAbiStubJavaFileObject;
import com.tom.rv2ide.javac.services.compiler.ReusableBorrow;
import com.tom.rv2ide.javac.services.partial.DiagnosticListenerImpl;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.lsp.java.visitors.MethodRangeScanner;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.projects.util.StringSearch;
import com.tom.rv2ide.tooling.api.ProjectType;
import com.tom.rv2ide.utils.ClassTrie;
import com.tom.rv2ide.utils.SourceClassTrie;
import com.tom.rv2ide.utils.StopWatch;
import java.io.File;
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
import java.util.Set;
import java.util.function.Consumer;
import jdkx.lang.model.SourceVersion;
import jdkx.tools.Diagnostic;
import jdkx.tools.JavaFileObject;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.util.TreePath;
import openjdk.tools.javac.api.ClientCodeWrapper;
import openjdk.tools.javac.api.JavacTaskImpl;
import openjdk.tools.javac.code.Kinds;
import openjdk.tools.javac.util.JCDiagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompileBatch implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(CompileBatch.class);
  public static final String DEFAULT_COMPILER_SOURCE_AND_TARGET_VERSION = "11";
  protected final JavaCompilerService parent;
  protected final ReusableBorrow borrow;
  protected final JavacTaskImpl task;
  protected final List<CompilationUnitTree> roots;
  /** Source URIs already accepted from the javac parse result. */
  private final Set<String> processedSourceUris = new HashSet<>();
  protected final Map<String, List<Pair<Range, TreePath>>> methodPositions = new HashMap<>();
  protected DiagnosticListenerImpl diagnosticListener;
  private String currentSourceContents;
  private int documentVersion = -1;
  private long documentRevision = -1L;
  /** Indicates the task that requested the compilation is finished with it. */
  boolean closed;

  CompileBatch(
    JavaCompilerService parent,
    Collection<? extends JavaFileObject> files,
    CompilationRequest compilationRequest) {
    this.parent = parent;
    this.borrow = batchTask(parent, files);
    this.task = borrow.task;
    this.roots = new ArrayList<>();
    String documentStateOrigin = "NONE";
    if (compilationRequest.methodReparsePlan != null) {
      final var plan = compilationRequest.methodReparsePlan;
      this.currentSourceContents = plan.contents;
      this.documentVersion = plan.documentVersion;
      this.documentRevision = plan.documentRevision;
      documentStateOrigin = "METHOD_REPARSE_PLAN";
    } else if (compilationRequest.documentState != null
        && compilationRequest.sources.size() == 1) {
      final var state = compilationRequest.documentState;
      final var requestedSource = compilationRequest.sources.iterator().next();
      try {
        final boolean sameSource =
            requestedSource.toUri().normalize().equals(state.file.toUri().normalize());
        final boolean sameContents =
            requestedSource.getCharContent(true).toString().equals(state.contents);
        if (sameSource
            && sameContents
            && state.documentVersion >= 0
            && state.documentRevision >= 0) {
          this.currentSourceContents = state.contents;
          this.documentVersion = state.documentVersion;
          this.documentRevision = state.documentRevision;
          documentStateOrigin = "DOCUMENT_STATE";
        }
      } catch (Exception ignored) {
        // Keep the document identity unknown when the supplied snapshot cannot be proven.
      }
    }
    if (this.currentSourceContents == null
        && compilationRequest.sources.size() == 1
        && compilationRequest.sources.iterator().next() instanceof SourceFileObject) {
      try {
        this.currentSourceContents =
            compilationRequest.sources.iterator().next().getCharContent(true).toString();
        documentStateOrigin = "SOURCE_CONTENTS_ONLY";
      } catch (Exception ignored) {
        this.currentSourceContents = null;
      }
    }
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "CompileBatch init parentHash={} fileCount={} files={} documentStateOrigin={} documentVersion={} documentRevision={}",
          System.identityHashCode(parent),
          files.size(),
          describeSources(files),
          documentStateOrigin,
          documentVersion,
          documentRevision);
    }
  
    final var context = task.getContext();
    final var config = JavaCompilerConfig.instance(context);
    config.setFiles(files);

    if (compilationRequest.configureContext != null) {
      compilationRequest.configureContext.accept(context);
    }
    Objects.requireNonNull(compilationRequest, "A task processor is required");

    try {
      compilationRequest.compilationTaskProcessor.process(borrow.task, this::processCompilationUnit);
    } catch (Throwable e) {
      LOG.error(
          "CompileBatch processing failed parentHash={} files={} contextPresent={} firstSource={}",
          System.identityHashCode(parent),
          files.size(),
          context != null,
          files.iterator().hasNext() ? files.iterator().next().toUri() : null,
          e);
      throw new RuntimeException(e);
    }

    config.setFiles(null);
  }
  private void processCompilationUnit(final CompilationUnitTree root) {
    if (KotlinAbiStubJavaFileObject.URI_SCHEME.equals(root.getSourceFile().toUri().getScheme())) {
      // The stub must be parsed/analyzed by javac, but has no real Java path and must not enter
      // method-position or navigation indexes. KotlinDefinitionFallback remains the source mapper.
      return;
    }
    final String sourceUri = root.getSourceFile().toUri().normalize().toString();
    if (!processedSourceUris.add(sourceUri)) {
      if (IdeLogConfig.shouldLogDebug()) {
        LOG.debug("Ignoring duplicate CompilationUnitTree source={}", sourceUri);
      }
      return;
    }
    roots.add(root);
    updatePositions(root, false);
  }
  

  void updatePositions(CompilationUnitTree tree, boolean allowDuplicate) {
    final StopWatch watch = new StopWatch("Scan method positions");
    final List<Pair<Range, TreePath>> positions = new ArrayList<>();
    new MethodRangeScanner(this.task).scan(tree, positions);
    final String path = new File(tree.getSourceFile().toUri()).getAbsolutePath();
    final List<Pair<Range, TreePath>> old = this.methodPositions.put(path, positions);
    if (old != null && !allowDuplicate) {
      throw new IllegalStateException(
          "Duplicate CompilationUnitTree for file:" + tree.getSourceFile().toUri());
    }
    if (IdeLogConfig.shouldLogIde()) {
      watch.log();
    }

  }

  @NonNull
  static String describeSources(@NonNull Collection<? extends JavaFileObject> sources) {
    final List<String> entries = new ArrayList<>();
    for (JavaFileObject source : sources) {
      if (source == null) {
        entries.add("<null-source>");
        continue;
      }
      entries.add(
          source.getClass().getName()
              + "|kind="
              + source.getKind()
              + "|name="
              + source.getName()
              + "|uri="
              + source.toUri());
    }
    return entries.toString();
  }

  private ReusableBorrow batchTask(
      @NonNull JavaCompilerService parent, @NonNull Collection<? extends JavaFileObject> sources) {
 
    parent.diagnostics.clear();
    final Iterable<String> options = options();

    diagnosticListener =
        new DiagnosticListenerWrapper(parent.diagnostics::add, sources.iterator().next());

    final ReusableBorrow borrow;
    try {
      borrow =
          parent.compiler.getTask(
              parent.fileManager, diagnosticListener, options, Collections.emptyList(), sources);
    } catch (Throwable err) {
      LOG.error(
          "CompileBatch batchTask getTask failed parentHash={} sources={} fileManagerClass={} optionsClass={} firstSource={}",
          System.identityHashCode(parent),
          sources.size(),
          parent.fileManager == null ? null : parent.fileManager.getClass().getName(),
          options == null ? null : options.getClass().getName(),
          sources.iterator().hasNext() ? sources.iterator().next().toUri() : null,
          err);
      throw err;
    }
 
    if (parent.fileManager != null) {
      try {
        parent.fileManager.setContext(borrow.task.getContext());
      } catch (Throwable err) {
        LOG.error(
            "CompileBatch setContext failed parentHash={} sourceCount={} contextPresent={}",
            System.identityHashCode(parent),
            sources.size(),
            borrow.task != null && borrow.task.getContext() != null,
            err);
        throw err;
      }
    }
 
    return borrow;
  }

  @NonNull
  private List<String> options() {
    List<String> options = new ArrayList<>();

    // This won't be used if the current module is Android module project
    System.setProperty(PROP_ANDROIDIDE_JAVA_HOME, JAVA_HOME.getAbsolutePath());
    if (this.parent.module != null && this.parent.module.getType() == ProjectType.Android) {
      setLatestSourceVersion(SourceVersion.RELEASE_8);
      setLatestSupportedSourceVersion(SourceVersion.RELEASE_11);
      disableModules();
    } else {
      setLatestSourceVersion(SourceVersion.RELEASE_11);
      setLatestSupportedSourceVersion(SourceVersion.RELEASE_11);
      enableModules();
    }

    setupCompileOptions(parent.module, options);
    Collections.addAll(options, "-proc:none", "-g");

    Collections.addAll(
        options,
        "-XDcompilePolicy=byfile",
        "-XD-Xprefer=source",
        "-XDide",
        "-XDkeepCommentsOverride=keep",
        "-XDsuppressAbortOnBadClassFile",
        "-XDshould-stop.at=GENERATE",
        "-XDdiags.formatterOptions=-source",
        "-XDdiags.layout=%L%m|%L%m|%L%m",
        "-XDbreakDocCommentParsingOnError=false",
        "-Xlint:cast",
        "-Xlint:deprecation",
        "-Xlint:empty",
        "-Xlint:fallthrough",
        "-Xlint:finally",
        "-Xlint:path",
        "-Xlint:unchecked",
        "-Xlint:varargs",
        "-Xlint:static");

    return options;
  }

  protected void setupCompileOptions(final ModuleProject module, final List<String> options) {
    if (module == null) {
      Collections.addAll(
          options,
          "-source",
          DEFAULT_COMPILER_SOURCE_AND_TARGET_VERSION,
          "-target",
          DEFAULT_COMPILER_SOURCE_AND_TARGET_VERSION);
      return;
    }
    final IJavaCompilerSettings compilerSettings = module.getCompilerSettings();
    options.add("-source");
    options.add(compilerSettings.getJavaSourceVersion());
    options.add("-target");
    options.add(compilerSettings.getJavaBytecodeVersion());
  }
  String currentSourceContents() {
    return currentSourceContents;
  }

  int documentVersion() {
    return documentVersion;
  }

  long documentRevision() {
    return documentRevision;
  }

  void updateDocumentState(String contents, int version, long revision) {
    this.currentSourceContents = contents;
    this.documentVersion = version;
    this.documentRevision = revision;
  }

  CompilationUnitTree root(Path file) {
    final String target = file.toUri().normalize().toString();
    for (CompilationUnitTree root : roots) {
      if (root.getSourceFile().toUri().normalize().toString().equals(target)) {
        return root;
      }
    }
    return null;
  }

  @Override
  public void close() {
    closed = true;
    methodPositions.clear();
    roots.clear();
    diagnosticListener = null;
  }


  /**
   * If the compilation failed because javac didn't find some package-private files in source files
   * with different names, list those source files.
   */
  Set<Path> needsAdditionalSources() {

    if (parent.getModule() == null) {
      return Collections.emptySet();
    }

    Set<Path> addFiles = new HashSet<>();
    for (Diagnostic<? extends JavaFileObject> err : parent.diagnostics) {
      if (!"compiler.err.cant.resolve.location".equals(err.getCode())) {
        continue;
      }
      if (!isValidFileRange(err)) {
        continue;
      }

      addPackagePrivateSiblingSource(err, addFiles);
      addMissingTypeSource(err, addFiles);
    }
    return addFiles;
  }

  private void addPackagePrivateSiblingSource(
      @NonNull final Diagnostic<? extends JavaFileObject> err, @NonNull final Set<Path> addFiles) {
    final String packageName = packageName(err);
    final ClassTrie.Node node = parent.getModule().compileJavaSourceClasses.findNode(packageName);
    if (node != null && node.isClass() && node instanceof SourceClassTrie.SourceNode) {
      addFiles.add(((SourceClassTrie.SourceNode) node).getFile());
    }
  }

  private void addMissingTypeSource(
      @NonNull final Diagnostic<? extends JavaFileObject> err, @NonNull final Set<Path> addFiles) {
    final String missingClassName = missingClassName(err);
    if (missingClassName == null || missingClassName.isBlank()) {
      return;
    }

    Path exact = parent.findTypeDeclaration(missingClassName);
    if (exact != CompilerProvider.NOT_FOUND) {
      addFiles.add(exact);
      return;
    }

    if (missingClassName.indexOf('.') >= 0) {
      return;
    }

    for (String qualifiedName : parent.findQualifiedNames(missingClassName, true)) {
      Path inferred = parent.findTypeDeclaration(qualifiedName);
      if (inferred != CompilerProvider.NOT_FOUND) {
        addFiles.add(inferred);
        return;
      }
    }
  }

  private String packageName(Diagnostic<? extends JavaFileObject> err) {
    if (err instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper) {
      JCDiagnostic diagnostic = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) err).d;
      JCDiagnostic.DiagnosticPosition pos = diagnostic.getDiagnosticPosition();
      Object[] args = diagnostic.getArgs();
      Kinds.KindName kind = (Kinds.KindName) args[0];
      if (kind == Kinds.KindName.CLASS) {
        if (pos.toString().contains(".")) {
          return pos.toString().substring(0, pos.toString().lastIndexOf('.'));
        }
      }
    }
    Path file = Paths.get(err.getSource().toUri());
    return StringSearch.packageName(file);
  }

  private String missingClassName(Diagnostic<? extends JavaFileObject> err) {
    if (!(err instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper)) {
      return null;
    }

    JCDiagnostic diagnostic = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) err).d;
    Object[] args = diagnostic.getArgs();
    if (args == null || args.length < 2 || !(args[0] instanceof Kinds.KindName)) {
      return null;
    }

    Kinds.KindName kind = (Kinds.KindName) args[0];
    if (kind != Kinds.KindName.CLASS) {
      return null;
    }

    String positionText = diagnostic.getDiagnosticPosition() == null
        ? ""
        : diagnostic.getDiagnosticPosition().toString();
    if (isResolvableClassName(positionText)) {
      return positionText;
    }

    for (Object arg : args) {
      if (arg == null) {
        continue;
      }

      String candidate = String.valueOf(arg).trim();
      if (isResolvableClassName(candidate)) {
        return candidate;
      }
    }

    return null;
  }

  private boolean isResolvableClassName(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return false;
    }

    if (candidate.contains(" ") || candidate.contains("/") || candidate.contains("(")) {
      return false;
    }

    char first = candidate.charAt(0);
    if (!Character.isJavaIdentifierStart(first)) {
      return false;
    }

    for (int i = 1; i < candidate.length(); i++) {
      char ch = candidate.charAt(i);
      if (!(Character.isJavaIdentifierPart(ch) || ch == '.')) {
        return false;
      }
    }

    return true;
  }

  private boolean isValidFileRange(Diagnostic<? extends JavaFileObject> d) {
    return d.getSource().toUri().getScheme().equals("file")
        && d.getStartPosition() >= 0
        && d.getEndPosition() >= 0;
  }

  public static class DiagnosticListenerWrapper extends DiagnosticListenerImpl {

    private final Consumer<Diagnostic<? extends JavaFileObject>> consumer;

    public DiagnosticListenerWrapper(
        final Consumer<Diagnostic<? extends JavaFileObject>> consumer, JavaFileObject jfo) {
      super(jfo);
      this.consumer = consumer;
    }

    @Override
    public void report(final Diagnostic<? extends JavaFileObject> diagnostic) {
      consumer.accept(diagnostic);
      super.report(diagnostic);
    }
  }
}
