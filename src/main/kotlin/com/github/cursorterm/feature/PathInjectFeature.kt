package com.github.cursorterm.feature

import com.github.cursorterm.DebugAgentLog
import com.github.cursorterm.EditorContextCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import org.jetbrains.plugins.terminal.ShellTerminalWidget

/**
 * 功能二：注入路径。仅响应显式按钮点击，不挂钩 Enter、不监听编辑器。
 */
object PathInjectFeature {

    fun inject(project: Project, shellWidget: ShellTerminalWidget?, attempt: Int = 0) {
        val ref = EditorContextCollector.collect(project)
        DebugAgentLog.write(
            "H-INJ",
            "PathInjectFeature",
            "collect",
            mapOf("hasRef" to (ref != null), "path" to ref?.relativePath, "attempt" to attempt),
        )
        if (ref == null) return
        val notation = ref.toAtNotation()
        ApplicationManager.getApplication().invokeLater {
            val widget = shellWidget
            if (widget == null) {
                if (attempt < 20) {
                    Alarm(Alarm.ThreadToUse.SWING_THREAD).addRequest(
                        { inject(project, shellWidget, attempt + 1) },
                        200,
                    )
                }
                return@invokeLater
            }
            val starter = widget.terminalStarter ?: return@invokeLater
            widget.terminalPanel.requestFocusInWindow()
            starter.sendString("\n$notation\n", true)
            DebugAgentLog.write("H-INJ", "PathInjectFeature", "sent", mapOf("notation" to notation))
        }
    }
}
