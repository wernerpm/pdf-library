package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.auth.AuthService
import com.example.auth.AuthStore
import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.metadata.PDFMetadata
import com.example.repository.InMemoryMetadataRepository
import com.example.repository.MetadataRepository
import com.example.repository.RepositoryManager
import com.example.repository.TextContentStore
import com.example.storage.FileSystemStorage
import com.example.sync.SyncService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import java.util.Date

// Minimal fake backing repo (same pattern as MainTest)
private class FakeRepo : MetadataRepository {
    override suspend fun getAllPDFs() = emptyList<PDFMetadata>()
    override suspend fun getPDF(id: String) = null
    override suspend fun savePDF(metadata: PDFMetadata) {}
    override suspend fun deletePDF(id: String) {}
    override suspend fun search(query: String) = emptyList<PDFMetadata>()
    override suspend fun searchByProperty(key: String, value: String) = emptyList<PDFMetadata>()
    override suspend fun searchByAuthor(author: String) = emptyList<PDFMetadata>()
    override suspend fun searchByTitle(title: String) = emptyList<PDFMetadata>()
    override suspend fun count() = 0L
    override suspend fun loadFromStorage() {}
    override suspend fun persistToStorage() {}
    override suspend fun clear() {}
}

class MainAuthTest {

    private lateinit var tempDir: java.io.File
    private val secret = "test-secret-32-bytes-long-enough!"
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("main-auth-test").toFile()
        jwtSecret = secret

        appConfig = AppConfiguration(
            pdfScanPaths = listOf(tempDir.absolutePath),
            metadataStoragePath = tempDir.absolutePath,
            scanning = ScanConfiguration(),
            rpId = "localhost",
            rpName = "Test Library",
            jwtIssuer = "localhost"
        )

        val storage = FileSystemStorage(tempDir.absolutePath)
        val inMemRepo = InMemoryMetadataRepository(FakeRepo())
        repository = inMemRepo
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

    private fun validToken(username: String = "alice"): String =
        JWT.create()
            .withIssuer(appConfig.jwtIssuer)
            .withSubject(username)
            .withExpiresAt(Date(System.currentTimeMillis() + 8 * 3600 * 1000L))
            .sign(Algorithm.HMAC256(secret))

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureContentNegotiation()
            configureAuth()
            configureRouting()
        }
        block()
    }

    private fun JsonElement(s: String) = json.parseToJsonElement(s)

    // ---- Public routes should not require auth ----

    @Test
    fun `GET login is public`() = testApp {
        val res = client.get("/login")
        // 200 (page served) or 404 if resource not on classpath in test — either way, not 401
        assert(res.status != HttpStatusCode.Unauthorized)
    }

    @Test
    fun `POST api-auth-login-start is public`() = testApp {
        val res = client.post("/api/auth/login/start") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":null}""")
        }
        // 503 (authService initialised but no credentials) or 500, definitely not 401
        assertEquals(false, res.status == HttpStatusCode.Unauthorized)
    }

    // ---- Protected routes require auth ----

    @Test
    fun `GET api-pdfs returns 401 without token`() = testApp {
        val res = client.get("/api/pdfs")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `GET api-pdfs returns 200 with valid token`() = testApp {
        val res = client.get("/api/pdfs") {
            header(HttpHeaders.Authorization, "Bearer ${validToken()}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `GET api-stats returns 401 without token`() = testApp {
        val res = client.get("/api/stats")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `GET api-stats returns 200 with valid token`() = testApp {
        val res = client.get("/api/stats") {
            header(HttpHeaders.Authorization, "Bearer ${validToken()}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `GET status returns 401 without token`() = testApp {
        val res = client.get("/status")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `POST api-sync returns 401 without token`() = testApp {
        val res = client.post("/api/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"incremental"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `GET api-pdfs-id returns 401 without token`() = testApp {
        val res = client.get("/api/pdfs/some-id")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `GET api-thumbnails-id returns 401 without token`() = testApp {
        val res = client.get("/api/thumbnails/some-id")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `invalid token returns 401`() = testApp {
        val res = client.get("/api/pdfs") {
            header(HttpHeaders.Authorization, "Bearer not-a-real-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `expired token returns 401`() = testApp {
        val expired = JWT.create()
            .withIssuer(appConfig.jwtIssuer)
            .withSubject("alice")
            .withExpiresAt(Date(System.currentTimeMillis() - 1000L))
            .sign(Algorithm.HMAC256(secret))
        val res = client.get("/api/pdfs") {
            header(HttpHeaders.Authorization, "Bearer $expired")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
