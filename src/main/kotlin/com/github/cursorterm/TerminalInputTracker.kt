package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.KeyboardFocusManager
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * 跟踪终端当前行是否有用户输入，以及 `\` 续行。
 *
 * cursor-agent TUI 不经 Swing/缓冲区：PTY 拦截 + 全局按键分发 + IME 兜底。
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

    @Volatile
    private var preEnterKeyPressCount = 0

    private val shadowInput = StringBuilder()

    private val terminalPanel = shellWidget.terminalPanel

    fun hasUserInput(): Boolean = inputSnapshot().hasUserInput

    fun inputSnapshot(): InputSnapshot {
        val typed = resolveTypedCommand()
        return InputSnapshot(
            hasUserInput = typed.isNotBlank() || preEnterKeyPressCount > 0 || pastePending,
            typedCommandLength = typed.length,
            preEnterKeyPressCount = preEnterKeyPressCount,
        )
    }

    fun refreshPtyCapture() {
        TtyConnectorCaptureSupport.install(shellWidget) { connector ->
            capturingConnector = connector
        }
    }

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
            KeyEvent.KEY_PRESSED -> {
                if (event.keyCode != KeyEvent.VK_ENTER && isTypingKeyPress(event)) {
                    noteTypingKey()
                }
                handleKeyPressed(event)
            }
        }
    }

    fun install() {
        installPtyCapture(retry = 0)
        installKeyDispatchTracking()
        installPanelKeyListener()

        val inputMethodListener = object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                if (event.committedCharacterCount <= 0) return
                val committed = event.text?.toString()?.takeLast(event.committedCharacterCount).orEmpty()
                if (committed.isEmpty()) return
                shadowInput.append(committed)
                noteTypingKey()
                lineContinuationPending = isLineContinuationChar(committed.last())
            }

            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        }
        terminalPanel.addInputMethodListener(inputMethodListener)
        Disposer.register(parentDisposable) {
            terminalPanel.removeInputMethodListener(inputMethodListener)
        }
    }

    private fun installKeyDispatchTracking() {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyboardFocusManager.getCurrentKeyboardFocusManager().let { focusManager ->
            java.awt.KeyEventDispatcher { event ->
                if (!isEventForTerminal(event)) return@KeyEventDispatcher false
                when (event.id) {
                    KeyEvent.KEY_TYPED -> appendShadowChar(event.keyChar)
                    KeyEvent.KEY_PRESSED -> {
                        if (event.keyCode != KeyEvent.VK_ENTER && isTypingKeyPress(event)) {
                            noteTypingKey()
                        }
                    }
                }
                false
            }.also { focusManager.addKeyEventDispatcher(it) }
        }
        Disposer.register(parentDisposable) {
            manager.removeKeyEventDispatcher(dispatcher)
        }
    }

    private fun installPanelKeyListener() {
        val listener = object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) = appendShadowChar(e.keyChar)

            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER && isTypingKeyPress(e)) {
                    noteTypingKey()
                }
            }
        }
        terminalPanel.addKeyListener(listener)
        Disposer.register(parentDisposable) {
            terminalPanel.removeKeyListener(listener)
        }
    }

    private fun installPtyCapture(retry: Int) {
        var installed = TtyConnectorCaptureSupport.install(shellWidget) { connector ->
            capturingConnector = connector
        }
        TtyConnectorCaptureSupport.registerWhenReady(shellWidget) { connector ->
            capturingConnector = connector
            installed = true
        }

        if (!installed && retry < 60) {
            ApplicationManager.getApplication().invokeLater {
                installPtyCapture(retry + 1)
            }
        }
    }

    private fun noteTypingKey() {
        preEnterKeyPressCount++
    }

    private fun clearTrackedInput() {
        shadowInput.setLength(0)
        pastePending = false
        lineContinuationPending = false
        preEnterKeyPressCount = 0
        capturingConnector?.clearLine()
    }

    private fun resolveTypedCommand(): String {
        capturingConnector?.currentLine()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        TtyConnectorCaptureSupport.shellTypedCommand(shellWidget).trim().takeIf { it.isNotBlank() }?.let { return it }

        shadowInput.toString().trim().takeIf { it.isNotBlank() }?.let { return it }

        if (pastePending || preEnterKeyPressCount > 0) return " "
        return ""
    }

    private fun appendShadowChar(ch: Char) {
        if (ch == KeyEvent.CHAR_UNDEFINED || ch.isISOControl()) return
        shadowInput.append(ch)
        noteTypingKey()
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

    private fun isEventForTerminal(event: KeyEvent): Boolean {
        val source = event.component
        if (source != null && SwingUtilities.isDescendingFrom(source, terminalPanel)) {
            return true
        }
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(focusOwner, terminalPanel)
    }

    private fun isTypingKeyPress(event: KeyEvent): Boolean {
        if (event.isControlDown || event.isAltDown || event.isMetaDown) return false
        if (isNavigationKey(event.keyCode)) return false
        return true
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

    data class InputSnapshot(
        val hasUserInput: Boolean,
        val typedCommandLength: Int,
        val preEnterKeyPressCount: Int,
    )

    companion object {
        private val CLEAR_BUFFER_KEYS = setOf(
            KeyEvent.VK_A,
            KeyEvent.VK_K,
            KeyEvent.VK_U,
        )
    }
}
