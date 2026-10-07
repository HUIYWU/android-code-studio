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

package com.tom.rv2ide.lsp.java.utils;

import androidx.annotation.NonNull;
import com.tom.rv2ide.common.logging.IdeLogConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import openjdk.tools.javac.parser.Scanner;
import openjdk.tools.javac.parser.ScannerFactory;
import openjdk.tools.javac.parser.Tokens;
import openjdk.tools.javac.parser.Tokens.TokenKind;
import openjdk.tools.javac.util.Context;
import org.jetbrains.annotations.Contract;

/**
 * @author Akash Yadav
 */
public class ASTFixer {
  public static final String IDENT = "I_N_J_E_C_T_E_D";

  private static final Set<TokenKind> MEMBER_SELECTION_TOKENS =
      ImmutableSet.of(
          Tokens.TokenKind.IDENTIFIER,
          Tokens.TokenKind.LT,
          TokenKind.NEW,
          TokenKind.THIS,
          TokenKind.SUPER,
          TokenKind.CLASS,
          TokenKind.STAR);
  private static final Set<TokenKind> INVALID_SELECTION_SUFFIXES =
      ImmutableSet.of(TokenKind.RBRACE);

  private final Context context;

  public ASTFixer(Context context) {
    this.context = context;
  }

  public StringBuilder fix(CharSequence content) {
    final long scanStartedNs = System.nanoTime();
    Scanner scanner = ScannerFactory.instance(context).newScanner(content, true);
    List<Edit> edits = new ArrayList<>();
    int tokenCount = 0;
    int memberSelectionCount = 0;
    int errorTokenCount = 0;
    for (; ; scanner.nextToken()) {
      Tokens.Token token = scanner.token();
      tokenCount++;
      if (token.kind == TokenKind.EOF) {
        break;
      } else if (token.kind == TokenKind.DOT || token.kind == TokenKind.COLCOL) {
        memberSelectionCount++;
        fixMemberSelection(scanner, content, edits);
      } else if (token.kind == TokenKind.ERROR) {
        errorTokenCount++;
        int errPos = scanner.errPos();
        if (errPos >= 0 && errPos < content.length()) {
          fixError(scanner, content, edits);
        }
      }
    }
    final long scanUs = (System.nanoTime() - scanStartedNs) / 1_000L;
    final long applyStartedNs = System.nanoTime();
    final StringBuilder result = Edit.applyInsertions(content, edits);
    final long applyUs = (System.nanoTime() - applyStartedNs) / 1_000L;
    if (IdeLogConfig.shouldLogIde()) {
      org.slf4j.LoggerFactory.getLogger(ASTFixer.class).debug(
          "JAVA_AST_FIXER contentLength={} tokenCount={} memberSelectionCount={} errorTokenCount={} editCount={} scanUs={} applyUs={} totalUs={}",
          content.length(),
          tokenCount,
          memberSelectionCount,
          errorTokenCount,
          edits.size(),
          scanUs,
          applyUs,
          scanUs + applyUs);
    }
    return result;
  }

  private void fixMemberSelection(
      @NonNull Scanner scanner, @NonNull CharSequence content, List<Edit> edits) {
    Tokens.Token token = scanner.token();
    Tokens.Token nextToken = scanner.token(1);

    if (containsLineBreak(content, token.pos, nextToken.pos)) {
      edits.add(Edit.create(token.endPos, IDENT + ";"));
    } else if (nextToken.kind == TokenKind.NEW) {
      final Tokens.Token typeToken = scanner.token(2);
      if (typeToken.kind != TokenKind.IDENTIFIER) {
        // A qualified inner-class creation starts with `expression.new Type(...)`.
        // Keep `new` intact and synthesize only the missing member type so javac can
        // attribute the qualifier and completion can enumerate its non-static classes.
        edits.add(Edit.create(nextToken.endPos, " " + IDENT));
      }
    } else if (!MEMBER_SELECTION_TOKENS.contains(nextToken.kind)) {
      String toInsert = IDENT;
      if (INVALID_SELECTION_SUFFIXES.contains(nextToken.kind)) {
        toInsert = IDENT + ";";
      }
      edits.add(Edit.create(token.endPos, toInsert));
    }
  }

  private static boolean containsLineBreak(
      @NonNull CharSequence content, int startInclusive, int endExclusive) {
    final int start = Math.max(0, Math.min(startInclusive, content.length()));
    final int end = Math.max(start, Math.min(endExclusive, content.length()));
    for (int index = start; index < end; index++) {
      final char ch = content.charAt(index);
      if (ch == '\n' || ch == '\r') {
        return true;
      }
    }
    return false;
  }

  private void fixError(@NonNull Scanner scanner, @NonNull CharSequence content, List<Edit> edits) {
    int errPos = scanner.errPos();
    if (content.charAt(errPos) == '.' && errPos > 0 && content.charAt(errPos) == '.') {
      if (errPos < content.length() - 1
          && Character.isJavaIdentifierStart(content.charAt(errPos + 1))) {
        edits.add(Edit.create(errPos, IDENT));
      }
    }
  }

  public static class Edit {
    private static final Ordering<Edit> REVERSE_INSERTION =
        Ordering.natural().onResultOf(Edit::getPos).reverse();

    private final int pos;
    private final String text;

    public Edit(int pos, String text) {
      this.pos = pos;
      this.text = text;
    }

    public int getPos() {
      return pos;
    }

    public String getText() {
      return text;
    }

    @NonNull
    @Contract(value = "_, _ -> new", pure = true)
    public static Edit create(int pos, String text) {
      return new Edit(pos, text);
    }

    @NonNull
    public static StringBuilder applyInsertions(CharSequence content, List<Edit> edits) {
      ImmutableList<Edit> reverseEdits = REVERSE_INSERTION.immutableSortedCopy(edits);

      StringBuilder sb = new StringBuilder(content);

      for (Edit edit : reverseEdits) {
        sb.insert(edit.getPos(), edit.getText());
      }
      return sb;
    }
  }
}
