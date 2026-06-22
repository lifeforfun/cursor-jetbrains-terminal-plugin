package com.github.cursorterm

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.Questioner
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.TerminalStarter
import java.nio.charset.StandardCharsets

/**
 * 拦截发往 PTY 的字节，跟踪当前输入行（cursor-agent TUI 不经 Swing/缓冲区）。
 */
class CapturingTtyConnector(
    private val delegate: TtyConnector,
) : TtyConnector {

    private val lineBuffer = StringBuilder()

    @Volatile
    private var lineContinuationPending = false

    fun currentLine(): String = lineBuffer.toString()

    fun hasLineContinuationPending(): Boolean =
        lineContinuationPending || lineEndsWithContinuation()

    fun consumeLineContinuation() {
        lineContinuationPending = false
    }

    private fun lineEndsWithContinuation(): Boolean {
        if (lineBuffer.isEmpty()) return false
        return isLineContinuationChar(lineBuffer[lineBuffer.length - 1])
    }

    private fun isLineContinuationChar(ch: Char): Boolean = ch == '\\' || ch == '＼'

    fun clearLine() {
        lineBuffer.setLength(0)
        lineContinuationPending = false
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int = delegate.read(buf, offset, length)

    override fun write(bytes: ByteArray) {
        captureBytes(bytes)
        delegate.write(bytes)
    }

    override fun write(string: String) {
        captureBytes(string.toByteArray(StandardCharsets.UTF_8))
        delegate.write(string)
    }

    override fun isConnected(): Boolean = delegate.isConnected

    override fun waitFor(): Int = delegate.waitFor()

    override fun ready(): Boolean = delegate.ready()

    override fun getName(): String = delegate.name

    override fun close() {
        delegate.close()
    }

    override fun init(questioner: Questioner): Boolean = delegate.init(questioner)

    override fun resize(termSize: TermSize) {
        delegate.resize(termSize)
    }

    @Deprecated("Deprecated in Java")
    override fun resize(termWinSize: java.awt.Dimension) {
        @Suppress("DEPRECATION")
        delegate.resize(termWinSize)
    }

    @Deprecated("Deprecated in Java")
    override fun resize(termWinSize: java.awt.Dimension, pixelSize: java.awt.Dimension) {
        @Suppress("DEPRECATION")
        delegate.resize(termWinSize, pixelSize)
    }

    private fun captureBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes[0] == ESC) return

        val text = bytes.toString(StandardCharsets.UTF_8)
        for (ch in text) {
            when {
                ch == '\r' || ch == '\n' -> Unit
                ch == '\u0008' || ch == '\u007F' -> {
                    if (lineBuffer.isNotEmpty()) {
                        lineBuffer.deleteCharAt(lineBuffer.length - 1)
                    }
                    lineContinuationPending = false
                }
                ch == '\u0003' || ch == '\u0015' -> clearLine()
                ch == '\u000B' -> {
                    lineBuffer.setLength(0)
                    lineContinuationPending = false
                }
                ch.isISOControl() -> Unit
                else -> {
                    lineBuffer.append(ch)
                    lineContinuationPending = ch == '\\' || ch == '＼'
                }
            }
        }
    }

    companion object {
        private const val ESC = 0x1B.toByte()

        fun wrap(connector: TtyConnector): CapturingTtyConnector =
            if (connector is CapturingTtyConnector) connector else CapturingTtyConnector(connector)

        fun installOn(starter: TerminalStarter): CapturingTtyConnector {
            val field = TerminalStarter::class.java.getDeclaredField("myTtyConnector")
            field.isAccessible = true
            val current = field.get(starter) as TtyConnector
            val wrapped = wrap(current)
            if (wrapped !== current) {
                field.set(starter, wrapped)
            }
            return wrapped
        }
    }
}
