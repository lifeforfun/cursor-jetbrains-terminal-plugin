package com.github.cursorterm

import java.awt.event.KeyEvent
import java.util.function.Consumer

internal object TerminalPreKeySupport {

    fun installFirst(panel: Any, handler: Consumer<KeyEvent>) {
        val prepended = prependHandler(panel, handler)
        if (!prepended) {
            invokeAddPreKeyHandler(panel, handler)
        }
    }

    private fun prependHandler(panel: Any, handler: Consumer<KeyEvent>): Boolean {
        for (fieldName in PRE_KEY_FIELD_NAMES) {
            try {
                val field = panel.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                when (val value = field.get(panel)) {
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (value as MutableList<Consumer<KeyEvent>>).add(0, handler)
                        return true
                    }
                    is java.util.List<*> -> return false
                }
            } catch (_: Exception) {
                // try next field
            }
        }
        return false
    }

    private fun invokeAddPreKeyHandler(panel: Any, handler: Consumer<KeyEvent>) {
        try {
            val method = panel.javaClass.getMethod("addPreKeyEventHandler", Consumer::class.java)
            method.invoke(panel, handler)
        } catch (_: Exception) {
            // best effort
        }
    }

    private val PRE_KEY_FIELD_NAMES = listOf(
        "myPreKeyEventConsumers",
        "preKeyEventConsumers",
    )
}
