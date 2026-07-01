package com.github.cursorterm.feature

import com.github.cursorterm.DebugAgentLog
import com.github.cursorterm.terminal.TerminalAccess
import com.github.cursorterm.terminal.TerminalShiftSelectionSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * 终端交互增强：URL 纯文本化、Shift 扩选。各子功能独立安装，互不影响。
 */
internal object TerminalInteractionFeature {

    fun install(project: Project, access: TerminalAccess, parentDisposable: Disposable) {
        installSafely("plain-text") { TerminalPlainTextFeature.install(access, parentDisposable) }
        installSafely("shift-selection") {
            val panel = access.terminalPanelOrNull() ?: return@installSafely
            TerminalShiftSelectionSupport(panel, parentDisposable).install()
        }
    }

    private inline fun installSafely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            DebugAgentLog.write("H-TERM", "TerminalInteractionFeature", "install-failed", mapOf("feature" to name, "error" to e.message))
        }
    }
}
