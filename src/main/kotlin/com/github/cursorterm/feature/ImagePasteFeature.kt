package com.github.cursorterm.feature

import com.github.cursorterm.DebugAgentLog
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * 功能三：粘贴图片。仅当剪贴板含图片时接管 Ctrl+V，向 agent 发送 0x16；文本粘贴不受影响。
 */
class ImagePasteFeature {

    @Volatile private var installedFor: Disposable? = null

    fun install(shellWidget: ShellTerminalWidget, parentDisposable: Disposable) {
        if (installedFor === parentDisposable) {
            DebugAgentLog.write("H-IMG", "ImagePasteFeature", "already-installed", emptyMap())
            return
        }
        installedFor = parentDisposable
        val action = object : DumbAwareAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = clipboardHasImage()
            }

            override fun actionPerformed(e: AnActionEvent) {
                forwardImagePaste(shellWidget)
            }
        }
        action.registerCustomShortcutSet(
            CustomShortcutSet(pasteKeyStroke()),
            shellWidget.terminalPanel,
            parentDisposable,
        )
        DebugAgentLog.write("H-IMG", "ImagePasteFeature", "installed", emptyMap())
    }

    private fun pasteKeyStroke(): KeyStroke {
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        return KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask)
    }

    private fun clipboardHasImage(): Boolean = try {
        CopyPasteManager.getInstance().areDataFlavorsAvailable(DataFlavor.imageFlavor)
    } catch (_: Exception) {
        false
    }

    private fun forwardImagePaste(shellWidget: ShellTerminalWidget) {
        val starter = shellWidget.terminalStarter ?: return
        starter.sendBytes(byteArrayOf(0x16), true)
        DebugAgentLog.write("H-IMG", "ImagePasteFeature", "sent-ctrl-v", emptyMap())
    }
}
