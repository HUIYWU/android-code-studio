package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TreeSitter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.util.JavacTask
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Direct regression coverage for conservative Java ABI element to Kotlin source navigation. */
class KotlinJvmSourceNavigatorTest {

  @Test
  fun facadeNavigation_selectsGenericAliasOverloadByAttributedParameterType() {
    val kotlinSource =
      """
      package navigation

      typealias NavigationListAlias<T> = List<T>

      fun navigateAlias(value: NavigationListAlias<String>): Unit {}
      fun navigateAlias(value: NavigationListAlias<Int>): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class NavigationApiKt {
        abstract void navigateAlias(java.util.List<java.lang.Integer> value);
      }
      """.trimIndent(),
      "navigation.NavigationApiKt",
      "navigateAlias",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/NavigationApi.kt"), kotlinSource, method)

    assertNotNull(location)
  }

  @Test
  fun facadeNavigation_refusesAmbiguousUnsupportedKotlinTypes() {
    val kotlinSource =
      """
      package navigation

      fun ambiguous(value: (Int) -> Unit): Unit {}
      fun ambiguous(value: (String) -> Unit): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class AmbiguousApiKt {
        abstract void ambiguous(java.lang.Object value);
      }
      """.trimIndent(),
      "navigation.AmbiguousApiKt",
      "ambiguous",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/AmbiguousApi.kt"), kotlinSource, method)

    assertNull(location)
  }

