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
package com.tom.rv2ide.adapters;

import static com.tom.rv2ide.utils.ResourceUtilsKt.resolveAttr;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.tom.rv2ide.R;
import com.tom.rv2ide.databinding.LayoutSymbolItemBinding;
import com.tom.rv2ide.editor.ui.IDEEditor;
import com.tom.rv2ide.models.ActionQuickItem;
import com.tom.rv2ide.models.EditorQuickItem;
import com.tom.rv2ide.models.SymbolQuickItem;
import io.github.rosemoe.sora.widget.SelectionMovement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SymbolInputAdapter extends RecyclerView.Adapter<SymbolInputAdapter.VH> {

  public interface ActionClickListener {
    void onActionClick(String actionId);
  }

  private static final List<Character> pairs;

  static {
    pairs = new ArrayList<>();
    pairs.add('}');
    pairs.add(')');
    pairs.add(']');
    pairs.add('"');
    pairs.add('\'');
    pairs.add('>');
  }

  private IDEEditor editor;
  private final List<EditorQuickItem> items;
  @Nullable private ActionClickListener actionClickListener;

  public SymbolInputAdapter(IDEEditor editor) {
    this(editor, null, null);
  }

  public SymbolInputAdapter(IDEEditor editor, List<EditorQuickItem> items) {
    this(editor, items, null);
  }

  public SymbolInputAdapter(
      IDEEditor editor, List<EditorQuickItem> items, @Nullable ActionClickListener actionClickListener) {
    this.editor = editor;
    this.items = new ArrayList<>();
    this.actionClickListener = actionClickListener;
    this.updateItems(items);
  }

  private void updateItems(List<EditorQuickItem> items) {
    if (items == null) {
      return;
    }

    this.items.clear();
    this.items.addAll(items);
    this.items.removeIf(Objects::isNull);
  }

  public void setActionClickListener(@Nullable ActionClickListener listener) {
    actionClickListener = listener;
  }

  @SuppressLint("NotifyDataSetChanged")
  public void refresh(IDEEditor editor, List<EditorQuickItem> newItems) {
    this.editor = Objects.requireNonNull(editor);

    if (this.items.equals(newItems)) {
      // no need to update items
      return;
    }

    updateItems(newItems);
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int itemType) {
    return new VH(
        LayoutSymbolItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull VH holder, int position) {
    final EditorQuickItem item = items.get(position);
    applyItemPresentation(holder, item);
    holder.binding.symbol.setText(item.getLabel());
    holder.binding.symbol.setTextColor(
        resolveAttr(holder.binding.symbol.getContext(), R.attr.colorOnSurface));
    holder.binding.symbol.setOnClickListener(
        __ -> {
          if (item instanceof SymbolQuickItem) {
            final var symbol = (SymbolQuickItem) item;
            insertSymbol(symbol.getCommit(), symbol.getOffset());
          } else if (item instanceof ActionQuickItem && actionClickListener != null) {
            actionClickListener.onActionClick(((ActionQuickItem) item).getActionId());
          }
        });
  }

  private void applyItemPresentation(VH holder, EditorQuickItem item) {
    final var symbol = holder.binding.symbol;
    if (item instanceof ActionQuickItem) {
      symbol.setPadding(0, symbol.getPaddingTop(), 0, symbol.getPaddingBottom());
      symbol.setMinWidth(0);
      symbol.setMinimumWidth(0);
    } else {
      symbol.setPadding(
          holder.basePaddingLeft,
          symbol.getPaddingTop(),
          holder.basePaddingRight,
          symbol.getPaddingBottom());
      symbol.setMinWidth(holder.baseMinWidth);
      symbol.setMinimumWidth(holder.baseMinWidth);
    }
  }

  @Override
  public int getItemCount() {
    return items.size();
  }

  void insertSymbol(String text, int selectionOffset) {
    if (selectionOffset < 0 || selectionOffset > text.length()) {
      return;
    }

    final var controller = editor.getSnippetController();
    if ("\t".equals(text) && controller.isInSnippet()) {
      controller.shiftToNextTabStop();
      return;
    }

    if ("\t".equals(text)) {
      editor.indentOrCommitTab();
      return;
    }

    var cur = editor.getText().getCursor();
    if (cur.isSelected()) {
      editor
          .getText()
          .delete(cur.getLeftLine(), cur.getLeftColumn(), cur.getRightLine(), cur.getRightColumn());
      editor.notifyIMEExternalCursorChange();
    }

    if (cur.getLeftColumn() < editor.getText().getColumnCount(cur.getLeftLine())
        && text.length() == 1
        && text.charAt(0) == editor.getText().charAt(cur.getLeftLine(), cur.getLeftColumn())
        && pairs.contains(text.charAt(0))) {
      editor.moveSelection(SelectionMovement.RIGHT);
    } else {
      editor.commitText(text);
      if (selectionOffset != text.length()) {
        editor.setSelection(
            cur.getRightLine(), cur.getRightColumn() - (text.length() - selectionOffset));
      }
    }
  }

  public static class VH extends RecyclerView.ViewHolder {

    LayoutSymbolItemBinding binding;
    final int basePaddingLeft;
    final int basePaddingRight;
    final int baseMinWidth;

    public VH(LayoutSymbolItemBinding binding) {
      super(binding.getRoot());
      this.binding = binding;
      this.basePaddingLeft = binding.symbol.getPaddingLeft();
      this.basePaddingRight = binding.symbol.getPaddingRight();
      this.baseMinWidth = binding.symbol.getMinWidth();
    }
  }
}