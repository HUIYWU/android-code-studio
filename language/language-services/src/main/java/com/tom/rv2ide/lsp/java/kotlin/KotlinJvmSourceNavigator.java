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

import com.tom.rv2ide.lsp.java.kotlin.KotlinJvmTypeIndex.KotlinTypeDeclaration;
import com.tom.rv2ide.models.Location;
import com.tom.rv2ide.models.Position;
import com.tom.rv2ide.models.Range;
import com.tom.rv2ide.preferences.internal.JavaPreferences;
import com.tom.rv2ide.projects.FileManager;
import com.tom.rv2ide.projects.ModuleProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdkx.lang.model.element.Element;
import jdkx.lang.model.element.ElementKind;
import jdkx.lang.model.element.ExecutableElement;
import jdkx.lang.model.element.TypeElement;

/** Maps javac elements from Kotlin ABI stubs back to real Kotlin source declarations. */
public final class KotlinJvmSourceNavigator {

  private static final Pattern TYPE_ALIAS_PATTERN =
      Pattern.compile("(?m)^\\s*typealias\\s+([A-Za-z_][\\w]*)\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern GENERIC_TYPE_ALIAS_PATTERN =
      Pattern.compile(
          "(?m)^\\s*typealias\\s+([A-Za-z_][\\w]*)\\s*<([^<>]+)>\\s*=\\s*([^\\r\\n]+?)\\s*$");
  private static final Pattern TYPE_NAME_PATTERN =
      Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");
  private static final Pattern VALUE_CLASS_UNDERLYING_TYPE_PATTERN = Pattern.compile(
      "\\bvalue\\s+class\\s+([A-Za-z_][\\w]*)\\s*\\(\\s*(?:val|var)\\s+"
          + "[A-Za-z_][\\w]*\\s*:\\s*([^,\\)\\r\\n]+)");
  private static final ThreadLocal<Map<String, String>> TYPE_ALIASES = new ThreadLocal<>();
  private static final ThreadLocal<Map<String, GenericTypeAlias>> GENERIC_TYPE_ALIASES =
      new ThreadLocal<>();

  private KotlinJvmSourceNavigator() {}

  public static Location find(ModuleProject module, Element element) {
    if (!JavaPreferences.INSTANCE.isJavaKotlinRecognitionEnabled()) {
      return null;
    }
    TypeElement owner = ownerType(element);
    if (module == null || owner == null) {
      return null;
    }
    final TypeElement declaredOwner = owner;
    final TypeElement topLevelOwner = topLevelOwner(owner);
    final String qualifiedName = topLevelOwner.getQualifiedName().toString();
    final List<KotlinTypeDeclaration> multifileDeclarations =
        KotlinJvmTypeIndex.findMultifileDeclarations(module, qualifiedName);
    if (!(element instanceof TypeElement) && !multifileDeclarations.isEmpty()) {
      for (KotlinTypeDeclaration multifileDeclaration : multifileDeclarations) {
        final String multifileSource =
            FileManager.INSTANCE.getDocumentContents(multifileDeclaration.file).toString();
        final Map<String, String> previousAliases = TYPE_ALIASES.get();
        final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
        TYPE_ALIASES.set(visibleTypeAliases(module, multifileDeclaration.file, multifileSource));
        GENERIC_TYPE_ALIASES.set(visibleGenericTypeAliases(
        module, multifileDeclaration.file, multifileSource));
        try {
          final SourceRange multifileRange = findFacadeMember(
              multifileSource, ValueClassAbiContext.fromSource(multifileSource), element);
          if (multifileRange != null) {
            return location(
                multifileDeclaration.file, multifileSource, multifileRange.offset, multifileRange.length);
          }
        } finally {
          if (previousAliases == null) {
            TYPE_ALIASES.remove();
          } else {
            TYPE_ALIASES.set(previousAliases);
          }
          if (previousGenericAliases == null) {
            GENERIC_TYPE_ALIASES.remove();
          } else {
            GENERIC_TYPE_ALIASES.set(previousGenericAliases);
          }
        }
      }
    }
    final KotlinTypeDeclaration declaration = multifileDeclarations.isEmpty()
        ? KotlinJvmTypeIndex.findDeclaration(module, qualifiedName)
        : multifileDeclarations.get(0);
    if (declaration == null) {
      return null;
    }
    final String source = FileManager.INSTANCE.getDocumentContents(declaration.file).toString();
    final Map<String, String> previousAliases = TYPE_ALIASES.get();
    final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
    TYPE_ALIASES.set(visibleTypeAliases(module, declaration.file, source));
    GENERIC_TYPE_ALIASES.set(visibleGenericTypeAliases(module, declaration.file, source));
    try {
      final KotlinJvmSyntaxParser.TypeSyntax topLevelType =
          KotlinJvmSyntaxParser.findTopLevelType(source, topLevelOwner.getSimpleName().toString());
      final boolean companionOwner = isCompanionOwner(declaredOwner, topLevelType, topLevelOwner);
      if (companionOwner) {
        owner = (TypeElement) declaredOwner.getEnclosingElement();
      }
      final KotlinJvmSyntaxParser.TypeSyntax type =
          nestedType(topLevelType, owner, topLevelOwner);
      if (element instanceof TypeElement && type != null && type.nameOffset >= 0) {
        return location(declaration.file, source, type.nameOffset, type.nameLength);
      }
      if (element instanceof TypeElement) {
        return location(declaration.file, source, declaration.offset, declaration.length);
      }
      final ValueClassAbiContext valueClassContext = ValueClassAbiContext.fromSource(source);
      final SourceRange range = type == null
          ? findFacadeMember(source, valueClassContext, element)
          : companionOwner
              ? findMember(type.companionMembers, valueClassContext, element, false)
              : findTypeMember(
                  type, declaration, valueClassContext, element,
                  KotlinJvmSyntaxParser.findTopLevelTypeSyntaxes(source));
      if (range != null) {
        return location(declaration.file, source, range.offset, range.length);
      }
      return type != null && type.nameOffset >= 0
          ? location(declaration.file, source, type.nameOffset, type.nameLength)
          : location(declaration.file, source, declaration.offset, declaration.length);
    } finally {
      if (previousAliases == null) {
        TYPE_ALIASES.remove();
      } else {
        TYPE_ALIASES.set(previousAliases);
      }
      if (previousGenericAliases == null) {
        GENERIC_TYPE_ALIASES.remove();
      } else {
        GENERIC_TYPE_ALIASES.set(previousGenericAliases);
      }
    }
  }

  private static SourceRange findTypeMember(
      KotlinJvmSyntaxParser.TypeSyntax type,
      KotlinTypeDeclaration typeDeclaration,
      ValueClassAbiContext valueClassContext,
      Element element,
      List<KotlinJvmSyntaxParser.TypeSyntax> sourceTypes) {
    if (element.getKind() == ElementKind.CONSTRUCTOR && element instanceof ExecutableElement) {
      if (KotlinAbiSyntheticMembers.isSyntheticConstructor(element)) {
        return null;
      }
      final ExecutableElement executable = (ExecutableElement) element;
      final Set<String> valueClassTypes = valueClassContext.types;
      final SourceRange typeRange = type.nameOffset >= 0
          ? new SourceRange(type.nameOffset, type.nameLength)
          : new SourceRange(typeDeclaration.offset, typeDeclaration.length);
      SourceRange match = null;
      int matches = 0;
      for (KotlinJvmSyntaxParser.ConstructorSyntax constructor : type.secondaryConstructors) {
        if (constructorMatches(
            constructor.parameters, constructor.jvmOverloads, valueClassTypes, executable)) {
          match = new SourceRange(constructor.nameOffset, constructor.nameLength);
          matches++;
        }
      }
      if (type.primaryConstructorPresent
          && primaryConstructorMatches(type, valueClassTypes, executable)) {
        match = typeRange;
        matches++;
      }
      return matches == 1 ? match : typeRange;
    }

    SourceRange range = findMember(type.members, valueClassContext, element, false);
    if (range != null) {
      return range;
    }
    range = findDefaultInterfaceMember(
        type, sourceTypes, valueClassContext, element, new java.util.LinkedHashSet<String>(),
        Collections.<String, String>emptyMap());
    if (range != null) {
      return range;
    }
    range = findConstructorProperty(
        type.constructorParameters, valueClassContext.types, element);
    if (range != null) {
      return range;
    }
    return findMember(type.companionMembers, valueClassContext, element, true);
  }

  private static SourceRange findDefaultInterfaceMember(
      KotlinJvmSyntaxParser.TypeSyntax implementation,
      List<KotlinJvmSyntaxParser.TypeSyntax> sourceTypes,
      ValueClassAbiContext valueClassContext,
      Element element,
      Set<String> visitedContracts,
      Map<String, String> substitutions) {
    if (sourceTypes == null) {
      return null;
    }
    for (KotlinJvmSyntaxParser.SuperTypeSyntax superType : implementation.superTypes) {
      if (superType.constructorInvocation) {
        continue;
      }
      final KotlinJvmTypeProjection.TypeApplication application =
          KotlinJvmTypeProjection.parseTypeApplication(superType.type);
      final String contractName = application == null ? superType.type.trim() : application.rawType;
      if (!TYPE_NAME_PATTERN.matcher(contractName).matches() || !visitedContracts.add(superType.type)) {
        continue;
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
        continue;
      }
      final Map<String, String> contractSubstitutions = new LinkedHashMap<>(substitutions);
      boolean supported = true;
      if (application != null) {
        for (int index = 0; index < application.arguments.size(); index++) {
          final String argument = substituteDefaultInterfaceType(
              application.arguments.get(index), substitutions);
          if (!TYPE_NAME_PATTERN.matcher(argument).matches()) {
            supported = false;
            break;
          }
          contractSubstitutions.put(contract.typeParameters.get(index).name, argument);
        }
      }
      if (!supported) {
        continue;
      }
      final List<KotlinJvmSyntaxParser.MemberSyntax> defaults = new java.util.ArrayList<>();
      for (KotlinJvmSyntaxParser.MemberSyntax member : contract.members) {
        if (member.functionBodyPresent) {
          defaults.add(substituteDefaultInterfaceMember(member, contractSubstitutions));
        }
      }
      final SourceRange range = findMember(defaults, valueClassContext, element, false);
      if (range != null) {
        return range;
      }
      final SourceRange inherited = findDefaultInterfaceMember(
          contract, sourceTypes, valueClassContext, element, visitedContracts, contractSubstitutions);
      if (inherited != null) {
        return inherited;
      }
    }
    return null;
  }

  private static String substituteDefaultInterfaceType(
      String type, Map<String, String> substitutions) {
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

  private static KotlinJvmSyntaxParser.MemberSyntax substituteDefaultInterfaceMember(
      KotlinJvmSyntaxParser.MemberSyntax member, Map<String, String> substitutions) {
    if (substitutions.isEmpty()) {
      return member;
    }
    final List<KotlinJvmSyntaxParser.ParameterSyntax> parameters = new java.util.ArrayList<>();
    for (KotlinJvmSyntaxParser.ParameterSyntax parameter : member.parameterList) {
      parameters.add(new KotlinJvmSyntaxParser.ParameterSyntax(
          parameter.name, substituteDefaultInterfaceType(parameter.type, substitutions),
          parameter.defaultValue, parameter.vararg));
    }
    return new KotlinJvmSyntaxParser.MemberSyntax(
        member.kind, member.declarationText, member.privateMember, member.jvmStatic, member.jvmField,
        member.jvmOverloads, member.suspendFunction, member.jvmSynthetic, member.getterJvmSynthetic,
        member.setterJvmSynthetic, member.jvmName, member.getterJvmName, member.setterJvmName,
        member.name, member.nameOffset, member.nameLength, member.parameters, parameters,
        member.typeParameters, substituteDefaultInterfaceType(member.receiverType, substitutions),
        substituteDefaultInterfaceType(member.declaredType, substitutions), member.mutableProperty,
        member.readOnlyProperty, member.functionBodyPresent);
  }

  private static SourceRange findConstructorProperty(
      List<KotlinJvmSyntaxParser.ConstructorParameterSyntax> parameters,
      Set<String> valueClassTypes,
      Element element) {
    final String javaName = element.getSimpleName().toString();
    for (KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter : parameters) {
      if (!parameter.property || parameter.nameOffset < 0 || parameter.name.isEmpty()
          || isScalarValueClassType(parameter.type, valueClassTypes)) {
        continue;
      }
      final String getter = propertyGetterName(parameter.name, parameter.type);
      final String setter = propertySetterName(parameter.name, parameter.type);
      if (javaName.equals(getter)
          || (parameter.mutableProperty && javaName.equals(setter))) {
        return new SourceRange(parameter.nameOffset, parameter.nameLength);
      }
    }
    return null;
  }
  /**
   * Resolves a type, constructor, or type member inside one Kotlin source file.
   *
   * <p>This package-private entry point exercises the same nested-type, constructor, property, and
   * companion matching used by production navigation, without requiring a module source index.
   */
  static Location findTypeMemberLocation(Path file, String source, Element element) {
    if (file == null || source == null || element == null) {
      return null;
    }
    TypeElement owner = ownerType(element);
    if (owner == null) {
      return null;
    }
    final TypeElement declaredOwner = owner;
    final TypeElement topLevelOwner = topLevelOwner(owner);
    final KotlinJvmSyntaxParser.TypeSyntax topLevelType =
        KotlinJvmSyntaxParser.findTopLevelType(source, topLevelOwner.getSimpleName().toString());
    final boolean companionOwner = isCompanionOwner(declaredOwner, topLevelType, topLevelOwner);
    if (companionOwner) {
      owner = (TypeElement) declaredOwner.getEnclosingElement();
    }
    final KotlinJvmSyntaxParser.TypeSyntax type = nestedType(topLevelType, owner, topLevelOwner);
    if (type == null) {
      return null;
    }
    final Map<String, String> previousAliases = TYPE_ALIASES.get();
    final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
    TYPE_ALIASES.set(collectSimpleTypeAliases(source));
    GENERIC_TYPE_ALIASES.set(collectGenericTypeAliases(source));
    try {
      if (element instanceof TypeElement && type.nameOffset >= 0) {
        return location(file, source, type.nameOffset, type.nameLength);
      }
      final KotlinTypeDeclaration declaration = new KotlinTypeDeclaration(
          file, Math.max(0, type.nameOffset), Math.max(1, type.nameLength));
      final ValueClassAbiContext valueClassContext = ValueClassAbiContext.fromSource(source);
      final SourceRange range = companionOwner
          ? findMember(type.companionMembers, valueClassContext, element, false)
          : findTypeMember(
              type, declaration, valueClassContext, element,
              KotlinJvmSyntaxParser.findTopLevelTypeSyntaxes(source));
      return range == null ? null : location(file, source, range.offset, range.length);
    } finally {
      if (previousAliases == null) {
        TYPE_ALIASES.remove();
      } else {
        TYPE_ALIASES.set(previousAliases);
      }
      if (previousGenericAliases == null) {
        GENERIC_TYPE_ALIASES.remove();
      } else {
        GENERIC_TYPE_ALIASES.set(previousGenericAliases);
      }
    }
  }

  /**
   * Resolves one Java facade member across Kotlin multifile facade parts.
   *
   * <p>Each source part gets an isolated same-file alias context, matching production behavior. A
   * source part only contributes a result when its own declarations identify one unique signature.
   */
  static Location findMultifileFacadeMemberLocation(
      List<Path> files, List<String> sources, Element element) {
    if (files == null || sources == null || element == null || files.size() != sources.size()) {
      return null;
    }
    for (int index = 0; index < files.size(); index++) {
      final Location location = findFacadeMemberLocation(files.get(index), sources.get(index), element);
      if (location != null) {
        return location;
      }
    }
    return null;
  }

  /**
   * Resolves a facade member inside one Kotlin source file.
   *
   * <p>This package-private entry point keeps signature matching independently testable without a
   * {@link ModuleProject}. Production navigation still uses {@link #find(ModuleProject, Element)}
   * so cross-file visibility continues to come from {@link KotlinJvmTypeIndex}.
   */
  static Location findFacadeMemberLocation(Path file, String source, Element element) {
    return findFacadeMemberLocation(
        file, source, element, Collections.emptyMap(), Collections.emptyMap());
  }

  /** Resolves a facade member with aliases supplied by the cross-file visibility index. */
  static Location findFacadeMemberLocation(
      Path file,
      String source,
      Element element,
      Map<String, String> visibleDirectAliases,
      Map<String, KotlinJvmTypeIndex.GenericTypeAlias> visibleGenericAliases) {
    if (file == null || source == null || element == null) {
      return null;
    }
    final Map<String, String> directAliases = collectSimpleTypeAliases(source);
    if (visibleDirectAliases != null) {
      for (Map.Entry<String, String> entry : visibleDirectAliases.entrySet()) {
        directAliases.putIfAbsent(entry.getKey(), entry.getValue());
      }
    }
    final Map<String, GenericTypeAlias> genericAliases = collectGenericTypeAliases(source);
    if (visibleGenericAliases != null) {
      for (Map.Entry<String, KotlinJvmTypeIndex.GenericTypeAlias> entry
          : visibleGenericAliases.entrySet()) {
        final KotlinJvmTypeIndex.GenericTypeAlias alias = entry.getValue();
        genericAliases.putIfAbsent(entry.getKey(), new GenericTypeAlias(
            alias.parameters,
            alias.targetRawType + "<" + String.join(", ", alias.targetArguments) + ">"));
      }
    }
    final Map<String, String> previousAliases = TYPE_ALIASES.get();
    final Map<String, GenericTypeAlias> previousGenericAliases = GENERIC_TYPE_ALIASES.get();
    TYPE_ALIASES.set(directAliases);
    GENERIC_TYPE_ALIASES.set(genericAliases);
    try {
      final SourceRange range = findFacadeMember(
          source, ValueClassAbiContext.fromSource(source), element);
      return range == null ? null : location(file, source, range.offset, range.length);
    } finally {
      if (previousAliases == null) {
        TYPE_ALIASES.remove();
      } else {
        TYPE_ALIASES.set(previousAliases);
      }
      if (previousGenericAliases == null) {
        GENERIC_TYPE_ALIASES.remove();
      } else {
        GENERIC_TYPE_ALIASES.set(previousGenericAliases);
      }
    }
  }

  private static SourceRange findFacadeMember(
      String source, ValueClassAbiContext valueClassContext, Element element) {
    final List<KotlinJvmSyntaxParser.MemberSyntax> members =
        KotlinJvmSyntaxParser.findTopLevelMembers(source);
    return members == null ? null : findMember(members, valueClassContext, element, false);
  }


  private static SourceRange findMember(
      List<KotlinJvmSyntaxParser.MemberSyntax> members,
      ValueClassAbiContext valueClassContext,
      Element element,
      boolean requireJvmStatic) {
    final String javaName = element.getSimpleName().toString();
    final ExecutableElement executable = element instanceof ExecutableElement
        ? (ExecutableElement) element
        : null;
    SourceRange match = null;
    int matches = 0;
    for (KotlinJvmSyntaxParser.MemberSyntax member : members) {
      if (member.name == null || member.nameOffset < 0 || member.privateMember || member.jvmSynthetic) {
        continue;
      }
      if (requireJvmStatic && !member.jvmStatic && !member.jvmField) {
        continue;
      }
      final String jvmMemberName = member.jvmName == null ? member.name : member.jvmName;
      final boolean nameMatches = javaName.equals(jvmMemberName);
      final boolean matchesElement = member.function()
          ? nameMatches && functionSignatureMatches(
              member, executable, valueClassContext.underlyingTypes)
          : propertyJavaNameMatches(
              member, javaName, element, isInterfaceMember(element), valueClassContext.types,
               valueClassContext.underlyingTypes);
      if (matchesElement) {
        match = new SourceRange(member.nameOffset, member.nameLength);
        matches++;
      }
    }
    return matches == 1 ? match : null;
  }

  private static boolean functionSignatureMatches(
      KotlinJvmSyntaxParser.MemberSyntax member,
      ExecutableElement executable,
      Map<String, String> valueClassUnderlyingTypes) {
    if (executable == null || executable.getReturnType() == null) {
      return false;
    }
    final int receiverCount = member.receiverType == null ? 0 : 1;
    final int parameterCount = executable.getParameters().size();
    final int kotlinParameterCount = parameterCount - receiverCount;
    final int fullCount = member.parameterList.size();
    if (kotlinParameterCount < 0
        || kotlinParameterCount != fullCount
            && (!member.jvmOverloads
                || kotlinParameterCount < trailingDefaultStart(member.parameterList)
                || kotlinParameterCount >= fullCount)) {
      return false;
    }
    if (receiverCount == 1
        && !functionParameterTypeCompatible(
            member.receiverType, false, executable.getParameters().get(0).asType().toString())) {
      return false;
    }
    for (int index = 0; index < kotlinParameterCount; index++) {
      final KotlinJvmSyntaxParser.ParameterSyntax parameter = member.parameterList.get(index);
      final boolean vararg = parameter.vararg && index == member.parameterList.size() - 1;
      final String javacType = executable.getParameters().get(index + receiverCount).asType().toString();
      if (!functionParameterTypeCompatible(parameter.type, vararg, javacType)) {
        return false;
      }
    }
    return functionReturnTypeMatches(member, executable, valueClassUnderlyingTypes);
  }

  private static boolean functionReturnTypeMatches(
      KotlinJvmSyntaxParser.MemberSyntax member,
      ExecutableElement executable,
      Map<String, String> valueClassUnderlyingTypes) {
    // Kotlin's non-null Unit is JVM void only in return position; do not put this
    // mapping in navigationJavaType because Unit remains kotlin.Unit in parameter position.
    if ("Unit".equals(member.declaredType) || "kotlin.Unit".equals(member.declaredType)) {
      return "void".equals(executable.getReturnType().toString());
    }
    // As with property getters, unresolved type variables and complex generic expressions cannot
    // safely disprove a candidate. Concrete return types must match so JVM bridge methods do not
    // navigate as independent Kotlin source declarations.
    if (navigationJavaType(member.declaredType) == null) {
      return true;
    }
    return extensionAccessorParameterMatches(
        member.declaredType,
        executable.getReturnType().toString(),
        member.jvmName != null,
        valueClassUnderlyingTypes);
  }

  private static boolean propertyJavaNameMatches(
      KotlinJvmSyntaxParser.MemberSyntax member,
      String javaName,
      Element element,
      boolean interfaceMember,
      Set<String> valueClassTypes,
      Map<String, String> valueClassUnderlyingTypes) {
    if (element.getKind() == ElementKind.FIELD && member.receiverType == null
        && javaName.equals(member.name)) {
      // A public Kotlin @JvmField has no accessor signature to disambiguate it. Preserve the
      // existing unknown-type fallback, but compare every type we can normalize so an invariant
      // Java field cannot navigate to a Kotlin wildcard field (or vice versa).
      return functionParameterTypeCompatible(member.declaredType, false, element.asType().toString());
    }
    if (element.getKind() != ElementKind.METHOD || member.name.isEmpty()
        || !(element instanceof ExecutableElement)) {
      return false;
    }
    final ExecutableElement executable = (ExecutableElement) element;
    final String getter = member.getterJvmName == null
        ? propertyGetterName(member.name, member.declaredType)
        : member.getterJvmName;
    final String setter = member.setterJvmName == null
        ? propertySetterName(member.name, member.declaredType)
        : member.setterJvmName;
    final boolean extensionProperty = member.receiverType != null;
    // Kotlin 2.1.0 rejects @get/@set:JvmName on interface member extension properties.
    if (interfaceMember && extensionProperty
        && (member.getterJvmName != null || member.setterJvmName != null)) {
      return false;
    }
    final boolean propertyProjectable = canNavigateValueClassProperty(
        member.getterJvmName,
        member.setterJvmName,
        member.mutableProperty,
        member.declaredType,
        valueClassTypes);
    final boolean getterProjectable = extensionProperty
        ? canNavigateValueClassExtensionGetter(
            member.getterJvmName, member.receiverType, member.declaredType, interfaceMember, valueClassTypes)
        : propertyProjectable;
    final boolean setterProjectable = member.mutableProperty && (extensionProperty
        ? canNavigateValueClassExtensionSetter(
            member.setterJvmName, member.receiverType, member.declaredType, interfaceMember, valueClassTypes)
        : propertyProjectable);
    final boolean getterMatches = getterProjectable && !member.getterJvmSynthetic
        && javaName.equals(getter)
        && propertyGetterReturnTypeMatches(
            member, executable, extensionProperty || member.getterJvmName != null, valueClassUnderlyingTypes)
        && propertyAccessorParametersMatch(
            member, executable, false, member.getterJvmName != null, valueClassUnderlyingTypes);
    final boolean setterMatches = setterProjectable && !member.setterJvmSynthetic
        && javaName.equals(setter) && propertyAccessorParametersMatch(
            member, executable, true, member.setterJvmName != null, valueClassUnderlyingTypes);
    return getterMatches || setterMatches;
  }

  private static boolean propertyGetterReturnTypeMatches(
      KotlinJvmSyntaxParser.MemberSyntax member,
      ExecutableElement executable,
      boolean allowExplicitValueClassUnderlying,
      Map<String, String> valueClassUnderlyingTypes) {
    if (executable == null || executable.getReturnType() == null) {
      return false;
    }
    // Unresolved type variables and complex generic expressions cannot safely disprove a match;
    // retain the existing conservative candidate behavior for those declarations.
    if (navigationJavaType(member.declaredType) == null) {
      return true;
    }
    return extensionAccessorParameterMatches(
        member.declaredType,
        executable.getReturnType().toString(),
        allowExplicitValueClassUnderlying,
        valueClassUnderlyingTypes);
  }

  private static boolean propertyAccessorParametersMatch(
      KotlinJvmSyntaxParser.MemberSyntax member,
      ExecutableElement executable,
      boolean setter,
      boolean allowExplicitValueClassUnderlying,
      Map<String, String> valueClassUnderlyingTypes) {
    final int receiverCount = member.receiverType == null ? 0 : 1;
    final int expectedCount = receiverCount + (setter ? 1 : 0);
    if (executable.getParameters().size() != expectedCount) return false;
    if (receiverCount == 1 && !extensionAccessorParameterMatches(
        member.receiverType, executable.getParameters().get(0).asType().toString(),
        allowExplicitValueClassUnderlying, valueClassUnderlyingTypes)) {
      return false;
    }
    return !setter || extensionAccessorParameterMatches(
        member.declaredType, executable.getParameters().get(receiverCount).asType().toString(),
        allowExplicitValueClassUnderlying, valueClassUnderlyingTypes);
  }

  private static boolean extensionAccessorParameterMatches(
      String kotlinType,
      String javaType,
      boolean allowExplicitValueClassUnderlying,
      Map<String, String> valueClassUnderlyingTypes) {
    if (parameterTypeMatches(kotlinType, false, javaType)) {
      return true;
    }
    if (!allowExplicitValueClassUnderlying || kotlinType == null
        || kotlinType.trim().endsWith("?")) {
      return false;
    }
    final String simpleName = kotlinType.trim();
    final String underlying = valueClassUnderlyingTypes.get(simpleName);
    return underlying != null && parameterTypeMatches(underlying, false, javaType);
  }

  private static String propertyGetterName(String name, String kotlinType) {
    return isBooleanIsProperty(name, kotlinType)
        ? name
        : "get" + propertyAccessorSuffix(name);
  }

  private static String propertySetterName(String name, String kotlinType) {
    return isBooleanIsProperty(name, kotlinType)
        ? "set" + name.substring(2)
        : "set" + propertyAccessorSuffix(name);
  }

  private static boolean isBooleanIsProperty(String name, String kotlinType) {
    if (name == null || name.length() < 3 || name.charAt(0) != 'i' || name.charAt(1) != 's'
        || !Character.isUpperCase(name.charAt(2)) || kotlinType == null) {
      return false;
    }
    final String type = kotlinType.trim();
    return "Boolean".equals(type) || "kotlin.Boolean".equals(type);
  }

  private static String propertyAccessorSuffix(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  private static boolean primaryConstructorMatches(
      KotlinJvmSyntaxParser.TypeSyntax type,
      Set<String> valueClassTypes,
      ExecutableElement executable) {
    for (KotlinJvmSyntaxParser.ConstructorParameterSyntax parameter : type.constructorParameters) {
      if (isScalarValueClassType(parameter.type, valueClassTypes)) {
        return false;
      }
    }
    final int parameterCount = executable.getParameters().size();
    final int fullCount = type.constructorParameters.size();
    if (parameterCount != fullCount
        && (!type.constructorJvmOverloads
            || parameterCount < trailingDefaultStart(type.constructorParameters)
            || parameterCount >= fullCount)) {
      return false;
    }
    for (int index = 0; index < parameterCount; index++) {
      if (!parameterTypeMatches(
          type.constructorParameters.get(index).type,
          type.constructorParameters.get(index).vararg,
          executable.getParameters().get(index).asType().toString())) {
        return false;
      }
    }
    return true;
  }

  private static boolean constructorMatches(
      List<KotlinJvmSyntaxParser.ParameterSyntax> parameters,
      boolean jvmOverloads,
      Set<String> valueClassTypes,
      ExecutableElement executable) {
    for (KotlinJvmSyntaxParser.ParameterSyntax parameter : parameters) {
      if (isScalarValueClassType(parameter.type, valueClassTypes)) {
        return false;
      }
    }
    final int parameterCount = executable.getParameters().size();
    if (parameterCount != parameters.size()
        && (!jvmOverloads
            || parameterCount < trailingDefaultStart(parameters)
            || parameterCount >= parameters.size())) {
      return false;
    }
    for (int index = 0; index < parameterCount; index++) {
      if (!parameterTypeMatches(
          parameters.get(index).type,
          parameters.get(index).vararg,
          executable.getParameters().get(index).asType().toString())) {
        return false;
      }
    }
    return true;
  }

  /** Mirrors the generator's compiler-backed extension accessor eligibility boundary. */
  private static boolean canNavigateValueClassExtensionGetter(
      String getterJvmName,
      String receiverType,
      String returnType,
      boolean interfaceMember,
      Set<String> valueClassTypes) {
    if (getterJvmName != null || isScalarValueClassType(receiverType, valueClassTypes)) {
      return getterJvmName != null;
    }
    return !containsValueClassType(returnType, valueClassTypes)
        || !interfaceMember && isDirectValueClassType(returnType, valueClassTypes);
  }

  private static boolean canNavigateValueClassExtensionSetter(
      String setterJvmName,
      String receiverType,
      String valueType,
      boolean interfaceMember,
      Set<String> valueClassTypes) {
    return setterJvmName != null
        || !isScalarValueClassType(receiverType, valueClassTypes)
            && !isScalarValueClassType(valueType, valueClassTypes);
  }

  /** Mirrors the generator's scalar-property accessor eligibility boundary. */
  private static boolean canNavigateValueClassProperty(
      String getterJvmName,
      String setterJvmName,
      boolean mutable,
      String type,
      Set<String> valueClassTypes) {
    return !isScalarValueClassType(type, valueClassTypes)
        || getterJvmName != null && (!mutable || setterJvmName != null);
  }

  private static boolean containsValueClassType(
      String kotlinType, Set<String> valueClassTypes) {
    if (kotlinType == null || valueClassTypes == null || valueClassTypes.isEmpty()) {
      return false;
    }
    for (String valueClassType : valueClassTypes) {
      if (Pattern.compile("(?:^|[^A-Za-z0-9_$.])" + Pattern.quote(valueClassType)
          + "(?:$|[^A-Za-z0-9_$])").matcher(kotlinType).find()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDirectValueClassType(
      String kotlinType, Set<String> valueClassTypes) {
    return kotlinType != null && !kotlinType.trim().endsWith("?")
        && isScalarValueClassType(kotlinType, valueClassTypes);
  }

  private static Map<String, String> collectValueClassUnderlyingTypes(String source) {
    final Map<String, String> result = new LinkedHashMap<>();
    if (source == null || source.isEmpty()) {
      return result;
    }
    final Matcher matcher = VALUE_CLASS_UNDERLYING_TYPE_PATTERN.matcher(source);
    while (matcher.find()) {
      result.put(matcher.group(1), matcher.group(2).trim());
    }
    return result;
  }

  private static final class ValueClassAbiContext {
    final Map<String, String> underlyingTypes;
    final Set<String> types;

    private ValueClassAbiContext(Map<String, String> underlyingTypes) {
      this.underlyingTypes = Collections.unmodifiableMap(underlyingTypes);
      this.types = Collections.unmodifiableSet(new LinkedHashSet<>(underlyingTypes.keySet()));
    }

    static ValueClassAbiContext fromSource(String source) {
      return new ValueClassAbiContext(collectValueClassUnderlyingTypes(source));
    }
  }

  private static boolean isScalarValueClassType(String kotlinType, Set<String> valueClassTypes) {
    if (kotlinType == null || valueClassTypes == null || valueClassTypes.isEmpty()) {
      return false;
    }
    String type = kotlinType.trim();
    if (type.endsWith("?")) {
      type = type.substring(0, type.length() - 1).trim();
    }
    if (type.indexOf('<') >= 0 || type.indexOf('>') >= 0
        || type.indexOf('(') >= 0 || type.indexOf(')') >= 0 || type.indexOf('[') >= 0) {
      return false;
    }
    final int separator = type.lastIndexOf('.');
    final String simpleName = separator < 0 ? type : type.substring(separator + 1);
    return valueClassTypes.contains(simpleName);
  }

  private static int trailingDefaultStart(
      List<? extends Object> parameters) {
    int index = parameters.size();
    while (index > 0) {
      final Object parameter = parameters.get(index - 1);
      final boolean defaultValue = parameter instanceof KotlinJvmSyntaxParser.ParameterSyntax
          ? ((KotlinJvmSyntaxParser.ParameterSyntax) parameter).defaultValue
          : ((KotlinJvmSyntaxParser.ConstructorParameterSyntax) parameter).defaultValue;
      if (!defaultValue) break;
      index--;
    }
    return index;
  }

  private static boolean functionParameterTypeCompatible(
      String kotlinType, boolean vararg, String javaType) {
    // Unknown complex Kotlin types cannot safely disprove a candidate. They remain compatible here;
    // findMember still refuses navigation when more than one source declaration survives.
    return navigationJavaType(kotlinType) == null
        || parameterTypeMatches(kotlinType, vararg, javaType);
  }

  private static boolean parameterTypeMatches(
      String kotlinType, boolean vararg, String javaType) {
    final String normalized = navigationJavaType(kotlinType);
    if (normalized == null) {
      return false;
    }
    final String expected = vararg ? normalized + "[]" : normalized;
    if (expected.equals(javaType)
        || (expected.indexOf('.') < 0 && javaType.endsWith("." + expected))) {
      return true;
    }
    // Generic structure must already compare exactly. A whole-type simple-name fallback would turn
    // List<? extends String> and List<String> into the same trailing `String>` token.
    if (expected.indexOf('<') >= 0 || javaType.indexOf('<') >= 0) {
      return false;
    }
    // javac/rendering variants may use `String` where the navigator normalized an alias to
    // `java.lang.String` (or vice versa). Compare simple names as a final exact JVM-type check.
    final int expectedSeparator = expected.lastIndexOf('.');
    final int actualSeparator = javaType.lastIndexOf('.');
    final String expectedSimple = expectedSeparator < 0 ? expected : expected.substring(expectedSeparator + 1);
    final String actualSimple = actualSeparator < 0 ? javaType : javaType.substring(actualSeparator + 1);
    return expectedSimple.equals(actualSimple);
  }

  private static String navigationJavaTypeArgument(String kotlinArgument) {
    String argument = kotlinArgument == null ? "" : kotlinArgument.trim();
    if ("*".equals(argument)) {
      return "?";
    }
    final boolean forceWildcard = argument.startsWith("@JvmWildcard ")
        || argument.startsWith("@kotlin.jvm.JvmWildcard ");
    final boolean suppressWildcard = argument.startsWith("@JvmSuppressWildcards ")
        || argument.startsWith("@kotlin.jvm.JvmSuppressWildcards ");
    if (forceWildcard || suppressWildcard) {
      argument = argument.substring(argument.indexOf(' ') + 1).trim();
    }
    final boolean out = argument.startsWith("out ");
    final boolean in = argument.startsWith("in ");
    if (out || in) {
      argument = argument.substring(out ? 4 : 3).trim();
    }
    final String mapped = navigationJavaType(argument);
    if (mapped == null) {
      return null;
    }
    final String boxed = boxedNavigationType(mapped);
    if (in) {
      return "? super " + boxed;
    }
    return (out || forceWildcard) && !suppressWildcard ? "? extends " + boxed : boxed;
  }

  private static String navigationJavaType(String kotlinType) {
    if (kotlinType == null) return null;
    String type = kotlinType.trim();
    final boolean nullable = type.endsWith("?");
    if (nullable) type = type.substring(0, type.length() - 1).trim();
    final Map<String, String> aliases = TYPE_ALIASES.get();
    if (aliases != null) {
      final String expandedAlias = aliases.get(type);
      if (expandedAlias != null) {
        type = expandedAlias;
      }
    }
    final KotlinJvmTypeProjection.TypeApplication application =
        KotlinJvmTypeProjection.parseTypeApplication(type);
    if (application != null) {
      final String expandedAlias = expandGenericTypeAlias(application);
      if (expandedAlias != null) {
        return navigationJavaType(expandedAlias);
      }
      if ("Array".equals(application.rawType) || "kotlin.Array".equals(application.rawType)) {
        if (application.arguments.size() != 1) return null;
        String element = application.arguments.get(0).trim();
        if ("*".equals(element)) return null;
        if (element.startsWith("out ")) element = element.substring(4).trim();
        else if (element.startsWith("in ")) element = element.substring(3).trim();
        final String javaElement = navigationJavaType(element);
        return javaElement == null ? null : boxedNavigationType(javaElement) + "[]";
      }
      final String rawType = KotlinJvmTypeProjection.javaCollectionType(application.rawType);
      if (rawType == null || application.arguments.isEmpty()) {
        return null;
      }
      final List<String> arguments = new ArrayList<>();
      for (String argument : application.arguments) {
        final String javaArgument = navigationJavaTypeArgument(argument);
        if (javaArgument == null) {
          return null;
        }
        arguments.add(javaArgument);
      }
      return rawType + "<" + String.join(",", arguments) + ">";
    }
    switch (type) {
      case "Byte": case "kotlin.Byte": return nullable ? "java.lang.Byte" : "byte";
      case "Short": case "kotlin.Short": return nullable ? "java.lang.Short" : "short";
      case "Int": case "kotlin.Int": return nullable ? "java.lang.Integer" : "int";
      case "Long": case "kotlin.Long": return nullable ? "java.lang.Long" : "long";
      case "Float": case "kotlin.Float": return nullable ? "java.lang.Float" : "float";
      case "Double": case "kotlin.Double": return nullable ? "java.lang.Double" : "double";
      case "Boolean": case "kotlin.Boolean": return nullable ? "java.lang.Boolean" : "boolean";
      case "Char": case "kotlin.Char": return nullable ? "java.lang.Character" : "char";
      case "String": case "kotlin.String": return "java.lang.String";
      case "CharSequence": case "kotlin.CharSequence": return "java.lang.CharSequence";
      case "IntArray": case "kotlin.IntArray": return "int[]";
      case "LongArray": case "kotlin.LongArray": return "long[]";
      case "BooleanArray": case "kotlin.BooleanArray": return "boolean[]";
      case "CharArray": case "kotlin.CharArray": return "char[]";
      case "ByteArray": case "kotlin.ByteArray": return "byte[]";
      case "ShortArray": case "kotlin.ShortArray": return "short[]";
      case "FloatArray": case "kotlin.FloatArray": return "float[]";
      case "DoubleArray": case "kotlin.DoubleArray": return "double[]";
      default:
        if (type.startsWith("Array<") && type.endsWith(">")) {
          final String element = navigationJavaType(
              type.substring("Array<".length(), type.length() - 1).trim());
          return element == null ? null : element + "[]";
        }
        if (type.matches("[A-Z][A-Za-z0-9_$]*")) {
          return type;
        }
        return null;
    }
  }

  private static String expandGenericTypeAlias(
      KotlinJvmTypeProjection.TypeApplication application) {
    final Map<String, GenericTypeAlias> aliases = GENERIC_TYPE_ALIASES.get();
    final GenericTypeAlias alias = aliases == null ? null : aliases.get(application.rawType);
    return alias == null ? null : KotlinJvmTypeProjection.expandGenericAlias(
        application, alias.parameters, alias.target, TYPE_NAME_PATTERN);
  }

  // Collection-name normalization is shared with the ABI generator.

  private static String boxedNavigationType(String type) {
    switch (type) {
      case "byte": return "java.lang.Byte";
      case "short": return "java.lang.Short";
      case "int": return "java.lang.Integer";
      case "long": return "java.lang.Long";
      case "float": return "java.lang.Float";
      case "double": return "java.lang.Double";
      case "boolean": return "java.lang.Boolean";
      case "char": return "java.lang.Character";
      default: return type;
    }
  }

  // Type-application parsing is shared with the ABI generator.

  // Generic argument splitting is shared with generation and indexing.

  private static Map<String, String> visibleTypeAliases(
      ModuleProject module, Path consumerFile, String source) {
    final Map<String, String> aliases = new LinkedHashMap<>(collectSimpleTypeAliases(source));
    for (Map.Entry<String, String> alias :
        KotlinJvmTypeIndex.visibleDirectTypeAliases(module, consumerFile).entrySet()) {
      aliases.putIfAbsent(alias.getKey(), alias.getValue());
    }
    return aliases;
  }

  private static Map<String, GenericTypeAlias> visibleGenericTypeAliases(
      ModuleProject module, Path consumerFile, String source) {
    final Map<String, GenericTypeAlias> aliases = collectGenericTypeAliases(source);
    for (Map.Entry<String, KotlinJvmTypeIndex.GenericTypeAlias> entry
        : KotlinJvmTypeIndex.visibleGenericTypeAliases(module, consumerFile).entrySet()) {
      final KotlinJvmTypeIndex.GenericTypeAlias value = entry.getValue();
      aliases.putIfAbsent(entry.getKey(), new GenericTypeAlias(
          value.parameters,
          value.targetRawType + "<" + String.join(", ", value.targetArguments) + ">"));
    }
    return aliases;
  }

  private static Map<String, GenericTypeAlias> collectGenericTypeAliases(String source) {
    final Map<String, GenericTypeAlias> aliases = new LinkedHashMap<>();
    final Matcher matcher = GENERIC_TYPE_ALIAS_PATTERN.matcher(source);
    while (matcher.find()) {
      final List<String> parameters = new ArrayList<>();
      for (String parameter : KotlinJvmTypeProjection.splitTopLevelArguments(matcher.group(2))) {
        final String trimmed = parameter.trim();
        if (!TYPE_NAME_PATTERN.matcher(trimmed).matches()) {
          parameters.clear();
          break;
        }
        parameters.add(trimmed);
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
      for (String argument : targetApplication.arguments) {
        if (!TYPE_NAME_PATTERN.matcher(argument.trim()).matches()) {
          valid = false;
          break;
        }
      }
      if (valid) {
        aliases.putIfAbsent(matcher.group(1), new GenericTypeAlias(parameters, target));
      }
    }
    return aliases;
  }

  private static Map<String, String> collectSimpleTypeAliases(String source) {
    final Map<String, String> aliases = new LinkedHashMap<>();
    final Matcher matcher = TYPE_ALIAS_PATTERN.matcher(source);
    while (matcher.find()) {
      final String name = matcher.group(1);
      final String target = matcher.group(2).trim();
      if (target.endsWith("?") || target.indexOf("->") >= 0 || target.indexOf('&') >= 0
          || target.indexOf('|') >= 0 || target.indexOf('(') >= 0 || target.indexOf(')') >= 0) {
        continue;
      }
      aliases.put(name, target);
    }
    aliases.values().removeIf(aliases::containsKey);
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

  private static boolean isCompanionOwner(
      TypeElement owner,
      KotlinJvmSyntaxParser.TypeSyntax topLevelType,
      TypeElement topLevelOwner) {
    if (owner == null || topLevelType == null || owner.equals(topLevelOwner)
        || !(owner.getEnclosingElement() instanceof TypeElement)) {
      return false;
    }
    final TypeElement enclosing = (TypeElement) owner.getEnclosingElement();
    final KotlinJvmSyntaxParser.TypeSyntax enclosingSyntax =
        nestedType(topLevelType, enclosing, topLevelOwner);
    return enclosingSyntax != null && enclosingSyntax.companionBody != null
        && owner.getSimpleName().contentEquals(enclosingSyntax.companionName);
  }

  private static TypeElement topLevelOwner(TypeElement owner) {
    TypeElement current = owner;
    while (current.getEnclosingElement() instanceof TypeElement) {
      current = (TypeElement) current.getEnclosingElement();
    }
    return current;
  }

  private static KotlinJvmSyntaxParser.TypeSyntax nestedType(
      KotlinJvmSyntaxParser.TypeSyntax topLevel,
      TypeElement owner,
      TypeElement topLevelOwner) {
    if (topLevel == null || owner.equals(topLevelOwner)) {
      return topLevel;
    }
    final java.util.ArrayList<String> names = new java.util.ArrayList<>();
    Element current = owner;
    while (current instanceof TypeElement && !current.equals(topLevelOwner)) {
      names.add(0, current.getSimpleName().toString());
      current = current.getEnclosingElement();
    }
    KotlinJvmSyntaxParser.TypeSyntax result = topLevel;
    for (String name : names) {
      KotlinJvmSyntaxParser.TypeSyntax match = null;
      for (KotlinJvmSyntaxParser.TypeSyntax nested : result.nestedTypes) {
        if (name.equals(nested.name)) {
          match = nested;
          break;
        }
      }
      if (match == null) {
        return null;
      }
      result = match;
    }
    return result;
  }

  private static boolean isInterfaceMember(Element element) {
    final TypeElement owner = ownerType(element);
    return owner != null && owner.getKind() == ElementKind.INTERFACE;
  }

  private static TypeElement ownerType(Element element) {
    Element current = element;
    while (current != null && !(current instanceof TypeElement)) {
      current = current.getEnclosingElement();
    }
    return current instanceof TypeElement ? (TypeElement) current : null;
  }

  private static Location location(Path file, String source, int offset, int length) {
    final Position start = positionAt(source, offset);
    final Position end = positionAt(source, offset + Math.max(1, length));
    return new Location(file, new Range(start, end));
  }

  private static Position positionAt(String source, int offset) {
    final int bounded = Math.max(0, Math.min(offset, source.length()));
    int line = 0;
    int lineStart = 0;
    for (int index = 0; index < bounded; index++) {
      if (source.charAt(index) == '\n') {
        line++;
        lineStart = index + 1;
      }
    }
    return new Position(line, bounded - lineStart);
  }

  private static final class SourceRange {
    final int offset;
    final int length;

    SourceRange(int offset, int length) {
      this.offset = offset;
      this.length = length;
    }
  }
}