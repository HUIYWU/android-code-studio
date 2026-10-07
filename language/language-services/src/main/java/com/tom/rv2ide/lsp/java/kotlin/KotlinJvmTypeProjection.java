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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, syntax-only Kotlin type helpers used by ABI generation and source navigation. */
final class KotlinJvmTypeProjection {

  private KotlinJvmTypeProjection() {}

  static TypeApplication parseTypeApplication(String type) {
    if (type == null) return null;
    final int open = type.indexOf('<');
    if (open < 1 || !type.endsWith(">")) return null;
    final String rawType = type.substring(0, open).trim();
    final List<String> arguments = splitTopLevelArguments(
        type.substring(open + 1, type.length() - 1));
    return rawType.isEmpty() || arguments.isEmpty()
        ? null
        : new TypeApplication(rawType, arguments);
  }

  static List<String> splitTopLevelArguments(String text) {
    if (text == null) return Collections.emptyList();
    final List<String> result = new ArrayList<>();
    int start = 0;
    int nesting = 0;
    for (int index = 0; index < text.length(); index++) {
      final char current = text.charAt(index);
      if (current == '<') nesting++;
      else if (current == '>') nesting--;
      else if (current == ',' && nesting == 0) {
        result.add(text.substring(start, index));
        start = index + 1;
      }
      if (nesting < 0) return Collections.emptyList();
    }
    if (nesting != 0) return Collections.emptyList();
    result.add(text.substring(start));
    return result;
  }

  static String expandGenericAlias(
      TypeApplication application,
      List<String> parameters,
      String target,
      Pattern typeNamePattern) {
    if (application == null || application.rawType.indexOf('.') >= 0
        || parameters == null || target == null
        || parameters.size() != application.arguments.size()) {
      return null;
    }
    for (String argument : application.arguments) {
      if (!typeNamePattern.matcher(argument.trim()).matches()) return null;
    }
    String expanded = target;
    for (int index = 0; index < parameters.size(); index++) {
      expanded = expanded.replaceAll(
          "\\b" + Pattern.quote(parameters.get(index)) + "\\b",
          Matcher.quoteReplacement(application.arguments.get(index).trim()));
    }
    return expanded;
  }

  static String javaCollectionType(String type) {
    switch (type) {
      case "List": case "MutableList": case "kotlin.List": case "kotlin.MutableList":
      case "kotlin.collections.List": case "kotlin.collections.MutableList":
        return "java.util.List";
      case "Set": case "MutableSet": case "kotlin.Set": case "kotlin.MutableSet":
      case "kotlin.collections.Set": case "kotlin.collections.MutableSet":
        return "java.util.Set";
      case "Map": case "MutableMap": case "kotlin.Map": case "kotlin.MutableMap":
      case "kotlin.collections.Map": case "kotlin.collections.MutableMap":
        return "java.util.Map";
      case "Collection": case "MutableCollection":
      case "kotlin.Collection": case "kotlin.MutableCollection":
      case "kotlin.collections.Collection": case "kotlin.collections.MutableCollection":
        return "java.util.Collection";
      case "Iterable": case "kotlin.Iterable": case "kotlin.collections.Iterable":
        return "java.lang.Iterable";
      default:
        return null;
    }
  }

  static final class TypeApplication {
    final String rawType;
    final List<String> arguments;

    TypeApplication(String rawType, List<String> arguments) {
      this.rawType = rawType;
      this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
    }
  }
}