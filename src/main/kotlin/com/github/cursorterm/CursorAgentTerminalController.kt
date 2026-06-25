package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JButton
import javax.swing.JPanel

/**
 * 管理工具窗内 cursor-agent 终端的生命周期。
 */
class CursorAgentTerminalController(
    private val project: Project,
    private val content: Content,
    private val panel: JPanel,
    private val toolbar: JPanel,
    private val projectDir: String,
) {

    @Volatile
    private var sessionDisposable: Disposable? = null

    @Volatile
    private var shellWidget: ShellTerminalWidget? = null

    @Volatile
    private var terminalComponent: Component? = null

    @Volatile
    private var starting = false

    @Volatile
    private var startingInitial = false

    private var newChatButton: JButton? = null

    fun createToolbar(): JPanel {
        newChatButton = JButton("开启新对话").apply {
            toolTipText = "结束当前对话并启动全新的 cursor-agent 会话"
            addActionListener { startNewConversation() }
        }
        toolbar.add(newChatButton)
        return toolbar
    }

    fun startInitialSessionIfNeeded() {
        startInitialSession()
    }

    fun startInitialSession() {
        if (hasLiveSession() || starting || startingInitial) {
            return
        }
        startingInitial = true
        ApplicationManager.getApplication().invokeLater {
            try {
                if (!hasLiveSession() && !starting) {
                    startSession(TerminalLauncher.SessionMode.RESUME_LAST)
                }
            } finally {
                startingInitial = false
            }
        }
    }

    fun startNewConversation() {
        if (starting || startingInitial) return
        starting = true
        newChatButton?.isEnabled = false
        CursorAgentSessionStore.invalidateCache(project.basePath)

        ApplicationManager.getApplication().invokeLater {
            try {
                stopCurrentSession()
                startSession(TerminalLauncher.SessionMode.NEW_CHAT)
            } finally {
                starting = false
                newChatButton?.isEnabled = true
            }
        }
    }

    private fun hasLiveSession(): Boolean {
        val disposable = sessionDisposable ?: return false
        return !Disposer.isDisposed(disposable) && shellWidget != null && terminalComponent != null
    }

    private fun stopCurrentSession() {
        sessionDisposable?.let { Disposer.dispose(it) }
        sessionDisposable = null
        shellWidget = null
        EditorContextOnSubmitSupport.resetForRestart(content)
        TerminalScrollFix.clearForRestart(content)
        terminalComponent?.let { panel.remove(it) }
        terminalComponent = null
        panel.revalidate()
        panel.repaint()
    }

    private fun startSession(mode: TerminalLauncher.SessionMode) {
        if (hasLiveSession()) {
            stopCurrentSession()
        }

        val launchSpec = TerminalLauncher.buildLaunchSpec(project, mode)
        launchSpec.resumedSessionId?.let { sessionId ->
            CursorAgentSessionStore.recordActiveSession(project.basePath, sessionId)
        }

        val disposable = Disposer.newDisposable("CursorAgentTerminalSession")
        Disposer.register(content, disposable)
        sessionDisposable = disposable

        try {
            val runner = LocalTerminalDirectRunner.createTerminalRunner(project)
            val options = ShellStartupOptions.Builder()
                .workingDirectory(projectDir)
                .shellCommand(launchSpec.shellCommand)
                .build()

            val terminalWidget = TerminalWidgetStartSupport.start(runner, disposable, options)
            val widget = ShellTerminalWidgetSupport.resolve(terminalWidget)
            shellWidget = widget

            val ui = TerminalWidgetStartSupport.uiComponent(terminalWidget, widget)
            terminalComponent = ui
            panel.add(ui, BorderLayout.CENTER)
            panel.revalidate()
            panel.repaint()

            EditorContextOnSubmitSupport.installOnce(project, widget, content)
            TerminalScrollFix.installOn(content, widget, disposable)
            try {
                ImagePasteSupport(widget, disposable).install()
            } catch (_: Exception) {
                // 可选功能
            }

            scheduleSessionDiscovery(disposable)
        } catch (e: Exception) {
            Disposer.dispose(disposable)
            sessionDisposable = null
            shellWidget = null
            terminalComponent = null
            showStartFailure(e)
        }
    }

    private fun scheduleSessionDiscovery(parentDisposable: Disposable) {
        val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)
        listOf(1_000, 3_000, 8_000).forEach { delayMs ->
            alarm.addRequest({
                ApplicationManager.getApplication().invokeLater {
                    if (!hasLiveSession()) return@invokeLater
                    CursorAgentSessionStore.discoverLatestSession(project.basePath)?.let { sessionId ->
                        CursorAgentSessionStore.recordActiveSession(project.basePath, sessionId)
                    }
                }
            }, delayMs)
        }
    }

    private fun showStartFailure(error: Exception) {
        error.printStackTrace()
        panel.removeAll()
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(
            javax.swing.JLabel("Cursor Agent 启动失败: ${error.message ?: error.javaClass.simpleName}"),
            BorderLayout.CENTER,
        )
        panel.revalidate()
        panel.repaint()
    }

    companion object {
        val CONTROLLER_KEY: Key<CursorAgentTerminalController> =
            Key.create("cursorterm.agentTerminalController")
    }
}
