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
 * 输入框为空时不注入 @（待发送列表 / agent 处理中仍可用 Enter）。
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
        val handler = Consumer<KeyEvent> { event ->
            if (!active) return@Consumer
            inputTracker.handlePreKeyEvent(event)

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
            if (!EnterInjectGate.tryAcquire(event)) return@Consumer

            val ref = EditorContextCollector.collect(project)?.toAtNotation() ?: return@Consumer
            val starter = shellWidget.terminalStarter ?: return@Consumer
            starter.sendString("${System.lineSeparator()}$ref", true)
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
        event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown

    /**
     * 同一物理 Enter 可能被多个 PreKey 钩子各处理一次；用 [KeyEvent.getWhen] 全局去重。
     */
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
        private const val PLUGIN_HOOK_VERSION = "1.8.7"
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
