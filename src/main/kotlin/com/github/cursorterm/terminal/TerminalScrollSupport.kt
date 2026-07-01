package com.github.cursorterm.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.jediterm.terminal.ui.TerminalPanel
import javax.swing.BoundedRangeModel

/**
 * 将终端视图滚到输入区（底部）。
 *
 * JediTerm 的 [TerminalPanel.verticalScrollModel] 中 value=0 表示当前屏（底部）；
 * 负值越大表示越往历史顶部。勿把 Swing [javax.swing.JScrollBar.minimum] 当作底部。
 */
internal object TerminalScrollSupport {

    /** JediTerm：0 = 当前输入屏（底部）。 */
    private const val BOTTOM_SCROLL_VALUE = 0

    fun scrollToBottom(access: TerminalAccess) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            scrollToBottomOnEdt(access)
        } else {
            app.invokeLater({ scrollToBottomOnEdt(access) }, ModalityState.any())
        }
    }

    private fun scrollToBottomOnEdt(access: TerminalAccess) {
        val panel = access.terminalPanelOrNull() ?: return
        ensureHistoryScrollingEnabled(panel)
        val scrollModel = panel.verticalScrollModel
        setScrollValue(scrollModel, BOTTOM_SCROLL_VALUE)
        invokeUpdateScrolling(panel, force = true)
        invokePanelScrollToBottom(panel)
        panel.repaint()
        access.focusComponent.requestFocusInWindow()
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

    private fun invokeUpdateScrolling(terminalPanel: TerminalPanel, force: Boolean) {
        try {
            val method = TerminalPanel::class.java.getDeclaredMethod(
                "updateScrolling",
                Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(terminalPanel, force)
        } catch (_: Exception) {
        }
    }

    private fun invokePanelScrollToBottom(terminalPanel: TerminalPanel) {
        try {
            val method = TerminalPanel::class.java.getDeclaredMethod("scrollToBottom")
            method.isAccessible = true
            method.invoke(terminalPanel)
        } catch (_: Exception) {
        }
    }
}