  @Test
  fun facadeNavigation_refusesConflictingPropertyAccessorJvmSurface() {
    val kotlinSource =
      """
      package navigation

      @get:JvmName("readShared")
      val first: String = "first"
      fun readShared(): String = "function"
      @get:JvmName("readShared")
      val second: Int = 2
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class AccessorConflictApiKt {
        abstract java.lang.String readShared();
      }
      """.trimIndent(),
      "navigation.AccessorConflictApiKt",
      "readShared",
    )

    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/AccessorConflictApi.kt"), kotlinSource, method))
  }

  @Test
  fun facadeNavigation_resolvesJvmNamedExtensionAccessorAndRejectsSyntheticGetter() {
    val kotlinSource =
      """
      package navigation

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
    val javaSource =
      """
      package navigation;
      abstract class NamedExtensionPropertyNavigationKt {
        static java.lang.String readLabel(java.lang.String receiver) { return receiver; }
        static void writeLabel(java.lang.String receiver, java.lang.String value) {}
        static java.lang.String getHidden(java.lang.String receiver) { return receiver; }
        static void setHidden(java.lang.String receiver, java.lang.String value) {}
      }
      """.trimIndent()
    val getter = compileMethod(javaSource, "navigation.NamedExtensionPropertyNavigationKt", "readLabel")
    val setter = compileMethod(javaSource, "navigation.NamedExtensionPropertyNavigationKt", "writeLabel")
    val syntheticGetter = compileMethod(
      javaSource, "navigation.NamedExtensionPropertyNavigationKt", "getHidden")
    val visibleSetter = compileMethod(
      javaSource, "navigation.NamedExtensionPropertyNavigationKt", "setHidden")
    val file = Paths.get("/navigation/NamedExtensionPropertyNavigation.kt")

    assertEquals("label", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, getter)!!))
    assertEquals("label", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, setter)!!))
    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, syntheticGetter))
    assertEquals("hidden", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, visibleSetter)!!))
  }

  @Test
  fun facadeNavigation_matchesOnlyProvenValueClassExtensionAccessorSurfaces() {
    val kotlinSource =
      """
      package navigation

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
    val javaSource =
      """
      package navigation;
      abstract class ValueClassExtensionPropertiesKt {
        static java.lang.String getLabel(java.lang.String receiver) { return receiver; }
        static void setLabel(java.lang.String receiver, java.lang.String value) {}
        static java.lang.String getId(java.lang.String receiver) { return receiver; }
        static void setId(java.lang.String receiver, java.lang.String value) {}
        static java.lang.String getOther(java.lang.String receiver) { return receiver; }
        static void setOther(java.lang.String receiver, java.lang.String value) {}
        static java.lang.String readLabel(java.lang.String receiver) { return receiver; }
        static void writeLabel(java.lang.String receiver, java.lang.String value) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/ValueClassExtensionProperties.kt")

    for (name in listOf("getLabel", "setLabel", "setId", "getOther", "setOther")) {
      val accessor = compileMethod(javaSource, "navigation.ValueClassExtensionPropertiesKt", name)
      assertNull("Mangled value-class extension accessor must not match plain Java name: $name",
        KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, accessor))
    }
    val getter = compileMethod(javaSource, "navigation.ValueClassExtensionPropertiesKt", "getId")
    assertEquals("id", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, getter)!!))
    for (name in listOf("readLabel", "writeLabel")) {
      val accessor = compileMethod(javaSource, "navigation.ValueClassExtensionPropertiesKt", name)
      assertEquals("namedLabel", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, accessor)!!))
    }
  }

  @Test
  fun facadeNavigation_resolvesExtensionPropertyAccessorsByReceiverSignature() {
    val kotlinSource =
      """
      package navigation

      var String.label: String
        get() = this
        set(value) {}
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      abstract class ExtensionPropertyNavigationKt {
        static java.lang.String getLabel(java.lang.String receiver) { return receiver; }
        static void setLabel(java.lang.String receiver, java.lang.String value) {}
      }
      """.trimIndent()
    val getter = compileMethod(javaSource, "navigation.ExtensionPropertyNavigationKt", "getLabel")
    val setter = compileMethod(javaSource, "navigation.ExtensionPropertyNavigationKt", "setLabel")
    val missingReceiver = compileMethod(
      """
      package navigation;
      abstract class MissingReceiverApiKt {
        static java.lang.String getLabel() { return null; }
      }
      """.trimIndent(),
      "navigation.MissingReceiverApiKt",
      "getLabel",
    )
    val file = Paths.get("/navigation/ExtensionPropertyNavigation.kt")

    assertEquals("label", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, getter)!!))
    assertEquals("label", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, setter)!!))
    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, missingReceiver))
  }

  @Test
  fun facadeNavigation_resolvesJvmNamedFunction() {
    val kotlinSource =
      """
      package navigation

      @JvmName("loadNavigationValue")
      fun loadValue(id: Int): String = id.toString()
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class NamedNavigationApiKt {
        abstract java.lang.String loadNavigationValue(int id);
      }
      """.trimIndent(),
      "navigation.NamedNavigationApiKt",
      "loadNavigationValue",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/NamedNavigationApi.kt"), kotlinSource, method)

    assertNotNull(location)
  }

  @Test
  fun facadeNavigation_resolvesJvmNamedPropertyAccessors() {
    val kotlinSource =
      """
      package navigation

      @get:JvmName("readNavigationMode")
      @set:JvmName("writeNavigationMode")
      var navigationMode: String = "default"
      """.trimIndent()
    val getter = compileMethod(
      """
      package navigation;
      abstract class PropertyNavigationApiKt {
        abstract java.lang.String readNavigationMode();
        abstract void writeNavigationMode(java.lang.String value);
      }
      """.trimIndent(),
      "navigation.PropertyNavigationApiKt",
      "readNavigationMode",
    )
    val setter = compileMethod(
      """
      package navigation;
      abstract class PropertyNavigationApiKt {
        abstract java.lang.String readNavigationMode();
        abstract void writeNavigationMode(java.lang.String value);
      }
      """.trimIndent(),
      "navigation.PropertyNavigationApiKt",
      "writeNavigationMode",
    )

    assertNotNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/PropertyNavigationApi.kt"), kotlinSource, getter))
    assertNotNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/PropertyNavigationApi.kt"), kotlinSource, setter))
  }

  @Test
  fun facadeNavigation_rejectsJvmSyntheticDeclarationAndAccessor() {
    val kotlinSource =
      """
      package navigation

      @JvmSynthetic
      fun hiddenNavigation(): String = "hidden"

      @get:JvmSynthetic
      var guardedNavigation: String = "guarded"
      """.trimIndent()
    val hiddenFunction = compileMethod(
      """
      package navigation;
      abstract class SyntheticNavigationApiKt {
        abstract java.lang.String hiddenNavigation();
      }
      """.trimIndent(),
      "navigation.SyntheticNavigationApiKt",
      "hiddenNavigation",
    )
    val hiddenGetter = compileMethod(
      """
      package navigation;
      abstract class SyntheticNavigationApiKt {
        abstract java.lang.String getGuardedNavigation();
      }
      """.trimIndent(),
      "navigation.SyntheticNavigationApiKt",
      "getGuardedNavigation",
    )

    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/SyntheticNavigationApi.kt"), kotlinSource, hiddenFunction))
    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/SyntheticNavigationApi.kt"), kotlinSource, hiddenGetter))
  }

  @Test
  fun facadeNavigation_resolvesJvmNamedBooleanAccessorAndRejectsSyntheticGetter() {
    val kotlinSource =
      """
      package navigation

      @get:JvmName("readReady")
      @set:JvmName("writeReady")
      var isReady: Boolean = false

      @get:JvmSynthetic
      var isInternal: Boolean = false
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      abstract class BooleanNavigationApiKt {
        abstract boolean readReady();
        abstract void writeReady(boolean value);
        abstract boolean isInternal();
        abstract void setInternal(boolean value);
      }
      """.trimIndent()
    val getter = compileMethod(javaSource, "navigation.BooleanNavigationApiKt", "readReady")
    val setter = compileMethod(javaSource, "navigation.BooleanNavigationApiKt", "writeReady")
    val syntheticGetter = compileMethod(javaSource, "navigation.BooleanNavigationApiKt", "isInternal")
    val visibleSetter = compileMethod(javaSource, "navigation.BooleanNavigationApiKt", "setInternal")
    val file = Paths.get("/navigation/BooleanNavigationApi.kt")

    assertNotNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, getter))
    assertNotNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, setter))
    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, syntheticGetter))
    assertNotNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, visibleSetter))
  }

  @Test
  fun typeNavigation_resolvesBoundedGenericInterfaceExtensionPropertyWithoutBoundGuessing() {
    val kotlinSource =
      """
      package navigation

      interface BoundedExtensionPropertyNavigation<T : CharSequence> {
        var T.payload: T
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      interface BoundedExtensionPropertyNavigation<T extends java.lang.CharSequence> {
        T getPayload(T receiver);
        void setPayload(T receiver, T value);
      }
      """.trimIndent()
    val erasedSource =
      """
      package navigation;
      interface BoundErasedExtensionPropertyNavigation {
        java.lang.CharSequence getPayload(java.lang.CharSequence receiver);
        void setPayload(java.lang.CharSequence receiver, java.lang.CharSequence value);
      }
      """.trimIndent()
    val wrongShapeSource =
      """
      package navigation;
      interface BoundedExtensionPropertyNavigation<T extends java.lang.CharSequence> {
        T getPayload();
        void setPayload(T value);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/BoundedExtensionPropertyNavigation.kt")
    val getter = compileMethod(javaSource, "navigation.BoundedExtensionPropertyNavigation", "getPayload")
    val setter = compileMethod(javaSource, "navigation.BoundedExtensionPropertyNavigation", "setPayload")
    val erasedGetter = compileMethod(
      erasedSource, "navigation.BoundErasedExtensionPropertyNavigation", "getPayload")
    val erasedSetter = compileMethod(
      erasedSource, "navigation.BoundErasedExtensionPropertyNavigation", "setPayload")
    val missingReceiverGetter = compileMethod(
      wrongShapeSource, "navigation.BoundedExtensionPropertyNavigation", "getPayload")
    val missingReceiverSetter = compileMethod(
      wrongShapeSource, "navigation.BoundedExtensionPropertyNavigation", "setPayload")

    assertEquals("payload", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, getter)!!))
    assertEquals("payload", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, setter)!!))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, erasedGetter))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, erasedSetter))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, missingReceiverGetter))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, missingReceiverSetter))
  }

  @Test
  fun typeNavigation_resolvesGenericInterfaceExtensionPropertyAccessorsWithoutErasedObjectGuessing() {
    val kotlinSource =
      """
      package navigation

      interface GenericExtensionPropertyNavigation<T> {
        var T.payload: T
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      interface GenericExtensionPropertyNavigation<T> {
        T getPayload(T receiver);
        void setPayload(T receiver, T value);
      }
      """.trimIndent()
    val erasedSource =
      """
      package navigation;
      interface ErasedExtensionPropertyNavigation {
        java.lang.Object getPayload(java.lang.Object receiver);
        void setPayload(java.lang.Object receiver, java.lang.Object value);
      }
      """.trimIndent()
    val wrongShapeSource =
      """
      package navigation;
      interface GenericExtensionPropertyNavigation<T> {
        T getPayload();
        void setPayload(T value);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/GenericExtensionPropertyNavigation.kt")
    val getter = compileMethod(javaSource, "navigation.GenericExtensionPropertyNavigation", "getPayload")
    val setter = compileMethod(javaSource, "navigation.GenericExtensionPropertyNavigation", "setPayload")
    val erasedGetter = compileMethod(
      erasedSource, "navigation.ErasedExtensionPropertyNavigation", "getPayload")
    val erasedSetter = compileMethod(
      erasedSource, "navigation.ErasedExtensionPropertyNavigation", "setPayload")
    val missingReceiverGetter = compileMethod(
      wrongShapeSource, "navigation.GenericExtensionPropertyNavigation", "getPayload")
    val missingReceiverSetter = compileMethod(
      wrongShapeSource, "navigation.GenericExtensionPropertyNavigation", "setPayload")

    assertEquals("payload", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, getter)!!))
    assertEquals("payload", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, setter)!!))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, erasedGetter))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, erasedSetter))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, missingReceiverGetter))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, missingReceiverSetter))
  }

  @Test
  fun typeNavigation_rejectsPlainInterfaceValueClassExtensionAccessorSurfaces() {
    val kotlinSource =
      """
      package navigation

      @JvmInline
      value class UserId(val raw: String)

      interface ValueClassExtensionNavigationContract {
        var UserId.label: String
        var String.id: UserId
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      interface ValueClassExtensionNavigationContract {
        java.lang.String getLabel(java.lang.String receiver);
        void setLabel(java.lang.String receiver, java.lang.String value);
        java.lang.String getId(java.lang.String receiver);
        void setId(java.lang.String receiver, java.lang.String value);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/ValueClassExtensionNavigationContract.kt")

    for (name in listOf("getLabel", "setLabel", "getId", "setId")) {
      val accessor = compileMethod(
        javaSource, "navigation.ValueClassExtensionNavigationContract", name)
      assertNull("Mangled interface value-class accessor must not match plain Java name: $name",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, accessor))
    }
  }

  @Test
  fun typeNavigation_resolvesInterfaceExtensionPropertyAccessorsByReceiverSignature() {
    val kotlinSource =
      """
      package navigation

      interface ExtensionPropertyNavigationContract {
        var String.label: String
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      interface ExtensionPropertyNavigationContract {
        java.lang.String getLabel(java.lang.String receiver);
        void setLabel(java.lang.String receiver, java.lang.String value);
      }
      """.trimIndent()
    val missingReceiverSource =
      """
      package navigation;
      interface MissingReceiverExtensionPropertyContract {
        java.lang.String getLabel();
      }
      """.trimIndent()
    val file = Paths.get("/navigation/ExtensionPropertyNavigationContract.kt")
    val getter = compileMethod(javaSource, "navigation.ExtensionPropertyNavigationContract", "getLabel")
    val setter = compileMethod(javaSource, "navigation.ExtensionPropertyNavigationContract", "setLabel")
    val missingReceiver = compileMethod(
      missingReceiverSource, "navigation.MissingReceiverExtensionPropertyContract", "getLabel")

    assertEquals("label", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, getter)!!))
    assertEquals("label", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, setter)!!))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, missingReceiver))
  }

  @Test
  fun typeNavigation_resolvesInterfacePropertyAccessorsAndRejectsSyntheticGetter() {
    val kotlinSource =
      """
      package navigation

      interface PropertyNavigationContract {
        val title: String
        var isReady: Boolean
        @get:JvmName("readMode")
        @set:JvmName("writeMode")
        var mode: String
        @get:JvmSynthetic
        var internal: String
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      interface PropertyNavigationContract {
        java.lang.String getTitle();
        boolean isReady();
        void setReady(boolean value);
        java.lang.String readMode();
        void writeMode(java.lang.String value);
        java.lang.String getInternal();
        void setInternal(java.lang.String value);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/PropertyNavigationContract.kt")

    for (name in listOf("getTitle", "isReady", "setReady", "readMode", "writeMode", "setInternal")) {
      val method = compileMethod(javaSource, "navigation.PropertyNavigationContract", name)
      assertNotNull("Interface accessor did not navigate: $name",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, method))
    }
    val syntheticGetter = compileMethod(javaSource, "navigation.PropertyNavigationContract", "getInternal")
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, syntheticGetter))
  }

  @Test
  fun facadeNavigation_resolvesWildcardAndSuppressWildcardTypeArguments() {
    val kotlinSource =
      """
      package navigation

      fun plain(values: List<String>): String = values.first()
      fun declarationOut(values: MutableList<out CharSequence>): String = values.first().toString()
      fun declarationIn(values: MutableList<in String>): String = values.size.toString()
      fun star(values: List<*>): String = values.size.toString()
      fun forced(values: List<@JvmWildcard String>): String = values.first()
      fun suppressed(values: List<@JvmSuppressWildcards String>): String = values.first()
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      abstract class WildcardNavigationKt {
        abstract String plain(java.util.List<String> values);
        abstract String declarationOut(java.util.List<? extends CharSequence> values);
        abstract String declarationIn(java.util.List<? super String> values);
        abstract String star(java.util.List<?> values);
        abstract String forced(java.util.List<? extends String> values);
        abstract String suppressed(java.util.List<String> values);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/WildcardNavigation.kt")

    for (name in listOf(
      "plain", "declarationOut", "declarationIn", "star", "forced", "suppressed",
    )) {
      val method = compileMethod(javaSource, "navigation.WildcardNavigationKt", name)
      val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, method)
      assertNotNull("Wildcard surface did not navigate: $name (${method.asType()})", location)
      assertEquals(name, sourceTextAt(kotlinSource, location!!))
    }
  }

  @Test
  fun facadeNavigation_resolvesWildcardAndSuppressWildcardReturnTypes() {
    val kotlinSource =
      """
      package navigation

      fun plain(values: List<String>): List<String> = values
      fun declarationOut(values: MutableList<out CharSequence>): MutableList<out CharSequence> = values
      fun forced(values: List<@JvmWildcard String>): List<@JvmWildcard String> = values
      fun suppressed(values: List<@JvmSuppressWildcards String>): List<@JvmSuppressWildcards String> = values
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      abstract class WildcardReturnNavigationKt {
        abstract java.util.List<String> plain(java.util.List<String> values);
        abstract java.util.List<? extends CharSequence> declarationOut(
            java.util.List<? extends CharSequence> values);
        abstract java.util.List<? extends String> forced(java.util.List<? extends String> values);
        abstract java.util.List<String> suppressed(java.util.List<String> values);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/WildcardReturnNavigation.kt")

    for (name in listOf("plain", "declarationOut", "forced", "suppressed")) {
      val method = compileMethod(javaSource, "navigation.WildcardReturnNavigationKt", name)
      val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, method)
      assertNotNull("Wildcard return surface did not navigate: $name (${method.asType()})", location)
      assertEquals(name, sourceTextAt(kotlinSource, location!!))
    }
  }

  @Test
  fun typeNavigation_resolvesWildcardPropertyAccessorSurfaces() {
    val kotlinSource =
      """
      package navigation

      class WildcardPropertyNavigation {
        val plain: List<String> = emptyList()
        var declarationOut: MutableList<out CharSequence> = mutableListOf()
        var declarationIn: MutableList<in String> = mutableListOf()
        val star: List<*> = emptyList()
        val forced: List<@JvmWildcard String> = emptyList()
        val suppressed: List<@JvmSuppressWildcards String> = emptyList()
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      abstract class WildcardPropertyNavigation {
        abstract java.util.List<String> getPlain();
        abstract java.util.List<? extends CharSequence> getDeclarationOut();
        abstract void setDeclarationOut(java.util.List<? extends CharSequence> value);
        abstract java.util.List<? super String> getDeclarationIn();
        abstract void setDeclarationIn(java.util.List<? super String> value);
        abstract java.util.List<?> getStar();
        abstract java.util.List<? extends String> getForced();
        abstract java.util.List<String> getSuppressed();
      }
      """.trimIndent()
    val file = Paths.get("/navigation/WildcardPropertyNavigation.kt")

    for ((name, expected) in listOf(
      "getPlain" to "plain",
      "getDeclarationOut" to "declarationOut",
      "setDeclarationOut" to "declarationOut",
      "getDeclarationIn" to "declarationIn",
      "setDeclarationIn" to "declarationIn",
      "getStar" to "star",
      "getForced" to "forced",
      "getSuppressed" to "suppressed",
    )) {
      val accessor = compileMethod(javaSource, "navigation.WildcardPropertyNavigation", name)
      val location = KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, accessor)
      assertNotNull("Wildcard property accessor did not navigate: $name (${accessor.asType()})", location)
      assertEquals(expected, sourceTextAt(kotlinSource, location!!))
    }
  }

  @Test
  fun typeNavigation_resolvesSingleBoundGenericClassConstructorAndMethod() {
    val kotlinSource =
      """
      package navigation

      class SingleBound<T : CharSequence>(val value: T)

      class BoundApi {
        fun <T : CharSequence> single(value: T): T = value
      }
      """.trimIndent()
    val constructor = compileConstructor(
      """
      package navigation;
      class SingleBound<T extends CharSequence> {
        SingleBound(T value) {}
        T getValue() { return null; }
      }
      """.trimIndent(),
      "navigation.SingleBound",
      "T",
    )
    val method = compileMethod(
      """
      package navigation;
      class BoundApi {
        <T extends CharSequence> T single(T value) { return value; }
      }
      """.trimIndent(),
      "navigation.BoundApi",
      "single",
    )
    val classFile = Paths.get("/navigation/SingleBound.kt")
    val apiFile = Paths.get("/navigation/BoundApi.kt")

    assertEquals("SingleBound", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(classFile, kotlinSource, constructor)!!))
    assertEquals("single", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(apiFile, kotlinSource, method)!!))
  }

  @Test
  fun typeNavigation_resolvesMultipleWhereBoundGenericClassConstructorAndMethod() {
    val kotlinSource =
      """
      package navigation

      class MultipleBounds<T>(val value: T)
        where T : CharSequence, T : Comparable<T>

      class BoundApi {
        fun <T> multiple(value: T): T where T : CharSequence, T : Comparable<T> = value
      }
      """.trimIndent()
    val constructor = compileConstructor(
      """
      package navigation;
      class MultipleBounds<T extends CharSequence & Comparable<? super T>> {
        MultipleBounds(T value) {}
        T getValue() { return null; }
      }
      """.trimIndent(),
      "navigation.MultipleBounds",
      "T",
    )
    val method = compileMethod(
      """
      package navigation;
      class BoundApi {
        <T extends CharSequence & Comparable<? super T>> T multiple(T value) { return value; }
      }
      """.trimIndent(),
      "navigation.BoundApi",
      "multiple",
    )
    val classFile = Paths.get("/navigation/MultipleBounds.kt")
    val apiFile = Paths.get("/navigation/BoundApi.kt")

    assertEquals("MultipleBounds", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(classFile, kotlinSource, constructor)!!))
    assertEquals("multiple", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(apiFile, kotlinSource, method)!!))
  }

  @Test
  fun typeNavigation_resolvesInheritedDefaultInterfaceForwardersToContractDeclaration() {
    val kotlinSource =
      """
      package navigation

      interface DefaultContract {
        fun render(value: String): String = value
        val title: String
          get() = "title"
      }

      class DefaultConsumer : DefaultContract
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class DefaultConsumer implements DefaultContract {
        public String render(String value) { return value; }
        public String getTitle() { return "title"; }
      }
      interface DefaultContract {
        String render(String value);
        String getTitle();
      }
      """.trimIndent()
    val file = Paths.get("/navigation/DefaultInterfaceForwarders.kt")

    for ((name, expectedSourceName) in listOf("render" to "render", "getTitle" to "title")) {
      val method = compileMethod(javaSource, "navigation.DefaultConsumer", name)
      assertEquals(expectedSourceName, sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, method)!!,
      ))
    }
  }

  @Test
  fun typeNavigation_resolvesInheritedGenericDefaultForwardersAndRejectsErasedBridges() {
    val kotlinSource =
      """
      package navigation

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
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class StringGenericDefaultConsumer implements GenericDefaultContract<String> {
        public String echo(String value) { return value; }
      }
      class IndirectStringGenericDefaultConsumer implements DerivedGenericDefaultContract<String> {
        public String echo(String value) { return value; }
      }
      class StringMutableGenericDefaultConsumer implements MutableGenericDefaultContract<String> {
        public String getPayload() { return null; }
        public void setPayload(String value) {}
      }
      interface GenericDefaultContract<T> { T echo(T value); }
      interface DerivedGenericDefaultContract<T> extends GenericDefaultContract<T> {}
      interface MutableGenericDefaultContract<T> { T getPayload(); void setPayload(T value); }
      class BoundedStringGenericDefaultConsumer implements BoundedGenericDefaultContract<String> {
        public String echo(String value) { return value; }
      }
      interface BoundedGenericDefaultContract<T extends CharSequence> { T echo(T value); }
      """.trimIndent()
    val bridgeSource =
      """
      package navigation;
      class StringGenericDefaultConsumer {
        public Object echo(Object value) { return value; }
      }
      class BoundedStringGenericDefaultConsumer {
        public CharSequence echo(CharSequence value) { return value; }
      }
      class StringMutableGenericDefaultConsumer {
        public Object getPayload() { return null; }
        public void setPayload(Object value) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/GenericDefaultInterfaceForwarders.kt")

    for ((owner, name, expected) in listOf(
      Triple("navigation.StringGenericDefaultConsumer", "echo", "echo"),
      Triple("navigation.IndirectStringGenericDefaultConsumer", "echo", "echo"),
      Triple("navigation.StringMutableGenericDefaultConsumer", "getPayload", "payload"),
      Triple("navigation.StringMutableGenericDefaultConsumer", "setPayload", "payload"),
      Triple("navigation.BoundedStringGenericDefaultConsumer", "echo", "echo"),
    )) {
      val method = compileMethod(javaSource, owner, name)
      assertEquals(expected, sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, method)!!))
    }
    for ((owner, name) in listOf(
      "navigation.StringGenericDefaultConsumer" to "echo",
      "navigation.BoundedStringGenericDefaultConsumer" to "echo",
      "navigation.StringMutableGenericDefaultConsumer" to "getPayload",
      "navigation.StringMutableGenericDefaultConsumer" to "setPayload",
    )) {
      val bridge = compileMethod(bridgeSource, owner, name)
      assertNull("Erased generic default bridge must not navigate: $owner.$name",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, bridge))
    }
  }

  @Test
  fun typeNavigation_resolvesConcreteGenericOverridesAndRejectsBridgeShapedErasure() {
    val kotlinSource =
      """
      package navigation

      interface GenericContract<T> {
        fun accept(value: T): T
        var payload: T
      }

      class StringContract : GenericContract<String> {
        override fun accept(value: String): String = value
        override var payload: String = "payload"
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class StringContract implements GenericContract<String> {
        public String accept(String value) { return value; }
        public String getPayload() { return "payload"; }
        public void setPayload(String value) {}
      }
      interface GenericContract<T> {
        T accept(T value);
        T getPayload();
        void setPayload(T value);
      }
      """.trimIndent()
    val bridgeShapedSource =
      """
      package navigation;
      class StringContract {
        public Object accept(Object value) { return value; }
        public Object getPayload() { return null; }
        public void setPayload(Object value) {}
      }
      interface GenericContract<T> {
        T accept(T value);
        T getPayload();
        void setPayload(T value);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/GenericOverrideBridges.kt")

    for (name in listOf("accept", "getPayload", "setPayload")) {
      val method = compileMethod(javaSource, "navigation.StringContract", name)
      val expectedSourceName = if (name == "accept") "accept" else "payload"
      assertEquals(expectedSourceName, sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, method)!!,
      ))
    }

    for (name in listOf("accept", "getPayload", "setPayload")) {
      val bridge = compileMethod(bridgeShapedSource, "navigation.StringContract", name)
      assertNull(
        "Erased bridge-shaped $name must not navigate to a String override",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, bridge),
      )
    }
  }

  @Test
  fun typeNavigation_resolvesMultilevelGenericLeafAndRejectsTerminalErasedBridgeShapes() {
    val kotlinSource =
      """
      package navigation

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
    val javaSource =
      """
      package navigation;
      class StringGenericLeaf extends GenericMiddle<String> {
        public String accept(String value) { return value; }
        public String getPayload() { return "payload"; }
        public void setPayload(String value) {}
      }
      abstract class GenericMiddle<T> implements GenericContract<T> {
        public abstract T accept(T value);
        public abstract T getPayload();
        public abstract void setPayload(T value);
      }
      interface GenericContract<T> {
        T accept(T value);
        T getPayload();
        void setPayload(T value);
      }
      """.trimIndent()
    val bridgeShapedSource =
      """
      package navigation;
      class StringGenericLeaf {
        public Object accept(Object value) { return value; }
        public Object getPayload() { return null; }
        public void setPayload(Object value) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/MultilevelGenericOverrideBridges.kt")

    for (name in listOf("accept", "getPayload", "setPayload")) {
      val method = compileMethod(javaSource, "navigation.StringGenericLeaf", name)
      val expectedSourceName = if (name == "accept") "accept" else "payload"
      assertEquals(expectedSourceName, sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, method)!!,
      ))
    }

    for (name in listOf("accept", "getPayload", "setPayload")) {
      val bridge = compileMethod(bridgeShapedSource, "navigation.StringGenericLeaf", name)
      assertNull(
        "Terminal erased bridge-shaped $name must not navigate to a String leaf override",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, bridge),
      )
    }
  }

  @Test
  fun typeNavigation_resolvesBoundedGenericOverridesAndRejectsUpperBoundBridgeShapes() {
    val kotlinSource =
      """
      package navigation

      interface BoundedContract<T : CharSequence> {
        fun accept(value: T): T
        var payload: T
      }

      class BoundedStringContract : BoundedContract<String> {
        override fun accept(value: String): String = value
        override var payload: String = "payload"
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class BoundedStringContract implements BoundedContract<String> {
        public String accept(String value) { return value; }
        public String getPayload() { return "payload"; }
        public void setPayload(String value) {}
      }
      interface BoundedContract<T extends CharSequence> {
        T accept(T value);
        T getPayload();
        void setPayload(T value);
      }
      """.trimIndent()
    val bridgeShapedSource =
      """
      package navigation;
      class BoundedStringContract {
        public CharSequence accept(CharSequence value) { return value; }
        public CharSequence getPayload() { return null; }
        public void setPayload(CharSequence value) {}
      }
      interface BoundedContract<T extends CharSequence> {
        T accept(T value);
        T getPayload();
        void setPayload(T value);
      }
      """.trimIndent()
    val file = Paths.get("/navigation/BoundedGenericOverrideBridges.kt")

    for (name in listOf("accept", "getPayload", "setPayload")) {
      val method = compileMethod(javaSource, "navigation.BoundedStringContract", name)
      val expectedSourceName = if (name == "accept") "accept" else "payload"
      assertEquals(expectedSourceName, sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, method)!!,
      ))
    }

    for (name in listOf("accept", "getPayload", "setPayload")) {
      val bridge = compileMethod(bridgeShapedSource, "navigation.BoundedStringContract", name)
      assertNull(
        "Upper-bound bridge-shaped $name must not navigate to a String override",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, bridge),
      )
    }
  }

  @Test
  fun typeNavigation_resolvesCovariantReturnOverridesAndRejectsReturnOnlyBridgeShapes() {
    val kotlinSource =
      """
      package navigation

      interface CovariantContract {
        fun render(): CharSequence
        val title: CharSequence
      }

      class StringCovariantContract : CovariantContract {
        override fun render(): String = "rendered"
        override val title: String = "title"
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class StringCovariantContract implements CovariantContract {
        public String render() { return "rendered"; }
        public String getTitle() { return "title"; }
      }
      interface CovariantContract {
        CharSequence render();
        CharSequence getTitle();
      }
      """.trimIndent()
    val bridgeShapedSource =
      """
      package navigation;
      class StringCovariantContract {
        public CharSequence render() { return "rendered"; }
        public CharSequence getTitle() { return "title"; }
      }
      interface CovariantContract {
        CharSequence render();
        CharSequence getTitle();
      }
      """.trimIndent()
    val file = Paths.get("/navigation/CovariantReturnOverrideBridges.kt")

    for ((name, expectedSourceName) in listOf("render" to "render", "getTitle" to "title")) {
      val method = compileMethod(javaSource, "navigation.StringCovariantContract", name)
      assertEquals(expectedSourceName, sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, method)!!,
      ))
    }

    for (name in listOf("render", "getTitle")) {
      val bridge = compileMethod(bridgeShapedSource, "navigation.StringCovariantContract", name)
      assertNull(
        "Return-only bridge-shaped $name must not navigate to a String override",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, bridge),
      )
    }
  }

  @Test
  fun typeNavigation_resolvesPrimaryConstructorVarargByJvmArraySurface() {
    val kotlinSource =
        """
        package navigation

        class PrimaryVarargNavigation(vararg values: String)
        """.trimIndent()
    val javaSource =
        """
        package navigation;
        class PrimaryVarargNavigation {
          PrimaryVarargNavigation(String... values) {}
        }
        """.trimIndent()
    val constructor = compileConstructor(
        javaSource, "navigation.PrimaryVarargNavigation", "java.lang.String[]")
    val file = Paths.get("/navigation/PrimaryVarargNavigation.kt")

    assertEquals("PrimaryVarargNavigation", sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, constructor)))
  }

  @Test
  fun typeNavigation_resolvesNestedArrayPrimaryVarargByTwoDimensionalJvmSurface() {
    val kotlinSource =
        """
        package navigation

        class NestedPrimaryVarargNavigation(vararg values: Array<Int?>)
        """.trimIndent()
    val constructor = compileConstructor(
        """
        package navigation;
        class NestedPrimaryVarargNavigation {
          NestedPrimaryVarargNavigation(Integer[]... values) {}
        }
        """.trimIndent(),
        "navigation.NestedPrimaryVarargNavigation", "java.lang.Integer[][]")
    val file = Paths.get("/navigation/NestedPrimaryVarargNavigation.kt")

    assertEquals("NestedPrimaryVarargNavigation", sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, constructor)))
  }

  @Test
  fun typeNavigation_resolvesNullablePrimaryConstructorVarargByBoxedArraySurface() {
    val kotlinSource =
        """
        package navigation

        class NullablePrimaryVarargNavigation(vararg values: Int?)
        """.trimIndent()
    val constructor = compileConstructor(
        """
        package navigation;
        class NullablePrimaryVarargNavigation {
          NullablePrimaryVarargNavigation(Integer... values) {}
        }
        """.trimIndent(),
        "navigation.NullablePrimaryVarargNavigation", "java.lang.Integer[]")
    val file = Paths.get("/navigation/NullablePrimaryVarargNavigation.kt")

    assertEquals("NullablePrimaryVarargNavigation", sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, constructor)))
  }

  @Test
  fun typeNavigation_resolvesGenericPrimaryConstructorVarargByTypeVariableArraySurface() {
    val kotlinSource =
        """
        package navigation

        class GenericPrimaryVarargNavigation<T>(vararg values: T)
        """.trimIndent()
    val genericConstructor = compileConstructor(
        """
        package navigation;
        class GenericPrimaryVarargNavigation<T> {
          GenericPrimaryVarargNavigation(T... values) {}
        }
        """.trimIndent(),
        "navigation.GenericPrimaryVarargNavigation", "T[]")
    val file = Paths.get("/navigation/GenericPrimaryVarargNavigation.kt")

    assertEquals("GenericPrimaryVarargNavigation", sourceTextAt(
        kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, genericConstructor)))
  }

  @Test
  fun typeNavigation_distinguishesPrimaryAndSecondaryConstructors() {
    val kotlinSource =
      """
      package navigation

      class ConstructorNavigation(val name: String, val count: Int = 0) {
        constructor(code: Int) : this(code.toString())
      }
      """.trimIndent()
    val primary = compileConstructor(
      """
      package navigation;
      class ConstructorNavigation {
        ConstructorNavigation(java.lang.String name, int count) {}
        ConstructorNavigation(int code) {}
      }
      """.trimIndent(),
      "navigation.ConstructorNavigation",
      "java.lang.String,int",
    )
    val secondary = compileConstructor(
      """
      package navigation;
      class ConstructorNavigation {
        ConstructorNavigation(java.lang.String name, int count) {}
        ConstructorNavigation(int code) {}
      }
      """.trimIndent(),
      "navigation.ConstructorNavigation",
      "int",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/ConstructorNavigation.kt"), kotlinSource, primary))
    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/ConstructorNavigation.kt"), kotlinSource, secondary))
  }

  @Test
  fun typeNavigation_resolvesNestedAndInnerTypes() {
    val kotlinSource =
      """
      package navigation

      class TypeNavigation {
        class NestedNavigation
        inner class InnerNavigation
      }
      """.trimIndent()
    val nested = compileType(
      """
      package navigation;
      class TypeNavigation {
        static class NestedNavigation {}
        class InnerNavigation {}
      }
      """.trimIndent(),
      "navigation.TypeNavigation.NestedNavigation",
    )
    val inner = compileType(
      """
      package navigation;
      class TypeNavigation {
        static class NestedNavigation {}
        class InnerNavigation {}
      }
      """.trimIndent(),
      "navigation.TypeNavigation.InnerNavigation",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/TypeNavigation.kt"), kotlinSource, nested))
    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/TypeNavigation.kt"), kotlinSource, inner))
  }

  @Test
  fun typeNavigation_resolvesCompanionJvmStaticFunction() {
    val kotlinSource =
      """
      package navigation

      class CompanionNavigation {
        companion object {
          @JvmStatic
          fun createNavigation(value: String): CompanionNavigation = CompanionNavigation()
        }
      }
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      class CompanionNavigation {
        static CompanionNavigation createNavigation(java.lang.String value) { return null; }
      }
      """.trimIndent(),
      "navigation.CompanionNavigation",
      "createNavigation",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/CompanionNavigation.kt"), kotlinSource, method))
  }

  @Test
  fun typeNavigation_resolvesNamedCompanionJvmOwnerAndHostBridge() {
    val kotlinSource =
      """
      package navigation

      class NamedCompanionNavigation {
        companion object Factory {
          fun ordinary(value: String): String = value
          @JvmStatic fun create(value: Int): Int = value
        }
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class NamedCompanionNavigation {
        static int create(int value) { return value; }
        static class Factory {
          String ordinary(java.lang.String value) { return value; }
          int create(int value) { return value; }
        }
      }
      """.trimIndent()
    val hostCreate = compileMethod(javaSource, "navigation.NamedCompanionNavigation", "create")
    val factoryOrdinary = compileMethod(
      javaSource, "navigation.NamedCompanionNavigation.Factory", "ordinary")
    val factoryCreate = compileMethod(
      javaSource, "navigation.NamedCompanionNavigation.Factory", "create")
    val incorrectAnonymousOwner = compileMethod(
      """
      package navigation;
      class NamedCompanionNavigation {
        static class Companion { String ordinary(java.lang.String value) { return value; } }
      }
      """.trimIndent(),
      "navigation.NamedCompanionNavigation.Companion",
      "ordinary",
    )
    val file = Paths.get("/navigation/NamedCompanionNavigation.kt")

    assertEquals("create", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, hostCreate)!!))
    assertEquals("ordinary", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, factoryOrdinary)!!))
    assertEquals("create", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, factoryCreate)!!))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      file, kotlinSource, incorrectAnonymousOwner))
  }

  @Test
  fun typeNavigation_resolvesNamedCompanionPropertyOwnersAndHostSurfaces() {
    val kotlinSource =
      """
      package navigation

      class NamedCompanionPropertyNavigation {
        companion object Factory {
          @get:JvmName("readMode")
          @set:JvmName("writeMode")
          @JvmStatic var mode: String = "default"
          @JvmField val VERSION: Int = 1
        }
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class NamedCompanionPropertyNavigation {
        static String readMode() { return null; }
        static void writeMode(String value) {}
        static int VERSION;
        static class Factory {
          String readMode() { return null; }
          void writeMode(String value) {}
        }
      }
      """.trimIndent()
    val hostGetter = compileMethod(
      javaSource, "navigation.NamedCompanionPropertyNavigation", "readMode")
    val hostSetter = compileMethod(
      javaSource, "navigation.NamedCompanionPropertyNavigation", "writeMode")
    val hostField = compileField(
      javaSource, "navigation.NamedCompanionPropertyNavigation", "VERSION")
    val factoryGetter = compileMethod(
      javaSource, "navigation.NamedCompanionPropertyNavigation.Factory", "readMode")
    val factorySetter = compileMethod(
      javaSource, "navigation.NamedCompanionPropertyNavigation.Factory", "writeMode")
    val incorrectHostDefaultGetter = compileMethod(
      """
      package navigation;
      class NamedCompanionPropertyNavigation {
        static String getMode() { return null; }
      }
      """.trimIndent(),
      "navigation.NamedCompanionPropertyNavigation",
      "getMode",
    )
    val incorrectAnonymousOwner = compileMethod(
      """
      package navigation;
      class NamedCompanionPropertyNavigation {
        static class Companion { String readMode() { return null; } }
      }
      """.trimIndent(),
      "navigation.NamedCompanionPropertyNavigation.Companion",
      "readMode",
    )
    val file = Paths.get("/navigation/NamedCompanionPropertyNavigation.kt")

    for (element in listOf(hostGetter, hostSetter, factoryGetter, factorySetter)) {
      assertEquals("mode", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, element)!!))
    }
    assertEquals("VERSION", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, hostField)!!))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      file, kotlinSource, incorrectHostDefaultGetter))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      file, kotlinSource, incorrectAnonymousOwner))
  }

  @Test
  fun typeNavigation_rejectsSyntheticCompanionJvmStaticSetter() {
    val kotlinSource =
      """
      package navigation

      class SyntheticStaticSetterNavigation {
        companion object {
          @set:JvmSynthetic
          @JvmStatic
          var visible: String = "visible"
        }
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class SyntheticStaticSetterNavigation {
        static String getVisible() { return null; }
        static void setVisible(java.lang.String value) {}
      }
      """.trimIndent()
    val getter = compileMethod(javaSource, "navigation.SyntheticStaticSetterNavigation", "getVisible")
    val syntheticSetter = compileMethod(
      javaSource, "navigation.SyntheticStaticSetterNavigation", "setVisible")
    val file = Paths.get("/navigation/SyntheticStaticSetterNavigation.kt")

    assertEquals("visible", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, getter)!!))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, syntheticSetter))
  }

  @Test
  fun typeNavigation_keepsCompanionAndHostStaticSurfacesSeparate() {
    val kotlinSource =
      """
      package navigation

      class CompanionOwnerNavigation {
        companion object {
          fun ordinary(value: String): String = value
          @JvmStatic fun bridged(value: Int): Int = value
        }
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class CompanionOwnerNavigation {
        static int bridged(int value) { return value; }
        static class Companion {
          String ordinary(java.lang.String value) { return value; }
          int bridged(int value) { return value; }
        }
      }
      """.trimIndent()
    val hostBridge = compileMethod(javaSource, "navigation.CompanionOwnerNavigation", "bridged")
    val companionOrdinary = compileMethod(
      javaSource, "navigation.CompanionOwnerNavigation.Companion", "ordinary")
    val companionBridge = compileMethod(
      javaSource, "navigation.CompanionOwnerNavigation.Companion", "bridged")
    val incorrectHostOrdinary = compileMethod(
      """
      package navigation;
      class CompanionOwnerNavigation {
        static String ordinary(java.lang.String value) { return value; }
      }
      """.trimIndent(),
      "navigation.CompanionOwnerNavigation",
      "ordinary",
    )
    val file = Paths.get("/navigation/CompanionOwnerNavigation.kt")

    assertEquals("bridged", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, hostBridge)!!))
    assertEquals("ordinary", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, companionOrdinary)!!))
    assertEquals("bridged", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, companionBridge)!!))
    assertNull(KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, incorrectHostOrdinary))
  }

  @Test
  fun typeNavigation_rejectsScalarValueClassConstructorPropertiesButResolvesBoxedContainers() {
    val kotlinSource =
      """
      package navigation

      @JvmInline
      value class UserId(val raw: String)

      class ScalarProperty(val id: UserId)
      class NullableScalarProperty(var id: UserId?)
      class ArrayProperty(var ids: Array<UserId>)
      class GenericProperty(var ids: List<UserId>)
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      final class UserId {}
      class ScalarProperty { String getId() { return null; } }
      class NullableScalarProperty {
        String getId() { return null; }
        void setId(String value) {}
      }
      class ArrayProperty {
        UserId[] getIds() { return null; }
        void setIds(UserId[] value) {}
      }
      class GenericProperty {
        java.util.List<UserId> getIds() { return null; }
        void setIds(java.util.List<UserId> value) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/ValueClassConstructorProperties.kt")
    for ((owner, method) in listOf(
      "navigation.ScalarProperty" to "getId",
      "navigation.NullableScalarProperty" to "getId",
      "navigation.NullableScalarProperty" to "setId",
    )) {
      val accessor = compileMethod(javaSource, owner, method)
      assertNull(
        "Scalar value-class accessor must not be treated as an ordinary Java property: $owner#$method",
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, accessor),
      )
    }

    for ((owner, method) in listOf(
      "navigation.ArrayProperty" to "getIds",
      "navigation.ArrayProperty" to "setIds",
      "navigation.GenericProperty" to "getIds",
      "navigation.GenericProperty" to "setIds",
    )) {
      val accessor = compileMethod(javaSource, owner, method)
      assertEquals("ids", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, accessor)!!))
    }
  }

  @Test
  fun typeNavigation_rejectsScalarValueClassConstructorButResolvesBoxedArraySurface() {
    val kotlinSource =
      """
      package navigation

      @JvmInline
      value class UserId(val raw: String)

      class ValueClassSecondary private constructor() {
        constructor(id: UserId) : this()
        constructor(ids: Array<UserId>) : this()
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      final class UserId {}
      class ValueClassSecondary {
        ValueClassSecondary(java.lang.String id) {}
        ValueClassSecondary(UserId[] ids) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/ValueClassSecondary.kt")

    val scalar = compileConstructor(
      javaSource, "navigation.ValueClassSecondary", "java.lang.String")
    val array = compileConstructor(
      javaSource, "navigation.ValueClassSecondary", "navigation.UserId[]")

    assertEquals(
      "ValueClassSecondary",
      sourceTextAt(kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, scalar)),
    )
    assertEquals(
      "constructor",
      sourceTextAt(kotlinSource,
        KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, array)),
    )
  }

  @Test
  fun typeNavigation_resolvesJvmOverloadsValueClassArrayPrimaryConstructorVariants() {
    val kotlinSource =
      """
      package navigation

      @JvmInline
      value class UserId(val raw: String)

      class ValueClassArrayOverloaded @JvmOverloads constructor(
        val ids: Array<UserId>,
        val count: Int = 0,
      )
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      final class UserId {}
      class ValueClassArrayOverloaded {
        ValueClassArrayOverloaded(UserId[] ids, int count) {}
        ValueClassArrayOverloaded(UserId[] ids) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/ValueClassArrayOverloaded.kt")

    for (parameters in listOf("navigation.UserId[],int", "navigation.UserId[]")) {
      val constructor = compileConstructor(
        javaSource, "navigation.ValueClassArrayOverloaded", parameters)
      assertEquals(
        "ValueClassArrayOverloaded",
        sourceTextAt(kotlinSource,
          KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, constructor)),
      )
    }
  }

  @Test
  fun typeNavigation_resolvesJvmOverloadsPrimaryConstructorVariants() {
    val kotlinSource =
      """
      package navigation

      class OverloadedNavigation @JvmOverloads constructor(
        val name: String,
        val count: Int = 0,
        val enabled: Boolean = true
      )
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class OverloadedNavigation {
        OverloadedNavigation(java.lang.String name, int count, boolean enabled) {}
        OverloadedNavigation(java.lang.String name, int count) {}
        OverloadedNavigation(java.lang.String name) {}
      }
      """.trimIndent()

    for (parameters in listOf(
      "java.lang.String,int,boolean",
      "java.lang.String,int",
      "java.lang.String",
    )) {
      val constructor = compileConstructor(javaSource, "navigation.OverloadedNavigation", parameters)
      assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
        Paths.get("/navigation/OverloadedNavigation.kt"), kotlinSource, constructor))
    }
  }

  @Test
  fun typeNavigation_resolvesNullableJvmOverloadsPrimaryConstructorWithNonTrailingVararg() {
    val kotlinSource =
      """
      package navigation

      class NullablePrimaryVarargOverloads @JvmOverloads constructor(
        vararg values: Int?,
        suffix: String = ""
      )
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class NullablePrimaryVarargOverloads {
        NullablePrimaryVarargOverloads(java.lang.Integer[] values, java.lang.String suffix) {}
        NullablePrimaryVarargOverloads(java.lang.Integer... values) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/NullablePrimaryVarargOverloads.kt")

    for (parameters in listOf("java.lang.Integer[],java.lang.String", "java.lang.Integer[]")) {
      val constructor = compileConstructor(
          javaSource, "navigation.NullablePrimaryVarargOverloads", parameters)
      assertEquals("NullablePrimaryVarargOverloads", sourceTextAt(kotlinSource,
          KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, constructor)))
    }
  }

  @Test
  fun typeNavigation_resolvesGenericJvmOverloadsPrimaryConstructorWithNonTrailingVararg() {
    val kotlinSource =
      """
      package navigation

      class GenericPrimaryVarargOverloads<T> @JvmOverloads constructor(
        vararg values: T,
        suffix: String = ""
      )
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class GenericPrimaryVarargOverloads<T> {
        GenericPrimaryVarargOverloads(T[] values, java.lang.String suffix) {}
        GenericPrimaryVarargOverloads(T... values) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/GenericPrimaryVarargOverloads.kt")

    for (parameters in listOf("T[],java.lang.String", "T[]")) {
      val constructor = compileConstructor(
          javaSource, "navigation.GenericPrimaryVarargOverloads", parameters)
      assertEquals("GenericPrimaryVarargOverloads", sourceTextAt(kotlinSource,
          KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, constructor)))
    }
  }

  @Test
  fun typeNavigation_resolvesJvmOverloadsConstructorsWithNonTrailingVararg() {
    val primarySource =
      """
      package navigation

      class PrimaryVarargOverloads @JvmOverloads constructor(
        vararg values: String,
        suffix: String = ""
      )
      """.trimIndent()
    val primaryJava =
      """
      package navigation;
      class PrimaryVarargOverloads {
        PrimaryVarargOverloads(java.lang.String[] values, java.lang.String suffix) {}
        PrimaryVarargOverloads(java.lang.String... values) {}
      }
      """.trimIndent()
    val secondarySource =
      """
      package navigation

      class SecondaryVarargOverloads private constructor() {
        @JvmOverloads
        constructor(vararg values: String, suffix: String = "") : this()
      }
      """.trimIndent()
    val secondaryJava =
      """
      package navigation;
      class SecondaryVarargOverloads {
        SecondaryVarargOverloads(java.lang.String[] values, java.lang.String suffix) {}
        SecondaryVarargOverloads(java.lang.String... values) {}
      }
      """.trimIndent()

    for (parameters in listOf("java.lang.String[],java.lang.String", "java.lang.String[]")) {
      val primary = compileConstructor(primaryJava, "navigation.PrimaryVarargOverloads", parameters)
      assertEquals("PrimaryVarargOverloads", sourceTextAt(primarySource,
          KotlinJvmSourceNavigator.findTypeMemberLocation(
              Paths.get("/navigation/PrimaryVarargOverloads.kt"), primarySource, primary)))
      val secondary = compileConstructor(secondaryJava, "navigation.SecondaryVarargOverloads", parameters)
      assertEquals("constructor", sourceTextAt(secondarySource,
          KotlinJvmSourceNavigator.findTypeMemberLocation(
              Paths.get("/navigation/SecondaryVarargOverloads.kt"), secondarySource, secondary)))
    }
  }

  @Test
  fun typeNavigation_resolvesJvmOverloadsSecondaryConstructorVariants() {
    val kotlinSource =
      """
      package navigation

      class SecondaryOverloadedNavigation private constructor(val value: String) {
        @JvmOverloads
        constructor(code: Int, enabled: Boolean = true, label: String = "") :
          this(code.toString())
      }
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class SecondaryOverloadedNavigation {
        SecondaryOverloadedNavigation(int code, boolean enabled, java.lang.String label) {}
        SecondaryOverloadedNavigation(int code, boolean enabled) {}
        SecondaryOverloadedNavigation(int code) {}
      }
      """.trimIndent()
    val file = Paths.get("/navigation/SecondaryOverloadedNavigation.kt")

    for (parameters in listOf("int,boolean,java.lang.String", "int,boolean", "int")) {
      val constructor = compileConstructor(
        javaSource, "navigation.SecondaryOverloadedNavigation", parameters)
      val location = KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, constructor)
      assertNotNull("Secondary overload did not navigate for $parameters", location)
      assertEquals("constructor", sourceTextAt(kotlinSource, location!!))
    }
  }

  @Test
  fun typeNavigation_resolvesConstructorPropertyAccessors() {
    val kotlinSource =
      """
      package navigation

      class ConstructorPropertyNavigation(
        val navigationId: String,
        var navigationEnabled: Boolean
      )
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      abstract class ConstructorPropertyNavigation {
        abstract java.lang.String getNavigationId();
        abstract boolean getNavigationEnabled();
        abstract void setNavigationEnabled(boolean value);
      }
      """.trimIndent()

    for (name in listOf("getNavigationId", "getNavigationEnabled", "setNavigationEnabled")) {
      val method = compileMethod(javaSource, "navigation.ConstructorPropertyNavigation", name)
      assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
        Paths.get("/navigation/ConstructorPropertyNavigation.kt"), kotlinSource, method))
    }
  }

  @Test
  fun typeNavigation_resolvesWildcardJvmFieldAndRejectsInvariantField() {
    val kotlinSource =
      """
      package navigation

      class WildcardFieldNavigation {
        @JvmField
        val forced: List<@JvmWildcard String> = emptyList()
      }
      """.trimIndent()
    val wildcardField = compileField(
      """
      package navigation;
      class WildcardFieldNavigation {
        java.util.List<? extends String> forced;
      }
      """.trimIndent(),
      "navigation.WildcardFieldNavigation",
      "forced",
    )
    val invariantField = compileField(
      """
      package navigation;
      class WildcardFieldNavigation {
        java.util.List<String> forced;
      }
      """.trimIndent(),
      "navigation.WildcardFieldNavigation",
      "forced",
    )
    val file = Paths.get("/navigation/WildcardFieldNavigation.kt")

    assertEquals("forced", sourceTextAt(
      kotlinSource, KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, wildcardField)!!))
    assertNull("Invariant Java field must not navigate to Kotlin @JvmWildcard field",
      KotlinJvmSourceNavigator.findTypeMemberLocation(file, kotlinSource, invariantField))
  }

  @Test
  fun typeNavigation_resolvesCompanionJvmField() {
    val kotlinSource =
      """
      package navigation

      class FieldNavigation {
        companion object {
          @JvmField
          val NAVIGATION_VERSION: Int = 1
        }
      }
      """.trimIndent()
    val field = compileField(
      """
      package navigation;
      class FieldNavigation {
        static int NAVIGATION_VERSION;
      }
      """.trimIndent(),
      "navigation.FieldNavigation",
      "NAVIGATION_VERSION",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/FieldNavigation.kt"), kotlinSource, field))
  }

  @Test
  fun multifileNavigation_selectsMemberFromCorrectFacadePart() {
    val firstPath = Paths.get("/navigation/MultifileFirst.kt")
    val secondPath = Paths.get("/navigation/MultifileSecond.kt")
    val firstSource =
      """
      @file:JvmName("NavigationFacade")
      @file:JvmMultifileClass
      package navigation

      fun firstNavigation(value: Int): String = value.toString()
      """.trimIndent()
    val secondSource =
      """
      @file:JvmName("NavigationFacade")
      @file:JvmMultifileClass
      package navigation

      fun secondNavigation(value: String): Boolean = value.isNotEmpty()
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class NavigationFacade {
        abstract boolean secondNavigation(java.lang.String value);
      }
      """.trimIndent(),
      "navigation.NavigationFacade",
      "secondNavigation",
    )

    val location = KotlinJvmSourceNavigator.findMultifileFacadeMemberLocation(
      listOf(firstPath, secondPath), listOf(firstSource, secondSource), method)

    assertNotNull(location)
    assertEquals(secondPath, location!!.file)
    assertEquals("secondNavigation", sourceTextAt(secondSource, location))
  }

  @Test
  fun facadeNavigation_resolvesReferenceArrayVarianceWithoutStarProjectionGuessing() {
    val file = Paths.get("/navigation/ArrayVarianceNavigation.kt")
    val kotlinSource =
        """
        package navigation

        fun readValues(values: Array<out String>): String = values.joinToString()
        fun writeValues(values: Array<in String>): Int = values.size
        fun unknownValues(values: Array<*>): Int = values.size
        """.trimIndent()

    val read = compileMethod(
        """
        package navigation;
        abstract class ArrayVarianceNavigationKt {
          abstract String readValues(String[] values);
        }
        """.trimIndent(),
        "navigation.ArrayVarianceNavigationKt", "readValues")
    val write = compileMethod(
        """
        package navigation;
        abstract class ArrayVarianceNavigationKt {
          abstract int writeValues(String[] values);
        }
        """.trimIndent(),
        "navigation.ArrayVarianceNavigationKt", "writeValues")
    val unknown = compileMethod(
        """
        package navigation;
        abstract class ArrayVarianceNavigationKt {
          abstract int unknownValues(Object[] values);
        }
        """.trimIndent(),
        "navigation.ArrayVarianceNavigationKt", "unknownValues")

    assertEquals("readValues", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, read)))
    assertEquals("writeValues", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, write)))
    // Array<*> has no exact component-type proof. The navigator nevertheless retains an otherwise
    // unique candidate; it only rejects unknown signatures when multiple declarations survive.
    assertEquals("unknownValues", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, unknown)))
  }

  // Array<*> and Array<out Any> cannot be declared as same-name overloads: both erase to Object[].
  // Keep ambiguity coverage to source combinations with a valid, distinct Kotlin/JVM declaration set.

  @Test
  fun facadeNavigation_resolvesNullableReferenceArrayComponentsAndDimensions() {
    val file = Paths.get("/navigation/NullableArrayNavigation.kt")
    val kotlinSource =
        """
        package navigation

        fun nullableNumbers(values: Array<Int?>): Int = values.size
        fun nullableLabels(values: Array<String?>): Int = values.size
        fun nullableNested(values: Array<Array<Int?>?>): Int = values.size
        """.trimIndent()
    val numbers = compileMethod(
        """
        package navigation;
        abstract class NullableArrayNavigationKt {
          abstract int nullableNumbers(Integer[] values);
        }
        """.trimIndent(),
        "navigation.NullableArrayNavigationKt", "nullableNumbers")
    val labels = compileMethod(
        """
        package navigation;
        abstract class NullableArrayNavigationKt {
          abstract int nullableLabels(String[] values);
        }
        """.trimIndent(),
        "navigation.NullableArrayNavigationKt", "nullableLabels")
    val nested = compileMethod(
        """
        package navigation;
        abstract class NullableArrayNavigationKt {
          abstract int nullableNested(Integer[][] values);
        }
        """.trimIndent(),
        "navigation.NullableArrayNavigationKt", "nullableNested")
    val primitiveMismatch = compileMethod(
        """
        package navigation;
        abstract class NullableArrayNavigationKt {
          abstract int nullableNumbers(int[] values);
        }
        """.trimIndent(),
        "navigation.NullableArrayNavigationKt", "nullableNumbers")

    assertEquals("nullableNumbers", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, numbers)))
    assertEquals("nullableLabels", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, labels)))
    assertEquals("nullableNested", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, nested)))
    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, primitiveMismatch))
  }

  @Test
  fun facadeNavigation_resolvesReferenceAndPrimitiveVarargsByJvmArraySurface() {
    val file = Paths.get("/navigation/VarargNavigation.kt")
    val kotlinSource =
        """
        package navigation

        fun labels(vararg values: String): String = values.joinToString()
        fun numbers(vararg values: Int): Int = values.sum()
        """.trimIndent()
    val labels = compileMethod(
        """
        package navigation;
        abstract class VarargNavigationKt {
          abstract String labels(String... values);
        }
        """.trimIndent(),
        "navigation.VarargNavigationKt", "labels")
    val numbers = compileMethod(
        """
        package navigation;
        abstract class VarargNavigationKt {
          abstract int numbers(int... values);
        }
        """.trimIndent(),
        "navigation.VarargNavigationKt", "numbers")
    val wrongDimensions = compileMethod(
        """
        package navigation;
        abstract class VarargNavigationKt {
          abstract String labels(String[][] values);
        }
        """.trimIndent(),
        "navigation.VarargNavigationKt", "labels")

    assertEquals("labels", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, labels)))
    assertEquals("numbers", sourceTextAt(
        kotlinSource, KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, numbers)))
    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(file, kotlinSource, wrongDimensions))
  }

  @Test
  fun multifileNavigation_usesAliasContextOfEachPart() {
    val firstPath = Paths.get("/navigation/AliasFirst.kt")
    val secondPath = Paths.get("/navigation/AliasSecond.kt")
    val firstSource =
      """
      @file:JvmName("AliasNavigationFacade")
      @file:JvmMultifileClass
      package navigation

      typealias PartAlias<T> = List<T>
      fun aliasNavigation(value: PartAlias<String>): Unit {}
      """.trimIndent()
    val secondSource =
      """
      @file:JvmName("AliasNavigationFacade")
      @file:JvmMultifileClass
      package navigation

      typealias PartAlias<T> = List<T>
      fun aliasNavigation(value: PartAlias<Int>): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class AliasNavigationFacade {
        abstract void aliasNavigation(java.util.List<java.lang.Integer> value);
      }
      """.trimIndent(),
      "navigation.AliasNavigationFacade",
      "aliasNavigation",
    )

    val location = KotlinJvmSourceNavigator.findMultifileFacadeMemberLocation(
      listOf(firstPath, secondPath), listOf(firstSource, secondSource), method)

    assertNotNull(location)
    assertEquals(secondPath, location!!.file)
    assertEquals("aliasNavigation", sourceTextAt(secondSource, location))
  }

  @Test
  fun crossFileAliasVisibility_drivesFacadeNavigation() {
    val root = Files.createTempDirectory("kotlin-navigation-alias")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      writeSource(shared.resolve("Aliases.kt"), """
        package shared
        typealias ImportedNavigationAlias<T> = List<T>
      """.trimIndent())
      val consumerFile = consumer.resolve("NavigationApi.kt")
      val consumerSource =
        """
        package consumer
        import shared.ImportedNavigationAlias

        fun crossFileNavigation(value: ImportedNavigationAlias<String>): Unit {}
        fun crossFileNavigation(value: ImportedNavigationAlias<Int>): Unit {}
        """.trimIndent()
      writeSource(consumerFile, consumerSource)
      val aliases = KotlinJvmTypeIndex.visibleGenericTypeAliases(
        listOf(root.toFile()), consumerFile)
      val method = compileMethod(
        """
        package consumer;
        abstract class NavigationApiKt {
          abstract void crossFileNavigation(java.util.List<java.lang.Integer> value);
        }
        """.trimIndent(),
        "consumer.NavigationApiKt",
        "crossFileNavigation",
      )

      val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
        consumerFile, consumerSource, method, emptyMap(), aliases)

      assertNotNull(location)
      assertEquals("crossFileNavigation", sourceTextAt(consumerSource, location!!))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun wildcardAliasVisibility_doesNotEnableAmbiguousNavigation() {
    val root = Files.createTempDirectory("kotlin-navigation-wildcard-alias")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      writeSource(shared.resolve("Aliases.kt"), """
        package shared
        typealias WildcardNavigationAlias<T> = List<T>
      """.trimIndent())
      val consumerFile = consumer.resolve("NavigationApi.kt")
      val consumerSource =
        """
        package consumer
        import shared.*

        fun wildcardNavigation(value: WildcardNavigationAlias<String>): Unit {}
        fun wildcardNavigation(value: WildcardNavigationAlias<Int>): Unit {}
        """.trimIndent()
      writeSource(consumerFile, consumerSource)
      val aliases = KotlinJvmTypeIndex.visibleGenericTypeAliases(
        listOf(root.toFile()), consumerFile)
      val method = compileMethod(
        """
        package consumer;
        abstract class NavigationApiKt {
          abstract void wildcardNavigation(java.util.List<java.lang.Integer> value);
        }
        """.trimIndent(),
        "consumer.NavigationApiKt",
        "wildcardNavigation",
      )

      assertTrue(aliases.isEmpty())
      assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
        consumerFile, consumerSource, method, emptyMap(), aliases))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun compileMethod(
    source: String,
    qualifiedOwner: String,
    methodName: String,
  ): ExecutableElement {
    val task =
      JavacTool.create()
        .getTask(
          null,
          null,
          null,
          emptyList<String>(),
          emptyList<String>(),
          listOf(FakeSourceFile(source)),
        ) as JavacTask
    task.parse()
    task.analyze()
    val owner = task.elements.getTypeElement(qualifiedOwner) as TypeElement
    return owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { it.simpleName.contentEquals(methodName) }
  }

  private fun writeSource(path: Path, source: String) {
    Files.write(path, source.toByteArray(Charsets.UTF_8))
  }

  private fun sourceTextAt(source: String, location: com.tom.rv2ide.models.Location): String {
    val start = location.range.start
    val end = location.range.end
    if (start.line != end.line) return ""
    return source.lineSequence().elementAt(start.line).substring(start.column, end.column)
  }

  private fun compileField(
    source: String,
    qualifiedOwner: String,
    fieldName: String,
  ): VariableElement {
    val owner = compileType(source, qualifiedOwner)
    return owner.enclosedElements
      .filterIsInstance<VariableElement>()
      .single { it.simpleName.contentEquals(fieldName) }
  }

  private fun compileConstructor(
    source: String,
    qualifiedOwner: String,
    parameterTypes: String,
  ): ExecutableElement {
    val owner = compileType(source, qualifiedOwner)
    return owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { constructor ->
        constructor.simpleName.contentEquals("<init>") &&
          constructor.parameters.joinToString(",") { it.asType().toString() } == parameterTypes
      }
  }

  private fun compileType(source: String, qualifiedName: String): TypeElement {
    val task = compileTask(source)
    return task.elements.getTypeElement(qualifiedName) as TypeElement
  }

  private fun compileTask(source: String): JavacTask {
    val task =
      JavacTool.create()
        .getTask(
          null,
          null,
          null,
          emptyList<String>(),
          emptyList<String>(),
          listOf(FakeSourceFile(source)),
        ) as JavacTask
    task.parse()
    task.analyze()
    return task
  }

  private class FakeSourceFile(private val code: String) :
    SimpleJavaFileObject(URI.create("string:///NavigationApiKt.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    override fun getLastModified(): Long = 1L
  }

  companion object {
    @JvmStatic
    @BeforeClass
    fun loadParserLibraries() {
      TreeSitter.loadLibrary()
      System.loadLibrary("tree-sitter-kotlin")
    }
  }
}
