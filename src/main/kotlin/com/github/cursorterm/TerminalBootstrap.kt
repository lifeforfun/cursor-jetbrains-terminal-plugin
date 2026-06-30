package com.github.cursorterm

import com.intellij.openapi.Disposable
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.Component

internal object TerminalBootstrap {
    fun start(runner: Any, parent: Disposable, options: ShellStartupOptions): Any {
        invoke(runner, "startShellTerminalWidget", parent, options, true)?.let { return it }
        invoke(runner, "createShellTerminalWidget", parent, options, null)?.let { return it }
        error("Unsupported terminal runner: ${runner.javaClass.name}")
    }

    fun resolveShellWidget(widget: Any): ShellTerminalWidget {
        if (widget is ShellTerminalWidget) return widget
        val shellClass = ShellTerminalWidget::class.java
        for (method in listOf("toShellJediTermWidgetOrThrow", "asShellJediTermWidget", "asJediTermWidget")) {
            for (t in typesFor(widget)) {
                try {
                    val r = shellClass.getMethod(method, t).invoke(null, widget)
                    if (r is ShellTerminalWidget) return r
                } catch (_: Exception) {
                }
            }
        }
        error("Unsupported terminal widget: ${widget.javaClass.name}")
    }

    fun uiComponent(terminalWidget: Any, shell: ShellTerminalWidget): Component {
        component(terminalWidget)?.let { return it }
        inner(terminalWidget)?.let { component(it)?.let { c -> return c } }
        return component(shell) ?: error("No terminal UI component")
    }

    private fun component(target: Any): Component? {
        for (m in listOf("getComponent", "getPreferredFocusableComponent", "getTerminalPanel")) {
            try {
                (target.javaClass.getMethod(m).invoke(target) as? Component)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return target as? Component
    }

    private fun inner(target: Any): Any? {
        for (m in listOf("getWidget", "widget", "asJediTermWidget", "getJediTermWidget")) {
            try {
                return target.javaClass.getMethod(m).invoke(target)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun typesFor(arg: Any): List<Class<*>> {
        val types = linkedSetOf<Class<*>>()
        types.add(arg.javaClass)
        generateSequence(arg.javaClass.superclass) { it.superclass }.filterNotNull().forEach { types.add(it) }
        for (n in listOf("com.intellij.terminal.ui.TerminalWidget", "com.intellij.terminal.JBTerminalWidget")) {
            try {
                types.add(Class.forName(n))
            } catch (_: ClassNotFoundException) {
            }
        }
        return types.toList()
    }

    private fun invoke(runner: Any, method: String, parent: Disposable, options: ShellStartupOptions, defer: Boolean?): Any? =
        try {
            if (defer == null) {
                runner.javaClass.getMethod(method, Disposable::class.java, ShellStartupOptions::class.java)
                    .invoke(runner, parent, options)
            } else {
                runner.javaClass.getMethod(
                    method, Disposable::class.java, ShellStartupOptions::class.java, Boolean::class.javaPrimitiveType,
                ).invoke(runner, parent, options, defer)
            }
        } catch (_: Exception) {
            null
        }
}
