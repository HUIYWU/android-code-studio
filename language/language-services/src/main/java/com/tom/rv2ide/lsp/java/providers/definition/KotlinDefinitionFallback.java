/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java.providers.definition;

import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService;
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex;
import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex.KotlinTypeDeclaration;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level Java-to-Kotlin navigation fallback.
 *
 * <p>It is intentionally only invoked after javac cannot resolve a definition. Kotlin source is
 * not supplied to javac, and this fallback does not claim member-level Kotlin semantics.
 */
public final class KotlinDefinitionFallback {

  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*;");
  private static final Pattern IMPORT_PATTERN =
      Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*(?:\\.\\*)?)\\s*;");
  private static final Pattern QUALIFIED_IDENTIFIER_PATTERN =
      Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$])*");

  private KotlinDefinitionFallback() {}

  public static List<Location> find(
      JavaCompilerService compiler, Path javaFile, int zeroBasedLine, int zeroBasedColumn) {
    final ModuleProject module = compiler.getModule();
    if (!JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()
        || module == null || javaFile == null) {
      return Collections.emptyList();
    }
    final String source = FileManager.INSTANCE.getDocumentContents(javaFile).toString();
    final String token = identifierAt(source, zeroBasedLine, zeroBasedColumn);
    if (token.isEmpty()) {
      return Collections.emptyList();
    }

    final String qualifiedName = resolveQualifiedName(module, source, token);
    if (qualifiedName == null) {
      return Collections.emptyList();
    }
    final KotlinTypeDeclaration declaration =
        KotlinJvmTypeIndex.findDeclaration(module, qualifiedName);
    if (declaration == null) {
      return Collections.emptyList();
    }
    return Collections.singletonList(location(declaration));
  }

  private static String resolveQualifiedName(ModuleProject module, String source, String token) {
    if (token.indexOf('.') >= 0) {
      return token;
    }
    final Matcher imports = IMPORT_PATTERN.matcher(source);
    while (imports.find()) {
      final String imported = imports.group(1);
      if (imported.endsWith("." + token)) {
        return imported;
      }
      if (imported.endsWith(".*")) {
        final String candidate = imported.substring(0, imported.length() - 1) + token;
        if (KotlinJvmTypeIndex.publicTopLevelTypes(module).contains(candidate)) {
          return candidate;
        }
      }
    }
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
    final String samePackageCandidate = packageName.isEmpty() ? token : packageName + "." + token;
    return KotlinJvmTypeIndex.publicTopLevelTypes(module).contains(samePackageCandidate)
        ? samePackageCandidate
        : null;
  }

  private static String identifierAt(String source, int line, int column) {
    final int offset = offsetAt(source, line, column);
    if (offset < 0 || offset > source.length()) {
      return "";
    }
    final Matcher matcher = QUALIFIED_IDENTIFIER_PATTERN.matcher(source);
    while (matcher.find()) {
      if (matcher.start() <= offset && offset <= matcher.end()) {
        return matcher.group();
      }
    }
    return "";
  }

  private static int offsetAt(String source, int line, int column) {
    int currentLine = 0;
    int offset = 0;
    while (currentLine < line && offset < source.length()) {
      final int next = source.indexOf('\n', offset);
      if (next < 0) {
        return -1;
      }
      offset = next + 1;
      currentLine++;
    }
    return Math.min(offset + Math.max(0, column), source.length());
  }

  private static Location location(KotlinTypeDeclaration declaration) {
    final String source = FileManager.INSTANCE.getDocumentContents(declaration.file).toString();
    final Position start = positionAt(source, declaration.offset);
    final Position end = positionAt(source, declaration.offset + declaration.length);
    return new Location(declaration.file, new Range(start, end));
  }

  private static Position positionAt(String source, int offset) {
    final int boundedOffset = Math.max(0, Math.min(offset, source.length()));
    int line = 0;
    int lineStart = 0;
    for (int index = 0; index < boundedOffset; index++) {
      if (source.charAt(index) == '\n') {
        line++;
        lineStart = index + 1;
      }
    }
    return new Position(line, boundedOffset - lineStart);
  }
}
