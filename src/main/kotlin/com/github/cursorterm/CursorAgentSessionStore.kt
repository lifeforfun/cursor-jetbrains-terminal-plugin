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

    /** 仅缓存非 null 的扫描结果，避免把「尚未写入 meta」误判缓存 30s。 */
    private val cache = ConcurrentHashMap<String, CachedSession>()

    /** 当前工具窗终端实际使用的会话，优先于磁盘扫描。 */
    private val activeSession = ConcurrentHashMap<String, String>()

    fun findLastSessionId(projectPath: String?): String? {
        if (projectPath.isNullOrBlank()) return null
        val workspaceHash = md5Hex(normalizePath(projectPath))
        val chatsRoot = homeDir.resolve(".cursor/chats").resolve(workspaceHash)

        activeSession[workspaceHash]?.let { remembered ->
            if (sessionHasConversation(chatsRoot, remembered)) {
                return remembered
            }
            activeSession.remove(workspaceHash)
        }

        cache[workspaceHash]?.let { cached ->
            if (System.currentTimeMillis() - cached.cachedAtMs < CACHE_TTL_MS) {
                if (sessionHasConversation(chatsRoot, cached.sessionId)) {
                    activeSession[workspaceHash] = cached.sessionId
                    return cached.sessionId
                }
                cache.remove(workspaceHash)
            }
        }

        val sessionId = findLastSessionIdUncached(chatsRoot)
        if (sessionId != null) {
            cache[workspaceHash] = CachedSession(sessionId, System.currentTimeMillis())
            activeSession[workspaceHash] = sessionId
        }
        return sessionId
    }

    fun recordActiveSession(projectPath: String?, sessionId: String?) {
        if (projectPath.isNullOrBlank() || sessionId.isNullOrBlank()) return
        activeSession[md5Hex(normalizePath(projectPath))] = sessionId
    }

    fun invalidateCache(projectPath: String?) {
        if (projectPath.isNullOrBlank()) return
        val workspaceHash = md5Hex(normalizePath(projectPath))
        cache.remove(workspaceHash)
        activeSession.remove(workspaceHash)
    }

    private fun findLastSessionIdUncached(chatsRoot: Path): String? {
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

    private fun sessionHasConversation(chatsRoot: Path, sessionId: String): Boolean {
        val meta = readMeta(chatsRoot.resolve(sessionId)) ?: return false
        return meta.hasConversation
    }

    private data class CachedSession(
        val sessionId: String,
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
