/*
 * This file is part of AndroidIDE.
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
package com.tom.rv2ide.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.tom.rv2ide.adapters.SymbolInputAdapter;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.models.EditorQuickItem;
import com.tom.rv2ide.models.Symbol;
import com.tom.rv2ide.utils.EditorQuickInputProvider;
import java.io.File;
import java.util.List;

public class SymbolInputView extends FrameLayout {

  private static final int EXPANDED_SPAN_COUNT = 10;

  public enum ExpandDirection {
    UP,
    DOWN
  }

  public interface ExpansionChangeListener {
    void onExpansionChanged(boolean expanded, ExpandDirection direction);
  }

  public interface ActionClickListener {
    void onActionClick(String actionId);
  }

  private final RecyclerView collapsedList;
  private final RecyclerView expandedGrid;
  @Nullable private View toggleButton;
  @Nullable private ExpansionChangeListener expansionChangeListener;
  @Nullable private ActionClickListener actionClickListener;
  private ExpandDirection expandDirection = ExpandDirection.UP;
  private boolean expanded;
  private boolean contentExpanded;


  public SymbolInputView(Context context) {
    this(context, null);
  }

  public SymbolInputView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public SymbolInputView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    collapsedList = new RecyclerView(context);
    collapsedList.setLayoutManager(
        new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
    collapsedList.setOverScrollMode(OVER_SCROLL_NEVER);
    collapsedList.setNestedScrollingEnabled(false);
    collapsedList.setClipToPadding(false);

    expandedGrid = new RecyclerView(context);
    expandedGrid.setLayoutManager(new GridLayoutManager(getContext(), EXPANDED_SPAN_COUNT));
    expandedGrid.setOverScrollMode(OVER_SCROLL_NEVER);
    expandedGrid.setNestedScrollingEnabled(false);
    expandedGrid.setClipToPadding(false);
    expandedGrid.setPadding(0, dp(8), 0, dp(8));

    addView(
        collapsedList,
        new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    addView(
        expandedGrid,
        new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    setExpanded(false);
  }

  public void bindToggleButton(@Nullable View button) {
    toggleButton = button;
    if (toggleButton != null) {
      toggleButton.setOnClickListener(__ -> toggleExpanded());
    }
    updateToggleButton();
  }

  public void setExpandDirection(ExpandDirection direction) {
    expandDirection = direction;
  }

  public ExpandDirection getExpandDirection() {
    return expandDirection;
  }

  public void setExpansionChangeListener(@Nullable ExpansionChangeListener listener) {
    expansionChangeListener = listener;
  }

  /** Forwards action-item clicks without coupling this view to activity-level action execution. */
  public void setActionClickListener(@Nullable ActionClickListener listener) {
    actionClickListener = listener;
  }

  public void refresh(IDEEditor editor, File file, List<Symbol> symbols) {
    final var collapsedItems =
        symbols == null || symbols.isEmpty()
            ? EditorQuickInputProvider.INSTANCE.plainTextItems()
            : EditorQuickInputProvider.INSTANCE.toQuickItems(symbols);
    final var expandedItems = EditorQuickInputProvider.INSTANCE.expandedItems(file, collapsedItems);

    refreshAdapter(collapsedList, editor, collapsedItems);
    refreshAdapter(expandedGrid, editor, expandedItems);
    setExpanded(false);
  }

  public boolean isExpanded() {
    return expanded;
  }

  public void expand() {
    setExpanded(true);
  }

  public void collapse() {
    setExpanded(false);
  }

  public void toggleExpanded() {
    setExpanded(!expanded);
  }

  public void setExpanded(boolean expanded) {
    final boolean changed = this.expanded != expanded;

    this.expanded = expanded;
    applyContentExpanded(expanded);
    collapsedList.clearFocus();
    expandedGrid.clearFocus();
    if (toggleButton != null) {
      toggleButton.setPressed(false);
      toggleButton.clearFocus();
    }
    updateToggleButton();

    if (changed && expansionChangeListener != null) {
      expansionChangeListener.onExpansionChanged(expanded, expandDirection);
    }
  }

  public void setContentExpanded(boolean expanded) {
    applyContentExpanded(expanded);
  }

  public void setContentTransitionProgress(float expandedProgress) {
    final float progress = Math.max(0f, Math.min(1f, expandedProgress));
    contentExpanded = progress >= 0.5f;

    collapsedList.setVisibility(progress >= 1f ? GONE : VISIBLE);
    collapsedList.setEnabled(progress < 0.5f);
    collapsedList.setAlpha(1f - progress);

    expandedGrid.setVisibility(progress <= 0f ? GONE : VISIBLE);
    expandedGrid.setEnabled(progress >= 0.5f);
    expandedGrid.setAlpha(progress);
  }

  public boolean isContentExpanded() {
    return contentExpanded;
  }

  private void applyContentExpanded(boolean expanded) {
    setContentTransitionProgress(expanded ? 1f : 0f);
  }

  public void endItemAnimations() {
    if (collapsedList.getItemAnimator() != null) {
      collapsedList.getItemAnimator().endAnimations();
    }
    if (expandedGrid.getItemAnimator() != null) {
      expandedGrid.getItemAnimator().endAnimations();
    }
  }

  private void refreshAdapter(
      RecyclerView recyclerView, IDEEditor editor, List<EditorQuickItem> quickItems) {
    final var adapter = recyclerView.getAdapter();
    if (adapter instanceof SymbolInputAdapter) {
      ((SymbolInputAdapter) adapter).refresh(editor, quickItems);
    } else {
      recyclerView.setAdapter(
          new SymbolInputAdapter(
              editor,
              quickItems,
              actionId -> {
                if (actionClickListener != null) {
                  actionClickListener.onActionClick(actionId);
                }
              }));
    }
  }

  private void updateToggleButton() {
    if (toggleButton instanceof ImageView) {
      ((ImageView) toggleButton).setRotation(expanded ? 90f : -90f);
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
