package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalPanel
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.KeyboardFocusManager
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/** 跟踪终端输入与 `\` 续行。 */
class TerminalInputTracker(
    private val shellWidget: ShellTerminalWidget,
    private val parentDisposable: Disposable,
) {

    @Volatile
    private var hasUserInput = false

    @Volatile
    private var lineContinuationPending = false

    private val terminalPanel = shellWidget.terminalPanel

    private val keyDispatcher = KeyboardFocusManager.getCurrentKeyboardFocusManager().let { manager ->
        java.awt.KeyEventDispatcher { event ->
            if (!isEventForTerminal(event)) return@KeyEventDispatcher false
            dispatchKeyEvent(event)
            false
        }.also { dispatcher ->
            manager.addKeyEventDispatcher(dispatcher)
            Disposer.register(parentDisposable) {
                manager.removeKeyEventDispatcher(dispatcher)
            }
        }
    }

    @Suppress("unused")
    private val dispatcherHolder = keyDispatcher

    fun hasUserInput(): Boolean = hasUserInput

    fun consumeLineContinuationEnter(): Boolean {
        if (!lineContinuationPending) return false
        lineContinuationPending = false
        return true
    }

    fun onShiftEnter() {
        hasUserInput = false
        lineContinuationPending = false
    }

    fun reset() {
        hasUserInput = false
        lineContinuationPending = false
    }

    fun install() {
        val panelListener = object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) = onKeyTyped(e.keyChar)

            override fun keyPressed(e: KeyEvent) = onKeyPressed(e)
        }
        terminalPanel.addKeyListener(panelListener)
        Disposer.register(parentDisposable) {
            terminalPanel.removeKeyListener(panelListener)
        }

        val inputMethodListener = object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                if (event.committedCharacterCount <= 0 || !isTerminalFocused()) return
                hasUserInput = true
                val committed = event.text?.toString()?.takeLast(event.committedCharacterCount).orEmpty()
                if (committed.isNotEmpty()) {
                    lineContinuationPending = isLineContinuationChar(committed.last())
                }
            }

            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        }
        terminalPanel.addInputMethodListener(inputMethodListener)
        Disposer.register(parentDisposable) {
            terminalPanel.removeInputMethodListener(inputMethodListener)
        }
    }

    private fun dispatchKeyEvent(event: KeyEvent) {
        when (event.id) {
            KeyEvent.KEY_TYPED -> onKeyTyped(event.keyChar)
            KeyEvent.KEY_PRESSED -> onKeyPressed(event)
        }
    }

    private fun isEventForTerminal(event: KeyEvent): Boolean {
        val source = event.component
        if (source != null && SwingUtilities.isDescendingFrom(source, terminalPanel)) {
            return true
        }
        return isTerminalFocused()
    }

    private fun isTerminalFocused(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(focusOwner, terminalPanel)
    }

    private fun onKeyTyped(ch: Char) {
        if (ch == KeyEvent.CHAR_UNDEFINED || ch.isISOControl()) return
        hasUserInput = true
        lineContinuationPending = isLineContinuationChar(ch)
    }

    private fun onKeyPressed(event: KeyEvent) {
        if (event.keyCode == KeyEvent.VK_ENTER && event.isShiftDown) {
            onShiftEnter()
            return
        }
        if (event.keyCode == KeyEvent.VK_ENTER) return
        if (event.isControlDown && event.keyCode == KeyEvent.VK_V) {
            hasUserInput = true
            lineContinuationPending = false
            return
        }
        if (event.keyCode == KeyEvent.VK_BACK_SLASH && !hasModifiers(event)) {
            lineContinuationPending = true
            return
        }
        if (event.keyCode == KeyEvent.VK_BACK_SPACE || event.keyCode == KeyEvent.VK_DELETE) {
            lineContinuationPending = false
            return
        }
        if (isNavigationKey(event.keyCode)) return
        if (event.isControlDown || event.isAltDown || event.isMetaDown) return
        hasUserInput = true
    }

    private fun hasModifiers(event: KeyEvent): Boolean =
        event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown

    private fun isLineContinuationChar(ch: Char): Boolean =
        ch == '\\' || ch == '＼'

    private fun isNavigationKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
        KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END,
        KeyEvent.VK_TAB, KeyEvent.VK_ESCAPE,
        KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META,
        KeyEvent.VK_CAPS_LOCK,
        -> true
        else -> false
    }
}
