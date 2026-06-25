package com.github.cursorterm

import com.intellij.openapi.project.ProjectManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object DebugLog {
    private fun logPath(): Path {
        val base = ProjectManager.getInstance().openProjects.firstOrNull()?.basePath
        return if (base != null) {
            Path.of(base, ".cursor", "debug-61a369.log")
        } else {
            Path.of(System.getProperty("user.home"), ".cursor", "debug-61a369.log")
        }
    }

    fun write(hypothesisId: String, location: String, message: String, data: Map<String, Any?> = emptyMap()) {
        try {
            val path = logPath()
            val payload = buildString {
                append("{\"sessionId\":\"61a369\",\"hypothesisId\":").append(q(hypothesisId))
                append(",\"location\":").append(q(location))
                append(",\"message\":").append(q(message))
                append(",\"timestamp\":").append(System.currentTimeMillis())
                append(",\"data\":{")
                append(data.entries.joinToString(",") { (k, v) -> "${q(k)}:${qv(v)}" })
                append("}}")
            }
            Files.createDirectories(path.parent)
            Files.writeString(path, payload + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) {
        }
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun qv(v: Any?) = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        else -> q(v.toString())
    }
}
