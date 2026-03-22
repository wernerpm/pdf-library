package com.example.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChallengeCacheTest {

    @Test
    fun `put and consume returns the stored value`() {
        val cache = ChallengeCache<String>()
        val id = cache.put("hello")
        assertNotNull(id)
        assertEquals("hello", cache.consume(id))
    }

    @Test
    fun `consume is single-use`() {
        val cache = ChallengeCache<String>()
        val id = cache.put("once")
        cache.consume(id)
        assertNull(cache.consume(id))
    }

    @Test
    fun `consume unknown session returns null`() {
        val cache = ChallengeCache<String>()
        assertNull(cache.consume("does-not-exist"))
    }

    @Test
    fun `expired entry is rejected`() {
        val cache = ChallengeCache<String>()
        // Inject an already-expired entry directly via reflection
        val entry = ChallengeEntry("expired", expiresAt = System.currentTimeMillis() - 1000)
        val field = ChallengeCache::class.java.getDeclaredField("map")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(cache) as java.util.concurrent.ConcurrentHashMap<String, ChallengeEntry<String>>
        map["stale-id"] = entry

        assertNull(cache.consume("stale-id"))
    }

    @Test
    fun `multiple independent sessions do not interfere`() {
        val cache = ChallengeCache<Int>()
        val id1 = cache.put(1)
        val id2 = cache.put(2)
        assertEquals(2, cache.consume(id2))
        assertEquals(1, cache.consume(id1))
    }
}
