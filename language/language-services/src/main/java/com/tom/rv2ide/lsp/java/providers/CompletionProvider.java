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
package com.tom.rv2ide.lsp.java.providers;

import static com.tom.rv2ide.lsp.api.HelpersKt.describeSnippet;
import static com.tom.rv2ide.progress.ProgressManager.abortIfCancelled;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.blankj.utilcode.util.ReflectUtils;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.tom.rv2ide.lsp.api.AbstractServiceProvider;

import com.tom.rv2ide.lsp.api.ICompletionProvider;
import com.tom.rv2ide.lsp.api.IServerSettings;
import com.tom.rv2ide.lsp.internal.model.CachedCompletion;
import com.tom.rv2ide.lsp.java.compiler.CompileTask;
import com.tom.rv2ide.lsp.java.compiler.CompletionInfo;
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerConfig;
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService;
import com.tom.rv2ide.lsp.java.compiler.SourceFileObject;
import com.tom.rv2ide.lsp.java.compiler.SynchronizedTask;
import com.tom.rv2ide.lsp.java.models.CompilationRequest;
import com.tom.rv2ide.lsp.java.models.MethodReparsePlan;

import com.tom.rv2ide.lsp.java.providers.completion.IJavaCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.IdentifierCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.ImportCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.KeywordCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.MemberReferenceCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.MemberSelectCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.QualifiedNewClassCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.SwitchConstantCompletionProvider;
import com.tom.rv2ide.lsp.java.providers.completion.ts.TSCompletionSuppression;
import com.tom.rv2ide.lsp.java.providers.completion.ts.TSCompletionSuppressionReason;
import com.tom.rv2ide.lsp.java.utils.ASTFixer;
import com.tom.rv2ide.lsp.java.utils.CancelChecker;
import com.tom.rv2ide.lsp.java.visitors.FindCompletionsAt;
import com.tom.rv2ide.lsp.models.CompletionParams;
import com.tom.rv2ide.lsp.models.CompletionResult;
import com.tom.rv2ide.utils.DocumentUtils;
import io.github.rosemoe.sora.lang.completion.snippet.CodeSnippet;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.function.Consumer;
import openjdk.source.tree.Tree;
import openjdk.source.util.TreePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompletionProvider extends AbstractServiceProvider implements ICompletionProvider {

  public static final int MAX_COMPLETION_ITEMS = CompletionResult.MAX_ITEMS;
  private static final Logger LOG = LoggerFactory.getLogger(CompletionProvider.class);
  private final JavaCompilerService compiler;
  private final CachedCompletion cache;
  private final Consumer<CachedCompletion> nextCacheConsumer;

  /**
   * Creates one request-scoped provider. The reusable compiler is module-owned; this object only
   * holds immutable request wiring so overlapping completion threads cannot overwrite each other.
   */
  public CompletionProvider(
      JavaCompilerService compiler,
      IServerSettings settings,
      CachedCompletion cache,
      Consumer<CachedCompletion> nextCacheConsumer
  ) {
    this.compiler = compiler;
    this.cache = cache;
    this.nextCacheConsumer = nextCacheConsumer;
    super.applySettings(settings);
  }

  @Override
  public boolean canComplete(Path file) {
    return ICompletionProvider.super.canComplete(file) && DocumentUtils.isJavaFile(file);
  }

  @NonNull
  @Override
  public CompletionResult complete(@NonNull CompletionParams params) {
    final var synchronizedTask = compiler.getSynchronizedTask();
    if (synchronizedTask.isBusy()) {
      CompletionResult busyFallback = tryServeBusyFallback(params);
      if (busyFallback != null) {
        return busyFallback;
      }
      if (IdeLogConfig.shouldLogWarn()) {
        LOG.warn(
            "Completion busy and no fallback result is available file={} cursor={} prefix={} version={} revision={} compilerHash={} cachePresent={} providerScoped=true",
            params.getFile(),
            params.getPosition().requireIndex(),
            params.getPrefix(),
            params.getDocumentVersion(),
            params.getDocumentRevision(),
            System.identityHashCode(compiler),
            cache != null);
        synchronizedTask.logStats();
      }
      return CompletionResult.EMPTY;
    }

    try {
      abortIfCancelled();
      abortCompletionIfCancelled();
      return completeInternal(params);
    } catch (Throwable err) {
      if (CancelChecker.isCancelled(err)) {
        if (IdeLogConfig.shouldLogIde()) {
          LOG.info("Completion request cancelled");
        }
      } else {
        final Path file = params.getFile();
        final int line = params.getPosition().getLine();
        final int column = params.getPosition().getColumn();
        final long index = params.getPosition().getIndex();
        final String prefix = params.getPrefix();
        LOG.error(
            "An error occurred while computing completions file={} line={} column={} index={} prefix={} compilerHash={} currentContextPresent={} synchronizedTaskBusy={} errorType={} errorMessage={}",
            file,
            line,
            column,
            index,
            prefix,
            System.identityHashCode(compiler),
            compiler != null && compiler.compiler.currentContext != null,
            synchronizedTask.isBusy(),
            err.getClass().getName(),
            err.getMessage(),
            err);
      }
      throw err;
    }
  }

  @Nullable
  private CompletionResult tryServeBusyFallback(@NonNull final CompletionParams params) {
    if (this.cache == null || isQualifiedNewClassPrefix(params.getPrefix())) {
      return null;
    }

    final String partial = params.getPrefix() == null ? "" : partialIdentifier(params.requirePrefix(), params.requirePrefix().length());
    if (this.cache.canUseCache(params)) {
      return mapCachedBusyResult(partial);
    }
    return null;
  }

  @NonNull
  private CompletionResult mapCachedBusyResult(@NonNull final String partial) {
    final CompletionResult result = CompletionResult.mapAndFilter(this.cache.result, partial, item -> {
      final var description = item.getSnippetDescription();
      var deleteSelected = true;
      var allowCommands = false;
      CodeSnippet snippet = null;
      if (description != null) {
        deleteSelected = description.getDeleteSelected();
        allowCommands = description.getAllowCommandExecution();
        snippet = description.getSnippet();
      }
      item.setSnippetDescription(describeSnippet(partial, deleteSelected, snippet, allowCommands));
    });
    result.markCached();
    return result;
  }
private TSCompletionSuppressionReason classifyCompletionSuppression(
      @NonNull CompletionParams params, @NonNull String contents) {
    try {
      final Path file = params.getFile();
      final int line = params.getPosition().getLine();
      final int column = params.getPosition().getColumn();
      final long cursor = params.getPosition().requireIndex();
      return TSCompletionSuppression.classify(file, contents, cursor, line, column);
    } catch (Throwable ignored) {
      return TSCompletionSuppressionReason.NONE;
    }
  }


  @NonNull
  private CompletionResult completeInternal(final @NonNull CompletionParams params) {
    Path file = params.getFile();
    int line = params.getPosition().getLine();
    int column = params.getPosition().getColumn();
    if (IdeLogConfig.shouldLogIde()) {
      LOG.debug("Complete at {}({},{})...", file.getFileName(), line, column);
    }

    Instant started = Instant.now();

    if (this.cache != null && !isQualifiedNewClassPrefix(params.getPrefix())
        && this.cache.canUseCache(params)) {
      final String prefix = params.requirePrefix();
      final String partial = partialIdentifier(prefix, prefix.length());
      final CompletionResult result = CompletionResult.mapAndFilter(this.cache.result, partial,
          item -> {
            final var description = item.getSnippetDescription();
            var deleteSelected = true;
            var allowCommands = false;
            CodeSnippet snippet = null;

            if (description != null) {
              deleteSelected = description.getDeleteSelected();
              allowCommands = description.getAllowCommandExecution();
              snippet = description.getSnippet();
            }

            item.setSnippetDescription(
                describeSnippet(partial, deleteSelected, snippet, allowCommands));
          });

      result.markCached();

      if (!result.isIncomplete() && !result.getItems().isEmpty()) {
        if (IdeLogConfig.shouldLogIde()) {
          LOG.debug("...using cached completion");
        }

        logCompletionDuration(started, result);
        return result;
      } else {
        if (IdeLogConfig.shouldLogIde()) {
          LOG.debug("...cached completions are empty");
        }

      }
    } else {
      if (IdeLogConfig.shouldLogIde()) {
        LOG.debug("...cannot use cached completions");
      }

    }

    abortIfCancelled();
    abortCompletionIfCancelled();
    final CompletionPipelineTiming pipelineTiming = new CompletionPipelineTiming();
    pipelineTiming.startedNs = System.nanoTime();
    final long cursor = params.getPosition().requireIndex();
    final int tsLine = params.getPosition().getLine();
    final int tsColumn = params.getPosition().getColumn();
    final long requestContentsStartedNs = System.nanoTime();
    final String originalContents = requestContents(params);
    pipelineTiming.requestContentsUs = elapsedUs(requestContentsStartedNs);
    final var contentBuilder = new StringBuilder(originalContents);

    final long semicolonStartedNs = System.nanoTime();
    int endOfLine = endOfLine(contentBuilder, (int) cursor);
    contentBuilder.insert(endOfLine, ';');
    pipelineTiming.semicolonUs = elapsedUs(semicolonStartedNs);

    final long astFixerStartedNs = System.nanoTime();
    final StringBuilder contents;
    final var context = compiler.compiler.currentContext;
    if (context != null) {
      abortIfCancelled();
      abortCompletionIfCancelled();
      contents = new ASTFixer(context).fix(contentBuilder);
    } else {
      contents = contentBuilder;
    }
    pipelineTiming.astFixerUs = elapsedUs(astFixerStartedNs);
    final String contentString = contents.toString();
    pipelineTiming.prepareUs = elapsedUs(pipelineTiming.startedNs);
final long classifyStartedNs = System.nanoTime();
    final TSCompletionSuppressionReason suppressionReason =
        classifyCompletionSuppression(params, contentString);
    pipelineTiming.classifyUs = elapsedUs(classifyStartedNs);
    if (suppressionReason != TSCompletionSuppressionReason.NONE) {
      pipelineTiming.logContextOutcome(file, cursor, suppressionReason, "EARLY_EMPTY_LITERAL_OR_COMMENT");
      if (IdeLogConfig.shouldLogInfo()) {
        LOG.info("Skipping Java completion in comment or literal context file={} cursor={} reason={}",
            file,
            cursor,
            suppressionReason);
      }
      return CompletionResult.EMPTY;
    }
    final boolean astFixerApplied = context != null;
    final boolean astFixerChangedContents = astFixerApplied && !contentString.contentEquals(contentBuilder);
    final int prefixLength = params.requirePrefix().length();
    final int injectedCharCount = contentString.length() - originalContents.length();
    if (IdeLogConfig.shouldLogDebug()) {
      LOG.debug(
          "Completion compile request file={} cursor={} prefixLength={} contentLength={} originalContentLength={} injectedCharCount={} semicolonInserted={} astFixerApplied={} astFixerChangedContents={} currentContextPresent={}",
          file,
          cursor,
          prefixLength,
          contentString.length(),
          originalContents.length(),
          injectedCharCount,
          true,
          astFixerApplied,
          astFixerChangedContents,
          context != null);
    }

    abortIfCancelled();
    abortCompletionIfCancelled();
 
    CompletionResult result = compileAndComplete(contentString, params, pipelineTiming);
    if (result == null) {
      LOG.warn(
          "Completion provider returned null result file={} cursor={} prefix={} version={} revision={}",
          file,
          cursor,
          params.getPrefix(),
          params.getDocumentVersion(),
          params.getDocumentRevision());
      result = CompletionResult.EMPTY;
    }
    if (result.getItems().isEmpty()) {
      LOG.warn(
          "Completion provider produced no items file={} cursor={} prefix={} version={} revision={} contextPresent={}",
          file,
          cursor,
          params.getPrefix(),
          params.getDocumentVersion(),
          params.getDocumentRevision(),
          compiler.compiler.currentContext != null);
    }

    abortIfCancelled();
    abortCompletionIfCancelled();
    logCompletionDuration(started, result);

    abortIfCancelled();
    abortCompletionIfCancelled();
    if (this.nextCacheConsumer != null) {
      this.nextCacheConsumer.accept(CachedCompletion.cache(params, result));
    }

    return result;
  }

  private boolean isQualifiedNewClassPrefix(@Nullable String prefix) {
    return prefix != null
        && prefix.matches("(?s).*\\.\\s*new(?:\\s+[A-Za-z_$][\\w$]*)?\\s*");
  }

  /**
   * Returns the text frozen by the editor at request creation time.
   *
   * A completion worker must not reread FileManager after its cursor/version have been captured:
   * another edit could otherwise pair newer text with this request's older position. Non-editor
   * callers may omit content, in which case the established SourceFileObject fallback is retained.
   */
  @NonNull
  private String requestContents(@NonNull final CompletionParams params) {
    final CharSequence requestContent = params.getContent();
    if (requestContent != null) {
      return requestContent.toString();
    }
    return new SourceFileObject(params.getFile()).getCharContent(true).toString();
  }

  private String partialIdentifier(String contents, int end) {
    int start = end;
    while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
      start--;
    }
    return contents.substring(start, end);
  }

  private void logCompletionDuration(Instant started, @NonNull CompletionResult result) {
    long elapsedMs = Duration.between(started, Instant.now()).toMillis();
    if (IdeLogConfig.shouldLogIde()) {
      LOG.debug("Found {} items{}{}in {} ms",
          result.getItems().size(),
          result.isIncomplete() ? " (incomplete) " : "",
          result.isCached() ? " (cached) " : " ",
          elapsedMs
      );
    }

  }

  private int endOfLine(@NonNull CharSequence contents, int cursor) {
    while (cursor < contents.length()) {
      char c = contents.charAt(cursor);
      if (c == '\r' || c == '\n') {
        break;
      }
      cursor++;
    }
    return cursor;
  }

  private CompletionResult compileAndComplete(
      String contents,
      CompletionParams params,
      CompletionPipelineTiming pipelineTiming) {
    final long cursor = params.getPosition().requireIndex();
    final var file = params.getFile();

    final var started = Instant.now();
    final var source = new SourceFileObject(file, contents, Instant.now());
    final var partial = partialIdentifier(contents, (int) cursor);
    final var endsWithParen = endsWithParen(contents, (int) cursor);
    abortIfCancelled();
    abortCompletionIfCancelled();

    // Kotlin source ABI stubs are added by JavaCompilerService's stable compilation path when
    // Kotlin recognition is enabled, so completion always uses one consistent request shape.
    final openjdk.tools.javac.util.Context[] completionContext =
        new openjdk.tools.javac.util.Context[1];
    final CompilationRequest request = new CompilationRequest(
        Collections.singletonList(source),
        new com.tom.rv2ide.lsp.java.compiler.DefaultCompilationTaskProcessor(),
        null,
        new MethodReparsePlan(
            file,
            contents,
            params.getDocumentVersion(),
            params.getDocumentRevision(),
            cursor));
    request.configureContext = ctx -> {
      completionContext[0] = ctx;
      final var config = JavaCompilerConfig.instance(ctx);
      config.setCompletionInfo(new CompletionInfo(params.getPosition()));
    };

    final long compileStartedNs = System.nanoTime();
    SynchronizedTask synchronizedTask = compiler.compile(request);
    try {
      return synchronizedTask.get(task -> {
        pipelineTiming.compileUs = elapsedUs(compileStartedNs);
        if (task == null || task.task == null || task.task.getContext() == null) {
          LOG.warn(
              "Compilation resulted in an invalid JavacTask file={} cursor={} taskPresent={} javacTaskPresent={} contextPresent={}",
              file,
              cursor,
              task != null,
              task != null && task.task != null,
              task != null && task.task != null && task.task.getContext() != null);
          return CompletionResult.EMPTY;
        }
        abortIfCancelled();
        abortCompletionIfCancelled();
        if (IdeLogConfig.shouldLogIde()) {
          LOG.debug("...compiled in {}ms", Duration.between(started, Instant.now()).toMillis());
        }
        final var completionRoot = task.root(file);
        final long scanStartedNs = System.nanoTime();
        TreePath path = new FindCompletionsAt(task.task).scan(completionRoot, cursor);
        pipelineTiming.scanUs = elapsedUs(scanStartedNs);
        if (path == null || path.getLeaf() == null) {
          LOG.warn(
              "Completion scan returned null path file={} cursor={} rootPresent={} diagnosticsCountUnknown=true",
              file,
              cursor,
              completionRoot != null);
          return CompletionResult.EMPTY;
        }

        abortIfCancelled();
        abortCompletionIfCancelled();
        String newPartial = partial;

        if (path.getLeaf().getKind() == Tree.Kind.IMPORT) {
          newPartial = qualifiedPartialIdentifier(contents, (int) cursor);
          if (newPartial.endsWith(ASTFixer.IDENT)) {
            newPartial = newPartial.substring(0, newPartial.length() - ASTFixer.IDENT.length());
          }
        } else if (path.getLeaf().getKind() == Tree.Kind.NEW_CLASS && "new".equals(newPartial)) {
          // At `qualifier.new|`, `new` is syntax rather than the member-type prefix.
          newPartial = "";
        }

        final long providerStartedNs = System.nanoTime();
        final var result =
            doComplete(file, contents, cursor, newPartial, endsWithParen, task, path);
        pipelineTiming.providerUs = elapsedUs(providerStartedNs);
        pipelineTiming.logContextOutcome(file, cursor, TSCompletionSuppressionReason.NONE, "CONTINUED_TO_JAVAC");
        pipelineTiming.log(file, cursor, path.getLeaf().getKind(), TSCompletionSuppressionReason.NONE, newPartial, result);

        return result;
      });
    } finally {
      final var context = completionContext[0];
      if (context != null) {
        JavaCompilerConfig.instance(context).setCompletionInfo(null);
      }
    }
  }

  @NonNull
  private CompletionResult doComplete(final Path file, final String contents, final long cursor,
      final String partial, final boolean endsWithParen,
      final CompileTask task, final TreePath path
  ) {
    final Class<? extends IJavaCompletionProvider> klass;
    abortIfCancelled();
    abortCompletionIfCancelled();
    switch (path.getLeaf().getKind()) {
      case IDENTIFIER:
        klass = IdentifierCompletionProvider.class;
        break;
      case MEMBER_SELECT:
        klass = MemberSelectCompletionProvider.class;
        break;
      case NEW_CLASS:
        klass = QualifiedNewClassCompletionProvider.class;
        break;
      case MEMBER_REFERENCE:
        LOG.warn(
            "Skipping member reference completion file={} cursor={} leafKind={} partial={} endsWithParen={} reason=temporarily disabled to avoid repeated full fallback and memory pressure",
            file,
            cursor,
            path.getLeaf().getKind(),
            partial,
            endsWithParen);
        return CompletionResult.EMPTY;
      case SWITCH:
        klass = SwitchConstantCompletionProvider.class;
        break;
      case IMPORT:
        klass = ImportCompletionProvider.class;
        break;
      default:
        klass = KeywordCompletionProvider.class;
        break;
    }

    final IJavaCompletionProvider provider = ReflectUtils.reflect(klass)
        .newInstance(file, cursor, compiler, getSettings())
        .get();

    if (provider instanceof ImportCompletionProvider) {
      final String importPath = qualifiedPartialIdentifier(contents, (int) cursor);
      ((ImportCompletionProvider) provider).setImportPath(importPath);
      if (IdeLogConfig.shouldLogInfo()) {
        LOG.info("Routing completion to ImportCompletionProvider file={} cursor={} leafKind={} importPath={}",
            file,
            cursor,
            path.getLeaf().getKind(),
            importPath);
      }
    }

    abortIfCancelled();
    abortCompletionIfCancelled();
    try {
      return provider.complete(task, path, partial, endsWithParen);
    } catch (Throwable err) {
      LOG.error(
          "Completion provider failed file={} cursor={} leafKind={} provider={} partial={} endsWithParen={}",
          file,
          cursor,
          path.getLeaf().getKind(),
          klass.getName(),
          partial,
          endsWithParen,
          err);
      throw err;
    }
  }

  private static long elapsedUs(long startedNs) {
    return (System.nanoTime() - startedNs) / 1_000L;
  }

  /** Request-scoped timing only; it does not participate in completion behavior. */
  private static final class CompletionPipelineTiming {
    long startedNs;
    long prepareUs;
    long requestContentsUs;
    long semicolonUs;
    long astFixerUs;
    long classifyUs;
    long compileUs;
    long scanUs;
    long providerUs;

    void logContextOutcome(
        Path file, long cursor, TSCompletionSuppressionReason suppressionReason, String outcome) {
      if (!IdeLogConfig.shouldLogIde()) {
        return;
      }
      LOG.debug(
          "JAVA_TS_SUPPRESSION_OUTCOME file={} cursor={} suppressionReason={} outcome={} "
              + "prepareUs={} classifyUs={} compileUs={} scanUs={} providerUs={} totalUs={}",
          file,
          cursor,
          suppressionReason,
          outcome,
          prepareUs,
          classifyUs,
          compileUs,
          scanUs,
          providerUs,
          elapsedUs(startedNs));
    }

    void log(
        Path file,
        long cursor,
        Tree.Kind leafKind,
        TSCompletionSuppressionReason suppressionReason,
        String partial,
        CompletionResult result) {
      if (!IdeLogConfig.shouldLogIde()) {
        return;
      }
      final long totalUs = elapsedUs(startedNs);
      LOG.debug(
          "JAVA_COMPLETION_PIPELINE file={} cursor={} leafKind={} suppressionReason={} partialLength={} "
              + "prepareUs={} requestContentsUs={} semicolonUs={} astFixerUs={} classifyUs={} "
              + "compileUs={} scanUs={} providerUs={} totalUs={} "
              + "itemCount={} incomplete={} cached={}",
          file,
          cursor,
          leafKind,
          suppressionReason,
          partial == null ? -1 : partial.length(),
          prepareUs,
          requestContentsUs,
          semicolonUs,
          astFixerUs,
          classifyUs,
          compileUs,
          scanUs,
          providerUs,
          totalUs,
          result == null ? 0 : result.getItems().size(),
          result != null && result.isIncomplete(),
          result != null && result.isCached());
    }
  }

  private boolean endsWithParen(@NonNull String contents, int cursor) {
    for (int i = cursor; i < contents.length(); i++) {
      if (!Character.isJavaIdentifierPart(contents.charAt(i))) {
        return contents.charAt(i) == '(';
      }
    }
    return false;
  }

  @NonNull
  private String qualifiedPartialIdentifier(String contents, int end) {
    int start = end;
    while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
      start--;
    }
    return contents.substring(start, end);
  }

  private boolean isQualifiedIdentifierChar(char c) {
    return c == '.' || Character.isJavaIdentifierPart(c);
  }
}
