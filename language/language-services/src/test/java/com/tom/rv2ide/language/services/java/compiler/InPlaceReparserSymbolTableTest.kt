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
package com.tom.rv2ide.language.services.java.compiler

import com.tom.rv2ide.language.compiler.javac.compiler.CancelServiceImpl
import com.tom.rv2ide.language.compiler.javac.compiler.ReusableContext
import com.tom.rv2ide.language.compiler.javac.partial.CompilationInfo
import com.tom.rv2ide.language.compiler.javac.partial.DiagnosticListenerImpl
import com.tom.rv2ide.language.compiler.javac.partial.InPlaceReparser
import com.tom.rv2ide.language.compiler.javac.util.ReparserUtils
import java.net.URI
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.tools.javac.api.JavacTaskImpl
import openjdk.tools.javac.api.JavacTool
import openjdk.tools.javac.api.JavacTrees
import openjdk.tools.javac.code.Symbol.ClassSymbol
import openjdk.tools.javac.code.Flags
import openjdk.tools.javac.comp.Check
import openjdk.tools.javac.tree.JCTree.JCBlock
import openjdk.tools.javac.tree.JCTree.JCClassDecl
import openjdk.tools.javac.tree.JCTree.JCCompilationUnit
import openjdk.tools.javac.tree.JCTree.JCMethodDecl
import openjdk.tools.javac.tree.JCTree.JCVariableDecl
import openjdk.tools.javac.util.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * javac-level verification of the symbol-table effects of method reparse.
 *
 * These tests answer one question: when a method body that contains an anonymous class is replaced,
 * does `Enter.unenter` really remove that class symbol, and does the replacement leave copy symbols
 * behind in `Check.compiled`?
 *
 * They are intentionally JVM-only: the fixtures are compiled with the vendored javac, and the
 * assertions read `Check.compiled` directly instead of observing editor behaviour. Local and
 * anonymous classes are entered with `Symtab.defineClass` plus `Check.putCompiled`, so
 * `Check.compiled` — not `Symtab.getAllClasses()` — is the table that proves whether they leaked.
 */
class InPlaceReparserSymbolTableTest {

  /**
   * A body with an anonymous class. Attribution enters `fixture.SymbolFixture$1`.
   */
  private val bodyWithAnonymousClass =
      """
      {
        Runnable r = new Runnable() {
          public void run() { }
        };
        r.run();
        return value;
      }
      """.trimIndent()

  /**
   * A body without any local or anonymous class.
   */
  private val bodyWithoutAnonymousClass =
      """
      {
        return value + 1;
      }
      """.trimIndent()

  /** A field whose initializer introduces an anonymous class. */
  private val sourceWithAnonymousInitializer =
      """
      package fixture;

      final class SymbolFixture {
        Runnable ticker = new Runnable() {
          public void run() { }
        };
      }
      """.trimIndent()

  /** An instance initializer block that introduces an anonymous class. */
  private val sourceWithAnonymousInitializerBlock =
      """
      package fixture;

      final class SymbolFixture {
        {
          Runnable ticker = new Runnable() {
            public void run() { }
          };
          ticker.run();
        }
      }
      """.trimIndent()

  /** A constructor body cannot return a value, so the replacement body is empty. */
  private val emptyConstructorBody = "{ }"

  /** A body with a named local class, which uses the same `JCClassDecl` entry path. */
  private val bodyWithLocalClass =
      """
      {
        class Local {
          int size() { return 1; }
        }
        return new Local().size();
      }
      """.trimIndent()

  @Test
  fun attribution_entersAnonymousClassSymbol() {
    val fixture = compile()
    assertNotNull(
        "anonymous class must be registered in Check.compiled after attribution",
        compiled(fixture, anonymousFlatName()),
    )
  }

  @Test
  fun reparse_removesLiveAnonymousClassSymbol() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile()
    assertNotNull(compiled(fixture, anonymousFlatName()))

    assertTrue(
        "reparse of a method body without anonymous classes must succeed",
        reparseWithBody(fixture, bodyWithoutAnonymousClass),
    )

    assertNull(
        "the replaced anonymous class must be unentered from Check.compiled",
        compiled(fixture, anonymousFlatName()),
    )

