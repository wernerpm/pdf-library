package com.example.auth

import com.example.storage.FileSystemStorage
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AuthStoreTest {

    private lateinit var tempDir: java.io.File
    private lateinit var store: AuthStore

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("auth-store-test").toFile()
        store = AuthStore(FileSystemStorage(tempDir.absolutePath))
        runBlocking { store.loadFromDisk() }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun testCred(username: String = "alice", id: String = "cred-id-1") = StoredCredential(
        username = username,
        credentialId = id,
        publicKeyCose = "bGVnaXRpbWF0ZQ",  // arbitrary base64url
        signCount = 0,
        displayName = "Alice",
        createdAt = Clock.System.now()
    )

    @Test
    fun `starts empty`() = runBlocking {
        assertFalse(store.hasCredentials())
        assertTrue(store.findByUsername("alice").isEmpty())
    }

    @Test
    fun `addCredential persists and is retrievable`() = runBlocking {
        store.addCredential(testCred())
        assertTrue(store.hasCredentials())
        val found = store.findByUsername("alice")
        assertEquals(1, found.size)
        assertEquals("cred-id-1", found[0].credentialId)
    }

    @Test
    fun `round-trip reload from disk returns same credentials`() = runBlocking {
        store.addCredential(testCred("alice", "a1"))
        store.addCredential(testCred("bob", "b1"))

        // Fresh store from the same directory
        val store2 = AuthStore(FileSystemStorage(tempDir.absolutePath))
        store2.loadFromDisk()

        assertEquals(1, store2.findByUsername("alice").size)
        assertEquals(1, store2.findByUsername("bob").size)
    }

    @Test
    fun `updateSignCount updates only matching credential`() = runBlocking {
        store.addCredential(testCred("alice", "a1"))
        store.addCredential(testCred("alice", "a2"))

        store.updateSignCount("a1", 42)

        val creds = store.findByUsername("alice")
        val updated = creds.first { it.credentialId == "a1" }
        val unchanged = creds.first { it.credentialId == "a2" }
        assertEquals(42, updated.signCount)
        assertEquals(0, unchanged.signCount)
    }

    @Test
    fun `CredentialRepository getCredentialIdsForUsername returns correct ids`() = runBlocking {
        store.addCredential(testCred("alice", "aaa"))
        val ids = store.getCredentialIdsForUsername("alice")
        assertEquals(1, ids.size)
        assertEquals("aaa", ids.first().id.base64Url)
    }

    @Test
    fun `CredentialRepository getUserHandleForUsername is consistent`() = runBlocking {
        store.addCredential(testCred("alice"))
        val handle = store.getUserHandleForUsername("alice")
        assertTrue(handle.isPresent)
        // Same username always gives same handle
        val handle2 = store.getUserHandleForUsername("alice")
        assertEquals(handle.get(), handle2.get())
    }

    @Test
    fun `CredentialRepository getUsernameForUserHandle round-trips`() = runBlocking {
        store.addCredential(testCred("alice"))
        val handle = store.getUserHandleForUsername("alice").get()
        val username = store.getUsernameForUserHandle(handle)
        assertTrue(username.isPresent)
        assertEquals("alice", username.get())
    }

    @Test
    fun `CredentialRepository lookup returns credential for correct id`() = runBlocking {
        store.addCredential(testCred("alice", "aaa"))
        val handle = store.getUserHandleForUsername("alice").get()
        val credId = com.yubico.webauthn.data.ByteArray.fromBase64Url("aaa")
        val result = store.lookup(credId, handle)
        assertTrue(result.isPresent)
        assertEquals("aaa", result.get().credentialId.base64Url)
    }

    @Test
    fun `CredentialRepository lookup returns empty for unknown id`() = runBlocking {
        store.addCredential(testCred("alice", "aaa"))
        val handle = store.getUserHandleForUsername("alice").get()
        val unknownId = com.yubico.webauthn.data.ByteArray.fromBase64Url("zzz")
        val result = store.lookup(unknownId, handle)
        assertFalse(result.isPresent)
    }
}
