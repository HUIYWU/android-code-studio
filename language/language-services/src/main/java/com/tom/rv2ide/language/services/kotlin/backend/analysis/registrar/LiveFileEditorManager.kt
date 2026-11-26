package com.tom.rv2ide.language.services.kotlin.backend.analysis.registrar

import com.android.javax.swing.JComponent
import com.tom.rv2ide.language.services.kotlin.backend.analysis.live.LiveSourceFileRegistry
import com.tom.rv2ide.projects.FileManager
import java.nio.file.Path
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.editor.Caret
import org.jetbrains.kotlin.com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.EditorDataProvider
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileEditor
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileEditorComposite
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileEditorManager
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileEditorNavigatable
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.OpenFileDescriptor
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager

internal class LiveFileEditorManager(
    private val analysisProject: Project,
    private val sourceRegistry: LiveSourceFileRegistry,
) : FileEditorManager() {
  override fun getProject(): Project = analysisProject

  override fun getOpenFiles(): Array<VirtualFile> {
    val files = FileManager.getActiveDocumentFiles().asSequence()
        .filter { sourceRegistry.moduleForPath(it) != null }
        .map { path ->
          sourceRegistry.virtualFile(path)
              ?: requireNotNull(VirtualFileManager.getInstance().findFileByNioPath(path)) {
                "Active analysis document has no virtual file: $path"
              }
        }
        .toList()
    return files.toTypedArray()
  }

  override fun getOpenFilesWithRemotes(): List<VirtualFile> = openFiles.toList()

  override fun isFileOpen(file: VirtualFile): Boolean {
    val path = sourceRegistry.pathForVirtualFile(file) ?: Path.of(file.path).normalize()
    return sourceRegistry.moduleForPath(path) != null && FileManager.isActive(path)
  }

  override fun getComposite(file: VirtualFile): FileEditorComposite? = null
  override fun getSelectedTextEditor(): Editor? = null
  override fun getSelectedFiles(): Array<VirtualFile> = emptyArray()
  override fun getSelectedEditors(): Array<FileEditor> = emptyArray()
  override fun getSelectedEditor(file: VirtualFile): FileEditor? = null
  override fun getEditors(file: VirtualFile): Array<FileEditor> = emptyArray()
  override fun getAllEditors(file: VirtualFile): Array<FileEditor> = emptyArray()
  override fun getAllEditorList(file: VirtualFile): List<FileEditor> = emptyList()
  override fun getAllEditors(): Array<FileEditor> = emptyArray()

  override fun openFile(file: VirtualFile, focusEditor: Boolean): Array<FileEditor> = desktopEditorOperation()
  override fun openFile(file: VirtualFile): List<FileEditor> = desktopEditorOperation()
  override fun closeFile(file: VirtualFile): Unit = desktopEditorOperation()
  override fun openTextEditor(descriptor: OpenFileDescriptor, focusEditor: Boolean): Editor? = desktopEditorOperation()
  override fun openFileEditor(descriptor: FileEditorNavigatable, focusEditor: Boolean): List<FileEditor> = desktopEditorOperation()
  override fun addTopComponent(editor: FileEditor, component: JComponent): Unit = desktopEditorOperation()
  override fun removeTopComponent(editor: FileEditor, component: JComponent): Unit = desktopEditorOperation()
  override fun addBottomComponent(editor: FileEditor, component: JComponent): Unit = desktopEditorOperation()
  override fun removeBottomComponent(editor: FileEditor, component: JComponent): Unit = desktopEditorOperation()
  override fun registerExtraEditorDataProvider(provider: EditorDataProvider, parentDisposable: Disposable?): Unit = desktopEditorOperation()
  override fun getData(dataId: String, editor: Editor, caret: Caret): Any? = desktopEditorOperation()
  override fun setSelectedEditor(file: VirtualFile, fileEditorProviderId: String): Unit = desktopEditorOperation()
  override fun runWhenLoaded(editor: Editor, runnable: Runnable): Unit = desktopEditorOperation()

  private fun desktopEditorOperation(): Nothing =
      throw UnsupportedOperationException("Desktop file editors are not available in the analysis environment")
}
