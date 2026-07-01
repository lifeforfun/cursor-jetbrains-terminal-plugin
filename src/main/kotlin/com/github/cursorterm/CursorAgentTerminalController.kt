package com.github.cursorterm

import com.github.cursorterm.feature.ImagePasteFeature
import com.github.cursorterm.feature.PathInjectFeature
import com.github.cursorterm.feature.SessionFeature
import com.github.cursorterm.feature.TerminalInteractionFeature
import com.github.cursorterm.terminal.TerminalAccess
import com.github.cursorterm.terminal.TerminalScrollSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 工具窗编排：会话、路径注入、图片粘贴与终端交互增强彼此独立。
 */
class CursorAgentTerminalController(
    private val project: Project,
    content: Content,
    private val panel: JPanel,
    toolbar: JPanel,
    projectDir: String,
) {
    private val session = SessionFeature(project, content, panel, projectDir)
    private val imagePaste = ImagePasteFeature()
    private val placeholder = JLabel("正在自动开启 cursor-agent 会话…")

    init {
        panel.add(placeholder, BorderLayout.CENTER)
        toolbar.layout = FlowLayout(FlowLayout.LEFT, 8, 4)
        toolbar.add(JButton("开启会话").apply {
            toolTipText = "会话进行中再次点击将开启全新对话"
            addActionListener { onStartSession() }
        })
        toolbar.add(JButton("注入路径").apply {
            toolTipText = "向终端注入当前激活标签页的 @ 路径"
            addActionListener { onInjectPath() }
        })
        toolbar.add(JButton("滚到底部").apply {
            toolTipText = "将终端滚动到最底部（输入区）"
            addActionListener { onScrollToBottom() }
        })
        autoStartSessionIfNeeded()
    }

    fun autoStartSessionIfNeeded() {
        session.autoResumeIfNeeded(::onSessionReady)
    }

    private fun onStartSession() {
        session.start(::onSessionReady)
    }

    private fun onSessionReady(access: TerminalAccess) {
        val disposable = session.sessionDisposable() ?: return
        imagePaste.install(access, disposable)
        TerminalInteractionFeature.install(project, access, disposable)
    }

    private fun onInjectPath() {
        if (!session.isLive()) {
            session.autoResumeIfNeeded { access ->
                onSessionReady(access)
                PathInjectFeature.inject(project, access)
            }
            return
        }
        PathInjectFeature.inject(project, session.terminalAccess())
    }

    private fun onScrollToBottom() {
        val access = session.terminalAccess() ?: run {
            session.autoResumeIfNeeded { ready ->
                onSessionReady(ready)
                TerminalScrollSupport.scrollToBottom(ready)
            }
            return
        }
        TerminalScrollSupport.scrollToBottom(access)
    }

    companion object {
        const val TOOL_WINDOW_ID = "com.github.cursorterm.agent"
        val CONTROLLER_KEY: Key<CursorAgentTerminalController> = Key.create("cursorterm.controller")

        fun of(project: Project): CursorAgentTerminalController? =
            ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)
                ?.contentManager
                ?.getContent(0)
                ?.getUserData(CONTROLLER_KEY)
    }
}
