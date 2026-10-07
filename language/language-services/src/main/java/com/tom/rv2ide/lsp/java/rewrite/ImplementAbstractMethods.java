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
package com.tom.rv2ide.lsp.java.rewrite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tom.rv2ide.lsp.java.compiler.CompileTask;
import com.tom.rv2ide.lsp.java.compiler.CompilerProvider;
import com.tom.rv2ide.lsp.java.compiler.SynchronizedTask;
import com.tom.rv2ide.lsp.java.utils.EditHelper;
import com.tom.rv2ide.lsp.java.utils.FindHelper;
import com.tom.rv2ide.lsp.java.utils.MethodStubGenerator;

import com.tom.rv2ide.lsp.java.visitors.FindAnonymousTypeDeclaration;
import com.tom.rv2ide.lsp.java.visitors.FindTypeDeclarationAt;
import com.tom.rv2ide.lsp.models.CodeActionItem;
import com.tom.rv2ide.lsp.models.Command;
import com.tom.rv2ide.lsp.models.TextEdit;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.preferences.internal.EditorPreferences;
import com.tom.rv2ide.preferences.utils.EditorUtilKt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;
import jdkx.lang.model.element.ExecutableElement;
import jdkx.lang.model.element.Modifier;
import jdkx.lang.model.element.TypeElement;
import jdkx.lang.model.type.ExecutableType;
import jdkx.lang.model.util.Elements;
import jdkx.tools.JavaFileObject;
import openjdk.source.tree.ClassTree;
import openjdk.source.tree.CompilationUnitTree;
import openjdk.source.tree.ImportTree;
import openjdk.source.tree.Tree;
import openjdk.source.util.Trees;
import openjdk.tools.javac.util.JCDiagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImplementAbstractMethods extends Rewrite {

  private static final Logger LOG = LoggerFactory.getLogger(ImplementAbstractMethods.class);
  private final String className;
  private final String classFile;
  private final String superTypeName;
  private final long position;
  @Nullable private final Path sourceFile;

  public ImplementAbstractMethods(@NonNull JCDiagnostic diagnostic) {
    Object[] args = diagnostic.getArgs();
    String targetName = args[0].toString();
    this.sourceFile = resolveSourceFile(diagnostic);

    if (!isAnonymousTarget(targetName)) {
      this.className = targetName;
      this.classFile = targetName;
      this.superTypeName = targetName;
      this.position = 0;
    } else {
      String ownerName = extractAnonymousOwner(targetName);
      this.classFile = ownerName;
      this.className = targetName;
      this.superTypeName = args[2].toString();
      this.position = diagnostic.getStartPosition();
    }
  }
 
  @NonNull
  @Override
  public Map<Path, TextEdit[]> rewrite(@NonNull CompilerProvider compiler) {
    final Path file = this.sourceFile != null ? this.sourceFile : compiler.findTypeDeclaration(this.classFile);
    if (file == null || file == CompilerProvider.NOT_FOUND) {
      LOG.warn("Unable to find source file for class: {} classFile={}", this.className,
          this.classFile);
      return CANCELLED;
    }

    final SynchronizedTask synchronizedTask = compiler.compile(file);
    return synchronizedTask.get(
        task -> {
          final CompilationUnitTree fileRoot = task.root(file);
          StringBuilder insertText = new StringBuilder();
          Elements elements = task.task.getElements();
          Trees trees = Trees.instance(task.task);
          TypeElement superType = elements.getTypeElement(this.superTypeName);

          ClassTree thisTree = getClassTree(task, file);
          TypeElement thisClass = null;
          if (thisTree != null) {
            thisClass = (TypeElement) trees.getElement(trees.getPath(fileRoot, thisTree));
          }
          if (thisTree == null) {
            thisClass = elements.getTypeElement(this.className);
            if (thisClass != null) {
              thisTree = trees.getTree(thisClass);
            }
          }

          if (thisTree == null || thisClass == null || superType == null) {
            LOG.warn(
                "ImplementAbstractMethods could not resolve target class. className={} classFile={} superTypeName={} position={}",
                this.className,
                this.classFile,
                this.superTypeName,
                this.position);
            return CANCELLED;
          }
          final Set<String> imports = new TreeSet<>();
          final Set<String> addedMethods = new HashSet<>();
          Position insert = EditHelper.insertAtEndOfClass(task.task, fileRoot, thisTree);
          // Insert before the closing brace so existing fields, methods, and initializer blocks remain intact.
          int braceIndent = EditHelper.lineIndent(task.task, fileRoot, thisTree);
          int indent = braceIndent + EditorPreferences.INSTANCE.getTabSize();
          for (Element member : elements.getAllMembers(thisClass)) {
            if (member.getKind() != ElementKind.METHOD || !member.getModifiers().contains(Modifier.ABSTRACT)) {
              continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.getSimpleName().contentEquals("<init>")) {
              continue;
            }
            if (method.getEnclosingElement() instanceof TypeElement) {
              TypeElement owner = (TypeElement) method.getEnclosingElement();
              if (owner.getQualifiedName().contentEquals("java.lang.Object")) {
                continue;
              }
            }
            String signatureKey =
                method.getSimpleName()
                    + "#"
                    + String.join(",", FindHelper.erasedParameterTypes(task, method));
            if (!addedMethods.add(signatureKey)) {
              continue;
            }
            final ExecutableType executableType;
            if (thisClass.asType() instanceof jdkx.lang.model.type.DeclaredType) {
              executableType =
                  (ExecutableType)
                      task.task.getTypes().asMemberOf(
                          (jdkx.lang.model.type.DeclaredType) thisClass.asType(), method);
            } else {
              final openjdk.source.util.TreePath methodPath = trees.getPath(method);
              if (methodPath == null) {
                LOG.warn(
                    "ImplementAbstractMethods could not resolve method path. className={} method={}",
                    this.className,
                    method);
                continue;
              }
              executableType = (ExecutableType) trees.getTypeMirror(methodPath);
            }
            final MethodStubGenerator.GeneratedMethod generated =
                MethodStubGenerator.generate(
                    method,
                    executableType,
                    null,
                    MethodStubGenerator.BodyStrategy.IMPLEMENT_ABSTRACT,
                    task.module());
            imports.addAll(generated.getImports());
            String memberIndent = EditorUtilKt.indentationString(indent);
            String text = generated.getRenderedText();
            if (insertText.length() == 0) {
              insertText.append("\n\n");
            } else {
              insertText.append("\n");
            }
            String[] lines = text.split("\\r?\\n", -1);
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
              if (lineIndex > 0) {
                insertText.append("\n");
              }
              insertText.append(memberIndent);
              insertText.append(lines[lineIndex]);
            }
            insertText.append("\n");
            insertText.append(EditorUtilKt.indentationString(braceIndent));
          }

          if (insertText.length() == 0) {
            LOG.warn(
                "ImplementAbstractMethods found no abstract methods to generate. className={} superTypeName={} position={}",
                this.className,
                this.superTypeName,
                this.position);
            return CANCELLED;
          }

          final List<TextEdit> edits = new ArrayList<>();
          edits.add(new TextEdit(new Range(insert, insert), insertText.toString()));
          addImports(compiler, task, file, imports, edits);

          return Collections.singletonMap(file, edits.toArray(new TextEdit[0]));
        });
  }

  private void addImports(
      CompilerProvider compiler,
      CompileTask task,
      Path file,
      Set<String> imports,
      List<TextEdit> edits) {
    imports =
        imports.stream()
            .filter(name -> !name.startsWith("java.lang."))
            .filter(name -> name.contains("."))
            .collect(Collectors.toSet());
    for (String name : imports) {
      final List<TextEdit> importEdits =
          EditHelper.addImportIfNeeded(compiler, file, getFileImports(task, file), name);
      if (importEdits != null && !importEdits.isEmpty()) {
        edits.addAll(importEdits);
      }
    }
  }

  private Set<String> getFileImports(@NonNull CompileTask task, Path file) {
    return task.root(file).getImports().stream()
        .map(ImportTree::getQualifiedIdentifier)
        .map(Tree::toString)
        .collect(Collectors.toSet());
  }

  @Nullable
  private ClassTree getClassTree(@NonNull CompileTask task, Path file) {
    ClassTree thisTree = null;
    CompilationUnitTree root = task.root(file);
    if (root == null) {
      return null;
    }

    if (position != 0) {
      final FindTypeDeclarationAt scanner = new FindTypeDeclarationAt(task.task);
      thisTree = scanner.scan(root, position);
    }

    if (thisTree == null) {
      final FindAnonymousTypeDeclaration scanner =
          new FindAnonymousTypeDeclaration(task.task, root);
      thisTree = scanner.scan(root, position);
    }

    return thisTree;
  }

  @Override
  protected void finalizeCodeAction(@NonNull CodeActionItem action) {
    action.setCommand(new Command("Format code", Command.FORMAT_CODE));
  }
  @Nullable
  private static Path resolveSourceFile(@NonNull JCDiagnostic diagnostic) {
    try {
      JavaFileObject source = diagnostic.getSource();
      if (source == null) {
        return null;
      }
      return Path.of(source.toUri());
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static boolean isAnonymousTarget(String targetName) {
    return targetName.length() > 2
        && targetName.charAt(0) == '<'
        && targetName.charAt(targetName.length() - 1) == '>';
  }

  private static String extractAnonymousOwner(String targetName) {
    String owner = targetName.substring(1, targetName.length() - 1).trim();

    // Diagnostic display names for anonymous types are localized, so prefer extracting the
    // binary name tail instead of matching a specific language prefix such as "anonymous".
    int binaryNameStart = findBinaryNameStart(owner);
    if (binaryNameStart > 0 && binaryNameStart < owner.length()) {
      owner = owner.substring(binaryNameStart);
    } else {
      int lastSpace = owner.lastIndexOf(' ');
      if (lastSpace >= 0 && lastSpace + 1 < owner.length()) {
        owner = owner.substring(lastSpace + 1);
      }
    }

    int dollar = owner.indexOf('$');
    if (dollar >= 0) {
      owner = owner.substring(0, dollar);
    }
    return owner;
  }

  private static int findBinaryNameStart(String text) {
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (!isAsciiIdentifierStart(current)) {
        continue;
      }
      int cursor = index + 1;
      while (cursor < text.length() && isAsciiIdentifierPart(text.charAt(cursor))) {
        cursor++;
      }
      if (cursor < text.length() && text.charAt(cursor) == '.') {
        return index;
      }
    }
    return -1;
  }

  private static boolean isAsciiIdentifierStart(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
  }

  private static boolean isAsciiIdentifierPart(char ch) {
    return isAsciiIdentifierStart(ch) || (ch >= '0' && ch <= '9');
  }

}
