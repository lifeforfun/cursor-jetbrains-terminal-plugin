package com.github.cursorterm.feature

import com.github.cursorterm.DebugAgentLog
import com.github.cursorterm.terminal.TerminalAccess
import com.github.cursorterm.terminal.TerminalShiftSelectionSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.jediterm.terminal.ui.TerminalPanel

/**
 * 终端交互增强：URL 纯文本化、Shift 扩选。各子功能独立安装，互不影响。
 * Block 终端 openSession 异步完成前 panel 可能尚未就绪，故延迟重试安装。
 */
internal object TerminalInteractionFeature {

    private val SHIFT_INSTALLED_KEY = "cursorterm.shiftSelectionInstalled"
    private val PLAIN_TEXT_INSTALLED_KEY = "cursorterm.plainTextInstalled"

    private val INSTALL_DELAYS_MS = listOf(0L, 300L, 800L, 1_500L, 3_000L, 5_000L, 8_000L)

    fun install(project: Project, access: TerminalAccess, parentDisposable: Disposable) {
        scheduleInstall(parentDisposable, "plain-text") {
            if (isPlainTextInstalled(access)) return@scheduleInstall true
            val connectorReady = access.widget.ttyConnectorAccessor.ttyConnector != null
                || access.ttyConnectorOrNull() != null
            if (!connectorReady) return@scheduleInstall false
            installSafely("plain-text") { TerminalPlainTextFeature.install(access, parentDisposable) }
            markPlainTextInstalled(access)
            true
        }
        scheduleInstall(parentDisposable, "shift-selection") {
            val panel = access.terminalPanelOrNull() ?: return@scheduleInstall false
            if (isShiftSelectionInstalled(panel)) return@scheduleInstall true
            installSafely("shift-selection") {
                TerminalShiftSelectionSupport(panel, parentDisposable).install()
            }
            markShiftSelectionInstalled(panel)
            true
        }
    }

    private fun scheduleInstall(
        parentDisposable: Disposable,
        feature: String,
        attempt: () -> Boolean,
    ) {
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
        var installed = false
        INSTALL_DELAYS_MS.forEach { delayMs ->
            alarm.addRequest({
                if (installed || Disposer.isDisposed(parentDisposable)) return@addRequest
                try {
                    if (attempt()) {
                        installed = true
                        DebugAgentLog.write(
                            "H-TERM",
                            "TerminalInteractionFeature",
                            "installed",
                            mapOf("feature" to feature, "delayMs" to delayMs),
                        )
                    }
                } catch (e: Exception) {
                    DebugAgentLog.write(
                        "H-TERM",
                        "TerminalInteractionFeature",
                        "install-failed",
                        mapOf("feature" to feature, "error" to e.message),
                    )
                }
            }, delayMs)
        }
    }

    private fun isShiftSelectionInstalled(panel: TerminalPanel): Boolean =
        panel.getClientProperty(SHIFT_INSTALLED_KEY) == true

    private fun markShiftSelectionInstalled(panel: TerminalPanel) {
        panel.putClientProperty(SHIFT_INSTALLED_KEY, true)
    }

    private fun isPlainTextInstalled(access: TerminalAccess): Boolean =
        access.widget.component.getClientProperty(PLAIN_TEXT_INSTALLED_KEY) == true

    private fun markPlainTextInstalled(access: TerminalAccess) {
        access.widget.component.putClientProperty(PLAIN_TEXT_INSTALLED_KEY, true)
    }

    private inline fun installSafely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            DebugAgentLog.write(
                "H-TERM",
                "TerminalInteractionFeature",
                "install-failed",
                mapOf("feature" to name, "error" to e.message),
            )
        }
    }
}
