package com.github.cursorterm.feature

import com.github.cursorterm.DebugAgentLog
import com.github.cursorterm.terminal.TerminalAccess
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * 功能三：粘贴图片。仅当剪贴板含图片时接管 Ctrl+V，向 agent 发送 0x16。
 */
class ImagePasteFeature {

    @Volatile private var installedFor: Disposable? = null

    fun install(access: TerminalAccess, parentDisposable: Disposable) {
        if (installedFor === parentDisposable) return
        installedFor = parentDisposable
        val action = object : DumbAwareAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = clipboardHasImage()
            }

            override fun actionPerformed(e: AnActionEvent) {
                access.sendBytes(byteArrayOf(0x16))
                DebugAgentLog.write("H-IMG", "ImagePasteFeature", "sent-ctrl-v", emptyMap())
            }
        }
        action.registerCustomShortcutSet(
            CustomShortcutSet(pasteKeyStroke()),
            access.focusComponent,
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
}
