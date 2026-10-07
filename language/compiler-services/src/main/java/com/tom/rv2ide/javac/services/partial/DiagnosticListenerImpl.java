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

package com.tom.rv2ide.javac.services.partial;

import androidx.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import jdkx.tools.Diagnostic;
import jdkx.tools.DiagnosticListener;
import jdkx.tools.JavaFileObject;
import openjdk.tools.javac.api.ClientCodeWrapper;
import openjdk.tools.javac.util.JCDiagnostic;

/**
 * @author Akash Yadav
 */
public class DiagnosticListenerImpl implements DiagnosticListener<JavaFileObject> {

  private final Map<JavaFileObject, Diagnostics> source2Errors;
  private final JavaFileObject jfo;
  private volatile List<Diagnostic<? extends JavaFileObject>> partialReparseErrors;
  /** true if the partialReparseErrors contain some non-warning */
  private volatile boolean partialReparseRealErrors;

  private volatile List<Diagnostic<? extends JavaFileObject>> affectedErrors;
  private volatile List<Diagnostic<? extends JavaFileObject>> removedErrors;
  private volatile int currentDelta;

  public DiagnosticListenerImpl(@Nullable final JavaFileObject jfo) {
    this.jfo = jfo;
    this.source2Errors = new HashMap<>();
  }

  public final JavaFileObject jfoForDebug() {
    return jfo;
  }

  public final URI sourceUriForDebug() {
    return jfo == null ? null : jfo.toUri().normalize();
  }

  @Override
  public synchronized void report(Diagnostic<? extends JavaFileObject> message) {
    if (partialReparseErrors != null) {
      if (sameSource(this.jfo, message.getSource())) {
        partialReparseErrors.add(message);
        if (message.getKind() == Diagnostic.Kind.ERROR) {
          partialReparseRealErrors = true;
        }
      }
    } else {
      Diagnostics errors = getErrors(message.getSource());
      errors.add((int) message.getPosition(), message);
    }
  }

  private boolean sameSource(JavaFileObject left, JavaFileObject right) {
    if (left == right) {
      return left != null;
    }
    if (left == null || right == null) {
      return false;
    }
    try {
      return left.toUri().normalize().equals(right.toUri().normalize());
    } catch (Throwable ignored) {
      return false;
    }
  }

  private Diagnostics getErrors(JavaFileObject file) {
    Diagnostics errors;
    if (isIncompleteClassPath()) {
      //        if (root != null && JavaIndex.hasSourceCache(root.toURL(), false)) {
      //          errors = source2Errors.get(file);
      //          if (errors == null) {
      //            source2Errors.put(file, errors = new Diagnostics());
      //            if (this.jfo != null && this.jfo == file) {
      //              errors.add(0, new IncompleteClassPath(this.jfo));
      //            }
      //          }
      //        } else {
      errors = new Diagnostics();
      if (this.jfo != null && this.jfo == file) {
        errors.add(0, new IncompleteClassPath(this.jfo));
        //          }
      }
    } else {
      JavaFileObject key = null;
      for (JavaFileObject candidate : source2Errors.keySet()) {
        if (sameSource(candidate, file)) {
          key = candidate;
          break;
        }
      }
      errors = key == null ? null : source2Errors.get(key);
      if (errors == null) {
        source2Errors.put(file, errors = new Diagnostics());
      }
    }
    return errors;
  }

  private boolean isIncompleteClassPath() {
    return false;
  }

  public final boolean hasPartialReparseErrors() {
    return this.partialReparseErrors != null && partialReparseRealErrors;
  }

