package com.github.cursorterm.terminal

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner

/** 按 IDE 版本选择 Block 或 Classic 终端 runner，避免旧版 Terminal 插件缺类。 */
internal object TerminalRunnerFactory {

    private const val BLOCK_RUNNER_CLASS = "org.jetbrains.plugins.terminal.LocalBlockTerminalRunner"

    fun create(project: Project): LocalTerminalDirectRunner {
        return try {
            val clazz = Class.forName(
                BLOCK_RUNNER_CLASS,
                false,
                LocalTerminalDirectRunner::class.java.classLoader,
            )
            clazz.getConstructor(Project::class.java).newInstance(project) as LocalTerminalDirectRunner
        } catch (_: ClassNotFoundException) {
            LocalTerminalDirectRunner(project)
        }
    }

    fun isBlockRunner(runner: LocalTerminalDirectRunner): Boolean =
        runner.javaClass.name == BLOCK_RUNNER_CLASS
}
