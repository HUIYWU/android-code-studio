package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getImportedSimpleNameByImportAlias
import org.jetbrains.kotlin.psi.psiUtil.getSuperNames
import java.nio.file.Path

internal enum class KotlinSourceDeclarationKind {
  CLASS_LIKE,
  TYPE_ALIAS,
  TOP_LEVEL_FUNCTION,
  TOP_LEVEL_PROPERTY,
  FACADE,
  SCRIPT,
}

internal sealed interface KotlinSourceDeclarationKey {
  data class ClassLike(val classId: ClassId) : KotlinSourceDeclarationKey
  data class TypeAlias(val classId: ClassId) : KotlinSourceDeclarationKey
  data class TopLevelCallable(val callableId: CallableId) : KotlinSourceDeclarationKey
  data class Facade(val fqName: FqName) : KotlinSourceDeclarationKey
  data class Script(val fqName: FqName) : KotlinSourceDeclarationKey
}

internal data class KotlinSourceDeclarationRecord(
    val kind: KotlinSourceDeclarationKind,
    val key: KotlinSourceDeclarationKey,
    val path: Path,
    val identity: LiveFileIdentity,
    val startOffset: Int,
    val name: String,
    val directSupertypeNames: Set<String> = emptySet(),
    val inheritableAliasedNames: Set<String> = emptySet(),
)

internal data class KotlinSourceFileMetadata(
    val path: Path,
    val ownerModulePath: String?,
    val packageName: String,
    val sourceKind: KotlinSourceKind,
    val identity: LiveFileIdentity,
    val indexed: Boolean,
    val topLevelClassifierNames: Set<String>,
    val topLevelCallableNames: Set<String>,
    val declarations: List<KotlinSourceDeclarationRecord>,
)

internal class KotlinSourceMetadataIndex {
  private val lock = Any()
  private val records = mutableMapOf<Path, KotlinSourceFileMetadata>()
  private val packageToPaths = mutableMapOf<String, MutableSet<Path>>()
  private val classLikeById = mutableMapOf<ClassId, MutableSet<KotlinSourceDeclarationRecord>>()
  private val typeAliasById = mutableMapOf<ClassId, MutableSet<KotlinSourceDeclarationRecord>>()
  private val topLevelCallableById = mutableMapOf<CallableId, MutableSet<KotlinSourceDeclarationRecord>>()
  private val facadeByFqName = mutableMapOf<FqName, MutableSet<KotlinSourceDeclarationRecord>>()
  private val scriptByFqName = mutableMapOf<FqName, MutableSet<KotlinSourceDeclarationRecord>>()
  private val classesBySupertypeNameIndex = mutableMapOf<String, MutableSet<KotlinSourceDeclarationRecord>>()
  private val typeAliasesByAliasedNameIndex = mutableMapOf<String, MutableSet<KotlinSourceDeclarationRecord>>()

  fun replace(
      path: Path,
      file: KtFile,
      identity: LiveFileIdentity,
      sourceRoot: KotlinSourceRootDescriptor?,
  ) {
    val normalized = path.normalize()
    synchronized(lock) {
      removeLocked(normalized)
      val declarations = declarationsOf(normalized, identity, file)
      val metadata = KotlinSourceFileMetadata(
          path = normalized,
          ownerModulePath = sourceRoot?.ownerModulePath,
          packageName = file.packageFqName.asString(),
          sourceKind = sourceRoot?.sourceKind ?: KotlinSourceKind.UNKNOWN,
          identity = identity,
          indexed = true,
          topLevelClassifierNames = file.declarations.asSequence()
              .filterIsInstance<KtClassLikeDeclaration>()
              .mapNotNull { it.name }
              .toSet(),
          topLevelCallableNames = file.declarations.asSequence()
              .filterIsInstance<KtCallableDeclaration>()
              .mapNotNull { it.name }
              .toSet(),
          declarations = declarations,
      )
      records[normalized] = metadata
      packageToPaths.getOrPut(metadata.packageName) { linkedSetOf() }.add(normalized)
      declarations.forEach(::addDeclarationLocked)
    }
  }

  fun remove(path: Path) {
    synchronized(lock) {
      removeLocked(path.normalize())
    }
  }

  fun filesForPackage(packageName: String): Set<Path> = synchronized(lock) {
    packageToPaths[packageName]?.toSet().orEmpty()
  }

  fun packageExists(packageName: String): Boolean = synchronized(lock) {
    packageName.isEmpty() || packageToPaths.containsKey(packageName)
  }

