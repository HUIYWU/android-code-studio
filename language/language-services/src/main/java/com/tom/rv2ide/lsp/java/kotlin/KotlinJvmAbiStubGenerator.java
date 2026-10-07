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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative source-level Kotlin-to-Java ABI projection used while compiler-provided Kotlin JVM
 * symbols are unavailable.
 *
 * <p>Tree-sitter supplies declaration and body boundaries while JVM projection remains deliberately
 * conservative. Only common declarations whose JVM form is sufficiently unambiguous are emitted;
 * unsupported signatures use {@link Object} to preserve Java attribution without claiming false
 * Kotlin semantic precision.
 */
final class KotlinJvmAbiStubGenerator {

  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)");
  private static final Pattern FILE_JVM_NAME_PATTERN =
      Pattern.compile("(?m)^\\s*@file:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)");
  private static final Pattern KOTLIN_IMPORT_PATTERN =
      Pattern.compile(
          "(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(\\.\\*)?"
              + "(?:\\s+as\\s+([A-Za-z_$][\\w$]*))?\\s*$");
  private static final Pattern JAVA_TYPE_NAME_PATTERN =
      Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");
  private static final ThreadLocal<TypeResolutionContext> TYPE_CONTEXT = new ThreadLocal<>();
  private static final ThreadLocal<GenerationMode> GENERATION_MODE = new ThreadLocal<>();
  private static final Pattern GENERATED_TYPE_DECLARATION_PATTERN =
      Pattern.compile("\\b(?:class|interface|enum|@interface)\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern TYPE_PATTERN =
      Pattern.compile(
          "(?m)^\\s*((?:(?:public|protected|internal|private|open|abstract|sealed|data|inner|value)\\s+)*)"
              + "((?:enum\\s+class)|annotation\\s+class|class|interface|object)"
              + "\\s+([A-Za-z_][\\w]*)(?:\\s*<[^>{}()]*>)?");
  private static final Pattern VALUE_CLASS_UNDERLYING_PATTERN =
      Pattern.compile(
          "(?s)\\bvalue\\s+class\\s+([A-Za-z_][\\w]*)\\s*"
              + "(?:<[^>{}()]*>\\s*)?\\(\\s*(?:val|var)\\s+[A-Za-z_][\\w]*\\s*:\\s*"
              + "([^,)=]+)\\s*\\)");
  private static final Pattern TYPE_ALIAS_PATTERN =
      Pattern.compile("(?m)^\\s*typealias\\s+([A-Za-z_][\\w]*)\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern GENERIC_TYPE_ALIAS_PATTERN =
      Pattern.compile(
          "(?m)^\\s*typealias\\s+([A-Za-z_][\\w]*)\\s*<([^<>]+)>\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern SECONDARY_CONSTRUCTOR_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private)\\s+)*)constructor\\s*\\((.*?)\\)\\s*(?::.*)?$");
  private static final Pattern FUNCTION_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|abstract|final|override|suspend|"
              + "operator|infix|inline|tailrec|external)\\s+)*)fun\\s+(?:<[^>]+>\\s*)?"
              + "([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)"
              + "(?:\\s*:\\s*([^=\\{]+?))?(?:\\s+where\\b|\\s*[=\\{]|\\s*$).*$");
  private static final Pattern EXTENSION_FUNCTION_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|abstract|final|override|suspend|"
              + "operator|infix|inline|tailrec|external)\\s+)*)fun\\s+(?:<[^>]+>\\s*)?"
              + "([A-Za-z_][\\w]*(?:<[^>]+>)?\\??)\\.([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)"
              + "(?:\\s*:\\s*([^=\\{]+?))?(?:\\s+where\\b|\\s*[=\\{]|\\s*$).*$");
