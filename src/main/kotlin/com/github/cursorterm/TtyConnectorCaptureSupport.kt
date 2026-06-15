package com.github.cursorterm

import com.intellij.openapi.application.ApplicationManager
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.TerminalStarter
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.util.function.Consumer

/**
 * 通过反射安装 PTY 拦截，兼容各版本 IDE（TtyConnector 可能异步就绪）。
 */
internal object TtyConnectorCaptureSupport {

    fun install(shellWidget: ShellTerminalWidget, onInstalled: (CapturingTtyConnector) -> Unit): Boolean {
        if (installViaAccessor(shellWidget, onInstalled)) return true
        if (installViaWidget(shellWidget, onInstalled)) return true
        return installViaStarter(shellWidget, onInstalled)
    }

    fun registerWhenReady(shellWidget: ShellTerminalWidget, onInstalled: (CapturingTtyConnector) -> Unit) {
        try {
            val accessor = shellWidget.javaClass.getMethod("getTtyConnectorAccessor").invoke(shellWidget)
                ?: return
            val execute = accessor.javaClass.getMethod(
                "executeWithTtyConnector",
                Consumer::class.java,
            )
            val consumer = Consumer<Any?> { connector ->
                if (connector !is TtyConnector) return@Consumer
                ApplicationManager.getApplication().invokeLater {
                    onInstalled(replaceConnector(shellWidget, accessor, connector))
                }
            }
            execute.invoke(accessor, consumer)
        } catch (_: Exception) {
            // 旧版 IDE 无 accessor
        }
    }

    private fun installViaAccessor(
        shellWidget: ShellTerminalWidget,
        onInstalled: (CapturingTtyConnector) -> Unit,
    ): Boolean {
        return try {
            val accessor = shellWidget.javaClass.getMethod("getTtyConnectorAccessor").invoke(shellWidget)
                ?: return false
            val connector = accessor.javaClass.getMethod("getTtyConnector").invoke(accessor) as? TtyConnector
                ?: return false
            onInstalled(replaceConnector(shellWidget, accessor, connector))
            registerWhenReady(shellWidget, onInstalled)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun installViaWidget(
        shellWidget: ShellTerminalWidget,
        onInstalled: (CapturingTtyConnector) -> Unit,
    ): Boolean {
        return try {
            val connector = shellWidget.javaClass.getMethod("getTtyConnector").invoke(shellWidget) as? TtyConnector
                ?: return false
            onInstalled(replaceConnector(shellWidget, null, connector))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun installViaStarter(
        shellWidget: ShellTerminalWidget,
        onInstalled: (CapturingTtyConnector) -> Unit,
    ): Boolean {
        val starter = shellWidget.terminalStarter ?: return false
        return try {
            onInstalled(CapturingTtyConnector.installOn(starter))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun replaceConnector(
        shellWidget: ShellTerminalWidget,
        accessor: Any?,
        connector: TtyConnector,
    ): CapturingTtyConnector {
        val wrapped = CapturingTtyConnector.wrap(connector)
        if (wrapped === connector) return wrapped

        var replaced = false
        if (accessor != null) {
            replaced = try {
                accessor.javaClass.getMethod("setTtyConnector", TtyConnector::class.java)
                    .invoke(accessor, wrapped)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!replaced) {
            try {
                shellWidget.javaClass.getMethod("setTtyConnector", TtyConnector::class.java)
                    .invoke(shellWidget, wrapped)
                replaced = true
            } catch (_: Exception) {
                // fall through
            }
        }
        if (!replaced) {
            shellWidget.terminalStarter?.let { starter ->
                try {
                    CapturingTtyConnector.installOn(starter)
                } catch (_: Exception) {
                    // best effort
                }
            }
        }
        return wrapped
    }

    fun shellTypedCommand(shellWidget: ShellTerminalWidget): String =
        try {
            shellWidget.javaClass.getMethod("getTypedShellCommand").invoke(shellWidget) as? String ?: ""
        } catch (_: Exception) {
            ""
        }
}
