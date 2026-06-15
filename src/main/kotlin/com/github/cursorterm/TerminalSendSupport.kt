package com.github.cursorterm

import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

internal object TerminalSendSupport {

    fun sendString(shellWidget: ShellTerminalWidget, text: String): Boolean {
        if (text.isEmpty()) return false

        resolveStarter(shellWidget)?.let { starter ->
            if (invokeSendString(starter, text)) {
                return true
            }
        }

        if (sendViaWriteCharacters(shellWidget, text)) return true
        if (sendViaShellWrite(shellWidget, text)) return true
        return sendViaTtyConnector(shellWidget, text)
    }

    private fun resolveStarter(shellWidget: ShellTerminalWidget): Any? {
        invokeNoArg(shellWidget, "getTerminalStarter")?.let { return it }

        try {
            var found: Any? = null
            val consumer = Consumer<Any> { starter -> found = starter }
            shellWidget.javaClass.getMethod("doWithTerminalStarter", Consumer::class.java)
                .invoke(shellWidget, consumer)
            found?.let { return it }
        } catch (_: Exception) {
            // optional API
        }

        return null
    }

    private fun invokeSendString(starter: Any, text: String): Boolean {
        return try {
            val method = starter.javaClass.getMethod(
                "sendString",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(starter, text, true)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendViaWriteCharacters(shellWidget: ShellTerminalWidget, text: String): Boolean {
        return try {
            writeText(shellWidget, text)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendViaShellWrite(shellWidget: ShellTerminalWidget, text: String): Boolean {
        return try {
            invokeDeclaredStringMethod(shellWidget, "write", text)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeText(target: Any, text: String) {
        var index = 0
        var wrote = false
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\r') {
                index++
                if (index < text.length && text[index] == '\n') index++
                if (invokeNoArg(target, "newLine") != null) {
                    wrote = true
                } else {
                    invokeDeclaredStringMethod(target, "writeCharacters", "\n")
                    wrote = true
                }
                continue
            }
            if (ch == '\n') {
                index++
                if (invokeNoArg(target, "newLine") != null) {
                    wrote = true
                } else {
                    invokeDeclaredStringMethod(target, "writeCharacters", "\n")
                    wrote = true
                }
                continue
            }
            val nextBreak = text.indexOfAny(charArrayOf('\n', '\r'), index).let { if (it < 0) text.length else it }
            invokeDeclaredStringMethod(target, "writeCharacters", text.substring(index, nextBreak))
            wrote = true
            index = nextBreak
        }
        if (!wrote) {
            throw IllegalStateException("no characters written")
        }
    }

    private fun invokeDeclaredStringMethod(target: Any, methodName: String, arg: String) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(methodName, String::class.java)
                method.isAccessible = true
                method.invoke(target, arg)
                return
            } catch (_: Exception) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException("$methodName(String) on ${target.javaClass.name}")
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(methodName)
                method.isAccessible = true
                return method.invoke(target)
            } catch (_: Exception) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    private fun sendViaTtyConnector(shellWidget: ShellTerminalWidget, text: String): Boolean {
        val connector = resolveTtyConnector(shellWidget) ?: return false
        return writeToConnector(connector, text)
    }

    private fun writeToConnector(connector: Any, text: String): Boolean {
        return try {
            val byteMethod = connector.javaClass.getMethod("write", ByteArray::class.java)
            byteMethod.invoke(connector, text.toByteArray(StandardCharsets.UTF_8))
            true
        } catch (_: Exception) {
            try {
                val strMethod = connector.javaClass.getMethod("write", String::class.java)
                strMethod.invoke(connector, text)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun resolveTtyConnector(shellWidget: ShellTerminalWidget): Any? {
        for (methodName in TTY_CONNECTOR_METHODS) {
            invokeNoArg(shellWidget, methodName)?.let { return it }
        }
        try {
            val accessor = shellWidget.javaClass.getMethod("getTtyConnectorAccessor").invoke(shellWidget)
                ?: return null
            var found: Any? = null
            val consumer = Consumer<Any?> { connector ->
                if (connector != null) found = connector
            }
            accessor.javaClass.getMethod("executeWithTtyConnector", Consumer::class.java)
                .invoke(accessor, consumer)
            return found
        } catch (_: Exception) {
            return null
        }
    }

    private val TTY_CONNECTOR_METHODS = listOf("getTtyConnector", "getProcessTtyConnector")
}
