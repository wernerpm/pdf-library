package com.example

import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.metadata.PDFMetadata
import com.example.repository.InMemoryMetadataRepository
import com.example.repository.JsonRepository
import com.example.repository.MetadataRepository
import com.example.repository.RepositoryMemoryInfo
import com.example.repository.RepositoryManager
import com.example.repository.RepositoryMetrics
import com.example.repository.RepositoryStatus
import com.example.repository.TextContentStore
import com.example.scanning.DiscoveryManifest
import com.example.scanning.FileStatus
import com.example.scanning.PDFFileInfo
import com.example.storage.FileMetadata
import com.example.storage.FileSystemStorage
import com.example.storage.StorageProvider
import com.example.sync.ExtractionPhase
import com.example.sync.ExtractionProgress
import com.example.sync.SyncResult
import com.example.sync.SyncService
import com.example.sync.SyncType
import io.ktor.client.request.*
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

// ---- fake backing repository for InMemoryMetadataRepository ----
private class FakeBackingRepo : MetadataRepository {
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

class MainApiTest {

    private lateinit var tempDir: java.io.File
    private val json = Json { ignoreUnknownKeys = true }

    private fun testPdf(id: String, title: String? = null, author: String? = null) = PDFMetadata(
        id = id,
        path = "/books/$id.pdf",
        fileName = "$id.pdf",
        fileSize = 1024L,
        pageCount = 10,
        createdDate = null,
        modifiedDate = null,
        title = title,
        author = author,
        subject = null,
        creator = null,
        producer = null,
        pdfVersion = "1.4",
        contentHash = null,
        indexedAt = Clock.System.now()
    )

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("api-test").toFile()
        val metadataStorage = FileSystemStorage(tempDir.absolutePath)

        // Real InMemoryMetadataRepository so stats/search/cast work correctly
        val inMemRepo = InMemoryMetadataRepository(FakeBackingRepo())
        runBlocking {
            inMemRepo.savePDF(testPdf("pdf1", title = "Alpha Book", author = "Alice"))
            inMemRepo.savePDF(testPdf("pdf2", title = "Beta Book", author = "Bob"))
        }
        repository = inMemRepo

        appConfig = AppConfiguration(
            pdfScanPaths = listOf(tempDir.absolutePath),
            metadataStoragePath = tempDir.absolutePath,
            scanning = ScanConfiguration()
        )

        textContentStore = TextContentStore(metadataStorage)

        repositoryManager = mockk<RepositoryManager>(relaxed = true).also { rm ->
            coEvery { rm.getStatus() } returns RepositoryStatus(
                isInitialized = true,
                initializationTime = Clock.System.now(),
                lastConsistencyCheck = null,
                totalRecords = 2,
                memoryInfo = RepositoryMemoryInfo(2, 2, 2, 2),
                metrics = RepositoryMetrics(2, Duration.ZERO, null)
            )
        }

