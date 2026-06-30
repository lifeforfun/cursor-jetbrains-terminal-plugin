package com.github.cursorterm

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class CursorTerminalToolWindowFactory : ToolWindowFactory {
    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "Cursor CLI Terminal"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cm = toolWindow.contentManager
        if (cm.contentCount > 0) {
            cm.getContent(0)?.getUserData(CursorAgentTerminalController.CONTROLLER_KEY)?.autoStartSessionIfNeeded()
            return
        }

        val panel = JPanel(BorderLayout())
        val toolbar = JPanel()
        panel.add(toolbar, BorderLayout.NORTH)

        val content = cm.factory.createContent(panel, "Cursor Agent", false)
        cm.addContent(content)

        val controller = CursorAgentTerminalController(
            project,
            content,
            panel,
            toolbar,
            project.basePath ?: System.getProperty("user.home"),
        )
        content.putUserData(CursorAgentTerminalController.CONTROLLER_KEY, controller)
    }
}
