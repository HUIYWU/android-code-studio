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
import openjdk.source.util.TreePath
import openjdk.tools.javac.api.JavacTaskImpl
import openjdk.tools.javac.api.JavacTool
import openjdk.tools.javac.api.JavacTrees
import openjdk.tools.javac.code.Symbol.ClassSymbol
import openjdk.tools.javac.comp.Check
import openjdk.tools.javac.tree.JCTree.JCBlock
import openjdk.tools.javac.tree.JCTree.JCClassDecl
import openjdk.tools.javac.tree.JCTree.JCCompilationUnit
import openjdk.tools.javac.tree.JCTree.JCMethodDecl
import openjdk.tools.javac.tree.JCTree.JCVariableDecl
import openjdk.tools.javac.util.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

  private class FakeSource(private val source: String) :
      SimpleJavaFileObject(
          URI.create("string:///fixture/SymbolFixture.java"),
          JavaFileObject.Kind.SOURCE,
      ) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
  }
}