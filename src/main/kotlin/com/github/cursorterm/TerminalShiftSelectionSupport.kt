package com.github.cursorterm

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
 * 支持：先拖选开头 → Shift+滚轮 → Shift+点击/拖选结尾，将选区延伸到终点。
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

        private fun readExtendAnchor(panel: TerminalPanel): CharCoord? {
            return anchors[panel] ?: TerminalSelectionAccess.readSelectionAnchor(panel)
        }
    }
}

internal data class CharCoord(val x: Int, val y: Int)

internal object TerminalSelectionAccess {

    private const val JEDITERM_POINT_CLASS = "com.jediterm.core.compatibility.Point"
    private const val TERMINAL_SELECTION_CLASS = "com.jediterm.terminal.model.TerminalSelection"

    fun shouldDeferCustomScroll(panel: TerminalPanel): Boolean =
        TerminalShiftSelectionSupport.isShiftExtending(panel)

    fun readSelectionAnchor(panel: TerminalPanel): CharCoord? {
        val selection = getSelection(panel)
        if (selection != null) {
            readTerminalPoint(selection, "getStart")?.let { return it }
        }
        return readFieldPoint(panel, "mySelectionStartPoint")
    }

    fun panelToCharCoords(panel: TerminalPanel, point: Point): CharCoord? {
        return try {
            val method = findDeclaredMethod(panel, "panelToCharCoords", Point::class.java) ?: return null
            val result = method.invoke(panel, point) ?: return null
            readCoord(result)
        } catch (_: Exception) {
            null
        }
    }

    fun applySelection(panel: TerminalPanel, start: CharCoord, end: CharCoord): Boolean {
        return try {
            val classLoader = panel.javaClass.classLoader ?: return false
            val pointClass = classLoader.loadClass(JEDITERM_POINT_CLASS)
            val selectionClass = classLoader.loadClass(TERMINAL_SELECTION_CLASS)
            val startPoint = newTerminalPoint(pointClass, start)
            val endPoint = newTerminalPoint(pointClass, end)
            val selection = createTerminalSelection(selectionClass, pointClass, startPoint, endPoint)

            val selectionField = findDeclaredField(panel, "mySelection") ?: return false
            selectionField.set(panel, selection)

            findDeclaredField(panel, "mySelectionStartPoint")?.set(panel, startPoint)
            invokeForceUpdate(panel)
            panel.repaint()
            getSelection(panel) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun createTerminalSelection(
        selectionClass: Class<*>,
        pointClass: Class<*>,
        startPoint: Any,
        endPoint: Any,
    ): Any {
        return try {
            selectionClass.getConstructor(pointClass, pointClass).newInstance(startPoint, endPoint)
        } catch (_: NoSuchMethodException) {
            val selection = selectionClass.getConstructor(pointClass).newInstance(startPoint)
            selectionClass.getMethod("updateEnd", pointClass).invoke(selection, endPoint)
            selection
        }
    }

    private fun invokeForceUpdate(panel: TerminalPanel) {
        try {
            findDeclaredMethod(panel, "forceUpdate")?.invoke(panel)
        } catch (_: Exception) {
        }
    }

    private fun getSelection(panel: TerminalPanel): Any? {
        findDeclaredMethod(panel, "getSelection")?.let { method ->
            try {
                return method.invoke(panel)
            } catch (_: Exception) {
            }
        }
        return findDeclaredField(panel, "mySelection")?.get(panel)
    }

    private fun readFieldPoint(panel: TerminalPanel, fieldName: String): CharCoord? {
        return try {
            val field = findDeclaredField(panel, fieldName) ?: return null
            val value = field.get(panel) ?: return null
            readCoord(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun findDeclaredField(target: Any, name: String): java.lang.reflect.Field? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                if (field.trySetAccessible()) return field
            } catch (_: NoSuchFieldException) {
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun findDeclaredMethod(target: Any, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(name, *paramTypes)
                if (method.trySetAccessible()) return method
            } catch (_: NoSuchMethodException) {
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun readTerminalPoint(selection: Any, methodName: String): CharCoord? {
        return try {
            val point = selection.javaClass.getMethod(methodName).invoke(selection) ?: return null
            readCoord(point)
        } catch (_: Exception) {
            null
        }
    }

    private fun newTerminalPoint(pointClass: Class<*>, coord: CharCoord): Any {
        return pointClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(coord.x, coord.y)
    }

    private fun readCoord(point: Any): CharCoord {
        val clazz = point.javaClass
        return CharCoord(readAxis(clazz, point, "x"), readAxis(clazz, point, "y"))
    }

    private fun readAxis(clazz: Class<*>, point: Any, name: String): Int {
        return try {
            val field = clazz.getDeclaredField(name)
            if (field.trySetAccessible()) {
                field.getInt(point)
            } else {
                clazz.getMethod("get${name.replaceFirstChar { it.uppercaseChar() }}").invoke(point) as Int
            }
        } catch (_: Exception) {
            (clazz.getMethod(name).invoke(point) as Number).toInt()
        }
    }
}
