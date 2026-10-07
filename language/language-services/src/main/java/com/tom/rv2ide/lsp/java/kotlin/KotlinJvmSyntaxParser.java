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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java.kotlin;

import com.itsaky.androidide.treesitter.TSNode;
import com.itsaky.androidide.treesitter.TSParser;
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Extracts declaration boundaries from Kotlin source without performing semantic resolution. */
final class KotlinJvmSyntaxParser {

  private KotlinJvmSyntaxParser() {}

  /** Reports whether the native Kotlin grammar can create a usable syntax tree. Test diagnostics
   * use this instead of mistaking a swallowed native-linkage failure for a grammar mismatch. */
  static ParseStatus parseStatus(String source) {
    if (source == null) {
      return new ParseStatus(false, "SOURCE", "Source is null");
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return new ParseStatus(false, "TREE", "parseString returned null");
        }
        final TSNode root = tree.getRootNode();
        if (root == null || root.isNull()) {
          return new ParseStatus(false, "ROOT", "parse tree has no root node");
        }
        return new ParseStatus(true, "OK", root.getType());
      }
    } catch (Throwable error) {
      final String message = error.getMessage();
      return new ParseStatus(
          false,
          error.getClass().getName(),
          message == null || message.isEmpty() ? error.toString() : message);
    }
  }

  static List<TopLevelTypeSyntax> findTopLevelTypes(String source) {
    if (source == null) {
      return null;
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return null;
        }
        final TSNode root = tree.getRootNode();
        if (root == null || root.isNull()) {
          return null;
        }
        final List<TopLevelTypeSyntax> result = new ArrayList<>();
        for (int index = 0; index < root.getNamedChildCount(); index++) {
          final TSNode declaration = root.getNamedChild(index);
          final String kind = declaration.getType();
          if (!"class_declaration".equals(kind) && !"object_declaration".equals(kind)) {
            continue;
          }
          final TSNode name = fieldChild(declaration, "name", "type_identifier");
          if (name != null) {
            result.add(new TopLevelTypeSyntax(
                text(source, name),
                startIndex(source, name),
                endIndex(source, name) - startIndex(source, name),
                hasModifier(source, declaration, "private")));
          }
        }
        return Collections.unmodifiableList(result);
      }
    } catch (Throwable ignored) {
      return null;
    }
  }

  static List<TypeSyntax> findTopLevelTypeSyntaxes(String source) {
    if (source == null) {
      return null;
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return null;
        }
        final TSNode root = tree.getRootNode();
        if (root == null || root.isNull()) {
          return null;
        }
        final List<TypeSyntax> result = new ArrayList<>();
        for (int index = 0; index < root.getNamedChildCount(); index++) {
          final TSNode declaration = root.getNamedChild(index);
          final String kind = declaration.getType();
          if ("class_declaration".equals(kind) || "object_declaration".equals(kind)) {
            result.add(typeSyntax(source, declaration));
          }
        }
        return Collections.unmodifiableList(result);
      }
    } catch (Throwable ignored) {
      return null;
    }
  }

  static List<MemberSyntax> findTopLevelMembers(String source) {
    if (source == null) {
      return null;
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return null;
        }
        final TSNode root = tree.getRootNode();
        return root == null || root.isNull() ? null : members(source, root);
      }
    } catch (Throwable ignored) {
      return null;
    }
  }

  static TypeSyntax findTopLevelType(String source, String simpleName) {
    if (source == null || simpleName == null || simpleName.isEmpty()) {
      return null;
    }
    try (TSParser parser = TSParser.create()) {
      parser.setLanguage(TSLanguageKotlin.getInstance());
      try (var tree = parser.parseString(source)) {
        if (tree == null) {
          return null;
        }
        final TSNode root = tree.getRootNode();
        if (root == null || root.isNull()) {
          return null;
        }
        for (int index = 0; index < root.getNamedChildCount(); index++) {
          final TSNode declaration = root.getNamedChild(index);
          final String nodeType = declaration.getType();
          if (!"class_declaration".equals(nodeType) && !"object_declaration".equals(nodeType)) {
            continue;
          }
          final TSNode nameNode = fieldChild(declaration, "name", "type_identifier");
          if (nameNode == null || !simpleName.equals(text(source, nameNode))) {
            continue;
          }
          return typeSyntax(source, declaration);
        }
      }
    } catch (Throwable ignored) {
      // Tree-sitter is an accuracy layer. Callers retain a conservative text fallback for devices
      // where the native grammar cannot be loaded or an incomplete edit cannot be parsed safely.
    }
    return null;
  }

  private static TypeSyntax typeSyntax(String source, TSNode declaration) {
    final String nodeType = declaration.getType();
    final TSNode nameNode = fieldChild(declaration, "name", "type_identifier");
    final String name = nameNode == null ? "" : text(source, nameNode);
    TSNode bodyNode = fieldChild(declaration, "body", "class_body", "enum_class_body");
    if (bodyNode == null) {
      bodyNode = firstDescendant(declaration, "class_body");
    }
    if (bodyNode == null) {
      bodyNode = firstDescendant(declaration, "enum_class_body");
    }
    final TSNode constructor = constructorNode(declaration);
    final String recoveredConstructorText =
        constructorTextFallback(source, declaration, nameNode, bodyNode);
    final String constructorText = constructor == null
        ? recoveredConstructorText : text(source, constructor);
    final List<ConstructorParameterSyntax> parsedConstructorParameters =
        constructorParameters(source, constructor);
    final String parameterFallbackText =
        recoveredConstructorText == null ? constructorText : recoveredConstructorText;
    final List<ConstructorParameterSyntax> constructorParameters =
        parsedConstructorParameters.isEmpty() && parameterFallbackText != null
            ? constructorParametersFallback(parameterFallbackText)
            : parsedConstructorParameters;
    final TSNode companion = bodyNode == null ? null : directChild(bodyNode, "companion_object");
    final TSNode companionNameNode = fieldChild(companion, "name", "type_identifier");
    final String companionName = companionNameNode == null ? "Companion" : text(source, companionNameNode);
    final TSNode companionBodyNode = fieldChild(companion, "body", "class_body");
    final List<TypeSyntax> nestedTypes = new ArrayList<>();
    collectDirectNestedTypes(source, bodyNode, nestedTypes);
    return new TypeSyntax(
        nodeType,
        name,
        nameNode == null ? -1 : startIndex(source, nameNode),
        nameNode == null ? 0 : endIndex(source, nameNode) - startIndex(source, nameNode),
        text(source, declaration),
        bodyNode == null ? "" : innerBody(text(source, bodyNode)),
        members(source, bodyNode),
        Collections.unmodifiableList(nestedTypes),
        typeParameters(
            source,
            fieldChild(declaration, "type_parameters", "type_parameters"),
            typeConstraintsBeforeBody(source, declaration, bodyNode)),
        superTypes(source, declaration),
        constructorParameters,
        constructor != null || recoveredConstructorText != null,
        constructorVisibility(source, constructor),
        hasJvmOverloads(source, constructor),
        secondaryConstructors(source, bodyNode),
        companionBodyNode == null ? null : companionName,
        companionBodyNode == null ? null : innerBody(text(source, companionBodyNode)),
        members(source, companionBodyNode),
        hasDirectToken(declaration, "interface"),
        hasDirectToken(declaration, "enum"),
        hasClassModifier(source, declaration, "annotation"),
        hasModifier(source, declaration, "private"),
        hasClassModifier(source, declaration, "inner"),
        hasClassModifier(source, declaration, "value"));
  }

  private static void collectDirectNestedTypes(
      String source, TSNode node, List<TypeSyntax> result) {
    if (node == null) {
      return;
    }
    final String kind = node.getType();
    if ("class_declaration".equals(kind) || "object_declaration".equals(kind)) {
      result.add(typeSyntax(source, node));
      return;
    }
    if ("function_declaration".equals(kind)
        || "property_declaration".equals(kind)
        || "companion_object".equals(kind)
        || "anonymous_initializer".equals(kind)
        || "secondary_constructor".equals(kind)) {
      return;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      collectDirectNestedTypes(source, node.getNamedChild(index), result);
    }
  }

  private static void collectDirectMembers(TSNode node, List<TSNode> result) {
    final String kind = node.getType();
    if ("function_declaration".equals(kind) || "property_declaration".equals(kind)
        || "getter".equals(kind)) {
      result.add(node);
      return;
    }
    if ("class_declaration".equals(kind)
        || "object_declaration".equals(kind)
        || "companion_object".equals(kind)
        || "anonymous_initializer".equals(kind)
        || "secondary_constructor".equals(kind)) {
      return;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      collectDirectMembers(node.getNamedChild(index), result);
    }
  }

  private static List<MemberSyntax> members(String source, TSNode body) {
    if (body == null) {
      return Collections.emptyList();
    }
    final List<TSNode> declarations = new ArrayList<>();
    collectDirectMembers(body, declarations);
    final List<MemberSyntax> result = new ArrayList<>();
    for (int index = 0; index < declarations.size(); index++) {
      final TSNode declaration = declarations.get(index);
      final String kind = declaration.getType();
      if ("getter".equals(kind)) {
        continue;
      }
      final TSNode explicitGetter = "property_declaration".equals(kind)
          && index + 1 < declarations.size()
          && "getter".equals(declarations.get(index + 1).getType())
              ? declarations.get(index + 1) : null;
      final TSNode modifiers = fieldChild(declaration, "modifiers", "modifiers");
      final TSNode functionBody = fieldChild(declaration, "body", "function_body");
      final int declarationStart = modifiers == null ? startIndex(source, declaration) : endIndex(source, modifiers);
      final int declarationEnd = functionBody == null
          ? endIndex(source, declaration)
          : startIndex(source, functionBody);
      final String modifierText = modifiers == null ? "" : text(source, modifiers);
      final String declarationText = source.substring(
          Math.max(0, Math.min(source.length(), declarationStart)),
          Math.max(declarationStart, Math.min(source.length(), declarationEnd)))
          .replaceAll("\\s+", " ")
          .trim();
      result.add("function_declaration".equals(kind)
          ? functionSyntax(source, declaration, declarationText, modifiers, modifierText)
          : propertySyntax(source, declaration, declarationText, modifiers, modifierText, explicitGetter));
    }
    return Collections.unmodifiableList(result);
  }

  private static MemberSyntax functionSyntax(
      String source,
      TSNode declaration,
      String declarationText,
      TSNode modifiers,
      String modifierText) {
    final TSNode name = fieldChild(declaration, "name", "simple_identifier");
    final List<TypeParameterSyntax> typeParameters =
        typeParameters(
            source,
            fieldChild(declaration, "type_parameters", "type_parameters"),
            directChild(declaration, "type_constraints"));
    final TSNode parameters =
        fieldChild(declaration, "parameters", "function_value_parameters");
    final TSNode receiver = extensionReceiver(declaration, name);
    final TSNode declaredReturnType = fieldChild(declaration, "return_type");
    final TSNode returnType =
        declaredReturnType != null ? declaredReturnType : typeChildAfter(declaration, parameters);
    final List<ParameterSyntax> parameterList = parameters(source, parameters);
    return new MemberSyntax(
        "function_declaration",
        declarationText,
        containsToken(modifiers, "private"),
        modifierText.contains("JvmStatic"),
        modifierText.contains("JvmField"),
        modifierText.contains("JvmOverloads"),
        containsToken(modifiers, "suspend"),
        hasJvmSynthetic(leadingAnnotations(source, declaration) + " "
            + modifierText + " " + text(source, declaration)),
        false,
        false,
        jvmName(leadingAnnotations(source, declaration) + " "
            + modifierText + " " + text(source, declaration)), 
        null,
        null,
        name == null ? null : text(source, name),
        name == null ? -1 : startIndex(source, name),
        name == null ? 0 : endIndex(source, name) - startIndex(source, name),
        parameters == null ? "()" : text(source, parameters),
        parameterList,
        typeParameters,
        receiver == null ? null : text(source, receiver),
        returnType == null ? null : text(source, returnType),
        false,
        false,
        fieldChild(declaration, "body", "function_body") != null);
  }

  private static MemberSyntax propertySyntax(
      String source,
      TSNode declaration,
      String declarationText,
      TSNode modifiers,
      String modifierText,
      TSNode explicitGetter) {
    final TSNode variable =
        fieldChild(declaration, "declaration", "variable_declaration");
    final TSNode name = fieldChild(variable, "name", "simple_identifier");
    final TSNode declaredPropertyType = fieldChild(variable, "type");
    final TSNode propertyType =
        declaredPropertyType != null
            ? declaredPropertyType
            : variable == null ? null : firstTypeChild(variable);
    final TSNode receiver = variable == null ? null : extensionReceiver(declaration, variable);
    // The native Kotlin grammar emits an accessor as the immediate sibling following its property.
    // members(...) pairs only that adjacent getter with this declaration.
    final boolean getterBodyPresent = explicitGetter != null;
    return new MemberSyntax(
        "property_declaration",
        declarationText,
        containsToken(modifiers, "private"),
        modifierText.contains("JvmStatic"),
        modifierText.contains("JvmField"),
        modifierText.contains("JvmOverloads"),
        false,
        hasJvmSynthetic(leadingAnnotations(source, declaration) + " "
            + modifierText + " " + text(source, declaration)),
        hasAccessorJvmSynthetic(leadingAnnotations(source, declaration) + " "
            + modifierText + " " + text(source, declaration), "get"),
        hasAccessorJvmSynthetic(leadingAnnotations(source, declaration) + " "
            + modifierText + " " + text(source, declaration), "set"),
        null,
        accessorJvmName(leadingAnnotations(source, declaration) + " "
            + modifierText + " " + text(source, declaration), "get"),
        accessorJvmName(leadingAnnotations(source, declaration) + " "
            + modifierText + " " + text(source, declaration), "set"),
        name == null ? null : text(source, name),
        name == null ? -1 : startIndex(source, name),
        name == null ? 0 : endIndex(source, name) - startIndex(source, name),
        null,
        Collections.emptyList(),
        Collections.emptyList(),
        receiver == null ? null : text(source, receiver),
        propertyType == null ? null : text(source, propertyType),
        hasBindingPattern(declaration, "var"),
        hasBindingPattern(declaration, "val"),
        getterBodyPresent);
  }

  private static String leadingAnnotations(String source, TSNode declaration) {
    final int declarationStart = startIndex(source, declaration);
    int lineStart = declarationStart;
    while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
      lineStart--;
    }
    final StringBuilder annotations = new StringBuilder();
    final String sameLinePrefix = source.substring(lineStart, declarationStart).trim();
    if (sameLinePrefix.startsWith("@") && !sameLinePrefix.startsWith("@file:")) {
      annotations.append(sameLinePrefix).append(' ');
    }
    int cursor = lineStart;
    while (cursor > 0) {
      int previousEnd = cursor - 1;
      if (previousEnd > 0 && source.charAt(previousEnd - 1) == '\r') {
        previousEnd--;
      }
      int previousStart = previousEnd;
      while (previousStart > 0 && source.charAt(previousStart - 1) != '\n') {
        previousStart--;
      }
      final String line = source.substring(previousStart, previousEnd).trim();
      if (line.startsWith("@file:")
          || !line.matches("@(?:[A-Za-z_][\\w]*(?::[A-Za-z_][\\w]*)?)(?:\\s*\\([^)]*\\))?\\s*")) {
        break;
      }
      annotations.insert(0, line + " ");
      cursor = previousStart;
    }
    return annotations.toString();
  }

  private static boolean hasJvmSynthetic(String declarationText) {
    return declarationText != null
        && java.util.regex.Pattern.compile("@JvmSynthetic(?:\\s|\\(|$)").matcher(declarationText).find();
  }

  private static boolean hasAccessorJvmSynthetic(String declarationText, String useSite) {
    return declarationText != null
        && java.util.regex.Pattern.compile("@" + useSite + ":JvmSynthetic(?:\\s|\\(|$)")
            .matcher(declarationText).find();
  }

  private static String jvmName(String declarationText) {
    if (declarationText == null) {
      return null;
    }
    final java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("@JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)")
            .matcher(declarationText);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static String accessorJvmName(String declarationText, String useSite) {
    if (declarationText == null) {
      return null;
    }
    final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "@" + useSite + ":JvmName\\s*\\(\\s*\\\"([A-Za-z_$][\\w$]*)\\\"\\s*\\)")
        .matcher(declarationText);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static List<SuperTypeSyntax> superTypes(String source, TSNode declaration) {
    final List<SuperTypeSyntax> result = new ArrayList<>();
    final TSNode supertypes = fieldChild(declaration, "supertypes");
    if (supertypes != null) {
      collectSuperTypes(source, supertypes, result);
      return Collections.unmodifiableList(result);
    }
    for (int index = 0; index < declaration.getNamedChildCount(); index++) {
      final TSNode child = declaration.getNamedChild(index);
      if ("class_body".equals(child.getType()) || "enum_class_body".equals(child.getType())) {
        break;
      }
      collectSuperTypes(source, child, result);
    }
    return Collections.unmodifiableList(result);
  }

  private static void collectSuperTypes(
      String source, TSNode node, List<SuperTypeSyntax> result) {
    if ("delegation_specifier".equals(node.getType())) {
      final TSNode invocation = firstDescendant(node, "constructor_invocation");
      final TSNode userType = firstDescendant(node, "user_type");
      if (userType != null) {
        result.add(new SuperTypeSyntax(text(source, userType), invocation != null));
      }
      return;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      collectSuperTypes(source, node.getNamedChild(index), result);
    }
  }

  private static List<TypeParameterSyntax> typeParameters(
      String source, TSNode container, TSNode constraints) {
    if (container == null) {
      return Collections.emptyList();
    }
    final List<TypeParameterSyntax> result = new ArrayList<>();
    for (int index = 0; index < container.getNamedChildCount(); index++) {
      final TSNode parameter = container.getNamedChild(index);
      if (!"type_parameter".equals(parameter.getType())) {
        continue;
      }
      final TSNode name = fieldChild(parameter, "name", "type_identifier");
      final TSNode declaredBound = fieldChild(parameter, "bound");
      final TSNode bound =
          declaredBound != null ? declaredBound : typeChildAfter(parameter, name);
      if (name != null) {
        final List<String> bounds = new ArrayList<>();
        if (bound != null) {
          bounds.add(text(source, bound));
        }
        result.add(new TypeParameterSyntax(text(source, name), bounds));
      }
    }
    if (constraints != null) {
      for (int index = 0; index < constraints.getNamedChildCount(); index++) {
        final TSNode constraint = constraints.getNamedChild(index);
        if (!"type_constraint".equals(constraint.getType())) {
          continue;
        }
        final TSNode name = fieldChild(constraint, "name", "type_identifier");
        final TSNode bound = typeChildAfter(constraint, name);
        if (name == null || bound == null) {
          continue;
        }
        final String constrainedName = text(source, name);
        for (TypeParameterSyntax parameter : result) {
          if (constrainedName.equals(parameter.name)) {
            parameter.upperBounds.add(text(source, bound));
            if (parameter.upperBound == null) {
              parameter.upperBound = parameter.upperBounds.get(0);
            }
            break;
          }
        }
      }
    }
    return Collections.unmodifiableList(result);
  }

  private static boolean hasJvmOverloads(String source, TSNode constructor) {
    final TSNode modifiers = fieldChild(constructor, "modifiers", "modifiers");
    return modifiers != null && text(source, modifiers).contains("JvmOverloads");
  }

  private static String constructorVisibility(String source, TSNode constructor) {
    if (constructor == null) {
      return "public";
    }
    final TSNode modifiers = fieldChild(constructor, "modifiers", "modifiers");
    if (containsToken(modifiers, "private")) return "private";
    if (containsToken(modifiers, "protected")) return "protected";
    // Kotlin internal constructors are public in JVM bytecode. Keep that callable ABI surface.
    return "public";
  }

  private static List<ConstructorSyntax> secondaryConstructors(String source, TSNode body) {
    if (body == null) {
      return Collections.emptyList();
    }
    final List<ConstructorSyntax> result = new ArrayList<>();
    for (int index = 0; index < body.getNamedChildCount(); index++) {
      final TSNode declaration = body.getNamedChild(index);
      if (!"secondary_constructor".equals(declaration.getType())) {
        continue;
      }
      final TSNode modifiers = fieldChild(declaration, "modifiers", "modifiers");
      final TSNode parameterList =
          fieldChild(declaration, "parameters", "function_value_parameters");
      final String visibility = containsToken(modifiers, "private")
          ? "private"
          : containsToken(modifiers, "protected") ? "protected" : "public";
      final String constructorText = text(source, declaration);
      final int constructorRelativeOffset = constructorText.indexOf("constructor");
      result.add(new ConstructorSyntax(
          parameters(source, parameterList),
          visibility,
          hasJvmOverloads(source, declaration),
          constructorRelativeOffset < 0
              ? startIndex(source, declaration)
              : startIndex(source, declaration) + constructorRelativeOffset,
          "constructor".length()));
    }
    return Collections.unmodifiableList(result);
  }

  private static boolean hasDefaultValue(String source, TSNode parameter) {
    return topLevelIndexOf(text(source, parameter), '=') >= 0;
  }

  private static List<ConstructorParameterSyntax> constructorParameters(
      String source, TSNode constructor) {
    if (constructor == null) {
      return Collections.emptyList();
    }
    final List<ConstructorParameterSyntax> result = new ArrayList<>();
    final List<TSNode> parameters = new ArrayList<>();
    collectDescendants(constructor, "class_parameter", parameters);
    for (TSNode parameter : parameters) {
      final TSNode name = fieldChild(parameter, "name", "simple_identifier");
      final TSNode declaredType = fieldChild(parameter, "type");
      final TSNode type = declaredType != null ? declaredType : firstTypeChild(parameter);
      final TSNode propertyKind = fieldChild(parameter, "property_kind", "binding_pattern_kind");
      final TSNode defaultValue = fieldChild(parameter, "default_value");
      result.add(new ConstructorParameterSyntax(
          name == null ? "arg" + result.size() : text(source, name),
          type == null ? null : text(source, type),
          name == null ? -1 : startIndex(source, name),
          name == null ? 0 : endIndex(source, name) - startIndex(source, name),
          propertyKind != null
              ? containsToken(propertyKind, "val")
              : hasBindingPattern(parameter, "val"),
          propertyKind != null
              ? containsToken(propertyKind, "var")
              : hasBindingPattern(parameter, "var"),
          defaultValue != null || hasDefaultValue(source, parameter),
           containsToken(parameter, "vararg")));
    }
    return Collections.unmodifiableList(result);
  }

  private static List<ParameterSyntax> parameters(String source, TSNode container) {
    if (container == null) {
      return Collections.emptyList();
    }
    final List<TSNode> nodes = new ArrayList<>();
    final List<Boolean> varargs = new ArrayList<>();
    TSNode parameterModifiers = null;
    for (int index = 0; index < container.getNamedChildCount(); index++) {
      final TSNode child = container.getNamedChild(index);
      if ("parameter_modifiers".equals(child.getType())) {
        parameterModifiers = child;
      } else if ("parameter".equals(child.getType())) {
        nodes.add(child);
        varargs.add(containsToken(parameterModifiers, "vararg"));
        parameterModifiers = null;
      } else {
        parameterModifiers = null;
      }
    }
    final List<ParameterSyntax> result = new ArrayList<>();
    for (int index = 0; index < nodes.size(); index++) {
      final TSNode parameter = nodes.get(index);
      final TSNode name = fieldChild(parameter, "name", "simple_identifier");
      final TSNode declaredType = fieldChild(parameter, "type");
      final TSNode type = declaredType != null ? declaredType : firstTypeChild(parameter);
      final int suffixEnd = index + 1 < nodes.size()
          ? startIndex(source, nodes.get(index + 1))
          : endIndex(source, container) - 1;
      final int suffixStart = endIndex(source, parameter);
      final boolean defaultValue = suffixEnd > suffixStart
          && source.substring(suffixStart, suffixEnd).indexOf('=') >= 0;
      result.add(new ParameterSyntax(
          name == null ? "arg" + index : text(source, name),
          type == null ? null : text(source, type),
          defaultValue,
          varargs.get(index)));
    }
    return Collections.unmodifiableList(result);
  }

  /** Prefers the field-rich grammar and retains old node-shape recovery for earlier artifacts. */
  private static TSNode extensionReceiver(TSNode declaration, TSNode boundary) {
    final TSNode receiver = fieldChild(declaration, "receiver", "receiver_type");
    return receiver != null ? receiver : typeChildBefore(declaration, boundary);
  }

  private static TSNode typeChildBefore(TSNode parent, TSNode boundary) {
    if (boundary == null) {
      return null;
    }
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      if (sameNode(child, boundary)) {
        break;
      }
      if (isTypeNode(child.getType())) {
        return child;
      }
    }
    return null;
  }

  private static TSNode typeChildAfter(TSNode parent, TSNode boundary) {
    if (boundary == null) {
      return null;
    }
    boolean after = false;
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      if (after && isTypeNode(child.getType())) {
        return child;
      }
      if (sameNode(child, boundary)) {
        after = true;
      }
    }
    return null;
  }

  private static TSNode firstTypeChild(TSNode parent) {
    if (parent == null) {
      return null;
    }
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      if (isTypeNode(child.getType())) {
        return child;
      }
    }
    return null;
  }

  private static boolean isTypeNode(String type) {
    return "user_type".equals(type)
        || "nullable_type".equals(type)
        || "function_type".equals(type)
        || "parenthesized_type".equals(type)
        || "type_identifier".equals(type)
        || "not_nullable_type".equals(type);
  }

  private static boolean sameNode(TSNode first, TSNode second) {
    return first.getStartByte() == second.getStartByte()
        && first.getEndByte() == second.getEndByte()
        && first.getType().equals(second.getType());
  }
  private static TSNode fieldChild(TSNode parent, String fieldName, String... fallbackTypes) {
    if (parent == null) {
      return null;
    }
    try {
      final TSNode child = parent.getChildByFieldName(fieldName);
      if (child != null && !child.isNull()) {
        return child;
      }
    } catch (Throwable ignored) {
      // Fields are an accuracy enhancement. Keep parsing with stable node types when a grammar
      // artifact or its JNI binding cannot resolve a field on this device.
    }
    return fallbackTypes.length == 0 ? null : directChild(parent, fallbackTypes);
  }

  private static TSNode typeConstraintsBeforeBody(
      String source, TSNode declaration, TSNode body) {
    final int bodyStartByte = body == null ? declaration.getEndByte() : body.getStartByte();
    return firstDescendantBefore(declaration, "type_constraints", bodyStartByte);
  }

  private static TSNode firstDescendantBefore(
      TSNode node, String type, int endByte) {
    if (node == null || node.getStartByte() >= endByte) {
      return null;
    }
    if (type.equals(node.getType())) {
      return node;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      final TSNode match = firstDescendantBefore(
          node.getNamedChild(index), type, endByte);
      if (match != null) {
        return match;
      }
    }
    return null;
  }

  private static TSNode directChild(TSNode parent, String... types) {
    if (parent == null) {
      return null;
    }
    for (int index = 0; index < parent.getNamedChildCount(); index++) {
      final TSNode child = parent.getNamedChild(index);
      final String childType = child.getType();
      for (String type : types) {
        if (type.equals(childType)) {
          return child;
        }
      }
    }
    return null;
  }


  private static String constructorTextFallback(
      String source, TSNode declaration, TSNode name, TSNode body) {
    final int searchStart = endIndex(source, name);
    final int searchEnd = body == null
        ? endIndex(source, declaration)
        : startIndex(source, body);
    final int open = source.indexOf('(', searchStart);
    if (open < 0 || open >= searchEnd) {
      return null;
    }
    int depth = 0;
    for (int index = open; index < searchEnd; index++) {
      final char current = source.charAt(index);
      if (current == '(') {
        depth++;
      } else if (current == ')' && --depth == 0) {
        return source.substring(open, index + 1);
      }
    }
    return null;
  }

  private static List<ConstructorParameterSyntax> constructorParametersFallback(String constructorText) {
    if (constructorText.length() < 2) {
      return Collections.emptyList();
    }
    final List<ConstructorParameterSyntax> result = new ArrayList<>();
    for (String rawParameter : splitTopLevel(constructorText.substring(1, constructorText.length() - 1))) {
      String parameter = rawParameter.trim();
      final int colon = topLevelIndexOf(parameter, ':');
      if (colon < 1) {
        continue;
      }
      String declaration = parameter.substring(0, colon).trim();
      final boolean mutable = declaration.matches("(?s).*\\bvar\\s+[A-Za-z_$][\\w$]*$");
      final boolean property = mutable
          || declaration.matches("(?s).*\\bval\\s+[A-Za-z_$][\\w$]*$");
      declaration = declaration.replaceFirst("(?s)^.*\\b(?:val|var)\\s+", "").trim();
      final String[] nameParts = declaration.split("\\s+");
      final String name = nameParts.length == 0 ? "arg" + result.size() : nameParts[nameParts.length - 1];
      String type = parameter.substring(colon + 1).trim();
      final int equals = topLevelIndexOf(type, '=');
      if (equals >= 0) {
        type = type.substring(0, equals).trim();
      }
      result.add(new ConstructorParameterSyntax(
          name, type, -1, 0, property, mutable, equals >= 0,
          declaration.matches("(?s)(?:^|.*\\s)vararg\\s+[A-Za-z_$][\\w$]*$")));
    }
    return Collections.unmodifiableList(result);
  }

  private static List<String> splitTopLevel(String text) {
    final List<String> result = new ArrayList<>();
    int start = 0;
    int nesting = 0;
    for (int index = 0; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (current == '<' || current == '(' || current == '[' || current == '{') {
        nesting++;
      } else if (current == '>' || current == ')' || current == ']' || current == '}') {
        nesting = Math.max(0, nesting - 1);
      } else if (current == ',' && nesting == 0) {
        result.add(text.substring(start, index));
        start = index + 1;
      }
    }
    if (start < text.length()) {
      result.add(text.substring(start));
    }
    return result;
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

  private static TSNode constructorNode(TSNode declaration) {
    final TSNode constructor =
        fieldChild(declaration, "primary_constructor", "primary_constructor");
    if (constructor != null) {
      return constructor;
    }
    final TSNode body = fieldChild(declaration, "body", "class_body", "enum_class_body");
    final int bodyStart = body == null ? Integer.MAX_VALUE : body.getStartByte();
    for (int index = 0; index < declaration.getNamedChildCount(); index++) {
      final TSNode child = declaration.getNamedChild(index);
      if (child.getStartByte() >= bodyStart) {
        break;
      }
      final TSNode recovered = firstDescendant(child, "primary_constructor");
      if (recovered != null) {
        return recovered;
      }
      if (firstDescendant(child, "class_parameter") != null) {
        return child;
      }
    }
    return null;
  }

  private static TSNode firstDescendant(TSNode node, String type) {
    if (type.equals(node.getType())) {
      return node;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      final TSNode found = firstDescendant(node.getNamedChild(index), type);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static void collectDescendants(TSNode node, String type, List<TSNode> result) {
    if (type.equals(node.getType())) {
      result.add(node);
      return;
    }
    for (int index = 0; index < node.getNamedChildCount(); index++) {
      collectDescendants(node.getNamedChild(index), type, result);
    }
  }

  private static boolean hasDirectToken(TSNode parent, String token) {
    for (int index = 0; index < parent.getChildCount(); index++) {
      final TSNode child = parent.getChild(index);
      if (token.equals(child.getType())) {
        return true;
      }
    }
    return false;
  }

  /** Supports both 0.3.6's direct val/var tokens and 0.3.8's binding_pattern_kind node. */
  private static boolean hasBindingPattern(TSNode declaration, String token) {
    if (hasDirectToken(declaration, token)) {
      return true;
    }
    final TSNode bindingPattern = directChild(declaration, "binding_pattern_kind");
    return bindingPattern != null && containsToken(bindingPattern, token);
  }

  private static boolean hasClassModifier(
      String source, TSNode declaration, String modifier) {
    final TSNode modifiers = fieldChild(declaration, "modifiers", "modifiers");
    return hasWrappedModifier(source, modifiers, "class_modifier", modifier);
  }

  private static boolean hasWrappedModifier(
      String source, TSNode node, String wrapperType, String modifier) {
    if (node == null) {
      return false;
    }
    if (wrapperType.equals(node.getType())) {
      return modifier.equals(text(source, node).trim());
    }
    for (int index = 0; index < node.getChildCount(); index++) {
      if (hasWrappedModifier(source, node.getChild(index), wrapperType, modifier)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasModifier(String source, TSNode declaration, String modifier) {
    final TSNode modifiers = fieldChild(declaration, "modifiers", "modifiers");
    if (modifiers == null) {
      return false;
    }
    if (containsToken(modifiers, modifier)) {
      return true;
    }
    // Restrict textual recovery to the modifiers node range so identifiers, comments and
    // declaration bodies cannot create false modifier matches.
    final String modifierText = text(source, modifiers);
    return Pattern.compile("(?:^|\\s)" + Pattern.quote(modifier) + "(?:\\s|$)")
        .matcher(modifierText).find();
  }

  private static boolean containsToken(TSNode node, String token) {
    if (node == null) {
      return false;
    }
    if (token.equals(node.getType())) {
      return true;
    }
    for (int index = 0; index < node.getChildCount(); index++) {
      if (containsToken(node.getChild(index), token)) {
        return true;
      }
    }
    return false;
  }

  private static int startIndex(String source, TSNode node) {
    return Math.max(0, Math.min(source.length(), node.getStartByte() / 2));
  }

  private static int endIndex(String source, TSNode node) {
    return Math.max(0, Math.min(source.length(), node.getEndByte() / 2));
  }

  private static String text(String source, TSNode node) {
    // This Android binding parses Java strings as UTF-16; offsets are byte counts.
    final int start = startIndex(source, node);
    final int end = Math.max(start, endIndex(source, node));
    return source.substring(start, end);
  }

  private static String innerBody(String body) {
    final int start = body.indexOf('{');
    final int end = body.lastIndexOf('}');
    return start >= 0 && end > start ? body.substring(start + 1, end) : "";
  }
  static final class ParseStatus {
    final boolean available;
    final String stage;
    final String detail;

    ParseStatus(boolean available, String stage, String detail) {
      this.available = available;
      this.stage = stage;
      this.detail = detail;
    }

    @Override
    public String toString() {
      return "available=" + available + ", stage=" + stage + ", detail=" + detail;
    }
  }

  static final class TopLevelTypeSyntax {
    final String name;
    final int nameOffset;
    final int nameLength;
    final boolean privateType;

    TopLevelTypeSyntax(String name, int nameOffset, int nameLength, boolean privateType) {
      this.name = name;
      this.nameOffset = nameOffset;
      this.nameLength = nameLength;
      this.privateType = privateType;
    }
  }

  static final class TypeSyntax {

    final String nodeType;
    final String name;
    final int nameOffset;
    final int nameLength;
    final String declarationText;
    final String body;
    final List<MemberSyntax> members;
    final List<TypeSyntax> nestedTypes;
    final List<TypeParameterSyntax> typeParameters;
    final List<SuperTypeSyntax> superTypes;
    final List<ConstructorParameterSyntax> constructorParameters;
    final boolean primaryConstructorPresent;
    final String constructorVisibility;
    final boolean constructorJvmOverloads;
    final List<ConstructorSyntax> secondaryConstructors;
    final String companionName;
    final String companionBody;
    final List<MemberSyntax> companionMembers;
    final boolean interfaceType;
    final boolean enumType;
    final boolean annotationType;
    final boolean privateType;
    final boolean innerType;
    final boolean valueType;

    TypeSyntax(
        String nodeType,
        String name,
        int nameOffset,
        int nameLength,
        String declarationText,
        String body,
        List<MemberSyntax> members,
        List<TypeSyntax> nestedTypes,
        List<TypeParameterSyntax> typeParameters,
        List<SuperTypeSyntax> superTypes,
        List<ConstructorParameterSyntax> constructorParameters,
        boolean primaryConstructorPresent,
        String constructorVisibility,
        boolean constructorJvmOverloads,
        List<ConstructorSyntax> secondaryConstructors,
        String companionName,
        String companionBody,
        List<MemberSyntax> companionMembers,
        boolean interfaceType,
        boolean enumType,
        boolean annotationType,
        boolean privateType,
        boolean innerType,
        boolean valueType) {
      this.nodeType = nodeType;
      this.name = name;
      this.nameOffset = nameOffset;
      this.nameLength = nameLength;
      this.declarationText = declarationText;
      this.body = body;
      this.members = members;
      this.nestedTypes = nestedTypes;
      this.typeParameters = typeParameters;
      this.superTypes = superTypes;
      this.constructorParameters = constructorParameters;
      this.primaryConstructorPresent = primaryConstructorPresent;
      this.constructorVisibility = constructorVisibility;
      this.constructorJvmOverloads = constructorJvmOverloads;
      this.secondaryConstructors = secondaryConstructors;
      this.companionName = companionName;
      this.companionBody = companionBody;
      this.companionMembers = companionMembers;
      this.interfaceType = interfaceType;
      this.enumType = enumType;
      this.annotationType = annotationType;
      this.privateType = privateType;
      this.innerType = innerType;
      this.valueType = valueType;
    }

    boolean objectType() {
      return "object_declaration".equals(nodeType);
    }
  }

  static final class MemberSyntax {
    final String kind;
    final String declarationText;
    final boolean privateMember;
    final boolean jvmStatic;
    final boolean jvmField;
    final boolean jvmOverloads;
    final boolean suspendFunction;
    final boolean jvmSynthetic;
    final boolean getterJvmSynthetic;
    final boolean setterJvmSynthetic;
    final String jvmName;
    final String getterJvmName;
    final String setterJvmName;
    final String name;
    final int nameOffset;
    final int nameLength;
    final String parameters;
    final List<ParameterSyntax> parameterList;
    final List<TypeParameterSyntax> typeParameters;
    final String receiverType;
    final String declaredType;
    final boolean mutableProperty;
    final boolean readOnlyProperty;
    final boolean functionBodyPresent;

    MemberSyntax(
        String kind,
        String declarationText,
        boolean privateMember,
        boolean jvmStatic,
        boolean jvmField,
        boolean jvmOverloads,
        boolean suspendFunction,
        boolean jvmSynthetic,
        boolean getterJvmSynthetic,
        boolean setterJvmSynthetic,
        String jvmName,
        String getterJvmName,
        String setterJvmName,
        String name,
        int nameOffset,
        int nameLength,
        String parameters,
        List<ParameterSyntax> parameterList,
        List<TypeParameterSyntax> typeParameters,
        String receiverType,
        String declaredType,
        boolean mutableProperty,
        boolean readOnlyProperty,
        boolean functionBodyPresent) {
      this.kind = kind;
      this.declarationText = declarationText;
      this.privateMember = privateMember;
      this.jvmStatic = jvmStatic;
      this.jvmField = jvmField;
      this.jvmOverloads = jvmOverloads;
      this.suspendFunction = suspendFunction;
      this.jvmSynthetic = jvmSynthetic;
      this.getterJvmSynthetic = getterJvmSynthetic;
      this.setterJvmSynthetic = setterJvmSynthetic;
      this.jvmName = jvmName;
      this.getterJvmName = getterJvmName;
      this.setterJvmName = setterJvmName;
      this.name = name;
      this.nameOffset = nameOffset;
      this.nameLength = nameLength;
      this.parameters = parameters;
      this.parameterList = parameterList;
      this.typeParameters = typeParameters;
      this.receiverType = receiverType;
      this.declaredType = declaredType;
      this.mutableProperty = mutableProperty;
      this.readOnlyProperty = readOnlyProperty;
      this.functionBodyPresent = functionBodyPresent;
    }

    boolean function() {
      return "function_declaration".equals(kind);
    }
  }

  static final class SuperTypeSyntax {
    final String type;
    final boolean constructorInvocation;

    SuperTypeSyntax(String type, boolean constructorInvocation) {
      this.type = type;
      this.constructorInvocation = constructorInvocation;
    }
  }

  static final class TypeParameterSyntax {
    final String name;
    // Kept for existing JVM-erasure callers: Kotlin erases a type variable to its first bound.
    String upperBound;
    final List<String> upperBounds;

    TypeParameterSyntax(String name, String upperBound) {
      this(name, upperBound == null
          ? Collections.emptyList() : Collections.singletonList(upperBound));
    }

    TypeParameterSyntax(String name, List<String> upperBounds) {
      this.name = name;
      this.upperBounds = new ArrayList<>(upperBounds);
      this.upperBound = this.upperBounds.isEmpty() ? null : this.upperBounds.get(0);
    }
  }

  static final class ConstructorSyntax {
    final List<ParameterSyntax> parameters;
    final String visibility;
    final boolean jvmOverloads;
    final int nameOffset;
    final int nameLength;

    ConstructorSyntax(
        List<ParameterSyntax> parameters,
        String visibility,
        boolean jvmOverloads,
        int nameOffset,
        int nameLength) {
      this.parameters = parameters;
      this.visibility = visibility;
      this.jvmOverloads = jvmOverloads;
      this.nameOffset = nameOffset;
      this.nameLength = nameLength;
    }
  }

  static final class ConstructorParameterSyntax {
    final String name;
    final String type;
    final int nameOffset;
    final int nameLength;
    final boolean property;
    final boolean mutableProperty;
    final boolean defaultValue;
    final boolean vararg;

    ConstructorParameterSyntax(
        String name,
        String type,
        int nameOffset,
        int nameLength,
        boolean readOnlyProperty,
        boolean mutableProperty,
        boolean defaultValue,
        boolean vararg) {
      this.name = name;
      this.type = type;
      this.nameOffset = nameOffset;
      this.nameLength = nameLength;
      this.property = readOnlyProperty || mutableProperty;
      this.mutableProperty = mutableProperty;
      this.defaultValue = defaultValue;
      this.vararg = vararg;
    }
  }

  static final class ParameterSyntax {
    final String name;
    final String type;
    final boolean defaultValue;
    final boolean vararg;

    ParameterSyntax(String name, String type, boolean defaultValue, boolean vararg) {
      this.name = name;
      this.type = type;
      this.defaultValue = defaultValue;
      this.vararg = vararg;
    }
  }
}
