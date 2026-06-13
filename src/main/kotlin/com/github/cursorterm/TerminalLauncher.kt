package com.github.cursorterm

import com.intellij.openapi.project.Project

object TerminalLauncher {

    /**
     * 通过 login shell 启动 cursor-agent。
     *
     * cursor-agent 实际是 `#!/bin/sh` + `exec cursor agent "$@"` 的包装脚本；
     * 插件若直接 shellCommand(["cursor-agent"])，子进程 PATH 被 IDE 裁成 /usr/bin:/bin:...，
     * 脚本内找不到 `cursor` 命令而失败。普通终端标签页先起 zsh 加载 profile，所以手动输入正常。
     */
    fun buildShellCommand(project: Project): List<String> {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        return listOf(shell, "-lc", buildLaunchCommand(project))
    }

    private fun buildLaunchCommand(project: Project): String {
        val executable = System.getenv("CURSOR_AGENT_PATH")?.takeIf { it.isNotBlank() } ?: "cursor-agent"
        val parts = mutableListOf("exec", shellQuote(executable))
        CursorAgentSessionStore.findLastSessionId(project.basePath)?.let { sessionId ->
            parts += listOf("--resume", shellQuote(sessionId))
        }
        return parts.joinToString(" ")
    }

    private fun shellQuote(value: String): String =
        if (value.none { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' || it == '$' }) {
            value
        } else {
            "'" + value.replace("'", "'\\''") + "'"
        }
}
