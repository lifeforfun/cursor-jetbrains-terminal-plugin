package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer
import javax.swing.SwingUtilities

class EditorContextOnSubmitSupport private constructor(
    private val project: Project,
    private val shellWidget: ShellTerminalWidget,
    private val content: Content,
    private val inputTracker: TerminalInputTracker,
    private val parentDisposable: Disposable,
) {

    @Volatile
    private var active = true

    fun install() {
        EditorContextCollector.installTracking(project, parentDisposable)
        inputTracker.install()
        val panel = shellWidget.terminalPanel
        installTerminalFocusSnapshot(panel)
        installEnterHandlers(panel)
        Disposer.register(parentDisposable) {
            active = false
        }
    }

    private fun installEnterHandlers(panel: java.awt.Component) {
        val handler = Consumer<KeyEvent> { event ->
            if (!active) return@Consumer
            inputTracker.handlePreKeyEvent(event)
            if (event.id != KeyEvent.KEY_PRESSED) return@Consumer
            if (event.isConsumed) return@Consumer
            processSubmitEnter(event)
        }
        TerminalPreKeySupport.installFirst(panel, handler)
        installEnterKeyDispatcher(panel)
    }

    private fun installEnterKeyDispatcher(panel: java.awt.Component) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            if (!active) return@KeyEventDispatcher false
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (event.keyCode != KeyEvent.VK_ENTER) return@KeyEventDispatcher false
            if (!isEventForTerminal(event, panel)) return@KeyEventDispatcher false

            inputTracker.handlePreKeyEvent(event)
            processSubmitEnter(event)
            false
        }
        manager.addKeyEventDispatcher(dispatcher)
        Disposer.register(parentDisposable) {
            manager.removeKeyEventDispatcher(dispatcher)
        }
    }

    private fun processSubmitEnter(event: KeyEvent) {
        if (isShiftEnter(event)) {
            inputTracker.onShiftEnter()
            return
        }
        if (!isSubmitEnter(event)) return

        TerminalScrollFix.notifySubmitEnter(content, shellWidget)

        inputTracker.refreshPtyCapture()

        val reference = EditorContextCollector.collect(project)
        val inputSnapshot = inputTracker.inputSnapshot()
        if (reference == null) return
        if (!inputSnapshot.hasUserInput) return
        if (inputTracker.consumeLineContinuationEnter()) return
        if (!EnterInjectGate.tryAcquire(event)) return

        val ref = reference.toAtNotation()
        val sent = TerminalSendSupport.sendString(shellWidget, "${System.lineSeparator()}$ref")
        if (!sent) return
        inputTracker.reset()
    }

    private fun isEventForTerminal(event: KeyEvent, panel: java.awt.Component): Boolean {
        val source = event.component
        if (source != null && SwingUtilities.isDescendingFrom(source, panel)) {
            return true
        }
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(focusOwner, panel)
    }

    private fun installTerminalFocusSnapshot(panel: java.awt.Component) {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                EditorContextCollector.snapshotActiveEditor(project)
            }
        }
        panel.addMouseListener(listener)
        Disposer.register(parentDisposable) {
            panel.removeMouseListener(listener)
        }
    }

    private fun isSubmitEnter(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.VK_ENTER && !hasSubmitModifiers(event)

    private fun isShiftEnter(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.VK_ENTER && event.isShiftDown

    private fun hasSubmitModifiers(event: KeyEvent): Boolean =
        event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown

    private object EnterInjectGate {
        @Volatile
        private var lastInjectedEnterWhen = Long.MIN_VALUE

        @Volatile
        private var lastInjectAtMs = 0L

        fun tryAcquire(event: KeyEvent): Boolean {
            val enterWhen = event.`when`
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (now - lastInjectAtMs < 300 || enterWhen == lastInjectedEnterWhen) {
                    return false
                }
                lastInjectedEnterWhen = enterWhen
                lastInjectAtMs = now
                return true
            }
        }
    }

    companion object {
        private const val PLUGIN_HOOK_VERSION = "1.8.23"
        private val INSTALLED_VERSION = Key.create<String>("cursorterm.editorContextOnSubmit.version")
        private val INSTALLATION = Key.create<Disposable>("cursorterm.editorContextOnSubmit.installation")

        fun installOnce(project: Project, shellWidget: ShellTerminalWidget, content: Content) {
            if (content.getUserData(INSTALLED_VERSION) == PLUGIN_HOOK_VERSION) return

            content.getUserData(INSTALLATION)?.let { Disposer.dispose(it) }

            val installation = Disposer.newDisposable("EditorContextOnSubmit")
            Disposer.register(content, installation)

            val inputTracker = TerminalInputTracker(shellWidget, installation)
            EditorContextOnSubmitSupport(project, shellWidget, content, inputTracker, installation).install()

            content.putUserData(INSTALLATION, installation)
            content.putUserData(INSTALLED_VERSION, PLUGIN_HOOK_VERSION)
        }
    }
}
