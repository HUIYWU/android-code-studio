package com.tom.rv2ide.lsp.java.kotlin

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Opcodes

/**
 * Golden evidence for Kotlin/JVM value-class constructor representations.
 *
 * These assertions intentionally describe compiler output only. They do not yet authorize the
 * source projector to expose any of these constructors to Java LSP consumers.
 */
class KotlinCompilerJvmAbiProbeTest {

  @Test
  fun valueClassConstructorParameters_preserveCompilerBoxingAndArrayRepresentations() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class Direct(val id: UserId)
      class Nullable(val id: UserId?)
      class ValueClassArray(val ids: Array<UserId>)
      """.trimIndent(),
      "ValueClassConstructors.kt",
    ).associateBy { it.internalName }

    assertConstructorDescriptor(surfaces, "evidence/Direct", "(Ljava/lang/String;)V")
    // A nullable value class whose non-null underlying representation is already a reference can
    // encode null directly with that reference; Kotlin 2.1.0 does not box it as UserId here.
    assertConstructorDescriptor(surfaces, "evidence/Nullable", "(Ljava/lang/String;)V")
    assertConstructorDescriptor(surfaces, "evidence/ValueClassArray", "([Levidence/UserId;)V")

    // Direct unboxed value-class parameters need a compiler-generated marker overload. Record it
    // separately instead of mistaking it for an additional Java-source constructor surface.
    for (owner in listOf("evidence/Direct", "evidence/Nullable")) {
      val constructors = surfaces.getValue(owner).constructors()
      val representation = constructors.singleOrNull {
        it.descriptor == "(Ljava/lang/String;)V"
      }
      assertNotNull("Expected underlying representation constructor for $owner: $constructors", representation)
      assertTrue(
        "Expected underlying representation constructor to be private for $owner: $representation",
        representation!!.access and Opcodes.ACC_PRIVATE != 0,
      )

      val marker = constructors.singleOrNull {
        it.descriptor ==
          "(Ljava/lang/String;Lkotlin/jvm/internal/DefaultConstructorMarker;)V"
      }
      assertNotNull("Expected compiler marker constructor for $owner: $constructors", marker)
      assertTrue(
        "Expected marker constructor to be public for $owner: $marker",
        marker!!.access and Opcodes.ACC_PUBLIC != 0,
      )
      assertTrue(
        "Expected marker constructor to be synthetic for $owner: $marker",
        marker.access and Opcodes.ACC_SYNTHETIC != 0,
      )
    }
  }

  @Test
  fun valueClassArrayConstructor_isAJavaVisibleBoxedArraySurface() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class ValueClassArray(val ids: Array<UserId>)
      """.trimIndent(),
      "ValueClassArrayConstructor.kt",
    ).associateBy { it.internalName }

    val constructors = surfaces.getValue("evidence/ValueClassArray").constructors()
    val arrayConstructor = constructors.singleOrNull {
      it.descriptor == "([Levidence/UserId;)V"
    }
    assertNotNull("Expected boxed value-class array constructor: $constructors", arrayConstructor)
    assertTrue(
      "Expected boxed value-class array constructor to be public: $arrayConstructor",
      arrayConstructor!!.access and Opcodes.ACC_PUBLIC != 0,
    )
    assertTrue(
      "Expected boxed value-class array constructor not to be synthetic: $arrayConstructor",
      arrayConstructor.access and Opcodes.ACC_SYNTHETIC == 0,
    )
  }

  @Test
  fun primitiveValueClassConstructorParameters_distinguishDirectAndNullableRepresentations() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class IntId(val raw: Int)

      class DirectIntId(val id: IntId)
      class NullableIntId(val id: IntId?)
      class IntIdArray(val ids: Array<IntId>)
      """.trimIndent(),
      "PrimitiveValueClassConstructors.kt",
    ).associateBy { it.internalName }

    assertConstructorDescriptor(surfaces, "evidence/DirectIntId", "(I)V")
    assertConstructorDescriptor(surfaces, "evidence/NullableIntId", "(Levidence/IntId;)V")
    assertConstructorDescriptor(surfaces, "evidence/IntIdArray", "([Levidence/IntId;)V")

    assertPrivateRepresentationAndPublicSyntheticMarker(
      surfaces.getValue("evidence/DirectIntId").constructors(),
      "(I)V",
      "(ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
    )
    assertPrivateRepresentationAndPublicSyntheticMarker(
      surfaces.getValue("evidence/NullableIntId").constructors(),
      "(Levidence/IntId;)V",
      "(Levidence/IntId;Lkotlin/jvm/internal/DefaultConstructorMarker;)V",
    )
  }

  @Test
  fun secondaryConstructorWithValueClassParameter_usesTheSameMarkerVisibilityBoundary() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class Secondary private constructor() {
        constructor(id: UserId) : this()
      }
      """.trimIndent(),
      "SecondaryValueClassConstructor.kt",
    ).associateBy { it.internalName }

    assertPrivateRepresentationAndPublicSyntheticMarker(
      surfaces.getValue("evidence/Secondary").constructors(),
      "(Ljava/lang/String;)V",
      "(Ljava/lang/String;Lkotlin/jvm/internal/DefaultConstructorMarker;)V",
    )
  }

  @Test
  fun mixedAndMultipleDirectValueClassParameters_doNotCreateJavaVisibleConstructors() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      @JvmInline
      value class IntId(val raw: Int)

      class Mixed(val id: UserId, val count: Int)
      class Multiple(val first: UserId, val second: IntId)
      """.trimIndent(),
      "MixedValueClassConstructors.kt",
    ).associateBy { it.internalName }

    assertNoPublicNonSyntheticConstructor(
      surfaces.getValue("evidence/Mixed").constructors(),
      "evidence/Mixed",
    )
    assertNoPublicNonSyntheticConstructor(
      surfaces.getValue("evidence/Multiple").constructors(),
      "evidence/Multiple",
    )
    assertPrivateConstructorDescriptor(
      surfaces.getValue("evidence/Mixed").constructors(),
      "(Ljava/lang/String;I)V",
    )
    assertPrivateConstructorDescriptor(
      surfaces.getValue("evidence/Multiple").constructors(),
      "(Ljava/lang/String;I)V",
    )
  }

  @Test
  fun defaultedDirectValueClassParameter_doesNotCreateJavaVisibleJvmOverloads() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class Defaulted @JvmOverloads constructor(
        val id: UserId,
        val count: Int = 0,
      )
      """.trimIndent(),
      "DefaultedValueClassConstructor.kt",
    ).associateBy { it.internalName }

    val constructors = surfaces.getValue("evidence/Defaulted").constructors()
    assertPrivateConstructorDescriptor(constructors, "(Ljava/lang/String;I)V")
    assertNoPublicNonSyntheticConstructor(constructors, "evidence/Defaulted")
    assertTrue(
      "Expected compiler-generated synthetic constructor machinery: $constructors",
      constructors.any { it.access and Opcodes.ACC_SYNTHETIC != 0 },
    )
  }

  @Test
  fun jvmOverloadsWithValueClassArray_createsNormalJavaVisibleVariants() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class ArrayOverloaded @JvmOverloads constructor(
        val ids: Array<UserId>,
        val count: Int = 0,
      )
      """.trimIndent(),
      "ValueClassArrayOverloads.kt",
    ).associateBy { it.internalName }

    val constructors = surfaces.getValue("evidence/ArrayOverloaded").constructors()
    assertPublicNonSyntheticConstructor(constructors, "([Levidence/UserId;I)V")
    assertPublicNonSyntheticConstructor(constructors, "([Levidence/UserId;)V")
  }

  @Test
  fun constructorPropertyAccessors_distinguishMangledScalarsFromBoxedContainers() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      @JvmInline
      value class IntId(val raw: Int)

      class ReferenceScalar(var id: UserId)
      class NullableReferenceScalar(var id: UserId?)
      class PrimitiveScalar(var id: IntId)
      class NullablePrimitiveScalar(var id: IntId?)
      class ArrayProperty(var ids: Array<UserId>)
      class GenericProperty(var ids: List<UserId>)
      """.trimIndent(),
      "ValueClassConstructorProperties.kt",
    ).associateBy { it.internalName }

    assertMangledAccessor(
      surfaces.getValue("evidence/ReferenceScalar"),
      "getId-", "()Ljava/lang/String;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/ReferenceScalar"),
      "setId-", "(Ljava/lang/String;)V",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullableReferenceScalar"),
      "getId-", "()Ljava/lang/String;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullableReferenceScalar"),
      "setId-", "(Ljava/lang/String;)V",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/PrimitiveScalar"),
      "getId-", "()I",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/PrimitiveScalar"),
      "setId-", "(I)V",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullablePrimitiveScalar"),
      "getId-", "()Levidence/IntId;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullablePrimitiveScalar"),
      "setId-", "(Levidence/IntId;)V",
    )

    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayProperty"),
      "getIds", "()[Levidence/UserId;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayProperty"),
      "setIds", "([Levidence/UserId;)V",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericProperty"),
      "getIds", "()Ljava/util/List;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericProperty"),
      "setIds", "(Ljava/util/List;)V",
    )
  }

  @Test
  fun memberPropertyAccessors_requireExplicitJvmNamesForScalarValueClasses() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

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
      """.trimIndent(),
      "ValueClassMemberProperties.kt",
    ).associateBy { it.internalName }

    assertMangledAccessor(
      surfaces.getValue("evidence/MemberScalar"),
      "getId-", "()Ljava/lang/String;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/MemberScalar"),
      "setId-", "(Ljava/lang/String;)V",
    )

    assertPlainAccessor(
      surfaces.getValue("evidence/NamedScalar"),
      "readId", "()Ljava/lang/String;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/NamedScalar"),
      "writeId", "(Ljava/lang/String;)V",
    )
    assertTrue(
      "Explicit @JvmName accessors must replace mangled defaults: ${surfaces.getValue("evidence/NamedScalar").members}",
      surfaces.getValue("evidence/NamedScalar").members.none {
        it.name.startsWith("getId-") || it.name.startsWith("setId-")
      },
    )

    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayMember"),
      "getIds", "()[Levidence/UserId;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayMember"),
      "setIds", "([Levidence/UserId;)V",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericMember"),
      "getIds", "()Ljava/util/List;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericMember"),
      "setIds", "(Ljava/util/List;)V",
    )
  }

  @Test
  fun companionValueClassProperties_distinguishJvmFieldAndJvmStaticSurfaces() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class FieldHost {
        companion object {
          @JvmField
          val ids: Array<UserId> = emptyArray()
        }
      }

      class StaticScalarHost {
        companion object {
          @JvmStatic
          var id: UserId = UserId("static")
        }
      }

      class NamedStaticScalarHost {
        companion object {
          @get:JvmName("readId")
          @set:JvmName("writeId")
          @JvmStatic
          var id: UserId = UserId("named")
        }
      }
      """.trimIndent(),
      "ValueClassCompanionProperties.kt",
    ).associateBy { it.internalName }

    val fieldHost = surfaces.getValue("evidence/FieldHost")
    val field = fieldHost.fieldsNamed("ids").singleOrNull { it.descriptor == "[Levidence/UserId;" }
    assertNotNull("Expected @JvmField boxed array field; actual=${fieldHost.fields}", field)
    assertTrue("Expected @JvmField to be public: $field", field!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected @JvmField to be static: $field", field.access and Opcodes.ACC_STATIC != 0)
    assertTrue("Expected @JvmField not to be synthetic: $field", field.access and Opcodes.ACC_SYNTHETIC == 0)
    assertTrue("@JvmField must not create a host getter: ${fieldHost.members}",
      fieldHost.methodsNamed("getIds").isEmpty())
    assertTrue("@JvmField must not create a host setter: ${fieldHost.members}",
      fieldHost.methodsNamed("setIds").isEmpty())

    for (owner in listOf(
      "evidence/StaticScalarHost",
      "evidence/StaticScalarHost\$Companion",
    )) {
      assertMangledAccessor(
        surfaces.getValue(owner), "getId-", "()Ljava/lang/String;",
      )
      assertMangledAccessor(
        surfaces.getValue(owner), "setId-", "(Ljava/lang/String;)V",
      )
    }

    assertStaticPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost"),
      "readId", "()Ljava/lang/String;",
    )
    assertStaticPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost"),
      "writeId", "(Ljava/lang/String;)V",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost\$Companion"),
      "readId", "()Ljava/lang/String;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost\$Companion"),
      "writeId", "(Ljava/lang/String;)V",
    )
  }

  @Test
  fun companionJvmFieldProperties_ignoreAccessorJvmAnnotationsWithoutCreatingAccessors() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class FieldAnnotationContract {
        companion object {
          @get:JvmSynthetic
          @JvmField
          val syntheticField: String = "synthetic"
        }
      }
      """.trimIndent(),
      "JvmFieldAccessorAnnotations.kt",
    ).associateBy { it.internalName }

    val host = surfaces.getValue("evidence/FieldAnnotationContract")
    val field = host.fieldsNamed("syntheticField").singleOrNull {
      it.descriptor == "Ljava/lang/String;"
    }
    assertNotNull("Expected @JvmField host field; actual=${host.fields}", field)
    assertTrue("Expected @JvmField field to be public: $field",
      field!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected @JvmField field to be static: $field",
      field.access and Opcodes.ACC_STATIC != 0)
    assertTrue("Accessor annotation must not make the field synthetic: $field",
      field.access and Opcodes.ACC_SYNTHETIC == 0)
    assertTrue("@JvmField must not create a host getter: ${host.members}",
      host.methodsNamed("getSyntheticField").isEmpty())

    val companion = surfaces.getValue("evidence/FieldAnnotationContract\$Companion")
    assertTrue("@JvmField must not create a Companion getter: ${companion.members}",
      companion.methodsNamed("getSyntheticField").isEmpty())
  }

  @Test
  fun companionJvmFieldProperties_ignoreAccessorJvmNamesWithoutCreatingAccessors() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class NamedFieldAnnotationContract {
        companion object {
          @get:JvmName("readNamedField")
          @JvmField
          val namedField: String = "field"
        }
      }
      """.trimIndent(),
      "JvmFieldAccessorJvmNames.kt",
    ).associateBy { it.internalName }

    val host = surfaces.getValue("evidence/NamedFieldAnnotationContract")
    val field = host.fieldsNamed("namedField").singleOrNull {
      it.descriptor == "Ljava/lang/String;"
    }
    assertNotNull("Expected @JvmField host field; actual=${host.fields}", field)
    assertTrue("Expected @JvmField field to be public: $field",
      field!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected @JvmField field to be static: $field",
      field.access and Opcodes.ACC_STATIC != 0)
    assertTrue("@JvmField must not create a default host getter: ${host.members}",
      host.methodsNamed("getNamedField").isEmpty())
    assertTrue("@JvmField must not create a named host getter: ${host.members}",
      host.methodsNamed("readNamedField").isEmpty())

    val companion = surfaces.getValue("evidence/NamedFieldAnnotationContract\$Companion")
    assertTrue("@JvmField must not create a named Companion getter: ${companion.members}",
      companion.methodsNamed("readNamedField").isEmpty())
  }

  @Test
  fun companionJvmFieldMutableProperties_ignoreSetterAnnotationsWithoutCreatingAccessors() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class MutableFieldAnnotationContract {
        companion object {
          @set:JvmSynthetic
          @JvmField
          var syntheticMutableField: String = "synthetic"

          @set:JvmName("writeNamedMutableField")
          @JvmField
          var namedMutableField: String = "named"
        }
      }
      """.trimIndent(),
      "JvmFieldMutableAccessorAnnotations.kt",
    ).associateBy { it.internalName }

    val host = surfaces.getValue("evidence/MutableFieldAnnotationContract")
    for (fieldName in listOf("syntheticMutableField", "namedMutableField")) {
      val field = host.fieldsNamed(fieldName).singleOrNull {
        it.descriptor == "Ljava/lang/String;"
      }
      assertNotNull("Expected @JvmField host field $fieldName; actual=${host.fields}", field)
      assertTrue("Expected @JvmField field to be public: $field",
        field!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("Expected @JvmField field to be static: $field",
        field.access and Opcodes.ACC_STATIC != 0)
      assertTrue("Setter annotation must not make the field synthetic: $field",
        field.access and Opcodes.ACC_SYNTHETIC == 0)
    }

    assertTrue("@JvmField must not create a default host getter: ${host.members}",
      host.methodsNamed("getSyntheticMutableField").isEmpty() &&
        host.methodsNamed("getNamedMutableField").isEmpty())
    assertTrue("@JvmField must not create a default host setter: ${host.members}",
      host.methodsNamed("setSyntheticMutableField").isEmpty() &&
        host.methodsNamed("setNamedMutableField").isEmpty())
    assertTrue("@JvmField must not create a named host setter: ${host.members}",
      host.methodsNamed("writeNamedMutableField").isEmpty())

    val companion = surfaces.getValue("evidence/MutableFieldAnnotationContract\$Companion")
    assertTrue("@JvmField must not create Companion getters: ${companion.members}",
      companion.methodsNamed("getSyntheticMutableField").isEmpty() &&
        companion.methodsNamed("getNamedMutableField").isEmpty())
    assertTrue("@JvmField must not create default or named Companion setters: ${companion.members}",
      companion.methodsNamed("setSyntheticMutableField").isEmpty() &&
        companion.methodsNamed("setNamedMutableField").isEmpty() &&
        companion.methodsNamed("writeNamedMutableField").isEmpty())
  }

  @Test
  fun companionJvmStaticProperties_preserveSyntheticAccessorFlags() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class SyntheticGetterHost {
        companion object {
          @get:JvmSynthetic
          @JvmStatic
          var secret: String = "secret"
        }
      }

      class SyntheticSetterHost {
        companion object {
          @set:JvmSynthetic
          @JvmStatic
          var visible: String = "visible"
        }
      }
      """.trimIndent(),
      "SyntheticCompanionProperties.kt",
    ).associateBy { it.internalName }

    for (owner in listOf(
      "evidence/SyntheticGetterHost",
      "evidence/SyntheticGetterHost\$Companion",
    )) {
      assertSyntheticAccessor(surfaces.getValue(owner), "getSecret", "()Ljava/lang/String;")
      assertPlainAccessor(surfaces.getValue(owner), "setSecret", "(Ljava/lang/String;)V")
    }
    for (owner in listOf(
      "evidence/SyntheticSetterHost",
      "evidence/SyntheticSetterHost\$Companion",
    )) {
      assertPlainAccessor(surfaces.getValue(owner), "getVisible", "()Ljava/lang/String;")
      assertSyntheticAccessor(surfaces.getValue(owner), "setVisible", "(Ljava/lang/String;)V")
    }
  }

  @Test
  fun valueClassExtensionProperties_preserveCompilerManglingBoundaries() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

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
      """.trimIndent(),
      "ValueClassFieldsAndExtensionProperties.kt",
    ).associateBy { it.internalName }

    val facade = surfaces.getValue("evidence/ValueClassFieldsAndExtensionPropertiesKt")
    assertMangledAccessor(facade, "getLabel-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(facade, "setLabel-", "(Ljava/lang/String;Ljava/lang/String;)V")
    // A value-class return alone does not force a JVM name mangling. The getter can use the
    // underlying String return, while the setter takes a value-class parameter and is mangled.
    assertStaticPlainAccessor(facade, "getId", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(facade, "setId-", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertMangledAccessor(facade, "getOther-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(facade, "setOther-", "(Ljava/lang/String;Ljava/lang/String;)V")

    assertStaticPlainAccessor(facade, "readLabel", "(Ljava/lang/String;)Ljava/lang/String;")
    assertStaticPlainAccessor(facade, "writeLabel", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertTrue("Explicit extension accessor names must replace mangled defaults: ${facade.members}",
      facade.members.none {
        it.name.startsWith("getNamedLabel-") || it.name.startsWith("setNamedLabel-")
      })
  }

  @Test
  fun interfaceValueClassExtensionProperties_preserveDefaultAccessorManglingBoundaries() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      interface ValueClassExtensionContract {
        var UserId.label: String
        var String.id: UserId
        var UserId.other: UserId
      }
      """.trimIndent(),
      "InterfaceValueClassExtensionProperties.kt",
    ).associateBy { it.internalName }

    val contract = surfaces.getValue("evidence/ValueClassExtensionContract")
    assertMangledAccessor(contract, "getLabel-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(contract, "setLabel-", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertMangledAccessor(contract, "getId-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(contract, "setId-", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertMangledAccessor(contract, "getOther-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(contract, "setOther-", "(Ljava/lang/String;Ljava/lang/String;)V")
  }

  @Test
  fun genericInterfaceExtensionProperties_preserveDefaultAccessorErasure() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      interface GenericExtensionContract<T> {
        var T.payload: T
      }

      interface BoundedExtensionContract<T : CharSequence> {
        var T.payload: T
      }
      """.trimIndent(),
      "GenericInterfaceExtensionProperties.kt",
    ).associateBy { it.internalName }

    val generic = surfaces.getValue("evidence/GenericExtensionContract")
    assertPlainAccessor(generic, "getPayload", "(Ljava/lang/Object;)Ljava/lang/Object;")
    assertPlainAccessor(generic, "setPayload", "(Ljava/lang/Object;Ljava/lang/Object;)V")

    val bounded = surfaces.getValue("evidence/BoundedExtensionContract")
    assertPlainAccessor(
      bounded,
      "getPayload",
      "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;",
    )
    assertPlainAccessor(
      bounded,
      "setPayload",
      "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)V",
    )
  }
  @Test
  fun genericOverrides_recordSyntheticBridgeSurfaceSeparatelyFromSourceApi() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      interface GenericContract<T> {
        fun accept(value: T): T
        var payload: T
      }

      class StringContract : GenericContract<String> {
        override fun accept(value: String): String = value
        override var payload: String = "payload"
      }
      """.trimIndent(),
      "GenericOverrideBridges.kt",
    ).associateBy { it.internalName }

    val implementation = surfaces.getValue("evidence/StringContract")

    assertPlainAccessor(implementation, "accept", "(Ljava/lang/String;)Ljava/lang/String;")
    assertPlainAccessor(implementation, "getPayload", "()Ljava/lang/String;")
    assertPlainAccessor(implementation, "setPayload", "(Ljava/lang/String;)V")

    assertSyntheticBridge(implementation, "accept", "(Ljava/lang/Object;)Ljava/lang/Object;")
    assertSyntheticBridge(implementation, "getPayload", "()Ljava/lang/Object;")
    assertSyntheticBridge(implementation, "setPayload", "(Ljava/lang/Object;)V")
  }

  @Test
  fun boundedGenericOverrides_useUpperBoundSyntheticBridges() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      interface BoundedContract<T : CharSequence> {
        fun accept(value: T): T
        var payload: T
      }

      class BoundedStringContract : BoundedContract<String> {
        override fun accept(value: String): String = value
        override var payload: String = "payload"
      }
      """.trimIndent(),
      "BoundedGenericOverrideBridges.kt",
    ).associateBy { it.internalName }

    val implementation = surfaces.getValue("evidence/BoundedStringContract")

    assertPlainAccessor(implementation, "accept", "(Ljava/lang/String;)Ljava/lang/String;")
    assertPlainAccessor(implementation, "getPayload", "()Ljava/lang/String;")
    assertPlainAccessor(implementation, "setPayload", "(Ljava/lang/String;)V")

    assertSyntheticBridge(
      implementation,
      "accept",
      "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;",
    )
    assertSyntheticBridge(implementation, "getPayload", "()Ljava/lang/CharSequence;")
    assertSyntheticBridge(implementation, "setPayload", "(Ljava/lang/CharSequence;)V")
  }
  @Test
  fun covariantReturnOverrides_recordReturnOnlySyntheticBridges() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      interface CovariantContract {
        fun render(): CharSequence
        val title: CharSequence
      }

      class StringCovariantContract : CovariantContract {
        override fun render(): String = "rendered"
        override val title: String = "title"
      }
      """.trimIndent(),
      "CovariantReturnOverrideBridges.kt",
    ).associateBy { it.internalName }

    val implementation = surfaces.getValue("evidence/StringCovariantContract")

    assertPlainAccessor(implementation, "render", "()Ljava/lang/String;")
    assertPlainAccessor(implementation, "getTitle", "()Ljava/lang/String;")

    assertSyntheticBridge(implementation, "render", "()Ljava/lang/CharSequence;")
    assertSyntheticBridge(implementation, "getTitle", "()Ljava/lang/CharSequence;")
  }

  @Test
  fun multilevelGenericOverrides_preserveTerminalErasedSyntheticBridges() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

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
      """.trimIndent(),
      "MultilevelGenericOverrideBridges.kt",
    ).associateBy { it.internalName }

    val leaf = surfaces.getValue("evidence/StringGenericLeaf")

    assertPlainAccessor(leaf, "accept", "(Ljava/lang/String;)Ljava/lang/String;")
    assertPlainAccessor(leaf, "getPayload", "()Ljava/lang/String;")
    assertPlainAccessor(leaf, "setPayload", "(Ljava/lang/String;)V")

    assertSyntheticBridge(leaf, "accept", "(Ljava/lang/Object;)Ljava/lang/Object;")
    assertSyntheticBridge(leaf, "getPayload", "()Ljava/lang/Object;")
    assertSyntheticBridge(leaf, "setPayload", "(Ljava/lang/Object;)V")
  }

  @Test
  fun interfaceDefaultImplementations_recordDefaultImplsHelperSurfaceSeparatelyFromContract() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

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

class StringMutableGenericDefaultConsumer : MutableGenericDefaultContract<String>

interface BoundedGenericDefaultContract<T : CharSequence> {
  fun echo(value: T): T = value
}

class BoundedStringGenericDefaultConsumer : BoundedGenericDefaultContract<String>

class ExplicitDefaultConsumer : DefaultContract {
  override fun render(value: String): String = "explicit:${'$'}value"
  override val title: String
    get() = "explicit"
}

abstract class AbstractDefaultConsumer : DefaultContract

interface MutableDefaultContract {
var enabled: Boolean
get() = true
set(value) {}
}

class MutableDefaultConsumer : MutableDefaultContract
""".trimIndent(),
      "InterfaceDefaultImplementations.kt",
    ).associateBy { it.internalName }

    val contract = surfaces.getValue("evidence/DefaultContract")
    assertPlainAccessor(contract, "render", "(Ljava/lang/String;)Ljava/lang/String;")
    assertPlainAccessor(contract, "getTitle", "()Ljava/lang/String;")
    for (member in listOf(
      contract.methodsNamed("render").single { it.descriptor == "(Ljava/lang/String;)Ljava/lang/String;" },
      contract.methodsNamed("getTitle").single { it.descriptor == "()Ljava/lang/String;" },
    )) {
      assertTrue("Default contract member must be public: $member", member.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("Default contract member must be abstract: $member", member.access and Opcodes.ACC_ABSTRACT != 0)
    }

    val defaults = surfaces.getValue("evidence/DefaultContract\$DefaultImpls")
    for ((name, descriptor) in listOf(
      "render" to "(Levidence/DefaultContract;Ljava/lang/String;)Ljava/lang/String;",
      "getTitle" to "(Levidence/DefaultContract;)Ljava/lang/String;",
    )) {
      val helper = defaults.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected DefaultImpls helper $name$descriptor; actual=${defaults.members}", helper)
      assertTrue("DefaultImpls helper must be public: $helper", helper!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("DefaultImpls helper must be static: $helper", helper.access and Opcodes.ACC_STATIC != 0)
      assertTrue("DefaultImpls helper must not be synthetic: $helper", helper.access and Opcodes.ACC_SYNTHETIC == 0)
    }

    val consumer = surfaces.getValue("evidence/DefaultConsumer")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getTitle" to "()Ljava/lang/String;",
    )) {
      val forwarder = consumer.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected DefaultConsumer forwarding method $name$descriptor; actual=${consumer.members}", forwarder)
      assertTrue("DefaultConsumer forwarder must be public: $forwarder", forwarder!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("DefaultConsumer forwarder must not be abstract: $forwarder", forwarder.access and Opcodes.ACC_ABSTRACT == 0)
      assertTrue("DefaultConsumer forwarder must not be synthetic: $forwarder", forwarder.access and Opcodes.ACC_SYNTHETIC == 0)
    }

    val indirectConsumer = surfaces.getValue("evidence/IndirectDefaultConsumer")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getTitle" to "()Ljava/lang/String;",
    )) {
      val forwarder = indirectConsumer.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected IndirectDefaultConsumer forwarding method $name$descriptor; actual=${indirectConsumer.members}", forwarder)
      assertTrue("IndirectDefaultConsumer forwarder must be public: $forwarder", forwarder!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("IndirectDefaultConsumer forwarder must not be abstract: $forwarder", forwarder.access and Opcodes.ACC_ABSTRACT == 0)
      assertTrue("IndirectDefaultConsumer forwarder must not be synthetic: $forwarder", forwarder.access and Opcodes.ACC_SYNTHETIC == 0)
    }

    val genericConsumer = surfaces.getValue("evidence/StringGenericDefaultConsumer")
    val genericForwarder = genericConsumer.methodsNamed("echo")
      .singleOrNull { it.descriptor == "(Ljava/lang/Object;)Ljava/lang/Object;" }
    assertNotNull(
      "Expected erased generic default forwarder echo(Object): Object; actual=${genericConsumer.members}",
      genericForwarder,
    )
    assertTrue("Generic default forwarder must be public: $genericForwarder",
      genericForwarder!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Generic default forwarder must not be abstract: $genericForwarder",
      genericForwarder.access and Opcodes.ACC_ABSTRACT == 0)
    assertTrue("Erased generic default forwarder must be synthetic bridge: $genericForwarder",
genericForwarder.access and Opcodes.ACC_SYNTHETIC != 0
&& genericForwarder.access and Opcodes.ACC_BRIDGE != 0)
val specializedForwarder = genericConsumer.methodsNamed("echo")
      .singleOrNull { it.descriptor == "(Ljava/lang/String;)Ljava/lang/String;" }
    assertNotNull(
      "Expected specialized generic default forwarder echo(String): String; actual=${genericConsumer.members}",
      specializedForwarder,
    )
    assertTrue("Specialized generic default forwarder must be public: $specializedForwarder",
      specializedForwarder!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Specialized generic default forwarder must not be abstract: $specializedForwarder",
      specializedForwarder.access and Opcodes.ACC_ABSTRACT == 0)
    assertTrue("Specialized generic default forwarder must not be synthetic: $specializedForwarder",
      specializedForwarder.access and Opcodes.ACC_SYNTHETIC == 0)

    val indirectGenericConsumer = surfaces.getValue("evidence/IndirectStringGenericDefaultConsumer")
    for ((descriptor, synthetic) in listOf(
      "(Ljava/lang/String;)Ljava/lang/String;" to false,
      "(Ljava/lang/Object;)Ljava/lang/Object;" to true,
    )) {
      val member = indirectGenericConsumer.methodsNamed("echo")
        .singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected indirect generic echo$descriptor; actual=${indirectGenericConsumer.members}", member)
      assertTrue("Indirect generic echo must be public: $member", member!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("Indirect generic echo synthetic flag mismatch: $member",
        (member.access and Opcodes.ACC_SYNTHETIC != 0) == synthetic)
      if (synthetic) {
        assertTrue("Indirect erased echo must be bridge: $member", member.access and Opcodes.ACC_BRIDGE != 0)
      }
    }

    val mutableGenericConsumer = surfaces.getValue("evidence/StringMutableGenericDefaultConsumer")
    for ((name, descriptor, synthetic) in listOf(
      Triple("getPayload", "()Ljava/lang/String;", false),
      Triple("setPayload", "(Ljava/lang/String;)V", false),
      Triple("getPayload", "()Ljava/lang/Object;", true),
      Triple("setPayload", "(Ljava/lang/Object;)V", true),
    )) {
      val member = mutableGenericConsumer.methodsNamed(name)
        .singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected generic default accessor $name$descriptor; actual=${mutableGenericConsumer.members}", member)
      assertTrue("Generic default accessor must be public: $member", member!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("Generic default accessor synthetic flag mismatch: $member",
        (member.access and Opcodes.ACC_SYNTHETIC != 0) == synthetic)
      if (synthetic) {
        assertTrue("Erased generic accessor must be bridge: $member", member.access and Opcodes.ACC_BRIDGE != 0)
      }
    }

    val boundedGenericConsumer = surfaces.getValue("evidence/BoundedStringGenericDefaultConsumer")
    val boundedSpecializedForwarder = boundedGenericConsumer.methodsNamed("echo")
      .singleOrNull { it.descriptor == "(Ljava/lang/String;)Ljava/lang/String;" }
    assertNotNull(
      "Expected bounded specialized generic default forwarder echo(String): String; actual=${boundedGenericConsumer.members}",
      boundedSpecializedForwarder,
    )
    assertTrue("Bounded specialized forwarder must be public: $boundedSpecializedForwarder",
      boundedSpecializedForwarder!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Bounded specialized forwarder must not be synthetic: $boundedSpecializedForwarder",
      boundedSpecializedForwarder.access and Opcodes.ACC_SYNTHETIC == 0)
    val boundedBridge = boundedGenericConsumer.methodsNamed("echo")
      .singleOrNull { it.descriptor == "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;" }
    assertNotNull(
      "Expected bounded erased bridge echo(CharSequence): CharSequence; actual=${boundedGenericConsumer.members}",
      boundedBridge,
    )
    assertTrue("Bounded erased forwarder must be synthetic bridge: $boundedBridge",
      boundedBridge!!.access and Opcodes.ACC_SYNTHETIC != 0
      && boundedBridge.access and Opcodes.ACC_BRIDGE != 0)

    val explicitConsumer = surfaces.getValue("evidence/ExplicitDefaultConsumer")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getTitle" to "()Ljava/lang/String;",
    )) {
      val implementation = explicitConsumer.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected ExplicitDefaultConsumer override $name$descriptor; actual=${explicitConsumer.members}", implementation)
      assertTrue("ExplicitDefaultConsumer override must be public: $implementation", implementation!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("ExplicitDefaultConsumer override must not be abstract: $implementation", implementation.access and Opcodes.ACC_ABSTRACT == 0)
      assertTrue("ExplicitDefaultConsumer override must not be synthetic: $implementation", implementation.access and Opcodes.ACC_SYNTHETIC == 0)
    }

    val abstractConsumer = surfaces.getValue("evidence/AbstractDefaultConsumer")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getTitle" to "()Ljava/lang/String;",
    )) {
      val forwarder = abstractConsumer.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected AbstractDefaultConsumer forwarding method $name$descriptor; actual=${abstractConsumer.members}", forwarder)
      assertTrue("AbstractDefaultConsumer forwarder must be public: $forwarder", forwarder!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("AbstractDefaultConsumer forwarder must not be abstract: $forwarder", forwarder.access and Opcodes.ACC_ABSTRACT == 0)
      assertTrue("AbstractDefaultConsumer forwarder must not be synthetic: $forwarder", forwarder.access and Opcodes.ACC_SYNTHETIC == 0)
    }

    val mutableContract = surfaces.getValue("evidence/MutableDefaultContract")
    for ((name, descriptor) in listOf(
      "getEnabled" to "()Z",
      "setEnabled" to "(Z)V",
    )) {
      val member = mutableContract.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected mutable default contract member $name$descriptor; actual=${mutableContract.members}", member)
      assertTrue("Mutable default contract member must be abstract: $member", member!!.access and Opcodes.ACC_ABSTRACT != 0)
    }

    val mutableConsumer = surfaces.getValue("evidence/MutableDefaultConsumer")
    for ((name, descriptor) in listOf(
      "getEnabled" to "()Z",
      "setEnabled" to "(Z)V",
    )) {
      val forwarder = mutableConsumer.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected MutableDefaultConsumer forwarding method $name$descriptor; actual=${mutableConsumer.members}", forwarder)
      assertTrue("MutableDefaultConsumer forwarder must be public: $forwarder", forwarder!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("MutableDefaultConsumer forwarder must not be abstract: $forwarder", forwarder.access and Opcodes.ACC_ABSTRACT == 0)
      assertTrue("MutableDefaultConsumer forwarder must not be synthetic: $forwarder", forwarder.access and Opcodes.ACC_SYNTHETIC == 0)
    }
  }
  @Test
  fun jvmDefaultAllMode_movesInterfaceBodiesToConcreteInterfaceMethodsWithoutImplementerForwarders() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      interface JvmDefaultContract {
        fun render(value: String): String = value
        var enabled: Boolean
          get() = true
          set(value) {}
      }
      class JvmDefaultConsumer : JvmDefaultContract
      """.trimIndent(),
      "JvmDefaultAll.kt",
      additionalArguments = listOf("-Xjvm-default=all"),
    ).associateBy { it.internalName }

    val contract = surfaces.getValue("evidence/JvmDefaultContract")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getEnabled" to "()Z",
      "setEnabled" to "(Z)V",
    )) {
      val member = contract.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected JVM-default interface member $name$descriptor; actual=${contract.members}", member)
      assertTrue("JVM-default interface member must be public: $member",
        member!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("JVM-default interface member must be concrete: $member",
        member.access and Opcodes.ACC_ABSTRACT == 0)
      assertTrue("JVM-default interface member must not be synthetic: $member",
        member.access and Opcodes.ACC_SYNTHETIC == 0)
    }
    assertTrue("all mode must not emit DefaultImpls; emitted=${surfaces.keys}",
      "evidence/JvmDefaultContract\$DefaultImpls" !in surfaces)

    val consumer = surfaces.getValue("evidence/JvmDefaultConsumer")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getEnabled" to "()Z",
      "setEnabled" to "(Z)V",
    )) {
      assertTrue("all mode implementer must inherit interface default rather than materialize $name$descriptor; actual=${consumer.members}",
        consumer.methodsNamed(name).none { it.descriptor == descriptor })
    }
  }

  @Test
  fun jvmDefaultAllCompatibilityMode_keepsConcreteInterfaceDefaultsAndCompatibilityHelpers() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      interface JvmDefaultCompatibilityContract {
        fun render(value: String): String = value
        var enabled: Boolean
          get() = true
          set(value) {}
      }
      class JvmDefaultCompatibilityConsumer : JvmDefaultCompatibilityContract
      """.trimIndent(),
      "JvmDefaultAllCompatibility.kt",
      additionalArguments = listOf("-Xjvm-default=all-compatibility"),
    ).associateBy { it.internalName }

    val contract = surfaces.getValue("evidence/JvmDefaultCompatibilityContract")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getEnabled" to "()Z",
      "setEnabled" to "(Z)V",
    )) {
      val member = contract.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected compatibility-mode interface member $name$descriptor; actual=${contract.members}", member)
      assertTrue("Compatibility-mode interface member must be public: $member",
        member!!.access and Opcodes.ACC_PUBLIC != 0)
      assertTrue("Compatibility-mode interface member must be concrete: $member",
        member.access and Opcodes.ACC_ABSTRACT == 0)
      assertTrue("Compatibility-mode interface member must not be synthetic: $member",
        member.access and Opcodes.ACC_SYNTHETIC == 0)
    }

    val helpers = surfaces.getValue("evidence/JvmDefaultCompatibilityContract\$DefaultImpls")
    for ((name, descriptor) in listOf(
      "render" to "(Levidence/JvmDefaultCompatibilityContract;Ljava/lang/String;)Ljava/lang/String;",
      "getEnabled" to "(Levidence/JvmDefaultCompatibilityContract;)Z",
      "setEnabled" to "(Levidence/JvmDefaultCompatibilityContract;Z)V",
    )) {
      val helper = helpers.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
      assertNotNull("Expected compatibility helper $name$descriptor; actual=${helpers.members}", helper)
      assertTrue("Compatibility helper must be public static: $helper",
        helper!!.access and Opcodes.ACC_PUBLIC != 0 && helper.access and Opcodes.ACC_STATIC != 0)
    }

    val consumer = surfaces.getValue("evidence/JvmDefaultCompatibilityConsumer")
    for ((name, descriptor) in listOf(
      "render" to "(Ljava/lang/String;)Ljava/lang/String;",
      "getEnabled" to "()Z",
      "setEnabled" to "(Z)V",
    )) {
      assertTrue("Compatibility-mode implementer must inherit interface default rather than materialize $name$descriptor; actual=${consumer.members}",
        consumer.methodsNamed(name).none { it.descriptor == descriptor })
    }
  }

  @Test
  fun genericTypeParameterBounds_recordJvmSignatures() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class SingleBound<T : CharSequence>(val value: T)
      class MultipleBounds<T> where T : CharSequence, T : Comparable<T>

      class BoundApi {
        fun <T : CharSequence> single(value: T): T = value
        fun <T> multiple(value: T): T where T : CharSequence, T : Comparable<T> = value
      }
      """.trimIndent(),
      "GenericBounds.kt",
    ).associateBy { it.internalName }

    val singleBound = surfaces.getValue("evidence/SingleBound")
    val multipleBounds = surfaces.getValue("evidence/MultipleBounds")
    val api = surfaces.getValue("evidence/BoundApi")
    val single = api.methodsNamed("single").single()
    val multiple = api.methodsNamed("multiple").single()

    assertTrue("Single-bound class Signature must be recorded: $singleBound", singleBound.signature != null)
    assertTrue("Multiple-bound class Signature must be recorded: $multipleBounds", multipleBounds.signature != null)
    assertTrue("Single-bound method Signature must be recorded: $single", single.signature != null)
    assertTrue("Multiple-bound method Signature must be recorded: $multiple", multiple.signature != null)
    assertTrue("Single bound must erase to CharSequence: ${singleBound.constructors()}",
      singleBound.constructors().single().descriptor.contains("Ljava/lang/CharSequence;"))
    assertTrue("Single bound Signature must retain CharSequence: ${singleBound.signature}",
      singleBound.signature!!.contains("<T::Ljava/lang/CharSequence;>"))
    assertTrue("Multiple bounds must retain CharSequence then Comparable: ${multipleBounds.signature}",
      multipleBounds.signature!!.contains("<T::Ljava/lang/CharSequence;:Ljava/lang/Comparable<-TT;>;>"))
    assertTrue("Single-bound method must retain CharSequence: ${single.signature}",
      single.signature!!.startsWith("<T::Ljava/lang/CharSequence;>"))
    assertTrue("Multiple-bound method must retain both bounds: ${multiple.signature}",
      multiple.signature!!.startsWith("<T::Ljava/lang/CharSequence;:Ljava/lang/Comparable<-TT;>;>"))
  }

  @Test
  fun genericVarianceAndWildcardProperties_recordFieldAndAccessorSignatures() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class WildcardPropertyApi {
        val plain: List<String> = emptyList()
        var declarationOut: MutableList<out CharSequence> = mutableListOf()
        var declarationIn: MutableList<in String> = mutableListOf()
        val star: List<*> = emptyList<Any?>()
        val forced: List<@JvmWildcard String> = emptyList()
        val suppressed: List<@JvmSuppressWildcards String> = emptyList()

        @JvmField
        val forcedField: List<@JvmWildcard String> = emptyList()
      }
      """.trimIndent(),
      "GenericWildcardProperties.kt",
    ).associateBy { it.internalName }

    val api = surfaces.getValue("evidence/WildcardPropertyApi")
    val plainField = api.fieldsNamed("plain").single()
    val plainGetter = api.methodsNamed("getPlain").single()
    val outField = api.fieldsNamed("declarationOut").single()
    val outGetter = api.methodsNamed("getDeclarationOut").single()
    val outSetter = api.methodsNamed("setDeclarationOut").single()
    val inField = api.fieldsNamed("declarationIn").single()
    val inGetter = api.methodsNamed("getDeclarationIn").single()
    val inSetter = api.methodsNamed("setDeclarationIn").single()
    val starField = api.fieldsNamed("star").single()
    val starGetter = api.methodsNamed("getStar").single()
    val forcedField = api.fieldsNamed("forced").single()
    val forcedGetter = api.methodsNamed("getForced").single()
    val suppressedField = api.fieldsNamed("suppressed").single()
    val suppressedGetter = api.methodsNamed("getSuppressed").single()
    val publicForcedField = api.fieldsNamed("forcedField").single()

    for (member in listOf(
      plainField, plainGetter, outField, outGetter, outSetter,
      inField, inGetter, inSetter, starField, starGetter,
      forcedField, forcedGetter, suppressedField, suppressedGetter, publicForcedField,
    )) {
      assertTrue("Property generic signature must be recorded: $member", member.signature != null)
    }
    assertTrue("Plain backing field must be invariant: ${plainField.signature}",
      plainField.signature!!.contains("<Ljava/lang/String;>"))
    assertTrue("Plain getter must be invariant: ${plainGetter.signature}",
      plainGetter.signature!!.contains("<Ljava/lang/String;>"))
    for (member in listOf(outField, outGetter, outSetter)) {
      assertTrue("Out property surface must retain extends wildcard: $member",
        member.signature!!.contains("<+Ljava/lang/CharSequence;>"))
    }
    for (member in listOf(inField, inGetter, inSetter)) {
      assertTrue("In property surface must retain super wildcard: $member",
        member.signature!!.contains("<-Ljava/lang/String;>"))
    }
    for (member in listOf(starField, starGetter)) {
      assertTrue("Star property surface must retain unbounded wildcard: $member",
        member.signature!!.contains("<*>"))
    }
    for (member in listOf(forcedField, forcedGetter, publicForcedField)) {
      assertTrue("@JvmWildcard property surface must retain extends wildcard: $member",
        member.signature!!.contains("<+Ljava/lang/String;>"))
    }
    for (member in listOf(suppressedField, suppressedGetter)) {
      assertTrue("@JvmSuppressWildcards property surface must remain invariant: $member",
        member.signature!!.contains("<Ljava/lang/String;>")
          && !member.signature.contains("<+Ljava/lang/String;>"))
    }
    assertTrue("@JvmField wildcard property must be public: $publicForcedField",
      publicForcedField.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("@JvmField wildcard property must not create an accessor: ${api.members}",
      api.methodsNamed("getForcedField").isEmpty())
  }

  @Test
  fun genericVarianceAndWildcardReturnTypes_recordJvmSignatures() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class WildcardReturnApi {
        fun plain(): List<String> = emptyList()
        fun declarationOut(): MutableList<out CharSequence> = mutableListOf()
        fun forced(): List<@JvmWildcard String> = emptyList()
        fun suppressed(): List<@JvmSuppressWildcards String> = emptyList()
      }
      """.trimIndent(),
      "GenericWildcardReturns.kt",
    ).associateBy { it.internalName }

    val api = surfaces.getValue("evidence/WildcardReturnApi")
    val plain = api.methodsNamed("plain").single()
    val declarationOut = api.methodsNamed("declarationOut").single()
    val forced = api.methodsNamed("forced").single()
    val suppressed = api.methodsNamed("suppressed").single()

    assertTrue("Plain return signature must be recorded: $plain", plain.signature != null)
    assertTrue("Out return signature must be recorded: $declarationOut", declarationOut.signature != null)
    assertTrue("@JvmWildcard return signature must be recorded: $forced", forced.signature != null)
    assertTrue("@JvmSuppressWildcards return signature must be recorded: $suppressed", suppressed.signature != null)
    assertTrue("Plain List<String> return must be invariant: ${plain.signature}",
      plain.signature!!.endsWith("Ljava/util/List<Ljava/lang/String;>;"))
    assertTrue("Out return must retain an extends wildcard: ${declarationOut.signature}",
      declarationOut.signature!!.endsWith("Ljava/util/List<+Ljava/lang/CharSequence;>;"))
    assertTrue("@JvmWildcard return must force an extends wildcard: ${forced.signature}",
      forced.signature!!.endsWith("Ljava/util/List<+Ljava/lang/String;>;"))
    assertTrue("@JvmSuppressWildcards return must be invariant: ${suppressed.signature}",
      suppressed.signature!!.endsWith("Ljava/util/List<Ljava/lang/String;>;"))
  }

  @Test
  fun genericVarianceAndWildcardAnnotations_recordJvmSignatures() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      class WildcardApi {
        fun plain(values: List<String>) {}
        fun declarationOut(values: MutableList<out CharSequence>) {}
        fun declarationIn(values: MutableList<in String>) {}
        fun star(values: List<*>) {}
        fun forced(values: List<@JvmWildcard String>) {}
        fun suppressed(values: List<@JvmSuppressWildcards String>) {}
      }
      """.trimIndent(),
      "GenericWildcards.kt",
    ).associateBy { it.internalName }

    val api = surfaces.getValue("evidence/WildcardApi")
    val plain = api.methodsNamed("plain").single()
    val declarationOut = api.methodsNamed("declarationOut").single()
    val declarationIn = api.methodsNamed("declarationIn").single()
    val star = api.methodsNamed("star").single()
    val forced = api.methodsNamed("forced").single()
    val suppressed = api.methodsNamed("suppressed").single()

    assertTrue("Generic method signature must be recorded: $plain", plain.signature != null)
    assertTrue("Declaration-site out signature must be recorded: $declarationOut", declarationOut.signature != null)
    assertTrue("Declaration-site in signature must be recorded: $declarationIn", declarationIn.signature != null)
    assertTrue("Star-projection signature must be recorded: $star", star.signature != null)
    assertTrue("@JvmWildcard signature must be recorded: $forced", forced.signature != null)
    assertTrue("@JvmSuppressWildcards signature must be recorded: $suppressed", suppressed.signature != null)
    assertTrue("Plain List<String> parameter must be invariant in Java signature: ${plain.signature}",
      plain.signature!!.contains("<Ljava/lang/String;>")
        && !plain.signature.contains("<+Ljava/lang/String;>"))
    assertTrue("Explicit out parameter must retain an extends wildcard: ${declarationOut.signature}",
      declarationOut.signature!!.contains("<+Ljava/lang/CharSequence;>"))
    assertTrue("Explicit in parameter must retain a super wildcard: ${declarationIn.signature}",
      declarationIn.signature!!.contains("<-Ljava/lang/String;>"))
    assertTrue("Star projection must retain an unbounded wildcard: ${star.signature}",
      star.signature!!.contains("<*>"))
    assertTrue("@JvmWildcard must force an extends wildcard: ${forced.signature}",
      forced.signature!!.contains("<+Ljava/lang/String;>"))
    assertTrue("@JvmSuppressWildcards must remove the extends wildcard: ${suppressed.signature}",
      suppressed.signature!!.contains("<Ljava/lang/String;>")
        && !suppressed.signature.contains("<+Ljava/lang/String;>"))
  }

  private fun assertSyntheticBridge(


    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    name: String,
    descriptor: String,
  ) {
    val bridge = surface.members.singleOrNull {
      it.name == name && it.descriptor == descriptor
    }
    assertNotNull(
      "Expected synthetic bridge $name$descriptor in ${surface.internalName}; actual=${surface.members}",
      bridge,
    )
    assertTrue("Expected bridge to be public: $bridge", bridge!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected bridge to be synthetic: $bridge", bridge.access and Opcodes.ACC_SYNTHETIC != 0)
    assertTrue("Expected bridge flag: $bridge", bridge.access and Opcodes.ACC_BRIDGE != 0)
  }

  private fun assertMangledAccessor(

    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    namePrefix: String,
    descriptor: String,
  ) {
    val matches = surface.members.filter {
      it.name.startsWith(namePrefix) && it.descriptor == descriptor
    }
    assertTrue(
      "Expected one mangled accessor $namePrefix*$descriptor in ${surface.internalName}; actual=${surface.members}",
      matches.size == 1,
    )
    val accessor = matches.single()
    assertTrue("Expected mangled accessor to be public: $accessor",
      accessor.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected mangled accessor not to be synthetic: $accessor",
      accessor.access and Opcodes.ACC_SYNTHETIC == 0)
    val plainName = namePrefix.removeSuffix("-")
    assertTrue(
      "Plain accessor $plainName must not coexist with mangled value-class accessor: ${surface.members}",
      surface.methodsNamed(plainName).isEmpty(),
    )
  }

  private fun assertStaticPlainAccessor(
    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    name: String,
    descriptor: String,
  ) {
    val accessor = surface.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected static accessor $name$descriptor in ${surface.internalName}: ${surface.members}", accessor)
    assertTrue("Expected static accessor to be public: $accessor",
      accessor!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected static accessor to be static: $accessor",
      accessor.access and Opcodes.ACC_STATIC != 0)
    assertTrue("Expected static accessor not to be synthetic: $accessor",
      accessor.access and Opcodes.ACC_SYNTHETIC == 0)
  }

  private fun assertSyntheticAccessor(
    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    name: String,
    descriptor: String,
  ) {
    val accessor = surface.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected synthetic accessor $name$descriptor in ${surface.internalName}: ${surface.members}", accessor)
    assertTrue("Expected synthetic accessor to be public: $accessor",
      accessor!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected synthetic accessor flag: $accessor",
      accessor.access and Opcodes.ACC_SYNTHETIC != 0)
  }

  private fun assertPlainAccessor(
    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    name: String,
    descriptor: String,
  ) {
    val accessor = surface.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected plain accessor $name$descriptor in ${surface.internalName}: ${surface.members}", accessor)
    assertTrue("Expected plain accessor to be public: $accessor",
      accessor!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected plain accessor not to be synthetic: $accessor",
      accessor.access and Opcodes.ACC_SYNTHETIC == 0)
  }

  private fun assertNoPublicNonSyntheticConstructor(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    owner: String,
  ) {
    val javaVisible = constructors.filter {
      it.access and Opcodes.ACC_PUBLIC != 0 && it.access and Opcodes.ACC_SYNTHETIC == 0
    }
    assertTrue(
      "Expected no public non-synthetic Java constructor for $owner; actual=$constructors",
      javaVisible.isEmpty(),
    )
  }

  private fun assertPrivateConstructorDescriptor(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    descriptor: String,
  ) {
    val constructor = constructors.singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected constructor $descriptor: $constructors", constructor)
    assertTrue(
      "Expected constructor $descriptor to be private: $constructor",
      constructor!!.access and Opcodes.ACC_PRIVATE != 0,
    )
  }

  private fun assertPublicNonSyntheticConstructor(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    descriptor: String,
  ) {
    val constructor = constructors.singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected constructor $descriptor: $constructors", constructor)
    assertTrue(
      "Expected constructor $descriptor to be public: $constructor",
      constructor!!.access and Opcodes.ACC_PUBLIC != 0,
    )
    assertTrue(
      "Expected constructor $descriptor not to be synthetic: $constructor",
      constructor.access and Opcodes.ACC_SYNTHETIC == 0,
    )
  }

  private fun assertPrivateRepresentationAndPublicSyntheticMarker(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    representationDescriptor: String,
    markerDescriptor: String,
  ) {
    val representation = constructors.singleOrNull { it.descriptor == representationDescriptor }
    assertNotNull(
      "Expected representation constructor $representationDescriptor: $constructors",
      representation,
    )
    assertTrue(
      "Expected representation constructor to be private: $representation",
      representation!!.access and Opcodes.ACC_PRIVATE != 0,
    )

    val marker = constructors.singleOrNull { it.descriptor == markerDescriptor }
    assertNotNull("Expected marker constructor $markerDescriptor: $constructors", marker)
    assertTrue(
      "Expected marker constructor to be public: $marker",
      marker!!.access and Opcodes.ACC_PUBLIC != 0,
    )
    assertTrue(
      "Expected marker constructor to be synthetic: $marker",
      marker.access and Opcodes.ACC_SYNTHETIC != 0,
    )
  }

  private fun assertConstructorDescriptor(
    surfaces: Map<String, KotlinCompilerJvmAbiProbe.ClassSurface>,
    owner: String,
    descriptor: String,
  ) {
    val surface = surfaces[owner]
    assertNotNull("Compiler probe did not emit $owner; emitted=${surfaces.keys}", surface)
    val constructors = surface!!.constructors()
    assertTrue(
      "Expected constructor ABI $descriptor for $owner; actual=$constructors",
      constructors.any { it.descriptor == descriptor },
    )
  }
}