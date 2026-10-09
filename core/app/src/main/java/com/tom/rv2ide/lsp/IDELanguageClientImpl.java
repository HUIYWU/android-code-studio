/*
 * This file is part of AndroidIDE.
 *
 *
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.tom.rv2ide.lsp;

import static com.tom.rv2ide.resources.R.drawable;
import static com.tom.rv2ide.resources.R.string;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.tom.rv2ide.activities.editor.EditorHandlerActivity;
import com.tom.rv2ide.adapters.DiagnosticsAdapter;
import com.tom.rv2ide.adapters.SearchListAdapter;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.fragments.sheets.ProgressSheet;
import com.tom.rv2ide.lsp.api.ILanguageClient;
import com.tom.rv2ide.lsp.models.CodeActionItem;
import com.tom.rv2ide.lsp.models.DiagnosticItem;
import com.tom.rv2ide.lsp.models.DiagnosticResult;
import com.tom.rv2ide.lsp.models.DiagnosticSeverity;
import com.tom.rv2ide.lsp.models.LineIndex;
import com.tom.rv2ide.lsp.models.PerformCodeActionParams;
import com.tom.rv2ide.lsp.models.ReferenceRole;
import com.tom.rv2ide.lsp.models.ShowDocumentParams;
import com.tom.rv2ide.lsp.models.ShowDocumentResult;
import com.tom.rv2ide.lsp.models.TextEdit;
import com.tom.rv2ide.lsp.util.DiagnosticUtil;
import com.tom.rv2ide.models.DiagnosticGroup;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.models.SearchResult;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.tasks.TaskExecutor;
import com.tom.rv2ide.ui.CodeEditorView;
import com.tom.rv2ide.utils.FlashbarActivityUtilsKt;
import com.tom.rv2ide.utils.FlashbarUtilsKt;
import com.tom.rv2ide.utils.LSPUtils;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import kotlin.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AndroidIDE specific implementation of the LanguageClient
 */
public class IDELanguageClientImpl implements ILanguageClient {

  public static final int MAX_DIAGNOSTIC_FILES = 10;
  public static final int MAX_DIAGNOSTIC_ITEMS_PER_FILE = 20;
  /**
   * Hard cap for diagnostics applied as editor underlines. A malformed large Java file can produce
   * thousands of diagnostics; mapping all of them to character offsets and pushing them into Sora's
   * DiagnosticsContainer is expensive on the UI thread.
   */
  private static final int MAX_EDITOR_DIAGNOSTIC_REGIONS = 200;
  private static final int MAX_LARGE_FILE_EDITOR_DIAGNOSTIC_REGIONS = 80;
  private static final long LARGE_FILE_DIAGNOSTIC_BYTES = 512L * 1024L;
  protected static final Logger LOG = LoggerFactory.getLogger(IDELanguageClientImpl.class);
  private static IDELanguageClientImpl mInstance;
  private static final String DIAGNOSTIC_CHANNEL_DEFAULT = DiagnosticResult.DEFAULT_CHANNEL;

  private final Map<File, Map<String, List<DiagnosticItem>>> diagnosticsByChannel = new HashMap<>();
  protected EditorHandlerActivity activity;

  private IDELanguageClientImpl(EditorHandlerActivity provider) {
    setActivity(provider);
  }

  public void setActivity(EditorHandlerActivity provider) {
    this.activity = provider;
  }

  public static IDELanguageClientImpl initialize(EditorHandlerActivity provider) {
    if (mInstance != null) {
      throw new IllegalStateException("Client is already initialized");
    }

    mInstance = new IDELanguageClientImpl(provider);

    return getInstance();
  }

  public static IDELanguageClientImpl getInstance() {
    if (mInstance == null) {
      throw new IllegalStateException("Client not initialized");
    }

    return mInstance;
  }

  public static void shutdown() {
    if (mInstance != null) {
      mInstance.activity = null;
    }
    mInstance = null;
  }

  public static boolean isInitialized() {
    return mInstance != null;
  }

  @Override
  public void publishDiagnostics(DiagnosticResult result) {
    publishDiagnostics(result, () -> true);
  }

