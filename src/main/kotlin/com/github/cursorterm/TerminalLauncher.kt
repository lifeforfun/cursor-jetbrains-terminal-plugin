package com.github.cursorterm

import com.intellij.openapi.project.Project

object TerminalLauncher {

    /**
     * 每次打开 Tool Window 都启动全新的 cursor-agent 进程。
     * 不复用 tmux 会话，避免历史对话残留在 scrollback 里。
     */
    fun buildShellCommand(@Suppress("UNUSED_PARAMETER") project: Project): List<String> {
        return listOf("cursor-agent")
    }
}
