package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyEvent

/**
 * 跟踪终端当前行是否有用户输入，以及 `\` 续行。
 *
 * cursor-agent TUI 不经 Swing/缓冲区，以 PTY 写入拦截为主。
 */
class TerminalInputTracker(
    private val shellWidget: ShellTerminalWidget,
    private val parentDisposable: Disposable,
) {

    @Volatile
    private var capturingConnector: CapturingTtyConnector? = null

    @Volatile
    private var lineContinuationPending = false

    @Volatile
    private var pastePending = false

    private val shadowInput = StringBuilder()

    private val terminalPanel = shellWidget.terminalPanel

    fun hasUserInput(): Boolean = resolveTypedCommand().isNotBlank()

    fun consumeLineContinuationEnter(): Boolean {
        if (capturingConnector?.hasLineContinuationPending() == true) {
            capturingConnector?.consumeLineContinuation()
            return true
        }
        if (!lineContinuationPending) return false
        lineContinuationPending = false
        return true
    }

    fun onShiftEnter() {
        clearTrackedInput()
    }

    fun reset() {
        clearTrackedInput()
    }

    fun handlePreKeyEvent(event: KeyEvent) {
        when (event.id) {
            KeyEvent.KEY_TYPED -> appendShadowChar(event.keyChar)
            KeyEvent.KEY_PRESSED -> handleKeyPressed(event)
        }
    }

    fun install() {
        installPtyCapture(retry = 0)

        val inputMethodListener = object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                if (event.committedCharacterCount <= 0) return
                val committed = event.text?.toString()?.takeLast(event.committedCharacterCount).orEmpty()
                if (committed.isEmpty()) return
                shadowInput.append(committed)
                lineContinuationPending = isLineContinuationChar(committed.last())
            }

            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        }
        terminalPanel.addInputMethodListener(inputMethodListener)
        Disposer.register(parentDisposable) {
            terminalPanel.removeInputMethodListener(inputMethodListener)
        }
    }

    private fun installPtyCapture(retry: Int) {
        val starter = shellWidget.terminalStarter
        if (starter == null) {
            if (retry < 30) {
                ApplicationManager.getApplication().invokeLater {
                    installPtyCapture(retry + 1)
                }
            }
            return
        }
        try {
            capturingConnector = CapturingTtyConnector.installOn(starter)
        } catch (_: Exception) {
            if (retry < 30) {
                ApplicationManager.getApplication().invokeLater {
                    installPtyCapture(retry + 1)
                }
            }
        }
    }

    private fun clearTrackedInput() {
        shadowInput.setLength(0)
        pastePending = false
        lineContinuationPending = false
        capturingConnector?.clearLine()
    }

    private fun resolveTypedCommand(): String {
        val ptyLine = capturingConnector?.currentLine()?.trim().orEmpty()
        if (ptyLine.isNotBlank()) return ptyLine

        val shadow = shadowInput.toString().trim()
        if (shadow.isNotBlank()) return shadow

        if (pastePending) return " "
        return ""
    }

    private fun appendShadowChar(ch: Char) {
        if (ch == KeyEvent.CHAR_UNDEFINED || ch.isISOControl()) return
        shadowInput.append(ch)
        lineContinuationPending = isLineContinuationChar(ch)
    }

    private fun handleKeyPressed(event: KeyEvent) {
        when {
            event.keyCode == KeyEvent.VK_ENTER && event.isShiftDown -> onShiftEnter()
            event.keyCode == KeyEvent.VK_BACK_SLASH && !hasModifiers(event) -> lineContinuationPending = true
            event.keyCode == KeyEvent.VK_BACK_SPACE -> {
                if (shadowInput.isNotEmpty()) {
                    shadowInput.deleteCharAt(shadowInput.length - 1)
                }
                lineContinuationPending = false
            }
            event.keyCode == KeyEvent.VK_DELETE -> lineContinuationPending = false
            event.isControlDown && event.keyCode == KeyEvent.VK_V -> pastePending = true
            event.isControlDown && event.keyCode in CLEAR_BUFFER_KEYS -> clearTrackedInput()
        }
    }

    private fun hasModifiers(event: KeyEvent): Boolean =
        event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown

    private fun isLineContinuationChar(ch: Char): Boolean =
        ch == '\\' || ch == '＼'

    companion object {
        private val CLEAR_BUFFER_KEYS = setOf(
            KeyEvent.VK_A,
            KeyEvent.VK_K,
            KeyEvent.VK_U,
        )
    }
}
