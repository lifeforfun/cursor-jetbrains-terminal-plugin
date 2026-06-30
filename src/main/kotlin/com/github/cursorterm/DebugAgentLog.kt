package com.github.cursorterm

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// #region agent log
internal object DebugAgentLog {
    private val LOG_PATH =
        Path.of("/Users/zhangmingming/workspace/test/cursor-cli-terminal-plugin/.cursor/debug-61a369.log")

    fun write(hypothesisId: String, location: String, message: String, data: Map<String, Any?>) {
        try {
            val dataJson = data.entries.joinToString(",") { (k, v) ->
                val value = when (v) {
                    null -> "null"
                    is Boolean, is Number -> v.toString()
                    else -> "\"${v.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
                }
                "\"$k\":$value"
            }
            val line =
                """{"sessionId":"61a369","hypothesisId":"$hypothesisId","location":"$location","message":"$message","timestamp":${System.currentTimeMillis()},"data":{$dataJson}}"""
            Files.writeString(LOG_PATH, "$line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) {
        }
    }
}
// #endregion
