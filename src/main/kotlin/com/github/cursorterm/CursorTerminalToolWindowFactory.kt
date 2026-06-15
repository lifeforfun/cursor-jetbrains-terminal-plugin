package com.github.cursorterm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.BorderLayout
import javax.swing.JPanel

class CursorTerminalToolWindowFactory : ToolWindowFactory {

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "Cursor CLI Terminal"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val projectDir = project.basePath ?: System.getProperty("user.home")

        val panel = JPanel(BorderLayout())
        val content = contentManager.factory.createContent(panel, "Cursor Agent", false)
        contentManager.addContent(content)

        ApplicationManager.getApplication().invokeLater {
            try {
                val runner = LocalTerminalDirectRunner.createTerminalRunner(project)
                val options = ShellStartupOptions.Builder()
                    .workingDirectory(projectDir)
                    .shellCommand(TerminalLauncher.buildShellCommand(project))
                    .build()

                val terminalWidget = TerminalWidgetStartSupport.start(runner, content, options)
                val shellWidget = ShellTerminalWidgetSupport.resolve(terminalWidget)
                panel.add(TerminalWidgetStartSupport.uiComponent(terminalWidget, shellWidget), BorderLayout.CENTER)
                panel.revalidate()
                panel.repaint()

                EditorContextOnSubmitSupport.installOnce(project, shellWidget, content)
                TerminalScrollFix.installOn(content, shellWidget, content)
                try {
                    ImagePasteSupport(shellWidget, content).install()
                } catch (_: Exception) {
                    // 可选功能
                }
            } catch (e: Exception) {
                e.printStackTrace()
                panel.removeAll()
                panel.add(
                    javax.swing.JLabel("Cursor Agent 启动失败: ${e.message ?: e.javaClass.simpleName}"),
                    BorderLayout.CENTER,
                )
                panel.revalidate()
                panel.repaint()
            }
        }
    }
}
