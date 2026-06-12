package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalPanel
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.KeyboardFocusManager
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * 跟踪自上次成功提交以来，用户是否在终端键入过内容。
 * cursor-agent TUI 下按键可能不经过 PreKey，故用 KeyEventDispatcher 全局观察（不 consume）。
 */
class TerminalInputTracker(
    private val shellWidget: ShellTerminalWidget,
    private val parentDisposable: Disposable,
) {

    @Volatile
    private var hasUserInput = false

    private val terminalPanel = shellWidget.terminalPanel

    private val keyDispatcher = KeyboardFocusManager.getCurrentKeyboardFocusManager().let { manager ->
        java.awt.KeyEventDispatcher { event ->
            if (!isEventForTerminal(event)) return@KeyEventDispatcher false
            when (event.id) {
                KeyEvent.KEY_TYPED -> markIfPrintable(event.keyChar)
                KeyEvent.KEY_PRESSED -> onKeyPressed(event)
            }
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

    fun reset() {
        hasUserInput = false
    }

    fun install() {
        val inputMethodListener = object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                if (event.committedCharacterCount > 0 && isTerminalFocused()) {
                    hasUserInput = true
                }
            }

            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        }
        terminalPanel.addInputMethodListener(inputMethodListener)
        Disposer.register(parentDisposable) {
            terminalPanel.removeInputMethodListener(inputMethodListener)
        }
    }

    private fun isEventForTerminal(event: KeyEvent): Boolean {
        if (!isTerminalFocused()) return false
        val source = event.component ?: return true
        return SwingUtilities.isDescendingFrom(source, terminalPanel)
    }

    private fun isTerminalFocused(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(focusOwner, terminalPanel)
    }

    private fun onKeyPressed(event: KeyEvent) {
        if (isPlainEnter(event)) return
        if (event.isControlDown || event.isAltDown || event.isMetaDown) {
            if (event.isControlDown && event.keyCode == KeyEvent.VK_V) {
                hasUserInput = true
            }
            return
        }
        if (isNavigationKey(event.keyCode)) return
        markIfPrintable(event.keyChar)
        hasUserInput = true
    }

    private fun markIfPrintable(ch: Char) {
        if (ch != KeyEvent.CHAR_UNDEFINED && !ch.isISOControl()) {
            hasUserInput = true
        }
    }

    private fun isPlainEnter(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.VK_ENTER && event.modifiersEx == 0

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
