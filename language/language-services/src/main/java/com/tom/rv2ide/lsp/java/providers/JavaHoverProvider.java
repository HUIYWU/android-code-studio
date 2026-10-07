/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.tom.rv2ide.lsp.java.providers;

import androidx.annotation.NonNull;
import com.tom.rv2ide.lsp.java.compiler.CompileTask;
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService;
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmSourceNavigator;
import com.tom.rv2ide.lsp.java.utils.MarkdownHelper;
import com.tom.rv2ide.lsp.java.utils.NavigationHelper;
import com.tom.rv2ide.lsp.java.utils.ShortTypePrinter;
import com.tom.rv2ide.lsp.models.DefinitionParams;
import com.tom.rv2ide.lsp.models.MarkupContent;
import com.tom.rv2ide.lsp.models.MarkupKind;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.progress.ICancelChecker;
import java.util.List;
import java.util.StringJoiner;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;
import jdkx.lang.model.element.ExecutableElement;
import jdkx.lang.model.element.TypeElement;
import jdkx.lang.model.element.TypeParameterElement;
import jdkx.lang.model.element.VariableElement;
import jdkx.lang.model.type.TypeMirror;
import openjdk.source.doctree.DocCommentTree;
import openjdk.source.util.DocTrees;

/** Provides conservative, attribution-backed Java and Kotlin ABI signature hover content. */
public final class JavaHoverProvider extends CancelableServiceProvider {
  private final JavaCompilerService compiler;

  public JavaHoverProvider(JavaCompilerService compiler, ICancelChecker cancelChecker) {
    super(cancelChecker);
    this.compiler = compiler;
  }

  @NonNull
  public MarkupContent hover(@NonNull DefinitionParams params) {
    abortIfCancelled();
    final int line = params.getPosition().getLine() + 1;
    final int column = params.getPosition().getColumn() + 1;
    return compiler.compile(params.getFile()).get(task -> hover(task, params, line, column));
  }

  @NonNull
  private MarkupContent hover(
      CompileTask task, DefinitionParams params, int line, int column) {
    abortIfCancelled();
    final Element element =
        NavigationHelper.findElement(task, params.getFile(), line, column, this);
    if (element == null) {
      return new MarkupContent();
    }
    final String signature = formatSignature(element);
    if (signature.isEmpty()) {
      return new MarkupContent();
    }
    String documentation = javaDocumentation(task, element);
    if (documentation.isEmpty()) {
      documentation = kotlinDocumentation(task, element);
    }
    return new MarkupContent(formatHoverMarkdown(signature, documentation), MarkupKind.MARKDOWN);
  }

  static String formatHoverMarkdown(String signature, String documentation) {
    final String codeBlock = "```java\n" + signature + "\n```";
    return documentation.isEmpty() ? codeBlock : codeBlock + "\n---\n" + documentation;
  }

  private static String javaDocumentation(CompileTask task, Element element) {
    final DocCommentTree documentation = DocTrees.instance(task.task).getDocCommentTree(element);
    if (documentation == null) {
      return "";
    }
    return MarkdownHelper.asMarkdown(documentation).trim();
  }

  private String kotlinDocumentation(CompileTask task, Element element) {
    final Location location = KotlinJvmSourceNavigator.find(task.module(), element);
    if (location == null) {
      return "";
    }
    final String source = FileManager.INSTANCE.getDocumentContents(location.getFile()).toString();
    final int declarationOffset = offsetAt(
        source, location.getRange().getStart().getLine(), location.getRange().getStart().getColumn());
    return extractAdjacentKDoc(source, declarationOffset);
  }

  private static int offsetAt(String source, int line, int column) {
    int offset = 0;
    for (int currentLine = 0; currentLine < line && offset < source.length(); currentLine++) {
      final int lineEnd = source.indexOf('\n', offset);
      offset = lineEnd < 0 ? source.length() : lineEnd + 1;
    }
    return Math.min(source.length(), offset + Math.max(0, column));
  }

  static String extractAdjacentKDoc(String source, int declarationOffset) {
    if (source == null || declarationOffset < 0 || declarationOffset > source.length()) {
      return "";
    }
    int end = declarationOffset;
    while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) {
      end--;
    }
    if (end < 2 || source.charAt(end - 1) != '/' || source.charAt(end - 2) != '*') {
      return "";
    }
    final int start = source.lastIndexOf("/**", end - 2);
    if (start < 0 || source.indexOf("*/", start) != end - 2) {
      return "";
    }
    final String body = source.substring(start + 3, end - 2);
    final StringJoiner lines = new StringJoiner("\n");
    for (String line : body.split("\\r?\\n", -1)) {
      lines.add(line.replaceFirst("^\\s*\\* ?", ""));
    }
    return lines.toString().trim();
  }

  static String formatSignature(Element element) {
    if (element == null) {
      return "";
    }
    if (element instanceof TypeElement) {
      final TypeElement type = (TypeElement) element;
      return typeKeyword(type.getKind()) + " " + type.getSimpleName() + typeParameters(type.getTypeParameters());
    }
    if (element instanceof ExecutableElement) {
      final ExecutableElement executable = (ExecutableElement) element;
      final String name = executable.getKind() == ElementKind.CONSTRUCTOR
          ? executable.getEnclosingElement().getSimpleName().toString()
          : executable.getSimpleName().toString();
      final String returnType = executable.getKind() == ElementKind.CONSTRUCTOR
          ? ""
          : ShortTypePrinter.NO_PACKAGE.print(executable.getReturnType()) + " ";
      final StringJoiner parameters = new StringJoiner(", ");
      for (VariableElement parameter : executable.getParameters()) {
        parameters.add(ShortTypePrinter.NO_PACKAGE.print(parameter.asType()) + " " + parameter.getSimpleName());
      }
      final String typeParameters = typeParameters(executable.getTypeParameters());
      return (typeParameters.isEmpty() ? "" : typeParameters + " ")
          + returnType + name + "(" + parameters + ")";
    }
    if (element instanceof VariableElement) {
      final VariableElement variable = (VariableElement) element;
      return ShortTypePrinter.NO_PACKAGE.print(variable.asType()) + " " + variable.getSimpleName();
    }
    if (element instanceof TypeParameterElement) {
      final TypeParameterElement parameter = (TypeParameterElement) element;
      return parameter.getSimpleName() + typeParameterBoundSuffix(parameter);
    }
    return "";
  }

  private static String typeKeyword(ElementKind kind) {
    switch (kind) {
      case INTERFACE:
        return "interface";
      case ENUM:
        return "enum";
      case ANNOTATION_TYPE:
        return "@interface";
      default:
        return "class";
    }
  }

  private static String typeParameters(List<? extends TypeParameterElement> parameters) {
    if (parameters.isEmpty()) {
      return "";
    }
    final StringJoiner joiner = new StringJoiner(", ", "<", ">");
    for (TypeParameterElement parameter : parameters) {
      joiner.add(parameter.getSimpleName() + typeParameterBoundSuffix(parameter));
    }
    return joiner.toString();
  }

  private static String typeParameterBoundSuffix(TypeParameterElement parameter) {
    final StringJoiner bounds = new StringJoiner(" & ");
    for (TypeMirror bound : parameter.getBounds()) {
      final String printed = ShortTypePrinter.NO_PACKAGE.print(bound);
      if (!"Object".equals(printed)) {
        bounds.add(printed);
      }
    }
    return bounds.length() == 0 ? "" : " extends " + bounds;
  }
}
