package com.github.cursorterm

import org.jetbrains.plugins.terminal.ShellTerminalWidget

/**
 * 跨 IDE 版本解析 [ShellTerminalWidget]。
 *
 * `toShellJediTermWidgetOrThrow(TerminalWidget)` 仅在较新 Terminal 插件中存在；
 * PyCharm 2023.3 等旧版需走 `asShellJediTermWidget` / 直接类型判断。
 */
internal object ShellTerminalWidgetSupport {

    fun resolve(widget: Any): ShellTerminalWidget {
        if (widget is ShellTerminalWidget) return widget

        val shellClass = ShellTerminalWidget::class.java
        for (methodName in RESOLVE_METHODS) {
            invokeStatic(shellClass, methodName, widget)?.let { result ->
                if (result is ShellTerminalWidget) return result
            }
        }

        error("Unsupported terminal widget type: ${widget.javaClass.name}")
    }

    private fun invokeStatic(clazz: Class<*>, methodName: String, arg: Any): Any? {
        for (paramType in parameterTypesFor(arg)) {
            try {
                val method = clazz.getMethod(methodName, paramType)
                return method.invoke(null, arg)
            } catch (_: Exception) {
                // try next parameter type
            }
        }
        return null
    }

    private fun parameterTypesFor(arg: Any): List<Class<*>> {
        val types = linkedSetOf<Class<*>>()
        types.add(arg.javaClass)
        generateSequence(arg.javaClass.superclass) { it.superclass }.filterNotNull().forEach { types.add(it) }
        for (name in FALLBACK_PARAM_TYPES) {
            try {
                types.add(Class.forName(name))
            } catch (_: ClassNotFoundException) {
                // optional API
            }
        }
        return types.toList()
    }

    private val RESOLVE_METHODS = listOf(
        "toShellJediTermWidgetOrThrow",
        "asShellJediTermWidget",
        "asJediTermWidget",
    )

    private val FALLBACK_PARAM_TYPES = listOf(
        "com.intellij.terminal.ui.TerminalWidget",
        "com.intellij.terminal.JBTerminalWidget",
    )
}
