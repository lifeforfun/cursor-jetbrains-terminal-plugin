package com.github.cursorterm

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil

object TerminalLauncher {
    private val LOG = Logger.getInstance(TerminalLauncher::class.java)

    data class LaunchSpec(
        val shellCommand: List<String>,
        val resumedSessionId: String?,
    )

    fun buildLaunchSpec(
        project: Project,
        preferredSessionId: String? = null,
        resume: Boolean = true,
    ): LaunchSpec {
        val resumed = if (resume) {
            CursorAgentSessionStore.findLastSessionId(project.basePath, preferredSessionId)
        } else {
            null
        }
        val command = buildCommand(resolveCursorAgentExecutable(), resumed)
        LOG.info("cursorterm launch: project=${project.basePath} resumed=$resumed command=$command")
        return LaunchSpec(command, resumed)
    }

    /** 解析 cursor-agent：CURSOR_AGENT_PATH 或 IDE 从用户 shell 还原的 PATH。 */
    private fun resolveCursorAgentExecutable(): String {
        readEnv("CURSOR_AGENT_PATH")?.let { return it }
        PathEnvironmentVariableUtil.findInPath("cursor-agent")?.absolutePath?.let { return it }
        return "cursor-agent"
    }

    /** 直接启动 cursor-agent，不经 shell 包装。 */
    private fun buildCommand(exe: String, resumed: String?): List<String> = buildList {
        add(exe)
        if (!resumed.isNullOrBlank()) {
            add("--resume")
            add(resumed)
        }
    }

    private fun readEnv(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: EnvironmentUtil.getValue(name)?.takeIf { it.isNotBlank() }
}
