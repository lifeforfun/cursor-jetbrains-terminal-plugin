package com.github.cursorterm

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 从 cursor-agent 本地会话目录解析项目最近一次对话 ID。
 * 目录结构：~/.cursor/chats/<md5(projectPath)>/<chatId>/meta.json
 */
object CursorAgentSessionStore {

    private val homeDir: Path
        get() = Path.of(System.getProperty("user.home"))

    fun findLastSessionId(projectPath: String?): String? {
        if (projectPath.isNullOrBlank()) return null
        val workspaceHash = md5Hex(normalizePath(projectPath))
        val chatsRoot = homeDir.resolve(".cursor/chats").resolve(workspaceHash)
        if (!Files.isDirectory(chatsRoot)) return null

        var bestId: String? = null
        var bestUpdatedAt = Long.MIN_VALUE
        Files.list(chatsRoot).use { sessions ->
            sessions
                .filter { Files.isDirectory(it) }
                .forEach { sessionDir ->
                    val meta = readMeta(sessionDir) ?: return@forEach
                    if (!meta.hasConversation) return@forEach
                    if (meta.updatedAtMs >= bestUpdatedAt) {
                        bestUpdatedAt = meta.updatedAtMs
                        bestId = sessionDir.getFileName().toString()
                    }
                }
        }
        return bestId
    }

    private data class SessionMeta(
        val hasConversation: Boolean,
        val updatedAtMs: Long,
    )

    private fun readMeta(sessionDir: Path): SessionMeta? {
        val metaFile = sessionDir.resolve("meta.json")
        if (!Files.isRegularFile(metaFile)) return null
        return try {
            val text = Files.readString(metaFile)
            val hasConversation = HAS_CONVERSATION.containsMatchIn(text)
            val updatedAtMs = UPDATED_AT.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            SessionMeta(hasConversation, updatedAtMs)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizePath(path: String): String = path.trimEnd('/', '\\')

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private val HAS_CONVERSATION = """"hasConversation"\s*:\s*true""".toRegex()
    private val UPDATED_AT = """"updatedAtMs"\s*:\s*(\d+)""".toRegex()
}