  @Override
  public void publishDiagnostics(
      @Nullable final DiagnosticResult result,
      @NonNull final java.util.function.BooleanSupplier isCurrent) {
    if (result == null || result == DiagnosticResult.NO_UPDATE) {
      return;
    }
    if (!canUseActivity()) {
      return;
    }

    activity.runOnUiThread(
        () -> {
          if (!canUseActivity() || !isCurrent.getAsBoolean()) {
            return;
          }

          final File file = result.getFile().toFile();
          final String channel =
              result.getChannel() == null || result.getChannel().isBlank()
                  ? DIAGNOSTIC_CHANNEL_DEFAULT
                  : result.getChannel();
          final List<DiagnosticItem> incoming =
              result.getDiagnostics() == null
                  ? Collections.emptyList()
                  : new ArrayList<>(result.getDiagnostics());
          final var activeSnapshot = FileManager.INSTANCE.getActiveDocumentSnapshot(result.getFile());
          if ((result.getDocumentVersion() != DiagnosticResult.UNKNOWN_DOCUMENT_VERSION
                  && (activeSnapshot == null
                      || activeSnapshot.getVersion() != result.getDocumentVersion()))
              || (result.getDocumentRevision() != DiagnosticResult.UNKNOWN_DOCUMENT_REVISION
                  && (activeSnapshot == null
                      || activeSnapshot.getRevision() != result.getDocumentRevision()))) {
            return;
          }

          if ((!file.exists() || !file.isFile()) && !incoming.isEmpty()) {
            return;
          }

          final List<DiagnosticItem> previousDiagnostics = getMergedDiagnostics(file);
          putDiagnosticsForChannel(file, channel, incoming);
          final List<DiagnosticItem> mergedDiagnostics = getMergedDiagnostics(file);
          activity.handleDiagnosticsResultVisibility(buildMergedDiagnosticsSnapshot().isEmpty());

          updateEditorDiagnostics(file, mergedDiagnostics);

          if (shouldUpdateDiagnosticsAdapter(previousDiagnostics, mergedDiagnostics)) {
            activity.setDiagnosticsAdapter(newDiagnosticsAdapter());
          }
        });
  }

  @Override
  public void clearDiagnostics(java.nio.file.Path root, String channel) {
    clearDiagnosticsMatching(root.normalize(), channel);
  }

  @Override
  public void clearDiagnostics(String channel) {
    clearDiagnosticsMatching(null, channel);
  }

  private void clearDiagnosticsMatching(@Nullable java.nio.file.Path root, String channel) {
    if (!canUseActivity()) return;
    activity.runOnUiThread(() -> {
      if (!canUseActivity()) return;
      final List<File> files;
      synchronized (this) {
        files = new ArrayList<>(diagnosticsByChannel.keySet());
      }
      for (File file : files) {
        if (root != null && !file.toPath().normalize().startsWith(root)) continue;
        putDiagnosticsForChannel(file, channel, Collections.emptyList());
        updateEditorDiagnostics(file, getMergedDiagnostics(file));
      }
      activity.handleDiagnosticsResultVisibility(buildMergedDiagnosticsSnapshot().isEmpty());
      activity.setDiagnosticsAdapter(newDiagnosticsAdapter());
    });
  }

  private void updateEditorDiagnostics(File file, List<DiagnosticItem> diagnostics) {
    final var editorView = activity.getEditorForFile(file);
    if (editorView == null || editorView.getEditor() == null) return;
    final var editor = editorView.getEditor();
    final var container = new DiagnosticsContainer();
    try {
      final var content = editor.getText();
      final var lineIndex = LineIndex.from(content);
      final var regions = new ArrayList<io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion>();
      for (DiagnosticItem diagnostic : selectDiagnosticsForEditor(diagnostics, file, content.length())) {
        regions.add(diagnostic.asDiagnosticRegion(lineIndex));
      }
      container.addDiagnostics(regions);
    } catch (Throwable err) {
      LOG.error("Unable to map DiagnosticItem to DiagnosticRegion", err);
    }
    editor.setDiagnostics(container);
  }

  private boolean shouldUpdateDiagnosticsAdapter(
      @Nullable final List<DiagnosticItem> previous,
      @Nullable final List<DiagnosticItem> current) {
    final List<DiagnosticItem> previousSafe =
        previous == null ? Collections.emptyList() : previous;
    final List<DiagnosticItem> currentSafe =
        current == null ? Collections.emptyList() : current;
    return !Objects.equals(previousSafe, currentSafe);
  }

