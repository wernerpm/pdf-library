package com.example.sync

import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.metadata.PDFMetadata
import com.example.repository.MetadataRepository
import com.example.scanning.DiscoveryManifest
import com.example.scanning.FileStatus
import com.example.scanning.PDFFileInfo
import com.example.storage.FileMetadata
import com.example.storage.StorageProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class DiffManifestsTest {

    // Minimal in-memory storage for DiscoveryManifestManager
    private class InMemoryStorage : StorageProvider {
        val files = mutableMapOf<String, ByteArray>()
        override suspend fun exists(path: String) = files.containsKey(path)
        override suspend fun read(path: String) = files[path] ?: throw Exception("Not found: $path")
        override suspend fun write(path: String, data: ByteArray) { files[path] = data }
        override suspend fun list(path: String) = emptyList<String>()
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun getMetadata(path: String): FileMetadata = throw UnsupportedOperationException()
        override suspend fun createDirectory(path: String) {}
    }

    // Repository that records which IDs were deleted
    private class TrackingRepository : MetadataRepository {
        val deletedIds = mutableListOf<String>()
        override suspend fun getAllPDFs() = emptyList<PDFMetadata>()
        override suspend fun getPDF(id: String) = null
        override suspend fun savePDF(metadata: PDFMetadata) {}
        override suspend fun deletePDF(id: String) { deletedIds.add(id) }
        override suspend fun search(query: String) = emptyList<PDFMetadata>()
        override suspend fun searchByProperty(key: String, value: String) = emptyList<PDFMetadata>()
        override suspend fun searchByAuthor(author: String) = emptyList<PDFMetadata>()
        override suspend fun searchByTitle(title: String) = emptyList<PDFMetadata>()
        override suspend fun count() = 0L
        override suspend fun loadFromStorage() {}
        override suspend fun persistToStorage() {}
        override suspend fun clear() {}
    }

    private fun makeService(
        scanPaths: List<String>,
        repo: TrackingRepository = TrackingRepository()
    ): Pair<SyncService, TrackingRepository> {
        val config = AppConfiguration(
            pdfScanPaths = scanPaths,
            metadataStoragePath = "/tmp/meta",
            scanning = ScanConfiguration()
        )
        val service = SyncService(InMemoryStorage(), config, repo)
        return service to repo
    }

    private fun fileInfo(
        path: String,
        size: Long = 1024,
        lastModified: Instant = Clock.System.now(),
        status: FileStatus = FileStatus.EXTRACTED
    ) = PDFFileInfo(
        path = path,
        fileName = path.substringAfterLast('/'),
        fileSize = size,
        lastModified = lastModified,
        status = status
    )

    private fun manifest(scanPaths: List<String>, vararg files: PDFFileInfo) = DiscoveryManifest(
        lastDiscovery = Clock.System.now(),
        scanPaths = scanPaths,
        files = files.toList()
    )

    // --- Normal deletion cases ---

    @Test
    fun `genuinely deleted file is removed when scan path is available`() = runTest {
        val (service, repo) = makeService(listOf("/books"))
        val existing = manifest(listOf("/books"), fileInfo("/books/a.pdf"), fileInfo("/books/b.pdf"))
        // New discovery still finds a.pdf — b.pdf was genuinely deleted
        val new = manifest(listOf("/books"), fileInfo("/books/a.pdf", status = FileStatus.DISCOVERED))

        val merged = service.diffManifests(existing, new)

        assertTrue(repo.deletedIds.isNotEmpty(), "b.pdf should be deleted from repository")
        assertEquals(1, merged.files.size)
        assertEquals("/books/a.pdf", merged.files[0].path)
    }

    @Test
    fun `unchanged extracted file keeps EXTRACTED status`() = runTest {
        val (service, _) = makeService(listOf("/books"))
        val now = Clock.System.now()
        val existing = manifest(listOf("/books"),
            PDFFileInfo("/books/a.pdf", "a.pdf", 1024, now, status = FileStatus.EXTRACTED))
        val new = manifest(listOf("/books"),
            PDFFileInfo("/books/a.pdf", "a.pdf", 1024, now, status = FileStatus.DISCOVERED))

        val merged = service.diffManifests(existing, new)

        assertEquals(FileStatus.EXTRACTED, merged.files[0].status)
    }

    @Test
    fun `changed file size resets status to DISCOVERED`() = runTest {
        val (service, _) = makeService(listOf("/books"))
        val now = Clock.System.now()
        val existing = manifest(listOf("/books"),
            PDFFileInfo("/books/a.pdf", "a.pdf", 1000, now, status = FileStatus.EXTRACTED))
        val new = manifest(listOf("/books"),
            PDFFileInfo("/books/a.pdf", "a.pdf", 2000, now, status = FileStatus.DISCOVERED))

        val merged = service.diffManifests(existing, new)

        assertEquals(FileStatus.DISCOVERED, merged.files[0].status)
    }

    // --- SMB guard cases ---

    @Test
    fun `SMB guard - no deletions when scan path returns 0 results`() = runTest {
        val (service, repo) = makeService(listOf("/books"))
        val existing = manifest(listOf("/books"), fileInfo("/books/a.pdf"), fileInfo("/books/b.pdf"))
        val new = manifest(listOf("/books")) // 0 results — volume offline

        val merged = service.diffManifests(existing, new)

        assertTrue(repo.deletedIds.isEmpty(), "No deletions should occur when scan path returns 0 results")
        assertEquals(2, merged.files.size, "Existing entries must be preserved in merged manifest")
        assertTrue(merged.files.any { it.path == "/books/a.pdf" })
        assertTrue(merged.files.any { it.path == "/books/b.pdf" })
    }

    @Test
    fun `SMB guard - partial unavailability, only safe-path deletions proceed`() = runTest {
        val (service, repo) = makeService(listOf("/books", "/papers"))
        val existing = manifest(listOf("/books", "/papers"),
            fileInfo("/books/a.pdf"),
            fileInfo("/books/b.pdf"),
            fileInfo("/papers/x.pdf"),
            fileInfo("/papers/y.pdf")
        )
        // /books offline (0), /papers available but y.pdf was deleted
        val new = manifest(listOf("/books", "/papers"),
            fileInfo("/papers/x.pdf", status = FileStatus.DISCOVERED))

        val merged = service.diffManifests(existing, new)

        // /papers/y.pdf should be deleted (path is available and file is genuinely gone)
        assertTrue(repo.deletedIds.isNotEmpty(), "/papers/y.pdf should be deleted")
        // /books entries should be preserved in merged manifest
        assertTrue(merged.files.any { it.path == "/books/a.pdf" }, "/books/a.pdf must be preserved")
        assertTrue(merged.files.any { it.path == "/books/b.pdf" }, "/books/b.pdf must be preserved")
        // /papers/x.pdf should remain
        assertTrue(merged.files.any { it.path == "/papers/x.pdf" })
        // /papers/y.pdf should not be in merged (correctly deleted)
        assertTrue(merged.files.none { it.path == "/papers/y.pdf" })
    }

    @Test
    fun `SMB guard - new file on available path is DISCOVERED even when other path is offline`() = runTest {
        val (service, _) = makeService(listOf("/books", "/papers"))
        val existing = manifest(listOf("/books", "/papers"),
            fileInfo("/books/a.pdf"),
            fileInfo("/papers/x.pdf")
        )
        // /books offline (0), /papers has a new file
        val new = manifest(listOf("/books", "/papers"),
            fileInfo("/papers/x.pdf", status = FileStatus.DISCOVERED),
            fileInfo("/papers/z.pdf", status = FileStatus.DISCOVERED)
        )

        val merged = service.diffManifests(existing, new)

        assertTrue(merged.files.any { it.path == "/papers/z.pdf" && it.status == FileStatus.DISCOVERED })
        assertTrue(merged.files.any { it.path == "/books/a.pdf" }, "/books/a.pdf must be preserved")
    }

    @Test
    fun `empty existing manifest - no SMB guard triggered`() = runTest {
        val (service, repo) = makeService(listOf("/books"))
        val existing = manifest(listOf("/books")) // no existing entries
        val new = manifest(listOf("/books"))      // no new entries either

        val merged = service.diffManifests(existing, new)

        assertTrue(repo.deletedIds.isEmpty())
        assertTrue(merged.files.isEmpty())
    }
}
