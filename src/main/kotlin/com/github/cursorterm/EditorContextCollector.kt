package com.github.cursorterm

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import kotlin.math.max
import kotlin.math.min

/** 采集到的编辑器引用；无上下文时为 null。 */
data class EditorReference(
    val relativePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
) {
    fun toAtNotation(): String {
        val escaped = relativePath.replace(" ", "\\ ")
        if (startLine != null && endLine != null) {
            return "@$escaped:$startLine-$endLine"
        }
        return "@$escaped"
    }
}

object EditorContextCollector {

    /**
     * 采集当前激活标签页引用；有选区带行号。
     * 不判断终端输入框是否为空（由 [EditorContextOnSubmitSupport] 负责）。
     */
    fun collect(project: Project): EditorReference? {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val (editor, file) = resolveActiveEditor(fileEditorManager, project) ?: return null
        if (!file.isValid || file.path.isBlank()) return null

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

    /**
     * 1. [FileEditorManager.selectedFiles] — 当前激活标签页（焦点在终端时仍有效）
     * 2. [EditorHistoryManager.fileList] — 最近编辑
     * 3. [FileEditorManager.openFiles] — 兜底
     *
     * 编辑器实例优先 [EditorFactory]，焦点切到终端后仍能读到选区。
     */
    private fun resolveActiveEditor(
        fileEditorManager: FileEditorManager,
        project: Project,
    ): Pair<Editor, VirtualFile>? {
        val file = fileEditorManager.selectedFiles.firstOrNull()
            ?: EditorHistoryManager.getInstance(project).fileList.firstOrNull()
            ?: fileEditorManager.openFiles.lastOrNull()
            ?: return null

        val editor = editorForFile(fileEditorManager, file) ?: return null
        return editor to file
    }

    private fun editorForFile(fileEditorManager: FileEditorManager, file: VirtualFile): Editor? {
        extractEditor(fileEditorManager.getSelectedEditor(file))?.let { return it }
        fileEditorManager.getEditors(file)
            .asSequence()
            .mapNotNull { extractEditor(it) }
            .firstOrNull()
            ?.let { return it }

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return EditorFactory.getInstance().getEditors(document).firstOrNull()
    }

    private fun extractEditor(fileEditor: FileEditor?): Editor? =
        (fileEditor as? TextEditor)?.editor

    private fun toRelativePath(project: Project, file: VirtualFile): String? {
        project.baseDir?.let { baseDir ->
            VfsUtilCore.getRelativePath(file, baseDir)
                ?.takeIf { it.isNotBlank() && it != "." }
                ?.let { return it.replace('\\', '/') }
        }
        val basePath = project.basePath ?: return file.path.replace('\\', '/')
        val normalizedBase = basePath.trimEnd('/', '\\')
        val normalizedFile = file.path.trimEnd('/', '\\')
        if (normalizedFile == normalizedBase) return null
        val prefix = normalizedBase + java.io.File.separator
        return if (normalizedFile.startsWith(prefix)) {
            normalizedFile.removePrefix(prefix).replace('\\', '/')
        } else {
            file.path.replace('\\', '/')
        }
    }
}