  public final synchronized void startPartialReparse(int from, int to) {
    if (from < 0 || to < from) {
      throw new IllegalArgumentException("Invalid partial diagnostic range from=" + from + " to=" + to);
    }
    if (partialReparseErrors == null) {
      partialReparseErrors = new ArrayList<>();
      Diagnostics errors = getErrors(jfo);
      this.removedErrors = new ArrayList<>();
      SortedMap<Integer, Collection<DiagNode>> subMap = errors.subMap(from, to);
      final List<DiagNode> removedNodes = new ArrayList<>();
      subMap.values().forEach(removedNodes::addAll);
      for (DiagNode node : removedNodes) {
        removedErrors.add(node.diag);
        errors.unlink(node);
      }
      subMap.clear(); // Remove errors in changed method during the partial reparse
      Map<Integer, Collection<DiagNode>> tail = errors.tailMap(to);
      this.affectedErrors = new ArrayList<>(tail.size());
      HashSet<DiagNode> tailNodes = new HashSet<>();
      for (Iterator<Map.Entry<Integer, Collection<DiagNode>>> it = tail.entrySet().iterator();
          it.hasNext(); ) {
        tailNodes.addAll(it.next().getValue());
        it.remove();
      }
      DiagNode node = errors.first;
      while (node != null) {
        if (tailNodes.contains(node)) {
          errors.unlink(node);
          // A diagnostic may already be a DeltaDiagnostic from an earlier successful
          // partial reparse. Do not cast it back to JCDiagnostic; preserve the public
          // Diagnostic wrapper and apply the next offset translation on top of it.
          this.affectedErrors.add(node.diag);
        }
        node = node.next;
      }
    } else {
      this.partialReparseErrors.clear();
    }
    partialReparseRealErrors = false;
  }

  private static <A, B> Map<A, List<B>> mapArraysToLists(final Map<? extends A, B[]> map) {
    final Map<A, List<B>> result = new HashMap<>();
    for (Map.Entry<? extends A, B[]> entry : map.entrySet()) {
      result.put(entry.getKey(), Arrays.asList(entry.getValue()));
    }
    return result;
  }
  public final synchronized void endPartialReparse(final int delta) {
    this.currentDelta += delta;
    final Diagnostics errors = getErrors(jfo);
    if (partialReparseErrors != null) {
      for (Diagnostic<? extends JavaFileObject> diagnostic : partialReparseErrors) {
        errors.add((int) diagnostic.getPosition(), diagnostic);
      }
    }
    if (affectedErrors != null) {
      for (Diagnostic<? extends JavaFileObject> diagnostic : affectedErrors) {
        final Diagnostic<? extends JavaFileObject> translated =
            currentDelta == 0 ? diagnostic : new DeltaDiagnostic(diagnostic, currentDelta);
        errors.add((int) translated.getPosition(), translated);
      }
    }
    partialReparseErrors = null;
    affectedErrors = null;
    removedErrors = null;
    currentDelta = 0;
    partialReparseRealErrors = false;
  }
  /** Aborts the current partial transaction and restores diagnostics removed at its start. */
  public final synchronized void abortPartialReparse() {
    if (partialReparseErrors == null) {
      return;
    }
    final Diagnostics errors = getErrors(jfo);
    if (removedErrors != null) {
      for (Diagnostic<? extends JavaFileObject> diagnostic : removedErrors) {
        errors.add((int) diagnostic.getPosition(), diagnostic);
      }
    }
    if (affectedErrors != null) {
      for (Diagnostic<? extends JavaFileObject> diagnostic : affectedErrors) {
        errors.add((int) diagnostic.getPosition(), diagnostic);
      }
    }
    partialReparseErrors = null;
    affectedErrors = null;
    removedErrors = null;
    currentDelta = 0;
    partialReparseRealErrors = false;
  }

  private static final class D implements Diagnostic {



    private final JCDiagnostic delegate;

    public D(final JCDiagnostic delegate) {
      assert delegate != null;
      this.delegate = delegate;
    }

    @Override
    public Kind getKind() {
      return this.delegate.getKind();
    }

    @Override
    public Object getSource() {
      return this.delegate.getSource();
    }

