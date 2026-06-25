package com.github.cursorterm

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

class CursorTerminalToolWindowFactory : ToolWindowFactory {

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "Cursor CLI Terminal"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        if (contentManager.contentCount > 0) {
            val existing = contentManager.getContent(0)
            val controller = existing?.getUserData(CursorAgentTerminalController.CONTROLLER_KEY)
            // #region agent log
            DebugLog.write(
                hypothesisId = "H-TW",
                location = "CursorTerminalToolWindowFactory.createToolWindowContent",
                message = "reuse content",
                data = mapOf(
                    "hasController" to (controller != null),
                    "contentCount" to contentManager.contentCount,
                ),
            )
            // #endregion
            controller?.startInitialSessionIfNeeded()
            return
        }

        val projectDir = project.basePath ?: System.getProperty("user.home")

        val panel = JPanel(BorderLayout())
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        panel.add(toolbar, BorderLayout.NORTH)

        val content = contentManager.factory.createContent(panel, "Cursor Agent", false)
        contentManager.addContent(content)

        val controller = CursorAgentTerminalController(project, content, panel, toolbar, projectDir)
        content.putUserData(CursorAgentTerminalController.CONTROLLER_KEY, controller)
        controller.createToolbar()
        controller.startInitialSession()
    }
}
