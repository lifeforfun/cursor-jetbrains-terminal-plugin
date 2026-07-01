package com.github.cursorterm.terminal

import com.intellij.terminal.ui.TerminalWidget
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.TerminalPanel
import org.jetbrains.plugins.terminal.LocalBlockTerminalRunner
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import javax.swing.JComponent

/** 统一 Block / Classic 终端的输入输出与视图访问。 */
class TerminalAccess(val widget: TerminalWidget) {

    val focusComponent: JComponent
        get() = widget.preferredFocusableComponent

    fun shellWidgetOrNull(): ShellTerminalWidget? {
        ShellTerminalWidget.asShellJediTermWidget(widget)?.let { return it }
        return BlockTerminalReflection.shellWidgetFromContentView(widget)
    }

    fun terminalPanelOrNull(): TerminalPanel? = shellWidgetOrNull()?.terminalPanel

    fun sendString(text: String) {
        terminalStarterOrNull()?.let {
            it.sendString(text, true)
            return
        }
        widget.ttyConnectorAccessor.executeWithTtyConnector { connector ->
            connector.write(text)
        }
    }

    fun sendBytes(bytes: ByteArray) {
        terminalStarterOrNull()?.let {
            it.sendBytes(bytes, true)
            return
        }
        widget.ttyConnectorAccessor.executeWithTtyConnector { connector ->
            connector.write(String(bytes, Charsets.ISO_8859_1))
        }
    }

    fun ttyConnectorOrNull(): TtyConnector? = try {
        widget.ttyConnector ?: terminalStarterOrNull()?.let { TerminalReflection.ttyConnector(it) }
    } catch (_: Exception) {
        terminalStarterOrNull()?.let { TerminalReflection.ttyConnector(it) }
    }

    private fun terminalStarterOrNull(): TerminalStarter? =
        shellWidgetOrNull()?.terminalStarter
            ?: BlockTerminalReflection.terminalStarter(widget)

    companion object {
        fun createRunner(project: com.intellij.openapi.project.Project): LocalTerminalDirectRunner =
            LocalBlockTerminalRunner(project)
    }
}
