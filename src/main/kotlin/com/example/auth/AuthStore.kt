package com.example.auth

import com.example.storage.StorageProvider
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Optional
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.Instant

private val logger = LoggerFactory.getLogger(AuthStore::class.java)

@Serializable
data class StoredCredential(
    val username: String,
    val credentialId: String,    // Base64url
    val publicKeyCose: String,   // Base64url COSE-encoded public key
    val signCount: Long,
    val displayName: String,
    val createdAt: Instant
)

/**
 * Persists WebAuthn credentials to credentials.json and implements
 * the synchronous CredentialRepository interface required by Yubico's library.
 * In-memory state is authoritative; disk is updated after every mutation.
 */
class AuthStore(private val storage: StorageProvider) : CredentialRepository {

    private val lock = ReentrantReadWriteLock()
    private val json = Json { ignoreUnknownKeys = true }
    private val fileName = "credentials.json"

    // In-memory list – initialised by loadFromDisk()
    private var credentials: List<StoredCredential> = emptyList()

    // ---- Initialisation ----

    suspend fun loadFromDisk() {
        val loaded: List<StoredCredential> = try {
            if (!storage.exists(fileName)) {
                emptyList()
            } else {
                json.decodeFromString(storage.read(fileName).decodeToString())
            }
        } catch (e: Exception) {
            logger.error("Failed to load credentials from disk; starting empty", e)
            emptyList()
        }
        lock.write { credentials = loaded }
        logger.info("AuthStore: loaded ${loaded.size} credential(s) from disk")
    }

    // ---- Mutation helpers (called from suspend context in auth endpoints) ----

    suspend fun addCredential(cred: StoredCredential) {
        lock.write { credentials = credentials + cred }
        persist()
    }

    suspend fun updateSignCount(credentialId: String, newCount: Long) {
        lock.write {
            credentials = credentials.map {
                if (it.credentialId == credentialId) it.copy(signCount = newCount) else it
            }
        }
        persist()
    }

    fun hasCredentials(): Boolean = lock.read { credentials.isNotEmpty() }

    fun findByUsername(username: String): List<StoredCredential> =
        lock.read { credentials.filter { it.username == username } }

    // ---- CredentialRepository (synchronous, called by Yubico internals) ----

    override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
        lock.read {
            credentials
                .filter { it.username == username }
                .map { c ->
                    PublicKeyCredentialDescriptor.builder()
                        .id(ByteArray.fromBase64Url(c.credentialId))
                        .build()
                }
                .toSet()
        }

    override fun getUserHandleForUsername(username: String): Optional<ByteArray> =
        Optional.of(usernameToHandle(username))

    override fun getUsernameForUserHandle(userHandle: ByteArray): Optional<String> =
        lock.read {
            credentials
                .firstOrNull { usernameToHandle(it.username) == userHandle }
                ?.username
                .let { Optional.ofNullable(it) }
        }

    override fun lookup(credentialId: ByteArray, userHandle: ByteArray): Optional<RegisteredCredential> =
        lock.read {
            credentials
                .firstOrNull { it.credentialId == credentialId.base64Url }
                ?.toRegistered()
                .let { Optional.ofNullable(it) }
        }

    override fun lookupAll(credentialId: ByteArray): Set<RegisteredCredential> =
        lock.read {
            credentials
                .filter { it.credentialId == credentialId.base64Url }
                .map { it.toRegistered() }
                .toSet()
        }

    // ---- Private helpers ----

    private fun StoredCredential.toRegistered(): RegisteredCredential =
        RegisteredCredential.builder()
            .credentialId(ByteArray.fromBase64Url(credentialId))
            .userHandle(usernameToHandle(username))
            .publicKeyCose(ByteArray.fromBase64Url(publicKeyCose))
            .signatureCount(signCount)
            .build()

    private fun usernameToHandle(username: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(username.toByteArray(Charsets.UTF_8))
        return ByteArray(digest)
    }

    private suspend fun persist() {
        val snapshot = lock.read { credentials }
        try {
            storage.write(fileName, json.encodeToString(snapshot).encodeToByteArray())
        } catch (e: Exception) {
            logger.error("Failed to persist credentials to disk", e)
        }
    }
}
