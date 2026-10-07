package com.tom.rv2ide.lsp.java.kotlin

import java.nio.file.Paths
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Lifecycle coverage for the per-consumer alias visibility cache. */
class KotlinJvmTypeIndexAliasCacheTest {

  @Test
  fun consumerAliasCache_hitsForSamePathAndSource() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val file = Paths.get("/consumer/Api.kt")
    val aliases = Collections.singletonMap("Name", "String")

    cache.put(file, "package consumer", aliases)

    assertSame(aliases, cache.get(file, "package consumer"))
    assertEquals(1, cache.size())
  }

  @Test
  fun consumerAliasCache_doesNotRetainOversizedConsumerSource() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val file = Paths.get("/consumer/LargeApi.kt")
    val smallSource = "package consumer"
    val source = "x".repeat(
      KotlinJvmTypeIndex.ConsumerAliasCache.MAX_CACHED_CONSUMER_SOURCE_CHARS + 1)
    val aliases = Collections.singletonMap("Name", "String")

    cache.put(file, smallSource, aliases)
    assertEquals(1, cache.size())
    cache.put(file, source, aliases)

    assertNull(cache.get(file, source))
    assertNull(cache.get(file, smallSource))
    assertEquals(0, cache.size())
  }

  @Test
  fun consumerAliasCache_replacesOldConsumerContentWithoutGrowing() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val file = Paths.get("/consumer/Api.kt")
    val oldAliases = Collections.singletonMap("OldName", "String")
    val newAliases = Collections.singletonMap("NewName", "Int")

    cache.put(file, "import shared.OldName", oldAliases)
    assertNull(cache.get(file, "import shared.NewName"))
    cache.put(file, "import shared.NewName", newAliases)

    assertNull(cache.get(file, "import shared.OldName"))
    assertSame(newAliases, cache.get(file, "import shared.NewName"))
    assertEquals(1, cache.size())
  }

  @Test
  fun compileSourceRevision_isIndependentOfDependencyIterationOrder() {
    val first = linkedMapOf(":mobile" to 2L, ":core:data" to 4L)
    val reversed = linkedMapOf(":core:data" to 4L, ":mobile" to 2L)

    assertEquals(
      KotlinJvmTypeIndex.CompileSourceRevision.from(first),
      KotlinJvmTypeIndex.CompileSourceRevision.from(reversed))
  }

  @Test
  fun revisionSnapshotStore_missesWhenCompileDependencyRevisionChanges() {
    val store = KotlinJvmTypeIndex.RevisionSnapshotStore<String>()
    val revisionOne = KotlinJvmTypeIndex.CompileSourceRevision.from(
      linkedMapOf(":mobile" to 2L, ":core:data" to 2L))
    val dependencyChanged = KotlinJvmTypeIndex.CompileSourceRevision.from(
      linkedMapOf(":mobile" to 2L, ":core:data" to 3L))

    store.replace(revisionOne, "snapshot-one")

    assertEquals("snapshot-one", store.get(revisionOne))
    assertNull(store.get(dependencyChanged))
  }

  @Test
  fun revisionSnapshotStore_replacesSnapshotsAcrossSourceRevisions() {
    val store = KotlinJvmTypeIndex.RevisionSnapshotStore<String>()
    val revisionOne = store.replace(41L, "snapshot-one")

    assertSame(revisionOne, store.get(41L))
    assertNull(store.get(42L))

    val revisionTwo = store.replace(42L, "snapshot-two")
    assertNull(store.get(41L))
    assertSame(revisionTwo, store.get(42L))
  }
  @Test
  fun revisionSnapshotStore_clearMakesEveryRevisionMiss() {
    val store = KotlinJvmTypeIndex.RevisionSnapshotStore<String>()
    store.replace(41L, "snapshot-one")

    store.clear()

    assertNull(store.get(41L))
    assertNull(store.get(42L))
  }

  @Test
  fun revisionSnapshotStore_rejectsNullSnapshotPublication() {
    val store = KotlinJvmTypeIndex.RevisionSnapshotStore<String?>()

    try {
      store.replace(41L, null)
      throw AssertionError("Expected replacement validation failure")
    } catch (_: IllegalArgumentException) {
      assertNull(store.get(41L))
    }
  }


  @Test
  fun aliasCacheStore_invalidateRemovesOnlyTheRequestedModuleSnapshot() {
    val cache = KotlinJvmTypeIndex.AliasCacheStore<String, String>()
    cache.put("first", "revision-one")
    cache.put("second", "revision-two")

    cache.remove("first")

    assertNull(cache.get("first"))
    assertEquals("revision-two", cache.get("second"))
    assertEquals(1, cache.size())
  }

  @Test
  fun aliasCacheStore_clearRemovesAllModuleSnapshots() {
    val cache = KotlinJvmTypeIndex.AliasCacheStore<String, String>()
    cache.put("first", "revision-one")
    cache.put("second", "revision-two")

    cache.clear()

    assertNull(cache.get("first"))
    assertNull(cache.get("second"))
    assertEquals(0, cache.size())
  }

  @Test
  fun consumerAliasCache_evictsPreviousEntriesWhenConsumerLimitIsReached() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val aliases = Collections.singletonMap("Name", "String")
    val source = "package consumer"
    val first = Paths.get("/consumer/Api0.kt")

    for (index in 0 until KotlinJvmTypeIndex.ConsumerAliasCache.MAX_CACHED_CONSUMERS) {
      cache.put(Paths.get("/consumer/Api$index.kt"), source, aliases)
    }
    assertEquals(KotlinJvmTypeIndex.ConsumerAliasCache.MAX_CACHED_CONSUMERS, cache.size())

    val overflow = Paths.get("/consumer/Overflow.kt")
    cache.put(overflow, source, aliases)

    assertEquals(1, cache.size())
    assertNull(cache.get(first, source))
    assertSame(aliases, cache.get(overflow, source))
  }

  @Test
  fun consumerAliasCache_normalizesEquivalentPaths() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val aliases = Collections.singletonMap("Name", "String")

    cache.put(Paths.get("/consumer/dir/../Api.kt"), "source", aliases)

    assertSame(aliases, cache.get(Paths.get("/consumer/Api.kt"), "source"))
    assertEquals(1, cache.size())
  }
}