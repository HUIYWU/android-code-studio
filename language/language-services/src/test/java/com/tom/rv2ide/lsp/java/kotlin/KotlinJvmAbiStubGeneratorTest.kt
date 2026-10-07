package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TreeSitter
import java.net.URI
import java.nio.file.Files
import java.util.Comparator
import jdkx.tools.DiagnosticCollector
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinJvmAbiStubGeneratorTest {

  @Test
  fun mergeGeneratedStubs_prefersStructuredAndRecursivelySupplementsMissingJvmSurface() {
    val structured =
        """
        package sample;
        public class Outer {
          public Outer() {}
          public String load(int value) { return null; }
          public static class Nested {
            public String existing() { return null; }
          }
        }
        """.trimIndent() + "\n"
    val fallback =
        """
        package sample;
        public class Outer {
          public Outer() {}
          public Object load(int arg0) { return null; }
          public boolean enabled() { return false; }
          public static class Nested {
            public String existing() { return null; }
            public int recovered() { return 0; }
          }
        }
        """.trimIndent() + "\n"

    val merged = KotlinJvmAbiStubGenerator.mergeGeneratedStubsForTest(structured, fallback)

    assertEquals(1, Regex("\\bload\\(int ").findAll(merged).count())
    assertTrue(merged.contains("public String load(int value)"))
    assertFalse(merged.contains("public Object load(int arg0)"))
    assertTrue(merged.contains("public boolean enabled()"))
    assertEquals(1, Regex("\\bexisting\\(\\)").findAll(merged).count())
    assertTrue(merged.contains("public int recovered()"))
  }

  @Test
  fun generate_projectsPrimaryConstructorVarargsAsJvmArrays() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class Labels(vararg values: String)
        class Numbers(vararg values: Int)
        class NullableNumbers(vararg values: Int?)
        class NestedNullableNumbers(vararg values: Array<Int?>)
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val labels = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.Labels", "PrimaryVarargs.kt", source, emptySet(), mode)
      val numbers = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.Numbers", "PrimaryVarargs.kt", source, emptySet(), mode)
      val nullableNumbers = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.NullableNumbers", "PrimaryVarargs.kt", source, emptySet(), mode)
      val nestedNullableNumbers = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.NestedNullableNumbers", "PrimaryVarargs.kt", source, emptySet(), mode)
      assertNotNull("Primary reference vararg generation failed in $mode", labels)
      assertNotNull("Primary primitive vararg generation failed in $mode", numbers)
      assertNotNull("Primary nullable vararg generation failed in $mode", nullableNumbers)
      assertNotNull("Primary nested nullable vararg generation failed in $mode", nestedNullableNumbers)
      assertContains(labels!!, "public Labels(String... values)")
      assertContains(numbers!!, "public Numbers(int... values)")
      assertContains(nullableNumbers!!, "public NullableNumbers(Integer... values)")
      assertContains(nestedNullableNumbers!!, "public NestedNullableNumbers(Integer[]... values)")
      assertFalse(labels.contains("public Labels(String values)"))
      assertFalse(numbers.contains("public Numbers(int values)"))
      assertFalse(nullableNumbers.contains("public NullableNumbers(int... values)"))
    }
  }

  @Test
  fun generate_projectsJvmOverloadsPrimaryConstructorWithNonTrailingVararg() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class PrimaryVarargOverloads @JvmOverloads constructor(
          vararg values: String,
          suffix: String = ""
        )
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.PrimaryVarargOverloads", "PrimaryVarargOverloads.kt", source, emptySet(), mode)
      assertNotNull("Primary non-trailing vararg overload generation failed in $mode", stub)
      assertContains(stub!!,
          "public PrimaryVarargOverloads(String[] values, String suffix)")
      assertContains(stub, "public PrimaryVarargOverloads(String... values)")
      assertFalse("Non-trailing vararg was rendered as Java varargs in $mode:\n$stub",
          stub.contains("PrimaryVarargOverloads(String... values, String suffix)"))
    }
  }

  @Test
  fun generate_projectsGenericJvmOverloadsPrimaryConstructorWithNonTrailingVararg() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class GenericPrimaryVarargOverloads<T> @JvmOverloads constructor(
          vararg values: T,
          suffix: String = ""
        )
        class BoundedPrimaryVarargOverloads<T : CharSequence> @JvmOverloads constructor(
          vararg values: T,
          suffix: String = ""
        )
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val generic = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.GenericPrimaryVarargOverloads", "GenericPrimaryVarargOverloads.kt", source,
          emptySet(), mode)
      val bounded = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.BoundedPrimaryVarargOverloads", "GenericPrimaryVarargOverloads.kt", source,
          emptySet(), mode)
      assertNotNull("Generic non-trailing primary vararg generation failed in $mode", generic)
      assertNotNull("Bounded non-trailing primary vararg generation failed in $mode", bounded)
      assertContains(generic!!,
          "public GenericPrimaryVarargOverloads(T[] values, String suffix)")
      assertContains(generic, "public GenericPrimaryVarargOverloads(T... values)")
      assertContains(bounded!!,
          "public BoundedPrimaryVarargOverloads(T[] values, String suffix)")
      assertContains(bounded, "public BoundedPrimaryVarargOverloads(T... values)")
      assertFalse(generic.contains("GenericPrimaryVarargOverloads(Object... values)"))
      assertFalse(bounded.contains("BoundedPrimaryVarargOverloads(CharSequence... values)"))
    }
  }

  @Test
  fun generate_projectsNullableJvmOverloadsPrimaryConstructorWithNonTrailingVararg() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class NullablePrimaryVarargOverloads @JvmOverloads constructor(
          vararg values: Int?,
          suffix: String = ""
        )
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.NullablePrimaryVarargOverloads", "NullablePrimaryVarargOverloads.kt", source,
          emptySet(), mode)
      assertNotNull("Nullable non-trailing primary vararg generation failed in $mode", stub)
      assertContains(stub!!,
          "public NullablePrimaryVarargOverloads(Integer[] values, String suffix)")
      assertContains(stub, "public NullablePrimaryVarargOverloads(Integer... values)")
      assertFalse(stub.contains("NullablePrimaryVarargOverloads(int[] values, String suffix)"))
      assertFalse(stub.contains("NullablePrimaryVarargOverloads(int... values)"))
    }
  }

  @Test
  fun generate_projectsGenericPrimaryConstructorVarargsWithoutErasingComponents() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class GenericLabels<T>(vararg values: T)
        class BoundedLabels<T : CharSequence>(vararg values: T)
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val generic = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.GenericLabels", "GenericPrimaryVarargs.kt", source, emptySet(), mode)
      val bounded = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.BoundedLabels", "GenericPrimaryVarargs.kt", source, emptySet(), mode)
      assertNotNull("Generic primary vararg generation failed in $mode", generic)
      assertNotNull("Bounded primary vararg generation failed in $mode", bounded)
      assertContains(generic!!, "public class GenericLabels<T>")
      assertContains(generic, "public GenericLabels(T... values)")
      assertContains(bounded!!, "public class BoundedLabels<T extends CharSequence>")
      assertContains(bounded, "public BoundedLabels(T... values)")
      assertFalse(generic.contains("public GenericLabels(Object... values)"))
      assertFalse(bounded.contains("public BoundedLabels(CharSequence... values)"))
    }
  }

  @Test
  fun generate_projectsValueClassesAsOpaqueNonConstructibleBoxedTypes() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String) {
          fun display(): String = raw
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.UserId", "UserId.kt", source, emptySet(), mode)
      assertNotNull("Value class generation failed in $mode", stub)
      assertTrue("Unexpected value class declaration in $mode:\n$stub",
          stub!!.contains("public final class UserId"))
      assertTrue("Unexpected value class constructor in $mode:\n$stub",
          stub.contains("private UserId(String raw)"))
      assertFalse(stub.contains("public UserId("))
      assertFalse(stub.contains("protected UserId()"))
      assertFalse(stub.contains("getRaw()"))
      assertFalse(stub.contains("display()"))
      assertFalse(stub.contains("__kotlin_abi_synthetic_constructor__"))
    }
  }

  @Test
  fun generate_omitsScalarValueClassConstructorsButKeepsBoxedContainerSurfaces() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String)

        class DirectHolder(val id: UserId)
        class NullableHolder(var id: UserId?)
        class DefaultedHolder @JvmOverloads constructor(val id: UserId, count: Int = 0)
        class ArrayHolder @JvmOverloads constructor(var ids: Array<UserId>, count: Int = 0)
        class GenericHolder(var ids: List<UserId>)

        class SecondaryHolder private constructor() {
          constructor(id: UserId) : this()
          constructor(ids: Array<UserId>) : this()
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val direct = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.DirectHolder", "ValueClassConstructors.kt", source, emptySet(), mode)
      val nullable = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.NullableHolder", "ValueClassConstructors.kt", source, emptySet(), mode)
      val defaulted = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.DefaultedHolder", "ValueClassConstructors.kt", source, emptySet(), mode)
      val array = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.ArrayHolder", "ValueClassConstructors.kt", source, emptySet(), mode)
      val generic = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.GenericHolder", "ValueClassConstructors.kt", source, emptySet(), mode)
      val secondary = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.SecondaryHolder", "ValueClassConstructors.kt", source, emptySet(), mode)

      assertNotNull("Direct value-class constructor generation failed in $mode", direct)
      assertNotNull("Nullable value-class constructor generation failed in $mode", nullable)
      assertNotNull("Defaulted value-class constructor generation failed in $mode", defaulted)
      assertNotNull("Value-class array constructor generation failed in $mode", array)
      assertNotNull("Generic value-class constructor generation failed in $mode", generic)
      assertNotNull("Secondary value-class constructor generation failed in $mode", secondary)

      assertFalse("Direct scalar constructor leaked in $mode:\n$direct",
          direct!!.contains("public DirectHolder("))
      assertFalse("Nullable scalar constructor leaked in $mode:\n$nullable",
          nullable!!.contains("public NullableHolder("))
      assertFalse("Defaulted scalar constructor leaked in $mode:\n$defaulted",
          defaulted!!.contains("public DefaultedHolder("))
      assertFalse("Scalar property getter leaked in $mode:\n$direct",
          direct.contains("getId()"))
      assertFalse("Nullable scalar property getter leaked in $mode:\n$nullable",
          nullable.contains("getId()"))
      assertFalse("Nullable scalar property setter leaked in $mode:\n$nullable",
          nullable.contains("setId("))
 
      assertContains(array!!, "public ArrayHolder(UserId[] ids, int count)")
      assertContains(array, "public ArrayHolder(UserId[] ids)")
      assertContains(array, "public UserId[] getIds()")
      assertContains(array, "public void setIds(UserId[] value)")
      assertContains(generic!!, "public GenericHolder(java.util.List<UserId> ids)")
      assertContains(generic, "public java.util.List<UserId> getIds()")
      assertContains(generic, "public void setIds(java.util.List<UserId> value)")

      assertContains(secondary!!, "private SecondaryHolder()")
      assertContains(secondary, "public SecondaryHolder(UserId[] ids)")
      assertFalse("Scalar secondary constructor leaked in $mode:\n$secondary",
          secondary.contains("public SecondaryHolder(UserId id)"))
      assertFalse("Underlying scalar secondary constructor leaked in $mode:\n$secondary",
          secondary.contains("public SecondaryHolder(String id)"))
    }
  }

  @Test
  fun generatedValueClassConstructorStubs_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String)

        class DirectHolder(val id: UserId)
        class NullableHolder(var id: UserId?)
        class ArrayHolder(var ids: Array<UserId>)
        class GenericHolder(var ids: List<UserId>)
        """.trimIndent()
    val qualifiedNames = listOf(
      "sample.UserId",
      "sample.DirectHolder",
      "sample.NullableHolder",
      "sample.ArrayHolder",
      "sample.GenericHolder",
    )
    val stubs = qualifiedNames.associateWith { qualifiedName ->
      KotlinJvmAbiStubGenerator.generateForTest(
        qualifiedName,
        "ValueClassConstructors.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      ) ?: error("Missing generated stub for $qualifiedName")
    }

    val supportedConsumer =
        """
        package consumer;
        import sample.ArrayHolder;
        import sample.GenericHolder;
        import sample.UserId;
        class SupportedConstructors {
          void use(UserId[] ids, java.util.List<UserId> list) {
            ArrayHolder array = new ArrayHolder(ids);
            array.setIds(array.getIds());
            GenericHolder generic = new GenericHolder(list);
            generic.setIds(generic.getIds());
          }
        }
        """.trimIndent()
    assertTrue(
      "Boxed value-class constructor/property surfaces must be attributable by javac:\n$stubs",
      javacSucceeds(stubs, "consumer.SupportedConstructors", supportedConsumer),
    )

    val unsupportedMethods = listOf(
      "void use() { new DirectHolder(\"id\"); }",
      "void use(DirectHolder value) { value.getId(); }",
      "void use() { new NullableHolder(\"id\"); }",
      "void use(NullableHolder value) { value.getId(); }",
      "void use(NullableHolder value) { value.setId(\"id\"); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      val unsupportedConsumer =
          """
          package consumer;
          import sample.DirectHolder;
          import sample.NullableHolder;
          class UnsupportedConstructors$index {
            $method
          }
          """.trimIndent()
      assertFalse(
        "Scalar value-class surface must not be attributable: $method\n$stubs",
        javacSucceeds(stubs, "consumer.UnsupportedConstructors$index", unsupportedConsumer),
      )
    }
  }

  @Test
  fun generatedValueClassCompanionStubs_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String)

        class FieldHost {
          companion object {
            @JvmField val ids: Array<UserId> = emptyArray()
          }
        }

        class StaticScalarHost {
          companion object {
            @JvmStatic var id: UserId = UserId("static")
          }
        }

        class NamedStaticScalarHost {
          companion object {
            @get:JvmName("readId")
            @set:JvmName("writeId")
            @JvmStatic var id: UserId = UserId("named")
          }
        }
        """.trimIndent()
    val qualifiedNames = listOf(
      "sample.UserId",
      "sample.FieldHost",
      "sample.StaticScalarHost",
      "sample.NamedStaticScalarHost",
    )
    val stubs = qualifiedNames.associateWith { qualifiedName ->
      KotlinJvmAbiStubGenerator.generateForTest(
        qualifiedName,
        "ValueClassCompanions.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      ) ?: error("Missing generated stub for $qualifiedName")
    }

    val supportedConsumer =
        """
        package consumer;
        import sample.FieldHost;
        import sample.NamedStaticScalarHost;
        import sample.UserId;
        class SupportedCompanions {
          void use(UserId[] ids) {
            UserId[] field = FieldHost.ids;
            String id = NamedStaticScalarHost.readId();
            NamedStaticScalarHost.writeId(id);
          }
        }
        """.trimIndent()
    assertTrue(
      "Proven value-class companion surface must be attributable by javac:\n$stubs",
      javacSucceeds(stubs, "consumer.SupportedCompanions", supportedConsumer),
    )

    val unsupportedMethods = listOf(
      "void use() { FieldHost.getIds(); }",
      "void use() { FieldHost.setIds(null); }",
      "void use() { StaticScalarHost.getId(); }",
      "void use() { StaticScalarHost.setId(\"id\"); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      val unsupportedConsumer =
          """
          package consumer;
          import sample.FieldHost;
          import sample.StaticScalarHost;
          class UnsupportedCompanions$index {
            $method
          }
          """.trimIndent()
      assertFalse(
        "Unproven companion accessor must not be attributable: $method\n$stubs",
        javacSucceeds(stubs, "consumer.UnsupportedCompanions$index", unsupportedConsumer),
      )
    }
  }

  @Test
  fun generatedValueClassMemberPropertyStubs_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String)

        class MemberScalar {
          var id: UserId = UserId("member")
        }

        class NamedScalar {
          @get:JvmName("readId")
          @set:JvmName("writeId")
          var id: UserId = UserId("named")
        }

        class ArrayMember {
          var ids: Array<UserId> = emptyArray()
        }

        class GenericMember {
          var ids: List<UserId> = emptyList()
        }
        """.trimIndent()
    val qualifiedNames = listOf(
      "sample.UserId",
      "sample.MemberScalar",
      "sample.NamedScalar",
      "sample.ArrayMember",
      "sample.GenericMember",
    )
    val stubs = qualifiedNames.associateWith { qualifiedName ->
      KotlinJvmAbiStubGenerator.generateForTest(
        qualifiedName,
        "ValueClassMemberProperties.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      ) ?: error("Missing generated stub for $qualifiedName")
    }

    val supportedConsumer =
        """
        package consumer;
        import sample.ArrayMember;
        import sample.GenericMember;
        import sample.NamedScalar;
        import sample.UserId;
        class SupportedMemberProperties {
          void use(UserId[] ids, java.util.List<UserId> list) {
            NamedScalar scalar = new NamedScalar();
            String id = scalar.readId();
            scalar.writeId(id);
            ArrayMember array = new ArrayMember();
            array.setIds(ids);
            UserId[] result = array.getIds();
            GenericMember generic = new GenericMember();
            generic.setIds(list);
            java.util.List<UserId> genericResult = generic.getIds();
          }
        }
        """.trimIndent()
    assertTrue(
      "Proven member-property surface must be attributable by javac:\n$stubs",
      javacSucceeds(stubs, "consumer.SupportedMemberProperties", supportedConsumer),
    )

    val unsupportedMethods = listOf(
      "void use(MemberScalar value) { value.getId(); }",
      "void use(MemberScalar value) { value.setId(\"id\"); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      val unsupportedConsumer =
          """
          package consumer;
          import sample.MemberScalar;
          class UnsupportedMemberProperties$index {
            $method
          }
          """.trimIndent()
      assertFalse(
        "Mangled scalar member accessor must not be attributable: $method\n$stubs",
        javacSucceeds(stubs, "consumer.UnsupportedMemberProperties$index", unsupportedConsumer),
      )
    }
  }

  @Test
  fun generatedConflictingJvmSurfaces_areNotAttributableByJavac() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val functionConflictSource =
        """
        package sample

        @JvmName("duplicate")
        fun first(value: String): String = value
        @JvmName("duplicate")
        fun second(value: String): Int = value.length
        @JvmName("duplicate")
        fun overloaded(value: Int): Int = value
        fun retained(value: Boolean): Boolean = value
        """.trimIndent()
    val functionConflictStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.ConflictApiKt",
      "ConflictApi.kt",
      functionConflictSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    )
    assertNotNull(functionConflictStub)
    val functionStubs = mapOf("sample.ConflictApiKt" to functionConflictStub!!)
    assertTrue(
      "Legal overloads must remain attributable after conflicting surface rejection:\n$functionConflictStub",
      javacSucceeds(
        functionStubs,
        "consumer.SupportedFunctionConflict",
        """
        package consumer;
        import sample.ConflictApiKt;
        class SupportedFunctionConflict {
          int useInt() { return ConflictApiKt.duplicate(1); }
          boolean useBoolean() { return ConflictApiKt.retained(true); }
        }
        """.trimIndent(),
      ),
    )
    assertFalse(
      "Conflicting duplicate(String) surface must not be attributable:\n$functionConflictStub",
      javacSucceeds(
        functionStubs,
        "consumer.UnsupportedFunctionConflict",
        """
        package consumer;
        import sample.ConflictApiKt;
        class UnsupportedFunctionConflict {
          String use() { return ConflictApiKt.duplicate("value"); }
        }
        """.trimIndent(),
      ),
    )

    val accessorConflictSource =
        """
        package sample

        @get:JvmName("readShared")
        val first: String = "first"
        fun readShared(): String = "function"
        @get:JvmName("readShared")
        val second: Int = 2
        fun retained(value: Int): Int = value
        """.trimIndent()
    val accessorConflictStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.AccessorConflictKt",
      "AccessorConflict.kt",
      accessorConflictSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    )
    assertNotNull(accessorConflictStub)
    val accessorStubs = mapOf("sample.AccessorConflictKt" to accessorConflictStub!!)
    assertTrue(
      "Non-conflicting accessor facade surface must remain attributable:\n$accessorConflictStub",
      javacSucceeds(
        accessorStubs,
        "consumer.SupportedAccessorConflict",
        """
        package consumer;
        import sample.AccessorConflictKt;
        class SupportedAccessorConflict {
          int use() { return AccessorConflictKt.retained(1); }
        }
        """.trimIndent(),
      ),
    )
    assertFalse(
      "Conflicting readShared() surface must not be attributable:\n$accessorConflictStub",
      javacSucceeds(
        accessorStubs,
        "consumer.UnsupportedAccessorConflict",
        """
        package consumer;
        import sample.AccessorConflictKt;
        class UnsupportedAccessorConflict {
          String use() { return AccessorConflictKt.readShared(); }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun generate_projectsValueClassUsesOnlyWithExplicitJvmNames() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String)

        fun hidden(id: UserId): UserId = id

        @JvmName("findRaw")
        fun find(id: UserId): UserId = id

        @JvmName("nullableRaw")
        fun nullable(id: UserId?): UserId? = id

        @JvmName("boxedList")
        fun list(ids: List<UserId>): List<UserId> = ids

        @JvmName("boxedArray")
        fun array(ids: Array<UserId>): Int = ids.size

        @JvmName("unsafeVararg")
        fun varargs(vararg ids: UserId): Int = ids.size

        fun UserId.hiddenExtension(): UserId = this

        @JvmName("renderRaw")
        fun UserId.render(): UserId = this

        val hiddenProperty: UserId = UserId("hidden")

        @get:JvmName("readRaw")
        val exposedProperty: UserId = UserId("exposed")
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.ValueUsesKt", "ValueUses.kt", source, emptySet(), mode)
      assertNotNull("Value-class facade generation failed in $mode", stub)
      assertFalse("Mangled function leaked in $mode:\n$stub", stub!!.contains(" hidden("))
      assertFalse("Mangled extension leaked in $mode:\n$stub", stub.contains("hiddenExtension"))
      assertFalse("Mangled property leaked in $mode:\n$stub", stub.contains("getHiddenProperty"))
      assertContains(stub, "String findRaw(String id)")
      assertContains(stub, "UserId nullableRaw(UserId id)")
      assertContains(stub, "java.util.List<UserId> boxedList(java.util.List<UserId> ids)")
      assertContains(stub, "int boxedArray(UserId[] ids)")
      assertFalse("Value-class vararg leaked in $mode:\n$stub", stub.contains("unsafeVararg("))
      assertFalse(stub.contains("unsafeVararg(String... ids)"))
      assertFalse(stub.contains("unsafeVararg(String[] ids)"))
      assertContains(stub, "String renderRaw(String receiver)")
      assertContains(stub, "String readRaw()")
    }
  }

  @Test
  fun generate_expandsVisibleCrossFileDirectTypeAliases() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        fun greet(name: UserName): UserName = name
        fun scores(): Scores = emptyList()
        fun indirect(value: IndirectName): IndirectName = value
        """.trimIndent()
    val visibleAliases = linkedMapOf(
        "UserName" to "String",
        "Scores" to "List<Int>"
    )

    val stub = KotlinJvmAbiStubGenerator.generate(
        "sample.ApiKt", "Api.kt", source, emptySet(), visibleAliases)
    assertNotNull("Cross-file typealias facade generation failed", stub)
    assertContains(stub!!, "String greet(String name)")
    assertContains(stub, "java.util.List<Integer> scores()")
    assertContains(stub, "Object indirect(Object value)")
  }

  @Test
  fun generate_mergesMultifileFacadePartsIntoOneStableJvmSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val firstPart =
        """
        @file:JvmName("Api")
        @file:JvmMultifileClass
        package sample

        fun load(id: Int): String = id.toString()
        """.trimIndent()
    val secondPart =
        """
        @file:JvmName("Api")
        @file:JvmMultifileClass
        package sample

        fun save(value: String): Boolean = value.isNotEmpty()
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val firstStub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.Api", "ApiOne.kt", firstPart, emptySet(), mode)
      val secondStub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.Api", "ApiTwo.kt", secondPart, emptySet(), mode)
      assertNotNull("First multifile part failed in $mode", firstStub)
      assertNotNull("Second multifile part failed in $mode", secondStub)
      val merged = KotlinJvmAbiStubGenerator.mergeGeneratedStubs(firstStub!!, secondStub!!)
      assertContains(merged, "public final class Api")
      assertContains(merged, "String load(int id)")
      assertContains(merged, "boolean save(String value)")
      assertEquals(1, Regex("\\bload\\(int id\\)").findAll(merged).count())
      assertEquals(1, Regex("\\bsave\\(String value\\)").findAll(merged).count())
      assertFalse(merged.contains("Api__ApiOneKt"))
      assertFalse(merged.contains("Api__ApiTwoKt"))
    }
  }

  @Test
  fun generate_omitsJvmSyntheticJvmSurfaces() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmSynthetic
        fun hiddenTop(value: String): String = value
        fun publicTop(value: String): String = value

        @JvmSynthetic
        val hiddenProperty: String = "hidden"
        val publicProperty: String = "public"

        @JvmSynthetic
        fun String.hiddenExtension(): String = this

        class Api {
          @JvmSynthetic fun hiddenMember(): String = "hidden"
          fun publicMember(): String = "public"

          companion object {
            @JvmStatic @JvmSynthetic fun hiddenStatic(): Api = Api()
            @JvmStatic fun publicStatic(): Api = Api()
          }
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val facade = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.SyntheticApiKt", "SyntheticApi.kt", source, emptySet(), mode)
      val api = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.Api", "SyntheticApi.kt", source, emptySet(), mode)
      assertNotNull("Synthetic facade generation failed in $mode", facade)
      assertNotNull("Synthetic type generation failed in $mode", api)
      assertFalse("Synthetic facade member leaked in $mode:\n$facade", facade!!.contains("hiddenTop("))
      assertFalse("Synthetic property leaked in $mode:\n$facade", facade.contains("getHiddenProperty"))
      assertFalse("Synthetic extension leaked in $mode:\n$facade", facade.contains("hiddenExtension"))
      assertContains(facade, "String publicTop(String value)")
      assertContains(facade, "String getPublicProperty()")
      assertFalse("Synthetic class member leaked in $mode:\n$api", api!!.contains("hiddenMember("))
      assertFalse("Synthetic static member leaked in $mode:\n$api", api.contains("hiddenStatic("))
      assertContains(api, "String publicMember()")
      assertContains(api, "static Api publicStatic()")
    }
  }

  @Test
  fun generate_rejectsBoundedOwnerTypeVariableArrayErasureConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface BoundedOwnerArrayConflict<T : CharSequence> {
          @JvmName("store")
          fun generic(values: Array<T>): String
          fun store(values: Array<CharSequence>): String
          fun store(values: Array<String>): String
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.BoundedOwnerArrayConflict", "BoundedOwnerArrayConflict.kt", source,
          emptySet(), mode)
      assertNotNull("Bounded owner array conflict generation failed in $mode", stub)
      assertFalse("Erased CharSequence[] owner surface leaked in $mode:\n$stub",
          stub!!.contains("store(T[] values)") || stub.contains("store(CharSequence[] values)"))
      assertContains(stub, "String store(String[] values);")
    }
  }

  @Test
  fun generate_usesMethodArrayBoundBeforeSameNamedOwnerVariable() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface MethodArrayShadowConflict<T : Number> {
          @JvmName("submit")
          fun <T : CharSequence> generic(values: Array<T>): String
          fun submit(values: Array<CharSequence>): String
          fun submit(values: Array<Number>): String
          fun submit(values: Array<String>): String
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.MethodArrayShadowConflict", "MethodArrayShadowConflict.kt", source,
          emptySet(), mode)
      assertNotNull("Method array shadow conflict generation failed in $mode", stub)
      assertFalse("Method array T used the wrong erasure in $mode:\n$stub",
          stub!!.contains("submit(T[] values)") || stub.contains("submit(CharSequence[] values)"))
      assertContains(stub, "String submit(Number[] values);")
      assertContains(stub, "String submit(String[] values);")
    }
  }

  @Test
  fun generate_rejectsTypeVariableReferenceArrayErasureConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface GenericArrayConflict<T> {
          @JvmName("store")
          fun storeGeneric(values: Array<T>): String
          @JvmName("store")
          fun storeAny(values: Array<Any>): String
          @JvmName("store")
          fun storeStrings(values: Array<String>): String
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.GenericArrayConflict", "GenericArrayConflict.kt", source, emptySet(), mode)
      assertNotNull("Generic array conflict generation failed in $mode", stub)
      assertFalse("Erased Object[] surface leaked in $mode:\n$stub",
          stub!!.contains("store(T[] values)") || stub.contains("store(Object[] values)"))
      assertContains(stub, "String store(String[] values);")
    }
  }

  @Test
  fun generate_projectsReferenceArrayVarianceWithoutErasingComponentType() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        fun read(values: Array<out String>): String = values.joinToString()
        fun write(values: Array<in String>): Int = values.size
        fun unknown(values: Array<*>): Int = values.size
        fun boxed(values: Array<Int>): Int = values.size
        fun nullableNumbers(values: Array<Int?>): Int = values.size
        fun nullableLabels(values: Array<String?>): Int = values.size
        fun nullableNested(values: Array<Array<Int?>?>): Int = values.size
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.ArrayVarianceKt", "ArrayVariance.kt", source, emptySet(), mode)
      assertNotNull("Array variance generation failed in $mode", stub)
      assertContains(stub!!, "String read(String[] values)")
      assertContains(stub, "int write(String[] values)")
      assertContains(stub, "int unknown(Object[] values)")
      assertContains(stub, "int boxed(Integer[] values)")
      assertFalse(stub.contains("int boxed(int[] values)"))
      assertContains(stub, "int nullableNumbers(Integer[] values)")
      assertContains(stub, "int nullableLabels(String[] values)")
      assertContains(stub, "int nullableNested(Integer[][] values)")
    }
  }

  @Test
  fun generate_keepsDifferentReferenceArrayDimensionsAsLegalOverloads() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface ArrayDimensionOverloads<T> {
          @JvmName("store")
          fun storeSingle(values: Array<T>): String
          @JvmName("store")
          fun storeNested(values: Array<Array<Any>>): String
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.ArrayDimensionOverloads", "ArrayDimensionOverloads.kt", source,
          emptySet(), mode)
      assertNotNull("Array dimension overload generation failed in $mode", stub)
      assertContains(stub!!, "String store(T[] values);")
      assertContains(stub, "String store(Object[][] values);")
    }
  }

  @Test
  fun generate_rejectsPrimaryAndSecondaryConstructorArrayVarargJvmSurfaceConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class ConstructorArrayConflict(vararg values: String) {
          constructor(values: Array<String>) : this(*values)
          constructor(values: IntArray) : this()
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ConstructorArrayConflict", "ConstructorArrayConflict.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull("Constructor array/vararg conflict generation failed", stub)
    assertFalse("Conflicting String[] constructors leaked:\n$stub",
        stub!!.contains("ConstructorArrayConflict(String... values)")
            || stub.contains("ConstructorArrayConflict(String[] values)"))
    assertContains(stub, "public ConstructorArrayConflict(int[] values)")
  }

  @Test
  fun generate_rejectsNonTrailingVarargConstructorArrayJvmSurfaceConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class NonTrailingVarargConstructorConflict(vararg values: String, suffix: String) {
          constructor(values: Array<String>, suffix: String) : this(*values, suffix)
          constructor(values: Array<String>, count: Int) : this(*values, count.toString())
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.NonTrailingVarargConstructorConflict", "NonTrailingVarargConstructorConflict.kt",
        source, emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull("Non-trailing vararg constructor conflict generation failed", stub)
    assertFalse("Conflicting (String[], String) constructors leaked:\n$stub",
        stub!!.contains("NonTrailingVarargConstructorConflict(String[] values, String suffix)"))
    assertContains(stub, "public NonTrailingVarargConstructorConflict(String[] values, int count)")
  }

  @Test
  fun generate_rejectsGenericArrayPrimaryJvmOverloadsVariantConflictingWithSecondaryConstructor() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class GenericArrayOverloadConstructorConflict<T> @JvmOverloads constructor(
          values: Array<T>,
          suffix: String = ""
        ) {
          constructor(values: Array<Any>) : this(emptyArray<T>())
          constructor(values: Array<Array<Any>>) : this(emptyArray())
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.GenericArrayOverloadConstructorConflict",
          "GenericArrayOverloadConstructorConflict.kt", source, emptySet(), mode)
      assertNotNull("Generic-array constructor conflict generation failed in $mode", stub)
      assertFalse("Conflicting Object[] constructor surface leaked in $mode:\n$stub",
          stub!!.contains("GenericArrayOverloadConstructorConflict(T[] values)")
              || stub.contains("GenericArrayOverloadConstructorConflict(Object[] values)"))
      assertContains(stub,
          "public GenericArrayOverloadConstructorConflict(Object[][] values)")
    }
  }

  @Test
  fun generate_fallbackRejectsPrimaryJvmOverloadsVariantConflictingWithSecondaryConstructor() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class FallbackOverloadConstructorConflict @JvmOverloads constructor(
          val value: String,
          retries: Int = 0
        ) {
          constructor(value: String) : this(value, 0)
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.FallbackOverloadConstructorConflict", "FallbackOverloadConstructorConflict.kt", source,
        emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)
    assertNotNull("Fallback constructor conflict generation failed", stub)
    assertFalse("Conflicting String constructor surface leaked:\n$stub",
        stub!!.contains("FallbackOverloadConstructorConflict(String value)"))
    assertContains(stub, "public FallbackOverloadConstructorConflict(String value, int retries)")
  }

  @Test
  fun generate_rejectsVarargAndReferenceArrayJvmSurfaceConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmName("join")
        fun joinVararg(vararg values: String): String = values.joinToString()
        @JvmName("join")
        fun joinArray(values: Array<String>): String = values.joinToString()
        @JvmName("join")
        fun joinInts(values: IntArray): String = values.joinToString()
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.ArrayErasureConflictKt", "ArrayErasureConflict.kt", source, emptySet(), mode)
      assertNotNull("Vararg/array conflict generation failed in $mode", stub)
      assertFalse("String[] JVM surface leaked in $mode:\n$stub",
          stub!!.contains("join(String... values)") || stub.contains("join(String[] values)"))
      assertContains(stub, "String join(int[] values)")
    }
  }

  @Test
  fun generate_rejectsParameterizedArgumentErasureConflictWithoutDroppingDifferentRawTypes() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmName("consume")
        fun consumeStrings(value: List<String>): String = value.joinToString()
        @JvmName("consume")
        fun consumeInts(value: List<Int>): String = value.joinToString()
        @JvmName("consume")
        fun consumeSet(value: Set<String>): String = value.joinToString()
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.ParameterizedErasureConflictKt", "ParameterizedErasureConflict.kt", source,
          emptySet(), mode)
      assertNotNull("Parameterized erasure conflict generation failed in $mode", stub)
      assertFalse("Conflicting List JVM surfaces leaked in $mode:\n$stub",
          stub!!.contains("consume(java.util.List<String> value)")
              || stub.contains("consume(java.util.List<Integer> value)"))
      assertContains(stub, "String consume(java.util.Set<String> value)")
    }
  }

  @Test
  fun generate_usesBoundedMethodTypeParameterErasureBeforeSameNamedOwnerVariable() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface MethodTypeShadowingConflict<T : Number> {
          @JvmName("submit")
          fun <T : CharSequence> generic(value: T): T
          fun submit(value: CharSequence): CharSequence
          fun submit(value: Number): Number
          fun submit(value: String): String
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.MethodTypeShadowingConflict", "MethodTypeShadowingConflict.kt", source,
          emptySet(), mode)
      assertNotNull("Bounded method shadowing conflict generation failed in $mode", stub)
      assertFalse("Method T was incorrectly erased through owner T in $mode:\n$stub",
          stub!!.contains("submit(T value)") || stub.contains("submit(CharSequence value)"))
      assertContains(stub, "Number submit(Number value);")
      assertContains(stub, "String submit(String value);")
    }
  }

  @Test
  fun generate_rejectsMethodTypeParameterErasureConflictWithoutTreatingReturnGenericsAsParameters() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmName("put")
        fun <T> generic(value: T): T = value
        fun put(value: Any): Any = value
        fun put(value: String): String = value
        fun collection(value: String): List<String> = listOf(value)
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.MethodErasureConflictKt", "MethodErasureConflict.kt", source, emptySet(), mode)
      assertNotNull("Method type-parameter conflict generation failed in $mode", stub)
      assertFalse("Erased Object put surface leaked in $mode:\n$stub",
          stub!!.contains("put(T value)") || stub.contains("put(Object value)"))
      assertContains(stub, "String put(String value)")
      assertContains(stub, "java.util.List<String> collection(String value)")
    }
  }

  @Test
  fun generate_rejectsConflictingJvmMethodSurfacesWithoutDroppingLegalOverloads() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmName("duplicate")
        fun first(value: String): String = value
        @JvmName("duplicate")
        fun second(value: String): Int = value.length
        @JvmName("duplicate")
        fun overloaded(value: Int): Int = value
        fun retained(value: Boolean): Boolean = value
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.ConflictApiKt", "ConflictApi.kt", source, emptySet(), mode)
      assertNotNull("Conflict facade generation failed in $mode", stub)
      assertFalse("Conflicting JVM surface leaked in $mode:\n$stub", stub!!.contains("duplicate(String value)"))
      assertContains(stub, "int duplicate(int value)")
      assertContains(stub, "boolean retained(boolean value)")
    }
  }

  @Test
  fun generate_rejectsConflictingPropertyAccessorJvmSurfaces() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @get:JvmName("readShared")
        val first: String = "first"
        fun readShared(): String = "function"
        @get:JvmName("readShared")
        val second: Int = 2
        fun retained(value: Int): Int = value
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.AccessorConflictKt", "AccessorConflict.kt", source, emptySet(), mode)
      assertNotNull("Accessor conflict facade generation failed in $mode", stub)
      assertFalse("Conflicting accessor/function surface leaked in $mode:\n$stub",
          stub!!.contains("readShared()"))
      assertContains(stub, "int retained(int value)")
    }
  }

  @Test
  fun generate_projectsBooleanIsPropertyAccessorNamesAndSyntheticVisibility() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @get:JvmName("readReady")
        @set:JvmName("writeReady")
        var isReady: Boolean = false

        @get:JvmSynthetic
        var isInternal: Boolean = false
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.BooleanPropertiesKt", "BooleanProperties.kt", source, emptySet(), mode)
      assertNotNull("Boolean property facade generation failed in $mode", stub)
      assertContains(stub!!, "boolean readReady()")
      assertContains(stub, "void writeReady(boolean value)")
      assertFalse("Default Boolean getter leaked in $mode:\n$stub", stub.contains("isReady()"))
      assertFalse("Default Boolean setter leaked in $mode:\n$stub", stub.contains("setReady("))
      assertFalse("Synthetic getter leaked in $mode:\n$stub", stub.contains("isInternal()"))
      assertContains(stub, "void setInternal(boolean value)")
    }
  }

  @Test
  fun generate_projectsRestrictedSameFileGenericTypeAliases() {
TreeSitter.loadLibrary()
System.loadLibrary("tree-sitter-kotlin")
val source =
"""
package sample

typealias Box<T> = List<T>
typealias PairBox<K, V> = Map<K, V>
typealias NullableBox<T> = List<T>?
typealias NestedBox<T> = List<List<T>>
typealias Callback<T> = (T) -> Unit

fun names(): Box<String> = emptyList()
fun accept(value: Box<Int>): Unit {}
fun lookup(value: PairBox<String, Long>): PairBox<String, Long> = emptyMap()
fun nullable(value: NullableBox<String>) = value
fun nested(value: NestedBox<String>) = value
fun callback(value: Callback<String>) = value
""".trimIndent()

for (mode in listOf(
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
val stub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.GenericAliasesKt", "GenericAliases.kt", source, emptySet(), mode)
assertNotNull("Generic typealias facade generation failed in $mode", stub)
assertContains(stub!!, "java.util.List<String> names()")
assertContains(stub, "void accept(java.util.List<Integer> value)")
assertContains(stub, "java.util.Map<String, Long> lookup(java.util.Map<String, Long> value)")
assertContains(stub, "Object nullable(Object value)")
assertContains(stub, "Object nested(Object value)")
assertContains(stub, "Object callback(Object value)")
}
}

@Test
fun generate_expandsOnlyDirectSameFileTypeAliases() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        typealias UserName = String
        typealias Scores = List<Int>
        typealias IndirectName = UserName
        typealias Callback = (String) -> Unit

        fun greet(name: UserName): UserName = name
        fun scores(): Scores = emptyList()
        fun indirect(name: IndirectName): IndirectName = name
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.AliasesKt", "Aliases.kt", source, emptySet(), mode)
      assertNotNull("Typealias facade generation failed in $mode", stub)
      assertContains(stub!!, "String greet(String name)")
      assertContains(stub, "java.util.List<Integer> scores()")
      assertContains(stub, "Object indirect(Object name)")
    }
  }

  @Test
  fun generate_omitsSuspendFunctionsRatherThanFakingContinuationAbi() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        suspend fun fetch(id: Int): String = id.toString()
        fun regular(id: Int): String = id.toString()
        suspend fun String.render(): String = this

        class Service {
          suspend fun load(): String = "value"
          fun available(): Boolean = true

          companion object {
            @JvmStatic suspend fun create(): Service = Service()
            @JvmStatic fun plain(): Service = Service()
          }
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val facade = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.SuspendApiKt", "SuspendApi.kt", source, emptySet(), mode)
      val service = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.Service", "SuspendApi.kt", source, emptySet(), mode)
      assertNotNull("Suspend facade generation failed in $mode", facade)
      assertNotNull("Suspend type generation failed in $mode", service)
      assertFalse("Suspend facade function leaked in $mode:\n$facade", facade!!.contains("fetch("))
      assertFalse("Suspend extension leaked in $mode:\n$facade", facade.contains("render("))
      assertContains(facade, "String regular(int id)")
      assertFalse("Suspend member leaked in $mode:\n$service", service!!.contains("load("))
      assertFalse("Suspend @JvmStatic leaked in $mode:\n$service", service.contains("create("))
      assertContains(service, "boolean available()")
      assertContains(service, "static Service plain()")
    }
  }

  @Test
  fun generate_projectsConstructorPropertiesAndMembers() {
    val source =
        """
        package sample

        class Person(val name: String, var age: Int) {
          fun greet(prefix: String): String = prefix + name
          val enabled: Boolean = true
          var score: Long = 0
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Person", "Person.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "package sample;")
    assertContains(stub, "public class Person")
    assertContains(stub, "public Person(String name, int age)")
    assertContains(stub, "public String getName()")
    assertContains(stub, "public int getAge()")
    assertContains(stub, "public void setAge(int value)")
    assertContains(stub, "public String greet(String prefix)")
    assertContains(stub, "public boolean getEnabled()")
    assertContains(stub, "public long getScore()")
    assertContains(stub, "public void setScore(long value)")
  }

  @Test
  fun generate_projectsBooleanIsPropertiesWithJvmAccessorNames() {
    val classSource =
        """
        package sample

        class Status(var isReady: Boolean, val isOptional: Boolean?) {
          var isActive: Boolean = true
          val issue: Boolean = false
          var isLabel: String = "label"

          companion object {
            @JvmStatic var isOnline: Boolean = true
          }
        }
        """.trimIndent()
    val facadeSource =
        """
        package sample

        var isFeatureEnabled: Boolean = true
        """.trimIndent()

    val classStub = KotlinJvmAbiStubGenerator.generate("sample.Status", "Status.kt", classSource)
    val facadeStub =
        KotlinJvmAbiStubGenerator.generate("sample.FeaturesKt", "Features.kt", facadeSource)

    assertNotNull(classStub)
    assertContains(classStub!!, "public boolean isReady()")
    assertContains(classStub, "public void setReady(boolean value)")
    assertContains(classStub, "public boolean isActive()")
    assertContains(classStub, "public void setActive(boolean value)")
    assertContains(classStub, "public Boolean getIsOptional()")
    assertContains(classStub, "public boolean getIssue()")
    assertContains(classStub, "public String getIsLabel()")
    assertContains(classStub, "public void setIsLabel(String value)")
    assertContains(classStub, "public static boolean isOnline()")
    assertContains(classStub, "public static void setOnline(boolean value)")
    assertFalse(classStub.contains("getIsActive()"))
    assertFalse(classStub.contains("setIsActive(boolean value)"))

    assertNotNull(facadeStub)
    assertContains(facadeStub!!, "public static boolean isFeatureEnabled()")
    assertContains(facadeStub, "public static void setFeatureEnabled(boolean value)")
    assertFalse(facadeStub.contains("getIsFeatureEnabled()"))
  }

  @Test
  fun generate_projectsJvmNameForFunctionsAndProperties() {
    val source =
        """
        package sample

        @JvmName("loadValue")
        fun load(): String = "value"

        @JvmName("fetchValue")
        @JvmOverloads
        fun fetch(id: Int, suffix: String = ""): String = id.toString() + suffix

        @JvmName("renderText")
        fun String.render(): String = this

        @get:JvmName("readMode")
        @set:JvmName("writeMode")
        var mode: String = "default"
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.NamedApiKt", "NamedApi.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public static String loadValue()")
    assertContains(stub, "public static String fetchValue(int id, String suffix)")
    assertContains(stub, "public static String fetchValue(int id)")
    assertContains(stub, "public static String renderText(String receiver)")
    assertContains(stub, "public static String readMode()")
    assertContains(stub, "public static void writeMode(String value)")
    assertFalse(stub.contains("public static String load()"))
    assertFalse(stub.contains("public static String fetch("))
    assertFalse(stub.contains("public static String getMode()"))
  }

  @Test
  fun generate_rejectsGenericInterfacePropertyAccessorErasureConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface GenericPropertyConflict<T> {
          @set:JvmName("assign")
          var value: T
          fun assign(value: Any)
          fun assign(value: String)
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.GenericPropertyConflict", "GenericPropertyConflict.kt", source, emptySet(), mode)
      assertNotNull("Generic interface conflict generation failed in $mode", stub)
      assertFalse("Erased Object JVM surface leaked in $mode:\n$stub",
          stub!!.contains("assign(T value)") || stub.contains("assign(Object value)"))
      assertContains(stub, "void assign(String value);")
    }
  }

  @Test
  fun generate_keepsOwnerTypeErasureWhenGenericInterfaceHasParameterizedSupertype() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface Parent<T>
        interface InheritedExtensionPropertyConflict<T : CharSequence> : Parent<String> {
          val T.payload: String
          fun read(receiver: CharSequence): String
          fun read(receiver: String): String
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.InheritedExtensionPropertyConflict", "InheritedExtensionPropertyConflict.kt", source,
        emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(stub)
    assertContains(stub!!,
        "public interface InheritedExtensionPropertyConflict<T extends CharSequence> extends Parent<String>")
    assertContains(stub!!, "String read(CharSequence receiver);")
    assertContains(stub, "String read(String receiver);")
  }

  @Test
  fun generate_rejectsBoundedGenericInterfaceExtensionReceiverErasureConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface BoundedExtensionPropertyConflict<T : CharSequence> {
          val T.payload: String
          fun read(receiver: CharSequence): String
          fun read(receiver: String): String
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.BoundedExtensionPropertyConflict", "BoundedExtensionPropertyConflict.kt", source,
        emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(stub)
    assertContains(stub!!, "String read(CharSequence receiver);")
    assertContains(stub, "String read(String receiver);")
  }

  @Test
  fun generate_rejectsGenericInterfaceExtensionPropertySetterErasureConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface GenericExtensionSetterConflict<T> {
          var T.payload: T
          fun assign(receiver: Any, value: Any)
          fun assign(receiver: Any, value: String)
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.GenericExtensionSetterConflict", "GenericExtensionSetterConflict.kt", source,
        emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(stub)
    assertContains(stub!!, "void assign(Object receiver, Object value);")
    assertContains(stub, "void assign(Object receiver, String value);")
  }

  @Test
  fun generate_rejectsGenericInterfaceExtensionPropertyReceiverErasureConflict() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface GenericExtensionPropertyConflict<T> {
          val T.payload: String
          fun access(receiver: Any): String
          fun access(receiver: String): String
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.GenericExtensionPropertyConflict", "GenericExtensionPropertyConflict.kt", source,
        emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(stub)
    assertContains(stub!!, "String access(Object receiver);")
    assertContains(stub, "String access(String receiver);")

    val fallback = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.GenericExtensionPropertyConflict", "GenericExtensionPropertyConflict.kt", source,
        emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)
    assertNotNull(fallback)
    assertFalse("Fallback must continue to omit interface extension properties:\n$fallback",
        fallback!!.contains("access(T receiver)"))
  }

  @Test
  fun generate_structuredProjectsGenericInterfaceExtensionPropertyAccessors() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface GenericExtensionContract<T> {
          var T.payload: T
        }

        interface BoundedExtensionContract<T : CharSequence> {
          var T.payload: T
        }
        """.trimIndent()

    val generic = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.GenericExtensionContract", "GenericExtensionContract.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(generic)
    assertContains(generic!!, "public interface GenericExtensionContract<T>")
    assertContains(generic, "T getPayload(T receiver);")
    assertContains(generic, "void setPayload(T receiver, T value);")

    val bounded = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.BoundedExtensionContract", "BoundedExtensionContract.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(bounded)
    assertContains(bounded!!, "public interface BoundedExtensionContract<T extends CharSequence>")
    assertContains(bounded, "T getPayload(T receiver);")
    assertContains(bounded, "void setPayload(T receiver, T value);")

    for ((qualifiedName, fileName) in listOf(
        "sample.GenericExtensionContract" to "GenericExtensionContract.kt",
        "sample.BoundedExtensionContract" to "BoundedExtensionContract.kt",
    )) {
      val fallback = KotlinJvmAbiStubGenerator.generateForTest(
          qualifiedName, fileName, source, emptySet(), KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)
      assertNotNull(fallback)
      assertFalse("Fallback must not invent generic interface extension accessors:\n$fallback",
          fallback!!.contains("getPayload(") || fallback.contains("setPayload("))
    }
  }

  @Test
  fun generate_structuredProjectsInterfaceExtensionPropertyAccessors() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface ExtensionPropertyContract {
          val String.initial: Char
          var String.label: String
        }
        """.trimIndent()

    val structured = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ExtensionPropertyContract", "ExtensionPropertyContract.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(structured)
    assertContains(structured!!, "char getInitial(String receiver);")
    assertContains(structured, "String getLabel(String receiver);")
    assertContains(structured, "void setLabel(String receiver, String value);")
    assertFalse(structured.contains("getInitial();"))
    assertFalse(structured.contains("getLabel();"))

    val fallback = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ExtensionPropertyContract", "ExtensionPropertyContract.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)
    assertNotNull(fallback)
    assertFalse("Fallback must not invent an interface extension accessor:\n$fallback",
        fallback!!.contains("getInitial(") || fallback.contains("getLabel(")
            || fallback.contains("setLabel("))
  }

  @Test
  fun generate_projectsInterfacePropertyAccessorJvmSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface PropertyContract {
          val title: String
          var isReady: Boolean
          @get:JvmName("readMode")
          @set:JvmName("writeMode")
          var mode: String
          @get:JvmSynthetic
          var internal: String
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.PropertyContract", "PropertyContract.kt", source, emptySet(), mode)
      assertNotNull("Interface property generation failed in $mode", stub)
      assertContains(stub!!, "String getTitle();")
      assertContains(stub, "boolean isReady();")
      assertContains(stub, "void setReady(boolean value);")
      assertContains(stub, "String readMode();")
      assertContains(stub, "void writeMode(String value);")
      assertFalse("Synthetic interface getter leaked in $mode:\n$stub", stub.contains("getInternal();"))
      assertContains(stub, "void setInternal(String value);")
    }
  }

  @Test
  fun generatedStructuredInterfacePropertyStubs_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        interface ConsumerPropertyContract {
          val title: String
          var isReady: Boolean
          @get:JvmName("readMode")
          @set:JvmName("writeMode")
          var mode: String
          @get:JvmSynthetic
          var internal: String
        }

        interface ConsumerExtensionContract {
          val String.initial: Char
          var String.label: String
        }

        @JvmInline
        value class UserId(val raw: String)

        interface ConsumerValueClassExtensionContract {
          var UserId.label: String
          var String.id: UserId
        }

        interface ConsumerGenericExtensionContract<T> {
          var T.payload: T
        }

        interface ConsumerBoundedExtensionContract<T : CharSequence> {
          var T.payload: T
        }
        """.trimIndent()
    val stubs = mapOf(
      "sample.ConsumerPropertyContract" to KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ConsumerPropertyContract",
        "ConsumerPropertyContract.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      )!!,
      "sample.ConsumerExtensionContract" to KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ConsumerExtensionContract",
        "ConsumerExtensionContract.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      )!!,
      "sample.ConsumerValueClassExtensionContract" to KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ConsumerValueClassExtensionContract",
        "ConsumerValueClassExtensionContract.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      )!!,
      "sample.ConsumerGenericExtensionContract" to KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ConsumerGenericExtensionContract",
        "ConsumerGenericExtensionContract.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      )!!,
      "sample.ConsumerBoundedExtensionContract" to KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ConsumerBoundedExtensionContract",
        "ConsumerBoundedExtensionContract.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      )!!,
    )
    assertTrue(
      "Proven structured interface property surfaces must be attributable:\n$stubs",
      javacSucceeds(
        stubs,
        "consumer.SupportedInterfaceProperties",
        """
        package consumer;
        import sample.ConsumerBoundedExtensionContract;
        import sample.ConsumerExtensionContract;
        import sample.ConsumerGenericExtensionContract;
        import sample.ConsumerPropertyContract;
        class SupportedInterfaceProperties {
          void use(
              ConsumerPropertyContract properties,
              ConsumerExtensionContract extensions,
              ConsumerGenericExtensionContract<String> generic,
              ConsumerBoundedExtensionContract<StringBuilder> bounded,
              ConsumerGenericExtensionContract rawGeneric) {
            String title = properties.getTitle();
            boolean ready = properties.isReady();
            properties.setReady(ready);
            String mode = properties.readMode();
            properties.writeMode(mode);
            char initial = extensions.getInitial("value");
            String label = extensions.getLabel("value");
            extensions.setLabel("value", label);
            String genericValue = generic.getPayload("value");
            generic.setPayload("value", genericValue);
            StringBuilder boundedValue = bounded.getPayload(new StringBuilder("value"));
            bounded.setPayload(boundedValue, boundedValue);
            Object rawValue = rawGeneric.getPayload(new Object());
            rawGeneric.setPayload(rawValue, rawValue);
          }
        }
        """.trimIndent(),
      ),
    )
    val unsupportedMethods = listOf(
      "void use(ConsumerExtensionContract value) { value.getInitial(); }",
      "void use(ConsumerExtensionContract value) { value.getLabel(); }", 
      "void use(ConsumerPropertyContract value) { value.getInternal(); }",
      "void use(ConsumerValueClassExtensionContract value) { value.getLabel(\"value\"); }",
      "void use(ConsumerValueClassExtensionContract value) { value.setLabel(\"value\", \"label\"); }",
      "void use(ConsumerValueClassExtensionContract value) { value.getId(\"value\"); }",
      "void use(ConsumerValueClassExtensionContract value) { value.setId(\"value\", \"id\"); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      assertFalse(
        "Unprojected interface accessor must not be attributable: $method\n$stubs",
        javacSucceeds(
          stubs,
          "consumer.UnsupportedInterfaceProperties$index",
          """
          package consumer;
          import sample.ConsumerExtensionContract;
          import sample.ConsumerPropertyContract;
          import sample.ConsumerValueClassExtensionContract;
          class UnsupportedInterfaceProperties$index {
            $method
          }
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun generate_keepsInterfacePropertyAccessorBodiesAbstractWithoutJvmDefaultEvidence() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        interface DefaultPropertyContract {
          val title: String
            get() = "default"
          var enabled: Boolean
            get() = true
            set(value) {}
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.DefaultPropertyContract", "DefaultPropertyContract.kt", source, emptySet(), mode)
      assertNotNull("Default interface property generation failed in $mode", stub)
      assertContains(stub!!, "String getTitle();")
      assertContains(stub, "boolean getEnabled();")
      assertContains(stub, "void setEnabled(boolean value);")
      assertFalse("Source accessor body was incorrectly emitted as Java default in $mode:\n$stub",
          stub.contains("getTitle() { return") || stub.contains("getEnabled() { return")
              || stub.contains("setEnabled(boolean value) {}"))
    }
  }

  @Test
  fun generate_projectsTopLevelMembersIntoDefaultFileFacade() {
    val source =
        """
        package sample

        fun create(name: String, count: Int): String = name
        val version: Int = 1
        var enabled: Boolean = true
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.UtilitiesKt", "Utilities.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public final class UtilitiesKt")
    assertContains(stub, "public static String create(String name, int count)")
    assertContains(stub, "public static int getVersion()")
    assertContains(stub, "public static boolean getEnabled()")
    assertContains(stub, "public static void setEnabled(boolean value)")
  }

  @Test
  fun generate_honorsFileJvmName() {
    val source =
        """
        @file:JvmName("Api")
        package sample

        fun load(): String = "value"
        """.trimIndent()

    val renamed = KotlinJvmAbiStubGenerator.generate("sample.Api", "Utilities.kt", source)
    val defaultFacade = KotlinJvmAbiStubGenerator.generate("sample.UtilitiesKt", "Utilities.kt", source)

    assertNotNull(renamed)
    assertContains(renamed!!, "public final class Api")
    assertContains(renamed, "public static String load()")
    assertNull(defaultFacade)
  }

  @Test
  fun generate_projectsObjectAndCompanionJvmSurface() {
    val objectSource =
        """
        package sample

        object Registry {
          fun find(id: Int): String = id.toString()
        }
        """.trimIndent()
    val classSource =
        """
        package sample

        class Config {
          companion object {
            @JvmStatic fun create(name: String): Config = Config()
            @JvmStatic @JvmName("buildConfig") fun named(name: String): Config = Config()
            @get:JvmName("readOnline")
            @set:JvmName("writeOnline")
            @JvmStatic var online: Boolean = true
            @JvmField val VERSION: Int = 1
            fun ordinary(): String = "value"
          }
        }
        """.trimIndent()

    val objectStub = KotlinJvmAbiStubGenerator.generate("sample.Registry", "Registry.kt", objectSource)
    val classStub = KotlinJvmAbiStubGenerator.generate("sample.Config", "Config.kt", classSource)

    assertNotNull(objectStub)
    assertContains(objectStub!!, "public static final Registry INSTANCE")
    assertContains(objectStub, "public String find(int id)")

    assertNotNull(classStub)
    assertContains(classStub!!, "public static final class Companion")
    assertContains(classStub, "public static final Companion Companion")
    assertContains(classStub, "public static Config create(String name)")
    assertContains(classStub, "public static Config buildConfig(String name)")
    assertContains(classStub, "public static boolean readOnline()")
    assertContains(classStub, "public static void writeOnline(boolean value)")
    assertContains(classStub, "public static int VERSION;")
    // @JvmField exposes the backing field, not generated Companion/host accessor methods.
    assertFalse(classStub.contains("getVERSION("))
    assertFalse(classStub.contains("setVERSION("))
    assertContains(classStub, "public String ordinary()")
  }

  @Test
  fun generate_structuredProjectsOnlyProvenValueClassExtensionAccessorSurfaces() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String)

        var UserId.label: String
          get() = raw
          set(value) {}

        var String.id: UserId
          get() = UserId(this)
          set(value) {}

        var UserId.other: UserId
          get() = this
          set(value) {}

        @get:JvmName("readLabel")
        @set:JvmName("writeLabel")
        var UserId.namedLabel: String
          get() = raw
          set(value) {}
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ValueClassExtensionsKt", "ValueClassExtensions.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(stub)
    assertFalse("Scalar receiver getter leaked:\n$stub", stub!!.contains("getLabel(String receiver)"))
    assertFalse("Scalar receiver setter leaked:\n$stub", stub.contains("setLabel(String receiver"))
    assertContains(stub, "static String getId(String receiver)")
    assertFalse("Scalar value setter leaked:\n$stub", stub.contains("setId(String receiver"))
    assertFalse("Scalar receiver/value getter leaked:\n$stub", stub.contains("getOther(String receiver)"))
    assertFalse("Scalar receiver/value setter leaked:\n$stub", stub.contains("setOther(String receiver"))
    assertContains(stub, "static String readLabel(String receiver)")
    assertContains(stub, "static void writeLabel(String receiver, String value)")
  }

  @Test
  fun generatedValueClassExtensionStub_controlsJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        @JvmInline
        value class UserId(val raw: String)

        var UserId.label: String
          get() = raw
          set(value) {}

        var String.id: UserId
          get() = UserId(this)
          set(value) {}

        @get:JvmName("readLabel")
        @set:JvmName("writeLabel")
        var UserId.namedLabel: String
          get() = raw
          set(value) {}
        """.trimIndent()
    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ValueClassExtensionsKt", "ValueClassExtensions.kt", kotlinSource, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull(stub)

    val supportedConsumer =
        """
        package consumer;
        import sample.ValueClassExtensionsKt;
        class Supported {
          String read() { return ValueClassExtensionsKt.getId("id"); }
          String named() { return ValueClassExtensionsKt.readLabel("id"); }
          void write() { ValueClassExtensionsKt.writeLabel("id", "value"); }
        }
        """.trimIndent()
    assertTrue(
        "Proven Kotlin ABI surface must be attributable by javac:\n$stub",
        javacSucceeds(stub!!, supportedConsumer))

    val unsupportedConsumer =
        """
        package consumer;
        import sample.ValueClassExtensionsKt;
        class Unsupported {
          void calls() {
            ValueClassExtensionsKt.getLabel("id");
            ValueClassExtensionsKt.setLabel("id", "value");
            ValueClassExtensionsKt.setId("id", "value");
          }
        }
        """.trimIndent()
    assertFalse(
        "Mangled Kotlin ABI surface must not be attributable under guessed Java names:\n$stub",
        javacSucceeds(stub, unsupportedConsumer))
  }

  @Test
  fun generate_structuredProjectsTopLevelExtensionPropertyAccessors() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        val String.initial: Char
          get() = first()

        var String.label: String
          get() = this
          set(value) {}
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.ExtensionPropertiesKt", "ExtensionProperties.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull("Extension property facade generation failed", stub)
    assertContains(stub!!, "static char getInitial(String receiver)")
    assertContains(stub, "static String getLabel(String receiver)")
    assertContains(stub, "static void setLabel(String receiver, String value)")
  }

  @Test
  fun generate_structuredProjectsJvmNamedSyntheticExtensionPropertyAccessors() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        @get:JvmName("readLabel")
        @set:JvmName("writeLabel")
        var String.label: String
          get() = this
          set(value) {}

        @get:JvmSynthetic
        var String.hidden: String
          get() = this
          set(value) {}
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.NamedExtensionPropertiesKt", "NamedExtensionProperties.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull("Named extension property facade generation failed", stub)
    assertContains(stub!!, "static String readLabel(String receiver)")
    assertContains(stub, "static void writeLabel(String receiver, String value)")
    assertFalse(stub.contains("getLabel(String receiver)"))
    assertFalse(stub.contains("setLabel(String receiver, String value)"))
    assertFalse("Synthetic extension getter leaked:\n$stub", stub.contains("getHidden(String receiver)"))
    assertContains(stub, "static void setHidden(String receiver, String value)")
  }

  @Test
  fun generatedNamedCompanionStub_controlsJavacOwnerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        class NamedCompanionConsumer {
          companion object Factory {
            fun ordinary(value: String): String = value
            @JvmStatic fun create(value: Int): Int = value
            @get:JvmName("readMode")
            @set:JvmName("writeMode")
            @JvmStatic var mode: String = "default"
            @JvmField val VERSION: Int = 1
          }
        }
        """.trimIndent()
    val stub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.NamedCompanionConsumer",
      "NamedCompanionConsumer.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    )
    assertNotNull(stub)
    assertTrue(
      "Named companion host and nested owner surfaces must be attributable:\n$stub",
      javacSucceeds(
        mapOf("sample.NamedCompanionConsumer" to stub!!),
        "consumer.NamedCompanionConsumerUse",
        """
        package consumer;
        import sample.NamedCompanionConsumer;
        class NamedCompanionConsumerUse {
          String ordinary() {
            return NamedCompanionConsumer.Factory.ordinary("value");
          }
          int create() { return NamedCompanionConsumer.create(1); }
          String mode() {
            String mode = NamedCompanionConsumer.readMode();
            NamedCompanionConsumer.writeMode(mode);
            return mode;
          }
          int version() { return NamedCompanionConsumer.VERSION; }
        }
        """.trimIndent(),
      ),
    )
    assertFalse("Named companion must not fabricate an anonymous owner:\n$stub",
      stub.contains("class Companion"))
  }

  @Test
  fun generate_projectsNamedCompanionUsingItsJvmNestedOwnerName() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class NamedCompanionOwner {
          companion object Factory {
            fun ordinary(value: String): String = value
            @JvmStatic fun create(value: Int): Int = value
          }
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.NamedCompanionOwner", "NamedCompanionOwner.kt", source, emptySet(), mode)
      assertNotNull("Named companion generation failed in $mode", stub)
      assertContains(stub!!, "public static final class Factory")
      assertContains(stub, "public static final Factory Factory")
      assertContains(stub, "public String ordinary(String value)")
      assertContains(stub, "public static int create(int value)")
      assertFalse("Anonymous companion owner leaked in $mode:\n$stub", stub.contains("class Companion"))
    }
  }

  @Test
  fun generatedWildcardTypeArguments_preserveProvenVarianceAndAnnotations() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
      """
      package sample

      class WildcardApi {
        fun plain(values: List<String>): List<String> = values
        fun declarationOut(values: MutableList<out CharSequence>): MutableList<out CharSequence> = values
        fun declarationIn(values: MutableList<in String>) {}
        fun star(values: List<*>) {}
        fun forced(values: List<@JvmWildcard String>): List<@JvmWildcard String> = values
        fun suppressed(values: List<@JvmSuppressWildcards String>): List<@JvmSuppressWildcards String> = values
      }
      """.trimIndent()

    for (mode in listOf(
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK,
    )) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.WildcardApi", "GenericWildcards.kt", source, emptySet(), mode)
        ?: error("Missing wildcard stub in $mode")
      assertContains(stub, "java.util.List<String> plain(java.util.List<String> values)")
      assertContains(stub, "java.util.List<? extends CharSequence> declarationOut(java.util.List<? extends CharSequence> values)")
      assertContains(stub, "Object declarationIn(java.util.List<? super String> values)")
      assertContains(stub, "Object star(java.util.List<?> values)")
      assertContains(stub, "java.util.List<? extends String> forced(java.util.List<? extends String> values)")
      assertContains(stub, "java.util.List<String> suppressed(java.util.List<String> values)")
      assertTrue("Wildcard surfaces must be attributable in $mode:\n$stub", javacSucceeds(
        mapOf("sample.WildcardApi" to stub),
        "consumer.WildcardUse",
        """
        package consumer;
        import sample.WildcardApi;
        import java.util.ArrayList;
        import java.util.List;
        class WildcardUse {
          void use(WildcardApi api, List<String> strings, ArrayList<CharSequence> mutable) {
            List<String> plainResult = api.plain(strings);
            List<? extends CharSequence> outResult = api.declarationOut(mutable);
            api.declarationIn(strings);
            api.star(strings);
            List<? extends String> forcedResult = api.forced(strings);
            List<String> suppressedResult = api.suppressed(strings);
          }
        }
        """.trimIndent(),
      ))
    }
  }

  @Test
  fun generatedWildcardPropertyAccessors_preserveProvenGenericSurfaces() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
      """
      package sample

      class WildcardPropertyApi {
        val plain: List<String> = emptyList()
        var declarationOut: MutableList<out CharSequence> = mutableListOf()
        var declarationIn: MutableList<in String> = mutableListOf()
        val star: List<*> = emptyList()
        val forced: List<@JvmWildcard String> = emptyList()
        val suppressed: List<@JvmSuppressWildcards String> = emptyList()
      }
      """.trimIndent()

    for (mode in listOf(
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK,
    )) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.WildcardPropertyApi", "GenericWildcardProperties.kt", source, emptySet(), mode)
        ?: error("Missing wildcard property stub in $mode")
      assertContains(stub, "java.util.List<String> getPlain()")
      assertContains(stub, "java.util.List<? extends CharSequence> getDeclarationOut()")
      assertContains(stub, "void setDeclarationOut(java.util.List<? extends CharSequence> value)")
      assertContains(stub, "java.util.List<? super String> getDeclarationIn()")
      assertContains(stub, "void setDeclarationIn(java.util.List<? super String> value)")
      assertContains(stub, "java.util.List<?> getStar()")
      assertContains(stub, "java.util.List<? extends String> getForced()")
      assertContains(stub, "java.util.List<String> getSuppressed()")
      assertTrue("Wildcard property accessors must be attributable in $mode:\n$stub", javacSucceeds(
        mapOf("sample.WildcardPropertyApi" to stub),
        "consumer.WildcardPropertyUse",
        """
        package consumer;
        import sample.WildcardPropertyApi;
        import java.util.List;
        class WildcardPropertyUse {
          void use(WildcardPropertyApi api, List<? extends CharSequence> out) {
            List<String> plain = api.getPlain();
            List<? extends CharSequence> readOut = api.getDeclarationOut();
            api.setDeclarationOut(out);
            List<? super String> readIn = api.getDeclarationIn();
            api.setDeclarationIn(readIn);
            List<?> star = api.getStar();
            List<? extends String> forced = api.getForced();
            List<String> suppressed = api.getSuppressed();
          }
        }
        """.trimIndent(),
      ))
    }
  }

  @Test
  fun generatedJvmFieldWildcardProperty_preservesHostFieldWithoutAccessor() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
      """
      package sample

      class WildcardFieldApi {
        @JvmField
        val forced: List<@JvmWildcard String> = emptyList()
      }
      """.trimIndent()

    for (mode in listOf(
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK,
    )) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.WildcardFieldApi", "GenericWildcardFields.kt", source, emptySet(), mode)
        ?: error("Missing wildcard field stub in $mode")
      assertContains(stub, "java.util.List<? extends String> forced;")
      assertFalse("@JvmField must not expose a getter in $mode:\n$stub", stub.contains("getForced("))
      assertTrue("Wildcard @JvmField surface must be attributable in $mode:\n$stub", javacSucceeds(
        mapOf("sample.WildcardFieldApi" to stub),
        "consumer.WildcardFieldUse",
        """
        package consumer;
        import sample.WildcardFieldApi;
        import java.util.List;
        class WildcardFieldUse {
          List<? extends String> use(WildcardFieldApi api) { return api.forced; }
        }
        """.trimIndent(),
      ))
    }
  }

  @Test
  fun generatedSingleBoundGenericClassAndMethod_preserveJvmErasureAndJavacAttribution() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
      """
      package sample

      class SingleBound<T : CharSequence>(val value: T)

      class BoundApi {
        fun <T : CharSequence> single(value: T): T = value
      }
      """.trimIndent()

    for (mode in listOf(
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK,
    )) {
      val singleBoundStub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.SingleBound", "GenericBounds.kt", source, emptySet(), mode)
        ?: error("Missing single-bound class stub in $mode")
      val apiStub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.BoundApi", "GenericBounds.kt", source, emptySet(), mode)
        ?: error("Missing bound API stub in $mode")

      assertContains(singleBoundStub, "public class SingleBound<T extends CharSequence>")
      assertContains(singleBoundStub, "SingleBound(T value)")
      assertContains(singleBoundStub, "T getValue()")
      assertContains(apiStub, "<T extends CharSequence> T single(T value)")
      assertTrue("Single-bound generic surfaces must be attributable in $mode:\n$singleBoundStub\n$apiStub",
        javacSucceeds(
          mapOf("sample.SingleBound" to singleBoundStub, "sample.BoundApi" to apiStub),
          "consumer.GenericBoundsUse",
          """
          package consumer;
          import sample.BoundApi;
          import sample.SingleBound;
          class GenericBoundsUse {
            String use(BoundApi api, SingleBound<String> boxed, String value) {
              SingleBound<String> created = new SingleBound<>(value);
              CharSequence erased = boxed.getValue();
              return api.single(value) + created.getValue() + erased;
            }
          }
          """.trimIndent(),
        ))
    }
  }

  @Test
  fun generatedMultipleWhereBounds_preserveCompilerProvenIntersectionAndJavacAttribution() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
      """
      package sample

      class MultipleBounds<T>(val value: T)
        where T : CharSequence, T : Comparable<T>

      class BoundApi {
        fun <T> multiple(value: T): T where T : CharSequence, T : Comparable<T> = value
      }
      """.trimIndent()

    for (mode in listOf(
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK,
    )) {
      val classStub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.MultipleBounds", "MultipleBounds.kt", source, emptySet(), mode)
        ?: error("Missing multiple-bound class stub in $mode")
      val apiStub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.BoundApi", "MultipleBounds.kt", source, emptySet(), mode)
        ?: error("Missing multiple-bound API stub in $mode")
      val bound = "T extends CharSequence & Comparable<? super T>"

      assertTrue("Multiple-bound class shape missing in $mode:\n$classStub",
        classStub.contains("public class MultipleBounds<$bound>"))
      assertContains(classStub, "MultipleBounds(T value)")
      assertTrue("Multiple-bound method shape missing in $mode:\n$apiStub",
        apiStub.contains("<$bound> T multiple(T value)"))
      assertTrue("Multiple where-bound surfaces must be attributable in $mode:\n$classStub\n$apiStub",
        javacSucceeds(
          mapOf("sample.MultipleBounds" to classStub, "sample.BoundApi" to apiStub),
          "consumer.MultipleBoundsUse",
          """
          package consumer;
          import sample.BoundApi;
          import sample.MultipleBounds;
          class MultipleBoundsUse {
            String use(BoundApi api, String value) {
              MultipleBounds<String> boxed = new MultipleBounds<>(value);
              return api.multiple(value) + boxed.getValue();
            }
          }
          """.trimIndent(),
        ))
    }
  }

  @Test
  fun generatedGenericOverrideStubs_omitSyntheticBridgesButRemainJavacAttributable() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        interface GenericContract<T> {
          fun accept(value: T): T
          var payload: T
        }

        class StringContract : GenericContract<String> {
          override fun accept(value: String): String = value
          override var payload: String = "payload"
        }
        """.trimIndent()
    val contractStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.GenericContract",
      "GenericOverrideBridges.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for GenericContract")
    val implementationStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.StringContract",
      "GenericOverrideBridges.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for StringContract")

    assertContains(contractStub, "public interface GenericContract<T>")
    assertContains(contractStub, "T accept(T value)")
    assertContains(contractStub, "T getPayload()")
    assertContains(contractStub, "void setPayload(T value)")

    assertContains(implementationStub, "public class StringContract")
    assertContains(implementationStub, "public String accept(String value)")
    assertContains(implementationStub, "public String getPayload()")
    assertContains(implementationStub, "public void setPayload(String value)")
    assertFalse("Synthetic Object bridge must not be projected:\n$implementationStub",
      implementationStub.contains("Object accept(Object value)") ||
        implementationStub.contains("Object getPayload()") ||
        implementationStub.contains("void setPayload(Object value)"))

    val stubs = mapOf(
      "sample.GenericContract" to contractStub,
      "sample.StringContract" to implementationStub,
    )
    assertTrue(
      "Generic override stubs must remain attributable by javac:\n$stubs",
      javacSucceeds(
        stubs,
        "consumer.GenericOverrideConsumer",
        """
        package consumer;
        import sample.GenericContract;
        import sample.StringContract;
        class GenericOverrideConsumer {
          String useImplementation(String value) {
            StringContract implementation = new StringContract();
            implementation.setPayload(value);
            return implementation.accept(value) + implementation.getPayload();
          }
          String useContract(GenericContract<String> contract, String value) {
            contract.setPayload(value);
            return contract.accept(value) + contract.getPayload();
          }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun generatedBoundedGenericOverrideStubs_omitUpperBoundSyntheticBridgesButRemainJavacAttributable() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        interface BoundedContract<T : CharSequence> {
          fun accept(value: T): T
          var payload: T
        }

        class BoundedStringContract : BoundedContract<String> {
          override fun accept(value: String): String = value
          override var payload: String = "payload"
        }
        """.trimIndent()
    val contractStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.BoundedContract",
      "BoundedGenericOverrideBridges.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for BoundedContract")
    val implementationStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.BoundedStringContract",
      "BoundedGenericOverrideBridges.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for BoundedStringContract")

    assertContains(contractStub, "public interface BoundedContract<T extends CharSequence>")
    assertContains(contractStub, "T accept(T value)")
    assertContains(contractStub, "T getPayload()")
    assertContains(contractStub, "void setPayload(T value)")

    assertContains(implementationStub, "public class BoundedStringContract")
    assertContains(implementationStub, "public String accept(String value)")
    assertContains(implementationStub, "public String getPayload()")
    assertContains(implementationStub, "public void setPayload(String value)")
    assertFalse("Synthetic CharSequence bridge must not be projected:\n$implementationStub",
      implementationStub.contains("CharSequence accept(CharSequence value)") ||
        implementationStub.contains("CharSequence getPayload()") ||
        implementationStub.contains("void setPayload(CharSequence value)"))

    val stubs = mapOf(
      "sample.BoundedContract" to contractStub,
      "sample.BoundedStringContract" to implementationStub,
    )
    assertTrue(
      "Bounded generic override stubs must remain attributable by javac:\n$stubs",
      javacSucceeds(
        stubs,
        "consumer.BoundedGenericOverrideConsumer",
        """
        package consumer;
        import sample.BoundedContract;
        import sample.BoundedStringContract;
        class BoundedGenericOverrideConsumer {
          String useImplementation(String value) {
            BoundedStringContract implementation = new BoundedStringContract();
            implementation.setPayload(value);
            return implementation.accept(value) + implementation.getPayload();
          }
          String useContract(BoundedContract<String> contract, String value) {
            contract.setPayload(value);
            return contract.accept(value) + contract.getPayload();
          }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
fun generatedInterfaceDefaultImplementationStubs_preserveAbstractContractWithoutDefaultImplsOwner() {
TreeSitter.loadLibrary()
System.loadLibrary("tree-sitter-kotlin")
val kotlinSource =
"""
package sample

interface DefaultContract {
fun render(value: String): String = value
val title: String
get() = "title"
}

class DefaultConsumer : DefaultContract

interface DerivedDefaultContract : DefaultContract

class IndirectDefaultConsumer : DerivedDefaultContract

interface GenericDefaultContract<T> {
  fun echo(value: T): T = value
}
class StringGenericDefaultConsumer : GenericDefaultContract<String>
interface DerivedGenericDefaultContract<T> : GenericDefaultContract<T>
class IndirectStringGenericDefaultConsumer : DerivedGenericDefaultContract<String>
interface MutableGenericDefaultContract<T> {
  var payload: T
    get() = throw UnsupportedOperationException()
    set(value) {}
}
abstract class StringMutableGenericDefaultConsumer : MutableGenericDefaultContract<String>
interface BoundedGenericDefaultContract<T : CharSequence> {
  fun echo(value: T): T = value
}
class BoundedStringGenericDefaultConsumer : BoundedGenericDefaultContract<String>

class ExplicitDefaultConsumer : DefaultContract {
  override fun render(value: String): String = "explicit:${'$'}value"
  override val title: String
    get() = "explicit"
}

class OverloadedDefaultConsumer : DefaultContract {
  fun render(value: Int): String = value.toString()
}

interface MutableDefaultContract {
  var enabled: Boolean
    get() = true
    set(value) {}
}

abstract class MutableDefaultConsumer : MutableDefaultContract
""".trimIndent()
val contractStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.DefaultContract",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for DefaultContract")
val consumerStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.DefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for DefaultConsumer")
val indirectStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.IndirectDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for IndirectDefaultConsumer")
val genericContractStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.GenericDefaultContract",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for GenericDefaultContract")
val genericConsumerStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.StringGenericDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for StringGenericDefaultConsumer")
val derivedGenericContractStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.DerivedGenericDefaultContract",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for DerivedGenericDefaultContract")
val indirectGenericConsumerStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.IndirectStringGenericDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for IndirectStringGenericDefaultConsumer")
val mutableGenericContractStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.MutableGenericDefaultContract",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for MutableGenericDefaultContract")
val boundedGenericContractStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.BoundedGenericDefaultContract",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for BoundedGenericDefaultContract")
val boundedGenericConsumerStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.BoundedStringGenericDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for BoundedStringGenericDefaultConsumer")
val mutableGenericConsumerStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.StringMutableGenericDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for StringMutableGenericDefaultConsumer")
val explicitStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.ExplicitDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for ExplicitDefaultConsumer")
val overloadStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.OverloadedDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for OverloadedDefaultConsumer")
val mutableContractStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.MutableDefaultContract",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for MutableDefaultContract")
val mutableConsumerStub = KotlinJvmAbiStubGenerator.generateForTest(
"sample.MutableDefaultConsumer",
"InterfaceDefaultImplementations.kt",
kotlinSource,
emptySet(),
KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
) ?: error("Missing generated stub for MutableDefaultConsumer")

assertContains(contractStub, "public interface DefaultContract")
assertContains(contractStub, "String render(String value);")
assertContains(contractStub, "String getTitle();")
assertFalse("DefaultImpls is compiler-only dispatch detail:\n$contractStub",
contractStub.contains("DefaultImpls"))
assertFalse("DefaultImpls ABI must not be rewritten as Java interface defaults:\n$contractStub",
contractStub.contains("default "))
assertContains(consumerStub, "public class DefaultConsumer implements DefaultContract")
assertContains(consumerStub, "String render(String value) { return null; }")
assertContains(consumerStub, "String getTitle() { return null; }")
assertContains(indirectStub, "String render(String value) { return null; }")
assertContains(indirectStub, "String getTitle() { return null; }")
assertContains(genericContractStub, "T echo(T value);")
for (stub in listOf(genericConsumerStub, indirectGenericConsumerStub)) {
assertContains(stub, "String echo(String value) { return null; }")
assertFalse("Synthetic Object bridge must not be projected:\n$stub",
stub.contains("Object echo(Object value)"))
}
assertContains(boundedGenericContractStub, "T echo(T value);")
assertContains(boundedGenericConsumerStub, "String echo(String value) { return null; }")
assertFalse("Synthetic CharSequence bridge must not be projected:\n$boundedGenericConsumerStub",
boundedGenericConsumerStub.contains("CharSequence echo(CharSequence value)"))
assertContains(mutableGenericContractStub, "T getPayload();")
assertContains(mutableGenericContractStub, "void setPayload(T value);")
assertContains(mutableGenericConsumerStub, "String getPayload() { return null; }")
assertContains(mutableGenericConsumerStub, "void setPayload(String value) {}")
assertFalse("Synthetic Object accessors must not be projected:\n$mutableGenericConsumerStub",
mutableGenericConsumerStub.contains("Object getPayload()")
|| mutableGenericConsumerStub.contains("void setPayload(Object value)"))
assertEquals(1, Regex("\\brender\\(String value\\)").findAll(explicitStub).count())
assertEquals(1, Regex("\\bgetTitle\\(\\)").findAll(explicitStub).count())
assertContains(overloadStub, "String render(int value) { return null; }")
assertContains(overloadStub, "String render(String value) { return null; }")
assertContains(mutableContractStub, "boolean getEnabled();")
assertContains(mutableContractStub, "void setEnabled(boolean value);")
assertContains(mutableConsumerStub, "boolean getEnabled() { return false; }")
assertContains(mutableConsumerStub, "void setEnabled(boolean value) {}")
assertTrue(
"Default-interface implementer forwarders must be attributable by javac:\n$consumerStub",
javacSucceeds(
mapOf(
"sample.DefaultContract" to contractStub,
"sample.DefaultConsumer" to consumerStub,
"sample.GenericDefaultContract" to genericContractStub,
"sample.StringGenericDefaultConsumer" to genericConsumerStub,
"sample.DerivedGenericDefaultContract" to derivedGenericContractStub,
"sample.IndirectStringGenericDefaultConsumer" to indirectGenericConsumerStub,
"sample.MutableGenericDefaultContract" to mutableGenericContractStub,
"sample.StringMutableGenericDefaultConsumer" to mutableGenericConsumerStub,
"sample.BoundedGenericDefaultContract" to boundedGenericContractStub,
"sample.BoundedStringGenericDefaultConsumer" to boundedGenericConsumerStub,
),
"consumer.DefaultConsumerUse",
"""
package consumer;
import sample.DefaultConsumer;
import sample.StringGenericDefaultConsumer;
import sample.IndirectStringGenericDefaultConsumer;
import sample.StringMutableGenericDefaultConsumer;
import sample.BoundedStringGenericDefaultConsumer;
class DefaultConsumerUse {
  String render(DefaultConsumer consumer) { return consumer.render("value"); }
  String title(DefaultConsumer consumer) { return consumer.getTitle(); }
  String generic(StringGenericDefaultConsumer consumer) { return consumer.echo("value"); }
  String indirect(IndirectStringGenericDefaultConsumer consumer) { return consumer.echo("value"); }
  String bounded(BoundedStringGenericDefaultConsumer consumer) { return consumer.echo("value"); }
  void payload(StringMutableGenericDefaultConsumer consumer) {
    consumer.setPayload("value");
    String value = consumer.getPayload();
  }
}
""".trimIndent(),
),
)
}

  @Test
  fun generatedMultilevelGenericOverrideStubs_preserveInheritanceAndOmitTerminalSyntheticBridges() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        interface GenericContract<T> {
          fun accept(value: T): T
          var payload: T
        }

        abstract class GenericMiddle<T> : GenericContract<T> {
          abstract override fun accept(value: T): T
          abstract override var payload: T
        }

        class StringGenericLeaf : GenericMiddle<String>() {
          override fun accept(value: String): String = value
          override var payload: String = "payload"
        }
        """.trimIndent()
    val contractStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.GenericContract", "MultilevelGenericOverrideBridges.kt", kotlinSource, emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for GenericContract")
    val middleStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.GenericMiddle", "MultilevelGenericOverrideBridges.kt", kotlinSource, emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for GenericMiddle")
    val leafStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.StringGenericLeaf", "MultilevelGenericOverrideBridges.kt", kotlinSource, emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for StringGenericLeaf")

    assertContains(contractStub, "public interface GenericContract<T>")
    assertContains(middleStub, "public class GenericMiddle<T> implements GenericContract<T>")
    assertContains(middleStub, "T accept(T value)")
    assertContains(middleStub, "T getPayload()")
    assertContains(middleStub, "void setPayload(T value)")
    assertContains(leafStub, "public class StringGenericLeaf extends GenericMiddle<String>")
    assertContains(leafStub, "public String accept(String value)")
    assertContains(leafStub, "public String getPayload()")
    assertContains(leafStub, "public void setPayload(String value)")
    assertFalse("Terminal Object bridges must not be projected:\n$leafStub",
      leafStub.contains("Object accept(Object value)") ||
        leafStub.contains("Object getPayload()") ||
        leafStub.contains("void setPayload(Object value)"))

    val stubs = mapOf(
      "sample.GenericContract" to contractStub,
      "sample.GenericMiddle" to middleStub,
      "sample.StringGenericLeaf" to leafStub,
    )
    assertTrue(
      "Multilevel generic override stubs must remain attributable by javac:\n$stubs",
      javacSucceeds(
        stubs,
        "consumer.MultilevelGenericOverrideConsumer",
        """
        package consumer;
        import sample.GenericContract;
        import sample.GenericMiddle;
        import sample.StringGenericLeaf;
        class MultilevelGenericOverrideConsumer {
          String useLeaf(String value) {
            StringGenericLeaf leaf = new StringGenericLeaf();
            leaf.setPayload(value);
            return leaf.accept(value) + leaf.getPayload();
          }
          String useMiddle(GenericMiddle<String> middle, String value) {
            middle.setPayload(value);
            return middle.accept(value) + middle.getPayload();
          }
          String useContract(GenericContract<String> contract, String value) {
            contract.setPayload(value);
            return contract.accept(value) + contract.getPayload();
          }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun generatedCovariantReturnOverrideStubs_omitReturnOnlySyntheticBridgesButRemainJavacAttributable() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        interface CovariantContract {
          fun render(): CharSequence
          val title: CharSequence
        }

        class StringCovariantContract : CovariantContract {
          override fun render(): String = "rendered"
          override val title: String = "title"
        }
        """.trimIndent()
    val contractStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.CovariantContract",
      "CovariantReturnOverrideBridges.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for CovariantContract")
    val implementationStub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.StringCovariantContract",
      "CovariantReturnOverrideBridges.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for StringCovariantContract")

    assertContains(contractStub, "public interface CovariantContract")
    assertContains(contractStub, "CharSequence render()")
    assertContains(contractStub, "CharSequence getTitle()")

    assertContains(implementationStub, "public class StringCovariantContract")
    assertContains(implementationStub, "public String render()")
    assertContains(implementationStub, "public String getTitle()")
    assertFalse("Synthetic CharSequence return bridge must not be projected:\n$implementationStub",
      implementationStub.contains("CharSequence render()") ||
        implementationStub.contains("CharSequence getTitle()"))

    val stubs = mapOf(
      "sample.CovariantContract" to contractStub,
      "sample.StringCovariantContract" to implementationStub,
    )
    assertTrue(
      "Covariant return override stubs must remain attributable by javac:\n$stubs",
      javacSucceeds(
        stubs,
        "consumer.CovariantReturnOverrideConsumer",
        """
        package consumer;
        import sample.CovariantContract;
        import sample.StringCovariantContract;
        class CovariantReturnOverrideConsumer {
          String useImplementation() {
            StringCovariantContract implementation = new StringCovariantContract();
            String rendered = implementation.render();
            String title = implementation.getTitle();
            return rendered + title;
          }
          CharSequence useContract(CovariantContract contract) {
            return contract.render().toString() + contract.getTitle();
          }
        }
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun generatedCompanionJvmFieldAccessorAnnotations_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        class FieldAnnotationConsumer {
          companion object {
            @get:JvmSynthetic
            @JvmField
            val syntheticField: String = "field"
          }
        }
        """.trimIndent()
    val stub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.FieldAnnotationConsumer",
      "FieldAnnotationConsumer.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for FieldAnnotationConsumer")
    val stubs = mapOf("sample.FieldAnnotationConsumer" to stub)

    assertTrue(
      "@JvmField host surface must be attributable:\n$stub",
      javacSucceeds(
        stubs,
        "consumer.SupportedFieldAnnotationConsumer",
        """
        package consumer;
        import sample.FieldAnnotationConsumer;
        class SupportedFieldAnnotationConsumer {
          String read() { return FieldAnnotationConsumer.syntheticField; }
        }
        """.trimIndent(),
      ),
    )

    val unsupportedMethods = listOf(
      "String read() { return FieldAnnotationConsumer.getSyntheticField(); }",
      "String read() { return FieldAnnotationConsumer.Companion.getSyntheticField(); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      assertFalse(
        "@JvmField must not expose an accessor despite accessor annotations: $method\n$stub",
        javacSucceeds(
          stubs,
          "consumer.UnsupportedFieldAnnotationConsumer$index",
          """
          package consumer;
          import sample.FieldAnnotationConsumer;
          class UnsupportedFieldAnnotationConsumer$index {
            $method
          }
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun generatedCompanionJvmFieldAccessorJvmNames_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        class NamedFieldAnnotationConsumer {
          companion object {
            @get:JvmName("readNamedField")
            @JvmField
            val namedField: String = "field"
          }
        }
        """.trimIndent()
    val stub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.NamedFieldAnnotationConsumer",
      "NamedFieldAnnotationConsumer.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for NamedFieldAnnotationConsumer")
    val stubs = mapOf("sample.NamedFieldAnnotationConsumer" to stub)

    assertTrue(
      "@JvmField host surface must be attributable despite accessor @JvmName:\n$stub",
      javacSucceeds(
        stubs,
        "consumer.SupportedNamedFieldAnnotationConsumer",
        """
        package consumer;
        import sample.NamedFieldAnnotationConsumer;
        class SupportedNamedFieldAnnotationConsumer {
          String read() { return NamedFieldAnnotationConsumer.namedField; }
        }
        """.trimIndent(),
      ),
    )

    val unsupportedMethods = listOf(
      "String read() { return NamedFieldAnnotationConsumer.getNamedField(); }",
      "String read() { return NamedFieldAnnotationConsumer.readNamedField(); }",
      "String read() { return NamedFieldAnnotationConsumer.Companion.readNamedField(); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      assertFalse(
        "@JvmField must not expose an accessor despite accessor @JvmName: $method\n$stub",
        javacSucceeds(
          stubs,
          "consumer.UnsupportedNamedFieldAnnotationConsumer$index",
          """
          package consumer;
          import sample.NamedFieldAnnotationConsumer;
          class UnsupportedNamedFieldAnnotationConsumer$index {
            $method
          }
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun generatedCompanionJvmFieldMutableSetterAnnotations_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        class MutableFieldAnnotationConsumer {
          companion object {
            @set:JvmSynthetic
            @JvmField
            var syntheticMutableField: String = "synthetic"

            @set:JvmName("writeNamedMutableField")
            @JvmField
            var namedMutableField: String = "named"
          }
        }
        """.trimIndent()
    val stub = KotlinJvmAbiStubGenerator.generateForTest(
      "sample.MutableFieldAnnotationConsumer",
      "MutableFieldAnnotationConsumer.kt",
      kotlinSource,
      emptySet(),
      KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
    ) ?: error("Missing generated stub for MutableFieldAnnotationConsumer")
    val stubs = mapOf("sample.MutableFieldAnnotationConsumer" to stub)

    assertTrue(
      "@JvmField mutable host surface must be attributable:\n$stub",
      javacSucceeds(
        stubs,
        "consumer.SupportedMutableFieldAnnotationConsumer",
        """
        package consumer;
        import sample.MutableFieldAnnotationConsumer;
        class SupportedMutableFieldAnnotationConsumer {
          void write() {
            MutableFieldAnnotationConsumer.syntheticMutableField = "updated";
            MutableFieldAnnotationConsumer.namedMutableField = "named-updated";
          }
        }
        """.trimIndent(),
      ),
    )

    val unsupportedMethods = listOf(
      "void write() { MutableFieldAnnotationConsumer.setSyntheticMutableField(\"updated\"); }",
      "void write() { MutableFieldAnnotationConsumer.setNamedMutableField(\"updated\"); }",
      "void write() { MutableFieldAnnotationConsumer.writeNamedMutableField(\"updated\"); }",
      "void write() { MutableFieldAnnotationConsumer.Companion.setSyntheticMutableField(\"updated\"); }",
      "void write() { MutableFieldAnnotationConsumer.Companion.setNamedMutableField(\"updated\"); }",
      "void write() { MutableFieldAnnotationConsumer.Companion.writeNamedMutableField(\"updated\"); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      assertFalse(
        "@JvmField must not expose a setter despite setter annotations: $method\n$stub",
        javacSucceeds(
          stubs,
          "consumer.UnsupportedMutableFieldAnnotationConsumer$index",
          """
          package consumer;
          import sample.MutableFieldAnnotationConsumer;
          class UnsupportedMutableFieldAnnotationConsumer$index {
            $method
          }
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun generatedCompanionJvmStaticSyntheticAccessors_controlJavacConsumerSurface() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val kotlinSource =
        """
        package sample

        class SyntheticGetterConsumer {
          companion object {
            @get:JvmSynthetic
            @JvmStatic
            var secret: String = "secret"
          }
        }

        class SyntheticSetterConsumer {
          companion object {
            @set:JvmSynthetic
            @JvmStatic
            var visible: String = "visible"
          }
        }
        """.trimIndent()
    val stubs = mapOf(
      "sample.SyntheticGetterConsumer" to KotlinJvmAbiStubGenerator.generateForTest(
        "sample.SyntheticGetterConsumer",
        "SyntheticGetterConsumer.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      )!!,
      "sample.SyntheticSetterConsumer" to KotlinJvmAbiStubGenerator.generateForTest(
        "sample.SyntheticSetterConsumer",
        "SyntheticSetterConsumer.kt",
        kotlinSource,
        emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
      )!!,
    )

    assertTrue(
      "Non-synthetic companion accessors must be attributable on host and nested owners:\n$stubs",
      javacSucceeds(
        stubs,
        "consumer.SupportedSyntheticCompanionAccessors",
        """
        package consumer;
        import sample.SyntheticGetterConsumer;
        import sample.SyntheticSetterConsumer;
        class SupportedSyntheticCompanionAccessors {
          void use() {
            SyntheticGetterConsumer.setSecret("secret");
            SyntheticGetterConsumer.Companion.setSecret("secret");
            String visible = SyntheticSetterConsumer.getVisible();
            String nestedVisible = SyntheticSetterConsumer.Companion.getVisible();
          }
        }
        """.trimIndent(),
      ),
    )

    val unsupportedMethods = listOf(
      "void use() { SyntheticGetterConsumer.getSecret(); }",
      "void use() { SyntheticGetterConsumer.Companion.getSecret(); }",
      "void use() { SyntheticSetterConsumer.setVisible(\"visible\"); }",
      "void use() { SyntheticSetterConsumer.Companion.setVisible(\"visible\"); }",
    )
    for ((index, method) in unsupportedMethods.withIndex()) {
      assertFalse(
        "Synthetic companion accessor must not be attributable: $method\n$stubs",
        javacSucceeds(
          stubs,
          "consumer.UnsupportedSyntheticCompanionAccessor$index",
          """
          package consumer;
          import sample.SyntheticGetterConsumer;
          import sample.SyntheticSetterConsumer;
          class UnsupportedSyntheticCompanionAccessor$index {
            $method
          }
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun generate_projectsCompanionJvmStaticPropertyHonorsSyntheticAccessors() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class SyntheticStaticProperty {
          companion object {
            @get:JvmSynthetic
            @JvmStatic
            var secret: String = "secret"
          }
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.SyntheticStaticProperty", "SyntheticStaticProperty.kt", source, emptySet(), mode)
      assertNotNull("Synthetic static property generation failed in $mode", stub)
      assertFalse("Synthetic static getter leaked in $mode:\n$stub", stub!!.contains("getSecret()"))
      assertContains(stub, "static void setSecret(String value)")
    }
  }

  @Test
  fun generate_projectsCompanionJvmStaticPropertyHonorsSyntheticSetter() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class SyntheticStaticSetter {
          companion object {
            @set:JvmSynthetic
            @JvmStatic
            var visible: String = "visible"
          }
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.SyntheticStaticSetter", "SyntheticStaticSetter.kt", source, emptySet(), mode)
      assertNotNull("Synthetic static setter generation failed in $mode", stub)
      assertContains(stub!!, "static String getVisible()")
      assertFalse("Synthetic static setter leaked in $mode:\n$stub", stub.contains("setVisible(String value)"))
    }
  }

  @Test
  fun generate_projectsOrdinaryNestedTypes() {
    val source =
        """
        package sample

        class Outer<T> {
          class Nested(val value: String) {
            fun label(): String = value

            class Deep(val count: Int)
          }

          interface Listener {
            fun onChanged(value: Int)
          }

          object Defaults {
            val enabled: Boolean = true
          }

          inner class Entry(val name: T)
          private class Hidden
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Outer", "Outer.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public static class Nested")
    assertContains(stub, "public Nested(String value)")
    assertFalse(stub.contains("protected Nested()"))
    assertFalse(stub.contains("public Nested()"))
    assertContains(stub, "public String getValue()")
    assertContains(stub, "public String label()")
    assertContains(stub, "public static class Deep")
    assertContains(stub, "public Deep(int count)")
    assertContains(stub, "public static interface Listener")
    assertContains(stub, "public void onChanged(int value)")
    assertContains(stub, "public static class Defaults")
    assertContains(stub, "public static final Defaults INSTANCE")
    assertContains(stub, "public boolean getEnabled()")
    assertContains(stub, "public class Entry")
    assertFalse(stub.contains("public static class Entry"))
    assertContains(stub, "public Entry(T name)")
    assertContains(stub, "public T getName()")
    assertFalse(stub.contains("class Hidden"))
  }

  @Test
  fun generate_projectsNullablePrimitivesCollectionsAndArrays() {
    val source =
        """
        package sample

        class TypeSamples(
          val nullableCount: Int?,
          val names: List<String>,
          val lookup: Map<String, Int>,
          val numbers: IntArray,
          val labels: Array<String>
        ) {
          fun transform(values: MutableList<Long?>): Set<Int> = emptySet()
          fun wildcard(values: List<*>): Collection<out Int> = emptyList()
          fun unknown(callback: (String) -> Int): Any = callback
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.TypeSamples", "TypeSamples.kt", source)

    assertNotNull(stub)
    assertContains(
        stub!!,
        "public TypeSamples(Integer nullableCount, java.util.List<String> names, " +
            "java.util.Map<String, Integer> lookup, int[] numbers, String[] labels)")
    assertContains(stub, "public Integer getNullableCount()")
    assertContains(stub, "public java.util.List<String> getNames()")
    assertContains(stub, "public java.util.Map<String, Integer> getLookup()")
    assertContains(stub, "public int[] getNumbers()")
    assertContains(stub, "public String[] getLabels()")
    assertContains(stub, "public java.util.Set<Integer> transform(java.util.List<Long> values)")
    assertContains(
        stub,
        "public java.util.Collection<? extends Integer> wildcard(java.util.List<?> values)")
    assertContains(stub, "public Object unknown(Object callback)")
  }

  @Test
  fun generate_preservesResolvableUserDeclaredTypes() {
    val source =
        """
        package sample

        import android.content.Context
        import java.time.Instant as TimePoint

        class Profile(val id: String)

        class Repository(val context: Context) {
          fun load(profile: Profile): Profile = profile
          fun history(): List<Profile> = emptyList()
          fun timestamp(): TimePoint? = null
          fun qualified(): java.util.Locale? = null
          fun callback(): (Profile) -> Profile = { it }
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Repository", "Repository.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public Repository(android.content.Context context)")
    assertContains(stub, "public Profile load(Profile profile)")
    assertContains(stub, "public java.util.List<Profile> history()")
    assertContains(stub, "public java.time.Instant timestamp()")
    assertContains(stub, "public java.util.Locale qualified()")
    assertContains(stub, "public Object callback()")
  }

  @Test
  fun generate_preservesKnownSamePackageTypeFromAnotherFile() {
    val source =
        """
        package sample

        class Repository {
          fun load(): Profile = error("not built")
        }
        """.trimIndent()

    val stub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.Repository", "Repository.kt", source, setOf("sample.Repository", "sample.Profile"))

    assertNotNull(stub)
    assertContains(stub!!, "public Profile load()")
  }

  @Test
  fun generate_projectsClassAndMethodTypeParameters() {
    val source =
        """
        package sample

        class Box<T : CharSequence>(val value: T) {
          fun getOr(defaultValue: T): T = value
          fun <R> map(value: R): List<R> = listOf(value)
          fun <N : Number> number(value: N): N = value
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Box", "Box.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public class Box<T extends CharSequence>")
    assertContains(stub, "public Box(T value)")
    assertContains(stub, "public T getValue()")
    assertContains(stub, "public T getOr(T defaultValue)")
    assertContains(stub, "public <R> java.util.List<R> map(R value)")
    assertContains(stub, "public <N extends Number> N number(N value)")
  }

  @Test
  fun generate_projectsClassAndInterfaceInheritance() {
    val source =
        """
        package sample

        interface Named
        interface Tagged<T>
        open class Base(val label: String)

        class User<T>(val id: T) : Base("user"), Named, Tagged<T>
        interface Detailed : Named, Tagged<String>
        """.trimIndent()

    val baseStub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.Base",
            "Models.kt",
            source,
            setOf("sample.Named", "sample.Tagged", "sample.Base", "sample.User", "sample.Detailed"))
    val userStub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.User",
            "Models.kt",
            source,
            setOf("sample.Named", "sample.Tagged", "sample.Base", "sample.User", "sample.Detailed"))
    val interfaceStub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.Detailed",
            "Models.kt",
            source,
            setOf("sample.Named", "sample.Tagged", "sample.Base", "sample.User", "sample.Detailed"))

    assertNotNull(baseStub)
    assertContains(baseStub!!, "protected Base()")
    assertContains(baseStub, "__kotlin_abi_synthetic_constructor__")
    assertContains(baseStub, "public Base(String label)")

    assertNotNull(userStub)
    assertContains(
        userStub!!,
        "public class User<T> extends Base implements Named, Tagged<T>")
    assertContains(userStub, "public User(T id)")

    assertNotNull(interfaceStub)
    assertContains(
        interfaceStub!!,
        "public interface Detailed extends Named, Tagged<String>")
  }

  @Test
  fun generate_projectsSecondaryConstructorsAndVisibility() {
    val source =
        """
        package sample

        class Token private constructor(val raw: String) {
          constructor(code: Int) : this(code.toString())
          protected constructor(flag: Boolean) : this(flag.toString())
          private constructor(value: Long) : this(value.toString())
          constructor(vararg parts: String) : this(parts.joinToString())
        }

        class Legacy {
          constructor(value: String)
        }
        """.trimIndent()

    val tokenStub = KotlinJvmAbiStubGenerator.generate("sample.Token", "Tokens.kt", source)
    val legacyStub = KotlinJvmAbiStubGenerator.generate("sample.Legacy", "Tokens.kt", source)

    assertNotNull(tokenStub)
    assertContains(tokenStub!!, "protected Token()")
    assertContains(tokenStub, "private Token(String raw)")
    assertContains(tokenStub, "public Token(int code)")
    assertContains(tokenStub, "protected Token(boolean flag)")
    assertContains(tokenStub, "private Token(long value)")
    assertContains(tokenStub, "public Token(String... parts)")

    assertNotNull(legacyStub)
    assertContains(legacyStub!!, "protected Legacy()")
    assertContains(legacyStub, "public Legacy(String value)")
    assertFalse(legacyStub.contains("public Legacy()"))
  }

  @Test
  fun generate_rejectsSecondaryJvmOverloadsConstructorSurfaceConflictingWithPrimary() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class OverloadConstructorConflict(val value: String) {
          @JvmOverloads
          constructor(value: String, retries: Int = 0) : this(value)
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generateForTest(
        "sample.OverloadConstructorConflict", "OverloadConstructorConflict.kt", source, emptySet(),
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
    assertNotNull("Secondary @JvmOverloads conflict generation failed", stub)
    assertFalse("Conflicting String constructor surface leaked:\n$stub",
        stub!!.contains("OverloadConstructorConflict(String value)"))
    assertContains(stub, "public OverloadConstructorConflict(String value, int retries)")
  }

  @Test
  fun generate_projectsSecondaryJvmOverloadsWithNonTrailingVararg() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")
    val source =
        """
        package sample

        class SecondaryVarargOverloads private constructor() {
          @JvmOverloads
          constructor(vararg values: String, suffix: String = "") : this()
        }
        """.trimIndent()

    for (mode in listOf(
        KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED,
        KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)) {
      val stub = KotlinJvmAbiStubGenerator.generateForTest(
          "sample.SecondaryVarargOverloads", "SecondaryVarargOverloads.kt", source, emptySet(), mode)
      assertNotNull("Secondary non-trailing vararg overload generation failed in $mode", stub)
      assertContains(stub!!,
          "public SecondaryVarargOverloads(String[] values, String suffix)")
      assertContains(stub, "public SecondaryVarargOverloads(String... values)")
      assertFalse("Non-trailing secondary vararg was rendered as Java varargs in $mode:\n$stub",
          stub.contains("SecondaryVarargOverloads(String... values, String suffix)"))
    }
  }

  @Test
  fun generate_projectsJvmOverloadsConstructors() {
    val source =
        """
        package sample

        class Request @JvmOverloads constructor(
          val path: String,
          val retries: Int = 3,
          val secure: Boolean = true
        )

        class Defaults @JvmOverloads constructor(
          val name: String = "default",
          val count: Int = 1
        )

        class Alternate private constructor(val value: String) {
          @JvmOverloads
          protected constructor(code: Int, enabled: Boolean = true, label: String = "") :
              this(code.toString())
        }

        class Plain(val name: String = "plain")
        """.trimIndent()

    val requestStub = KotlinJvmAbiStubGenerator.generate("sample.Request", "Requests.kt", source)
    val defaultsStub = KotlinJvmAbiStubGenerator.generate("sample.Defaults", "Requests.kt", source)
    val alternateStub = KotlinJvmAbiStubGenerator.generate("sample.Alternate", "Requests.kt", source)
    val plainStub = KotlinJvmAbiStubGenerator.generate("sample.Plain", "Requests.kt", source)

    assertNotNull(requestStub)
    assertContains(requestStub!!, "public Request(String path, int retries, boolean secure)")
    assertContains(requestStub, "public Request(String path, int retries)")
    assertContains(requestStub, "public Request(String path)")

    assertNotNull(defaultsStub)
    assertContains(defaultsStub!!, "public Defaults(String name, int count)")
    assertContains(defaultsStub, "public Defaults(String name)")
    assertContains(defaultsStub, "public Defaults()")
    assertFalse(defaultsStub.contains("protected Defaults()"))
    assertFalse(defaultsStub.contains("__kotlin_abi_synthetic_constructor__"))

    assertNotNull(alternateStub)
    assertContains(alternateStub!!, "private Alternate(String value)")
    assertContains(alternateStub, "protected Alternate(int code, boolean enabled, String label)")
    assertContains(alternateStub, "protected Alternate(int code, boolean enabled)")
    assertContains(alternateStub, "protected Alternate(int code)")

    assertNotNull(plainStub)
    assertContains(plainStub!!, "public Plain(String name)")
    assertFalse(plainStub.contains("public Plain()"))
  }

  @Test
  fun generate_projectsVarargParameters() {
    val classSource =
        """
        package sample

        class Varargs {
          fun names(vararg values: String): String = values.joinToString()
          fun ints(prefix: String, vararg values: Int): Int = values.size
          fun <T> collect(vararg values: T): List<T> = values.toList()
          fun middle(vararg values: String, suffix: String): String = suffix
          fun marker(first: String = "vararg", second: String): String = second
          @JvmOverloads
          fun overloaded(vararg values: String, suffix: String = ""): String = suffix
        }
        """.trimIndent()
    val facadeSource =
        """
        package sample

        fun join(vararg values: String): String = values.joinToString()
        """.trimIndent()

    val classStub =
        KotlinJvmAbiStubGenerator.generate("sample.Varargs", "Varargs.kt", classSource)
    val facadeStub =
        KotlinJvmAbiStubGenerator.generate("sample.FunctionsKt", "Functions.kt", facadeSource)

    assertNotNull(classStub)
    assertContains(classStub!!, "public String names(String... values)")
    assertContains(classStub, "public int ints(String prefix, int... values)")
    assertContains(classStub, "public <T> java.util.List<T> collect(T... values)")
    assertContains(classStub, "public String middle(String[] values, String suffix)")
    assertContains(classStub, "public String marker(String first, String second)")
    assertContains(classStub, "public String overloaded(String[] values, String suffix)")
    assertContains(classStub, "public String overloaded(String[] values)")

    assertNotNull(facadeStub)
    assertContains(facadeStub!!, "public static String join(String... values)")
  }

  @Test
  fun generate_excludesPrivateAndMismatchedDeclarations() {
    val privateType =
        """
        package sample
        private class Hidden
        """.trimIndent()
    val publicType =
        """
        package other
        class Visible
        """.trimIndent()

    assertNull(KotlinJvmAbiStubGenerator.generate("sample.Hidden", "Hidden.kt", privateType))
    assertNull(KotlinJvmAbiStubGenerator.generate("sample.Visible", "Visible.kt", publicType))
  }

  @Test
  fun generate_excludesPrivateMembers() {
    val source =
        """
        package sample

        class Service {
          private fun secret(): String = "hidden"
          fun visible(): String = "visible"
          private val token: String = "token"
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Service", "Service.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public String visible()")
    assertFalse(stub.contains("secret("))
    assertFalse(stub.contains("getToken("))
  }

  @Test
  fun generate_structuredAndFallbackHaveJvmSurfaceParity() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")

    val cases =
        listOf(
            AbiParityCase(
                "class members and resolved types",
                "sample.Service",
                "Service.kt",
                """
                package sample

                open class Base<T>
                class Service(val name: String, var count: Int) : Base<String>() {
                  fun labels(values: List<String?>): List<String?> = values
                  fun <T> echo(value: T): T = value
                  val enabled: Boolean = true
                }
                """.trimIndent()),
            AbiParityCase(
                "JVM names and companion members",
                "sample.Named",
                "Named.kt",
                """
                package sample

                class Named(var isActive: Boolean) {
                  @get:JvmName("readMode")
                  @set:JvmName("writeMode")
                  var mode: String = "default"

                  @JvmName("loadValue")
                  fun load(): String = mode

                  companion object {
                    @JvmStatic fun create(value: String): Named = Named(value.isNotEmpty())
                    @JvmField val version: Int = 1
                  }
                }
                """.trimIndent()),
            AbiParityCase(
                "top-level facade JVM names",
                "sample.NamedApiKt",
                "NamedApi.kt",
                """
                package sample

                @JvmName("loadValue")
                fun load(): String = "value"

                @JvmName("renderText")
                fun String.render(): String = this

                @get:JvmName("readMode")
                @set:JvmName("writeMode")
                var mode: String = "default"

                var isFeatureEnabled: Boolean = true
                """.trimIndent()),
            AbiParityCase(
                "constructors and nested types",
                "sample.Outer",
                "Outer.kt",
                """
                package sample

                class Outer<T> @JvmOverloads constructor(val id: String, val count: Int = 0) {
                  constructor(id: Int) : this(id.toString())

                  class Nested(val value: String) {
                    class Deep(val count: Int)
                  }
                  interface Listener { fun onChanged(value: Int) }
                  object Defaults { val enabled: Boolean = true }
                  inner class Entry(val name: T)
                  private class Hidden
                }
                """.trimIndent()))

    for (case in cases) {
      val structured =
          KotlinJvmAbiStubGenerator.generateForTest(
              case.qualifiedName,
              case.fileName,
              case.source,
              emptySet(),
              KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
      val fallback =
          KotlinJvmAbiStubGenerator.generateForTest(
              case.qualifiedName,
              case.fileName,
              case.source,
              emptySet(),
              KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)

      assertNotNull("Structured generation failed for ${case.description}", structured)
      assertNotNull("Fallback generation failed for ${case.description}", fallback)
      assertEquals(
          "JVM ABI surface differs for ${case.description}\n" +
              "Structured stub:\n$structured\nFallback stub:\n$fallback",
          jvmSurface(structured!!),
          jvmSurface(fallback!!))
    }
  }

  private fun javacSucceeds(stub: String, consumer: String): Boolean {
    return javacSucceeds(
      mapOf("sample.ValueClassExtensionsKt" to stub),
      "consumer.Consumer",
      consumer,
    )
  }

  private fun javacSucceeds(
    stubs: Map<String, String>,
    consumerName: String,
    consumer: String,
  ): Boolean {
    val sources = stubs.map { (qualifiedName, code) ->
      InMemoryJavaSource(qualifiedName, code)
    } + InMemoryJavaSource(consumerName, consumer)
    val outputDirectory = Files.createTempDirectory("kotlin-jvm-abi-stub-javac-")
    try {
      val diagnostics = DiagnosticCollector<JavaFileObject>()
      return JavacTool.create()
        .getTask(
          null,
          null,
          diagnostics,
          listOf("-d", outputDirectory.toString()),
          null,
          sources,
        )
        .call() == true
    } finally {
      Files.walk(outputDirectory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
      }
    }
  }

  private class InMemoryJavaSource(qualifiedName: String, private val code: String) :
    SimpleJavaFileObject(
      URI.create("string:///" + qualifiedName.replace('.', '/') + ".java"),
      JavaFileObject.Kind.SOURCE,
    ) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
  }

  private data class AbiParityCase(
      val description: String,
      val qualifiedName: String,
      val fileName: String,
      val source: String)

  /**
   * Converts generated Java into an order-independent declaration surface. Generator stubs place
   * declarations on individual lines, so this intentionally ignores formatting and method bodies
   * while retaining the declaring type, modifiers, names, parameter types, and return types.
   */
  private fun jvmSurface(stub: String): Set<String> {
    val result = linkedSetOf<String>()
    val owners = mutableListOf<String>()
    val typePattern =
        Regex(
            "(?:public\\s+)?(?:static\\s+)?(?:final\\s+)?" +
                "(class|interface|enum|@interface)\\s+([A-Za-z_$][\\w$]*)")

    for (rawLine in stub.lineSequence()) {
      val line = rawLine.trim().replace(Regex("\\s+"), " ")
      if (line.isEmpty() || line.startsWith("package ")) continue

      val type = typePattern.find(line)
      if (type != null) {
        val owner = (owners + type.groupValues[2]).joinToString(".")
        val declaration = line.substringBefore('{').trim()
        result += "type:$owner:$declaration"
        if (line.contains('{') && !line.contains("{}") && !line.contains("{ ; }")) {
          owners += type.groupValues[2]
        }
        continue
      }

      if (line == "}") {
        if (owners.isNotEmpty()) owners.removeAt(owners.lastIndex)
        continue
      }
      if (owners.isEmpty()) continue

      val declaration = canonicalMemberDeclaration(line)
      if (declaration.isNotEmpty()) {
        result += "member:${owners.joinToString(".")}:$declaration"
      }
    }
    return result.toSortedSet()
  }

  private fun canonicalMemberDeclaration(line: String): String {
    val declaration =
        line
            .substringBefore(" { return ")
            .substringBefore(" { throw ")
            .removeSuffix(" {}")
            .removeSuffix(";")
            .trim()
    val open = declaration.indexOf('(')
    val close = declaration.lastIndexOf(')')
    if (open < 0 || close < open) {
      return declaration.substringBefore(" = ").trim()
    }

    val parameterTypes =
        splitJavaParameters(declaration.substring(open + 1, close)).map { parameter ->
          parameter.trim().substringBeforeLast(' ', parameter.trim())
        }
    return declaration.substring(0, open + 1) +
        parameterTypes.joinToString(",") +
        declaration.substring(close)
  }

  private fun splitJavaParameters(parameters: String): List<String> {
    if (parameters.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    var genericDepth = 0
    var start = 0
    for (index in parameters.indices) {
      when (parameters[index]) {
        '<' -> genericDepth++
        '>' -> genericDepth--
        ',' ->
            if (genericDepth == 0) {
              result += parameters.substring(start, index)
              start = index + 1
            }
      }
    }
    result += parameters.substring(start)
    return result
  }

  private fun assertContains(actual: String, expected: String) {
    assertTrue("Expected generated stub to contain: $expected\nStub:\n$actual", actual.contains(expected))
  }
}
