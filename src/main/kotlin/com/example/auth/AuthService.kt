package com.example.auth

import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.UserIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.util.Optional
import kotlin.time.Clock

private val logger = LoggerFactory.getLogger(AuthService::class.java)

@Serializable
data class RegisterStartRequest(val username: String, val displayName: String)

@Serializable
data class RegisterStartResponse(val sessionId: String, val options: JsonElement)

@Serializable
data class LoginStartRequest(val username: String? = null)

@Serializable
data class LoginStartResponse(val sessionId: String, val options: JsonElement)

@Serializable
data class LoginFinishRequest(val sessionId: String, val credential: JsonElement)

class AuthService(
    rpId: String,
    rpName: String,
    private val authStore: AuthStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val rp: RelyingParty = RelyingParty.builder()
        .identity(
            RelyingPartyIdentity.builder()
                .id(rpId)
                .name(rpName)
                .build()
        )
        .credentialRepository(authStore)
        .build()

    private val registrationCache = ChallengeCache<PublicKeyCredentialCreationOptions>()
    private val assertionCache = ChallengeCache<AssertionRequest>()

    fun hasCredentials(): Boolean = authStore.hasCredentials()

    // ---- Registration ----

    fun startRegistration(username: String, displayName: String): RegisterStartResponse {
        val options = rp.startRegistration(
            StartRegistrationOptions.builder()
                .user(
                    UserIdentity.builder()
                        .name(username)
                        .displayName(displayName)
                        .id(authStore.getUserHandleForUsername(username)
                            .orElseGet { deriveHandle(username) })
                        .build()
                )
                .build()
        )
        val sessionId = registrationCache.put(options)
        val optionsJson = json.parseToJsonElement(options.toCredentialsCreateJson())
        return RegisterStartResponse(sessionId, optionsJson)
    }

    suspend fun finishRegistration(sessionId: String, credentialJson: String, username: String, displayName: String) {
        val options = registrationCache.consume(sessionId)
            ?: error("Registration session expired or not found")

        val result = rp.finishRegistration(
            FinishRegistrationOptions.builder()
                .request(options)
                .response(PublicKeyCredential.parseRegistrationResponseJson(credentialJson))
                .build()
        )

        val cred = StoredCredential(
            username = username,
            credentialId = result.keyId.id.base64Url,
            publicKeyCose = result.publicKeyCose.base64Url,
            signCount = result.signatureCount,
            displayName = displayName,
            createdAt = Clock.System.now()
        )
        authStore.addCredential(cred)
        logger.info("Registered new passkey for user '$username'")
    }

    // ---- Authentication ----

    fun startLogin(username: String?): LoginStartResponse {
        val opts = StartAssertionOptions.builder()
            .let { b -> if (username != null) b.username(Optional.of(username)) else b.username(Optional.empty()) }
            .build()
        val request = rp.startAssertion(opts)
        val sessionId = assertionCache.put(request)
        val optionsJson = json.parseToJsonElement(request.toCredentialsGetJson())
        return LoginStartResponse(sessionId, optionsJson)
    }

    suspend fun finishLogin(sessionId: String, credentialJson: String): String {
        val request = assertionCache.consume(sessionId)
            ?: error("Login session expired or not found")

        val result = rp.finishAssertion(
            FinishAssertionOptions.builder()
                .request(request)
                .response(PublicKeyCredential.parseAssertionResponseJson(credentialJson))
                .build()
        )

        check(result.isSuccess) { "Assertion verification failed" }

        authStore.updateSignCount(
            result.credential.credentialId.base64Url,
            result.signatureCount
        )

        val username = result.username
        logger.info("Successful passkey login for user '$username'")
        return username
    }

    // ---- Helper ----

    private fun deriveHandle(username: String): com.yubico.webauthn.data.ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(username.toByteArray(Charsets.UTF_8))
        return com.yubico.webauthn.data.ByteArray(digest)
    }
}
