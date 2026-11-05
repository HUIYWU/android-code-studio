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
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.tom.rv2ide.adapters.SymbolInputAdapter;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.models.EditorQuickItem;
import com.tom.rv2ide.utils.EditorQuickInputProvider;
import java.util.List;

public class SymbolInputView extends RecyclerView {

  @Nullable private SymbolInputAdapter.ActionClickListener actionClickListener;

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

  public void setActionClickListener(SymbolInputAdapter.ActionClickListener listener) {
    actionClickListener = listener;
    final var adapter = getAdapter();
    if (adapter instanceof SymbolInputAdapter) {
      ((SymbolInputAdapter) adapter).setActionClickListener(listener);
    }
  }

  public void refresh(IDEEditor editor, List<EditorQuickItem> items) {
    if (items == null || items.isEmpty()) {
      items = EditorQuickInputProvider.defaultItems();
    }

    final var adapter = getAdapter();
    if (adapter instanceof SymbolInputAdapter) {
      ((SymbolInputAdapter) adapter).refresh(editor, items);
    } else {
      setAdapter(new SymbolInputAdapter(editor, items, actionClickListener));
    }
  }
}