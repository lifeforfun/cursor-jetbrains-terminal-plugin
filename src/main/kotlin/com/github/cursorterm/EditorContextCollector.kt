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

    /** Enter 提交时采集：仅当前激活标签页，不回退缓存或其它 tab。 */
    fun collect(project: Project): EditorReference? =
        collectLive(project)?.also { remember(project, it) }

    fun snapshotActiveEditor(project: Project) {
        collectLive(project)?.let { remember(project, it) }
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
        resolveActiveEditor(fileEditorManager, project)?.let { (editor, file) ->
            if (!file.isValid || file.path.isBlank()) return null
            return toReference(project, editor, file)
        }
        return collectFromSelectedFileOnly(fileEditorManager, project)
    }

    /** CSV/表格等非 TextEditor 标签页：无 Editor 时仍注入当前 tab 文件路径。 */
    private fun collectFromSelectedFileOnly(
        fileEditorManager: FileEditorManager,
        project: Project,
    ): EditorReference? {
        val file = fileEditorManager.selectedFiles.firstOrNull() ?: return null
        if (!file.isValid || file.path.isBlank()) return null
        val path = toAtPath(project, file) ?: return null
        return EditorReference(relativePath = path)
    }

    private fun rememberFromEditor(project: Project, editor: Editor, file: VirtualFile) {
        if (!file.isValid || file.path.isBlank()) return
        val incoming = toReference(project, editor, file) ?: return
        remember(project, incoming)
    }

    private fun remember(project: Project, reference: EditorReference) {
        lastReferenceByProject[projectKey(project)] = reference
    }

    private fun projectKey(project: Project): String =
        project.locationHash

    private fun toReference(project: Project, editor: Editor, file: VirtualFile): EditorReference? {
        val path = toAtPath(project, file) ?: return null
        val selection = editor.selectionModel
        if (selection.hasSelection()) {
            val startLine = editor.offsetToLogicalPosition(selection.selectionStart).line + 1
            val endLine = editor.offsetToLogicalPosition(selection.selectionEnd).line + 1
            return EditorReference(
                relativePath = path,
                startLine = min(startLine, endLine),
                endLine = max(startLine, endLine),
            )
        }
        return EditorReference(relativePath = path)
    }

    /**
     * 解析当前激活标签页：优先 caret 所在编辑器，其次 selectedEditors / selectedFiles。
     */
    private fun resolveActiveEditor(
        fileEditorManager: FileEditorManager,
        @Suppress("UNUSED_PARAMETER") project: Project,
    ): Pair<Editor, VirtualFile>? {
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

        val selectedFile = fileEditorManager.selectedFiles.firstOrNull()
        val file = selectedFile ?: fileEditorManager.openFiles.lastOrNull() ?: return null
        val editor = editorForFile(fileEditorManager, file) ?: return null
        return editor to file
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

    /**
     * 项目内文件返回相对路径；项目外文件返回绝对路径（均用于 @ 引用）。
     */
    private fun toAtPath(project: Project, file: VirtualFile): String? {
        val absolutePath = resolvePhysicalPath(file) ?: return null

        project.baseDir?.let { baseDir ->
            VfsUtilCore.getRelativePath(file, baseDir)
                ?.takeIf { it.isNotBlank() && it != "." }
                ?.let { return it.replace('\\', '/') }
        }

        val basePath = project.basePath?.trimEnd('/', '\\')?.replace('\\', '/')
        if (basePath != null) {
            if (absolutePath == basePath) return null
            val prefix = "$basePath/"
            if (absolutePath.startsWith(prefix)) {
                return absolutePath.removePrefix(prefix)
            }
        }

        return absolutePath
    }

    private fun resolvePhysicalPath(file: VirtualFile): String? {
        return try {
            VfsUtilCore.virtualToIoFile(file).absolutePath
                .trimEnd('/', '\\')
                .replace('\\', '/')
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            file.path.trimEnd('/', '\\').replace('\\', '/').takeIf { it.isNotBlank() }
        }
    }
}
