package com.github.cursorterm

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

object TerminalLauncher {
    private val LOG = Logger.getInstance(TerminalLauncher::class.java)

    data class LaunchSpec(val shellCommand: List<String>, val resumedSessionId: String?)

    fun buildLaunchSpec(
        project: Project,
        preferredSessionId: String? = null,
        resume: Boolean = true,
    ): LaunchSpec {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        val resumed = if (resume) {
            CursorAgentSessionStore.findLastSessionId(project.basePath, preferredSessionId)
        } else {
            null
        }
        val cmd = buildCommand(resumed)
        LOG.info("cursorterm launch: project=${project.basePath} resumed=$resumed command=$cmd")
        return LaunchSpec(listOf(shell, "-lc", cmd), resumed)
    }

    private fun buildCommand(resumed: String?): String {
        val exe = System.getenv("CURSOR_AGENT_PATH")?.takeIf { it.isNotBlank() } ?: "cursor-agent"
        val parts = mutableListOf("exec", quote(exe))
        if (!resumed.isNullOrBlank()) parts += listOf("--resume", quote(resumed))
        return parts.joinToString(" ")
    }

    private fun quote(v: String) =
        if (v.none { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' || it == '$' }) v
        else "'" + v.replace("'", "'\\''") + "'"
}
