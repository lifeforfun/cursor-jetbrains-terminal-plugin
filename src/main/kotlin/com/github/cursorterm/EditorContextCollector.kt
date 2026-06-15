package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import java.util.concurrent.ConcurrentHashMap
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import kotlin.math.max
import kotlin.math.min

/** 采集到的编辑器引用；无上下文时为 null。 */
data class EditorReference(
    val relativePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
) {
    fun hasLineRange(): Boolean = startLine != null && endLine != null

    fun toAtNotation(): String {
        val escaped = relativePath.replace(" ", "\\ ")
        if (startLine != null && endLine != null) {
            return "@$escaped:$startLine-$endLine"
        }
        return "@$escaped"
    }
}

object EditorContextCollector {

    private val lastReferenceByProject = ConcurrentHashMap<String, EditorReference>()
    private val installedProjects = ConcurrentHashMap.newKeySet<String>()

    fun collect(project: Project): EditorReference? {
        val cached = lastReferenceByProject[projectKey(project)]
        val live = collectLive(project)
        val merged = mergeReferences(cached, live) ?: return cached
        remember(project, merged)
        return merged
    }

    fun snapshotActiveEditor(project: Project) {
        val cached = lastReferenceByProject[projectKey(project)]
        val live = collectLive(project) ?: return
        val merged = mergeReferences(cached, live) ?: live
        remember(project, merged)
    }

