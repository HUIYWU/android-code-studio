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
import android.view.ViewGroup;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.tom.rv2ide.adapters.SymbolInputAdapter;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.models.Symbol;
import com.tom.rv2ide.utils.Symbols;
import java.util.List;

public class SymbolInputView extends RecyclerView {

  private static final int ITEM_HEIGHT_DP = 40;
  private static final int MAX_ROWS = 3;

  public SymbolInputView(Context context) {
    this(context, null);
  }

  public SymbolInputView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public SymbolInputView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
  }

  /** Returns the height the expanded grid would use for the current adapter. */
  public int calculateExpandedHeight(int spanCount) {
    final int spans = Math.max(1, spanCount);
    final var adapter = getAdapter();
    final int count = adapter == null ? 0 : adapter.getItemCount();
    int rows = Math.max(1, (int) Math.ceil(count / (double) spans));
    rows = Math.min(rows, MAX_ROWS);
    return dp(ITEM_HEIGHT_DP * rows);
  }

  /**
   * Switches between the collapsed horizontal row and the expanded multi-row grid.
   *
   * @return the resulting height of this view in pixels.
   */
  public int setExpanded(boolean expanded, int spanCount) {
    if (isComputingLayout()) {
      post(() -> setExpanded(expanded, spanCount));
      return getHeight();
    }

    final int spans = Math.max(1, spanCount);
    if (expanded) {
      setLayoutManager(new GridLayoutManager(getContext(), spans));
    } else {
      setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
    }

    final int height = expanded ? calculateExpandedHeight(spans) : dp(ITEM_HEIGHT_DP);
    ViewGroup.LayoutParams params = getLayoutParams();
    if (params == null) {
      params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    } else {
      params.height = height;
    }
    setLayoutParams(params);
    post(() -> scrollToPosition(0));
    return height;
  }

  public void refresh(IDEEditor editor, List<Symbol> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      symbols = Symbols.INSTANCE.getPlainTextSymbols();
    }

    final var adapter = getAdapter();
    if (adapter instanceof SymbolInputAdapter) {
      ((SymbolInputAdapter) adapter).refresh(editor, symbols);
    } else {
      setAdapter(new SymbolInputAdapter(editor));
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}