        syncService = mockk<SyncService>(relaxed = true).also { ss ->
            every { ss.isSyncInProgress() } returns false
            every { ss.getExtractionProgress() } returns ExtractionProgress.idle()
            every { ss.isFileWatchingEnabled() } returns false
            coEvery { ss.loadManifest() } returns null
            coEvery { ss.performFullSync() } returns SyncResult(
                syncType = SyncType.FULL, filesScanned = 2, filesDiscovered = 2,
                metadataExtracted = 2, metadataStored = 2, metadataSkipped = 0,
                duplicatesRemoved = 0, totalErrors = 0,
                scanDuration = Duration.ZERO, extractionDuration = Duration.ZERO,
                syncDuration = Duration.ZERO, errors = emptyList(),
                startTime = Clock.System.now(), endTime = Clock.System.now()
            )
            coEvery { ss.performIncrementalSync() } returns SyncResult(
                syncType = SyncType.INCREMENTAL, filesScanned = 2, filesDiscovered = 0,
                metadataExtracted = 0, metadataStored = 0, metadataSkipped = 2,
                duplicatesRemoved = 0, totalErrors = 0,
                scanDuration = Duration.ZERO, extractionDuration = Duration.ZERO,
                syncDuration = Duration.ZERO, errors = emptyList(),
                startTime = Clock.System.now(), endTime = Clock.System.now()
            )
            coEvery { ss.performRetryFailed() } returns SyncResult(
                syncType = SyncType.RETRY_FAILED, filesScanned = 0, filesDiscovered = 0,
                metadataExtracted = 0, metadataStored = 0, metadataSkipped = 2,
                duplicatesRemoved = 0, totalErrors = 0,
                scanDuration = Duration.ZERO, extractionDuration = Duration.ZERO,
                syncDuration = Duration.ZERO, errors = emptyList(),
                startTime = Clock.System.now(), endTime = Clock.System.now()
            )
        }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureContentNegotiation()
            configureRouting()
        }
        block()
    }

    private fun JsonElement.str(key: String) = jsonObject[key]?.jsonPrimitive?.content
    private fun JsonElement.int(key: String) = jsonObject[key]?.jsonPrimitive?.int
    private fun JsonElement.bool(key: String) = jsonObject[key]?.jsonPrimitive?.boolean
    private fun JsonElement.obj(key: String) = jsonObject[key]?.jsonObject
    private fun JsonElement.arr(key: String) = jsonObject[key]?.jsonArray

    // ---- Redirect ----

    @Test
    fun `GET slash redirects to static index`() = testApp {
        // Use a non-redirecting client so we can inspect the 302 response itself
        val noRedirectClient = createClient { followRedirects = false }
        val response = noRedirectClient.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers[HttpHeaders.Location]?.contains("index.html") == true)
    }

    // ---- GET /api/pdfs ----

    @Test
    fun `GET api-pdfs returns 200 with paginated result`() = testApp {
        val response = client.get("/api/pdfs")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText())
        assertEquals(true, body.bool("success"))
        val data = body.obj("data")
        assertNotNull(data)
        assertEquals(2, data.int("total"))
        assertNotNull(data.arr("data"))
    }

    @Test
    fun `GET api-pdfs pagination slices correctly`() = testApp {
        val response = client.get("/api/pdfs?page=0&size=1")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).obj("data")!!
        assertEquals(1, data.arr("data")!!.size)
        assertEquals(2, data.int("total"))
        assertEquals(2, data.int("totalPages"))
    }

    @Test
    fun `GET api-pdfs page beyond total returns empty data list`() = testApp {
        val response = client.get("/api/pdfs?page=99&size=50")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).obj("data")!!
        assertEquals(0, data.arr("data")!!.size)
    }

    @Test
    fun `GET api-pdfs with search query returns results`() = testApp {
        val response = client.get("/api/pdfs?q=Alpha")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).obj("data")!!
        val results = data.arr("data")!!
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.str("title") == "Alpha Book" })
    }

    @Test
    fun `GET api-pdfs sort by title desc puts Beta first`() = testApp {
        val response = client.get("/api/pdfs?sort=title&order=desc")
        assertEquals(HttpStatusCode.OK, response.status)
        val items = json.parseToJsonElement(response.bodyAsText()).obj("data")!!.arr("data")!!
        assertEquals("Beta Book", items[0].str("title"))
        assertEquals("Alpha Book", items[1].str("title"))
    }

    @Test
    fun `GET api-pdfs sort by title asc puts Alpha first`() = testApp {
        val response = client.get("/api/pdfs?sort=title&order=asc")
        assertEquals(HttpStatusCode.OK, response.status)
        val items = json.parseToJsonElement(response.bodyAsText()).obj("data")!!.arr("data")!!
        assertEquals("Alpha Book", items[0].str("title"))
    }

    // ---- GET /api/pdfs/{id} ----

    @Test
    fun `GET api-pdfs by id returns 200 for existing pdf`() = testApp {
        val response = client.get("/api/pdfs/pdf1")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).obj("data")!!
        assertEquals("pdf1", data.str("id"))
        assertEquals("Alpha Book", data.str("title"))
    }

    @Test
    fun `GET api-pdfs by id returns 404 for missing pdf`() = testApp {
        val response = client.get("/api/pdfs/nonexistent")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = json.parseToJsonElement(response.bodyAsText())
        assertEquals(false, body.bool("success"))
    }

    // ---- GET /api/pdfs/{id}/text ----

    @Test
    fun `GET api-pdfs text returns 404 when no text content`() = testApp {
        val response = client.get("/api/pdfs/pdf1/text")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET api-pdfs text returns 200 with stored text`() = testApp {
        runBlocking { textContentStore.save("pdf1", "hello world content") }
        val response = client.get("/api/pdfs/pdf1/text")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("hello world content"))
    }

    @Test
    fun `GET api-pdfs text returns 404 for unknown id`() = testApp {
        val response = client.get("/api/pdfs/unknown/text")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- GET /api/pdfs/{id}/file ----

    @Test
    fun `GET api-pdfs file returns 404 when file not on disk`() = testApp {
        val response = client.get("/api/pdfs/pdf1/file")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET api-pdfs file returns 404 for unknown id`() = testApp {
        val response = client.get("/api/pdfs/unknown/file")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- GET /api/thumbnails/{id} ----

    @Test
    fun `GET api-thumbnails returns 404 when no thumbnail`() = testApp {
        val response = client.get("/api/thumbnails/pdf1")
        // pdf1 has thumbnailPath=null
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET api-thumbnails returns 404 for unknown id`() = testApp {
        val response = client.get("/api/thumbnails/nonexistent")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- GET /api/stats ----

    @Test
    fun `GET api-stats returns 200 with library stats`() = testApp {
        val response = client.get("/api/stats")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).obj("data")!!
        assertNotNull(data.int("totalPdfs"))
        assertEquals(2, data.int("totalPdfs"))
    }

    // ---- GET /status ----

    @Test
    fun `GET status returns 200 with system status`() = testApp {
        val response = client.get("/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).obj("data")!!
        assertNotNull(data.bool("syncInProgress"))
        assertEquals(false, data.bool("syncInProgress"))
    }

    // ---- GET /api/manifest ----

    @Test
    fun `GET api-manifest returns 404 when no manifest exists`() = testApp {
        // syncService.loadManifest() is stubbed to return null
        val response = client.get("/api/manifest")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(false, json.parseToJsonElement(response.bodyAsText()).bool("success"))
    }

    @Test
    fun `GET api-manifest returns 200 with counts when manifest exists`() = testApp {
        val manifest = DiscoveryManifest(
            lastDiscovery = Clock.System.now(),
            scanPaths = listOf("/books"),
            files = listOf(
                PDFFileInfo("/books/a.pdf", "a.pdf", 1024, Clock.System.now(), status = FileStatus.EXTRACTED),
                PDFFileInfo("/books/b.pdf", "b.pdf", 1024, Clock.System.now(), status = FileStatus.DISCOVERED),
                PDFFileInfo("/books/c.pdf", "c.pdf", 1024, Clock.System.now(), status = FileStatus.FAILED)
            )
        )
        coEvery { syncService.loadManifest() } returns manifest

        val response = client.get("/api/manifest")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json.parseToJsonElement(response.bodyAsText()).obj("data")!!
        assertEquals(3, data.int("totalFiles"))
        assertEquals(1, data.int("extracted"))
        assertEquals(1, data.int("pending"))
        assertEquals(1, data.int("failed"))
        val failedFiles = data.arr("failedFiles")!!
        assertEquals(1, failedFiles.size)
        assertEquals("/books/c.pdf", failedFiles[0].jsonPrimitive.content)
    }

    // ---- POST /api/sync ----

    @Test
    fun `POST api-sync full returns 200`() = testApp {
        val response = client.post("/api/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"full"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, json.parseToJsonElement(response.bodyAsText()).bool("success"))
    }

    @Test
    fun `POST api-sync incremental returns 200`() = testApp {
        val response = client.post("/api/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"incremental"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, json.parseToJsonElement(response.bodyAsText()).bool("success"))
    }

    @Test
    fun `POST api-sync retry-failed returns 200`() = testApp {
        val response = client.post("/api/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"retry-failed"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, json.parseToJsonElement(response.bodyAsText()).bool("success"))
    }

    @Test
    fun `POST api-sync with invalid type returns 500`() = testApp {
        val response = client.post("/api/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"bogus"}""")
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(false, json.parseToJsonElement(response.bodyAsText()).bool("success"))
    }
}
