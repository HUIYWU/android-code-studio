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
package com.tom.rv2ide.lsp.java.utils

import com.tom.rv2ide.projects.ModuleProject
import java.io.File
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import jdkx.lang.model.SourceVersion
import jdkx.lang.model.element.ElementKind
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.type.ArrayType
import jdkx.lang.model.type.DeclaredType
import jdkx.lang.model.type.ExecutableType
import jdkx.lang.model.type.TypeKind
import jdkx.lang.model.type.TypeMirror
import jdkx.lang.model.type.TypeVariable
import openjdk.tools.classfile.AccessFlags
import openjdk.tools.classfile.Attribute
import openjdk.tools.classfile.ClassFile
import openjdk.tools.classfile.Code_attribute
import openjdk.tools.classfile.LocalVariableTable_attribute
import openjdk.tools.classfile.Method
import openjdk.tools.classfile.MethodParameters_attribute

/**
 * Restores parameter names retained in external class files. The resolver is deliberately
 * all-or-nothing: incomplete or unsafe metadata never replaces javac's original names.
 */
object ExternalMethodParameterNameResolver {

  private data class LookupKey(
      val classpaths: String,
      val ownerBinaryName: String,
      val methodName: String,
      val descriptor: String,
  )

  private val resolvedNames = ConcurrentHashMap<LookupKey, List<String>>()

  @JvmStatic
  fun resolve(
      module: ModuleProject?,
      method: ExecutableElement,
  ): List<String>? {
    if (module == null || method.kind != ElementKind.METHOD) {
      return null
    }

    val owner = method.enclosingElement as? TypeElement ?: return null
    val ownerBinaryName = binaryName(owner) ?: return null
    val declaredType = method.asType() as? ExecutableType ?: return null
    val descriptor = methodDescriptor(declaredType) ?: return null
    val classpaths = module.getCompileClasspaths().filter(File::exists).toList()
    if (classpaths.isEmpty()) {
      return null
    }

    val key = LookupKey(
        classpaths.joinToString(File.pathSeparator) { it.absolutePath },
        ownerBinaryName,
        method.simpleName.toString(),
        descriptor,
    )
    return resolvedNames.computeIfAbsent(key) {
      resolveFromClasspaths(classpaths, ownerBinaryName, method.simpleName.toString(), descriptor)
        ?: emptyList()
    }.takeIf(List<String>::isNotEmpty)
  }

  private fun resolveFromClasspaths(
      classpaths: List<File>,
      ownerBinaryName: String,
      methodName: String,
      descriptor: String,
  ): List<String>? {
    val classEntry = ownerBinaryName.replace('.', '/') + ".class"
    for (classpath in classpaths) {
      val names =
          try {
            when {
              classpath.isDirectory -> File(classpath, classEntry).takeIf(File::isFile)?.inputStream()?.use {
                resolveFromClassFile(it, methodName, descriptor)
              }
              classpath.isFile && classpath.extension.equals("jar", ignoreCase = true) -> {
                JarFile(classpath).use { jar ->
                  jar.getJarEntry(classEntry)?.let { entry ->
                    jar.getInputStream(entry).use { resolveFromClassFile(it, methodName, descriptor) }
                  }
                }
              }
              else -> null
            }
          } catch (_: Throwable) {
            null
          }
      if (names != null) {
        return names
      }
    }
    return null
  }

  private fun resolveFromClassFile(input: InputStream, methodName: String, descriptor: String): List<String>? {
    val classFile = try {
      ClassFile.read(input)
    } catch (_: Throwable) {
      return null
    }
    val target = classFile.methods.firstOrNull { method ->
      try {
        method.getName(classFile.constant_pool) == methodName &&
            method.descriptor.getValue(classFile.constant_pool) == descriptor &&
            !method.access_flags.`is`(AccessFlags.ACC_SYNTHETIC) &&
            !method.access_flags.`is`(AccessFlags.ACC_BRIDGE)
      } catch (_: Throwable) {
        false
      }
    } ?: return null

    val parameterDescriptors = parameterDescriptors(descriptor) ?: return null
    return methodParameters(classFile, target, parameterDescriptors.size)
        ?: localVariableTable(classFile, target, parameterDescriptors)
  }

