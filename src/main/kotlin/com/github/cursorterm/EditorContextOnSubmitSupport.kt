package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.event.KeyEvent
import java.util.function.Consumer

/**
 * 终端按 Enter 时：已有输入 + 当前有激活编辑器 → PreKey 阶段追加 @ 引用，再由终端原生 Enter 提交。
 *
 * 空输入时 consume Enter，不注入、不提交。
 * 不用 AnAction + sendString("\\r")，避免与 cursor-agent TUI 抢 Enter。
 */
class EditorContextOnSubmitSupport private constructor(
    private val project: Project,
    private val shellWidget: ShellTerminalWidget,
    private val inputTracker: TerminalInputTracker,
    private val parentDisposable: Disposable,
) {

    fun install() {
        inputTracker.install()
        val panel = shellWidget.terminalPanel
        var active = true
        val handler = Consumer<KeyEvent> { event ->
            if (!active) return@Consumer
            if (event.id != KeyEvent.KEY_PRESSED) return@Consumer
            if (!isPlainEnter(event)) return@Consumer
            if (event.isConsumed) return@Consumer
            if (EditorContextCollector.collect(project) == null) return@Consumer

            if (!inputTracker.hasUserInput()) {
                event.consume()
                return@Consumer
            }

            if (inputTracker.consumeLineContinuationEnter()) {
                return@Consumer
            }

            val ref = EditorContextCollector.collect(project)?.toAtNotation() ?: return@Consumer
            val starter = shellWidget.terminalStarter ?: return@Consumer
            starter.sendString(" $ref", true)
            inputTracker.reset()
        }
        panel.addPreKeyEventHandler(handler)
        Disposer.register(parentDisposable) {
            active = false
        }
    }

    private fun isPlainEnter(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.VK_ENTER && event.modifiersEx == 0

    companion object {
        private const val PLUGIN_HOOK_VERSION = "1.7.4"
        private val INSTALLED_VERSION = Key.create<String>("cursorterm.editorContextOnSubmit.version")

        fun installOnce(project: Project, shellWidget: ShellTerminalWidget, content: Content) {
            if (content.getUserData(INSTALLED_VERSION) == PLUGIN_HOOK_VERSION) return
            content.putUserData(INSTALLED_VERSION, PLUGIN_HOOK_VERSION)
            val inputTracker = TerminalInputTracker(shellWidget, content)
            EditorContextOnSubmitSupport(project, shellWidget, inputTracker, content).install()
        }
    }
}
