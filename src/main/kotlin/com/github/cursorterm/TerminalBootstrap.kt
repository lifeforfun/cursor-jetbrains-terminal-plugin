package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.Component

internal object TerminalBootstrap {

    fun start(runner: LocalTerminalDirectRunner, parent: Disposable, options: ShellStartupOptions): TerminalWidget {
        val configured = runner.configureStartupOptions(options)
        return runner.startShellTerminalWidget(parent, configured, true)
    }

    fun uiComponent(widget: TerminalWidget): Component = widget.component
}
