package com.github.cursorterm.feature

import com.github.cursorterm.DebugAgentLog
import com.github.cursorterm.EditorContextCollector
import com.github.cursorterm.terminal.TerminalAccess
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * 功能二：注入路径。仅响应显式按钮点击。
 */
object PathInjectFeature {

    fun inject(project: Project, access: TerminalAccess?, attempt: Int = 0) {
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
            val terminal = access
            if (terminal == null) {
                if (attempt < 20) {
                    Alarm(Alarm.ThreadToUse.SWING_THREAD).addRequest(
                        { inject(project, access, attempt + 1) },
                        200,
                    )
                }
                return@invokeLater
            }
            terminal.focusComponent.requestFocusInWindow()
            terminal.sendString("\n$notation\n")
            DebugAgentLog.write("H-INJ", "PathInjectFeature", "sent", mapOf("notation" to notation))
        }
    }
}
