package com.github.cursorterm.terminal

import com.intellij.terminal.ui.TerminalWidget
import com.jediterm.terminal.TerminalStarter
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 通过反射访问 Block 终端内部视图。 */
internal object BlockTerminalReflection {

    fun shellWidgetFromContentView(widget: TerminalWidget): ShellTerminalWidget? {
        val view = contentView(widget) ?: return null
        if (!view.javaClass.simpleName.contains("OldPlain")) return null
        return TerminalReflection.readField(view, "widget") as? ShellTerminalWidget
    }

    fun terminalStarter(widget: TerminalWidget): TerminalStarter? {
        val session = blockSession(widget) ?: return null
        return try {
            val future = TerminalReflection.invoke(session, "getTerminalStarterFuture\$intellij_terminal")
                as? CompletableFuture<*>
            future?.get(500, TimeUnit.MILLISECONDS) as? TerminalStarter
        } catch (_: Exception) {
            null
        }
    }

    private fun blockSession(widget: TerminalWidget): Any? {
        val view = contentView(widget) ?: return null
        return TerminalReflection.readField(view, "session")
    }

    private fun contentView(widget: TerminalWidget): Any? =
        TerminalReflection.readField(widget, "view")
}