  fun subpackageNames(packageName: String): Set<String> = synchronized(lock) {
    val prefix = if (packageName.isEmpty()) "" else "$packageName."
    packageToPaths.keys.asSequence()
        .filter { it.startsWith(prefix) && it.length > prefix.length }
        .map { it.removePrefix(prefix).substringBefore('.') }
        .toSet()
  }

  fun metadata(path: Path): KotlinSourceFileMetadata? = synchronized(lock) {
    records[path.normalize()]
  }

  fun classLikeDeclarations(classId: ClassId): List<KotlinSourceDeclarationRecord> = synchronized(lock) {
    classLikeById[classId]?.toList().orEmpty()
  }

  fun typeAliasDeclarations(classId: ClassId): List<KotlinSourceDeclarationRecord> = synchronized(lock) {
    typeAliasById[classId]?.toList().orEmpty()
  }

  fun topLevelCallableDeclarations(callableId: CallableId): List<KotlinSourceDeclarationRecord> = synchronized(lock) {
    topLevelCallableById[callableId]?.toList().orEmpty()
  }

  fun facadeDeclarations(fqName: FqName): List<KotlinSourceDeclarationRecord> = synchronized(lock) {
    facadeByFqName[fqName]?.toList().orEmpty()
  }

  fun scriptDeclarations(fqName: FqName): List<KotlinSourceDeclarationRecord> = synchronized(lock) {
    scriptByFqName[fqName]?.toList().orEmpty()
  }

  fun classesBySupertypeName(name: String): List<KotlinSourceDeclarationRecord> = synchronized(lock) {
    classesBySupertypeNameIndex[name]?.toList().orEmpty()
  }

  fun typeAliasesByAliasedName(name: String): List<KotlinSourceDeclarationRecord> = synchronized(lock) {
    typeAliasesByAliasedNameIndex[name]?.toList().orEmpty()
  }

  fun paths(): Set<Path> = synchronized(lock) {
    records.keys.toSet()
  }

  private fun removeLocked(path: Path) {
    records.remove(path)?.let { record ->
      packageToPaths[record.packageName]?.let { paths ->
        paths.remove(path)
        if (paths.isEmpty()) packageToPaths.remove(record.packageName)
      }
      record.declarations.forEach(::removeDeclarationLocked)
    }
  }

  private fun addDeclarationLocked(record: KotlinSourceDeclarationRecord) {
    val target = when (val key = record.key) {
      is KotlinSourceDeclarationKey.ClassLike -> classLikeById.getOrPut(key.classId) { linkedSetOf() }
      is KotlinSourceDeclarationKey.TypeAlias -> typeAliasById.getOrPut(key.classId) { linkedSetOf() }
      is KotlinSourceDeclarationKey.TopLevelCallable -> topLevelCallableById.getOrPut(key.callableId) { linkedSetOf() }
      is KotlinSourceDeclarationKey.Facade -> facadeByFqName.getOrPut(key.fqName) { linkedSetOf() }
      is KotlinSourceDeclarationKey.Script -> scriptByFqName.getOrPut(key.fqName) { linkedSetOf() }
    }
    target.add(record)
    record.directSupertypeNames.forEach { name ->
      classesBySupertypeNameIndex.getOrPut(name) { linkedSetOf() }.add(record)
    }
    record.inheritableAliasedNames.forEach { name ->
      typeAliasesByAliasedNameIndex.getOrPut(name) { linkedSetOf() }.add(record)
    }
  }

  private fun removeDeclarationLocked(record: KotlinSourceDeclarationRecord) {
    when (val declarationKey = record.key) {
      is KotlinSourceDeclarationKey.ClassLike -> removeFromMap(classLikeById, declarationKey.classId, record)
      is KotlinSourceDeclarationKey.TypeAlias -> removeFromMap(typeAliasById, declarationKey.classId, record)
      is KotlinSourceDeclarationKey.TopLevelCallable -> removeFromMap(topLevelCallableById, declarationKey.callableId, record)
      is KotlinSourceDeclarationKey.Facade -> removeFromMap(facadeByFqName, declarationKey.fqName, record)
      is KotlinSourceDeclarationKey.Script -> removeFromMap(scriptByFqName, declarationKey.fqName, record)
    }
    record.directSupertypeNames.forEach { name -> removeFromMap(classesBySupertypeNameIndex, name, record) }
    record.inheritableAliasedNames.forEach { name -> removeFromMap(typeAliasesByAliasedNameIndex, name, record) }
  }

