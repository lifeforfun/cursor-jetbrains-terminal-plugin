package com.github.cursorterm

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 从 cursor-agent 本地会话目录解析项目最近一次对话 ID。
 * 目录结构：~/.cursor/chats/<md5(projectPath)>/<chatId>/meta.json
 */
object CursorAgentSessionStore {

    private val LOG = Logger.getInstance(CursorAgentSessionStore::class.java)

    private val homeDir: Path
        get() = Path.of(System.getProperty("user.home"))

    private val cache = ConcurrentHashMap<String, CachedSession>()
    private val activeSession = ConcurrentHashMap<String, String>()

    fun findLastSessionId(projectPath: String?, preferredSessionId: String? = null): String? {
        if (projectPath.isNullOrBlank()) return null
        val workspaceHash = md5Hex(normalizePath(projectPath))
        val chatsRoot = chatsRoot(workspaceHash)

        preferredSessionId?.takeIf { it.isNotBlank() }?.let { preferred ->
            if (sessionExists(chatsRoot, preferred)) {
                remember(workspaceHash, preferred)
                logResolve(projectPath, preferred, "preferred")
                return preferred
            }
        }

        activeSession[workspaceHash]?.let { remembered ->
            if (sessionExists(chatsRoot, remembered)) {
                logResolve(projectPath, remembered, "active")
                return remembered
            }
            activeSession.remove(workspaceHash)
        }

        readPersistedSession(chatsRoot)?.let { persisted ->
            if (sessionExists(chatsRoot, persisted)) {
                remember(workspaceHash, persisted)
                logResolve(projectPath, persisted, "persisted")
                return persisted
            }
            clearPersistedSession(chatsRoot)
        }

        cache[workspaceHash]?.let { cached ->
            if (System.currentTimeMillis() - cached.cachedAtMs < CACHE_TTL_MS) {
                if (sessionExists(chatsRoot, cached.sessionId)) {
                    remember(workspaceHash, cached.sessionId)
                    logResolve(projectPath, cached.sessionId, "cache")
                    return cached.sessionId
                }
                cache.remove(workspaceHash)
            }
        }

        val sessionId = findLastSessionIdUncached(chatsRoot)
        if (sessionId != null) {
            remember(workspaceHash, sessionId)
            logResolve(projectPath, sessionId, "scan")
        } else {
            LOG.info("cursorterm session resolve: project=$projectPath source=none sessionId=null")
        }
        return sessionId
    }

    fun recordActiveSession(projectPath: String?, sessionId: String?) {
        if (projectPath.isNullOrBlank() || sessionId.isNullOrBlank()) return
        remember(md5Hex(normalizePath(projectPath)), sessionId)
        LOG.info("cursorterm session record: project=$projectPath sessionId=$sessionId")
    }

    fun invalidateCache(projectPath: String?) {
        if (projectPath.isNullOrBlank()) return
        val workspaceHash = md5Hex(normalizePath(projectPath))
        cache.remove(workspaceHash)
        activeSession.remove(workspaceHash)
        clearPersistedSession(chatsRoot(workspaceHash))
        LOG.info("cursorterm session invalidate: project=$projectPath")
    }

