package com.github.cursorterm

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 从 cursor-agent 本地会话目录解析项目最近一次对话 ID。
 * 目录结构：~/.cursor/chats/<md5(projectPath)>/<chatId>/meta.json
 */
object CursorAgentSessionStore {

    private val homeDir: Path
        get() = Path.of(System.getProperty("user.home"))

    private val cache = ConcurrentHashMap<String, CachedSession>()

    fun findLastSessionId(projectPath: String?): String? {
        if (projectPath.isNullOrBlank()) return null
        val workspaceHash = md5Hex(normalizePath(projectPath))
        cache[workspaceHash]?.let { cached ->
            if (System.currentTimeMillis() - cached.cachedAtMs < CACHE_TTL_MS) {
                return cached.sessionId
            }
        }
        val sessionId = findLastSessionIdUncached(workspaceHash)
        cache[workspaceHash] = CachedSession(sessionId, System.currentTimeMillis())
        return sessionId
    }

    fun invalidateCache(projectPath: String?) {
        if (projectPath.isNullOrBlank()) return
        cache.remove(md5Hex(normalizePath(projectPath)))
    }

    private fun findLastSessionIdUncached(workspaceHash: String): String? {
        val chatsRoot = homeDir.resolve(".cursor/chats").resolve(workspaceHash)
        if (!Files.isDirectory(chatsRoot)) return null

        var bestId: String? = null
        var bestUpdatedAt = Long.MIN_VALUE
        val sessionDirs = Files.list(chatsRoot).use { sessions ->
            sessions.filter { Files.isDirectory(it) }.toList()
        }
        sessionDirs
            .sortedByDescending { dir ->
                try {
                    Files.getLastModifiedTime(dir).toMillis()
                } catch (_: Exception) {
                    0L
                }
            }
            .take(MAX_SCAN_DIRS)
            .forEach { sessionDir ->
                val meta = readMeta(sessionDir) ?: return@forEach
                if (!meta.hasConversation) return@forEach
                if (meta.updatedAtMs >= bestUpdatedAt) {
                    bestUpdatedAt = meta.updatedAtMs
                    bestId = sessionDir.fileName.toString()
                }
            }
        return bestId
    }

    private data class CachedSession(
        val sessionId: String?,
        val cachedAtMs: Long,
    )

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

    private const val CACHE_TTL_MS = 30_000L
    private const val MAX_SCAN_DIRS = 32
}