    fun installTracking(project: Project, parentDisposable: Disposable) {
        if (!installedProjects.add(projectKey(project))) return
        Disposer.register(parentDisposable) {
            installedProjects.remove(projectKey(project))
        }
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val editor = extractEditor(event.newEditor) ?: return
                val file = event.newFile ?: return
                rememberFromEditor(project, editor, file)
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                val editor = extractEditor(source.getSelectedEditor(file)) ?: return
                rememberFromEditor(project, editor, file)
                attachEditorFocusListener(project, editor)
            }
        })

        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    val editor = e.editor
                    if (editor.project != project) return
                    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                    rememberFromEditor(project, editor, file)
                }
            },
            parentDisposable,
        )

        attachFocusListenersToOpenEditors(project)
        collectLive(project)?.let { remember(project, it) }
    }

    private fun attachFocusListenersToOpenEditors(project: Project) {
        EditorFactory.getInstance().getAllEditors()
            .filter { it.project == project && !it.isDisposed }
            .forEach { editor -> attachEditorFocusListener(project, editor) }
    }

    private fun attachEditorFocusListener(project: Project, editor: Editor) {
        editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                rememberFromEditor(project, editor, file)
            }
        })
    }

    private fun collectLive(project: Project): EditorReference? {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val resolved = resolveActiveEditor(fileEditorManager, project) ?: return null
        val (editor, file) = resolved
        if (!file.isValid || file.path.isBlank()) return null
        return toReference(project, editor, file)
    }

    private fun mergeReferences(cached: EditorReference?, live: EditorReference?): EditorReference? {
        when {
            live == null -> return cached
            cached == null -> return live
            live.hasLineRange() -> return live
            cached.hasLineRange() && sameFile(cached, live) ->
                return live.copy(startLine = cached.startLine, endLine = cached.endLine)
            cached.hasLineRange() && !live.hasLineRange() && sameFile(cached, live) -> return cached
            cached.hasLineRange() && !live.hasLineRange() && !sameFile(cached, live) -> return cached
            else -> return live
        }
    }

    private fun sameFile(a: EditorReference, b: EditorReference): Boolean {
        if (a.relativePath == b.relativePath) return true
        return a.relativePath.endsWith("/${b.relativePath}") ||
            b.relativePath.endsWith("/${a.relativePath}") ||
            a.relativePath.substringAfterLast('/') == b.relativePath.substringAfterLast('/')
    }

    private fun rememberFromEditor(project: Project, editor: Editor, file: VirtualFile) {
        if (!file.isValid || file.path.isBlank()) return
        val incoming = toReference(project, editor, file) ?: return
        val cached = lastReferenceByProject[projectKey(project)]
        val merged = mergeReferences(cached, incoming) ?: incoming
        remember(project, merged)
    }

    private fun remember(project: Project, reference: EditorReference) {
        lastReferenceByProject[projectKey(project)] = reference
    }

    private fun projectKey(project: Project): String =
        project.locationHash

    private fun toReference(project: Project, editor: Editor, file: VirtualFile): EditorReference? {
        val relativePath = toRelativePath(project, file) ?: return null
        val selection = editor.selectionModel
        if (selection.hasSelection()) {
            val startLine = editor.offsetToLogicalPosition(selection.selectionStart).line + 1
            val endLine = editor.offsetToLogicalPosition(selection.selectionEnd).line + 1
            return EditorReference(
                relativePath = relativePath,
                startLine = min(startLine, endLine),
                endLine = max(startLine, endLine),
            )
        }
        return EditorReference(relativePath = relativePath)
    }

    private fun resolveActiveEditor(
        fileEditorManager: FileEditorManager,
        project: Project,
    ): Pair<Editor, VirtualFile>? {
        findEditorWithSelection(project)?.let { return it }

        fileEditorManager.selectedTextEditor?.let { editor ->
            FileDocumentManager.getInstance().getFile(editor.document)?.let { file ->
                return editor to file
            }
        }

        for (fileEditor in fileEditorManager.selectedEditors) {
            val editor = extractEditor(fileEditor) ?: continue
            val file = (fileEditor as? TextEditor)?.file
                ?: FileDocumentManager.getInstance().getFile(editor.document)
                ?: continue
            return editor to file
        }

        val historyFiles = EditorHistoryManager.getInstance(project).fileList
        for (file in historyFiles) {
            editorForFile(fileEditorManager, file)?.let { editor ->
                if (editor.selectionModel.hasSelection()) {
                    return editor to file
                }
            }
        }

        val file = fileEditorManager.selectedFiles.firstOrNull()
            ?: historyFiles.firstOrNull()
            ?: fileEditorManager.openFiles.lastOrNull()
            ?: return null

        val editor = editorForFile(fileEditorManager, file) ?: return null
        return editor to file
    }

    private fun findEditorWithSelection(project: Project): Pair<Editor, VirtualFile>? {
        val documentManager = FileDocumentManager.getInstance()
        return EditorFactory.getInstance().getAllEditors()
            .asSequence()
            .filter { editor ->
                editor.project == project && !editor.isDisposed && editor.selectionModel.hasSelection()
            }
            .mapNotNull { editor ->
                documentManager.getFile(editor.document)?.let { file -> editor to file }
            }
            .firstOrNull()
    }

    private fun editorForFile(fileEditorManager: FileEditorManager, file: VirtualFile): Editor? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        EditorFactory.getInstance().getEditors(document)
            .asSequence()
            .filter { !it.isDisposed }
            .maxByOrNull { editorScore(it) }
            ?.let { return it }

        extractEditor(fileEditorManager.getSelectedEditor(file))?.let { return it }
        return fileEditorManager.getEditors(file)
            .asSequence()
            .mapNotNull { extractEditor(it) }
            .maxByOrNull { editorScore(it) }
    }

    private fun editorScore(editor: Editor): Int {
        var score = 0
        if (editor.selectionModel.hasSelection()) score += 10
        if (editor.contentComponent.isShowing) score += 5
        return score
    }

    private fun extractEditor(fileEditor: FileEditor?): Editor? =
        (fileEditor as? TextEditor)?.editor

    private fun toRelativePath(project: Project, file: VirtualFile): String? {
        project.baseDir?.let { baseDir ->
            VfsUtilCore.getRelativePath(file, baseDir)
                ?.takeIf { it.isNotBlank() && it != "." }
                ?.let { return it.replace('\\', '/') }
        }
        val basePath = project.basePath ?: return file.name.takeIf { it.isNotBlank() }
        val normalizedBase = basePath.trimEnd('/', '\\')
        val normalizedFile = file.path.trimEnd('/', '\\')
        if (normalizedFile == normalizedBase) return null
        val prefix = normalizedBase + java.io.File.separator
        return when {
            normalizedFile.startsWith(prefix) ->
                normalizedFile.removePrefix(prefix).replace('\\', '/')
            else -> file.name.replace('\\', '/')
        }
    }
}