    /**
     * 终端启动后补录 chatId。若已有绑定会话且仍存在于磁盘，不切换到别的「更新」会话。
     */
    fun adoptDiscoveredSession(
        projectPath: String?,
        boundSessionId: String?,
        sessionStartedAtMs: Long = 0L,
        requireFreshSession: Boolean = false,
    ): String? {
        if (projectPath.isNullOrBlank()) return null
        val workspaceHash = md5Hex(normalizePath(projectPath))
        val chatsRoot = chatsRoot(workspaceHash)

        if (!boundSessionId.isNullOrBlank() && sessionExists(chatsRoot, boundSessionId)) {
            remember(workspaceHash, boundSessionId)
            LOG.info(
                "cursorterm session adopt: project=$projectPath bound=$boundSessionId action=keep-bound",
            )
            return boundSessionId
        }

        val discovered = findLastSessionIdUncached(chatsRoot)
        if (discovered != null) {
            if (requireFreshSession && !isSessionFresh(chatsRoot.resolve(discovered), sessionStartedAtMs)) {
                LOG.info(
                    "cursorterm session adopt: project=$projectPath discovered=$discovered action=skip-stale-new-chat",
                )
                return null
            }
            remember(workspaceHash, discovered)
            LOG.info(
                "cursorterm session adopt: project=$projectPath discovered=$discovered action=record",
            )
            return discovered
        }

        boundSessionId?.let { remember(workspaceHash, it) }
        return boundSessionId
    }

    fun sessionExists(projectPath: String?, sessionId: String): Boolean {
        if (projectPath.isNullOrBlank() || sessionId.isBlank()) return false
        return sessionExists(chatsRoot(md5Hex(normalizePath(projectPath))), sessionId)
    }

    private fun chatsRoot(workspaceHash: String): Path =
        homeDir.resolve(".cursor/chats").resolve(workspaceHash)

    private fun persistedSessionFile(chatsRoot: Path): Path =
        chatsRoot.resolve(PERSISTED_SESSION_FILE)

    private fun readPersistedSession(chatsRoot: Path): String? {
        val file = persistedSessionFile(chatsRoot)
        if (!Files.isRegularFile(file)) return null
        return try {
            Files.readString(file).trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writePersistedSession(chatsRoot: Path, sessionId: String) {
        try {
            Files.createDirectories(chatsRoot)
            Files.writeString(persistedSessionFile(chatsRoot), sessionId)
        } catch (_: Exception) {
        }
    }

    private fun clearPersistedSession(chatsRoot: Path) {
        try {
            Files.deleteIfExists(persistedSessionFile(chatsRoot))
        } catch (_: Exception) {
        }
    }

    private fun remember(workspaceHash: String, sessionId: String) {
        cache[workspaceHash] = CachedSession(sessionId, System.currentTimeMillis())
        activeSession[workspaceHash] = sessionId
        writePersistedSession(chatsRoot(workspaceHash), sessionId)
    }

    private fun findLastSessionIdUncached(chatsRoot: Path): String? {
        if (!Files.isDirectory(chatsRoot)) return null

        var bestId: String? = null
        var bestUpdatedAt = Long.MIN_VALUE
        Files.list(chatsRoot).use { sessions ->
            sessions.filter { Files.isDirectory(it) }
                .forEach { sessionDir ->
                    val meta = readMeta(sessionDir) ?: return@forEach
                    if (!meta.hasConversation) return@forEach
                    if (meta.updatedAtMs >= bestUpdatedAt) {
                        bestUpdatedAt = meta.updatedAtMs
                        bestId = sessionDir.fileName.toString()
                    }
                }
        }
        return bestId
    }

    private fun sessionExists(chatsRoot: Path, sessionId: String): Boolean =
        Files.isDirectory(chatsRoot.resolve(sessionId))

    private fun isSessionFresh(sessionDir: Path, sessionStartedAtMs: Long): Boolean {
        if (sessionStartedAtMs <= 0L) return true
        val threshold = sessionStartedAtMs - FRESH_SESSION_GRACE_MS
        readMeta(sessionDir)?.updatedAtMs?.let { if (it >= threshold) return true }
        return try {
            Files.getLastModifiedTime(sessionDir).toMillis() >= threshold
        } catch (_: Exception) {
            false
        }
    }

    private fun logResolve(projectPath: String, sessionId: String, source: String) {
        LOG.info("cursorterm session resolve: project=$projectPath source=$source sessionId=$sessionId")
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
    private const val FRESH_SESSION_GRACE_MS = 5_000L
    private const val PERSISTED_SESSION_FILE = ".plugin-bound-session"
}
