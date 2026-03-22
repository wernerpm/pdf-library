package com.example.auth

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TTL_MS = 5 * 60 * 1000L  // 5 minutes

data class ChallengeEntry<T>(
    val value: T,
    val expiresAt: Long = System.currentTimeMillis() + TTL_MS
)

/**
 * In-memory store correlating sessionIds to in-flight WebAuthn ceremony state.
 * Entries expire after 5 minutes and are swept lazily on each put.
 */
class ChallengeCache<T> {

    private val map = ConcurrentHashMap<String, ChallengeEntry<T>>()

    fun put(value: T): String {
        sweep()
        val id = UUID.randomUUID().toString()
        map[id] = ChallengeEntry(value)
        return id
    }

    /** Returns the value and removes the entry (challenges are single-use). */
    fun consume(sessionId: String): T? {
        val entry = map.remove(sessionId) ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) return null
        return entry.value
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        map.entries.removeIf { it.value.expiresAt < now }
    }
}
