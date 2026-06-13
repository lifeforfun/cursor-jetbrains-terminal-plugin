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
 * Enter 提交时：输入框有内容 + 有激活编辑器 → PreKey 追加 @ 引用，再由终端原生 Enter 提交。
 *
 * 输入框为空时不注入 @，也不 consume Enter（待发送列表操作 / agent 处理中仍可用 Enter）。
 * Shift+Enter 留给 cursor-agent 换行或打断，不注入 @。
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
        var lastInjectAtMs = 0L
        val handler = Consumer<KeyEvent> { event ->
            if (!active) return@Consumer
            if (event.id != KeyEvent.KEY_PRESSED) return@Consumer
            if (event.isConsumed) return@Consumer

            if (isShiftEnter(event)) {
                inputTracker.onShiftEnter()
                return@Consumer
            }

            if (!isSubmitEnter(event)) return@Consumer
            if (EditorContextCollector.collect(project) == null) return@Consumer
            if (!inputTracker.hasUserInput()) return@Consumer
            if (inputTracker.consumeLineContinuationEnter()) return@Consumer

            val now = System.currentTimeMillis()
            if (now - lastInjectAtMs < 300) return@Consumer
            lastInjectAtMs = now

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

    private fun isSubmitEnter(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.VK_ENTER && !hasSubmitModifiers(event)

    private fun isShiftEnter(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.VK_ENTER && event.isShiftDown

    private fun hasSubmitModifiers(event: KeyEvent): Boolean =
        event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown ||
            event.modifiersEx != 0

    companion object {
        private const val PLUGIN_HOOK_VERSION = "1.8.0"
        private val INSTALLED_VERSION = Key.create<String>("cursorterm.editorContextOnSubmit.version")
        private val INSTALLATION = Key.create<Disposable>("cursorterm.editorContextOnSubmit.installation")

        fun installOnce(project: Project, shellWidget: ShellTerminalWidget, content: Content) {
            if (content.getUserData(INSTALLED_VERSION) == PLUGIN_HOOK_VERSION) return

            content.getUserData(INSTALLATION)?.let { Disposer.dispose(it) }

            val installation = Disposer.newDisposable("EditorContextOnSubmit")
            content.putUserData(INSTALLATION, installation)
            content.putUserData(INSTALLED_VERSION, PLUGIN_HOOK_VERSION)
            Disposer.register(content, installation)

            val inputTracker = TerminalInputTracker(shellWidget, installation)
            EditorContextOnSubmitSupport(project, shellWidget, inputTracker, installation).install()
        }
    }
}
