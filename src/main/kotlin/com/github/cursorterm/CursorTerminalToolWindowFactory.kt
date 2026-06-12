package com.github.cursorterm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.BorderLayout
import javax.swing.JPanel

class CursorTerminalToolWindowFactory : ToolWindowFactory {

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

                val terminalWidget = runner.startShellTerminalWidget(content, options, true)
                panel.add(terminalWidget.component, BorderLayout.CENTER)

                val shellWidget = ShellTerminalWidget.toShellJediTermWidgetOrThrow(terminalWidget)
                TerminalScrollFix(shellWidget, content).install()
                ImagePasteSupport(shellWidget, content).install()
                EditorContextOnSubmitSupport.installOnce(project, shellWidget, content)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
