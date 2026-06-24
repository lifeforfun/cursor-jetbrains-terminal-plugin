package com.github.cursorterm

import com.intellij.openapi.project.Project

object TerminalLauncher {

    enum class SessionMode {
        /** 恢复项目最近一次有内容的对话（工具窗首次打开默认行为）。 */
        RESUME_LAST,

        /** 不恢复历史，启动全新 cursor-agent 对话。 */
        NEW_CHAT,
    }

    /**
     * 通过 login shell 启动 cursor-agent。
     *
     * cursor-agent 实际是 `#!/bin/sh` + `exec cursor agent "$@"` 的包装脚本；
     * 插件若直接 shellCommand(["cursor-agent"])，子进程 PATH 被 IDE 裁成 /usr/bin:/bin:...，
     * 脚本内找不到 `cursor` 命令而失败。普通终端标签页先起 zsh 加载 profile，所以手动输入正常。
     */
    fun buildShellCommand(project: Project, mode: SessionMode = SessionMode.RESUME_LAST): List<String> {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        return listOf(shell, "-lc", buildLaunchCommand(project, mode))
    }

    fun buildLaunchCommand(project: Project, mode: SessionMode = SessionMode.RESUME_LAST): String {
        val executable = agentExecutable()
        val parts = mutableListOf("exec", shellQuote(executable))
        if (mode == SessionMode.RESUME_LAST) {
            CursorAgentSessionStore.findLastSessionId(project.basePath)?.let { sessionId ->
                parts += listOf("--resume", shellQuote(sessionId))
            }
        }
        return parts.joinToString(" ")
    }

    private fun agentExecutable(): String =
        System.getenv("CURSOR_AGENT_PATH")?.takeIf { it.isNotBlank() } ?: "cursor-agent"

    private fun shellQuote(value: String): String =
        if (value.none { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' || it == '$' }) {
            value
        } else {
            "'" + value.replace("'", "'\\''") + "'"
        }
}
