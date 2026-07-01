package com.github.cursorterm

import com.github.cursorterm.terminal.TerminalReflection
import com.github.cursorterm.terminal.TerminalRunnerFactory
import com.intellij.openapi.Disposable
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.Component

internal object TerminalBootstrap {

    fun startBlocking(
        runner: LocalTerminalDirectRunner,
        parent: Disposable,
        options: ShellStartupOptions,
        panel: java.awt.Container,
    ): TerminalWidget {
        val widget = if (TerminalRunnerFactory.isBlockRunner(runner)) {
            startBlockTerminal(runner, parent, options, panel)
        } else {
            startClassicTerminal(runner, parent, options, panel)
        }
        return widget
    }

    /** Block 终端：先挂载 widget，再 openSession（configureStartupOptions 仅调用一次）。 */
    private fun startBlockTerminal(
        runner: LocalTerminalDirectRunner,
        parent: Disposable,
        options: ShellStartupOptions,
        panel: java.awt.Container,
    ): TerminalWidget {
        val widget = createShellWidget(runner, parent)
        mountCenter(panel, widget.component)
        openShellSession(runner, widget, options.builder().widget(widget).build())
        return widget
    }

    /** Classic 终端（GoLand 2023.x 等）：沿用 startShellTerminalWidget 流程。 */
    private fun startClassicTerminal(
        runner: LocalTerminalDirectRunner,
        parent: Disposable,
        options: ShellStartupOptions,
        panel: java.awt.Container,
    ): TerminalWidget {
        val configured = runner.configureStartupOptions(options)
        val widget = runner.startShellTerminalWidget(parent, configured, false)
        mountCenter(panel, widget.component)
        return widget
    }

    fun uiComponent(widget: TerminalWidget): Component = widget.component

    fun mountCenter(panel: java.awt.Container, ui: Component) {
        clearCenterSlot(panel)
        if (panel.layout == null) {
            panel.layout = java.awt.BorderLayout()
        }
        when (panel.layout) {
            is java.awt.BorderLayout -> panel.add(ui, java.awt.BorderLayout.CENTER)
            else -> panel.add(ui)
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun createShellWidget(runner: LocalTerminalDirectRunner, parent: Disposable): TerminalWidget {
        val method = TerminalReflection.findMethod(
            runner,
            "createShellTerminalWidget",
            Disposable::class.java,
            ShellStartupOptions::class.java,
        ) ?: error("AbstractTerminalRunner.createShellTerminalWidget not found")
        return method.invoke(runner, parent, ShellStartupOptions.Builder().build()) as TerminalWidget
    }

    private fun openShellSession(
        runner: LocalTerminalDirectRunner,
        widget: TerminalWidget,
        options: ShellStartupOptions,
    ) {
        val method = TerminalReflection.findMethod(
            runner,
            "openSession",
            TerminalWidget::class.java,
            ShellStartupOptions::class.java,
        ) ?: error("AbstractTerminalRunner.openSession not found")
        method.invoke(runner, widget, options)
    }

    private fun clearCenterSlot(panel: java.awt.Container) {
        val layout = panel.layout as? java.awt.BorderLayout ?: return
        panel.components.toList().forEach { child ->
            if (layout.getConstraints(child) == java.awt.BorderLayout.CENTER) {
                panel.remove(child)
            }
        }
    }
}
