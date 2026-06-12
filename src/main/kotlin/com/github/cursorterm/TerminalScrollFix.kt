package com.github.cursorterm

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.util.Alarm
import com.jediterm.terminal.model.TerminalHistoryBufferListener
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.Adjustable
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import javax.swing.BoundedRangeModel
import javax.swing.JScrollBar
import kotlin.math.abs

/**
 * 滚动修复：
 * 1. 备用屏下滚轮不映射为方向键，强制本地滚 scrollback
 * 2. 持续输出时跟随到底部
 * 3. 用户滚轮/拖滚动条时记录锚点，TUI 重绘导致的位置漂移一律恢复锚点
 */
class TerminalScrollFix(
    private val shellWidget: ShellTerminalWidget,
    parentDisposable: Disposable,
) {
    private val terminalPanel: TerminalPanel = shellWidget.terminalPanel
    private val scrollModel: BoundedRangeModel = terminalPanel.verticalScrollModel
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    @Volatile
    private var followOutput = true

    @Volatile
    private var userAdjustingScroll = false

    @Volatile
    private var programmaticScroll = false

    private var lastValue = BOTTOM_VALUE

    /** 仅由用户滚轮/滚动条写入，不被 TUI 被动滚动污染 */
    private var userAnchorValue = BOTTOM_VALUE

    private var lastUserScrollAt = 0L

    fun install() {
        patchSettingsProvider()
        installUserScrollDetection()
        installJumpGuard()
        installOutputFollow()
        scheduleFollowCheck()
        runOnEdt {
            ensureHistoryScrollingEnabled()
            terminalPanel.requestFocusInWindow()
        }
    }

    private fun patchSettingsProvider() {
        try {
            val field = TerminalPanel::class.java.getDeclaredField("mySettingsProvider")
            field.isAccessible = true
            val original = field.get(terminalPanel) as SettingsProvider
            if (Proxy.isProxyClass(original.javaClass)) return
            field.set(terminalPanel, wrapSettingsProvider(original))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun wrapSettingsProvider(delegate: SettingsProvider): SettingsProvider {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "sendArrowKeysInAlternativeMode",
                "simulateMouseScrollWithArrowKeysInAlternativeScreen",
                -> false
                // 滚轮始终走本地 scrollback，不等待 PTY 鼠标协议就绪
                "forceActionOnMouseReporting" -> true
                else -> if (args == null) method.invoke(delegate) else method.invoke(delegate, *args)
            }
        }
        return Proxy.newProxyInstance(
            SettingsProvider::class.java.classLoader,
            arrayOf(SettingsProvider::class.java),
            handler,
        ) as SettingsProvider
    }

    private fun installUserScrollDetection() {
        terminalPanel.addMouseWheelListener(MouseWheelListener { event ->
            if (event.scrollType != MouseWheelEvent.WHEEL_UNIT_SCROLL || event.isShiftDown) {
                return@MouseWheelListener
            }
            val units = event.unitsToScroll
            if (units != 0) {
                ensureHistoryScrollingEnabled()
                scrollByUnits(units)
            }
            onUserScrollIntent()
        })

        findVerticalScrollBar(shellWidget.component)?.let { scrollBar ->
            scrollBar.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    userAdjustingScroll = true
                    followOutput = false
                    touchUserScroll()
                    ensureHistoryScrollingEnabled()
                }

                override fun mouseReleased(e: MouseEvent) {
                    userAdjustingScroll = false
                    captureUserAnchor()
                    if (isAtBottom()) {
                        followOutput = true
                        userAnchorValue = BOTTOM_VALUE
                    }
                }
            })
        }
    }

    /**
     * cursor-agent 进入备用屏时 JediTerm 会关掉 myScrollingEnabled，
     * 导致滚轮在拖滚动条之前完全无效。强制保持历史滚动可用。
     */
    private fun ensureHistoryScrollingEnabled() {
        try {
            val scrollingField = TerminalPanel::class.java.getDeclaredField("myScrollingEnabled")
            scrollingField.isAccessible = true
            if (!scrollingField.getBoolean(terminalPanel)) {
                scrollingField.setBoolean(terminalPanel, true)
            }
            val updateMethod = TerminalPanel::class.java.getDeclaredMethod(
                "updateScrolling",
                Boolean::class.javaPrimitiveType,
            )
            updateMethod.isAccessible = true
            updateMethod.invoke(terminalPanel, true)
        } catch (_: Exception) {
        }
    }

    private fun scrollByUnits(units: Int) {
        programmaticScroll = true
        try {
            val method = TerminalPanel::class.java.getDeclaredMethod(
                "moveScrollBar",
                Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(terminalPanel, units)
            lastValue = scrollModel.value
        } catch (_: Exception) {
            val newValue = (scrollModel.value + units).coerceIn(scrollModel.minimum, scrollModel.maximum)
            scrollModel.value = newValue
            lastValue = newValue
        } finally {
            programmaticScroll = false
        }
    }

    private fun onUserScrollIntent() {
        followOutput = false
        touchUserScroll()
        runOnEdt {
            ApplicationManager.getApplication().invokeLater({
                captureUserAnchor()
                if (isAtBottom()) {
                    followOutput = true
                    userAnchorValue = BOTTOM_VALUE
                }
            }, ModalityState.any())
        }
    }

    private fun touchUserScroll() {
        lastUserScrollAt = System.currentTimeMillis()
    }

    private fun captureUserAnchor() {
        userAnchorValue = scrollModel.value
    }

    private fun installJumpGuard() {
        scrollModel.addChangeListener {
            if (programmaticScroll) {
                lastValue = scrollModel.value
                return@addChangeListener
            }

            val current = scrollModel.value
            val previous = lastValue

            if (isSpuriousTeleport(previous, current)) {
                restoreScroll(desiredScrollTarget())
                return@addChangeListener
            }

            lastValue = current

            if (!followOutput || userAdjustingScroll) {
                return@addChangeListener
            }

            if (!isAtBottom()) {
                stickToBottom()
            }
        }
    }

    private fun installOutputFollow() {
        val textBuffer = terminalPanel.terminalTextBuffer
        textBuffer.addModelListener {
            runOnEdt {
                ensureHistoryScrollingEnabled()
                followIfNeeded()
                schedulePostOutputGuard()
            }
        }
        textBuffer.addHistoryBufferListener(object : TerminalHistoryBufferListener {
            override fun historyBufferLineCountChanged() {
                runOnEdt {
                    ensureHistoryScrollingEnabled()
                    followIfNeeded()
                    schedulePostOutputGuard()
                }
            }
        })
    }

    private fun scheduleFollowCheck() {
        alarm.addRequest({
            ensureHistoryScrollingEnabled()
            followIfNeeded()
            scheduleFollowCheck()
        }, FOLLOW_CHECK_MS)
    }

    private fun schedulePostOutputGuard() {
        for (delay in POST_OUTPUT_GUARD_DELAYS_MS) {
            alarm.addRequest({ recoverAnchorIfDrifted() }, delay)
        }
    }

    private fun recoverAnchorIfDrifted() {
        if (programmaticScroll || userAdjustingScroll || isRecentUserScroll()) {
            return
        }

        val current = scrollModel.value
        val target = desiredScrollTarget()
        if (abs(current - target) > ANCHOR_DRIFT_TOLERANCE) {
            restoreScroll(target)
        }
    }

    private fun followIfNeeded() {
        if (followOutput && !userAdjustingScroll && !isRecentUserScroll() && !isAtBottom()) {
            stickToBottom()
        }
    }

    private fun isSpuriousTeleport(previous: Int, current: Int): Boolean {
        if (userAdjustingScroll || isRecentUserScroll()) return false

        val delta = abs(current - previous)
        if (delta < SPURIOUS_JUMP_DELTA) return false

        return if (followOutput) {
            current != BOTTOM_VALUE
        } else {
            abs(current - userAnchorValue) > ANCHOR_DRIFT_TOLERANCE
        }
    }

    private fun desiredScrollTarget(): Int {
        return if (followOutput) BOTTOM_VALUE else userAnchorValue
    }

    private fun isRecentUserScroll(): Boolean {
        return System.currentTimeMillis() - lastUserScrollAt < USER_SCROLL_GRACE_MS
    }

    private fun restoreScroll(target: Int) {
        programmaticScroll = true
        try {
            ensureHistoryScrollingEnabled()
            val clamped = target.coerceIn(scrollModel.minimum, scrollModel.maximum)
            scrollModel.value = clamped
            lastValue = clamped
            if (clamped == BOTTOM_VALUE) {
                invokePanelScrollToBottom()
            }
        } finally {
            programmaticScroll = false
        }
    }

    private fun stickToBottom() {
        if (isAtBottom()) return
        restoreScroll(BOTTOM_VALUE)
    }

    private fun invokePanelScrollToBottom() {
        try {
            val method = TerminalPanel::class.java.getDeclaredMethod("scrollToBottom")
            method.isAccessible = true
            method.invoke(terminalPanel)
        } catch (_: Exception) {
        }
    }

    private fun isAtBottom(): Boolean = scrollModel.value == BOTTOM_VALUE

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            action()
        } else {
            app.invokeLater(action, ModalityState.any())
        }
    }

    private fun findVerticalScrollBar(root: Component): JScrollBar? {
        if (root is JScrollBar && root.orientation == Adjustable.VERTICAL) {
            return root
        }
        if (root is Container) {
            for (child in root.components) {
                findVerticalScrollBar(child)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val BOTTOM_VALUE = 0
        private const val FOLLOW_CHECK_MS = 80L
        private const val USER_SCROLL_GRACE_MS = 350L
        private const val SPURIOUS_JUMP_DELTA = 25
        private const val ANCHOR_DRIFT_TOLERANCE = 8
        private val POST_OUTPUT_GUARD_DELAYS_MS = longArrayOf(40L, 120L, 250L)
    }
}
