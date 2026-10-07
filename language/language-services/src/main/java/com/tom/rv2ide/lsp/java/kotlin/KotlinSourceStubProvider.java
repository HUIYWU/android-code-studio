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
package com.tom.rv2ide.lsp.java.kotlin;

import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdkx.tools.JavaFileObject;

/**
 * Produces conservative Kotlin-as-Java stubs for javac full compilations.
 *
 * <p>It considers Java-visible top-level Kotlin JVM types reached by an explicit import, a star
 * import, or the Java source package. Star-import and same-package candidates are limited to type
 * names observed in the current compilation working set. The source projection covers common
 * constructors, functions and properties, but remains conservative: a real Kotlin class output
 * always takes precedence and unsupported Kotlin signatures degrade to {@code Object} rather than
 * being guessed incorrectly.
 */
public final class KotlinSourceStubProvider {
  private static final Pattern JAVA_IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*;");
  private static final Pattern JAVA_STAR_IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\.\\*\\s*;");
  private static final Pattern JAVA_PACKAGE =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*;");

  private KotlinSourceStubProvider() {}

  public static Collection<JavaFileObject> stubsFor(
      ModuleProject module, Collection<? extends JavaFileObject> javaSources) {
    if (module == null || javaSources == null || javaSources.isEmpty()) {
      return Collections.emptyList();
    }

    final Set<String> imports = new LinkedHashSet<>();
    final Set<String> starImports = new LinkedHashSet<>();
    final Set<String> sourcePackages = new LinkedHashSet<>();
    final Set<String> referencedNames = new LinkedHashSet<>();
    for (JavaFileObject source : javaSources) {
      if (KotlinAbiStubJavaFileObject.isKotlinAbiStub(source)) {
        continue;
      }
      collectVisiblePackages(source, imports, starImports, sourcePackages, referencedNames);
    }

    final Set<String> sourceTypes = KotlinJvmTypeIndex.publicTopLevelTypes(module);
    final Set<String> classOutputTypes = KotlinClassOutputProvider.publicDependencyTopLevelTypes(module);
    addOnDemandTypes(sourceTypes, starImports, referencedNames, imports);
    addOnDemandTypes(sourceTypes, sourcePackages, referencedNames, imports);
    final List<JavaFileObject> stubs = new ArrayList<>();
    final List<String> pending = new ArrayList<>(imports);
    final Set<String> generated = new LinkedHashSet<>();
    for (int index = 0; index < pending.size(); index++) {
      final String imported = pending.get(index);
      // A real Kotlin class output has precedence. Do not create a duplicate javac type.
      if (!generated.add(imported)
          || !sourceTypes.contains(imported)
          || classOutputTypes.contains(imported)) {
        continue;
      }
      final List<KotlinJvmTypeIndex.KotlinTypeDeclaration> multifileDeclarations =
          KotlinJvmTypeIndex.findMultifileDeclarations(module, imported);
      final KotlinJvmTypeIndex.KotlinTypeDeclaration declaration = multifileDeclarations.isEmpty()
          ? KotlinJvmTypeIndex.findDeclaration(module, imported)
          : multifileDeclarations.get(0);
      if (declaration == null) {
        continue;
      }
      final String stub = multifileDeclarations.isEmpty()
          ? generateStub(module, imported, declaration.file, sourceTypes)
          : generateMultifileStub(module, imported, multifileDeclarations, sourceTypes);
      if (stub != null) {
        stubs.add(new KotlinAbiStubJavaFileObject(imported, stub, module.getSourceIndexVersion()));
        addReferencedKotlinTypes(imported, stub, sourceTypes, generated, pending);
      }
    }
    return stubs;
  }

  private static void collectVisiblePackages(
      JavaFileObject source,
      Set<String> imports,
      Set<String> starImports,
      Set<String> sourcePackages,
      Set<String> referencedNames) {
    final CharSequence content;
    try {
      content = source.getCharContent(true);
    } catch (Exception ignored) {
      return;
    }
    final Matcher importMatcher = JAVA_IMPORT.matcher(content);
    while (importMatcher.find()) {
      imports.add(importMatcher.group(1));
    }
    final Matcher starImportMatcher = JAVA_STAR_IMPORT.matcher(content);
    while (starImportMatcher.find()) {
      starImports.add(starImportMatcher.group(1));
    }
    final Matcher packageMatcher = JAVA_PACKAGE.matcher(content);
    if (packageMatcher.find()) {
      sourcePackages.add(packageMatcher.group(1));
    }
    collectReferencedNames(content, referencedNames);
  }

  private static void addOnDemandTypes(
      Set<String> sourceTypes,
      Set<String> visiblePackages,
      Set<String> referencedNames,
      Set<String> imports) {
    for (String sourceType : sourceTypes) {
      final int separator = sourceType.lastIndexOf('.');
      final String packageName = separator < 0 ? "" : sourceType.substring(0, separator);
      final String simpleName = separator < 0 ? sourceType : sourceType.substring(separator + 1);
      if (visiblePackages.contains(packageName) && referencedNames.contains(simpleName)) {
        imports.add(sourceType);
      }
    }
  }

  private static void collectReferencedNames(CharSequence content, Set<String> names) {
    final Matcher identifier = Pattern.compile("\\b[A-Z][A-Za-z0-9_$]*\\b").matcher(content);
    while (identifier.find()) {
      names.add(identifier.group());
    }
  }

  private static void addReferencedKotlinTypes(
      String ownerType,
      String stub,
      Set<String> sourceTypes,
      Set<String> generated,
      List<String> pending) {
    final int separator = ownerType.lastIndexOf('.');
    final String ownerPackage = separator < 0 ? "" : ownerType.substring(0, separator);
    final Set<String> referencedNames = new LinkedHashSet<>();
    collectReferencedNames(stub, referencedNames);
    for (String candidate : sourceTypes) {
      if (generated.contains(candidate)) {
        continue;
      }
      final int candidateSeparator = candidate.lastIndexOf('.');
      final String candidatePackage =
          candidateSeparator < 0 ? "" : candidate.substring(0, candidateSeparator);
      final String candidateName =
          candidateSeparator < 0 ? candidate : candidate.substring(candidateSeparator + 1);
      if ((ownerPackage.equals(candidatePackage) && referencedNames.contains(candidateName))
          || stub.contains(candidate)) {
        pending.add(candidate);
      }
    }
  }

  private static String generateMultifileStub(
      ModuleProject module,
      String qualifiedName,
      List<KotlinJvmTypeIndex.KotlinTypeDeclaration> declarations,
      Set<String> knownTypes) {
    String merged = null;
    for (KotlinJvmTypeIndex.KotlinTypeDeclaration declaration : declarations) {
      final String stub = generateStub(module, qualifiedName, declaration.file, knownTypes);
      if (stub == null) {
        continue;
      }
      merged = merged == null ? stub : KotlinJvmAbiStubGenerator.mergeGeneratedStubs(merged, stub);
    }
    return merged;
  }

  private static String generateStub(
      ModuleProject module, String qualifiedName, Path kotlinFile, Set<String> knownTypes) {
    final String source = FileManager.INSTANCE.getDocumentContents(kotlinFile).toString();
    return KotlinJvmAbiStubGenerator.generate(
        qualifiedName,
        kotlinFile.getFileName().toString(),
        source,
        knownTypes,
        KotlinJvmTypeIndex.visibleDirectTypeAliases(module, kotlinFile),
        KotlinJvmTypeIndex.visibleGenericTypeAliases(module, kotlinFile));
  }
}