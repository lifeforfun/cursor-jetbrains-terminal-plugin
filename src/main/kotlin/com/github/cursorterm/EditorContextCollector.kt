package com.github.cursorterm

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
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
        val textEditor = resolveActiveTextEditor(fileEditorManager) ?: return null
        val file = textEditor.file
        if (!file.isValid || file.path.isBlank()) return null

        val relativePath = toRelativePath(project, file.path) ?: return null
        val editor = textEditor.editor
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
     * 1. [FileEditorManager.selectedTextEditor] — 编辑器有焦点
     * 2. [FileEditorManager.selectedFiles] + [FileEditorManager.getSelectedEditor] — 焦点在终端
     * 3. [FileEditorManager.getEditors] 首个 TextEditor — 兜底
     */
    private fun resolveActiveTextEditor(fileEditorManager: FileEditorManager): TextEditor? {
        (fileEditorManager.selectedTextEditor as? TextEditor)?.let { return it }
        val selectedFile = fileEditorManager.selectedFiles.firstOrNull()
            ?: fileEditorManager.openFiles.lastOrNull()
            ?: return null
        (fileEditorManager.getSelectedEditor(selectedFile) as? TextEditor)?.let { return it }
        return fileEditorManager.getEditors(selectedFile).filterIsInstance<TextEditor>().firstOrNull()
    }

    private fun toRelativePath(project: Project, absolutePath: String): String? {
        val basePath = project.basePath ?: return absolutePath
        val normalizedBase = basePath.trimEnd('/', '\\')
        val normalizedFile = absolutePath.trimEnd('/', '\\')
        if (normalizedFile == normalizedBase) return null
        val prefix = normalizedBase + java.io.File.separator
        return if (normalizedFile.startsWith(prefix)) normalizedFile.removePrefix(prefix) else absolutePath
    }
}
