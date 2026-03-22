package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.auth.AuthService
import com.example.auth.AuthStore
import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.repository.InMemoryMetadataRepository
import com.example.repository.TextContentStore
import com.example.storage.FileSystemStorage
import com.example.sync.SyncService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.util.Date

class MainSecurityHeadersTest {

    private lateinit var tempDir: java.io.File
    private val secret = "test-secret-32-bytes-long-enough!"

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("sec-headers-test").toFile()
        jwtSecret = secret
        appUrl = null

        appConfig = AppConfiguration(
            pdfScanPaths = listOf(tempDir.absolutePath),
            metadataStoragePath = tempDir.absolutePath,
            scanning = ScanConfiguration(),
            rpId = "localhost",
            rpName = "Test Library",
            jwtIssuer = "localhost"
        )

        val storage = FileSystemStorage(tempDir.absolutePath)
        repository = InMemoryMetadataRepository(FakeRepoForSec())
        textContentStore = TextContentStore(storage)
        repositoryManager = mockk(relaxed = true)
        syncService = mockk(relaxed = true)

        val authStore = AuthStore(storage)
        runBlocking { authStore.loadFromDisk() }
        authService = AuthService(appConfig.rpId, appConfig.rpName, authStore)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun token() = JWT.create()
        .withIssuer("localhost")
        .withSubject("alice")
        .withExpiresAt(Date(System.currentTimeMillis() + 8 * 3600 * 1000L))
        .sign(Algorithm.HMAC256(secret))

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureContentNegotiation()
            configureSecurityHeaders()
            configureAuth()
            configureRouting()
        }
        block()
    }

    @Test
    fun `security headers present on protected route`() = testApp {
        val res = client.get("/api/stats") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals("DENY", res.headers["X-Frame-Options"])
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
        assertNotNull(res.headers["Strict-Transport-Security"])
        assertNotNull(res.headers["Content-Security-Policy"])
        assertNotNull(res.headers["Referrer-Policy"])
    }

    @Test
    fun `security headers present on public login route`() = testApp {
        val res = client.get("/login")
        assertEquals("DENY", res.headers["X-Frame-Options"])
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
    }

    @Test
    fun `CSP does not allow unsafe-inline scripts`() = testApp {
        val res = client.get("/login")
        val csp = res.headers["Content-Security-Policy"] ?: ""
        assert(!csp.contains("script-src") || !csp.contains("unsafe-inline")) {
            "CSP should not permit inline scripts: $csp"
        }
    }
}

private class FakeRepoForSec : com.example.repository.MetadataRepository {
    override suspend fun getAllPDFs() = emptyList<com.example.metadata.PDFMetadata>()
    override suspend fun getPDF(id: String) = null
    override suspend fun savePDF(m: com.example.metadata.PDFMetadata) {}
    override suspend fun deletePDF(id: String) {}
    override suspend fun search(q: String) = emptyList<com.example.metadata.PDFMetadata>()
    override suspend fun searchByProperty(k: String, v: String) = emptyList<com.example.metadata.PDFMetadata>()
    override suspend fun searchByAuthor(a: String) = emptyList<com.example.metadata.PDFMetadata>()
    override suspend fun searchByTitle(t: String) = emptyList<com.example.metadata.PDFMetadata>()
    override suspend fun count() = 0L
    override suspend fun loadFromStorage() {}
    override suspend fun persistToStorage() {}
    override suspend fun clear() {}
}