  private fun methodParameters(classFile: ClassFile, method: Method, expectedCount: Int): List<String>? {
    val attribute = method.attributes.get(Attribute.MethodParameters) as? MethodParameters_attribute ?: return null
    if (attribute.method_parameter_table_length != expectedCount) {
      return null
    }
    return attribute.method_parameter_table.map { entry ->
      if (entry.name_index == 0) return null
      try {
        classFile.constant_pool.getUTF8Value(entry.name_index)
      } catch (_: Throwable) {
        return null
      }
    }.takeIf(::areUsableNames)
  }

  private fun localVariableTable(
      classFile: ClassFile,
      method: Method,
      parameterDescriptors: List<String>,
  ): List<String>? {
    val code = method.attributes.get(Attribute.Code) as? Code_attribute ?: return null
    val table = code.attributes.get(Attribute.LocalVariableTable) as? LocalVariableTable_attribute ?: return null
    var slot = if (method.access_flags.`is`(AccessFlags.ACC_STATIC)) 0 else 1
    val names = ArrayList<String>(parameterDescriptors.size)
    for (descriptor in parameterDescriptors) {
      val entry = table.local_variable_table
          .asSequence()
          .filter { it.index == slot && it.descriptor_index != 0 }
          .filter {
            try {
              classFile.constant_pool.getUTF8Value(it.descriptor_index) == descriptor
            } catch (_: Throwable) {
              false
            }
          }
          .sortedWith(compareBy<LocalVariableTable_attribute.Entry> { it.start_pc }.thenByDescending { it.length })
          .firstOrNull() ?: return null
      val name = try {
        classFile.constant_pool.getUTF8Value(entry.name_index)
      } catch (_: Throwable) {
        return null
      }
      names.add(name)
      slot += if (descriptor == "J" || descriptor == "D") 2 else 1
    }
    return names.takeIf(::areUsableNames)
  }

  private fun areUsableNames(names: List<String>): Boolean =
      names.isNotEmpty() &&
          names.size == names.toSet().size &&
          names.none { it.matches(Regex("arg\\d+")) } &&
          names.all { SourceVersion.isIdentifier(it) && !SourceVersion.isKeyword(it) }

  private fun binaryName(type: TypeElement): String? {
    val names = ArrayDeque<String>()
    var current: TypeElement? = type
    while (current != null) {
      names.addFirst(current.simpleName.toString())
      current = current.enclosingElement as? TypeElement
    }
    val packageElement = type.enclosingElement
    var enclosing = packageElement
    while (enclosing is TypeElement) {
      enclosing = enclosing.enclosingElement
    }
    val packageName = enclosing.toString()
    val binaryTypeName = names.joinToString("$")
    return if (packageName.isEmpty()) binaryTypeName else "$packageName.$binaryTypeName"
  }

  private fun methodDescriptor(type: ExecutableType): String? {
    val parameters = type.parameterTypes.map { typeDescriptor(it) ?: return null }
    val returnType = typeDescriptor(type.returnType) ?: return null
    return "(" + parameters.joinToString("") + ")" + returnType
  }

  private fun typeDescriptor(type: TypeMirror): String? =
      when (type.kind) {
        TypeKind.BOOLEAN -> "Z"
        TypeKind.BYTE -> "B"
        TypeKind.CHAR -> "C"
        TypeKind.SHORT -> "S"
        TypeKind.INT -> "I"
        TypeKind.LONG -> "J"
        TypeKind.FLOAT -> "F"
        TypeKind.DOUBLE -> "D"
        TypeKind.VOID -> "V"
        TypeKind.ARRAY -> typeDescriptor((type as ArrayType).componentType)?.let { "[$it" }
        TypeKind.DECLARED ->
            ((type as DeclaredType).asElement() as? TypeElement)?.let { binaryName(it) }
                ?.let { "L" + it.replace('.', '/') + ";" }
        TypeKind.TYPEVAR -> typeDescriptor((type as TypeVariable).upperBound)
        else -> null
      }

  private fun parameterDescriptors(descriptor: String): List<String>? {
    if (!descriptor.startsWith('(')) return null
    val result = ArrayList<String>()
    var index = 1
    while (index < descriptor.length && descriptor[index] != ')') {
      val start = index
      while (index < descriptor.length && descriptor[index] == '[') index++
      if (index >= descriptor.length) return null
      index = if (descriptor[index] == 'L') {
        val end = descriptor.indexOf(';', index)
        if (end < 0) return null
        end + 1
      } else if (descriptor[index] in "BCDFIJSZ") {
        index + 1
      } else {
        return null
      }
      result.add(descriptor.substring(start, index))
    }
    return result.takeIf { index < descriptor.length && descriptor[index] == ')' }
  }
}
