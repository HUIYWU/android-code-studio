package com.tom.rv2ide.language.services.interop

import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.models.Location
import com.tom.rv2ide.progress.ICancelChecker
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Java-to-Kotlin navigation backed by the shared Analysis PSI project.
 *
 * The Kotlin Analysis backend owns that project, so Java features consume this contract instead of
 * reaching into the backend. The slot is process-wide and follows the runtime lifecycle: while no
 * Analysis runtime is running, callers must rely on their own resolution path.
 */
interface KotlinSourceResolver {
  fun kotlinDefinition(javaFile: Path, offset: Int, cancelChecker: ICancelChecker): Location?

  fun kotlinHover(javaFile: Path, offset: Int, cancelChecker: ICancelChecker): MarkupContent?

  fun kotlinSignature(javaFile: Path, offset: Int, cancelChecker: ICancelChecker): SignatureHelp?

  fun kotlinReferences(
      javaFile: Path,
      offset: Int,
      includeDeclaration: Boolean,
      cancelChecker: ICancelChecker,
  ): ReferenceResult?

  fun kotlinTopLevelTypes(modulePath: String, cancelChecker: ICancelChecker): Set<String>?

  /**
   * Returns the Kotlin source files that declare [qualifiedName] from the shared metadata.
   *
   * Included only for JVM-visible top-level classes and file facades. Multifile facades may return
   * more than one file.
   */
  fun kotlinTypeSourceFiles(modulePath: String, qualifiedName: String): List<Path> = emptyList()

  /** Monotonic revision of the shared source metadata that backs [kotlinTopLevelTypes]. */
  fun metadataRevision(): Long

  fun kotlinOwner(
      javaFile: Path,
      offset: Int,
      ownerQualifiedName: String,
      cancelChecker: ICancelChecker,
  ): Boolean = false

  fun kotlinMembers(
      javaFile: Path,
      offset: Int,
      ownerQualifiedName: String,
      staticContext: Boolean,
      prefix: String,
      addParentheses: Boolean,
      cancelChecker: ICancelChecker,
  ): List<CompletionItem>

  fun kotlinInnerClasses(
      javaFile: Path,
      offset: Int,
      ownerQualifiedName: String,
      prefix: String,
      cancelChecker: ICancelChecker,
  ): List<CompletionItem>
}

object JavaPsiInterop {
  private class State(val resolver: KotlinSourceResolver?, val generation: Long)

  // Resolver identity and generation are published as one immutable state. Two separate atomics
  // could pair a new resolver with the previous generation, so a caller that builds a cached result
  // from the older resolver would publish it under the newer generation.
  private val state = AtomicReference(State(null, 0))

  @JvmStatic
  fun register(value: KotlinSourceResolver) {
    while (true) {
      val current = state.get()
      if (state.compareAndSet(current, State(value, current.generation + 1))) return
    }
  }

  @JvmStatic
  fun unregister(value: KotlinSourceResolver) {
    while (true) {
      val current = state.get()
      if (current.resolver !== value) return
      if (state.compareAndSet(current, State(null, current.generation + 1))) return
    }
  }

  /**
   * Identity of the type source that [kotlinTopLevelTypesSnapshot] would use right now.
   *
   * Never use it as a substitute for the snapshot stamps: an enumeration result is only keyed by
   * the stamps captured together with it, otherwise a replacement between the two reads would be
   * published under the newer identity.
   */
  @JvmStatic
  fun kotlinTopLevelTypesStamp(): KotlinTopLevelTypesStamp {
    while (true) {
      val current = state.get()
      val resolver = current.resolver
          ?: return KotlinTopLevelTypesStamp(current.generation, NO_METADATA_REVISION)
      val metadataRevision = resolver.metadataRevision()
      // A replacement can happen while the revision is read. Re-read the state so the returned
      // generation and revision always belong to the same resolver instance.
      if (state.get() !== current) continue
      return KotlinTopLevelTypesStamp(current.generation, metadataRevision)
    }
  }

  @JvmStatic
  fun isAvailable(): Boolean = state.get().resolver != null

