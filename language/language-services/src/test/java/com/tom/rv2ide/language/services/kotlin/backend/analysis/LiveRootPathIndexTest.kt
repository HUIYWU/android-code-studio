package com.tom.rv2ide.language.services.kotlin.backend.analysis

import com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar.LiveRootPathIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRootPathIndexTest {
  @Test
  fun emptyAndSegmentBoundariesDoNotMatchSiblingPrefixes() {
    val empty = LiveRootPathIndex<String>(emptyList())
    assertFalse(empty.contains("/project/src/File.java"))
    assertNull(empty.find("/project/src/File.java"))
    val root = entry("/project/src", "source", 0)
    val index = LiveRootPathIndex(listOf(root))
    assertTrue(index.contains("/project/src"))
    assertTrue(index.contains("/project/src/pkg/File.java"))
    assertFalse(index.contains("/project/src-extra/File.java"))
    assertFalse(index.contains("/project/src2/File.java"))
    assertFalse(index.contains("/project/sr"))
    assertSame(root, index.find("/project/src/pkg/File.java"))
  }

  @Test
  fun floorCandidateFallsBackToItsPrefixAncestors() {
    val parent = entry("/project/src", "parent", 0)
    val child = entry("/project/src/generated", "generated", 1)
    val index = LiveRootPathIndex(listOf(
        entry("/project/src-extra", "sibling", 2), child, parent,
        entry("/project/src/generated-extra", "near-child", 3),
        entry("/project/src/aaa", "earlier-child", 4),
    ))
    assertSame(child, index.find("/project/src/generated/pkg/File.java"))
    assertSame(parent, index.find("/project/src/generated2/File.java"))
    assertSame(parent, index.find("/project/src/zzz/File.java"))
    assertNull(index.find("/project/srz/File.java"))
  }

  @Test
  fun priorityAndOriginalOrderSurviveSortingAndDuplicates() {
    val first = LiveRootPathIndex.Entry("/project/src", "first", 100, 0)
    val duplicate = LiveRootPathIndex.Entry("/project/src", "duplicate", 100, 1)
    val nested = LiveRootPathIndex.Entry("/project/src/generated", "nested", 100, 2)
    val index = LiveRootPathIndex(listOf(nested, duplicate, first))
    assertSame(first, index.find("/project/src/generated/File.java"))
    val higher = LiveRootPathIndex.Entry("/project/src", "higher", 101, 3)
    assertSame(higher, LiveRootPathIndex(listOf(first, higher)).find("/project/src/File.java"))
    assertSame(nested, LiveRootPathIndex(listOf(nested, entry("/project/src", "normal", 4)))
        .find("/project/src/generated/File.java"))
  }

  @Test
  fun archiveBoundariesDoNotMatchAdjacentJarNames() {
    val archive = entry("/libraries/api.jar", "archive", 0)
    val member = entry("/libraries/api.jar!/pkg", "member", 1)
    val index = LiveRootPathIndex(listOf(archive, member), archiveBoundary = true)
    assertSame(archive, index.find("/libraries/api.jar!/other/Type.class"))
    assertSame(member, index.find("/libraries/api.jar!/pkg/Type.class"))
    assertSame(archive, index.find("/libraries/api.jar!/pkg2/Type.class"))
    assertNull(index.find("/libraries/api.jar2!/pkg/Type.class"))
    assertNull(index.find("/libraries/api.jar!not-an-archive"))
    val native = LiveRootPathIndex(listOf(archive))
    assertFalse(native.contains("/libraries/api.jar!/pkg/Type.class"))
    val filesystem = LiveRootPathIndex(listOf(entry("/", "filesystem", 0)))
    assertTrue(filesystem.contains("/libraries/api.jar!/pkg/Type.class"))
    assertFalse(filesystem.contains("relative/Type.class"))
  }

  @Test
  fun indexedQueriesAgreeWithLinearBoundaryAndPrioritySelection() {
    val paths = listOf(
        "/project", "/project/app", "/project/app-extra", "/project/app/src",
        "/project/app/src/generated", "/project/app/src/generated-extra", "/project/application",
        "/libraries/api.jar", "/libraries/api.jar!/pkg", "/libraries/api.jar2", "/libraries/api.jar!/pkg2",
    )
    val queries = paths.flatMap { path ->
      listOf(path, "$path/File.java", "${path}2/File.java", "$path!/pkg/Type.class")
    } + listOf("/", "/outside/File.java", "/project/app/src/zzz/File.java", "/libraries/api.jar!/pkg3/Type.class")
    for (archiveBoundary in listOf(false, true)) {
      for (offset in paths.indices) {
        val roots = paths.drop(offset).mapIndexed { order, path ->
          LiveRootPathIndex.Entry(path, path, path.length + order % 3, order)
        }
        val index = LiveRootPathIndex(roots.reversed(), archiveBoundary)
        for (query in queries) {
          val expected = roots.filter { root ->
            query == root.path || query.startsWith(root.path.trimEnd('/') + "/") ||
                (archiveBoundary && query.startsWith(root.path + "!/"))
          }.sortedWith(compareByDescending<LiveRootPathIndex.Entry<String>> { it.priority }.thenBy { it.order })
              .firstOrNull()
          assertEquals("archive=$archiveBoundary offset=$offset query=$query", expected != null, index.contains(query))
          assertSame("archive=$archiveBoundary offset=$offset query=$query", expected, index.find(query))
        }
      }
    }
  }

  private fun entry(path: String, value: String, order: Int) =
      LiveRootPathIndex.Entry(path, value, path.length, order)
}
