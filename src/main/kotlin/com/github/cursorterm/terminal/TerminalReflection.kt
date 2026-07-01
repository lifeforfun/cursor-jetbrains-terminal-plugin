package com.github.cursorterm.terminal

import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.TerminalPanel
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.lang.reflect.Field
import java.lang.reflect.Method

/** JediTerm / Block 终端反射访问。 */
internal object TerminalReflection {

    fun textBufferFromPanel(panel: TerminalPanel): TerminalTextBuffer? =
        readField(panel, "myTerminalTextBuffer") as? TerminalTextBuffer

    fun terminalStarter(widget: ShellTerminalWidget): TerminalStarter? = try {
        readField(widget.terminalPanel, "myTerminalStarter") as? TerminalStarter ?: widget.terminalStarter
    } catch (_: Exception) {
        widget.terminalStarter
    }

    fun ttyConnector(starter: TerminalStarter): TtyConnector? =
        readField(starter, "myTtyConnector") as? TtyConnector

    fun readField(target: Any, name: String): Any? = field(target, name)?.get(target)

    fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val argTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(name, *argTypes)
                if (method.trySetAccessible()) {
                    return method.invoke(target, *args)
                }
            } catch (_: NoSuchMethodException) {
            }
            clazz = clazz.superclass
        }
        return null
    }

    fun findMethod(target: Any, name: String, vararg paramTypes: Class<*>): Method? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(name, *paramTypes)
                if (method.trySetAccessible()) return method
            } catch (_: NoSuchMethodException) {
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun field(target: Any, name: String): Field? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(name)
                if (f.trySetAccessible()) return f
            } catch (_: NoSuchFieldException) {
            }
            clazz = clazz.superclass
        }
        return null
    }
}