    @Override
    public long getPosition() {
      return this.delegate.getPosition();
    }

    @Override
    public long getStartPosition() {
      return this.delegate.getStartPosition();
    }

    @Override
    public long getEndPosition() {
      return this.delegate.getEndPosition();
    }

    @Override
    public long getLineNumber() {
      return -1;
    }

    @Override
    public long getColumnNumber() {
      return -1;
    }

    @Override
    public String getCode() {
      return this.delegate.getCode();
    }

    @Override
    public String getMessage(Locale locale) {
      return this.delegate.getMessage(locale);
    }
  }
  private static final class DeltaDiagnostic implements Diagnostic<JavaFileObject> {

    private final Diagnostic<? extends JavaFileObject> delegate;
    private final int delta;

    private DeltaDiagnostic(Diagnostic<? extends JavaFileObject> delegate, int delta) {
      this.delegate = delegate;
      this.delta = delta;
    }

    @Override
    public Kind getKind() {
      return delegate.getKind();
    }

    @Override
    public JavaFileObject getSource() {
      return delegate.getSource();
    }

    @Override
    public long getPosition() {
      return translate(delegate.getPosition());
    }

    @Override
    public long getStartPosition() {
      return translate(delegate.getStartPosition());
    }

    @Override
    public long getEndPosition() {
      return translate(delegate.getEndPosition());
    }

    @Override
    public long getLineNumber() {
      return delegate.getLineNumber();
    }

    @Override
    public long getColumnNumber() {
      return delegate.getColumnNumber();
    }

    @Override
    public String getCode() {
      return delegate.getCode();
    }

    @Override
    public String getMessage(Locale locale) {
      return delegate.getMessage(locale);
    }

    private long translate(long pos) {
      return pos < 0 ? pos : pos + delta;
    }
  }

  private static final class IncompleteClassPath implements Diagnostic<JavaFileObject> {


    private final JavaFileObject file;

    IncompleteClassPath(final JavaFileObject file) {
      this.file = file;
    }

    @Override
    public Kind getKind() {
      return Kind.WARNING;
    }

    @Override
    public JavaFileObject getSource() {
      return file;
    }

    @Override
    public long getPosition() {
      return 0;
    }

    @Override
    public long getStartPosition() {
      return getPosition();
    }

    @Override
    public long getEndPosition() {
      return getPosition();
    }

    @Override
    public long getLineNumber() {
      return getPosition();
    }

    @Override
    public long getColumnNumber() {
      return getPosition();
    }

    @Override
    public String getCode() {
      return "nb.classpath.incomplete"; // NOI18N
    }

    @Override
    public String getMessage(Locale locale) {
      return "Incomplete classpath";
    }
  }

  private static final class Diagnostics extends TreeMap<Integer, Collection<DiagNode>> {
    private DiagNode first;
    private DiagNode last;

    public void add(int pos, Diagnostic<? extends JavaFileObject> diag) {
      Collection<DiagNode> nodes = get((int) diag.getPosition());
      if (nodes == null) {
        put((int) diag.getPosition(), nodes = new ArrayList<>());
      }
      DiagNode node = new DiagNode(last, diag, null);
      nodes.add(node);
      if (last != null) {
        last.next = node;
      }
      last = node;
      if (first == null) {
        first = node;
      }
    }

    private void unlink(DiagNode node) {
      if (node.next == null) {
        last = node.prev;
      } else {
        node.next.prev = node.prev;
      }
      if (node.prev == null) {
        first = node.next;
      } else {
        node.prev.next = node.next;
      }
    }
  }

  private static final class DiagNode {
    private final Diagnostic<? extends JavaFileObject> diag;
    private DiagNode next;
    private DiagNode prev;

    private DiagNode(DiagNode prev, Diagnostic<? extends JavaFileObject> diag, DiagNode next) {
      this.diag = diag;
      this.next = next;
      this.prev = prev;
    }
  }
}
