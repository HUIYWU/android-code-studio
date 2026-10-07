package com.tom.rv2ide.lsp.java.kotlin

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Test-only Kotlin compiler ABI evidence collector.
 *
 * This intentionally compiles a self-contained fixture on the host JVM and reads the emitted class
 * files with ASM. It is not part of the Android runtime or the Java LSP request path. Its purpose is
 * to establish compiler-backed golden evidence before a source-level projection rule is added for a
 * Kotlin/JVM feature whose ABI cannot safely be inferred from syntax alone.
 */
internal object KotlinCompilerJvmAbiProbe {

  data class Member(
    val name: String,
    val descriptor: String,
    val access: Int,
    val signature: String? = null,
  )

  data class ClassSurface(
    val internalName: String,
    val access: Int,
    val signature: String? = null,
    val members: List<Member>,
    val fields: List<Member>,
  ) {
    fun constructors(): List<Member> = members.filter { it.name == "<init>" }

    fun methodsNamed(name: String): List<Member> = members.filter { it.name == name }

    fun fieldsNamed(name: String): List<Member> = fields.filter { it.name == name }
  }

  fun compile(
    source: String,
    fileName: String = "Probe.kt",
    additionalArguments: List<String> = emptyList(),
  ): List<ClassSurface> {
    require(fileName.endsWith(".kt")) { "Kotlin fixture name must end with .kt: $fileName" }

    val root = Files.createTempDirectory("kotlin-compiler-abi-probe")
    try {
      val sourceFile = root.resolve(fileName)
      val outputDirectory = root.resolve("classes")
      Files.write(sourceFile, source.toByteArray(StandardCharsets.UTF_8))
      Files.createDirectories(outputDirectory)

      val exitCode = K2JVMCompiler().exec(
        System.err,
        "-no-reflect",
        // kotlin-stdlib is supplied explicitly below; the Android test host has no Kotlin home.
        "-no-stdlib",
        "-classpath", kotlinStdlibPath(),
        "-d", outputDirectory.toString(),
        *additionalArguments.toTypedArray(),
        sourceFile.toString(),
      )
      check(exitCode == ExitCode.OK) {
        "Kotlin compiler ABI probe failed for $fileName with exit code $exitCode"
      }

      return Files.walk(outputDirectory).use { paths ->
        paths
          .filter { Files.isRegularFile(it) && it.extension == "class" }
          .map { readClass(it) }
          .sorted(compareBy { it.internalName })
          .toList()
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun kotlinStdlibPath(): String {
    val location = Unit::class.java.protectionDomain?.codeSource?.location
      ?: error("Unable to locate kotlin-stdlib for Kotlin compiler ABI probe")
    return File(location.toURI()).absolutePath
  }

  private fun readClass(path: Path): ClassSurface {
    var internalName = ""
    var access = 0
    var classSignature: String? = null
    val members = mutableListOf<Member>()
    val fields = mutableListOf<Member>()
    ClassReader(Files.readAllBytes(path)).accept(object : ClassVisitor(Opcodes.ASM9) {
      override fun visit(
        version: Int,
        classAccess: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
      ) {
        internalName = name
        access = classAccess
        classSignature = signature
      }

      override fun visitField(
        fieldAccess: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
      ): FieldVisitor? {
        fields += Member(name, descriptor, fieldAccess, signature)
        return null
      }

      override fun visitMethod(
        methodAccess: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
      ): MethodVisitor? {
        members += Member(name, descriptor, methodAccess, signature)
        return null
      }
    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return ClassSurface(internalName, access, classSignature, members, fields)
  }
}
