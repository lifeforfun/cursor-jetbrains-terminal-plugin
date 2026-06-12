package com.github.cursorterm

import com.intellij.openapi.project.Project

object TerminalLauncher {

    /**
     * 启动 cursor-agent；若该项目存在历史会话则自动 --resume 最近一次对话。
     */
    fun buildShellCommand(project: Project): List<String> {
        val command = mutableListOf("cursor-agent")
        CursorAgentSessionStore.findLastSessionId(project.basePath)?.let { sessionId ->
            command += listOf("--resume", sessionId)
        }
        return command
    }
}
