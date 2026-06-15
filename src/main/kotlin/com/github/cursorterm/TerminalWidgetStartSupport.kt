package com.github.cursorterm

import com.intellij.openapi.Disposable
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.Component

internal object TerminalWidgetStartSupport {

    fun start(
        runner: Any,
        parent: Disposable,
        options: ShellStartupOptions,
    ): Any {
        invokeStartMethod(runner, "startShellTerminalWidget", parent, options, defer = true)?.let { return it }
        invokeStartMethod(runner, "createShellTerminalWidget", parent, options, defer = null)?.let { return it }
        error("Unsupported terminal runner: ${runner.javaClass.name}")
    }

    fun uiComponent(terminalWidget: Any, shellWidget: Any): Component {
        resolveUiComponent(terminalWidget)?.let { return it }

        unwrapInnerWidget(terminalWidget)?.let { inner ->
            resolveUiComponent(inner)?.let { return it }
        }

        resolveUiComponent(shellWidget)?.let { return it }

        error("Cannot resolve terminal widget component: ${terminalWidget.javaClass.name}")
    }

    private fun resolveUiComponent(target: Any): Component? {
        for (methodName in OUTER_COMPONENT_METHODS) {
            invokeComponentGetter(target, methodName)?.let { return it }
        }
        if (target is Component) {
            return target
        }
        return null
    }

    private fun unwrapInnerWidget(target: Any): Any? {
        for (methodName in INNER_WIDGET_METHODS) {
            try {
                val method = target.javaClass.getMethod(methodName)
                return method.invoke(target)
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    private fun invokeComponentGetter(target: Any, methodName: String): Component? {
        return try {
            when (val result = target.javaClass.getMethod(methodName).invoke(target)) {
                is Component -> result
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun invokeStartMethod(
        runner: Any,
        methodName: String,
        parent: Disposable,
        options: ShellStartupOptions,
        defer: Boolean?,
    ): Any? {
        return try {
            val method = if (defer == null) {
                runner.javaClass.getMethod(
                    methodName,
                    Disposable::class.java,
                    ShellStartupOptions::class.java,
                )
            } else {
                runner.javaClass.getMethod(
                    methodName,
                    Disposable::class.java,
                    ShellStartupOptions::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            }
            when (defer) {
                null -> method.invoke(runner, parent, options)
                else -> method.invoke(runner, parent, options, defer)
            }
        } catch (_: Exception) {
            null
        }
    }

    private val OUTER_COMPONENT_METHODS = listOf(
        "getComponent",
        "getPreferredFocusableComponent",
        "getTerminalPanel",
    )

    private val INNER_WIDGET_METHODS = listOf(
        "getWidget",
        "widget",
        "asJediTermWidget",
        "getJediTermWidget",
    )
}