  private fun <K> removeFromMap(
      map: MutableMap<K, MutableSet<KotlinSourceDeclarationRecord>>,
      key: K,
      record: KotlinSourceDeclarationRecord,
  ) {
    map[key]?.let { declarations ->
      declarations.remove(record)
      if (declarations.isEmpty()) map.remove(key)
    }
  }

  private fun declarationsOf(
      path: Path,
      identity: LiveFileIdentity,
      file: KtFile,
  ): List<KotlinSourceDeclarationRecord> {
    val declarations = mutableListOf<KotlinSourceDeclarationRecord>()
    val facadeFqName = file.javaFileFacadeFqName
    declarations += KotlinSourceDeclarationRecord(
        kind = KotlinSourceDeclarationKind.FACADE,
        key = KotlinSourceDeclarationKey.Facade(facadeFqName),
        path = path,
        identity = identity,
        startOffset = 0,
        name = facadeFqName.asString(),
    )
    file.script?.let { script ->
      val scriptFqName = script.fqName
      declarations += KotlinSourceDeclarationRecord(
          kind = KotlinSourceDeclarationKind.SCRIPT,
          key = KotlinSourceDeclarationKey.Script(scriptFqName),
          path = path,
          identity = identity,
          startOffset = script.textRange?.startOffset ?: 0,
          name = scriptFqName.asString(),
      )
    }
    file.accept(object : KtTreeVisitorVoid() {
      override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        val classId = classOrObject.getClassId()
        val name = classOrObject.name
        val startOffset = classOrObject.textRange?.startOffset
        if (classId != null && name != null && startOffset != null) {
          declarations += KotlinSourceDeclarationRecord(
              kind = KotlinSourceDeclarationKind.CLASS_LIKE,
              key = KotlinSourceDeclarationKey.ClassLike(classId),
              path = path,
              identity = identity,
              startOffset = startOffset,
              name = name,
              directSupertypeNames = classOrObject.getSuperNames().toSet(),
          )
        }
        super.visitClassOrObject(classOrObject)
      }

      override fun visitTypeAlias(typeAlias: KtTypeAlias) {
        val classId = typeAlias.getClassId()
        val name = typeAlias.name
        val startOffset = typeAlias.textRange?.startOffset
        if (classId != null && name != null && startOffset != null) {
          declarations += KotlinSourceDeclarationRecord(
              kind = KotlinSourceDeclarationKind.TYPE_ALIAS,
              key = KotlinSourceDeclarationKey.TypeAlias(classId),
              path = path,
              identity = identity,
              startOffset = startOffset,
              name = name,
              inheritableAliasedNames = typeAlias.getTypeReference()?.typeElement?.let(::findInheritableSimpleNames).orEmpty().toSet(),
          )
        }
        super.visitTypeAlias(typeAlias)
      }

      override fun visitNamedFunction(function: KtNamedFunction) {
        if (function.isTopLevel) {
          addTopLevelCallable(function, KotlinSourceDeclarationKind.TOP_LEVEL_FUNCTION)
        }
        super.visitNamedFunction(function)
      }

      override fun visitProperty(property: KtProperty) {
        if (property.isTopLevel) {
          addTopLevelCallable(property, KotlinSourceDeclarationKind.TOP_LEVEL_PROPERTY)
        }
        super.visitProperty(property)
      }

      private fun addTopLevelCallable(
          declaration: KtCallableDeclaration,
          kind: KotlinSourceDeclarationKind,
      ) {
        val name = declaration.nameAsName ?: return
        val startOffset = declaration.textRange?.startOffset ?: return
        declarations += KotlinSourceDeclarationRecord(
            kind = kind,
            key = KotlinSourceDeclarationKey.TopLevelCallable(CallableId(file.packageFqName, name)),
            path = path,
            identity = identity,
            startOffset = startOffset,
            name = name.asString(),
        )
      }
    })
    return declarations
  }
}

private fun findInheritableSimpleNames(typeElement: KtTypeElement): List<String> = when (typeElement) {
  is KtUserType -> {
    val referenceName = typeElement.referencedName ?: return emptyList()
    buildList {
      add(referenceName)
      val file = typeElement.containingKtFile
      if (!file.isCompiled) {
        getImportedSimpleNameByImportAlias(file, referenceName)?.let(::add)
      }
    }
  }
  is KtNullableType -> typeElement.innerType?.let(::findInheritableSimpleNames).orEmpty()
  else -> emptyList()
}