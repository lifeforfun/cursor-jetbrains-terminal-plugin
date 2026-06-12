package com.github.cursorterm

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
 * 方案 A：图片粘贴转交 cursor-agent 自行读取剪贴板（自包含，便于回滚）
 */
class ImagePasteSupport(
    private val shellWidget: ShellTerminalWidget,
    private val parentDisposable: Disposable,
) {

    fun install() {
        val action = object : DumbAwareAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = clipboardHasImage()
            }

            override fun actionPerformed(e: AnActionEvent) {
                forwardCtrlVToAgent()
            }
        }
        action.registerCustomShortcutSet(
            CustomShortcutSet(pasteKeyStroke()),
            shellWidget.terminalPanel,
            parentDisposable,
        )
    }

    private fun pasteKeyStroke(): KeyStroke {
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        return KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask)
    }

    private fun clipboardHasImage(): Boolean {
        return try {
            CopyPasteManager.getInstance().areDataFlavorsAvailable(DataFlavor.imageFlavor)
        } catch (_: Exception) {
            false
        }
    }

    private fun forwardCtrlVToAgent() {
        val starter = shellWidget.terminalStarter ?: return
        starter.sendBytes(byteArrayOf(0x16), true)
    }
}