private static final Pattern PROPERTY_PATTERN =
      Pattern.compile(
          "^\\s*((?:(?:public|protected|internal|private|open|override|const|lateinit)\\s+)*)"
              + "(val|var)\\s+([A-Za-z_][\\w]*)(?![\\w.])\\s*(?::\\s*([^=\\{]+))?.*$");
  private static final Pattern JVM_STATIC_FUNCTION_PATTERN =
      Pattern.compile(
          "(?s)(?:@JvmName\\s*\\(\\s*\\\"[A-Za-z_$][\\w$]*\\\"\\s*\\)\\s*)?"
              + "@JvmStatic\\s*(?:\\([^)]*\\))?\\s*"
              + "(?:@JvmName\\s*\\(\\s*\\\"[A-Za-z_$][\\w$]*\\\"\\s*\\)\\s*)?"
              + "((?:(?:public|protected|internal|private|open|abstract|final|override|suspend|"
              + "operator|infix|inline|tailrec|external)\\s+)*)fun\\s+(?:<[^>]+>\\s*)?"
              + "([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)\\s*(?::\\s*([^=\\{]+))?");
  private static final Pattern JVM_STATIC_PROPERTY_PATTERN =
      Pattern.compile(
          "(?s)(?:(?:@(?:get:|set:)?JvmName\\s*\\(\\s*\\\"[A-Za-z_$][\\w$]*\\\"\\s*\\)|@(?:get:|set:)?JvmSynthetic(?:\\s*\\([^)]*\\))?)\\s*)*"
              + "@JvmStatic\\s*(?:\\([^)]*\\))?\\s*"
              + "(?:(?:@(?:get:|set:)?JvmName\\s*\\(\\s*\\\"[A-Za-z_$][\\w$]*\\\"\\s*\\)|@(?:get:|set:)?JvmSynthetic(?:\\s*\\([^)]*\\))?)\\s*)*"
              + "((?:(?:public|protected|internal|private|open|override|const|lateinit)\\s+)*)"
              + "(val|var)\\s+([A-Za-z_][\\w]*)\\s*(?::\\s*([^=\\{]+))?");
  private static final Pattern JVM_FIELD_PROPERTY_PATTERN =
      Pattern.compile(
          "(?s)@JvmField\\s*(?:\\([^)]*\\))?\\s*"
              + "((?:(?:public|protected|internal|private|open|override|const|lateinit)\\s+)*)"
              + "(val|var)\\s+([A-Za-z_][\\w]*)\\s*(?::\\s*([^=\\{]+))?");

  private KotlinJvmAbiStubGenerator() {}

  enum GenerationMode {
    AUTO,
    STRUCTURED,
    FALLBACK
  }

  private static GenerationMode generationMode() {
    final GenerationMode mode = GENERATION_MODE.get();
    return mode == null ? GenerationMode.AUTO : mode;
  }

  private static boolean structuredGenerationEnabled() {
    return generationMode() != GenerationMode.FALLBACK;
  }

  private static boolean fallbackGenerationEnabled() {
    return generationMode() != GenerationMode.STRUCTURED;
  }

  static String generate(String qualifiedName, String kotlinFileName, String source) {
    return generate(qualifiedName, kotlinFileName, source, java.util.Collections.emptySet());
  }

  static String generateForTest(
      String qualifiedName,
      String kotlinFileName,
      String source,
      Set<String> knownTypes,
      GenerationMode mode) {
    final GenerationMode previous = GENERATION_MODE.get();
    GENERATION_MODE.set(mode);
    try {
      return generate(qualifiedName, kotlinFileName, source, knownTypes);
    } finally {
      if (previous == null) {
        GENERATION_MODE.remove();
      } else {
        GENERATION_MODE.set(previous);
      }
    }
  }

  static String generate(
      String qualifiedName, String kotlinFileName, String source, Set<String> knownTypes) {
    return generate(qualifiedName, kotlinFileName, source, knownTypes, java.util.Collections.emptyMap());
  }

  static String generate(
      String qualifiedName,
      String kotlinFileName,
      String source,
      Set<String> knownTypes,
      Map<String, String> visibleTypeAliases) {
    return generate(qualifiedName, kotlinFileName, source, knownTypes, visibleTypeAliases,
        java.util.Collections.emptyMap());
  }

  static String generate(
      String qualifiedName,
      String kotlinFileName,
      String source,
      Set<String> knownTypes,
      Map<String, String> visibleTypeAliases,
      Map<String, KotlinJvmTypeIndex.GenericTypeAlias> visibleGenericTypeAliases) {
    final int separator = qualifiedName.lastIndexOf('.');
    final String packageName = separator < 0 ? "" : qualifiedName.substring(0, separator);
    final String simpleName = separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    final Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
    final String sourcePackage = packageMatcher.find() ? packageMatcher.group(1) : "";
    if (!packageName.equals(sourcePackage)) {
      return null;
    }

    final TypeResolutionContext previous = TYPE_CONTEXT.get();
    TYPE_CONTEXT.set(
        TypeResolutionContext.create(
            sourcePackage, simpleName, source, knownTypes, visibleTypeAliases,
            visibleGenericTypeAliases));
    try {
      final List<KotlinJvmSyntaxParser.TypeSyntax> sourceTypes =
          structuredGenerationEnabled()
              ? KotlinJvmSyntaxParser.findTopLevelTypeSyntaxes(source)
              : null;
      KotlinJvmSyntaxParser.TypeSyntax syntax = null;
      if (sourceTypes != null) {
        for (KotlinJvmSyntaxParser.TypeSyntax candidate : sourceTypes) {
          if (simpleName.equals(candidate.name)) {
            syntax = candidate;
            break;
          }
        }
      }
      if (syntax != null) {
        if (syntax.privateType) {
          return null;
        }
        final String structured = generateType(packageName, simpleName, syntax, sourceTypes);
        if (generationMode() != GenerationMode.AUTO) {
          return rejectConflictingJvmSurfaces(structured);
        }
        final String fallback = generateTypeFallbackIfPresent(
            packageName, simpleName, source);
        return rejectConflictingJvmSurfaces(
            fallback == null ? structured : mergeGeneratedStubs(structured, fallback));
      }

      // Retain the previous scanner as a compatibility fallback when the native grammar is
      // unavailable or an incomplete edit produces no usable top-level declaration.
      if (fallbackGenerationEnabled()) {
        final String fallback = generateTypeFallbackIfPresent(packageName, simpleName, source);
        if (fallback != null) {
          return rejectConflictingJvmSurfaces(fallback);
        }
      }
      return isFacadeName(simpleName, kotlinFileName, source)
          ? generateFacade(packageName, simpleName, source)
          : null;
    } finally {
      if (previous == null) {
        TYPE_CONTEXT.remove();
      } else {
        TYPE_CONTEXT.set(previous);
      }
    }
  }

  private static String generateType(
      String packageName,
      String simpleName,
      KotlinJvmSyntaxParser.TypeSyntax syntax,
      List<KotlinJvmSyntaxParser.TypeSyntax> sourceTypes) {
    final boolean isObject = syntax.objectType();
    final boolean isInterface = syntax.interfaceType;
    final StringBuilder out = header(packageName);
    if (syntax.enumType) {
      out.append("public enum ").append(simpleName).append(" { ; }\n");
      return out.toString();
    }
    if (syntax.annotationType) {
      out.append("public @interface ").append(simpleName).append(" {}\n");
      return out.toString();
    }
    if (syntax.valueType) {
      appendStructuredValueClass(out, simpleName, syntax, false);
      return out.toString();
    }
    registerTypeVariables(syntax.typeParameters);
    out.append("public ").append(isInterface ? "interface " : "class ")
        .append(simpleName).append(javaTypeParameters(syntax.typeParameters).trim())
        .append(javaInheritanceClause(syntax.superTypes, isInterface)).append(" {\n");
    if (isObject) {
      out.append("  public static final ").append(simpleName).append(" INSTANCE = null;\n");
    } else if (!isInterface) {
      appendSyntaxConstructors(
          out,
          simpleName,
          syntax.constructorParameters,
          syntax.primaryConstructorPresent,
          syntax.constructorVisibility,
          syntax.constructorJvmOverloads,
          syntax.secondaryConstructors);
      appendSyntaxConstructorProperties(out, syntax.constructorParameters);
    }
    appendSyntaxMembers(out, syntax.members, isInterface, false);
    if (!isInterface) {
      appendDefaultInterfaceForwarders(out, syntax, sourceTypes);
    }
    if (syntax.companionBody != null) {
      appendCompanionSyntax(out, syntax.companionName, syntax.companionMembers);
    }
    appendNestedTypes(out, syntax.nestedTypes);
    out.append("}\n");
    return out.toString();
  }

  private static void appendStructuredValueClass(
      StringBuilder out,
      String simpleName,
      KotlinJvmSyntaxParser.TypeSyntax syntax,
      boolean nested) {
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    final Set<String> outerVariables = context == null
        ? java.util.Collections.emptySet()
        : new LinkedHashSet<>(context.typeVariables);
    if (context != null && nested) {
      context.typeVariables.clear();
    }
    registerTypeVariables(syntax.typeParameters);
    try {
      out.append(nested ? "  public static final class " : "public final class ")
          .append(simpleName).append(javaTypeParameters(syntax.typeParameters).trim()).append(" {\n");
      final KotlinJvmSyntaxParser.ConstructorParameterSyntax underlying =
          syntax.constructorParameters.size() == 1
              && syntax.constructorParameters.get(0).property
              ? syntax.constructorParameters.get(0)
              : null;
      if (underlying == null) {
        out.append("  private ").append(simpleName).append("() {}\n");
      } else {
        final String type = javaType(underlying.type);
        out.append("  private ").append(simpleName).append('(').append(type).append(' ')
            .append(safeName(underlying.name, 0)).append(") {}\n");
      }
      out.append(nested ? "  }\n" : "}\n");
    } finally {
      if (context != null) {
        context.typeVariables.clear();
        context.typeVariables.addAll(outerVariables);
      }
    }
  }

  private static void appendNestedTypes(
      StringBuilder out, List<KotlinJvmSyntaxParser.TypeSyntax> nestedTypes) {
    for (KotlinJvmSyntaxParser.TypeSyntax nested : nestedTypes) {
      if (nested.privateType || nested.name == null || nested.name.isEmpty()) {
        continue;
      }
      final boolean interfaceType = nested.interfaceType;
      final boolean objectType = nested.objectType();
      if (nested.enumType) {
        out.append("  public enum ").append(nested.name).append(" { ; }\n");
        continue;
      }
      if (nested.annotationType) {
        out.append("  public @interface ").append(nested.name).append(" {}\n");
        continue;
      }
      if (nested.valueType) {
        appendStructuredValueClass(out, nested.name, nested, true);
        continue;
      }
      final TypeResolutionContext context = TYPE_CONTEXT.get();
      final Set<String> outerVariables = context == null
          ? java.util.Collections.emptySet()
          : new LinkedHashSet<>(context.typeVariables);
      if (context != null && !nested.innerType) {
        context.typeVariables.clear();
      }
      registerTypeVariables(nested.typeParameters);
      try {
        out.append("  public ");
        if (!nested.innerType) {
          out.append("static ");
        }
        out.append(interfaceType ? "interface " : "class ")
            .append(nested.name).append(javaTypeParameters(nested.typeParameters).trim())
            .append(javaInheritanceClause(nested.superTypes, interfaceType)).append(" {\n");
        if (objectType) {
          out.append("  public static final ").append(nested.name).append(" INSTANCE = null;\n");
        } else if (!interfaceType) {
          appendSyntaxConstructors(
              out, nested.name, nested.constructorParameters, nested.primaryConstructorPresent,
              nested.constructorVisibility, nested.constructorJvmOverloads,
              nested.secondaryConstructors, false);
          appendSyntaxConstructorProperties(out, nested.constructorParameters);
        }
        appendSyntaxMembers(out, nested.members, interfaceType, false);
        if (nested.companionBody != null) {
        appendCompanionSyntax(out, nested.companionName, nested.companionMembers);
      }
        appendNestedTypes(out, nested.nestedTypes);
        out.append("  }\n");
      } finally {
        if (context != null) {
          context.typeVariables.clear();
          context.typeVariables.addAll(outerVariables);
        }
      }
    }
  }

  static String mergeGeneratedStubsForTest(String structured, String fallback) {
    return mergeGeneratedStubs(structured, fallback);
  }

  static String mergeGeneratedStubs(String structured, String fallback) {
    final int structuredOpen = structured.indexOf('{');
    final int fallbackOpen = fallback.indexOf('{');
    if (structuredOpen < 0 || fallbackOpen < 0) {
      return structured;
    }
    final int structuredClose = matchingBrace(structured, structuredOpen);
    final int fallbackClose = matchingBrace(fallback, fallbackOpen);
    if (structuredClose <= structuredOpen || fallbackClose <= fallbackOpen) {
      return structured;
    }
    final String structuredHeader = structured.substring(0, structuredOpen + 1);
    if (structuredHeader.contains(" enum ") || structuredHeader.contains(" @interface ")) {
      return structured;
    }
    final List<GeneratedMember> primary = generatedMembers(
        structured.substring(structuredOpen + 1, structuredClose),
        generatedTypeVariableErasures(structured.substring(0, structuredOpen)));
    final List<GeneratedMember> supplemental = generatedMembers(
        fallback.substring(fallbackOpen + 1, fallbackClose),
        generatedTypeVariableErasures(fallback.substring(0, fallbackOpen)));
    final Map<String, GeneratedMember> byKey = new LinkedHashMap<>();
    for (GeneratedMember member : primary) {
      byKey.putIfAbsent(member.key, member);
    }
    boolean changed = false;
    for (GeneratedMember member : supplemental) {
      final GeneratedMember existing = byKey.get(member.key);
      if (existing == null) {
        primary.add(member);
        byKey.put(member.key, member);
        changed = true;
      } else if (member.typeName != null && existing.typeName != null) {
        final String merged = mergeGeneratedStubs(existing.text, member.text);
        if (!merged.equals(existing.text)) {
          existing.text = merged;
          changed = true;
        }
      }
    }
    if (!changed) {
      return structured;
    }
    final StringBuilder out = new StringBuilder(structuredHeader).append('\n');
    for (GeneratedMember member : primary) {
      out.append(member.text);
      if (!member.text.endsWith("\n")) out.append('\n');
    }
    return out.append(structured.substring(structuredClose)).toString();
  }

  private static String rejectConflictingJvmSurfaces(String stub) {
    if (stub == null) return null;
    final int open = stub.indexOf('{');
    final int close = open < 0 ? -1 : matchingBrace(stub, open);
    if (close <= open) return stub;
    final List<GeneratedMember> members = generatedMembers(
        stub.substring(open + 1, close), generatedTypeVariableErasures(stub.substring(0, open)));
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (GeneratedMember member : members) {
      if (member.typeName == null) counts.merge(member.key, 1, Integer::sum);
    }
    boolean changed = false;
    final StringBuilder body = new StringBuilder();
    for (GeneratedMember member : members) {
      if (member.typeName != null) {
        final String nested = rejectConflictingJvmSurfaces(member.text);
        changed |= !nested.equals(member.text);
        body.append(nested);
      } else if (counts.get(member.key) == 1) {
        body.append(member.text);
      } else {
        // Multiple Kotlin declarations claim one owner/name/parameter JVM surface. There is no
        // deterministic Java declaration or navigation target, so expose none of them.
        changed = true;
      }
    }
    return changed
        ? stub.substring(0, open + 1) + '\n' + body + stub.substring(close)
        : stub;
  }

  private static List<GeneratedMember> generatedMembers(
      String body, Map<String, String> typeVariableErasures) {
    final List<GeneratedMember> result = new ArrayList<>();
    int index = 0;
    while (index < body.length()) {
      while (index < body.length() && Character.isWhitespace(body.charAt(index))) index++;
      if (index >= body.length()) break;
      final int lineEndIndex = body.indexOf('\n', index);
      final int lineEnd = lineEndIndex < 0 ? body.length() : lineEndIndex;
      final String line = body.substring(index, lineEnd).trim();
      final Matcher type = GENERATED_TYPE_DECLARATION_PATTERN.matcher(line);
      if (type.find()) {
        final int open = body.indexOf('{', index);
        if (open >= index && open <= lineEnd) {
          final int close = matchingBrace(body, open);
          if (close > open) {
            final String text = body.substring(index, close + 1).trim() + "\n";
            result.add(new GeneratedMember("T:" + type.group(1), type.group(1), text));
            index = close + 1;
            continue;
          }
        }
      }
      if (!line.isEmpty()) {
        result.add(new GeneratedMember(
            generatedMemberKey(line, typeVariableErasures), null, line + "\n"));
      }
      index = lineEndIndex < 0 ? body.length() : lineEndIndex + 1;
    }
    return result;
  }

  private static Map<String, String> generatedTypeVariableErasures(String header) {
    final Map<String, String> result = new LinkedHashMap<>();
    final int interfaceKeyword = header.lastIndexOf(" interface ");
    final int classKeyword = header.lastIndexOf(" class ");
    final int keyword = Math.max(interfaceKeyword, classKeyword);
    if (keyword < 0) return result;
    int index = keyword + (keyword == interfaceKeyword ? " interface ".length() : " class ".length());
    while (index < header.length() && Character.isJavaIdentifierPart(header.charAt(index))) index++;
    while (index < header.length() && Character.isWhitespace(header.charAt(index))) index++;
    if (index >= header.length() || header.charAt(index) != '<') return result;
    final int open = index;
    final int close = matchingAngleBracket(header, open);
    if (close <= open) return result;
    for (String parameter : splitParameters(header.substring(open + 1, close))) {
      final String trimmed = parameter.trim();
      if (trimmed.isEmpty()) continue;
      final int extendsIndex = trimmed.indexOf(" extends ");
      final String name = (extendsIndex < 0 ? trimmed : trimmed.substring(0, extendsIndex)).trim();
      if (!JAVA_TYPE_NAME_PATTERN.matcher(name).matches()) continue;
      final String bound = extendsIndex < 0 ? "Object"
          : trimmed.substring(extendsIndex + " extends ".length()).split("\\s*&\\s*", 2)[0].trim();
      result.put(name, erasedJvmParameterType(bound, result));
    }
    return result;
  }

  private static Map<String, String> generatedMethodTypeVariableErasures(
      String declarationPrefix, Map<String, String> outerErasures) {
    final Map<String, String> result = new LinkedHashMap<>();
    final Matcher typeParameters = Pattern.compile("\\bpublic\\s+(?:static\\s+)?<").matcher(declarationPrefix);
    if (!typeParameters.find()) return result;
    final int open = typeParameters.end() - 1;
    final int close = matchingAngleBracket(declarationPrefix, open);
    if (open < 0 || close <= open) return result;
    for (String parameter : splitParameters(declarationPrefix.substring(open + 1, close))) {
      final String trimmed = parameter.trim();
      final int extendsIndex = trimmed.indexOf(" extends ");
      final String name = (extendsIndex < 0 ? trimmed : trimmed.substring(0, extendsIndex)).trim();
      if (!JAVA_TYPE_NAME_PATTERN.matcher(name).matches()) continue;
      final String bound = extendsIndex < 0 ? "Object"
          : trimmed.substring(extendsIndex + " extends ".length()).split("\\s*&\\s*", 2)[0].trim();
      result.put(name, erasedJvmParameterType(bound, outerErasures));
    }
    return result;
  }

  private static String generatedMemberKey(
      String declaration, Map<String, String> typeVariableErasures) {
    final int open = declaration.indexOf('(');
    final int close = open < 0 ? -1 : declaration.lastIndexOf(')');
    if (open >= 0 && close > open) {
      final String before = declaration.substring(0, open).trim();
      final Matcher name = Pattern.compile("([A-Za-z_$][\\w$]*)$").matcher(before);
      if (name.find()) {
        final Map<String, String> erasures = new LinkedHashMap<>(typeVariableErasures);
        erasures.putAll(generatedMethodTypeVariableErasures(before, erasures));
        final List<String> parameterTypes = new ArrayList<>();
        for (String parameter : splitParameters(declaration.substring(open + 1, close))) {
          final String normalized = parameter.trim().replaceAll(
              "\\s+[A-Za-z_$][\\w$]*$", "").replaceAll("\\s+", " ");
          parameterTypes.add(erasedJvmParameterType(
              normalized.endsWith("...")
                  ? normalized.substring(0, normalized.length() - 3).trim() + "[]"
                  : normalized,
              erasures));
        }
        return "M:" + name.group(1) + "(" + String.join(",", parameterTypes) + ")";
      }
    }
    final String field = declaration.replaceFirst("\\s*=.*$", "")
        .replaceFirst(";\\s*$", "").trim();
    final Matcher name = Pattern.compile("([A-Za-z_$][\\w$]*)$").matcher(field);
    return name.find() ? "F:" + name.group(1) : "U:" + declaration;
  }

  private static String erasedJvmParameterType(
      String javaType, Map<String, String> typeVariableErasures) {
    if (javaType == null || javaType.trim().isEmpty()) return "Object";
    String type = javaType.trim();
    final StringBuilder arraySuffix = new StringBuilder();
    while (type.endsWith("[]")) {
      arraySuffix.append("[]");
      type = type.substring(0, type.length() - 2).trim();
    }
    final int genericStart = type.indexOf('<');
    final String raw = genericStart < 0 ? type : type.substring(0, genericStart).trim();
    final String erased = typeVariableErasures.get(raw);
    return (erased == null ? raw : erased) + arraySuffix;
  }

  private static final class GeneratedMember {
    final String key;
    final String typeName;
    String text;

    GeneratedMember(String key, String typeName, String text) {
      this.key = key;
      this.typeName = typeName;
      this.text = text;
    }
  }

  private static String generateTypeFallbackIfPresent(
      String packageName, String simpleName, String source) {
    final Matcher typeMatcher = TYPE_PATTERN.matcher(source);
    while (typeMatcher.find()) {
      if (simpleName.equals(typeMatcher.group(3)) && !isPrivate(typeMatcher.group(1))) {
        return generateTypeFallback(packageName, simpleName, typeMatcher, source);
      }
    }
    return null;
  }

  private static String generateTypeFallback(
      String packageName, String simpleName, Matcher declaration, String source) {
    final String keyword = declaration.group(2);
    final boolean isObject = "object".equals(keyword);
    final boolean isInterface = "interface".equals(keyword);
    final boolean isEnum = keyword.startsWith("enum");
    final boolean isAnnotation = keyword.startsWith("annotation");
    final boolean isValue = containsModifier(declaration.group(1), "value");
    final StringBuilder out = header(packageName);
    if (isEnum) {
      out.append("public enum ").append(simpleName).append(" { ; }\n");
      return out.toString();
    }
    if (isAnnotation) {
      out.append("public @interface ").append(simpleName).append(" {}\n");
      return out.toString();
    }
    if (isValue) {
      appendFallbackValueClass(out, simpleName, source, declaration.end(3), false);
      return out.toString();
    }
    final List<KotlinJvmSyntaxParser.TypeParameterSyntax> typeParameters =
        typeParametersFallback(source, declaration.end(3));
    final List<KotlinJvmSyntaxParser.SuperTypeSyntax> superTypes =
        superTypesFallback(source, declaration.end(3));
    registerTypeVariables(typeParameters);
    out.append("public ");
    if (isInterface) {
      out.append("interface ");
    } else {
      out.append("class ");
    }
    out.append(simpleName).append(javaTypeParameters(typeParameters).trim())
        .append(javaInheritanceClause(superTypes, isInterface)).append(" {\n");
    final String constructor =
        isObject || isInterface ? null : primaryConstructorText(source, declaration.end(3));
    int bodySearchStart = declaration.end();
    if (constructor != null) {
      final int constructorStart = source.indexOf(constructor, declaration.end(3));
      if (constructorStart >= 0) {
        bodySearchStart = constructorStart + constructor.length();
      }
    }
    final int bodyStart = typeBodyStart(source, bodySearchStart);
    final int bodyEnd = bodyStart < 0 ? -1 : matchingBrace(source, bodyStart);
    final String body = bodyEnd > bodyStart ? source.substring(bodyStart + 1, bodyEnd) : "";
    final boolean hasSecondaryConstructors = hasSecondaryConstructorsFallback(body);
    final boolean hasNoArgSecondaryConstructor = hasNoArgSecondaryConstructorFallback(body);

    if (isObject) {
      out.append("  public static final ").append(simpleName).append(" INSTANCE = null;\n");
    } else if (!isInterface) {
      appendFallbackConstructors(
          out,
          simpleName,
          constructor,
          primaryConstructorVisibilityFallback(source, declaration.end(3), constructor),
          primaryConstructorJvmOverloadsFallback(source, declaration.end(3), constructor),
          hasSecondaryConstructors,
          hasNoArgSecondaryConstructor);
      appendConstructorProperties(out, constructor);
    }

    if (!body.isEmpty()) {
      appendMembers(out, body, isInterface, false);
      if (!isInterface && !isObject) {
        appendSecondaryConstructorsFallback(out, simpleName, body, constructor);
      }
      appendCompanionMembers(out, body);
      appendNestedTypesFallback(out, body);
    }
    out.append("}\n");
    return out.toString();
  }

  private static void appendNestedTypesFallback(StringBuilder out, String body) {
    final Matcher declaration = TYPE_PATTERN.matcher(body);
    while (declaration.find()) {
      if (braceDepthAt(body, declaration.start()) != 0
          || isPrivate(declaration.group(1))) {
        continue;
      }
      final String keyword = declaration.group(2);
      final String name = declaration.group(3);
      final boolean innerType = containsModifier(declaration.group(1), "inner");
      final boolean valueType = containsModifier(declaration.group(1), "value");
      final boolean objectType = "object".equals(keyword);
      final boolean interfaceType = "interface".equals(keyword);
      if (keyword.startsWith("enum")) {
        out.append("  public enum ").append(name).append(" { ; }\n");
        continue;
      }
      if (keyword.startsWith("annotation")) {
        out.append("  public @interface ").append(name).append(" {}\n");
        continue;
      }
      if (valueType) {
        appendFallbackValueClass(out, name, body, declaration.end(3), true);
        continue;
      }
      final List<KotlinJvmSyntaxParser.TypeParameterSyntax> typeParameters =
          typeParametersFallback(body, declaration.end(3));
      final TypeResolutionContext context = TYPE_CONTEXT.get();
      final Set<String> outerVariables = context == null
          ? java.util.Collections.emptySet()
          : new LinkedHashSet<>(context.typeVariables);
      if (context != null && !innerType) {
        context.typeVariables.clear();
      }
      registerTypeVariables(typeParameters);
      try {
        out.append("  public ");
        if (!innerType) {
          out.append("static ");
        }
        out.append(interfaceType ? "interface " : "class ")
            .append(name).append(javaTypeParameters(typeParameters).trim())
            .append(javaInheritanceClause(
                superTypesFallback(body, declaration.end(3)), interfaceType))
            .append(" {\n");
        final String constructor = objectType || interfaceType
            ? null : primaryConstructorText(body, declaration.end(3));
        int bodySearchStart = declaration.end();
        if (constructor != null) {
          final int constructorStart = body.indexOf(constructor, declaration.end(3));
          if (constructorStart >= 0) bodySearchStart = constructorStart + constructor.length();
        }
        final int nestedBodyStart = typeBodyStart(body, bodySearchStart);
        final int nestedBodyEnd = nestedBodyStart < 0 ? -1 : matchingBrace(body, nestedBodyStart);
        final String nestedBody = nestedBodyEnd > nestedBodyStart
            ? body.substring(nestedBodyStart + 1, nestedBodyEnd) : "";
        if (objectType) {
          out.append("  public static final ").append(name).append(" INSTANCE = null;\n");
        } else if (!interfaceType) {
          appendFallbackConstructors(
              out, name, constructor,
              primaryConstructorVisibilityFallback(body, declaration.end(3), constructor),
              primaryConstructorJvmOverloadsFallback(body, declaration.end(3), constructor),
              hasSecondaryConstructorsFallback(nestedBody),
              hasNoArgSecondaryConstructorFallback(nestedBody), false);
          appendConstructorProperties(out, constructor);
        }
        if (!nestedBody.isEmpty()) {
          appendMembers(out, nestedBody, interfaceType, false);
          if (!interfaceType && !objectType) {
            appendSecondaryConstructorsFallback(out, name, nestedBody, constructor);
          }
          appendCompanionMembers(out, nestedBody);
          appendNestedTypesFallback(out, nestedBody);
        }
        out.append("  }\n");
      } finally {
        if (context != null) {
          context.typeVariables.clear();
          context.typeVariables.addAll(outerVariables);
        }
      }
    }
  }

  private static void appendFallbackValueClass(
      StringBuilder out, String name, String source, int declarationNameEnd, boolean nested) {
    out.append(nested ? "  public static final class " : "public final class ")
        .append(name).append(" {\n");
    final String constructor = primaryConstructorText(source, declarationNameEnd);
    final List<String> parameters = constructor == null || constructor.length() < 2
        ? java.util.Collections.emptyList()
        : splitParameters(constructor.substring(1, constructor.length() - 1));
    if (parameters.size() == 1) {
      final String raw = parameters.get(0).trim()
          .replaceFirst("^(?:(?:public|protected|internal|private|val|var|crossinline|noinline|vararg)\\s+)+", "");
      final int colon = topLevelIndexOf(raw, ':');
      final String parameterName = colon < 0 ? "value" : raw.substring(0, colon).trim();
      final String kotlinType = colon < 0 ? null : raw.substring(colon + 1)
          .replaceFirst("\\s*=.*$", "").trim();
      out.append("  private ").append(name).append('(').append(javaType(kotlinType)).append(' ')
          .append(safeName(parameterName, 0)).append(") {}\n");
    } else {
      out.append("  private ").append(name).append("() {}\n");
    }
    out.append(nested ? "  }\n" : "}\n");
  }

  private static boolean containsModifier(String modifiers, String modifier) {
    return modifiers != null
        && Pattern.compile("(?:^|\\s)" + Pattern.quote(modifier) + "(?:\\s|$)")
            .matcher(modifiers).find();
  }

  private static int braceDepthAt(String source, int end) {
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    boolean lineComment = false;
    boolean blockComment = false;
    for (int index = 0; index < end; index++) {
      final char current = source.charAt(index);
      final char next = index + 1 < end ? source.charAt(index + 1) : '\0';
      if (lineComment) {
        if (current == '\n') lineComment = false;
        continue;
      }
      if (blockComment) {
        if (current == '*' && next == '/') {
          blockComment = false;
          index++;
        }
        continue;
      }
      if (quoted) {
        if (current == '"' && !escaped) quoted = false;
        escaped = current == '\\' && !escaped;
        if (current != '\\') escaped = false;
        continue;
      }
      if (current == '/' && next == '/') {
        lineComment = true;
        index++;
      } else if (current == '/' && next == '*') {
        blockComment = true;
        index++;
      } else if (current == '"') {
        quoted = true;
      } else if (current == '{') {
        depth++;
      } else if (current == '}') {
        depth = Math.max(0, depth - 1);
      }
    }
    return depth;
  }

  private static int typeBodyStart(String source, int headerEnd) {
    final int lineEnd = source.indexOf('\n', headerEnd);
    final int declarationLineEnd = lineEnd < 0 ? source.length() : lineEnd;
    final int sameLineBrace = source.indexOf('{', headerEnd);
    if (sameLineBrace >= 0 && sameLineBrace < declarationLineEnd) {
      return sameLineBrace;
    }
    int index = declarationLineEnd;
    while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
      index++;
    }
    return index < source.length() && source.charAt(index) == '{' ? index : -1;
  }

  private static String generateFacade(String packageName, String simpleName, String source) {
    final List<KotlinJvmSyntaxParser.MemberSyntax> members =
        structuredGenerationEnabled()
            ? KotlinJvmSyntaxParser.findTopLevelMembers(source)
            : null;
    if (members != null) {
      final StringBuilder structured = facadeHeader(packageName, simpleName);
      appendSyntaxMembers(structured, members, false, true);
      structured.append("}\n");
      if (generationMode() != GenerationMode.AUTO) {
        return rejectConflictingJvmSurfaces(structured.toString());
      }
      final StringBuilder fallback = facadeHeader(packageName, simpleName);
      appendMembers(fallback, source, false, true);
      fallback.append("}\n");
      return rejectConflictingJvmSurfaces(
          mergeGeneratedStubs(structured.toString(), fallback.toString()));
    }
    if (!fallbackGenerationEnabled()) {
      return null;
    }
    final StringBuilder fallback = facadeHeader(packageName, simpleName);
    appendMembers(fallback, source, false, true);
    fallback.append("}\n");
    return rejectConflictingJvmSurfaces(fallback.toString());
  }

  private static StringBuilder facadeHeader(String packageName, String simpleName) {
    return header(packageName)
        .append("public final class ").append(simpleName).append(" {\n")
        .append("  private ").append(simpleName).append("() {}\n");
  }

  private static void appendCompanionMembers(StringBuilder out, String body) {
    // Anchor the declaration at the beginning of a source line so examples such as
    // `// companion object {` do not shadow the real companion declared later in the class.
    final Matcher companion =
        Pattern.compile("(?m)^\\s*companion\\s+object(?:\\s+([A-Za-z_][\\w]*))?\\s*\\{")
            .matcher(body);
    if (!companion.find()) {
      return;
    }
    final int bodyStart = companion.end() - 1;
    final int bodyEnd = matchingBrace(body, bodyStart);
    if (bodyEnd <= bodyStart) {
      return;
    }
    final String companionBody = body.substring(bodyStart + 1, bodyEnd);
    appendCompanionBody(out, companion.group(1), companionBody);
  }

  private static void appendCompanionBody(
      StringBuilder out, String companionName, String companionBody) {
    final String jvmName = companionName == null || companionName.isEmpty() ? "Companion" : companionName;
    // Kotlin exposes the companion object using its source name, or Companion when unnamed. Keep
    // that normal JVM surface in addition to host-class methods created only for @JvmStatic.
    out.append("  public static final class ").append(jvmName).append(" {\n");
    // @JvmField exposes a backing field on the host class, not Companion accessors. Remove its
    // declaration from this fallback companion pass; appendJvmFields emits the host field below.
    appendMembers(
        out, withoutJvmFieldProperties(companionBody).replace("@JvmStatic", ""), false, false);
    out.append("  }\n");
    out.append("  public static final ").append(jvmName).append(' ')
        .append(jvmName).append(" = null;\n");
    // Do not depend on annotations and declarations sharing a particular line layout. Kotlin permits
    // @JvmStatic, its use-site arguments, whitespace and the declaration to span separate lines.
    final Matcher staticFunction = JVM_STATIC_FUNCTION_PATTERN.matcher(companionBody);
    while (staticFunction.find()) {
      if (!isPrivate(staticFunction.group(1))) {
        appendStaticFunction(out, staticFunction);
      }
    }
    final Matcher staticProperty = JVM_STATIC_PROPERTY_PATTERN.matcher(companionBody);
    while (staticProperty.find()) {
      if (!isPrivate(staticProperty.group(1))) {
        appendStaticProperty(out, staticProperty);
      }
    }
    appendJvmFields(out, companionBody);
  }

  private static void appendDefaultInterfaceForwarders(
      StringBuilder out,
      KotlinJvmSyntaxParser.TypeSyntax implementation,
      List<KotlinJvmSyntaxParser.TypeSyntax> sourceTypes) {
    if (sourceTypes == null) {
      return;
    }
    final Set<String> visitedContracts = new LinkedHashSet<>();
    final List<KotlinJvmSyntaxParser.MemberSyntax> projected = new ArrayList<>(implementation.members);
    for (KotlinJvmSyntaxParser.SuperTypeSyntax superType : implementation.superTypes) {
      appendDefaultInterfaceForwarders(
          out, implementation, superType, sourceTypes, visitedContracts, projected,
          java.util.Collections.<String, String>emptyMap());
    }
  }

  private static void appendDefaultInterfaceForwarders(
      StringBuilder out,
      KotlinJvmSyntaxParser.TypeSyntax implementation,
      KotlinJvmSyntaxParser.SuperTypeSyntax superType,
      List<KotlinJvmSyntaxParser.TypeSyntax> sourceTypes,
      Set<String> visitedContracts,
      List<KotlinJvmSyntaxParser.MemberSyntax> projected,
      Map<String, String> substitutions) {
    // Same-file interface chains only. Type arguments must be simple names after the already
    // established substitutions; nested/star projections and external contracts remain omitted.
    if (superType.constructorInvocation) {
      return;
    }
    final KotlinJvmTypeProjection.TypeApplication application =
        KotlinJvmTypeProjection.parseTypeApplication(superType.type);
    final String contractName = application == null ? superType.type.trim() : application.rawType;
    if (!contractName.matches("[A-Za-z_$][\\w$]*") || !visitedContracts.add(superType.type)) {
      return;
    }
    KotlinJvmSyntaxParser.TypeSyntax contract = null;
    for (KotlinJvmSyntaxParser.TypeSyntax candidate : sourceTypes) {
      if (candidate.interfaceType && contractName.equals(candidate.name)) {
        contract = candidate;
        break;
      }
    }
    if (contract == null || (application != null
        && application.arguments.size() != contract.typeParameters.size())) {
      return;
    }
    final Map<String, String> contractSubstitutions = new LinkedHashMap<>(substitutions);
    if (application != null) {
      for (int index = 0; index < application.arguments.size(); index++) {
        final String argument = substituteType(application.arguments.get(index), substitutions);
        if (!argument.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")) {
          return;
        }
        contractSubstitutions.put(contract.typeParameters.get(index).name, argument);
      }
    }
    for (KotlinJvmSyntaxParser.MemberSyntax original : contract.members) {
      final KotlinJvmSyntaxParser.MemberSyntax member = substituteMember(original, contractSubstitutions);
      if (!defaultInterfaceMember(member) || declaredBy(projected, member)) {
        continue;
      }
      if (member.function()) {
        appendSyntaxFunction(out, member, false, false);
      } else {
        appendSyntaxProperty(out, member, false, false);
      }
      projected.add(member);
    }
    for (KotlinJvmSyntaxParser.SuperTypeSyntax parent : contract.superTypes) {
      appendDefaultInterfaceForwarders(
          out, implementation, parent, sourceTypes, visitedContracts, projected,
          contractSubstitutions);
    }
  }

  private static String substituteType(String type, Map<String, String> substitutions) {
    if (type == null || substitutions.isEmpty()) {
      return type;
    }
    String result = type;
    for (Map.Entry<String, String> entry : substitutions.entrySet()) {
      result = result.replaceAll(
          "\\b" + Pattern.quote(entry.getKey()) + "\\b",
          Matcher.quoteReplacement(entry.getValue()));
    }
    return result;
  }

  private static KotlinJvmSyntaxParser.MemberSyntax substituteMember(
      KotlinJvmSyntaxParser.MemberSyntax member, Map<String, String> substitutions) {
    if (substitutions.isEmpty()) {
      return member;
    }
    final List<KotlinJvmSyntaxParser.ParameterSyntax> parameters = new ArrayList<>();
    for (KotlinJvmSyntaxParser.ParameterSyntax parameter : member.parameterList) {
      parameters.add(new KotlinJvmSyntaxParser.ParameterSyntax(
          parameter.name, substituteType(parameter.type, substitutions),
          parameter.defaultValue, parameter.vararg));
    }
    return new KotlinJvmSyntaxParser.MemberSyntax(
        member.kind, member.declarationText, member.privateMember, member.jvmStatic, member.jvmField,
        member.jvmOverloads, member.suspendFunction, member.jvmSynthetic, member.getterJvmSynthetic,
        member.setterJvmSynthetic, member.jvmName, member.getterJvmName, member.setterJvmName,
        member.name, member.nameOffset, member.nameLength, member.parameters, parameters,
        member.typeParameters, substituteType(member.receiverType, substitutions),
        substituteType(member.declaredType, substitutions), member.mutableProperty,
        member.readOnlyProperty, member.functionBodyPresent);
  }

  private static boolean defaultInterfaceMember(KotlinJvmSyntaxParser.MemberSyntax member) {
    return !member.privateMember && !member.jvmSynthetic && !member.suspendFunction
        && member.functionBodyPresent;
  }

  private static boolean declaredBy(
      List<KotlinJvmSyntaxParser.MemberSyntax> declared,
      KotlinJvmSyntaxParser.MemberSyntax inherited) {
    for (KotlinJvmSyntaxParser.MemberSyntax member : declared) {
      if (member.function() != inherited.function()) {
        continue;
      }
      if (!inherited.function()) {
        if (inherited.name != null && inherited.name.equals(member.name)) {
          return true;
        }
        continue;
      }
      final String inheritedName = inherited.jvmName == null ? inherited.name : inherited.jvmName;
      final String memberName = member.jvmName == null ? member.name : member.jvmName;
      if (inheritedName == null || !inheritedName.equals(memberName)
          || inherited.parameterList.size() != member.parameterList.size()) {
        continue;
      }
      boolean sameParameters = true;
      for (int index = 0; index < inherited.parameterList.size(); index++) {
        if (!javaAbiType(inherited.parameterList.get(index).type)
            .equals(javaAbiType(member.parameterList.get(index).type))) {
          sameParameters = false;
          break;
        }
      }
      if (sameParameters) {
        return true;
      }
    }
    return false;
  }

  private static void appendSyntaxMembers(
      StringBuilder out,
      List<KotlinJvmSyntaxParser.MemberSyntax> members,
      boolean interfaceType,
      boolean topLevel) {
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (member.privateMember || member.declarationText.isEmpty() || member.jvmSynthetic
          || member.function() && member.suspendFunction) {
        continue;
      }
      if (member.function()) {
        appendSyntaxFunction(out, member, interfaceType, topLevel);
        if (member.jvmOverloads) {
          appendSyntaxFunctionOverloads(out, member, interfaceType, topLevel);
        }
      } else if (member.jvmField) {
        appendSyntaxField(out, member, topLevel);
      } else {
        appendSyntaxProperty(out, member, interfaceType, topLevel);
      }
    }
  }

  private static void appendCompanionSyntax(
      StringBuilder out,
      String companionName,
      List<KotlinJvmSyntaxParser.MemberSyntax> members) {
    final String jvmName = companionName == null || companionName.isEmpty() ? "Companion" : companionName;
    out.append("  public static final class ").append(jvmName).append(" {\n");
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      // @JvmField has only the host-class field projection; do not invent Companion accessors.
      if (!member.function() && member.jvmField) continue;
      appendSyntaxMembers(out, Collections.singletonList(member), false, false);
    }
    out.append("  }\n");
    out.append("  public static final ").append(jvmName).append(' ')
        .append(jvmName).append(" = null;\n");
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (member.privateMember || member.declarationText.isEmpty() || member.jvmSynthetic
          || member.function() && member.suspendFunction) {
        continue;
      }
      if (member.function() && member.jvmStatic) {
        appendSyntaxFunction(out, member, false, true);
        if (member.jvmOverloads) {
          appendSyntaxFunctionOverloads(out, member, false, true);
        }
      } else if (!member.function()) {
        if (member.jvmField) {
          appendSyntaxField(out, member, true);
        } else if (member.jvmStatic) {
          appendSyntaxProperty(out, member, false, true);
        }
      }
    }
  }

  private static void appendSyntaxFunction(
      StringBuilder out,
      KotlinJvmSyntaxParser.MemberSyntax function,
      boolean interfaceType,
      boolean topLevel) {
    if (function.name == null || function.name.isEmpty()
        || function.receiverType != null && !topLevel
        || !canProjectValueClassFunction(
            function.jvmName, function.receiverType, function.parameterList, function.declaredType)) {
      return;
    }
    final Set<String> methodVariables = registerTypeVariables(function.typeParameters);
    try {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }

      out.append(javaTypeParameters(function.typeParameters));
      final List<String> parameters = new ArrayList<>();
      if (function.receiverType != null) {
        parameters.add(javaAbiType(function.receiverType) + " receiver");
      }
      for (int index = 0; index < function.parameterList.size(); index++) {
        final KotlinJvmSyntaxParser.ParameterSyntax parameter = function.parameterList.get(index);
        parameters.add(javaAbiSyntaxParameter(
            parameter, index, index == function.parameterList.size() - 1));
      }
      final String returnType = functionReturnType(
          function.declaredType, interfaceType, topLevel, function.functionBodyPresent);
      out.append(javaAbiType(returnType)).append(' ')
          .append(function.jvmName == null ? function.name : function.jvmName)
          .append('(').append(String.join(", ", parameters)).append(')')
          .append(interfaceType && !topLevel ? ";\n" : methodBody(returnType));
    } finally {
      unregisterTypeVariables(methodVariables);
    }
  }

  private static void appendSyntaxFunctionOverloads(
      StringBuilder out,
      KotlinJvmSyntaxParser.MemberSyntax function,
      boolean interfaceType,
      boolean topLevel) {
    if (function.name == null || function.receiverType != null
        || !canProjectValueClassFunction(
            function.jvmName, function.receiverType, function.parameterList, function.declaredType)) {
      return;
    }
    final List<KotlinJvmSyntaxParser.ParameterSyntax> parameters = function.parameterList;
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && parameters.get(index).defaultValue; index--) {
      firstOmittable = index;
    }
    if (firstOmittable == parameters.size()) {
      return;
    }
    final Set<String> methodVariables = registerTypeVariables(function.typeParameters);
    try {
      for (int count = parameters.size() - 1; count >= firstOmittable; count--) {
        out.append("  public ");
        if (topLevel) {
          out.append("static ");
        }
        final String returnType = functionReturnType(
            function.declaredType, interfaceType, topLevel, function.functionBodyPresent);
        out.append(javaTypeParameters(function.typeParameters))
            .append(javaAbiType(returnType)).append(' ')
            .append(function.jvmName == null ? function.name : function.jvmName)
            .append(javaAbiSyntaxParameterList(
                parameters.subList(0, count), count == parameters.size()))
            .append(interfaceType && !topLevel ? ";\n" : methodBody(returnType));
      }
    } finally {
      unregisterTypeVariables(methodVariables);
    }
  }

  private static void appendSyntaxProperty(
      StringBuilder out,
      KotlinJvmSyntaxParser.MemberSyntax property,
      boolean interfaceType,
      boolean topLevel) {
    if (property.name == null || property.receiverType != null && !topLevel && !interfaceType) {
      return;
    }
    final boolean extensionProperty = property.receiverType != null;
    // Kotlin 2.1.0 rejects @get/@set:JvmName on interface member extension properties. Do not
    // project an ABI surface for a source declaration the compiler itself will not accept.
    if (interfaceType && extensionProperty
        && (property.getterJvmName != null || property.setterJvmName != null)) {
      return;
    }
    final boolean getterProjectable = extensionProperty
        ? canProjectValueClassExtensionGetter(
            property.getterJvmName, property.receiverType, property.declaredType, interfaceType)
        : canProjectValueClassProperty(
            property.getterJvmName, property.setterJvmName, property.mutableProperty, property.declaredType);
    final boolean setterProjectable = !property.mutableProperty ? false : extensionProperty
        ? canProjectValueClassExtensionSetter(
            property.setterJvmName, property.receiverType, property.declaredType)
        : canProjectValueClassProperty(
            property.getterJvmName, property.setterJvmName, true, property.declaredType);
    if (!getterProjectable && !setterProjectable) {
      return;
    }
    final String type = javaAbiType(property.declaredType);
    final String getter = property.getterJvmName == null
        ? propertyGetterName(property.name, property.declaredType)
        : property.getterJvmName;
    final String setter = property.setterJvmName == null
        ? propertySetterName(property.name, property.declaredType)
        : property.setterJvmName;
    if (getterProjectable && !property.getterJvmSynthetic) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append(type).append(' ').append(getter).append('(');
      if (property.receiverType != null) {
        out.append(javaAbiType(property.receiverType)).append(" receiver");
      }
      out.append(')').append(interfaceType && !topLevel
          ? ";\n"
          : " { return " + defaultValue(property.declaredType) + "; }\n");
    }
    if (setterProjectable && !property.setterJvmSynthetic) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append("void ").append(setter).append('(');
      if (property.receiverType != null) {
        out.append(javaAbiType(property.receiverType)).append(" receiver, ");
      }
      out.append(type).append(" value)")
          .append(interfaceType && !topLevel ? ";\n" : " {}\n");
    }
  }

  private static void appendSyntaxField(
      StringBuilder out, KotlinJvmSyntaxParser.MemberSyntax property, boolean staticField) {
    if (property.name != null && property.receiverType == null) {
      out.append("  public ");
      if (staticField) {
        out.append("static ");
      }
      out.append(javaType(property.declaredType)).append(' ')
          .append(property.name).append(";\n");
    }
  }

  private static void appendExtensionFunction(
      StringBuilder out, Matcher extension, boolean interfaceType, boolean topLevel) {
    appendExtensionFunction(out, extension, interfaceType, topLevel, null);
  }

  private static void appendExtensionFunction(
      StringBuilder out, Matcher extension, boolean interfaceType, boolean topLevel,
      String jvmName) {
    if (isSuspendModifier(extension.group(1))) {
      return;
    }
    final List<String> rawParameters = splitParameters(extension.group(4));
    if (!canProjectFallbackValueClassFunction(jvmName, extension.group(2), rawParameters, extension.group(5))) {
      return;
    }
    out.append("  public ");
    if (topLevel) {
      out.append("static ");
    }
    final List<String> parameters = new ArrayList<>();
    parameters.add(javaAbiType(extension.group(2)) + " receiver");
    final String ordinaryParameters = javaAbiParameterList(rawParameters);
    if (ordinaryParameters.length() > 2) {
      parameters.add(ordinaryParameters.substring(1, ordinaryParameters.length() - 1));
    }
    final String returnType = functionReturnType(
        extension.group(5), interfaceType, topLevel, fallbackFunctionBodyPresent(extension.group()));
    out.append(javaAbiType(returnType)).append(' ')
        .append(jvmName == null ? extension.group(3) : jvmName)
        .append("(").append(String.join(", ", parameters)).append(")");
    out.append(interfaceType && !topLevel ? ";\n" : methodBody(returnType));
  }

  private static void appendFunction(
      StringBuilder out,
      Matcher function,
      boolean interfaceType,
      boolean topLevel,
      List<KotlinJvmSyntaxParser.TypeParameterSyntax> typeParameters) {
    appendFunction(out, function, interfaceType, topLevel, typeParameters, null);
  }

  private static void appendFunction(
      StringBuilder out,
      Matcher function,
      boolean interfaceType,
      boolean topLevel,
      List<KotlinJvmSyntaxParser.TypeParameterSyntax> typeParameters,
      String jvmName) {
    if (isSuspendModifier(function.group(1))) {
      return;
    }
    final List<String> parameters = splitParameters(function.group(3));
    if (!canProjectFallbackValueClassFunction(jvmName, null, parameters, function.group(4))) {
      return;
    }
    final Set<String> methodVariables = registerTypeVariables(typeParameters);
    try {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      final String returnType = functionReturnType(
          function.group(4), interfaceType, topLevel, fallbackFunctionBodyPresent(function.group()));
      out.append(javaTypeParameters(typeParameters))
          .append(javaAbiType(returnType)).append(' ')
          .append(jvmName == null ? function.group(2) : jvmName)
          .append(javaAbiParameterList(parameters));
      out.append(interfaceType && !topLevel ? ";\n" : methodBody(returnType));
    } finally {
      unregisterTypeVariables(methodVariables);
    }
  }

  private static void appendFunctionOverloads(
      StringBuilder out,
      Matcher function,
      boolean interfaceType,
      boolean topLevel,
      List<KotlinJvmSyntaxParser.TypeParameterSyntax> typeParameters) {
    appendFunctionOverloads(out, function, interfaceType, topLevel, typeParameters, null);
  }

  private static void appendFunctionOverloads(
      StringBuilder out,
      Matcher function,
      boolean interfaceType,
      boolean topLevel,
      List<KotlinJvmSyntaxParser.TypeParameterSyntax> typeParameters,
      String jvmName) {
    if (isSuspendModifier(function.group(1))) {
      return;
    }
    final List<String> parameters = splitParameters(function.group(3));
    if (!canProjectFallbackValueClassFunction(jvmName, null, parameters, function.group(4))) {
      return;
    }
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && hasDefaultValue(parameters.get(index));
        index--) {
      firstOmittable = index;
    }
    // Kotlin only creates overloads by dropping a contiguous trailing suffix of default-valued
    // parameters. A default value before a required parameter does not create a Java overload.
    if (firstOmittable == parameters.size()) {
      return;
    }
    final Set<String> methodVariables = registerTypeVariables(typeParameters);
    try {
      for (int count = parameters.size() - 1; count >= firstOmittable; count--) {
        out.append("  public ");
        if (topLevel) {
          out.append("static ");
        }
        final String returnType = functionReturnType(
            function.group(4), interfaceType, topLevel, fallbackFunctionBodyPresent(function.group()));
        out.append(javaTypeParameters(typeParameters))
            .append(javaAbiType(returnType)).append(' ')
            .append(jvmName == null ? function.group(2) : jvmName)
            .append(javaAbiParameterList(parameters.subList(0, count), false));
        out.append(interfaceType && !topLevel ? ";\n" : methodBody(returnType));
      }
    } finally {
      unregisterTypeVariables(methodVariables);
    }
  }

  private static void appendStaticFunction(StringBuilder out, Matcher function) {
    if (isSuspendModifier(function.group(1))) {
      return;
    }
    final Matcher jvmNameMatcher = Pattern.compile(
        "@JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)").matcher(function.group());
    final String jvmName = jvmNameMatcher.find() ? jvmNameMatcher.group(1) : null;
    final List<String> parameters = splitParameters(function.group(3));
    if (!canProjectFallbackValueClassFunction(jvmName, null, parameters, function.group(4))) {
      return;
    }
    final String name = jvmName == null ? function.group(2) : jvmName;
    out.append("  public static ").append(javaAbiType(function.group(4))).append(' ')
        .append(name).append(javaAbiParameterList(parameters))
        .append(methodBody(function.group(4)));
  }

  private static String withoutJvmFieldProperties(String companionBody) {
    if (companionBody == null || companionBody.isEmpty()) return companionBody;
    final Matcher field = JVM_FIELD_PROPERTY_PATTERN.matcher(companionBody);
    final StringBuffer filtered = new StringBuffer();
    while (field.find()) field.appendReplacement(filtered, "");
    field.appendTail(filtered);
    return filtered.toString();
  }

  private static void appendJvmFields(StringBuilder out, String companionBody) {
    final Matcher field = JVM_FIELD_PROPERTY_PATTERN.matcher(companionBody);
    while (field.find()) {
      if (!isPrivate(field.group(1))) {
        appendStaticField(out, field);
      }
    }
  }

  private static void appendStaticField(StringBuilder out, Matcher property) {
    appendField(out, property, true);
  }

  private static void appendField(StringBuilder out, Matcher property, boolean staticField) {
    out.append("  public ");
    if (staticField) {
      out.append("static ");
    }
    out.append(javaType(property.group(4))).append(' ')
        .append(property.group(3)).append(";\n");
  }

  private static void appendProperty(
      StringBuilder out, Matcher property, boolean interfaceType, boolean topLevel) {
    appendProperty(out, property, interfaceType, topLevel, null, null, false, false);
  }

  private static void appendProperty(
      StringBuilder out, Matcher property, boolean interfaceType, boolean topLevel,
      String getterJvmName, String setterJvmName) {
    appendProperty(out, property, interfaceType, topLevel, getterJvmName, setterJvmName, false, false);
  }

  private static void appendProperty(
      StringBuilder out, Matcher property, boolean interfaceType, boolean topLevel,
      String getterJvmName, String setterJvmName,
      boolean getterJvmSynthetic, boolean setterJvmSynthetic) {
    final boolean mutable = "var".equals(property.group(2));
    if (!canProjectValueClassProperty(getterJvmName, setterJvmName, mutable, property.group(4))) {
      return;
    }
    final String type = javaAbiType(property.group(4));
    final String name = property.group(3);
    final String getter = getterJvmName == null
        ? propertyGetterName(name, property.group(4)) : getterJvmName;
    final String setter = setterJvmName == null
        ? propertySetterName(name, property.group(4)) : setterJvmName;
    if (!getterJvmSynthetic) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append(type).append(' ').append(getter).append("()")
          .append(interfaceType && !topLevel
              ? ";\n"
              : " { return " + defaultValue(property.group(4)) + "; }\n");
    }
    if ("var".equals(property.group(2)) && !setterJvmSynthetic) {
      out.append("  public ");
      if (topLevel) {
        out.append("static ");
      }
      out.append("void ").append(setter).append('(').append(type).append(" value)")
          .append(interfaceType && !topLevel ? ";\n" : " {}\n");
    }
  }

  private static void appendStaticProperty(StringBuilder out, Matcher property) {
    final String name = property.group(3);
    final Matcher getterJvmNameMatcher = Pattern.compile(
        "@get:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)").matcher(property.group());
    final Matcher setterJvmNameMatcher = Pattern.compile(
        "@set:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)").matcher(property.group());
    final String getterJvmName = getterJvmNameMatcher.find() ? getterJvmNameMatcher.group(1) : null;
    final String setterJvmName = setterJvmNameMatcher.find() ? setterJvmNameMatcher.group(1) : null;
    final boolean getterJvmSynthetic = Pattern.compile("@get:JvmSynthetic(?:\\s|\\(|$)")
        .matcher(property.group()).find();
    final boolean setterJvmSynthetic = Pattern.compile("@set:JvmSynthetic(?:\\s|\\(|$)")
        .matcher(property.group()).find();
    final boolean mutable = "var".equals(property.group(2));
    if (!canProjectValueClassProperty(getterJvmName, setterJvmName, mutable, property.group(4))) {
      return;
    }
    final String type = javaAbiType(property.group(4));
    final String getter = getterJvmName == null
        ? propertyGetterName(name, property.group(4)) : getterJvmName;
    final String setter = setterJvmName == null
        ? propertySetterName(name, property.group(4)) : setterJvmName;
    if (!getterJvmSynthetic) {
      out.append("  public static ").append(type).append(' ').append(getter).append("()")
          .append(" { return ").append(defaultValue(property.group(4))).append("; }\n");
    }
    if ("var".equals(property.group(2)) && !setterJvmSynthetic) {
      out.append("  public static void ").append(setter).append('(').append(type)
          .append(" value) {}\n");
    }
  }
  private static String propertyGetterName(String name, String kotlinType) {
    if (isBooleanIsProperty(name, kotlinType)) {
      return name;
    }
    return "get" + propertyAccessorSuffix(name);
  }

  private static String propertySetterName(String name, String kotlinType) {
    if (isBooleanIsProperty(name, kotlinType)) {
      return "set" + name.substring(2);
    }
    return "set" + propertyAccessorSuffix(name);
  }

  private static boolean isBooleanIsProperty(String name, String kotlinType) {
    if (name == null || name.length() < 3 || name.charAt(0) != 'i' || name.charAt(1) != 's'
        || !Character.isUpperCase(name.charAt(2)) || kotlinType == null) {
      return false;
    }
    final String type = stripDefaultValue(kotlinType.trim());
    return "Boolean".equals(type) || "kotlin.Boolean".equals(type);
  }

  private static String propertyAccessorSuffix(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  private static void appendSyntaxConstructors(
      StringBuilder out,
      String simpleName,
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> kotlinParameters,
      boolean primaryConstructorPresent,
      String primaryVisibility,
      boolean primaryJvmOverloads,
      List<KotlinJvmSyntaxParser.ConstructorSyntax> secondaryConstructors) {
    appendSyntaxConstructors(
        out, simpleName, kotlinParameters, primaryConstructorPresent, primaryVisibility,
        primaryJvmOverloads, secondaryConstructors, true);
  }

  private static void appendSyntaxConstructors(
      StringBuilder out,
      String simpleName,
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> kotlinParameters,
      boolean primaryConstructorPresent,
      String primaryVisibility,
      boolean primaryJvmOverloads,
      List<KotlinJvmSyntaxParser.ConstructorSyntax> secondaryConstructors,
      boolean allowSyntheticBridge) {
    final Set<String> emittedParameters = new LinkedHashSet<>();
    final boolean primaryProjectable = canProjectValueClassConstructorParameters(kotlinParameters);
    boolean hasRealNoArgConstructor =
        primaryProjectable && primaryConstructorPresent && kotlinParameters.isEmpty();
    hasRealNoArgConstructor |= primaryProjectable
        && primaryJvmOverloads && allConstructorParametersDefault(kotlinParameters);
    for (KotlinJvmSyntaxParser.ConstructorSyntax constructor : secondaryConstructors) {
      if (!canProjectValueClassSyntaxParameters(constructor.parameters)) {
        continue;
      }
      hasRealNoArgConstructor |= constructor.parameters.isEmpty();
      hasRealNoArgConstructor |=
          constructor.jvmOverloads && allParametersDefault(constructor.parameters);
    }
    if (allowSyntheticBridge
        && !hasRealNoArgConstructor
        && (!kotlinParameters.isEmpty()
            || (!primaryConstructorPresent && !secondaryConstructors.isEmpty()))) {
      appendSyntheticNoArgConstructor(out, simpleName);
      emittedParameters.add("()");
    }
    if (primaryProjectable && (primaryConstructorPresent || secondaryConstructors.isEmpty())) {
      final String primaryParameters = javaConstructorParameterList(kotlinParameters);
      out.append("  ").append(javaConstructorVisibility(primaryVisibility)).append(' ')
          .append(simpleName).append(primaryParameters).append(" {}\n");
      emittedParameters.add(primaryConstructorSignature(kotlinParameters));
      if (primaryJvmOverloads) {
        appendPrimaryConstructorOverloads(
            out, simpleName, kotlinParameters, primaryVisibility, emittedParameters);
      }
    }
    for (KotlinJvmSyntaxParser.ConstructorSyntax constructor : secondaryConstructors) {
      if (!canProjectValueClassSyntaxParameters(constructor.parameters)) {
        continue;
      }
      final String parameters = javaSyntaxParameterList(constructor.parameters, true);
      // Keep every real Kotlin constructor declaration. If it shares a JVM parameter vector
      // with the primary constructor (or another secondary constructor), the final owner-level
      // conflict registry must see both and reject the entire ambiguous surface.
      emittedParameters.add(secondaryConstructorSignature(constructor.parameters));
      out.append("  ").append(javaConstructorVisibility(constructor.visibility)).append(' ')
          .append(simpleName).append(parameters).append(" {}\n");
      if (constructor.jvmOverloads) {
        appendSecondaryConstructorOverloads(
            out, simpleName, constructor, emittedParameters);
      }
    }
  }

  private static void appendPrimaryConstructorOverloads(
      StringBuilder out,
      String simpleName,
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> parameters,
      String visibility,
      Set<String> emittedParameters) {
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && parameters.get(index).defaultValue; index--) {
      firstOmittable = index;
    }
    for (int count = parameters.size() - 1; count >= firstOmittable; count--) {
      final List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> overload =
          parameters.subList(0, count);
      if (!emittedParameters.add(primaryConstructorSignature(overload))) {
        continue;
      }
      out.append("  ").append(javaConstructorVisibility(visibility)).append(' ')
          .append(simpleName).append(javaConstructorParameterList(overload)).append(" {}\n");
    }
  }

  private static void appendSecondaryConstructorOverloads(
      StringBuilder out,
      String simpleName,
      KotlinJvmSyntaxParser.ConstructorSyntax constructor,
      Set<String> emittedParameters) {
    final List<KotlinJvmSyntaxParser.ParameterSyntax> parameters = constructor.parameters;
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && parameters.get(index).defaultValue; index--) {
      firstOmittable = index;
    }
    for (int count = parameters.size() - 1; count >= firstOmittable; count--) {
      final List<KotlinJvmSyntaxParser.ParameterSyntax> overload = parameters.subList(0, count);
      // Derived @JvmOverloads constructors also claim a real JVM surface. Do not hide a
      // collision with an earlier primary/secondary constructor; the final registry must reject
      // every declaration that shares that owner/name/parameter vector.
      emittedParameters.add(secondaryConstructorSignature(overload));
      out.append("  ").append(javaConstructorVisibility(constructor.visibility)).append(' ')
          .append(simpleName).append(javaSyntaxParameterList(overload, true)).append(" {}\n");
    }
  }

  private static boolean allConstructorParametersDefault(
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> parameters) {
    return !parameters.isEmpty() && parameters.stream().allMatch(parameter -> parameter.defaultValue);
  }

  private static boolean allParametersDefault(
      List<KotlinJvmSyntaxParser.ParameterSyntax> parameters) {
    return !parameters.isEmpty() && parameters.stream().allMatch(parameter -> parameter.defaultValue);
  }

  private static String primaryConstructorSignature(
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> parameters) {
    final List<String> types = new ArrayList<>();
    for (int index = 0; index < parameters.size(); index++) {
      final KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter = parameters.get(index);
      final String type = javaType(parameter.type);
      types.add(parameter.vararg ? type + "[]" : type);
    }
    return "(" + String.join(",", types) + ")";
  }

  private static String secondaryConstructorSignature(
      List<KotlinJvmSyntaxParser.ParameterSyntax> parameters) {
    final List<String> types = new ArrayList<>();
    for (int index = 0; index < parameters.size(); index++) {
      final KotlinJvmSyntaxParser.ParameterSyntax parameter = parameters.get(index);
      final String type = javaType(parameter.type);
      types.add(parameter.vararg ? type + "[]" : type);
    }
    return "(" + String.join(",", types) + ")";
  }

  private static void appendSyntheticNoArgConstructor(StringBuilder out, String simpleName) {
    out.append("  @SuppressWarnings(\"")
        .append(KotlinAbiSyntheticMembers.SYNTHETIC_CONSTRUCTOR_WARNING)
        .append("\")\n  protected ").append(simpleName).append("() {}\n");
  }

  private static String javaConstructorVisibility(String visibility) {
    return "private".equals(visibility) || "protected".equals(visibility)
        ? visibility
        : "public";
  }

  private static void appendFallbackConstructors(
      StringBuilder out,
      String simpleName,
      String kotlinParameters,
      String primaryVisibility,
      boolean primaryJvmOverloads,
      boolean hasSecondaryConstructors,
      boolean hasNoArgSecondaryConstructor) {
    appendFallbackConstructors(
        out, simpleName, kotlinParameters, primaryVisibility, primaryJvmOverloads,
        hasSecondaryConstructors, hasNoArgSecondaryConstructor, true);
  }

  private static void appendFallbackConstructors(
      StringBuilder out,
      String simpleName,
      String kotlinParameters,
      String primaryVisibility,
      boolean primaryJvmOverloads,
      boolean hasSecondaryConstructors,
      boolean hasNoArgSecondaryConstructor,
      boolean allowSyntheticBridge) {
    final String parameters = parameterList(kotlinParameters);
    final List<String> primaryKotlinParameters = kotlinParameters == null
        ? Collections.emptyList()
        : splitParameters(kotlinParameters.substring(1, kotlinParameters.length() - 1));
    final boolean primaryProjectable =
        canProjectFallbackValueClassConstructorParameters(primaryKotlinParameters);
    final boolean overloadCreatesNoArg = primaryProjectable
        && primaryJvmOverloads
        && kotlinParameters != null
        && allParametersDefaultFallback(primaryKotlinParameters);
    if (allowSyntheticBridge
        && !hasNoArgSecondaryConstructor
        && !overloadCreatesNoArg
        && (!"()".equals(parameters) || (kotlinParameters == null && hasSecondaryConstructors))) {
      appendSyntheticNoArgConstructor(out, simpleName);
    }
    if (primaryProjectable && (kotlinParameters != null || !hasSecondaryConstructors)) {
      out.append("  ").append(javaConstructorVisibility(primaryVisibility)).append(' ')
          .append(simpleName).append(parameters).append(" {}\n");
      if (primaryJvmOverloads && kotlinParameters != null) {
        appendConstructorOverloadsFallback(
            out,
            simpleName,
            primaryKotlinParameters,
            primaryVisibility,
            new LinkedHashSet<>(java.util.Collections.singleton(parameters)));
      }
    }
  }

  private static boolean hasSecondaryConstructorsFallback(String body) {
    int depth = 0;
    for (String line : body.split("\\R")) {
      if (depth == 0
          && SECONDARY_CONSTRUCTOR_PATTERN.matcher(
              line.replace("@JvmOverloads", "").trim()).matches()) {
        return true;
      }
      depth = Math.max(0, depth + braceDelta(line));
    }
    return false;
  }

  private static boolean hasNoArgSecondaryConstructorFallback(String body) {
    int depth = 0;
    for (String line : body.split("\\R")) {
      if (depth == 0) {
        final boolean jvmOverloads = line.contains("@JvmOverloads");
        final Matcher constructor = SECONDARY_CONSTRUCTOR_PATTERN.matcher(
            line.replace("@JvmOverloads", "").trim());
        if (constructor.matches()) {
          final List<String> parameters = splitParameters(constructor.group(2));
          if (canProjectFallbackValueClassConstructorParameters(parameters)
              && (parameters.isEmpty()
                  || (jvmOverloads && allParametersDefaultFallback(parameters)))) {
            return true;
          }
        }
      }
      depth = Math.max(0, depth + braceDelta(line));
    }
    return false;
  }

  private static void appendSecondaryConstructorsFallback(
      StringBuilder out, String simpleName, String body, String primaryConstructor) {
    final Set<String> emittedParameters = new LinkedHashSet<>();
    emittedParameters.add(parameterList(primaryConstructor));
    if (primaryConstructor != null && !"()".equals(parameterList(primaryConstructor))) {
      emittedParameters.add("()");
    }
    int depth = 0;
    boolean jvmOverloads = false;
    for (String line : body.split("\\R")) {
      if (depth == 0) {
        final boolean lineJvmOverloads = line.contains("@JvmOverloads");
        final String declarationLine = line.replace("@JvmOverloads", "").trim();
        jvmOverloads |= lineJvmOverloads;
        final Matcher constructor = SECONDARY_CONSTRUCTOR_PATTERN.matcher(declarationLine);
        if (constructor.matches()) {
          final List<String> kotlinParameters = splitParameters(constructor.group(2));
          if (!canProjectFallbackValueClassConstructorParameters(kotlinParameters)) {
            jvmOverloads = false;
            depth = Math.max(0, depth + braceDelta(line));
            continue;
          }
          final String parameters = javaParameterList(kotlinParameters);
          // Preserve every source-declared secondary constructor. A duplicate JVM signature must
          // remain visible to the final owner-level registry so it can reject all claimants.
          emittedParameters.add(parameters);
          out.append("  ").append(javaConstructorVisibility(constructor.group(1).trim()))
              .append(' ').append(simpleName).append(parameters).append(" {}\n");
          if (jvmOverloads) {
            appendConstructorOverloadsFallback(
                out,
                simpleName,
                kotlinParameters,
                constructor.group(1).trim(),
                emittedParameters);
          }
          jvmOverloads = false;
        } else if (!declarationLine.isEmpty() && !declarationLine.startsWith("@")) {
          jvmOverloads = false;
        }
      }
      depth = Math.max(0, depth + braceDelta(line));
    }
  }

  private static void appendConstructorOverloadsFallback(
      StringBuilder out,
      String simpleName,
      List<String> parameters,
      String visibility,
      Set<String> emittedParameters) {
    int firstOmittable = parameters.size();
    for (int index = parameters.size() - 1;
        index >= 0 && hasDefaultValue(parameters.get(index)); index--) {
      firstOmittable = index;
    }
    for (int count = parameters.size() - 1; count >= firstOmittable; count--) {
      // The sub-list is the actual generated @JvmOverloads constructor signature. A Kotlin
      // vararg that becomes its final parameter may use Java `...`; only non-final parameters
      // in that generated signature must remain `[]`.
      final String overload = javaParameterList(parameters.subList(0, count));
      // A generated @JvmOverloads variant is also a JVM surface claimant. Preserve collisions
      // for final owner-level conflict rejection instead of retaining whichever was emitted first.
      emittedParameters.add(overload);
      out.append("  ").append(javaConstructorVisibility(visibility)).append(' ')
          .append(simpleName).append(overload).append(" {}\n");
    }
  }

  private static boolean allParametersDefaultFallback(List<String> parameters) {
    return !parameters.isEmpty() && parameters.stream().allMatch(
        KotlinJvmAbiStubGenerator::hasDefaultValue);
  }

  private static boolean primaryConstructorJvmOverloadsFallback(
      String source, int classNameEnd, String constructor) {
    if (constructor == null) {
      return false;
    }
    final int constructorStart = source.indexOf(constructor, classNameEnd);
    return constructorStart >= classNameEnd
        && source.substring(classNameEnd, constructorStart).contains("JvmOverloads");
  }

  private static String primaryConstructorVisibilityFallback(
      String source, int classNameEnd, String constructor) {
    if (constructor == null) {
      return "public";
    }
    final int constructorStart = source.indexOf(constructor, classNameEnd);
    if (constructorStart < classNameEnd) {
      return "public";
    }
    final String prefix = source.substring(classNameEnd, constructorStart);
    if (prefix.matches("(?s).*\\bprivate\\s+(?:constructor\\s*)?$")) return "private";
    if (prefix.matches("(?s).*\\bprotected\\s+(?:constructor\\s*)?$")) return "protected";
    return "public";
  }

  private static String javaConstructorParameterList(
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> kotlinParameters) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      final KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter = kotlinParameters.get(index);
      final String type = javaType(parameter.type);
      parameters.add((parameter.vararg
          ? varargJavaType(type, index == kotlinParameters.size() - 1)
          : type) + " " + safeName(parameter.name, index));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static void appendSyntaxConstructorProperties(
      StringBuilder out,
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> kotlinParameters) {
    for (int index = 0; index < kotlinParameters.size(); index++) {
      final KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter = kotlinParameters.get(index);
      if (!parameter.property || parameter.name == null || parameter.name.isEmpty()
          || isScalarValueClassType(parameter.type)) {
        continue;
      }
      final String name = safeName(parameter.name, index);
      final String kotlinType = parameter.type;
      final String type = javaType(kotlinType);
      final String getter = propertyGetterName(name, kotlinType);
      final String setter = propertySetterName(name, kotlinType);
      out.append("  public ").append(type).append(' ').append(getter)
          .append("() { return ").append(defaultValue(kotlinType)).append("; }\n");
      if (parameter.mutableProperty) {
        out.append("  public void ").append(setter).append('(').append(type)
            .append(" value) {}\n");
      }
    }
  }

  private static void appendConstructorProperties(StringBuilder out, String kotlinParameters) {
    if (kotlinParameters == null || kotlinParameters.length() < 2) {
      return;
    }
    for (String raw :
        splitParameters(kotlinParameters.substring(1, kotlinParameters.length() - 1))) {
      final String part = raw.trim();
      if (!part.startsWith("val ") && !part.startsWith("var ")) {
        continue;
      }
      final String declaration = part.substring(4).trim();
      final int colon = declaration.indexOf(':');
      if (colon < 1) {
        continue;
      }
      final String name = declaration.substring(0, colon).trim();
      if (!name.matches("[A-Za-z_$][\\w$]*")) {
        continue;
      }
      final String kotlinType = declaration.substring(colon + 1);
      if (isScalarValueClassType(kotlinType)) {
        continue;
      }
      final String type = javaType(kotlinType);
      final String getter = propertyGetterName(name, kotlinType);
      final String setter = propertySetterName(name, kotlinType);
      out.append("  public ").append(type).append(' ').append(getter).append("() { return ")
          .append(defaultValue(kotlinType)).append("; }\n");
      if (part.startsWith("var ")) {
        out.append("  public void ").append(setter).append('(').append(type)
            .append(" value) {}\n");
      }
    }
  }

  private static void appendMembers(
      StringBuilder out, String source, boolean interfaceType, boolean topLevel) {
    int depth = 0;
    boolean jvmOverloads = false;
    boolean pendingJvmSynthetic = false;
    boolean pendingJvmField = false;
    boolean pendingGetterJvmSynthetic = false;
    boolean pendingSetterJvmSynthetic = false;
    String pendingJvmName = null;
    String pendingGetterJvmName = null;
    String pendingSetterJvmName = null;
    for (String line : source.split("\\R")) {
      if (depth == 0) {
        final boolean hasJvmOverloads = line.contains("@JvmOverloads");
        final boolean hasGetterJvmSynthetic = line.contains("@get:JvmSynthetic");
        final boolean hasSetterJvmSynthetic = line.contains("@set:JvmSynthetic");
        pendingJvmField |= line.matches(".*@JvmField(?:\\s|\\(|$).*");
        pendingGetterJvmSynthetic |= hasGetterJvmSynthetic;
        pendingSetterJvmSynthetic |= hasSetterJvmSynthetic;
        if (line.matches(".*@JvmSynthetic(?:\\s|\\(|$).*")) {
          pendingJvmSynthetic = true;
        }
        final Matcher jvmNameMatcher = Pattern.compile("@JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)")
            .matcher(line);
        if (jvmNameMatcher.find()) {
          pendingJvmName = jvmNameMatcher.group(1);
        }
        final Matcher getterJvmNameMatcher = Pattern.compile(
            "@get:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)").matcher(line);
        if (getterJvmNameMatcher.find()) {
          pendingGetterJvmName = getterJvmNameMatcher.group(1);
        }
        final Matcher setterJvmNameMatcher = Pattern.compile(
            "@set:JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)").matcher(line);
        if (setterJvmNameMatcher.find()) {
          pendingSetterJvmName = setterJvmNameMatcher.group(1);
        }
        final String declarationLine = line.replace("@JvmOverloads", "")
            .replaceAll("@(?:get:|set:)?JvmSynthetic(?:\\s*\\([^)]*\\))?", "")
            .replaceAll("@(?:get:|set:)?JvmName\\s*\\(\\s*\\\"[A-Za-z_$][\\w$]*\\\"\\s*\\)", "")
            .trim();
        if (hasJvmOverloads) {
          jvmOverloads = true;
        }
        final Matcher extensionFunction = EXTENSION_FUNCTION_PATTERN.matcher(declarationLine);
        if (extensionFunction.matches() && !isPrivate(extensionFunction.group(1))) {
          if (!pendingJvmSynthetic) {
            appendExtensionFunction(
                out, extensionFunction, interfaceType, topLevel, pendingJvmName);
          }
          jvmOverloads = false;
          pendingJvmSynthetic = false;
          pendingJvmName = null;
          pendingGetterJvmName = null;
          pendingSetterJvmName = null;
        } else {
          final Matcher function = FUNCTION_PATTERN.matcher(declarationLine);
          if (function.matches() && !isPrivate(function.group(1))) {
            if (!pendingJvmSynthetic) {
              final List<KotlinJvmSyntaxParser.TypeParameterSyntax> functionTypeParameters =
                  functionTypeParametersFallback(declarationLine);
              appendFunction(
                  out, function, interfaceType, topLevel, functionTypeParameters, pendingJvmName);
              if (jvmOverloads) {
                appendFunctionOverloads(
                    out, function, interfaceType, topLevel, functionTypeParameters, pendingJvmName);
              }
            }
            jvmOverloads = false;
            pendingJvmSynthetic = false;
            pendingJvmName = null;
            pendingGetterJvmName = null;
            pendingSetterJvmName = null;
            depth += braceDelta(line);
            if (depth < 0) {
              depth = 0;
            }
            continue;
          }
          if (jvmOverloads
              && !declarationLine.isEmpty()
              && !declarationLine.startsWith("@")
              && !PROPERTY_PATTERN.matcher(declarationLine).matches()) {
            jvmOverloads = false;
          }
          final Matcher property = PROPERTY_PATTERN.matcher(declarationLine);
          if (property.matches() && !isPrivate(property.group(1))) {
            jvmOverloads = false;
            if (!pendingJvmSynthetic) {
              if (pendingJvmField) {
                appendField(out, property, topLevel);
              } else {
                appendProperty(
                    out, property, interfaceType, topLevel,
                    pendingGetterJvmName, pendingSetterJvmName,
                    pendingGetterJvmSynthetic, pendingSetterJvmSynthetic);
              }
            }
            pendingJvmSynthetic = false;
            pendingJvmField = false;
            pendingGetterJvmSynthetic = false;
            pendingSetterJvmSynthetic = false;
            pendingJvmName = null;
            pendingGetterJvmName = null;
            pendingSetterJvmName = null;
          }
        }
      }
      depth += braceDelta(line);
      if (depth < 0) {
        depth = 0;
      }
    }
  }

  private static List<KotlinJvmSyntaxParser.SuperTypeSyntax> superTypesFallback(
      String source, int declarationNameEnd) {
    int index = declarationNameEnd;
    while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
      index++;
    }
    if (index < source.length() && source.charAt(index) == '<') {
      final int end = matchingDelimiter(source, index, '<', '>');
      if (end < 0) return java.util.Collections.emptyList();
      index = end + 1;
    }
    while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
      index++;
    }
    if (index < source.length() && source.charAt(index) == '(') {
      final int end = matchingDelimiter(source, index, '(', ')');
      if (end < 0) return java.util.Collections.emptyList();
      index = end + 1;
    }
    while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
      index++;
    }
    if (index >= source.length() || source.charAt(index) != ':') {
      return java.util.Collections.emptyList();
    }
    final int bodyStart = topLevelCharacterIndex(source, index + 1, '{');
    final int declarationEnd = inheritanceListEnd(source, index + 1);
    final int end = bodyStart < 0
        ? declarationEnd
        : Math.min(bodyStart, declarationEnd);
    final List<KotlinJvmSyntaxParser.SuperTypeSyntax> result = new ArrayList<>();
    for (String rawEntry : splitParameters(source.substring(index + 1, end))) {
      String entry = rawEntry.trim();
      final int by = entry.indexOf(" by ");
      if (by >= 0) {
        entry = entry.substring(0, by).trim();
      }
      final int invocation = topLevelCharacterIndex(entry, 0, '(');
      final boolean constructorInvocation = invocation > 0;
      final String type = constructorInvocation ? entry.substring(0, invocation).trim() : entry;
      if (!type.isEmpty() && type.indexOf("->") < 0) {
        result.add(new KotlinJvmSyntaxParser.SuperTypeSyntax(type, constructorInvocation));
      }
    }
    return result;
  }

  private static List<KotlinJvmSyntaxParser.TypeParameterSyntax> typeParametersFallback(
      String source, int declarationNameEnd) {
    for (int index = declarationNameEnd; index < source.length(); index++) {
      final char current = source.charAt(index);
      if (Character.isWhitespace(current)) {
        continue;
      }
      if (current != '<') {
        return java.util.Collections.emptyList();
      }
      final int end = matchingDelimiter(source, index, '<', '>');
      if (end < 0) {
        return java.util.Collections.emptyList();
      }
      final List<KotlinJvmSyntaxParser.TypeParameterSyntax> parameters =
          parseTypeParameters(source.substring(index + 1, end));
      appendWhereBoundsFallback(source, end + 1, parameters);
      return parameters;
    }
    return java.util.Collections.emptyList();
  }

  private static void appendWhereBoundsFallback(
      String source, int start, List<KotlinJvmSyntaxParser.TypeParameterSyntax> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return;
    }
    int where = -1;
    int end = source.length();
    int nesting = 0;
    int constraintLineStart = -1;
    for (int index = start; index < source.length(); index++) {
      final char current = source.charAt(index);
      if (current == '<' || current == '(' || current == '[') {
        nesting++;
      } else if (current == '>' || current == ')' || current == ']') {
        nesting = Math.max(0, nesting - 1);
      } else if (nesting == 0 && where < 0 && source.startsWith("where", index)
          && (index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1)))
          && (index + 5 >= source.length()
              || !Character.isJavaIdentifierPart(source.charAt(index + 5)))) {
        where = index + 5;
        constraintLineStart = where;
      } else if (nesting == 0 && (current == '{' || current == '=')) {
        end = index;
        break;
      } else if (nesting == 0 && where >= 0 && current == '\n') {
        final String line = source.substring(constraintLineStart, index).trim();
        if (!line.endsWith(",")) {
          end = index;
          break;
        }
        constraintLineStart = index + 1;
      }
    }
    if (where < 0) {
      return;
    }
    for (String constraint : splitParameters(source.substring(where, end))) {
      final int colon = topLevelIndexOf(constraint, ':');
      if (colon < 0) {
        continue;
      }
      final String name = constraint.substring(0, colon).trim();
      final String bound = constraint.substring(colon + 1).trim();
      if (bound.isEmpty()) {
        continue;
      }
      for (KotlinJvmSyntaxParser.TypeParameterSyntax parameter : parameters) {
        if (name.equals(parameter.name)) {
          parameter.upperBounds.add(bound);
          if (parameter.upperBound == null) {
            parameter.upperBound = parameter.upperBounds.get(0);
          }
          break;
        }
      }
    }
  }

  private static List<KotlinJvmSyntaxParser.TypeParameterSyntax> functionTypeParametersFallback(
      String declarationLine) {
    final int fun = declarationLine.indexOf("fun");
    if (fun < 0) {
      return java.util.Collections.emptyList();
    }
    int index = fun + 3;
    while (index < declarationLine.length()
        && Character.isWhitespace(declarationLine.charAt(index))) {
      index++;
    }
    if (index >= declarationLine.length() || declarationLine.charAt(index) != '<') {
      return java.util.Collections.emptyList();
    }
    final int end = matchingDelimiter(declarationLine, index, '<', '>');
    if (end < 0) {
      return java.util.Collections.emptyList();
    }
    final List<KotlinJvmSyntaxParser.TypeParameterSyntax> parameters =
        parseTypeParameters(declarationLine.substring(index + 1, end));
    appendWhereBoundsFallback(declarationLine, end + 1, parameters);
    return parameters;
  }

  private static List<KotlinJvmSyntaxParser.TypeParameterSyntax> parseTypeParameters(String text) {
    final List<KotlinJvmSyntaxParser.TypeParameterSyntax> result = new ArrayList<>();
    for (String rawParameter : splitParameters(text)) {
      String parameter = rawParameter.trim()
          .replaceFirst("^(?:(?:reified|in|out)\\s+)+", "");
      final int colon = topLevelIndexOf(parameter, ':');
      final String name = (colon < 0 ? parameter : parameter.substring(0, colon)).trim();
      if (!name.matches("[A-Za-z_$][\\w$]*")) {
        continue;
      }
      final String bound = colon < 0 ? null : parameter.substring(colon + 1).trim();
      result.add(new KotlinJvmSyntaxParser.TypeParameterSyntax(
          name, bound == null || bound.isEmpty() ? null : bound));
    }
    return result;
  }

  private static int inheritanceListEnd(String source, int start) {
    int lineStart = start;
    while (lineStart < source.length()) {
      final int lineEnd = source.indexOf('\n', lineStart);
      final int end = lineEnd < 0 ? source.length() : lineEnd;
      final String line = source.substring(lineStart, end).trim();
      if (!line.endsWith(",")) {
        return end;
      }
      lineStart = end + 1;
    }
    return source.length();
  }

  private static int topLevelCharacterIndex(String text, int start, char target) {
    int angleDepth = 0;
    int parenDepth = 0;
    int bracketDepth = 0;
    for (int index = start; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (current == target && angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
        return index;
      }
      if (current == '<') angleDepth++;
      else if (current == '>') angleDepth = Math.max(0, angleDepth - 1);
      else if (current == '(') parenDepth++;
      else if (current == ')') parenDepth = Math.max(0, parenDepth - 1);
      else if (current == '[') bracketDepth++;
      else if (current == ']') bracketDepth = Math.max(0, bracketDepth - 1);
    }
    return -1;
  }

  private static int topLevelIndexOf(String text, char target) {
    int nesting = 0;
    for (int index = 0; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (current == '<' || current == '(' || current == '[' || current == '{') {
        nesting++;
      } else if (current == '>' || current == ')' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == target && nesting == 0) {
        return index;
      }
    }
    return -1;
  }

  private static String primaryConstructorText(String source, int classNameEnd) {
    int angleDepth = 0;
    for (int index = classNameEnd; index < source.length(); index++) {
      final char current = source.charAt(index);
      if (current == '<') {
        angleDepth++;
      } else if (current == '>') {
        angleDepth = Math.max(0, angleDepth - 1);
      } else if (angleDepth == 0 && (current == ':' || current == '{')) {
        return null;
      } else if (angleDepth == 0 && current == '(') {
        final int end = matchingDelimiter(source, index, '(', ')');
        return end < 0 ? null : source.substring(index, end + 1);
      }
    }
    return null;
  }

  private static int matchingDelimiter(String text, int open, char opening, char closing) {
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = open; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
        if (current != '\\') {
          escaped = false;
        }
        continue;
      }
      if (current == '"') {
        quoted = true;
      } else if (current == opening) {
        depth++;
      } else if (current == closing && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static String parameterList(String kotlinParameters) {
    if (kotlinParameters == null || kotlinParameters.length() < 2) {
      return "()";
    }
    return javaParameterList(
        splitParameters(kotlinParameters.substring(1, kotlinParameters.length() - 1)));
  }

  private static String javaSyntaxParameterList(
      List<KotlinJvmSyntaxParser.ParameterSyntax> kotlinParameters,
      boolean allowTrailingVarargs) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      parameters.add(javaSyntaxParameter(
          kotlinParameters.get(index), index, allowTrailingVarargs && index == kotlinParameters.size() - 1));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static String javaAbiSyntaxParameterList(
      List<KotlinJvmSyntaxParser.ParameterSyntax> kotlinParameters,
      boolean allowTrailingVarargs) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      parameters.add(javaAbiSyntaxParameter(
          kotlinParameters.get(index), index, allowTrailingVarargs && index == kotlinParameters.size() - 1));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static String javaSyntaxParameter(
      KotlinJvmSyntaxParser.ParameterSyntax parameter, int index, boolean lastParameter) {
    return javaSyntaxParameter(parameter, index, lastParameter, false);
  }

  private static String javaAbiSyntaxParameter(
      KotlinJvmSyntaxParser.ParameterSyntax parameter, int index, boolean lastParameter) {
    return javaSyntaxParameter(parameter, index, lastParameter, true);
  }

  private static String javaSyntaxParameter(
      KotlinJvmSyntaxParser.ParameterSyntax parameter,
      int index,
      boolean lastParameter,
      boolean valueClassAbi) {
    final String elementType = valueClassAbi ? javaAbiType(parameter.type) : javaType(parameter.type);
    final String javaParameterType = parameter.vararg
        ? varargJavaType(elementType, lastParameter)
        : elementType;
    return javaParameterType + " " + safeName(parameter.name, index);
  }

  private static String varargJavaType(String elementType, boolean lastParameter) {
    if ("void".equals(elementType)) {
      return "Object[]";
    }
    return elementType + (lastParameter ? "..." : "[]");
  }

  private static String javaParameterList(List<String> kotlinParameters) {
    return javaParameterList(kotlinParameters, true);
  }

  private static String javaAbiParameterList(List<String> kotlinParameters) {
    return javaAbiParameterList(kotlinParameters, true);
  }

  private static String javaAbiParameterList(
      List<String> kotlinParameters, boolean allowTrailingVarargs) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      final String rawPart = kotlinParameters.get(index).trim();
      final boolean vararg = rawPart.matches("(?s)^(?:(?:val|var|crossinline|noinline)\\s+)*vararg\\b.*");
      final String part = rawPart.replaceAll(
          "^(?:(?:val|var|crossinline|noinline|vararg)\\s+)+", "");
      final int colon = part.indexOf(':');
      final String name = colon < 0 ? "arg" + index : part.substring(0, colon).trim();
      final String elementType = colon < 0 ? "Object" : javaAbiType(part.substring(colon + 1));
      parameters.add((vararg
          ? varargJavaType(elementType, allowTrailingVarargs && index == kotlinParameters.size() - 1)
          : elementType) + " " + safeName(name, index));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static String javaParameterList(
      List<String> kotlinParameters, boolean allowTrailingVarargs) {
    final List<String> parameters = new ArrayList<>();
    for (int index = 0; index < kotlinParameters.size(); index++) {
      final String rawPart = kotlinParameters.get(index).trim();
      final boolean vararg = rawPart.matches("(?s)^(?:(?:val|var|crossinline|noinline)\\s+)*vararg\\b.*");
      String part = rawPart.replaceAll(
          "^(?:(?:val|var|crossinline|noinline|vararg)\\s+)+", "");
      final int colon = part.indexOf(':');
      final String name = colon < 0 ? "arg" + index : part.substring(0, colon).trim();
      final String elementType = colon < 0 ? "Object" : javaType(part.substring(colon + 1));
      final String type = vararg
          ? varargJavaType(
              elementType, allowTrailingVarargs && index == kotlinParameters.size() - 1)
          : elementType;
      parameters.add(type + " " + safeName(name, index));
    }
    return "(" + String.join(", ", parameters) + ")";
  }

  private static List<String> splitParameters(String parameterText) {
    final List<String> parameters = new ArrayList<>();
    if (parameterText == null || parameterText.trim().isEmpty()) {
      return parameters;
    }
    int start = 0;
    int nesting = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < parameterText.length(); index++) {
      final char current = parameterText.charAt(index);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
        continue;
      }
      if (current == '"') {
        quoted = true;
      } else if (current == '(' || current == '<' || current == '[' || current == '{') {
        nesting++;
      } else if (current == ')' || current == '>' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == ',' && nesting == 0) {
        parameters.add(parameterText.substring(start, index));
        start = index + 1;
      }
    }
    parameters.add(parameterText.substring(start));
    return parameters;
  }

  private static boolean hasDefaultValue(String parameter) {
    int nesting = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < parameter.length(); index++) {
      final char current = parameter.charAt(index);
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
      } else if (current == '"') {
        quoted = true;
      } else if (current == '(' || current == '<' || current == '[' || current == '{') {
        nesting++;
      } else if (current == ')' || current == '>' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == '=' && nesting == 0) {
        return true;
      }
    }
    return false;
  }

  private static String javaInheritanceClause(
      List<KotlinJvmSyntaxParser.SuperTypeSyntax> superTypes, boolean interfaceType) {
    if (superTypes == null || superTypes.isEmpty()) {
      return "";
    }
    final List<String> interfaces = new ArrayList<>();
    String superClass = null;
    for (KotlinJvmSyntaxParser.SuperTypeSyntax superType : superTypes) {
      final String mapped = javaType(superType.type);
      if ("Object".equals(mapped) || "void".equals(mapped) || mapped.endsWith("[]")) {
        continue;
      }
      if (!interfaceType && superType.constructorInvocation && superClass == null) {
        superClass = mapped;
      } else {
        interfaces.add(mapped);
      }
    }
    if (interfaceType) {
      return interfaces.isEmpty() ? "" : " extends " + String.join(", ", interfaces);
    }
    final StringBuilder clause = new StringBuilder();
    if (superClass != null) {
      clause.append(" extends ").append(superClass);
    }
    if (!interfaces.isEmpty()) {
      clause.append(" implements ").append(String.join(", ", interfaces));
    }
    return clause.toString();
  }

  private static Set<String> registerTypeVariables(
      List<KotlinJvmSyntaxParser.TypeParameterSyntax> parameters) {
    final Set<String> added = new LinkedHashSet<>();
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    if (context == null || parameters == null) {
      return added;
    }
    for (KotlinJvmSyntaxParser.TypeParameterSyntax parameter : parameters) {
      if (parameter.name != null && context.typeVariables.add(parameter.name)) {
        added.add(parameter.name);
      }
    }
    return added;
  }

  private static void unregisterTypeVariables(Set<String> variables) {
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    if (context != null) {
      context.typeVariables.removeAll(variables);
    }
  }

  private static String javaTypeParameterBound(String parameterName, String kotlinBound) {
    if (kotlinBound == null) {
      return "Object";
    }
    final String bound = kotlinBound.trim();
    if (("Comparable<" + parameterName + ">").equals(bound)
        || ("kotlin.Comparable<" + parameterName + ">").equals(bound)) {
      // Verified by Kotlin compiler Signature evidence: Comparable<T> in a type-parameter
      // where-clause is emitted as Comparable<? super T>, not invariant Comparable<T>.
      return "Comparable<? super " + parameterName + ">";
    }
    return javaType(bound);
  }

  private static String javaTypeParameters(
      List<KotlinJvmSyntaxParser.TypeParameterSyntax> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return "";
    }
    final List<String> declarations = new ArrayList<>();
    for (KotlinJvmSyntaxParser.TypeParameterSyntax parameter : parameters) {
      if (parameter.name == null || !JAVA_TYPE_NAME_PATTERN.matcher(parameter.name).matches()) {
        continue;
      }
      final List<String> bounds = new ArrayList<>();
      for (String kotlinBound : parameter.upperBounds) {
        final String bound = javaTypeParameterBound(parameter.name, kotlinBound);
        if (!"Object".equals(bound) && !"void".equals(bound)) {
          bounds.add(bound);
        }
      }
      declarations.add(parameter.name
          + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds)));
    }
    return declarations.isEmpty() ? "" : "<" + String.join(", ", declarations) + "> ";
  }

  /**
   * Extension getters with a scalar receiver are mangled. A direct non-null scalar return alone
   * remains a plain JVM getter using its underlying representation; nullable returns stay omitted
   * until their representation has dedicated compiler evidence.
   */
  private static boolean canProjectValueClassExtensionGetter(
      String getterJvmName, String receiverType, String returnType, boolean interfaceMember) {
    if (getterJvmName != null) {
      return true;
    }
    if (isScalarValueClassType(receiverType)) {
      return false;
    }
    // Unlike top-level extensions, an interface member extension getter with a scalar value-class
    // return is mangled by Kotlin 2.1.0 and cannot be represented by a stable ordinary JVM name.
    return !containsValueClassType(returnType)
        || !interfaceMember && isDirectValueClassType(returnType);
  }

  /** Extension setters are mangled for either a scalar receiver or scalar value parameter. */
  private static boolean canProjectValueClassExtensionSetter(
      String setterJvmName, String receiverType, String valueType) {
    return setterJvmName != null
        || !isScalarValueClassType(receiverType) && !isScalarValueClassType(valueType);
  }

  /**
   * Scalar value-class properties use mangled default accessors. Arrays and generic containers of
   * value classes are boxed JVM surfaces and retain ordinary accessors.
   */
  private static boolean canProjectValueClassProperty(
      String getterJvmName, String setterJvmName, boolean mutable, String type) {
    if (!isScalarValueClassType(type)) {
      return true;
    }
    return getterJvmName != null && (!mutable || setterJvmName != null);
  }

  private static boolean canProjectValueClassFunction(
      String jvmName,
      String receiverType,
      List<KotlinJvmSyntaxParser.ParameterSyntax> parameters,
      String returnType) {
    boolean usesValueClass = containsValueClassType(receiverType) || containsValueClassType(returnType);
    if (parameters != null) {
      for (KotlinJvmSyntaxParser.ParameterSyntax parameter : parameters) {
        final boolean parameterUsesValueClass = containsValueClassType(parameter.type);
        // A value-class vararg is an array surface whose boxing/mangling cannot be proven from
        // source alone. Do not incorrectly reuse direct-parameter unboxing for its component.
        if (parameter.vararg && parameterUsesValueClass) return false;
        usesValueClass |= parameterUsesValueClass;
      }
    }
    return !usesValueClass || jvmName != null;
  }

  private static boolean canProjectFallbackValueClassFunction(
      String jvmName, String receiverType, List<String> parameters, String returnType) {
    boolean usesValueClass = containsValueClassType(receiverType) || containsValueClassType(returnType);
    if (parameters != null) {
      for (String parameter : parameters) {
        final int colon = topLevelIndexOf(parameter, ':');
        final boolean parameterUsesValueClass =
            colon >= 0 && containsValueClassType(parameter.substring(colon + 1));
        if (parameterUsesValueClass
            && Pattern.compile("(?:^|\\s)vararg(?:\\s|$)").matcher(parameter).find()) {
          return false;
        }
        usesValueClass |= parameterUsesValueClass;
      }
    }
    return !usesValueClass || jvmName != null;
  }

  private static boolean containsValueClassType(String kotlinType) {
    if (kotlinType == null) {
      return false;
    }
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    if (context == null || context.valueClassUnderlyingTypes.isEmpty()) {
      return false;
    }
    final String type = stripDefaultValue(kotlinType);
    for (String valueClass : context.valueClassUnderlyingTypes.keySet()) {
      if (Pattern.compile("(?:^|[^A-Za-z0-9_$.])" + Pattern.quote(valueClass)
          + "(?:$|[^A-Za-z0-9_$])").matcher(type).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Constructor declarations with a scalar value-class parameter have no public non-synthetic JVM
   * constructor in Kotlin 2.1.0. Arrays and generic containers remain boxed Java-visible surfaces.
   */
  private static boolean isScalarValueClassType(String kotlinType) {
    if (kotlinType == null) {
      return false;
    }
    String type = stripDefaultValue(kotlinType.trim());
    if (type.endsWith("?")) {
      type = type.substring(0, type.length() - 1).trim();
    }
    if (type.indexOf('<') >= 0 || type.indexOf('>') >= 0
        || type.indexOf('(') >= 0 || type.indexOf(')') >= 0 || type.indexOf('[') >= 0) {
      return false;
    }
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    return context != null && context.valueClassUnderlyingTypes.containsKey(type);
  }

  private static boolean canProjectValueClassConstructorParameters(
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> parameters) {
    for (KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter : parameters) {
      if (isScalarValueClassType(parameter.type)) {
        return false;
      }
    }
    return true;
  }

  private static boolean canProjectValueClassSyntaxParameters(
      List<KotlinJvmSyntaxParser.ParameterSyntax> parameters) {
    for (KotlinJvmSyntaxParser.ParameterSyntax parameter : parameters) {
      if (isScalarValueClassType(parameter.type)) {
        return false;
      }
    }
    return true;
  }

  private static boolean canProjectFallbackValueClassConstructorParameters(List<String> parameters) {
    for (String parameter : parameters) {
      final int colon = topLevelIndexOf(parameter, ':');
      if (colon >= 0 && isScalarValueClassType(parameter.substring(colon + 1))) {
        return false;
      }
    }
    return true;
  }

  /** Returns true only for a non-null, non-generic direct value-class occurrence. */
  private static boolean isDirectValueClassType(String kotlinType) {
    if (kotlinType == null) {
      return false;
    }
    final String type = stripDefaultValue(kotlinType.trim());
    if (type.endsWith("?") || type.indexOf('<') >= 0 || type.indexOf('>') >= 0
        || type.indexOf('(') >= 0 || type.indexOf(')') >= 0 || type.indexOf('[') >= 0) {
      return false;
    }
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    return context != null && context.valueClassUnderlyingTypes.containsKey(type);
  }

  /** Maps only the direct non-null value-class use proven safe by canProjectValueClassFunction. */
  private static String javaAbiType(String kotlinType) {
    if (isDirectValueClassType(kotlinType)) {
      final String underlying = TYPE_CONTEXT.get().valueClassUnderlyingTypes.get(kotlinType.trim());
      if (underlying != null) {
        return javaType(underlying);
      }
    }
    return javaType(kotlinType);
  }

  private static String javaType(String kotlinType) {
    if (kotlinType == null || kotlinType.trim().isEmpty()) {
      return "Object";
    }
    String type = stripDefaultValue(kotlinType.trim());
    final boolean nullable = type.endsWith("?");
    if (nullable) {
      type = type.substring(0, type.length() - 1).trim();
    }
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    if (context != null) {
      final String expandedAlias = context.typeAliases.get(type);
      if (expandedAlias != null) {
        type = expandedAlias;
      }
    }

    final String primitive = javaPrimitiveType(type);
    if (primitive != null) {
      return nullable ? boxedType(primitive) : primitive;
    }
    if ("String".equals(type) || "kotlin.String".equals(type)) return "String";
    if ("CharSequence".equals(type) || "kotlin.CharSequence".equals(type)) {
      return "CharSequence";
    }
    if ("Number".equals(type) || "kotlin.Number".equals(type)) return "Number";
    if ("Throwable".equals(type) || "kotlin.Throwable".equals(type)) return "Throwable";
    if ("Unit".equals(type) || "kotlin.Unit".equals(type)) return nullable ? "Object" : "void";
    if ("Any".equals(type) || "kotlin.Any".equals(type)
        || "Nothing".equals(type) || "kotlin.Nothing".equals(type)) {
      return "Object";
    }

    final String primitiveArray = javaPrimitiveArrayType(type);
    if (primitiveArray != null) {
      return primitiveArray;
    }

    final KotlinJvmTypeProjection.TypeApplication application =
        KotlinJvmTypeProjection.parseTypeApplication(type);
    if (application == null) {
      return javaUserType(type);
    }
    final String expandedGenericAlias = expandGenericTypeAlias(application, context);
    if (expandedGenericAlias != null) {
      return javaType(expandedGenericAlias);
    }
    // An imported generic name that is not in the supported alias map may be a Kotlin typealias
    // whose JVM surface is intentionally opaque (nullable, nested, function type, etc.). Do not
    // emit the alias spelling into Java; it is not a JVM class and would become javac ErrorType.
    if (context != null
        && context.imports.containsKey(application.rawType)
        && !context.genericTypeAliases.containsKey(application.rawType)) {
      return "Object";
    }
    final String rawJavaType = KotlinJvmTypeProjection.javaCollectionType(application.rawType);
    if ("Array".equals(application.rawType) || "kotlin.Array".equals(application.rawType)) {
      return application.arguments.size() == 1
          ? javaReferenceArrayType(application.arguments.get(0))
          : "Object[]";
    }
    final String resolvedRawType =
        rawJavaType == null ? javaUserType(application.rawType) : rawJavaType;
    if ("Object".equals(resolvedRawType)) {
      return "Object";
    }
    final List<String> arguments = new ArrayList<>();
    for (String argument : application.arguments) {
      arguments.add(javaTypeArgument(argument));
    }
    return arguments.isEmpty()
        ? resolvedRawType
        : resolvedRawType + "<" + String.join(", ", arguments) + ">";
  }

  private static String stripDefaultValue(String type) {
    int nesting = 0;
    for (int index = 0; index < type.length(); index++) {
      final char current = type.charAt(index);
      if (current == '<' || current == '(' || current == '[') {
        nesting++;
      } else if (current == '>' || current == ')' || current == ']') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == '=' && nesting == 0) {
        return type.substring(0, index).trim();
      }
    }
    return type;
  }

  private static String javaPrimitiveType(String type) {
    switch (type) {
      case "Int": case "kotlin.Int": return "int";
      case "Long": case "kotlin.Long": return "long";
      case "Double": case "kotlin.Double": return "double";
      case "Float": case "kotlin.Float": return "float";
      case "Boolean": case "kotlin.Boolean": return "boolean";
      case "Char": case "kotlin.Char": return "char";
      case "Byte": case "kotlin.Byte": return "byte";
      case "Short": case "kotlin.Short": return "short";
      default: return null;
    }
  }

  private static String boxedType(String primitive) {
    switch (primitive) {
      case "int": return "Integer";
      case "long": return "Long";
      case "double": return "Double";
      case "float": return "Float";
      case "boolean": return "Boolean";
      case "char": return "Character";
      case "byte": return "Byte";
      case "short": return "Short";
      default: return "Object";
    }
  }

  private static String javaPrimitiveArrayType(String type) {
    switch (type) {
      case "IntArray": case "kotlin.IntArray": return "int[]";
      case "LongArray": case "kotlin.LongArray": return "long[]";
      case "DoubleArray": case "kotlin.DoubleArray": return "double[]";
      case "FloatArray": case "kotlin.FloatArray": return "float[]";
      case "BooleanArray": case "kotlin.BooleanArray": return "boolean[]";
      case "CharArray": case "kotlin.CharArray": return "char[]";
      case "ByteArray": case "kotlin.ByteArray": return "byte[]";
      case "ShortArray": case "kotlin.ShortArray": return "short[]";
      default: return null;
    }
  }

  private static String javaUserType(String type) {
    if (!JAVA_TYPE_NAME_PATTERN.matcher(type).matches()) {
      return "Object";
    }
    final TypeResolutionContext context = TYPE_CONTEXT.get();
    if (context == null) {
      return "Object";
    }
    if (context.typeVariables.contains(type)) {
      return type;
    }
    final String imported = context.imports.get(type);
    if (imported != null) {
      return imported;
    }
    if (type.indexOf('.') >= 0) {
      return type;
    }
    if (context.declaredTypes.containsKey(type) || context.knownSimpleTypes.containsKey(type)) {
      return type;
    }
    return "Object";
  }

  // Collection-name normalization is shared with source navigation.

  private static String javaTypeArgument(String kotlinArgument) {
    String argument = kotlinArgument.trim();
    if ("*".equals(argument)) return "?";
    final boolean forceWildcard = argument.startsWith("@JvmWildcard ")
        || argument.startsWith("@kotlin.jvm.JvmWildcard ");
    final boolean suppressWildcard = argument.startsWith("@JvmSuppressWildcards ")
        || argument.startsWith("@kotlin.jvm.JvmSuppressWildcards ");
    if (forceWildcard) {
      argument = argument.substring(argument.indexOf(' ') + 1).trim();
    } else if (suppressWildcard) {
      argument = argument.substring(argument.indexOf(' ') + 1).trim();
    }
    if (argument.startsWith("out ")) {
      return "? extends " + boxedTypeArgument(javaType(argument.substring(4)));
    }
    if (argument.startsWith("in ")) {
      return "? super " + boxedTypeArgument(javaType(argument.substring(3)));
    }
    final String mapped = boxedTypeArgument(javaType(argument));
    return forceWildcard ? "? extends " + mapped : mapped;
  }

  private static String boxedTypeArgument(String javaType) {
    switch (javaType) {
      case "int":
      case "long":
      case "double":
      case "float":
      case "boolean":
      case "char":
      case "byte":
      case "short":
        return boxedType(javaType);
      default:
        return javaType;
    }
  }

  private static String javaReferenceArrayType(String kotlinElementType) {
    String element = kotlinElementType == null ? "" : kotlinElementType.trim();
    if (element.startsWith("out ")) element = element.substring(4).trim();
    else if (element.startsWith("in ")) element = element.substring(3).trim();
    final String elementType = boxedTypeArgument(javaType(element));
    return "void".equals(elementType) || elementType.contains("?") ? "Object[]" : elementType + "[]";
  }

  private static String expandGenericTypeAlias(
      KotlinJvmTypeProjection.TypeApplication application, TypeResolutionContext context) {
    if (context == null) return null;
    final GenericTypeAlias alias = context.genericTypeAliases.get(application.rawType);
    return alias == null ? null : KotlinJvmTypeProjection.expandGenericAlias(
        application, alias.parameters, alias.target, JAVA_TYPE_NAME_PATTERN);
  }

  // Type-application parsing is shared with source navigation.

  private static final class TypeResolutionContext {
    final Map<String, String> imports;
    final Map<String, String> declaredTypes;
    final Map<String, String> knownSimpleTypes;
    final Map<String, String> valueClassUnderlyingTypes;
    final Map<String, String> typeAliases;
    final Map<String, GenericTypeAlias> genericTypeAliases;
    final Set<String> typeVariables = new LinkedHashSet<>();

    private TypeResolutionContext(
        Map<String, String> imports,
        Map<String, String> declaredTypes,
        Map<String, String> knownSimpleTypes,
        Map<String, String> valueClassUnderlyingTypes,
        Map<String, String> typeAliases,
        Map<String, GenericTypeAlias> genericTypeAliases) {
      this.imports = imports;
      this.declaredTypes = declaredTypes;
      this.knownSimpleTypes = knownSimpleTypes;
      this.valueClassUnderlyingTypes = valueClassUnderlyingTypes;
      this.typeAliases = typeAliases;
      this.genericTypeAliases = genericTypeAliases;
    }

    static TypeResolutionContext create(
        String packageName,
        String generatedSimpleName,
        String source,
        Set<String> knownTypes,
        Map<String, String> visibleTypeAliases,
        Map<String, KotlinJvmTypeIndex.GenericTypeAlias> visibleGenericTypeAliases) {
      final Map<String, String> imports = new LinkedHashMap<>();
      final Matcher importMatcher = KOTLIN_IMPORT_PATTERN.matcher(source);
      while (importMatcher.find()) {
        final String qualifiedName = importMatcher.group(1);
        if (importMatcher.group(2) != null) {
          continue;
        }
        final int separator = qualifiedName.lastIndexOf('.');
        final String importedName = importMatcher.group(3) != null
            ? importMatcher.group(3)
            : separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
        imports.put(importedName, qualifiedName);
      }

      final Map<String, String> declaredTypes = new LinkedHashMap<>();
      final List<KotlinJvmSyntaxParser.TopLevelTypeSyntax> syntaxTypes =
          structuredGenerationEnabled()
              ? KotlinJvmSyntaxParser.findTopLevelTypes(source)
              : null;
      if (syntaxTypes != null) {
        for (KotlinJvmSyntaxParser.TopLevelTypeSyntax type : syntaxTypes) {
          if (!type.privateType && type.name != null) {
            declaredTypes.put(type.name, qualifiedName(packageName, type.name));
          }
        }
      }
      if (fallbackGenerationEnabled()) {
        collectTopLevelTypeNamesFallback(source, packageName, declaredTypes);
      }
      declaredTypes.put(
          generatedSimpleName, qualifiedName(packageName, generatedSimpleName));
      final KotlinJvmSyntaxParser.TypeSyntax generatedType =
          structuredGenerationEnabled()
              ? KotlinJvmSyntaxParser.findTopLevelType(source, generatedSimpleName)
              : null;
      if (generatedType != null) {
        collectNestedTypeNames(generatedType, packageName, generatedSimpleName, declaredTypes);
      }

      final Map<String, String> knownSimpleTypes = new LinkedHashMap<>();
      if (knownTypes != null) {
        for (String qualifiedType : knownTypes) {
          final int separator = qualifiedType.lastIndexOf('.');
          final String typePackage = separator < 0 ? "" : qualifiedType.substring(0, separator);
          final String simpleName =
              separator < 0 ? qualifiedType : qualifiedType.substring(separator + 1);
          if (packageName.equals(typePackage)) {
            knownSimpleTypes.put(simpleName, qualifiedType);
          }
        }
      }
      final Map<String, String> valueClassUnderlyingTypes = new LinkedHashMap<>();
      final Matcher valueClass = VALUE_CLASS_UNDERLYING_PATTERN.matcher(source);
      while (valueClass.find()) {
        valueClassUnderlyingTypes.put(valueClass.group(1), valueClass.group(2).trim());
      }
      final Map<String, String> typeAliases = collectSimpleTypeAliases(source, valueClassUnderlyingTypes);
      final Map<String, GenericTypeAlias> genericTypeAliases = collectGenericTypeAliases(source);
      if (visibleGenericTypeAliases != null) {
        for (Map.Entry<String, KotlinJvmTypeIndex.GenericTypeAlias> alias
            : visibleGenericTypeAliases.entrySet()) {
          genericTypeAliases.putIfAbsent(alias.getKey(),
              new GenericTypeAlias(alias.getValue().parameters,
                  alias.getValue().targetRawType + "<"
                      + String.join(", ", alias.getValue().targetArguments) + ">"));
        }
      }
      if (visibleTypeAliases != null) {
        for (Map.Entry<String, String> alias : visibleTypeAliases.entrySet()) {
          typeAliases.putIfAbsent(alias.getKey(), alias.getValue());
        }
      }
      return new TypeResolutionContext(
          imports,
          declaredTypes,
          knownSimpleTypes,
          valueClassUnderlyingTypes,
          typeAliases,
          genericTypeAliases);
    }
  }

  private static Map<String, GenericTypeAlias> collectGenericTypeAliases(String source) {
    final Map<String, GenericTypeAlias> aliases = new LinkedHashMap<>();
    final Matcher matcher = GENERIC_TYPE_ALIAS_PATTERN.matcher(source);
    while (matcher.find()) {
      final String name = matcher.group(1);
      final List<String> parameters = new ArrayList<>();
      for (String parameter : splitParameters(matcher.group(2))) {
        parameters.add(parameter.trim());
      }
      final String target = matcher.group(3).trim();
      final KotlinJvmTypeProjection.TypeApplication targetApplication =
          KotlinJvmTypeProjection.parseTypeApplication(target);
      if (parameters.isEmpty() || targetApplication == null || target.endsWith("?")
          || target.indexOf("->") >= 0 || target.indexOf('&') >= 0 || target.indexOf('|') >= 0
          || targetApplication.rawType.indexOf('.') >= 0) {
        continue;
      }
      boolean valid = true;
      for (String parameter : parameters) {
        if (!JAVA_TYPE_NAME_PATTERN.matcher(parameter).matches()) {
          valid = false;
          break;
        }
      }
      for (String argument : targetApplication.arguments) {
        if (!JAVA_TYPE_NAME_PATTERN.matcher(argument.trim()).matches()) {
          valid = false;
          break;
        }
      }
      if (valid && !aliases.containsKey(name)) {
        aliases.put(name, new GenericTypeAlias(parameters, target));
      }
    }
    return aliases;
  }

  private static Map<String, String> collectSimpleTypeAliases(
      String source, Map<String, String> valueClassUnderlyingTypes) {
    final Map<String, String> aliases = new LinkedHashMap<>();
    final Matcher alias = TYPE_ALIAS_PATTERN.matcher(source);
    while (alias.find()) {
      final String name = alias.group(1);
      final String target = alias.group(2).trim();
      // Generic declarations, function types and nullable targets are intentionally not expanded:
      // their JVM representation needs variance/boxing rules beyond this source-only projector.
      if (target.endsWith("?") || target.indexOf("->") >= 0 || target.indexOf('&') >= 0
          || target.indexOf('|') >= 0 || target.indexOf('(') >= 0 || target.indexOf(')') >= 0
          || valueClassUnderlyingTypes.containsKey(target)) {
        continue;
      }
      aliases.put(name, target);
    }
    // Do not recursively chase aliases: cycles and alias-specific generic substitutions must remain
    // conservative. Removing all targets that are aliases leaves only direct, stable expansions.
    for (String target : new ArrayList<>(aliases.values())) {
      aliases.values().removeIf(value -> aliases.containsKey(value));
    }
    return aliases;
  }

  private static final class GenericTypeAlias {
    final List<String> parameters;
    final String target;

    GenericTypeAlias(List<String> parameters, String target) {
      this.parameters = new ArrayList<>(parameters);
      this.target = target;
    }
  }

  // TypeApplication is defined by KotlinJvmTypeProjection.

  private static String functionReturnType(
      String declaredType, boolean interfaceType, boolean topLevel, boolean bodyPresent) {
    return (declaredType == null || declaredType.trim().isEmpty())
            && interfaceType && !topLevel && !bodyPresent
        ? "Unit"
        : declaredType;
  }

  private static boolean isSuspendModifier(String modifiers) {
    return modifiers != null
        && Pattern.compile("(?:^|\\s)suspend(?:\\s|$)").matcher(modifiers).find();
  }

  private static boolean fallbackFunctionBodyPresent(String declaration) {
    if (declaration == null) {
      return false;
    }
    final int parametersEnd = declaration.lastIndexOf(')');
    if (parametersEnd < 0 || parametersEnd + 1 >= declaration.length()) {
      return false;
    }
    final String tail = declaration.substring(parametersEnd + 1);
    return tail.indexOf('=') >= 0 || tail.indexOf('{') >= 0;
  }

  private static String methodBody(String kotlinType) {
    return "void".equals(javaType(kotlinType))
        ? " {}\n"
        : " { return " + defaultValue(kotlinType) + "; }\n";
  }

  private static String defaultValue(String kotlinType) {
    final String type = javaType(kotlinType);
    if ("void".equals(type)) return "";
    if ("boolean".equals(type)) return "false";
    if ("char".equals(type)) return "'\\0'";
    if ("byte".equals(type) || "short".equals(type) || "int".equals(type)) return "0";
    if ("long".equals(type)) return "0L";
    if ("float".equals(type)) return "0F";
    if ("double".equals(type)) return "0D";
    return "null";
  }

  private static boolean isFacadeName(String simpleName, String kotlinFileName, String source) {
    final Matcher jvmName = FILE_JVM_NAME_PATTERN.matcher(source);
    if (jvmName.find()) {
      return simpleName.equals(jvmName.group(1));
    }
    if (kotlinFileName == null || !kotlinFileName.endsWith(".kt")) {
      return false;
    }
    final String fileBaseName = kotlinFileName.substring(0, kotlinFileName.length() - 3);
    return simpleName.equals(fileBaseName + "Kt");
  }

  private static void collectTopLevelTypeNamesFallback(
      String source, String packageName, Map<String, String> result) {
    int braceDepth = 0;
    for (String line : source.split("\\R")) {
      if (braceDepth == 0) {
        final Matcher typeMatcher = TYPE_PATTERN.matcher(line);
        if (typeMatcher.find() && !isPrivate(typeMatcher.group(1))) {
          final String name = typeMatcher.group(3);
          result.putIfAbsent(name, qualifiedName(packageName, name));
        }
      }
      braceDepth = Math.max(0, braceDepth + braceDelta(line));
    }
  }

  private static void collectNestedTypeNames(
      KotlinJvmSyntaxParser.TypeSyntax owner,
      String packageName,
      String ownerName,
      Map<String, String> result) {
    for (KotlinJvmSyntaxParser.TypeSyntax nested : owner.nestedTypes) {
      if (nested.privateType || nested.name == null || nested.name.isEmpty()) {
        continue;
      }
      final String sourceName = ownerName + "." + nested.name;
      result.putIfAbsent(nested.name, qualifiedName(packageName, sourceName));
      collectNestedTypeNames(nested, packageName, sourceName, result);
    }
  }

  private static String qualifiedName(String packageName, String simpleName) {
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }

  private static StringBuilder header(String packageName) {
    final StringBuilder out = new StringBuilder();
    if (!packageName.isEmpty()) out.append("package ").append(packageName).append(";\n\n");
    return out;
  }

  private static boolean isPrivate(String modifiers) {
    return modifiers != null && Pattern.compile("(?:^|\\s)private(?:\\s|$)").matcher(modifiers).find();
  }

  private static String safeName(String name, int index) {
    return name.matches("[A-Za-z_$][\\w$]*") ? name : "arg" + index;
  }

  private static int matchingBrace(String source, int start) {
    int depth = 0;
    for (int i = start; i < source.length(); i++) {
      char current = source.charAt(i);
      if (current == '{') depth++;
      else if (current == '}' && --depth == 0) return i;
    }
    return -1;
  }

  private static int matchingAngleBracket(String source, int start) {
    int depth = 0;
    for (int index = start; index < source.length(); index++) {
      final char current = source.charAt(index);
      if (current == '<') depth++;
      else if (current == '>' && --depth == 0) return index;
    }
    return -1;
  }

  private static int braceDelta(String line) {
    int delta = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < line.length(); i++) {
      final char current = line.charAt(i);
      if (quoted) {
        if (current == '"' && !escaped) quoted = false;
        escaped = current == '\\' && !escaped;
      } else if (current == '"') quoted = true;
      else if (current == '{') delta++;
      else if (current == '}') delta--;
    }
    return delta;
  }
}
