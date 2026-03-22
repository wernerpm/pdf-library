package com.example.sync

import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.metadata.PDFMetadata
import com.example.repository.MetadataRepository
import com.example.scanning.DiscoveryManifest
import com.example.scanning.FileStatus
import com.example.scanning.PDFFileInfo
import com.example.storage.FileMetadata
import com.example.storage.FileSystemStorage
import com.example.storage.StorageProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class SyncServiceRetryTest {

    private lateinit var tempDir: File
    private lateinit var service: SyncService

    private class InMemoryStorage : StorageProvider {
        override suspend fun exists(path: String) = false
        override suspend fun read(path: String): ByteArray = throw Exception("Not found: $path")
        override suspend fun write(path: String, data: ByteArray) {}
        override suspend fun list(path: String) = emptyList<String>()
        override suspend fun delete(path: String) {}
        override suspend fun getMetadata(path: String): FileMetadata = throw UnsupportedOperationException()
        override suspend fun createDirectory(path: String) {}
    }

    private class NoOpRepository : MetadataRepository {
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

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("sync-retry-test").toFile()
        val config = AppConfiguration(
            pdfScanPaths = listOf(tempDir.absolutePath),
            metadataStoragePath = tempDir.absolutePath,
            scanning = ScanConfiguration()
        )
        service = SyncService(InMemoryStorage(), config, NoOpRepository())
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeManifest(manifest: DiscoveryManifest) {
        val bytes = json.encodeToString(DiscoveryManifest.serializer(), manifest).encodeToByteArray()
        File(tempDir, "discovery-manifest.json").writeBytes(bytes)
    }

    private fun fileInfo(path: String, status: FileStatus) = PDFFileInfo(
        path = path,
        fileName = path.substringAfterLast('/'),
        fileSize = 1024,
        lastModified = Clock.System.now(),
        status = status
    )

    @Test
    fun `performRetryFailed returns error when no manifest exists`() = runTest {
        val result = service.performRetryFailed()

        assertEquals(SyncType.RETRY_FAILED, result.syncType)
        assertTrue(result.totalErrors > 0)
        assertTrue(
            result.errors.any { it.path.contains("manifest", ignoreCase = true) ||
                    it.message.contains("manifest", ignoreCase = true) },
            "Error should mention missing manifest"
        )
    }

    @Test
    fun `performRetryFailed resets FAILED entries to DISCOVERED and attempts extraction`() = runTest {
        writeManifest(DiscoveryManifest(
            lastDiscovery = Clock.System.now(),
            scanPaths = listOf(tempDir.absolutePath),
            files = listOf(
                fileInfo("/books/ok.pdf", FileStatus.EXTRACTED),
                fileInfo("/books/fail1.pdf", FileStatus.FAILED),
                fileInfo("/books/fail2.pdf", FileStatus.FAILED)
            )
        ))

        val result = service.performRetryFailed()

        assertEquals(SyncType.RETRY_FAILED, result.syncType)
        // EXTRACTED entry is skipped; both FAILED entries were attempted (they'll fail since files don't exist)
        assertEquals(2, result.totalErrors, "Both FAILED entries should have been attempted and failed")
        assertEquals(0, result.metadataExtracted)
    }

    @Test
    fun `performRetryFailed with no FAILED entries does nothing`() = runTest {
        writeManifest(DiscoveryManifest(
            lastDiscovery = Clock.System.now(),
            scanPaths = listOf(tempDir.absolutePath),
            files = listOf(
                fileInfo("/books/ok.pdf", FileStatus.EXTRACTED),
                fileInfo("/books/ok2.pdf", FileStatus.EXTRACTED)
            )
        ))

        val result = service.performRetryFailed()

        assertEquals(SyncType.RETRY_FAILED, result.syncType)
        assertEquals(0, result.metadataExtracted)
        assertEquals(0, result.totalErrors)
        assertEquals(2, result.metadataSkipped)
    }

    @Test
    fun `performRetryFailed releases sync lock so subsequent sync can run`() = runTest {
        // No manifest → returns error; lock must be released
        val result1 = service.performRetryFailed()
        val result2 = service.performRetryFailed()

        assertEquals(SyncType.RETRY_FAILED, result1.syncType)
        assertEquals(SyncType.RETRY_FAILED, result2.syncType)
        // Neither should be the "already in progress" result (which has "SYNC_IN_PROGRESS" error path)
        assertTrue(result1.errors.none { it.path == "SYNC_IN_PROGRESS" })
        assertTrue(result2.errors.none { it.path == "SYNC_IN_PROGRESS" })
    }
}
