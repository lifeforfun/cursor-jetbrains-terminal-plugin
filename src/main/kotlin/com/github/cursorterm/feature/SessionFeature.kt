package com.github.cursorterm.feature

import com.github.cursorterm.CursorAgentSessionStore
import com.github.cursorterm.DebugAgentLog
import com.github.cursorterm.TerminalBootstrap
import com.github.cursorterm.TerminalLauncher
import com.github.cursorterm.terminal.TerminalAccess
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 功能一：开启会话。使用 Block 终端，仅负责 cursor-agent 生命周期。
 */
class SessionFeature(
    private val project: Project,
    private val content: Content,
    private val panel: JPanel,
    private val projectDir: String,
) {
    @Volatile private var sessionDisposable: Disposable? = null
    @Volatile private var terminalWidget: TerminalWidget? = null
    @Volatile private var terminalComponent: Component? = null
    @Volatile private var starting = false
    @Volatile private var sessionLaunchAtMs = 0L

    fun isLive(): Boolean {
        val d = sessionDisposable ?: return false
        return !Disposer.isDisposed(d) && terminalWidget != null
    }

    fun terminalAccess(): TerminalAccess? = terminalWidget?.let { TerminalAccess(it) }

    fun sessionDisposable(): Disposable? = sessionDisposable

    fun autoResumeIfNeeded(onReady: (TerminalAccess) -> Unit) {
        if (starting || isLive()) return
        ApplicationManager.getApplication().invokeLater {
            if (starting || isLive()) return@invokeLater
            DebugAgentLog.write("H-SESS", "SessionFeature", "auto-start", emptyMap())
            startInternal(onReady, newChat = false)
        }
    }

    fun start(onReady: (TerminalAccess) -> Unit) {
        if (starting) return
        if (isLive()) {
            if (Messages.showYesNoDialog(
                    project,
                    "将结束当前对话并开启全新 cursor-agent 会话。是否继续？",
                    "开启新会话",
                    Messages.getWarningIcon(),
                ) != Messages.YES
            ) {
                return
            }
            ApplicationManager.getApplication().invokeLater { startInternal(onReady, newChat = true) }
            return
        }
        ApplicationManager.getApplication().invokeLater { startInternal(onReady, newChat = false) }
    }

    fun stop() {
        sessionDisposable?.let { Disposer.dispose(it) }
        sessionDisposable = null
        terminalWidget = null
        terminalComponent?.let { panel.remove(it) }
        terminalComponent = null
        panel.revalidate()
        panel.repaint()
    }

    private fun startInternal(onReady: (TerminalAccess) -> Unit, newChat: Boolean) {
        if (starting) return
        starting = true
        try {
            stop()
            if (newChat) {
                CursorAgentSessionStore.invalidateCache(project.basePath)
                clearBound()
            }
            sessionLaunchAtMs = System.currentTimeMillis()
            val preferred = if (newChat) null else readBound() ?: CursorAgentSessionStore.findLastSessionId(project.basePath)
            val spec = TerminalLauncher.buildLaunchSpec(project, preferred, resume = !newChat)
            spec.resumedSessionId?.let { bind(it) }

            val disposable = Disposer.newDisposable("CursorAgentSession")
            Disposer.register(content, disposable)
            sessionDisposable = disposable

            val runner = TerminalAccess.createRunner(project)
            val options = ShellStartupOptions.Builder()
                .workingDirectory(projectDir)
                .shellCommand(spec.shellCommand)
                .envVariables(mapOf("NO_HYPERLINK" to "1"))
                .build()
            val widget = TerminalBootstrap.startBlocking(runner, disposable, options, panel)
            terminalWidget = widget
            terminalComponent = TerminalBootstrap.uiComponent(widget)
            widget.preferredFocusableComponent.requestFocusInWindow()
            scheduleAdopt(disposable, newChat)
            DebugAgentLog.write(
                "H-SESS",
                "SessionFeature",
                if (newChat) "started-new" else "started",
                mapOf("resumed" to spec.resumedSessionId, "newChat" to newChat),
            )
            onReady(TerminalAccess(widget))
        } catch (e: Exception) {
            onStartFailed(e)
        } finally {
            starting = false
        }
    }

    private fun onStartFailed(e: Exception) {
        stop()
        TerminalBootstrap.mountCenter(panel, JLabel("会话启动失败: ${e.message}"))
        DebugAgentLog.write("H-SESS", "SessionFeature", "failed", mapOf("error" to e.message))
    }

    private fun scheduleAdopt(parent: Disposable, requireFreshSession: Boolean) {
        val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
        listOf(1_000L, 3_000L, 8_000L).forEach { ms ->
            alarm.addRequest({
                ApplicationManager.getApplication().invokeLater {
                    if (!isLive()) return@invokeLater
                    CursorAgentSessionStore.adoptDiscoveredSession(
                        project.basePath,
                        readBound(),
                        sessionLaunchAtMs,
                        requireFreshSession = requireFreshSession,
                    )?.let { bind(it) }
                }
            }, ms)
        }
    }

    private fun readBound(): String? = content.getUserData(BOUND_KEY)
    private fun bind(id: String) {
        content.putUserData(BOUND_KEY, id)
        CursorAgentSessionStore.recordActiveSession(project.basePath, id)
    }

    private fun clearBound() = content.putUserData(BOUND_KEY, null)

    companion object {
        private val BOUND_KEY: Key<String> = Key.create("cursorterm.boundSession")
    }
}
