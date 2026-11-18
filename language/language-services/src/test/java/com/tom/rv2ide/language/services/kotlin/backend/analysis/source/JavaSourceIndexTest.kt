package com.tom.rv2ide.language.services.kotlin.backend.analysis.source

import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.fixturePath
import com.tom.rv2ide.language.services.kotlin.backend.analysis.KotlinAnalysisTestSupport.withWorkspace
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = BaseApplication::class)
class JavaSourceIndexTest {

  @Test
  fun refreshFileTracksActiveDocumentContentAndDiskFallback() {
    withWorkspace {
      val root = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app")
      val file = root.resolve("JavaIndexSubject.java")
      val packageName = "com.tom.rv2ide.testing.kotlinfixture.app"
      val index = JavaSourceIndex { listOf(root) }
      try {
        Files.write(
            file,
            javaSource(packageName, "public class JavaIndexSubject {}").toByteArray(Charsets.UTF_8),
        )
        assertTrue(
            "public type must be indexed initially",
            index.classesBySimpleNamePrefix(":app", "JavaIndexSubject", 10)
                .any { it.simpleName == "JavaIndexSubject" },
        )
        val filesBefore = index.sourceFiles(":app")
        assertTrue("source file must be listed", filesBefore.contains(file))

        Files.write(
            file,
            javaSource(packageName, "class JavaIndexSubject {}").toByteArray(Charsets.UTF_8),
        )
        index.refreshFile(file)
        assertTrue(
            "the saved package-private type must disappear without a full invalidation",
            index.classesBySimpleNamePrefix(":app", "JavaIndexSubject", 10).isEmpty(),
        )

        openDocument(
            file,
            javaSource(packageName, "public class JavaIndexSubject {}"),
            version = 1,
            revision = 1L,
        )
        index.refreshFile(file)
        assertTrue(
            "the active document must take precedence over the disk text",
            index.classesBySimpleNamePrefix(":app", "JavaIndexSubject", 10)
                .any { it.simpleName == "JavaIndexSubject" },
        )

        openDocument(
            file,
            "package $packageName;\n\npublic class JavaIndexSubject {",
            version = 2,
            revision = 2L,
        )
        index.refreshFile(file)
        assertTrue(
            "an unparsable revision must keep the last usable types",
            index.classesBySimpleNamePrefix(":app", "JavaIndexSubject", 10)
                .any { it.simpleName == "JavaIndexSubject" },
        )

        closeDocument(file)
        index.refreshFile(file)
        assertTrue(
            "closing the document must fall back to the disk text",
            index.classesBySimpleNamePrefix(":app", "JavaIndexSubject", 10).isEmpty(),
        )
        assertEquals(
            "refresh must keep the cached path set",
            filesBefore,
            index.sourceFiles(":app"),
        )
      } finally {
        closeDocument(file)
        Files.deleteIfExists(file)
        index.close()
      }
    }
  }

  @Test
  fun refreshFileUpdatesEveryCachedModuleTableListingTheFile() {
    withWorkspace {
      val root = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app")
      val file = root.resolve("JavaIndexSharedSubject.java")
      val packageName = "com.tom.rv2ide.testing.kotlinfixture.app"
      val index = JavaSourceIndex { listOf(root) }
      try {
        Files.write(
            file,
            javaSource(packageName, "public class JavaIndexSharedSubject {}")
                .toByteArray(Charsets.UTF_8),
        )
        assertTrue(
            ":app must index the public type",
            index.classesBySimpleNamePrefix(":app", "JavaIndexSharedSubject", 10)
                .any { it.simpleName == "JavaIndexSharedSubject" },
        )
        assertTrue(
            ":library must index the public type from the same root",
            index.classesBySimpleNamePrefix(":library", "JavaIndexSharedSubject", 10)
                .any { it.simpleName == "JavaIndexSharedSubject" },
        )

        Files.write(
            file,
            javaSource(packageName, "class JavaIndexSharedSubject {}").toByteArray(Charsets.UTF_8),
        )
        index.refreshFile(file)
        assertTrue(
            ":app must drop the package-private type",
            index.classesBySimpleNamePrefix(":app", "JavaIndexSharedSubject", 10).isEmpty(),
        )
        assertTrue(
            ":library must drop the package-private type",
            index.classesBySimpleNamePrefix(":library", "JavaIndexSharedSubject", 10).isEmpty(),
        )
      } finally {
        Files.deleteIfExists(file)
        index.close()
      }
    }
  }

  @Test
  fun invalidateDuringColdBuildDiscardsTheStaleWalk() {
    withWorkspace {
      val root = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app")
      val file = root.resolve("JavaIndexRaceSubject.java")
      val packageName = "com.tom.rv2ide.testing.kotlinfixture.app"
      val firstBuildStarted = CountDownLatch(1)
      val releaseFirstBuild = CountDownLatch(1)
      val buildCount = AtomicInteger(0)
      val index = JavaSourceIndex {
        if (buildCount.incrementAndGet() == 1) {
          firstBuildStarted.countDown()
          assertTrue(
              "the first build must be released",
              releaseFirstBuild.await(10, TimeUnit.SECONDS),
          )
        }
        listOf(root)
      }
      val executor = Executors.newSingleThreadExecutor()
      try {
        Files.write(
            file,
            javaSource(packageName, "public class JavaIndexRaceSubject {}")
                .toByteArray(Charsets.UTF_8),
        )
        val firstQuery =
            executor.submit(Callable { index.classesInPackage(":app", packageName) })
        assertTrue("the cold build must start", firstBuildStarted.await(10, TimeUnit.SECONDS))
        index.invalidate()
        releaseFirstBuild.countDown()
        assertTrue(
            "a build racing with invalidate must not answer with the stale walk",
            firstQuery.get(10, TimeUnit.SECONDS).isEmpty(),
        )
        assertTrue(
            "the next query must rebuild against the current roots",
            index.classesInPackage(":app", packageName)
                .any { it.simpleName == "JavaIndexRaceSubject" },
        )
      } finally {
        releaseFirstBuild.countDown()
        executor.shutdownNow()
        Files.deleteIfExists(file)
        index.close()
      }
    }
  }

  @Test
  fun duplicateSourceRootsProduceOnePathEntry() {
    withWorkspace {
      val root = fixturePath("app/src/main/java/com/tom/rv2ide/testing/kotlinfixture/app")
      val file = root.resolve("JavaIndexDuplicateRootSubject.java")
      val packageName = "com.tom.rv2ide.testing.kotlinfixture.app"
      val index = JavaSourceIndex { listOf(root, root) }
      try {
        Files.write(
            file,
            javaSource(packageName, "public class JavaIndexDuplicateRootSubject {}")
                .toByteArray(Charsets.UTF_8),
        )
        val files = index.sourceFiles(":app")
        assertEquals(
            "an overlapping source root must not duplicate the file entry",
            1,
            files.count { it == file },
        )
        assertTrue(
            "the deduplicated file must still be parsed",
            index.classesBySimpleNamePrefix(":app", "JavaIndexDuplicateRootSubject", 10)
                .any { it.simpleName == "JavaIndexDuplicateRootSubject" },
        )
      } finally {
        Files.deleteIfExists(file)
        index.close()
      }
    }
  }

  private fun javaSource(packageName: String, declaration: String): String =
      "package $packageName;\n\n$declaration\n"
}
