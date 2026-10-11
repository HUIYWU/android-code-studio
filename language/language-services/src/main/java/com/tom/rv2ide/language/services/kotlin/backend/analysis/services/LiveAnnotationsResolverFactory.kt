package com.tom.rv2ide.language.services.kotlin.backend.analysis.services

import com.tom.rv2ide.language.services.kotlin.backend.analysis.environment.analysisRead
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.KotlinSourceMetadataIndex
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileIndex
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolver
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.declarationRecursiveVisitor

internal class LiveAnnotationsResolverFactory : KotlinAnnotationsResolverFactory {
  private lateinit var project: Project
  private lateinit var liveIndex: LiveSourceFileIndex
  private lateinit var metadataIndex: KotlinSourceMetadataIndex

  fun setup(project: MockProject, liveIndex: LiveSourceFileIndex, metadataIndex: KotlinSourceMetadataIndex) {
    this.project = project
    this.liveIndex = liveIndex
    this.metadataIndex = metadataIndex
  }

  override fun createAnnotationResolver(searchScope: GlobalSearchScope): KotlinAnnotationsResolver =
      LiveAnnotationsResolver(project, liveIndex, metadataIndex, searchScope)
}

private class LiveAnnotationsResolver(
    private val project: Project,
    private val liveIndex: LiveSourceFileIndex,
    private val metadataIndex: KotlinSourceMetadataIndex,
    private val scope: GlobalSearchScope,
) : KotlinAnnotationsResolver {
  override fun declarationsByAnnotation(annotationClassId: ClassId): Set<KtAnnotated> =
      allDeclarations().filterTo(linkedSetOf()) { annotationClassId in annotationsOnDeclaration(it) }

  override fun annotationsOnDeclaration(declaration: KtAnnotated): Set<ClassId> =
      declaration.annotationEntries.mapNotNull { entry ->
        val userType = entry.typeReference?.typeElement as? KtUserType ?: return@mapNotNull null
        val name = userType.referencedName ?: return@mapNotNull null
        val fqName = if (name.contains('.')) name else {
          val imported = declaration.containingKtFile.importDirectives.firstOrNull {
            it.importedName?.asString() == name
          }?.importedFqName?.asString()
         imported ?: declaration.containingKtFile.packageFqName.child(Name.identifier(name)).asString()

        }
        ClassId.topLevel(FqName(fqName))
      }.toSet()

  private fun allDeclarations(): List<KtDeclaration> = buildList {
    metadataIndex.paths().forEach { path ->
      val file = liveIndex.getCurrentKtFile(path) ?: return@forEach
      val virtualFile = file.virtualFile ?: return@forEach
      if (virtualFile !in scope) return@forEach
      project.analysisRead {
        file.accept(declarationRecursiveVisitor { declaration ->
          add(declaration)
        })
      }
    }
  }
}