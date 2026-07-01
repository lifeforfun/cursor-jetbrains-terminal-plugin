package com.github.cursorterm.feature

import com.github.cursorterm.DebugAgentLog
import com.github.cursorterm.terminal.TerminalAccess
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.jediterm.terminal.TtyConnector

/**
 * 终端 URL 以纯文本展示：读路径剥离 OSC8 超链接序列，并去掉链式下划线。
 */
internal object TerminalPlainTextFeature {

    private const val RESET_UNDERLINE = "\u001B[24m\u001B[0m"
    private val OSC8 = Regex("""\u001B\]8;[^\u0007\u001B]*(?:\u0007|\u001B\\)""")
    private val LINK_UNDERLINE = Regex("""\u001B\[4m(?=https?://)""", RegexOption.IGNORE_CASE)
    private val HYPERLINK_END = Regex("""\u001B\\|\u0007""")

    fun install(access: TerminalAccess, parentDisposable: Disposable) {
        installOutputFilter(access, parentDisposable)
    }

    /** 读路径过滤：去掉超链接控制序列，保留可见文本。 */
    private fun installOutputFilter(access: TerminalAccess, parentDisposable: Disposable) {
        val accessor = access.widget.ttyConnectorAccessor
        val current = accessor.ttyConnector ?: access.ttyConnectorOrNull() ?: return
        val wrapped = PlainTextTtyConnector(current)
        accessor.ttyConnector = wrapped
        Disposer.register(parentDisposable) {
            if (accessor.ttyConnector === wrapped) {
                accessor.ttyConnector = current
            }
        }
        DebugAgentLog.write("H-PLAIN", "TerminalPlainTextFeature", "output-filter", emptyMap())
    }

    private class PlainTextTtyConnector(private val delegate: TtyConnector) : TtyConnector {

        override fun read(buf: CharArray, offset: Int, length: Int): Int {
            val read = delegate.read(buf, offset, length)
            if (read <= 0) return read
            val fixed = sanitize(String(buf, offset, read))
            if (fixed.length <= length) {
                fixed.toCharArray().copyInto(buf, offset, 0, fixed.length)
                return fixed.length
            }
            return read
        }

        override fun write(bytes: ByteArray) = delegate.write(bytes)

        override fun write(string: String) = delegate.write(string)

        override fun isConnected(): Boolean = delegate.isConnected

        override fun waitFor(): Int = delegate.waitFor()

        override fun ready(): Boolean = delegate.ready()

        override fun getName(): String = delegate.name

        override fun close() = delegate.close()
    }

    /** 剥离 OSC8、链式下划线，并补发复位以防后续文本带下划线。 */
    private fun sanitize(chunk: String): String {
        if (!chunk.contains('\u001B')) return chunk
        var text = OSC8.replace(chunk, "")
        text = LINK_UNDERLINE.replace(text, "")
        return injectUnderlineReset(text)
    }

    private fun injectUnderlineReset(chunk: String): String =
        HYPERLINK_END.replace(chunk) { match ->
            val tail = chunk.substring(match.range.last + 1)
            if (tail.startsWith("\u001B[24") || tail.startsWith("\u001B[0")) {
                match.value
            } else {
                match.value + RESET_UNDERLINE
            }
        }
}
