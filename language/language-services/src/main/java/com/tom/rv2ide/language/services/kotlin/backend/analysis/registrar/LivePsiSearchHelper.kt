package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import org.jetbrains.kotlin.com.intellij.concurrency.AsyncFuture
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiReference
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.search.LocalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.search.PsiNonJavaFileReferenceProcessor
import org.jetbrains.kotlin.com.intellij.psi.search.PsiSearchHelper
import org.jetbrains.kotlin.com.intellij.psi.search.PsiSearchRequest
import org.jetbrains.kotlin.com.intellij.psi.search.RequestResultProcessor
import org.jetbrains.kotlin.com.intellij.psi.search.SearchRequestCollector
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope
import org.jetbrains.kotlin.com.intellij.psi.search.TextOccurenceProcessor
import org.jetbrains.kotlin.com.intellij.psi.search.UsageSearchContext
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import java.nio.charset.StandardCharsets

internal class LivePsiSearchHelper(
    private val files: LiveProjectFiles,
) : PsiSearchHelper {
  override fun isCheapEnoughToSearch(
      name: String,
      scope: GlobalSearchScope,
      psiFileToIgnoreOccurrencesIn: PsiFile?,
      progress: ProgressIndicator?,
  ): PsiSearchHelper.SearchCostResult {
    if (name.isEmpty()) return PsiSearchHelper.SearchCostResult.ZERO_OCCURRENCES
    val identifier = Regex("(?<![A-Za-z0-9_$])${Regex.escape(name)}(?![A-Za-z0-9_$])")
    val ignored = psiFileToIgnoreOccurrencesIn?.virtualFile
    var matchingFiles = 0
    val completed = files.process(scope, null, Processor { file ->
      progress?.checkCanceled()
      ProgressManager.checkCanceled()
      if (file.isDirectory || file.fileType != JavaFileType.INSTANCE || file == ignored) return@Processor true
      val text = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
      if (identifier.containsMatchIn(text)) {
        matchingFiles++
        if (matchingFiles > MAX_FILES_TO_SEARCH) return@Processor false
      }
      true
    })
    return if (!completed || matchingFiles > MAX_FILES_TO_SEARCH) PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES
    else if (matchingFiles == 0) PsiSearchHelper.SearchCostResult.ZERO_OCCURRENCES
    else PsiSearchHelper.SearchCostResult.FEW_OCCURRENCES

  }

  override fun findCommentsContainingIdentifier(identifier: String, searchScope: SearchScope): Array<PsiElement> = unsupported()

  override fun processCommentsContainingIdentifier(
      identifier: String,
      searchScope: SearchScope,
      processor: Processor<in PsiElement>,
  ): Boolean = unsupported()

  override fun processCandidateFilesForText(
      scope: GlobalSearchScope,
      searchContext: Short,
      caseSensitively: Boolean,
      text: String,
      processor: Processor<in VirtualFile>,
  ): Boolean = unsupported()

  override fun findFilesWithPlainTextWords(word: String): Array<PsiFile> = unsupported()

  override fun processUsagesInNonJavaFiles(
      qName: String,
      processor: PsiNonJavaFileReferenceProcessor,
      searchScope: GlobalSearchScope,
  ): Boolean = unsupported()

  override fun processUsagesInNonJavaFiles(
      originalElement: PsiElement?,
      qName: String,
      processor: PsiNonJavaFileReferenceProcessor,
      searchScope: GlobalSearchScope,
  ): Boolean = unsupported()

  override fun getUseScope(element: PsiElement): SearchScope = element.useScope

  override fun processAllFilesWithWord(
      word: String,
      scope: GlobalSearchScope,
      processor: Processor<in PsiFile>,
      caseSensitively: Boolean,
  ): Boolean = unsupported()

  override fun processAllFilesWithWordInText(
      word: String,
      scope: GlobalSearchScope,
      processor: Processor<in PsiFile>,
      caseSensitively: Boolean,
  ): Boolean = unsupported()

  override fun processAllFilesWithWordInComments(
      word: String,
      scope: GlobalSearchScope,
      processor: Processor<in PsiFile>,
  ): Boolean = unsupported()

  override fun processAllFilesWithWordInLiterals(
      word: String,
      scope: GlobalSearchScope,
      processor: Processor<in PsiFile>,
  ): Boolean = unsupported()

  override fun processRequests(
      request: SearchRequestCollector,
      processor: Processor<in PsiReference>,
  ): Boolean {
    for (query in request.takeQueryRequests()) {
      if (!query.runQuery()) return false
    }
    for (action in request.takeCustomSearchActions()) {
      if (!action.process(processor)) return false
    }
    for (search in request.takeSearchRequests()) {
      if (!processSearchRequest(search, processor)) return false
    }
    return true
  }

  private fun processSearchRequest(
      request: PsiSearchRequest,
      processor: Processor<in PsiReference>,
  ): Boolean {
    if ((request.searchContext.toInt() and UsageSearchContext.IN_CODE.toInt()) == 0) return true
    val word = request.word
    if (word.isEmpty()) return true
    val scope = request.searchScope
    if (scope is LocalSearchScope) {
      for (element in scope.scope) {
        val file = element.containingFile as? PsiJavaFile ?: continue
        if (!processPsiFile(file, word, request.caseSensitive, request.processor, processor, element)) return false
      }
      return true
    }
    val global = scope as? GlobalSearchScope ?: return true
    val project = global.project ?: return true
    val psiManager = PsiManager.getInstance(project)
    return files.process(global, null, Processor { virtualFile ->
      ProgressManager.checkCanceled()
      if (virtualFile.isDirectory || virtualFile.fileType != JavaFileType.INSTANCE) return@Processor true
      val file = psiManager.findFile(virtualFile) as? PsiJavaFile ?: return@Processor true
      processPsiFile(file, word, request.caseSensitive, request.processor, processor, null)
    })
  }

  private fun processPsiFile(
      file: PsiJavaFile,
      word: String,
      caseSensitive: Boolean,
      resultProcessor: RequestResultProcessor,
      consumer: Processor<in PsiReference>,
      scopeElement: PsiElement?,
  ): Boolean {
    var completed = true
    val matcher = if (caseSensitive) Regex("(?<![A-Za-z0-9_$])${Regex.escape(word)}(?![A-Za-z0-9_$])")
    else Regex("(?i)(?<![A-Za-z0-9_$])${Regex.escape(word)}(?![A-Za-z0-9_$])")
    file.accept(object : JavaRecursiveElementWalkingVisitor() {
      override fun visitReferenceElement(element: PsiJavaCodeReferenceElement) {
        if (!completed) return
        ProgressManager.checkCanceled()
        val name = element.referenceNameElement ?: return super.visitReferenceElement(element)
        if (scopeElement != null && !isInScope(scopeElement, element)) {
          return super.visitReferenceElement(element)
        }
        if (matcher.matches(name.text)) {
          val offset = name.textRange.startOffset - element.textRange.startOffset
          completed = resultProcessor.processTextOccurrence(element, offset, consumer)
        }
        if (completed) super.visitReferenceElement(element)
      }
    })
    return completed
  }

  private fun isInScope(scopeElement: PsiElement, element: PsiElement): Boolean {
    if (scopeElement === element) return true
    val scopeRange = scopeElement.textRange ?: return false
    val elementRange = element.textRange ?: return false
    return scopeRange.contains(elementRange)
  }

  override fun processRequestsAsync(
      request: SearchRequestCollector,
      processor: Processor<in PsiReference>,
  ): AsyncFuture<Boolean> = unsupported()

  override fun processElementsWithWord(
      processor: TextOccurenceProcessor,
      searchScope: SearchScope,
      text: String,
      searchContext: Short,
      caseSensitive: Boolean,
  ): Boolean = unsupported()

  override fun processElementsWithWord(
      processor: TextOccurenceProcessor,
      searchScope: SearchScope,
      text: String,
      searchContext: Short,
      caseSensitive: Boolean,
      processInjectedPsi: Boolean,
  ): Boolean = unsupported()

  override fun hasIdentifierInFile(psiFile: PsiFile, name: String): Boolean =
      Regex("(?<![A-Za-z0-9_$])${Regex.escape(name)}(?![A-Za-z0-9_$])").containsMatchIn(psiFile.text)

  override fun processElementsWithWordAsync(
      processor: TextOccurenceProcessor,
      searchScope: SearchScope,
      text: String,
      searchContext: Short,
      caseSensitive: Boolean,
  ): AsyncFuture<Boolean> = unsupported()

  private fun <T> unsupported(): T =
      throw UnsupportedOperationException("Live PSI search operation is not implemented")

  private companion object {
    const val MAX_FILES_TO_SEARCH = 10
  }
}