  /**
   * Enumerates Java-visible Kotlin top-level types together with the resolver generation and shared
   * metadata revision that produced them. Callers key cached results by that triple, so neither a
   * runtime replacement nor an asynchronously indexed source file can be served a stale set.
   */
  @JvmStatic
  fun kotlinTopLevelTypesSnapshot(
      modulePath: String,
      cancelChecker: ICancelChecker,
  ): KotlinTopLevelTypesResult {
    val current = state.get()
    val resolver = current.resolver
        ?: return KotlinTopLevelTypesResult(
            null,
            current.generation,
            NO_METADATA_REVISION,
            null,
        )
    var metadataRevision = NO_METADATA_REVISION
    return try {
      metadataRevision = resolver.metadataRevision()
      KotlinTopLevelTypesResult(
          resolver.kotlinTopLevelTypes(modulePath, cancelChecker),
          current.generation,
          metadataRevision,
          null,
      )
    } catch (error: CancellationException) {
      // Cancellation is the caller's control flow, not an unavailable type source.
      throw error
    } catch (error: Exception) {
      KotlinTopLevelTypesResult(null, current.generation, metadataRevision, error)
    }
  }

  /** Result of one shared type enumeration, tagged with the identity that produced it. */
  class KotlinTopLevelTypesResult(
      @JvmField val types: Set<String>?,
      @JvmField val resolverGeneration: Long,
      @JvmField val metadataRevision: Long,
      @JvmField val error: Exception?,
  )

  /** Identity of a shared type source, read at one point in time. */
  class KotlinTopLevelTypesStamp(
      @JvmField val resolverGeneration: Long,
      @JvmField val metadataRevision: Long,
  )

  private const val NO_METADATA_REVISION = -1L

  @JvmStatic
  fun kotlinDefinition(javaFile: Path, offset: Int, cancelChecker: ICancelChecker): Location? {
    val current = state.get().resolver ?: return null
    return current.kotlinDefinition(javaFile, offset, cancelChecker)
  }

  @JvmStatic
  fun kotlinHover(javaFile: Path, offset: Int, cancelChecker: ICancelChecker): MarkupContent? {
    val current = state.get().resolver ?: return null
    return current.kotlinHover(javaFile, offset, cancelChecker)
  }

  @JvmStatic
  fun kotlinSignature(javaFile: Path, offset: Int, cancelChecker: ICancelChecker): SignatureHelp? {
    val current = state.get().resolver ?: return null
    return current.kotlinSignature(javaFile, offset, cancelChecker)
  }

  @JvmStatic
  fun kotlinReferences(
      javaFile: Path,
      offset: Int,
      includeDeclaration: Boolean,
      cancelChecker: ICancelChecker,
  ): ReferenceResult? {
    val current = state.get().resolver ?: return null
    return current.kotlinReferences(javaFile, offset, includeDeclaration, cancelChecker)
  }

  @JvmStatic
  fun kotlinTopLevelTypes(modulePath: String, cancelChecker: ICancelChecker): Set<String>? {
    val current = state.get().resolver ?: return null
    return current.kotlinTopLevelTypes(modulePath, cancelChecker)
  }

  @JvmStatic
  fun kotlinTypeSourceFiles(modulePath: String, qualifiedName: String): List<Path> {
    val current = state.get().resolver ?: return emptyList()
    return current.kotlinTypeSourceFiles(modulePath, qualifiedName)
  }

  @JvmStatic
  fun kotlinOwner(
      javaFile: Path,
      offset: Int,
      ownerQualifiedName: String,
      cancelChecker: ICancelChecker,
  ): Boolean {
    val current = state.get().resolver ?: return false
    return current.kotlinOwner(javaFile, offset, ownerQualifiedName, cancelChecker)
  }

  @JvmStatic
  fun kotlinMembers(
      javaFile: Path,
      offset: Int,
      ownerQualifiedName: String,
      staticContext: Boolean,
      prefix: String,
      addParentheses: Boolean,
      cancelChecker: ICancelChecker,
  ): List<CompletionItem> {
    val current = state.get().resolver ?: return emptyList()
    return current.kotlinMembers(
        javaFile,
        offset,
        ownerQualifiedName,
        staticContext,
        prefix,
        addParentheses,
        cancelChecker,
    )
  }

  @JvmStatic
  fun kotlinInnerClasses(
      javaFile: Path,
      offset: Int,
      ownerQualifiedName: String,
      prefix: String,
      cancelChecker: ICancelChecker,
  ): List<CompletionItem> {
    val current = state.get().resolver ?: return emptyList()
    return current.kotlinInnerClasses(javaFile, offset, ownerQualifiedName, prefix, cancelChecker)
  }
}
