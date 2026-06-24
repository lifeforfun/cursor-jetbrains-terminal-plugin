package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer

/**
 * Enter 提交：PreKey 阶段同行追加 @，由 JediTerm 原生 Enter 完成提交（与 1.8.9 一致）。
 */
class EditorContextOnSubmitSupport private constructor(
    private val project: Project,
    private val shellWidget: ShellTerminalWidget,
    private val content: Content,
    private val inputTracker: TerminalInputTracker,
    private val parentDisposable: Disposable,
) {

    @Volatile
    private var active = true

    private val preKeyHandler = Consumer<KeyEvent> { event ->
        if (!active) return@Consumer
        inputTracker.handlePreKeyEvent(event)
        if (event.id != KeyEvent.KEY_PRESSED) return@Consumer
        if (event.isConsumed) return@Consumer
        processSubmitEnter(event)
    }

    fun install() {
        EditorContextCollector.installTracking(project, parentDisposable)
        inputTracker.install()
        val panel = shellWidget.terminalPanel
        installTerminalFocusSnapshot(panel)
        TerminalPreKeySupport.installFirst(panel, preKeyHandler)
        Disposer.register(parentDisposable) {
            active = false
            TerminalPreKeySupport.removeHandler(panel, preKeyHandler)
        }
    }

    private fun processSubmitEnter(event: KeyEvent) {
        if (!SubmitEnterGate.tryProcess(event)) return

        if (isShiftEnter(event)) {
            inputTracker.onShiftEnter()
            return
        }
        if (!isSubmitEnter(event)) return

        inputTracker.refreshPtyCapture()
        if (inputTracker.consumeLineContinuationEnter()) return

        TerminalScrollFix.notifySubmitEnter(content, shellWidget)

        val reference = EditorContextCollector.collect(project) ?: return
        val inputSnapshot = inputTracker.inputSnapshot()
        if (!inputSnapshot.hasUserInput) return

        val ref = reference.toAtNotation()
        val line = inputTracker.inputLine()
        if (line.contains(ref)) return

        val starter = shellWidget.terminalStarter ?: return
        starter.sendString(" $ref", true)

        ApplicationManager.getApplication().invokeLater {
            inputTracker.reset()
        }
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

    private object SubmitEnterGate {
        @Volatile
        private var lastProcessedEnterWhen = Long.MIN_VALUE

        fun tryProcess(event: KeyEvent): Boolean {
            val enterWhen = event.`when`
            synchronized(this) {
                if (enterWhen == lastProcessedEnterWhen) return false
                lastProcessedEnterWhen = enterWhen
                return true
            }
        }
    }

    companion object {
        private const val PLUGIN_HOOK_VERSION = "1.8.34"
        private val INSTALLED_VERSION = Key.create<String>("cursorterm.editorContextOnSubmit.version")
        private val INSTALLATION = Key.create<Disposable>("cursorterm.editorContextOnSubmit.installation")
        private val SHELL_WIDGET = Key.create<ShellTerminalWidget>("cursorterm.editorContextOnSubmit.shellWidget")

        fun resetForRestart(content: Content) {
            content.getUserData(INSTALLATION)?.let { Disposer.dispose(it) }
            content.putUserData(INSTALLATION, null)
            content.putUserData(INSTALLED_VERSION, null)
            content.putUserData(SHELL_WIDGET, null)
        }

        fun installOnce(project: Project, shellWidget: ShellTerminalWidget, content: Content) {
            val installed = content.getUserData(INSTALLATION)
            if (content.getUserData(INSTALLED_VERSION) == PLUGIN_HOOK_VERSION
                && content.getUserData(SHELL_WIDGET) === shellWidget
                && installed != null
            ) {
                return
            }

            installed?.let { Disposer.dispose(it) }

            val installation = Disposer.newDisposable("EditorContextOnSubmit")
            Disposer.register(content, installation)

            val inputTracker = TerminalInputTracker(shellWidget, installation)
            EditorContextOnSubmitSupport(project, shellWidget, content, inputTracker, installation).install()

            content.putUserData(INSTALLATION, installation)
            content.putUserData(INSTALLED_VERSION, PLUGIN_HOOK_VERSION)
            content.putUserData(SHELL_WIDGET, shellWidget)
        }
    }
}
