package com.github.cursorterm.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.jediterm.terminal.ui.TerminalPanel
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.WeakHashMap
import javax.swing.SwingUtilities

/**
 * 先拖选开头 → 滚动 → Shift+点击/拖选结尾，将选区延伸到终点。
 */
internal class TerminalShiftSelectionSupport(
    private val terminalPanel: TerminalPanel,
    private val parentDisposable: Disposable,
) {

    fun install() {
        val pressListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                try {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (e.isShiftDown) {
                        val hasAnchor = hasExtendAnchor(terminalPanel)
                        setShiftExtending(terminalPanel, hasAnchor)
                        if (hasAnchor) {
                            e.consume()
                            previewExtend(terminalPanel, e.point)
                        }
                    } else {
                        setShiftExtending(terminalPanel, false)
                    }
                } catch (_: Exception) {
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                try {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (e.isShiftDown) {
                        if (isShiftExtending(terminalPanel)) {
                            e.consume()
                            extendSelectionTo(terminalPanel, e.point)
                        }
                        setShiftExtending(terminalPanel, false)
                        return
                    }
                    captureAnchorFromSelection(terminalPanel)
                } catch (_: Exception) {
                }
            }
        }

        val dragListener = object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                try {
                    if (!SwingUtilities.isLeftMouseButton(e) || !e.isShiftDown) return
                    if (!hasExtendAnchor(terminalPanel)) return
                    e.consume()
                    extendSelectionTo(terminalPanel, e.point)
                } catch (_: Exception) {
                }
            }
        }

        terminalPanel.addMouseListener(pressListener)
        terminalPanel.addMouseMotionListener(dragListener)
        Disposer.register(parentDisposable) {
            terminalPanel.removeMouseListener(pressListener)
            terminalPanel.removeMouseMotionListener(dragListener)
            clearAnchor(terminalPanel)
            setShiftExtending(terminalPanel, false)
        }
    }

    private fun previewExtend(panel: TerminalPanel, point: Point) {
        extendSelectionTo(panel, point)
    }

    private fun captureAnchorFromSelection(panel: TerminalPanel) {
        val anchor = TerminalSelectionAccess.readSelectionAnchor(panel) ?: return
        setAnchor(panel, anchor)
    }

    private fun extendSelectionTo(panel: TerminalPanel, point: Point) {
        val anchor = readExtendAnchor(panel) ?: return
        val end = TerminalSelectionAccess.panelToCharCoords(panel, point) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (TerminalSelectionAccess.applySelection(panel, anchor, end)) {
                setAnchor(panel, anchor)
            }
        }
    }

    companion object {
        private val anchors = WeakHashMap<TerminalPanel, CharCoord>()
        private val shiftExtendingPanels = WeakHashMap<TerminalPanel, Boolean>()

        fun isShiftExtending(panel: TerminalPanel): Boolean = shiftExtendingPanels[panel] == true

        fun hasExtendAnchor(panel: TerminalPanel): Boolean = readExtendAnchor(panel) != null

        fun clearAnchor(panel: TerminalPanel) {
            anchors.remove(panel)
        }

        private fun setShiftExtending(panel: TerminalPanel, active: Boolean) {
            if (active) {
                shiftExtendingPanels[panel] = true
            } else {
                shiftExtendingPanels.remove(panel)
            }
        }

        private fun setAnchor(panel: TerminalPanel, coord: CharCoord) {
            anchors[panel] = coord
        }

        private fun readExtendAnchor(panel: TerminalPanel): CharCoord? =
            anchors[panel] ?: TerminalSelectionAccess.readSelectionAnchor(panel)
    }
}