  private List<DiagnosticItem> selectDiagnosticsForEditor(
      @Nullable final List<DiagnosticItem> source,
      @NonNull final File file,
      final int contentLength) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }

    final int limit = file.length() >= LARGE_FILE_DIAGNOSTIC_BYTES
        || contentLength >= LARGE_FILE_DIAGNOSTIC_BYTES
            ? MAX_LARGE_FILE_EDITOR_DIAGNOSTIC_REGIONS
            : MAX_EDITOR_DIAGNOSTIC_REGIONS;
    if (source.size() <= limit) {
      return source;
    }

    final List<DiagnosticItem> selected = new ArrayList<>(limit);
    for (DiagnosticItem diagnostic : source) {
      if (diagnostic != null && diagnostic.getSeverity() == DiagnosticSeverity.ERROR) {
        selected.add(diagnostic);
        if (selected.size() == limit) {
          break;
        }
      }
    }
    if (selected.size() < limit) {
      for (DiagnosticItem diagnostic : source) {
        if (diagnostic != null && diagnostic.getSeverity() != DiagnosticSeverity.ERROR) {
          selected.add(diagnostic);
          if (selected.size() == limit) {
            break;
          }
        }
      }
    }

    LOG.warn(
        "Limiting editor diagnostic underlines to {} of {} items for file {}",
        selected.size(),
        source.size(),
        file.getName());
    return selected;
  }
 
  private synchronized List<DiagnosticItem> getMergedDiagnostics(@NonNull final File file) {

    final var byChannel = diagnosticsByChannel.get(file);
    if (byChannel == null || byChannel.isEmpty()) {
      return Collections.emptyList();
    }

    final var merged = new ArrayList<DiagnosticItem>();
    final var orderedChannels = new ArrayList<>(byChannel.keySet());
    Collections.sort(orderedChannels);
    for (final var channel : orderedChannels) {
      final var channelDiagnostics = byChannel.get(channel);
      if (channelDiagnostics != null && !channelDiagnostics.isEmpty()) {
        merged.addAll(channelDiagnostics);
      }
    }
    merged.sort(DiagnosticItem.START_COMPARATOR);
    return merged;
  }

  private synchronized void putDiagnosticsForChannel(
      @NonNull final File file,
      @NonNull final String channel,
      @Nullable final List<DiagnosticItem> channelDiagnostics) {
    final var normalized = channelDiagnostics == null ? Collections.<DiagnosticItem>emptyList() : channelDiagnostics;
    final var byChannel = diagnosticsByChannel.computeIfAbsent(file, ignored -> new HashMap<>());
    if (normalized.isEmpty()) {
      byChannel.remove(channel);
      if (byChannel.isEmpty()) {
        diagnosticsByChannel.remove(file);
      }
      return;
    }

    byChannel.put(channel, new ArrayList<>(normalized));
  }

  @Nullable
  @Override
  public DiagnosticItem getDiagnosticAt(final File file, final int line, final int column) {
    return DiagnosticUtil.binarySearchDiagnostic(getMergedDiagnostics(file), line, column);
  }

  @Override
  public void performCodeAction(PerformCodeActionParams params) {
    if (params == null) {
      return;
    }

    final var action = params.getAction();
    if (!canUseActivity()) {
      LOG.error("Unable to perform code action activity=null action={}", action);
      FlashbarUtilsKt.flashError(string.msg_cannot_perform_fix);
      return;
    }

    final var currentEditor = this.activity.getCurrentEditor();
    final var editor = currentEditor != null ? currentEditor.getEditor() : null;

    if (!params.getAsync()) {
      applyActionEdits(editor, action);
      if (editor != null) {
        action.getCommand();
        editor.executeCommand(action.getCommand());
      }
      return;
    }

    final ProgressSheet progress = new ProgressSheet();
    progress.setSubMessageEnabled(false);
    progress.setCancelable(false);
    progress.setMessage(this.activity.getString(string.msg_performing_actions));
    progress.show(this.activity.getSupportFragmentManager(), "quick_fix_progress");

    TaskExecutor.executeAsyncProvideError(
        () -> applyActionEdits(editor, action),
        (result, throwable) -> {
          progress.dismiss();
          if (result == null || throwable != null || !result) {
            LOG.error("Unable to perform code action result={}", result, throwable);
            FlashbarActivityUtilsKt.flashError(this.activity, string.msg_cannot_perform_fix);
          } else if (editor != null) {
            editor.executeCommand(action.getCommand());
          }
        });
  }

  private Boolean applyActionEdits(@Nullable final IDEEditor editor, final CodeActionItem action) {
    final var changes = action.getChanges();
    if (changes.isEmpty()) {
      return Boolean.FALSE;
    }

    for (var change : changes) {
      final var path = change.getFile();
      if (path == null) {
        continue;
      }

      final File file = path.toFile();
      if (!file.exists()) {
        continue;
      }

      for (TextEdit edit : change.getEdits()) {
        final String editorFilepath =
            editor == null || editor.getFile() == null ? "" : editor.getFile().getAbsolutePath();
        if (file.getAbsolutePath().equals(editorFilepath)) {
          // Edit is in the same editor which requested the code action
          editInEditor(editor, edit);
        } else {
          var openedFrag = findEditorByFile(file);

          if (openedFrag != null && openedFrag.getEditor() != null) {
            // Edit is in another 'opened' file
            editInEditor(openedFrag.getEditor(), edit);
          } else {
            // Edit is in some other file which is not opened
            // open that file and perform the edit
            openedFrag = activity.openFile(file);
            if (openedFrag != null && openedFrag.getEditor() != null) {
              editInEditor(openedFrag.getEditor(), edit);
            }
          }
        }
      }
    }

    return Boolean.TRUE;
  }

  private void editInEditor(final IDEEditor editor, final TextEdit edit) {
    activity
        .runOnUiThread(
            () -> {
              final Range range = edit.getRange();
              final int startLine = range.getStart().getLine();
              final int startCol = range.getStart().getColumn();
              final int endLine = range.getEnd().getLine();
              final int endCol = range.getEnd().getColumn();
              final EditorAutoCompletion completion =
                  editor.getComponent(EditorAutoCompletion.class);
              final boolean completionWasEnabled = completion != null && completion.isEnabled();
              if (completionWasEnabled) {
                // A CodeAction replacement is not user typing. Disable completion for this synchronous
                // content mutation so Sora does not open an unrelated completion window after a fix.
                completion.cancelCompletion();
                completion.setEnabled(false);
              }
              try {
                if (startLine == endLine && startCol == endCol) {
                  editor.getText().insert(startLine, startCol, edit.getNewText());
                } else {
                  editor.getText().replace(startLine, startCol, endLine, endCol, edit.getNewText());
                }
              } finally {
                if (completionWasEnabled) {
                  completion.setEnabled(true);
                }
              }
            });
  }

  @Override
  public ShowDocumentResult showDocument(ShowDocumentParams params) {
    boolean success = false;
    final var result = new ShowDocumentResult(false);
    if (!canUseActivity()) {
      return result;
    }

    if (params != null) {
      File file = params.getFile().toFile();
      if (file.exists() && file.isFile() && FileUtils.isUtf8(file)) {
        final var range = params.getSelection();
        var frag =
            activity.getEditorAtIndex(activity.getContent().tabs.getSelectedTabPosition());
        if (frag != null
            && frag.getFile() != null
            && frag.getEditor() != null
            && frag.getFile().getAbsolutePath().equals(file.getAbsolutePath())) {
          if (LSPUtils.isEqual(range.getStart(), range.getEnd())) {
            frag.getEditor().setSelection(range.getStart().getLine(), range.getStart().getColumn());
          } else {
            frag.getEditor().setSelection(range);
          }
        } else {
          activity.openFileAndSelect(file, range);
        }
        success = true;
      }
    }

    result.setSuccess(success);
    return result;
  }

  public DiagnosticsAdapter newDiagnosticsAdapter() {
    return new DiagnosticsAdapter(mapAsGroup(buildMergedDiagnosticsSnapshot()), activity);
  }

  private synchronized Map<File, List<DiagnosticItem>> buildMergedDiagnosticsSnapshot() {
    final var merged = new HashMap<File, List<DiagnosticItem>>();
    for (final var entry : diagnosticsByChannel.entrySet()) {
      final var mergedDiagnostics = getMergedDiagnostics(entry.getKey());
      if (!mergedDiagnostics.isEmpty()) {
        merged.put(entry.getKey(), mergedDiagnostics);
      }
    }
    return merged;
  }

  private List<DiagnosticGroup> mapAsGroup(Map<File, List<DiagnosticItem>> map) {
    final var groups = new ArrayList<DiagnosticGroup>();
    var diagnosticMap = map;
    if (diagnosticMap == null || diagnosticMap.size() == 0) {
      return groups;
    }

    if (diagnosticMap.size() > 10) {
      LOG.warn("Limiting the diagnostics to 10 files");
      diagnosticMap = filterRelevantDiagnostics(map);
    }

    for (File file : diagnosticMap.keySet()) {
      var fileDiagnostics = diagnosticMap.get(file);
      if (fileDiagnostics == null || fileDiagnostics.size() == 0) {
        continue;
      }

      // Trim the diagnostics list if we have too many diagnostic items.
      // Including a lot of diagnostic items will result in UI lag when they are shown
      if (fileDiagnostics.size() > MAX_DIAGNOSTIC_ITEMS_PER_FILE) {
        LOG.warn("Limiting diagnostics to {} items for file {}",
            MAX_DIAGNOSTIC_ITEMS_PER_FILE,
            file.getName());

        fileDiagnostics = fileDiagnostics.subList(0, MAX_DIAGNOSTIC_ITEMS_PER_FILE);
      }
      DiagnosticGroup group = new DiagnosticGroup(drawable.ic_language_java, file, fileDiagnostics);
      groups.add(group);
    }
    return groups;
  }

  @NonNull
  private Map<File, List<DiagnosticItem>> filterRelevantDiagnostics(
      @NonNull final Map<File, List<DiagnosticItem>> map) {
    final var result = new HashMap<File, List<DiagnosticItem>>();
    final var files = map.keySet();

    // Diagnostics of files that are open must always be included
    final var relevantFiles = findOpenFiles(files, MAX_DIAGNOSTIC_FILES);

    // If we can show a few more file diagnostics...
    if (relevantFiles.size() < MAX_DIAGNOSTIC_FILES) {
      final var alphabetical = new TreeSet<>(Comparator.comparing(File::getName));
      alphabetical.addAll(files);
      for (var file : alphabetical) {
        relevantFiles.add(file);
        if (relevantFiles.size() == MAX_DIAGNOSTIC_FILES) {
          break;
        }
      }
    }

    for (var file : relevantFiles) {
      result.put(file, map.get(file));
    }
    return result;
  }

  @NonNull
  private Set<File> findOpenFiles(final Set<File> files, final int max) {
    final var openedFiles = activity.getEditorViewModel().getOpenedFiles();
    final var result = new TreeSet<File>();
    for (int i = 0; i < openedFiles.size(); i++) {
      final var opened = openedFiles.get(i);
      if (files.contains(opened)) {
        result.add(opened);
      }
      if (result.size() == max) {
        break;
      }
    }
    return result;
  }

  /**
   * Called by {@link IDEEditor IDEEditor} to show locations in EditorActivity
   */
  @Override
  public void showLocations(List<Location> locations) {
    showLocations(locations, Collections.emptyList());
  }

  @Override
  public void showReferences(List<Location> locations, List<ReferenceRole> roles) {
    showLocations(locations, roles);
  }

  private void showLocations(List<Location> locations, List<ReferenceRole> roles) {

    // Cannot show anything if the activity() is null
    if (!canUseActivity()) {
      return;
    }

    boolean error = locations == null || locations.isEmpty();
    activity.handleSearchResultVisibility(error);

    if (error) {
      activity
          .setSearchResultAdapter(
              new SearchListAdapter(Collections.emptyMap(), this::noOp, this::noOp));
      return;
    }

    final Map<File, List<SearchResult>> results = new HashMap<>();
    for (int i = 0; i < locations.size(); i++) {
      try {
        final Location loc = locations.get(i);
        if (loc == null) {
          continue;
        }

        final File file = loc.getFile().toFile();
        if (!file.exists() || !file.isFile()) {
          continue;
        }
        var frag = findEditorByFile(file);
        Content content;
        if (frag != null && frag.getEditor() != null) {
          content = frag.getEditor().getText();
        } else {
          content = new Content(FileIOUtils.readFile2String(file));
        }
        final List<SearchResult> matches =
            results.containsKey(file) ? results.get(file) : new ArrayList<>();
        Objects.requireNonNull(matches)
            .add(
                new SearchResult(
                    loc.getRange(),
                    file,
                    content.getLineString(loc.getRange().getStart().getLine()),
                    content
                        .subContent(
                            loc.getRange().getStart().getLine(),
                            loc.getRange().getStart().getColumn(),
                            loc.getRange().getEnd().getLine(),
                            loc.getRange().getEnd().getColumn())
                        .toString(),
                    referenceRoleLabel(roles, i)));
        results.put(file, matches);
      } catch (Throwable th) {
        LOG.error("Failed to show file location", th);
      }
    }

    activity.handleSearchResults(results);
  }

  @Nullable
  private String referenceRoleLabel(List<ReferenceRole> roles, int index) {
    if (roles == null || index < 0 || index >= roles.size()) {
      return null;
    }
    final ReferenceRole role = roles.get(index);
    if (role == ReferenceRole.DEFINITION) {
      return activity.getString(string.reference_role_definition);
    }
    if (role == ReferenceRole.USAGE) {
      return activity.getString(string.reference_role_usage);
    }
    return null;
  }

  private CodeEditorView findEditorByFile(File file) {
    return activity.getEditorForFile(file);
  }

  private boolean canUseActivity() {
    return activity != null
        && !activity.isFinishing()
        && !activity.isDestroyed()
        && !activity.getSupportFragmentManager().isDestroyed()
        && !activity.getSupportFragmentManager().isStateSaved();
  }

  private Unit noOp(final Object obj) {
    return Unit.INSTANCE;
  }
}
