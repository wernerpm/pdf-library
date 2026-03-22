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
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.Date

class MainCorsTest {

    private lateinit var tempDir: java.io.File
    private val secret = "test-secret-32-bytes-long-enough!"
    private val configuredOrigin = "https://library.example.com"

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("cors-test").toFile()
        jwtSecret = secret
        appUrl = configuredOrigin

        appConfig = AppConfiguration(
            pdfScanPaths = listOf(tempDir.absolutePath),
            metadataStoragePath = tempDir.absolutePath,
            scanning = ScanConfiguration(),
            rpId = "library.example.com",
            rpName = "Test Library",
            jwtIssuer = "library.example.com"
        )

        val storage = FileSystemStorage(tempDir.absolutePath)
        repository = InMemoryMetadataRepository(FakeRepoForCors())
        textContentStore = TextContentStore(storage)
        repositoryManager = mockk(relaxed = true)
        syncService = mockk(relaxed = true)

        val authStore = AuthStore(storage)
        runBlocking { authStore.loadFromDisk() }
        authService = AuthService(appConfig.rpId, appConfig.rpName, authStore)
    }

    @AfterTest
    fun tearDown() {
        appUrl = null
        tempDir.deleteRecursively()
    }

    private fun token() = JWT.create()
        .withIssuer("library.example.com")
        .withSubject("alice")
        .withExpiresAt(Date(System.currentTimeMillis() + 8 * 3600 * 1000L))
        .sign(Algorithm.HMAC256(secret))

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureContentNegotiation()
            configureSecurityHeaders()
            appUrl?.let { configureCors(it) }
            configureAuth()
            configureRouting()
        }
        block()
    }

    @Test
    fun `same-origin preflight is allowed`() = testApp {
        val res = client.options("/api/pdfs") {
            header(HttpHeaders.Origin, configuredOrigin)
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, "Authorization")
        }
        assertEquals(configuredOrigin, res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `cross-origin preflight is blocked`() = testApp {
        val res = client.options("/api/pdfs") {
            header(HttpHeaders.Origin, "https://evil.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertNull(res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `same-origin GET carries ACAO header`() = testApp {
        val res = client.get("/api/pdfs") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            header(HttpHeaders.Origin, configuredOrigin)
        }
        assertEquals(configuredOrigin, res.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}

private class FakeRepoForCors : com.example.repository.MetadataRepository {
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
