package com.github.cursorterm

import com.github.cursorterm.terminal.TerminalReflection
import com.intellij.openapi.Disposable
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.Component

internal object TerminalBootstrap {

    /** 创建 Block 终端、挂载面板，再 openSession（configureStartupOptions 仅由终端插件在 BGT 调用一次）。 */
    fun startBlocking(
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
