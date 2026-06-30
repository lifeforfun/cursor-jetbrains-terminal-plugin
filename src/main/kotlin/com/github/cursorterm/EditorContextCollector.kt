package com.github.cursorterm

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import kotlin.math.max
import kotlin.math.min

data class EditorReference(
    val relativePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
) {
    fun toAtNotation(): String {
        val escaped = relativePath.replace(" ", "\\ ")
        if (startLine != null && endLine != null) return "@$escaped:$startLine-$endLine"
        return "@$escaped"
    }
}

/** 按需采集激活标签页路径，无全局监听、无 Enter 钩子。 */
object EditorContextCollector {

    fun collect(project: Project): EditorReference? {
        val fem = FileEditorManager.getInstance(project)
        resolveActiveEditor(fem)?.let { (editor, file) ->
            if (!file.isValid || file.path.isBlank()) return null
            return toReference(project, editor, file)
        }
        val file = fem.selectedFiles.firstOrNull() ?: return null
        if (!file.isValid || file.path.isBlank()) return null
        val path = toAtPath(project, file) ?: return null
        return EditorReference(relativePath = path)
    }

    private fun toReference(project: Project, editor: Editor, file: VirtualFile): EditorReference? {
        val path = toAtPath(project, file) ?: return null
        val sel = editor.selectionModel
        if (sel.hasSelection()) {
            val s = editor.offsetToLogicalPosition(sel.selectionStart).line + 1
            val e = editor.offsetToLogicalPosition(sel.selectionEnd).line + 1
            return EditorReference(path, min(s, e), max(s, e))
        }
        return EditorReference(relativePath = path)
    }

    private fun resolveActiveEditor(fem: FileEditorManager): Pair<Editor, VirtualFile>? {
        fem.selectedTextEditor?.let { ed ->
            FileDocumentManager.getInstance().getFile(ed.document)?.let { return ed to it }
        }
        for (fe in fem.selectedEditors) {
            val ed = (fe as? TextEditor)?.editor ?: continue
            val file = (fe as? TextEditor)?.file
                ?: FileDocumentManager.getInstance().getFile(ed.document) ?: continue
            return ed to file
        }
        val file = fem.selectedFiles.firstOrNull() ?: return null
        val ed = (fem.getSelectedEditor(file) as? TextEditor)?.editor ?: return null
        return ed to file
    }

    private fun toAtPath(project: Project, file: VirtualFile): String? {
        val abs = resolvePhysicalPath(file) ?: return null
        project.baseDir?.let { base ->
            VfsUtilCore.getRelativePath(file, base)?.takeIf { it.isNotBlank() && it != "." }
                ?.let { return it.replace('\\', '/') }
        }
        val base = project.basePath?.trimEnd('/', '\\')?.replace('\\', '/')
        if (base != null && abs != base) {
            val prefix = "$base/"
            if (abs.startsWith(prefix)) return abs.removePrefix(prefix)
        }
        return abs
    }

    private fun resolvePhysicalPath(file: VirtualFile): String? = try {
        VfsUtilCore.virtualToIoFile(file).absolutePath.trimEnd('/', '\\').replace('\\', '/')
            .takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        file.path.trimEnd('/', '\\').replace('\\', '/').takeIf { it.isNotBlank() }
    }
}
