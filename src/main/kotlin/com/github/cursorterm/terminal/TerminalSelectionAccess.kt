package com.github.cursorterm.terminal

import com.jediterm.terminal.ui.TerminalPanel
import java.awt.Point

internal data class CharCoord(val x: Int, val y: Int)

/** JediTerm 面板选区读写（Shift 扩选）。 */
internal object TerminalSelectionAccess {

    private const val JEDITERM_POINT_CLASS = "com.jediterm.core.compatibility.Point"
    private const val TERMINAL_SELECTION_CLASS = "com.jediterm.terminal.model.TerminalSelection"

    fun readSelectionRange(panel: TerminalPanel): Pair<CharCoord, CharCoord>? {
        val selection = getSelection(panel) ?: return null
        val start = readTerminalPoint(selection, "getStart") ?: return null
        val end = readTerminalPoint(selection, "getEnd") ?: return null
        return if (start.y < end.y || (start.y == end.y && start.x <= end.x)) {
            start to end
        } else {
            end to start
        }
    }

    fun readSelectionAnchor(panel: TerminalPanel): CharCoord? {
        val selection = getSelection(panel)
        if (selection != null) {
            readTerminalPoint(selection, "getStart")?.let { return it }
        }
        return readFieldPoint(panel, "mySelectionStartPoint")
    }

    fun panelToCharCoords(panel: TerminalPanel, point: Point): CharCoord? = try {
        val method = TerminalReflection.findMethod(panel, "panelToCharCoords", Point::class.java) ?: return null
        val result = method.invoke(panel, point) ?: return null
        readCoord(result)
    } catch (_: Exception) {
        null
    }

    fun applySelection(panel: TerminalPanel, start: CharCoord, end: CharCoord): Boolean = try {
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

    private fun createTerminalSelection(
        selectionClass: Class<*>,
        pointClass: Class<*>,
        startPoint: Any,
        endPoint: Any,
    ): Any = try {
        selectionClass.getConstructor(pointClass, pointClass).newInstance(startPoint, endPoint)
    } catch (_: NoSuchMethodException) {
        val selection = selectionClass.getConstructor(pointClass).newInstance(startPoint)
        selectionClass.getMethod("updateEnd", pointClass).invoke(selection, endPoint)
        selection
    }

    private fun invokeForceUpdate(panel: TerminalPanel) {
        try {
            TerminalReflection.findMethod(panel, "forceUpdate")?.invoke(panel)
        } catch (_: Exception) {
        }
    }

    private fun getSelection(panel: TerminalPanel): Any? {
        TerminalReflection.findMethod(panel, "getSelection")?.let { method ->
            try {
                return method.invoke(panel)
            } catch (_: Exception) {
            }
        }
        return findDeclaredField(panel, "mySelection")?.get(panel)
    }

    private fun readFieldPoint(panel: TerminalPanel, fieldName: String): CharCoord? = try {
        val field = findDeclaredField(panel, fieldName) ?: return null
        val value = field.get(panel) ?: return null
        readCoord(value)
    } catch (_: Exception) {
        null
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

    private fun readTerminalPoint(selection: Any, methodName: String): CharCoord? = try {
        val point = selection.javaClass.getMethod(methodName).invoke(selection) ?: return null
        readCoord(point)
    } catch (_: Exception) {
        null
    }

    private fun newTerminalPoint(pointClass: Class<*>, coord: CharCoord): Any =
        pointClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(coord.x, coord.y)

    private fun readCoord(point: Any): CharCoord {
        val clazz = point.javaClass
        return CharCoord(readAxis(clazz, point, "x"), readAxis(clazz, point, "y"))
    }

    private fun readAxis(clazz: Class<*>, point: Any, name: String): Int = try {
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
