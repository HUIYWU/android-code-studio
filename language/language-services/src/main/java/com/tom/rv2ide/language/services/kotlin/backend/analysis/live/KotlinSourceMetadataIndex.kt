package com.tom.rv2ide.language.services.kotlin.backend.analysis.live

import com.tom.rv2ide.language.services.kotlin.backend.analysis.semantic.ReferenceConventions
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUnaryExpression
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

internal enum class KotlinSourceClassifierVisibility {
  PUBLIC,
  INTERNAL,
  PRIVATE,
}

internal enum class KotlinSourceExtensionKind {
  FUNCTION,
  PROPERTY,
}

internal data class KotlinSourceExtension(
    val name: String,
    val receiverType: String,
    val kind: KotlinSourceExtensionKind,
    val visibility: KotlinSourceClassifierVisibility,
)

internal data class KotlinSourceImportAlias(
    val fqName: String,
    val localName: String,
)

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
    val topLevelClassifierVisibility: Map<String, KotlinSourceClassifierVisibility>,
    val topLevelCallableNames: Set<String>,
    val topLevelImportableFunctionNames: Set<String>,
    val topLevelImportablePropertyNames: Set<String>,
    val topLevelCallableVisibility: Map<String, KotlinSourceClassifierVisibility>,
    val topLevelExtensions: List<KotlinSourceExtension>,
    val declarations: List<KotlinSourceDeclarationRecord>,
    val usageNames: Set<String>,
    val conventionMask: Int,
    val aliasImports: List<KotlinSourceImportAlias>,
) {
  fun isClassifierImportable(name: String, currentModulePath: String?): Boolean =
      isImportable(topLevelClassifierVisibility[name], currentModulePath)

  fun isCallableImportable(name: String, currentModulePath: String?): Boolean =
      isImportable(topLevelCallableVisibility[name], currentModulePath)

  fun isExtensionImportable(
      extension: KotlinSourceExtension,
      currentModulePath: String?,
  ): Boolean = isImportable(extension.visibility, currentModulePath)

  private fun isImportable(
      visibility: KotlinSourceClassifierVisibility?,
      currentModulePath: String?,
  ): Boolean =
      when (visibility ?: KotlinSourceClassifierVisibility.PUBLIC) {
        KotlinSourceClassifierVisibility.PRIVATE -> false
        KotlinSourceClassifierVisibility.INTERNAL -> ownerModulePath == currentModulePath
        KotlinSourceClassifierVisibility.PUBLIC -> true
      }
}

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
  private val usageNameToPaths = mutableMapOf<String, MutableSet<Path>>()
  private val conventionPathsByBit = mutableMapOf<Int, MutableSet<Path>>()
  private val aliasNameByFqName = mutableMapOf<String, MutableMap<String, MutableSet<Path>>>()
  private val aliasImportPaths = mutableSetOf<Path>()
  private var revisionValue = 0L

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
      val usageSummary = usageSummaryOf(file)
      val classifierVisibility =
          file.declarations.asSequence()
              .filterIsInstance<KtClassLikeDeclaration>()
              .mapNotNull { declaration ->
                declaration.name?.let { it to declaration.declarationVisibility() }
              }
              .toMap()
      val importableFunctions =
          file.declarations.filterIsInstance<KtNamedFunction>()
              .filter { it.receiverTypeReference == null }
      val importableProperties =
          file.declarations.filterIsInstance<KtProperty>()
              .filter { it.receiverTypeReference == null }
      val callableVisibility =
          (importableFunctions.asSequence() + importableProperties.asSequence())
              .mapNotNull { declaration ->
                declaration.name?.let { it to declaration.declarationVisibility() }
              }
              .toMap()
      val extensions =
          buildList {
            file.declarations.filterIsInstance<KtNamedFunction>().forEach { function ->
              val receiver = simpleReceiverTypeName(function.receiverTypeReference)
              val name = function.name
              if (receiver != null && name != null) {
                add(
                    KotlinSourceExtension(
                        name,
                        receiver,
                        KotlinSourceExtensionKind.FUNCTION,
                        function.declarationVisibility(),
                    )
                )
              }
            }
            file.declarations.filterIsInstance<KtProperty>().forEach { property ->
              val receiver = simpleReceiverTypeName(property.receiverTypeReference)
              val name = property.name
              if (receiver != null && name != null) {
                add(
                    KotlinSourceExtension(
                        name,
                        receiver,
                        KotlinSourceExtensionKind.PROPERTY,
                        property.declarationVisibility(),
                    )
                )
              }
            }
          }
      val metadata = KotlinSourceFileMetadata(
          path = normalized,
          ownerModulePath = sourceRoot?.ownerModulePath,
          packageName = file.packageFqName.asString(),
          sourceKind = sourceRoot?.sourceKind ?: KotlinSourceKind.UNKNOWN,
          identity = identity,
          indexed = true,
          topLevelClassifierNames = classifierVisibility.keys,
          topLevelClassifierVisibility = classifierVisibility,
          topLevelCallableNames = file.declarations.asSequence()
              .filterIsInstance<KtCallableDeclaration>()
              .mapNotNull { it.name }
              .toSet(),
          topLevelImportableFunctionNames = importableFunctions.mapNotNull { it.name }.toSet(),
          topLevelImportablePropertyNames = importableProperties.mapNotNull { it.name }.toSet(),
          topLevelCallableVisibility = callableVisibility,
          topLevelExtensions = extensions,
          declarations = declarations,
          usageNames = usageSummary.names,
          conventionMask = usageSummary.conventionMask,
          aliasImports = usageSummary.aliases,
      )
      records[normalized] = metadata
      packageToPaths.getOrPut(metadata.packageName) { linkedSetOf() }.add(normalized)
      declarations.forEach(::addDeclarationLocked)
      usageSummary.names.forEach { name ->
        usageNameToPaths.getOrPut(name) { linkedSetOf() }.add(normalized)
      }
      forEachConventionBit(usageSummary.conventionMask) { bit ->
        conventionPathsByBit.getOrPut(bit) { linkedSetOf() }.add(normalized)
      }
      usageSummary.aliases.forEach { alias ->
        aliasNameByFqName
            .getOrPut(alias.fqName) { mutableMapOf() }
            .getOrPut(alias.localName) { linkedSetOf() }
            .add(normalized)
        aliasImportPaths.add(normalized)
      }
      revisionValue++
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

  fun pathsReferencing(names: Set<String>): Set<Path> = synchronized(lock) {
    if (names.isEmpty()) return@synchronized emptySet()
    val result = linkedSetOf<Path>()
    names.forEach { name -> usageNameToPaths[name]?.let { result.addAll(it) } }
    result
  }

  fun pathsWithConventions(mask: Int): Set<Path> = synchronized(lock) {
    if (mask == 0) return@synchronized emptySet()
    val result = linkedSetOf<Path>()
    forEachConventionBit(mask) { bit ->
      conventionPathsByBit[bit]?.let { result.addAll(it) }
    }
    result
  }

  fun pathsWithAliasImports(): Set<Path> = synchronized(lock) {
    aliasImportPaths.toSet()
  }

  fun aliasNamesFor(fqNames: Set<String>): Set<String> = synchronized(lock) {
    if (fqNames.isEmpty()) return@synchronized emptySet()
    val result = linkedSetOf<String>()
    fqNames.forEach { fqName ->
      aliasNameByFqName[fqName]?.forEach { (localName, paths) ->
        if (paths.isNotEmpty()) result.add(localName)
      }
    }
    result
  }

  fun revision(): Long = synchronized(lock) { revisionValue }

  private fun removeLocked(path: Path) {
    records.remove(path)?.let { record ->
      packageToPaths[record.packageName]?.let { paths ->
        paths.remove(path)
        if (paths.isEmpty()) packageToPaths.remove(record.packageName)
      }
      record.declarations.forEach(::removeDeclarationLocked)
      record.usageNames.forEach { name ->
        removePathFromSet(usageNameToPaths, name, path)
      }
      forEachConventionBit(record.conventionMask) { bit ->
        removePathFromSet(conventionPathsByBit, bit, path)
      }
      record.aliasImports.forEach { alias ->
        aliasNameByFqName[alias.fqName]?.let { byAlias ->
          byAlias[alias.localName]?.let { paths ->
            paths.remove(path)
            if (paths.isEmpty()) byAlias.remove(alias.localName)
          }
          if (byAlias.isEmpty()) aliasNameByFqName.remove(alias.fqName)
        }
      }
      if (record.aliasImports.isNotEmpty()) {
        aliasImportPaths.remove(path)
      }
      revisionValue++
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

  private fun <K> removePathFromSet(
      map: MutableMap<K, MutableSet<Path>>,
      key: K,
      path: Path,
  ) {
    map[key]?.let { paths ->
      paths.remove(path)
      if (paths.isEmpty()) map.remove(key)
    }
  }

  private fun forEachConventionBit(mask: Int, action: (Int) -> Unit) {
    var bit = 1
    while (bit <= ReferenceConventions.ALL) {
      if (mask and bit != 0) action(bit)
      bit = bit shl 1
    }
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

  private data class KotlinSourceUsageSummary(
      val names: Set<String>,
      val conventionMask: Int,
      val aliases: List<KotlinSourceImportAlias>,
  )

  private fun usageSummaryOf(file: KtFile): KotlinSourceUsageSummary {
    val names = linkedSetOf<String>()
    val aliases =
        buildList<KotlinSourceImportAlias> {
          file.importDirectives.forEach { directive ->
            if (directive.isAllUnder) return@forEach
            val fqName = directive.importedFqName?.asString() ?: return@forEach
            val aliasName = directive.aliasName ?: return@forEach
            names.add(aliasName)
            add(KotlinSourceImportAlias(fqName, aliasName))
          }
        }
    var conventionMask = 0
    file.accept(
        object : KtTreeVisitorVoid() {
          override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
            expression.getReferencedName()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
            super.visitSimpleNameExpression(expression)
          }

          override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
            conventionMask = conventionMask or ReferenceConventions.ARRAY_ACCESS
            super.visitArrayAccessExpression(expression)
          }

          override fun visitBinaryExpression(expression: KtBinaryExpression) {
            conventionMask = conventionMask or ReferenceConventions.OPERATOR
            super.visitBinaryExpression(expression)
          }

          override fun visitUnaryExpression(expression: KtUnaryExpression) {
            conventionMask = conventionMask or ReferenceConventions.OPERATOR
            super.visitUnaryExpression(expression)
          }

          override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
            conventionMask = conventionMask or ReferenceConventions.DESTRUCTURING
            super.visitDestructuringDeclarationEntry(entry)
          }

          override fun visitForExpression(expression: KtForExpression) {
            conventionMask = conventionMask or ReferenceConventions.FOR_LOOP
            super.visitForExpression(expression)
          }

          override fun visitPropertyDelegate(delegate: KtPropertyDelegate) {
            conventionMask = conventionMask or ReferenceConventions.DELEGATE
            super.visitPropertyDelegate(delegate)
          }

          override fun visitCallExpression(expression: KtCallExpression) {
            conventionMask = conventionMask or ReferenceConventions.INVOKE
            super.visitCallExpression(expression)
          }
        }
    )
    return KotlinSourceUsageSummary(names, conventionMask, aliases)
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

private fun KtDeclaration.declarationVisibility(): KotlinSourceClassifierVisibility =
    when {
      hasModifier(KtTokens.PRIVATE_KEYWORD) -> KotlinSourceClassifierVisibility.PRIVATE
      hasModifier(KtTokens.INTERNAL_KEYWORD) -> KotlinSourceClassifierVisibility.INTERNAL
      else -> KotlinSourceClassifierVisibility.PUBLIC
    }

private fun simpleReceiverTypeName(reference: KtTypeReference?): String? {
  val text = reference?.text?.trim() ?: return null
  if (text.isEmpty()) return null
  if (text.any { it == '<' || it == '>' || it == '?' || it == '.' || it == ',' || it.isWhitespace() }) {
    return null
  }
  return text
}

internal fun KotlinSourceMetadataIndex.ownerModulePathOf(file: KtFile): String? =
    file.liveBackingPath?.let { metadata(it)?.ownerModulePath }

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