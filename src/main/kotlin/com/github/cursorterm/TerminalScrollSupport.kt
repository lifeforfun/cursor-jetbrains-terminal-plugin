package com.github.cursorterm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.jediterm.terminal.ui.TerminalPanel
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import javax.swing.BoundedRangeModel
import javax.swing.Timer

/**
 * 将终端视图滚回输入区（底部）。供 Enter 提交与 [TerminalScrollFix] 未安装时兜底。
 */
object TerminalScrollSupport {

    private const val BOTTOM_VALUE = 0

    fun scrollToInputArea(shellWidget: ShellTerminalWidget) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            scrollToInputAreaOnEdt(shellWidget)
        } else {
            app.invokeLater({ scrollToInputAreaOnEdt(shellWidget) }, ModalityState.any())
        }
    }

    fun scheduleScrollToInputArea(shellWidget: ShellTerminalWidget) {
        for (delay in POST_ENTER_SCROLL_DELAYS_MS) {
            Timer(delay.toInt()) {
                scrollToInputAreaOnEdt(shellWidget)
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun scrollToInputAreaOnEdt(shellWidget: ShellTerminalWidget) {
        val terminalPanel = shellWidget.terminalPanel
        ensureHistoryScrollingEnabled(terminalPanel)
        val scrollModel = terminalPanel.verticalScrollModel
        setScrollValue(scrollModel, BOTTOM_VALUE)
        invokePanelScrollToBottom(terminalPanel)
    }

    private fun ensureHistoryScrollingEnabled(terminalPanel: TerminalPanel) {
        try {
            val scrollingField = TerminalPanel::class.java.getDeclaredField("myScrollingEnabled")
            scrollingField.isAccessible = true
            if (!scrollingField.getBoolean(terminalPanel)) {
                scrollingField.setBoolean(terminalPanel, true)
            }
            val updateMethod = TerminalPanel::class.java.getDeclaredMethod(
                "updateScrolling",
                Boolean::class.javaPrimitiveType,
            )
            updateMethod.isAccessible = true
            updateMethod.invoke(terminalPanel, true)
        } catch (_: Exception) {
        }
    }

    private fun setScrollValue(scrollModel: BoundedRangeModel, value: Int) {
        scrollModel.value = value.coerceIn(scrollModel.minimum, scrollModel.maximum)
    }

    private fun invokePanelScrollToBottom(terminalPanel: TerminalPanel) {
        try {
            val method = TerminalPanel::class.java.getDeclaredMethod("scrollToBottom")
            method.isAccessible = true
            method.invoke(terminalPanel)
        } catch (_: Exception) {
        }
    }

    private val POST_ENTER_SCROLL_DELAYS_MS = longArrayOf(40L, 120L, 250L)
}