    assertEquals(
        "no anonymous class entry may survive the replacement: " +
            compiledAnonymousClassNames(fixture),
        0,
        compiledAnonymousClassCount(fixture),
    )
  }

  /**
   * Guards the invariant that a body replacement must not leave class symbols behind.
   *
   * `JavacTrees.getScope(TreePath(methodPath, method.body))` copies the old body and attributes the
   * copy, so anonymous classes inside that copy are entered into `Check.compiled` under a freshly
   * allocated flatname that `fixLocalClassNames` rewrites afterwards. `Check.compiled` is not
   * shadowed by `Enter.shadowTypeEnvs`, so such entries can survive the reparse. Re-attributing with
   * `getScope(methodPath)` avoids the copy entirely, which is what this test locks in.
   */
  @Test
  fun reparse_doesNotLeaveCopiedAnonymousClassSymbols() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile()
    assertEquals(
        "exactly one anonymous class is expected before the edit",
        1,
        compiledAnonymousClassCount(fixture),
    )

    assertTrue(
        "reparse of a method body without anonymous classes must succeed",
        reparseWithBody(fixture, bodyWithoutAnonymousClass),
    )

    assertEquals(
        "no anonymous class symbol may survive a body that has none: " +
            compiledAnonymousClassNames(fixture),
        0,
        compiledAnonymousClassCount(fixture),
    )
  }

  /**
   * Repeatedly adds and removes an anonymous class in the same method.
   *
   * A leaking implementation shows up here as a growing number of `SymbolFixture$N` symbols or as a
   * stale `Check.compiled` entry, even though a single replacement looked clean.
   */
  @Test
  fun repeatedAnonymousClassToggles_keepSymbolTablesClean() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile()
    assertEquals(
        "the fixture must start with exactly one anonymous class entry: " +
            compiledAnonymousClassNames(fixture),
        1,
        compiledAnonymousClassCount(fixture),
    )

    repeat(TOGGLE_CYCLES) { cycle ->
      assertTrue(
          "cycle $cycle: removing the anonymous class must succeed",
          reparseWithBody(fixture, bodyWithoutAnonymousClass),
      )
      assertEquals(
          "cycle $cycle: no compiled entry may survive removal: " +
              compiledAnonymousClassNames(fixture),
          0,
          compiledAnonymousClassCount(fixture),
      )

      assertTrue(
          "cycle $cycle: re-adding the anonymous class must succeed",
          reparseWithBody(fixture, bodyWithAnonymousClass),
      )
      assertEquals(
          "cycle $cycle: exactly one compiled entry is expected: " +
              compiledAnonymousClassNames(fixture),
          1,
          compiledAnonymousClassCount(fixture),
      )
    }
  }

  /**
   * Constructor bodies use the same reparse path; `reAttrMethodBody` already inserts the implicit
   * super call for constructors, so supporting them needs no extra machinery.
   */
  @Test
  fun constructorBody_reparsesInPlace() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture =
        compile(
            source = sourceWithMember("SymbolFixture()", bodyWithAnonymousClass),
            memberName = "<init>",
        )
    assertEquals(
        "the constructor fixture must contain one anonymous class",
        1,
        compiledAnonymousClassCount(fixture),
    )

    assertTrue(
        "reparse of a constructor body must succeed",
        reparseWithBody(fixture, emptyConstructorBody),
    )

    assertEquals(
        "no anonymous class entry may survive the constructor replacement: " +
            compiledAnonymousClassNames(fixture),
        0,
        compiledAnonymousClassCount(fixture),
    )
  }

  /**
   * A named local class is a `JCClassDecl` too, so `Enter.unenter` must remove it like an anonymous
   * class even though its flat name carries a suffix.
   */
  @Test
  fun namedLocalClass_isRemovedOnBodyReplacement() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile(sourceWith(bodyWithLocalClass))
    assertEquals(
        "the fixture must contain one local class entry: " + compiledLocalClassNames(fixture, "Local"),
        1,
        compiledLocalClassCount(fixture, "Local"),
    )

    assertTrue(
        "reparse of a body without the local class must succeed",
        reparseWithBody(fixture, bodyWithoutAnonymousClass),
    )

    assertEquals(
        "no local class entry may survive the replacement: " +
            compiledLocalClassNames(fixture, "Local"),
        0,
        compiledLocalClassCount(fixture, "Local"),
    )
  }

  /**
   * Field initializers are replaced without touching the field symbol; an anonymous class that lived
   * inside the old initializer must be unentered.
   */
  @Test
  fun fieldInitializer_reparsesInPlaceAndDropsAnonymousClass() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compileField(sourceWithAnonymousInitializer, "ticker")
    assertEquals(
        "the initializer fixture must contain one anonymous class: " +
            compiledAnonymousClassNames(fixture.task, fixture.cu),
        1,
        compiledAnonymousClassCount(fixture.task, fixture.cu),
    )

    assertTrue(
        "replacing a field initializer must succeed",
        reparseInitializer(fixture, "null"),
    )

    assertEquals(
        "no anonymous class entry may survive the initializer replacement",
        0,
        compiledAnonymousClassCount(fixture.task, fixture.cu),
    )
  }

  /**
   * Instance initializer blocks are members of their own: they are replaced in place and the local
   * classes of the old block are unentered.
   */
  @Test
  fun initializerBlock_reparsesInPlaceAndDropsAnonymousClass() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compileBlock(sourceWithAnonymousInitializerBlock)
    assertEquals(
        "the initializer block fixture must contain one anonymous class: " +
            compiledAnonymousClassNames(fixture.task, fixture.cu),
        1,
        compiledAnonymousClassCount(fixture.task, fixture.cu),
    )

    assertTrue("replacing an initializer block must succeed", reparseBlock(fixture, "{ }"))

    assertEquals(
        "no anonymous class entry may survive the block replacement",
        0,
        compiledAnonymousClassCount(fixture.task, fixture.cu),
    )
  }

  /**
   * A method without written modifiers is the hardest header case: `JCModifiers.pos` is `NOPOS`, so
   * the declaration starts at the return type and the first modifier is a pure insertion there.
   */
  @Test
  fun modifiers_applyInPlaceAndKeepSymbolIdentity() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile()
    val method = fixture.methodPath.leaf as JCMethodDecl
    val symbol = method.sym
    val body = method.body
    assertNotNull(body)
    assertEquals("the fixture method must start without modifiers", 0L, method.mods.flags)

    assertTrue("adding modifiers must succeed", reparseModifiers(fixture, "public static "))

    assertTrue(
        "PUBLIC must reach the symbol",
        (symbol.flags() and Flags.PUBLIC.toLong()) != 0L,
    )
    assertTrue(
        "STATIC must reach the symbol",
        (symbol.flags() and Flags.STATIC.toLong()) != 0L,
    )
    assertTrue(
        "STATIC must reach the tree",
        (method.mods.flags and Flags.STATIC.toLong()) != 0L,
    )
    assertSame("the body tree must stay identical", body, method.body)
  }

  @Test
  fun modifiers_rejectAnnotationChanges() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile()
    val method = fixture.methodPath.leaf as JCMethodDecl
    val original = fixture.source

    assertFalse(
        "an added annotation must be rejected instead of silently dropped",
        reparseModifiers(fixture, "@Deprecated "),
    )

    assertEquals("a rejected edit must not touch the document", original, fixture.source)
    assertEquals("a rejected edit must not touch the flags", 0L, method.mods.flags)
  }

  @Test
  fun throwsClause_replacesThrownTypesInPlace() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture =
        compile(
            sourceWithMember(
                "int run(int value) throws java.io.IOException",
                "{ return value; }",
            ),
        )
    val method = fixture.methodPath.leaf as JCMethodDecl
    val symbol = method.sym
    val body = method.body
    assertEquals(
        "the fixture must declare exactly one thrown type",
        1,
        method.getThrows().size,
    )

    assertTrue(
        "replacing a thrown type must succeed",
        reparseThrows(fixture, "java.lang.IllegalStateException"),
    )

    assertEquals(
        "the method type must carry exactly one thrown type",
        1,
        symbol.type.getThrownTypes().size,
    )
    assertEquals(
        "the method type must carry the new thrown type",
        "IllegalStateException",
        symbol.type.getThrownTypes().first().tsym.name.toString(),
    )
    assertEquals(
        "the syntax tree must expose the new thrown type",
        1,
        method.getThrows().size,
    )
    assertSame("the body tree must stay identical", body, method.body)
  }

  @Test
  fun throwsClause_rejectsNonThrowableTypes() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture =
        compile(
            sourceWithMember(
                "int run(int value) throws java.io.IOException",
                "{ return value; }",
            ),
        )
    val method = fixture.methodPath.leaf as JCMethodDecl
    val symbol = method.sym

    assertFalse(
        "a type outside java.lang.Throwable must be rejected",
        reparseThrows(fixture, "java.lang.String"),
    )

    assertEquals(
        "a rejected edit must keep the thrown type",
        "IOException",
        symbol.type.getThrownTypes().first().tsym.name.toString(),
    )
  }

  @Test
  fun throwsClause_canBeAddedInFrontOfTheBody() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile()
    val method = fixture.methodPath.leaf as JCMethodDecl
    val symbol = method.sym
    val body = method.body
    assertTrue("the fixture must not declare thrown types", method.getThrows().isEmpty())

    assertTrue(
        "inserting a throws clause in front of the body must succeed",
        reparseAddedThrows(fixture, " throws java.io.IOException "),
    )

    assertEquals("exactly one thrown type is expected", 1, symbol.type.getThrownTypes().size)
    assertEquals(
        "the method type must carry the inserted thrown type",
        "IOException",
        symbol.type.getThrownTypes().first().tsym.name.toString(),
    )
    assertSame("the body tree must stay identical", body, method.body)
  }

  @Test
  fun throwsClause_rejectsInsertedTextWithoutThrowsKeyword() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile()
    val method = fixture.methodPath.leaf as JCMethodDecl
    val symbol = method.sym

    assertFalse(
        "an insertion that is not a throws clause must be rejected",
        reparseAddedThrows(fixture, " int temporary = 1 "),
    )
    assertTrue(
        "a rejected insertion must keep the method type",
        symbol.type.getThrownTypes().isEmpty(),
    )
  }

  @Test
  fun throwsClause_canBeAddedToBodylessDeclaration() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile(sourceWithAbstractMethod)
    val method = fixture.methodPath.leaf as JCMethodDecl
    val symbol = method.sym
    assertNull("the fixture must have no body", method.body)

    assertTrue(
        "inserting a throws clause before the semicolon must succeed",
        reparseAddedThrows(fixture, " throws java.io.IOException "),
    )

    assertEquals("exactly one thrown type is expected", 1, symbol.type.getThrownTypes().size)
    assertEquals(
        "the method type must carry the inserted thrown type",
        "IOException",
        symbol.type.getThrownTypes().first().tsym.name.toString(),
    )
    assertNull("the declaration must stay body-less", method.body)
  }

  @Test
  fun modifiers_applyToBodylessDeclaration() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compile(sourceWithAbstractMethod)
    val method = fixture.methodPath.leaf as JCMethodDecl
    val symbol = method.sym
    assertNull("the fixture must have no body", method.body)

    assertTrue(
        "adding a modifier to a body-less declaration must succeed",
        reparseModifiers(fixture, "protected "),
    )

    assertTrue(
        "PROTECTED must reach the symbol",
        (symbol.flags() and Flags.PROTECTED.toLong()) != 0L,
    )
    assertTrue(
        "ABSTRACT must stay on the symbol",
        (symbol.flags() and Flags.ABSTRACT.toLong()) != 0L,
    )
    assertNull("the declaration must stay body-less", method.body)
  }

  @Test
  fun fieldModifiers_applyInPlace() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compileField(sourceWithField, "value")
    val field = fixture.fieldPath.leaf as JCVariableDecl
    val symbol = field.sym
    val initializer = field.init
    assertEquals("the fixture field must start without modifiers", 0L, field.mods.flags)

    assertTrue(
        "adding a modifier to a field must succeed",
        reparseFieldModifiers(fixture, "private "),
    )

    assertTrue(
        "PRIVATE must reach the symbol",
        (symbol.flags() and Flags.PRIVATE.toLong()) != 0L,
    )
    assertTrue(
        "PRIVATE must reach the tree",
        (field.mods.flags and Flags.PRIVATE.toLong()) != 0L,
    )
    assertSame("the initializer tree must stay identical", initializer, field.init)
  }

  @Test
  fun fieldModifiers_rejectAnnotationChanges() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compileField(sourceWithField, "value")
    val field = fixture.fieldPath.leaf as JCVariableDecl
    val original = fixture.source

    assertFalse(
        "an added annotation must be rejected instead of silently dropped",
        reparseFieldModifiers(fixture, "@Deprecated "),
    )

    assertEquals("a rejected edit must not touch the document", original, fixture.source)
    assertEquals("a rejected edit must not touch the flags", 0L, field.mods.flags)
  }

  @Test
  fun classModifiers_applyInPlace() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compileClass(sourceWithClass)
    val classDecl = fixture.classPath.leaf as JCClassDecl
    val symbol = classDecl.sym
    val memberCount = classDecl.defs.size
    assertEquals("the fixture class must start without modifiers", 0L, classDecl.mods.flags)

    assertTrue("adding class modifiers must succeed", reparseClassModifiers(fixture, "public final "))

    assertTrue(
        "PUBLIC must reach the symbol",
        (symbol.flags() and Flags.PUBLIC.toLong()) != 0L,
    )
    assertTrue(
        "PUBLIC must reach the tree",
        (classDecl.mods.flags and Flags.PUBLIC.toLong()) != 0L,
    )
    assertTrue(
        "FINAL must reach the tree",
        (classDecl.mods.flags and Flags.FINAL.toLong()) != 0L,
    )
    assertEquals("the member list must stay unchanged", memberCount, classDecl.defs.size)
  }

  @Test
  fun classModifiers_rejectAnnotationChanges() {
    assumeTrue("partial reparse is unavailable on this JDK", ReparserUtils.canReparse())
    val fixture = compileClass(sourceWithClass)
    val classDecl = fixture.classPath.leaf as JCClassDecl
    val original = fixture.source

    assertFalse(
        "an added annotation must be rejected instead of silently dropped",
        reparseClassModifiers(fixture, "@Deprecated "),
    )

    assertEquals("a rejected edit must not touch the document", original, fixture.source)
    assertEquals("a rejected edit must not touch the flags", 0L, classDecl.mods.flags)
  }

  /** A class without modifiers: the class declaration header can be edited in place. */
  private val sourceWithClass =
      """
      package fixture;

      class SymbolFixture {
        int value = 1;
      }
      """
          .trimIndent()

  /** A field with an initializer: modifier edits must not touch the initializer tree. */
  private val sourceWithField =
      """
      package fixture;

      final class SymbolFixture {
        int value = 1;
      }
      """
          .trimIndent()

  /** A body-less declaration: modifiers are replaced without touching any body tree. */
  private val sourceWithAbstractMethod =
      """
      package fixture;

      abstract class SymbolFixture {
        abstract int run(int value);
      }
      """
          .trimIndent()

  private fun compile(
      source: String = sourceWith(bodyWithAnonymousClass),
      memberName: String = "run",
  ): Fixture {
    // Reuse the production context: partial reparse depends on the NB* overrides and on ReusableLog
    // (an NBLog), which a bare Context does not provide.
    val context = ReusableContext(CancelServiceImpl())
    val task =
        JavacTool.create()
            .getTask(
                null,
                null,
                null,
                emptyList<String>(),
                emptyList<String>(),
                listOf(FakeSource(source)),
                context,
            ) as JavacTaskImpl
    val units = task.parse().toList()
    val cu = units.single() as JCCompilationUnit
    task.analyze()

    val type = task.elements.getTypeElement("fixture.SymbolFixture") as TypeElement
    val method =
        type.enclosedElements
            .filterIsInstance<ExecutableElement>()
            .single { it.simpleName.contentEquals(memberName) }
    val methodPath = JavacTrees.instance(task).getPath(method)
        ?: error("Unable to resolve the fixture method path")

    return Fixture(task = task, cu = cu, source = source, methodPath = methodPath)
  }

  private fun sourceWith(body: String): String = sourceWithMember("int run(int value)", body)

  private fun sourceWithMember(declaration: String, body: String): String =
      """
      package fixture;

      final class SymbolFixture {
        $declaration
      $body
      }
      """
          .trimIndent()

  /**
   * Replaces the fixture method body through the production reparse path, exactly as completion
   * does: the body text range is derived from javac source positions and the full updated text is
   * passed as the document contents.
   */
  private fun reparseWithBody(fixture: Fixture, bodyText: String): Boolean {
    val trees = JavacTrees.instance(fixture.task)
    val positions = trees.sourcePositions
    val method = fixture.methodPath.leaf as JCMethodDecl
    val body = method.body ?: return false
    val start = positions.getStartPosition(fixture.cu, body).toInt()
    val end = positions.getEndPosition(fixture.cu, body).toInt()
    if (start < 0 || end <= start) {
      return false
    }

    val updated = fixture.source.substring(0, start) + bodyText + fixture.source.substring(end)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed = InPlaceReparser().reparseMethod(info, fixture.methodPath, bodyText, updated)
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  private data class BlockFixture(
      val task: JavacTaskImpl,
      val cu: JCCompilationUnit,
      var source: String,
      val blockPath: TreePath,
  )

  private fun compileBlock(source: String): BlockFixture {
    val context = ReusableContext(CancelServiceImpl())
    val task =
        JavacTool.create()
            .getTask(
                null,
                null,
                null,
                emptyList<String>(),
                emptyList<String>(),
                listOf(FakeSource(source)),
                context,
            ) as JavacTaskImpl
    val units = task.parse().toList()
    val cu = units.single() as JCCompilationUnit
    task.analyze()

    val classDecl = cu.typeDecls.filterIsInstance<JCClassDecl>().single()
    val block = classDecl.defs.filterIsInstance<JCBlock>().single()
    val blockPath = TreePath.getPath(cu, block)
        ?: error("Unable to resolve the fixture initializer block path")
    return BlockFixture(task = task, cu = cu, source = source, blockPath = blockPath)
  }

  private fun reparseBlock(fixture: BlockFixture, newBlock: String): Boolean {
    val trees = JavacTrees.instance(fixture.task)
    val positions = trees.sourcePositions
    val block = fixture.blockPath.leaf as JCBlock
    val start = positions.getStartPosition(fixture.cu, block).toInt()
    val end = positions.getEndPosition(fixture.cu, block).toInt()
    if (start < 0 || end <= start) {
      return false
    }
    val updated = fixture.source.substring(0, start) + newBlock + fixture.source.substring(end)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed =
        InPlaceReparser().reparseInitializerBlock(info, fixture.blockPath, newBlock, updated)
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  /** Mirrors the service class header end: the {@code class} keyword position. */
  private fun reparseClassModifiers(fixture: ClassFixture, written: String): Boolean {
    val positions = JavacTrees.instance(fixture.task).sourcePositions
    val classDecl = fixture.classPath.leaf as JCClassDecl
    val declarationStart = positions.getStartPosition(fixture.cu, classDecl).toInt()
    val headerEnd = classDecl.pos
    if (declarationStart < 0 || headerEnd < 0) {
      return false
    }
    val updated =
        fixture.source.substring(0, declarationStart) +
            written +
            fixture.source.substring(declarationStart)
    val newModifiers = updated.substring(declarationStart, headerEnd + written.length)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed =
        InPlaceReparser()
            .reparseClassModifiers(
                info,
                fixture.classPath,
                newModifiers,
                declarationStart,
                fixture.source,
                updated,
                declarationStart,
                declarationStart,
            )
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  /**
   * Inserts [written] at the field declaration start: the new modifiers region is the text between
   * the declaration start and the header end, and the changed window is that empty insertion point.
   */
  private fun reparseFieldModifiers(fixture: FieldFixture, written: String): Boolean {
    val positions = JavacTrees.instance(fixture.task).sourcePositions
    val field = fixture.fieldPath.leaf as JCVariableDecl
    val declarationStart = positions.getStartPosition(fixture.cu, field).toInt()
    if (declarationStart < 0) {
      return false
    }
    val headerEnd = fieldHeaderEnd(positions, fixture.cu, field)
    if (headerEnd < 0 || headerEnd < declarationStart) {
      return false
    }
    val updated =
        fixture.source.substring(0, declarationStart) +
            written +
            fixture.source.substring(declarationStart)
    val newModifiers = updated.substring(declarationStart, headerEnd + written.length)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed =
        InPlaceReparser()
            .reparseFieldModifiers(
                info,
                fixture.fieldPath,
                newModifiers,
                declarationStart,
                fixture.source,
                updated,
                declarationStart,
                declarationStart,
            )
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  /** Mirrors the service field header end: the earliest of the declared name and the type. */
  private fun fieldHeaderEnd(
      positions: SourcePositions,
      cu: JCCompilationUnit,
      field: JCVariableDecl,
  ): Int {
    var end = field.pos
    val type = field.vartype
    if (type != null) {
      val start = positions.getStartPosition(cu, type).toInt()
      if (start >= 0) {
        end = minOf(end, start)
      }
    }
    return end
  }

  /**
   * Inserts [written] at the declaration start, exactly as a completion request does: the new
   * modifiers region is the text between the declaration start and the header end, and the changed
   * window is that empty insertion point.
   */
  private fun reparseModifiers(fixture: Fixture, written: String): Boolean {
    val positions = JavacTrees.instance(fixture.task).sourcePositions
    val method = fixture.methodPath.leaf as JCMethodDecl
    val declarationStart = positions.getStartPosition(fixture.cu, method).toInt()
    if (declarationStart < 0) {
      return false
    }
    val headerEnd = declarationHeaderEnd(positions, fixture.cu, method)
    if (headerEnd < 0 || headerEnd < declarationStart) {
      return false
    }
    val updated =
        fixture.source.substring(0, declarationStart) +
            written +
            fixture.source.substring(declarationStart)
    val newModifiers = updated.substring(declarationStart, headerEnd + written.length)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed =
        InPlaceReparser()
            .reparseMethodModifiers(
                info,
                fixture.methodPath,
                newModifiers,
                declarationStart,
                fixture.source,
                updated,
                declarationStart,
                declarationStart,
            )
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  /**
   * Mirrors the service header end: the earliest of the return type, the first type parameter and
   * the declared name.
   */
  private fun declarationHeaderEnd(
      positions: SourcePositions,
      cu: JCCompilationUnit,
      method: JCMethodDecl,
  ): Int {
    var end = method.pos
    val returnType = method.restype
    if (returnType != null) {
      val start = positions.getStartPosition(cu, returnType).toInt()
      if (start >= 0) {
        end = minOf(end, start)
      }
    }
    if (!method.typarams.isEmpty()) {
      val start = positions.getStartPosition(cu, method.typarams.head).toInt()
      if (start >= 0) {
        end = minOf(end, start)
      }
    }
    return end
  }

  /**
   * Replaces the thrown-type list of the fixture method, exactly as a completion request does: the
   * document is the old text with the new clause text, and the window is the replaced type range.
   */
  private fun reparseThrows(fixture: Fixture, newThrows: String): Boolean {
    val positions = JavacTrees.instance(fixture.task).sourcePositions
    val method = fixture.methodPath.leaf as JCMethodDecl
    val thrown = method.getThrows()
    if (thrown.isEmpty()) {
      return false
    }
    val start = positions.getStartPosition(fixture.cu, thrown.first()).toInt()
    val end = positions.getEndPosition(fixture.cu, thrown.last()).toInt()
    if (start < 0 || end < start) {
      return false
    }
    val updated = fixture.source.substring(0, start) + newThrows + fixture.source.substring(end)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed =
        InPlaceReparser()
            .reparseThrowsClause(
                info,
                fixture.methodPath,
                newThrows,
                start,
                fixture.source,
                updated,
                start,
                end,
                false,
            )
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  /**
   * Inserts a throws clause in front of the fixture body, exactly as a completion request does for an
   * add edit: the window is the empty insertion point and the region is the inserted text.
   */
  private fun reparseAddedThrows(fixture: Fixture, clause: String): Boolean {
    val positions = JavacTrees.instance(fixture.task).sourcePositions
    val method = fixture.methodPath.leaf as JCMethodDecl
    val body = method.body
    val insertion =
        if (body != null) {
          positions.getStartPosition(fixture.cu, body).toInt()
        } else {
          positions.getEndPosition(fixture.cu, method).toInt() - 1
        }
    if (insertion < 0) {
      return false
    }
    val updated = fixture.source.substring(0, insertion) + clause + fixture.source.substring(insertion)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed =
        InPlaceReparser()
            .reparseThrowsClause(
                info,
                fixture.methodPath,
                clause,
                insertion,
                fixture.source,
                updated,
                insertion,
                insertion,
                true,
            )
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  private data class FieldFixture(
      val task: JavacTaskImpl,
      val cu: JCCompilationUnit,
      var source: String,
      val fieldPath: TreePath,
  )

  private fun compileField(source: String, fieldName: String): FieldFixture {
    val context = ReusableContext(CancelServiceImpl())
    val task =
        JavacTool.create()
            .getTask(
                null,
                null,
                null,
                emptyList<String>(),
                emptyList<String>(),
                listOf(FakeSource(source)),
                context,
            ) as JavacTaskImpl
    val units = task.parse().toList()
    val cu = units.single() as JCCompilationUnit
    task.analyze()

    val type = task.elements.getTypeElement("fixture.SymbolFixture") as TypeElement
    val field =
        type.enclosedElements
            .filterIsInstance<VariableElement>()
            .single { it.simpleName.contentEquals(fieldName) }
    val fieldPath = JavacTrees.instance(task).getPath(field)
        ?: error("Unable to resolve the fixture field path")
    return FieldFixture(task = task, cu = cu, source = source, fieldPath = fieldPath)
  }

  private fun reparseInitializer(fixture: FieldFixture, newInitializer: String): Boolean {
    val trees = JavacTrees.instance(fixture.task)
    val positions = trees.sourcePositions
    val field = fixture.fieldPath.leaf as JCVariableDecl
    val initializer = field.init ?: return false
    val start = positions.getStartPosition(fixture.cu, initializer).toInt()
    val end = positions.getEndPosition(fixture.cu, initializer).toInt()
    if (start < 0 || end <= start) {
      return false
    }
    val updated = fixture.source.substring(0, start) + newInitializer + fixture.source.substring(end)
    val info = CompilationInfo(fixture.task, DiagnosticListenerImpl(null), fixture.cu)
    val reparsed =
        InPlaceReparser().reparseFieldInitializer(info, fixture.fieldPath, newInitializer, updated)
    if (reparsed) {
      fixture.source = updated
    }
    return reparsed
  }

  private fun compiled(fixture: Fixture, flatName: String): ClassSymbol? =
      compiled(fixture.task, fixture.cu, flatName)

  private fun compiled(task: JavacTaskImpl, cu: JCCompilationUnit, flatName: String): ClassSymbol? {
    val check = Check.instance(task.context)
    val names = Names.instance(task.context)
    return check.getCompiled(cu.modle, names.fromString(flatName))
  }

  /**
   * Number of `Check.compiled` entries for the fixture's anonymous classes.
   *
   * `Check.compiled` is the authoritative table here: javac enters local and anonymous classes with
   * `Symtab.defineClass` plus `Check.putCompiled`, so they never appear in `Symtab.getAllClasses()`.
   */
  private fun compiledAnonymousClassCount(fixture: Fixture, limit: Int = PROBE_LIMIT): Int =
      compiledAnonymousClassCount(fixture.task, fixture.cu, limit)

  private fun compiledAnonymousClassCount(
      task: JavacTaskImpl,
      cu: JCCompilationUnit,
      limit: Int = PROBE_LIMIT,
  ): Int = (1..limit).count { index -> compiled(task, cu, anonymousFlatName(index)) != null }

  private fun compiledAnonymousClassNames(fixture: Fixture, limit: Int = PROBE_LIMIT): List<String> =
      compiledAnonymousClassNames(fixture.task, fixture.cu, limit)

  private fun compiledAnonymousClassNames(
      task: JavacTaskImpl,
      cu: JCCompilationUnit,
      limit: Int = PROBE_LIMIT,
  ): List<String> =
      (1..limit)
          .filter { index -> compiled(task, cu, anonymousFlatName(index)) != null }
          .map { index -> anonymousFlatName(index) }

  /**
   * Number of `Check.compiled` entries for a **named** local class.
   *
   * Its flat name is `<enclosing>$<index><SimpleName>`, so the index must be probed rather than
   * assumed.
   */
  private fun compiledLocalClassCount(
      fixture: Fixture,
      simpleName: String,
      limit: Int = PROBE_LIMIT,
  ): Int = (1..limit).count { index -> compiled(fixture, localFlatName(index, simpleName)) != null }

  private fun compiledLocalClassNames(
      fixture: Fixture,
      simpleName: String,
      limit: Int = PROBE_LIMIT,
  ): List<String> =
      (1..limit)
          .filter { index -> compiled(fixture, localFlatName(index, simpleName)) != null }
          .map { index -> localFlatName(index, simpleName) }

  private fun anonymousFlatName(index: Int = 1): String = "$NESTED_FLAT_PREFIX$index"

  private fun localFlatName(index: Int, simpleName: String): String =
      "$NESTED_FLAT_PREFIX$index$simpleName"

  private val NESTED_FLAT_PREFIX = "fixture.SymbolFixture\$"

  /** Enough cycles to expose per-reparse growth without making the test slow. */
  private val TOGGLE_CYCLES = 10

  /** Upper bound of anonymous-class indices probed in `Check.compiled`. */
  private val PROBE_LIMIT = 64

  private data class Fixture(
      val task: JavacTaskImpl,
      val cu: JCCompilationUnit,
      var source: String,
      val methodPath: TreePath,
  )

  private data class ClassFixture(
      val task: JavacTaskImpl,
      val cu: JCCompilationUnit,
      var source: String,
      val classPath: TreePath,
  )

  private fun compileClass(source: String): ClassFixture {
    val context = ReusableContext(CancelServiceImpl())
    val task =
        JavacTool.create()
            .getTask(
                null,
                null,
                null,
                emptyList<String>(),
                emptyList<String>(),
                listOf(FakeSource(source)),
                context,
            ) as JavacTaskImpl
    val units = task.parse().toList()
    val cu = units.single() as JCCompilationUnit
    task.analyze()

    val classDecl = cu.typeDecls.filterIsInstance<JCClassDecl>().single()
    val classPath =
        TreePath.getPath(cu, classDecl) ?: error("Unable to resolve the fixture class path")
    return ClassFixture(task = task, cu = cu, source = source, classPath = classPath)
  }

  private class FakeSource(private val source: String) :
      SimpleJavaFileObject(
          URI.create("string:///fixture/SymbolFixture.java"),
          JavaFileObject.Kind.SOURCE,
      ) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
  }
}