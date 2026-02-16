package com.example.repository

import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.metadata.PDFMetadata
import com.example.storage.FileSystemStorage
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepositoryIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `repository should store and retrieve metadata successfully`() = runTest {
        // Setup
        val storage = FileSystemStorage(tempDir.toString())
        val config = AppConfiguration(
            pdfScanPaths = listOf(tempDir.toString()),
            metadataStoragePath = "$tempDir/metadata",
            scanning = ScanConfiguration(
                excludePatterns = emptyList(),
                maxFileSize = 100L * 1024 * 1024
            )
        )

        // Create repository with layered architecture
        val jsonRepository = JsonRepository(storage, config.metadataStoragePath)
        val repository = InMemoryMetadataRepository(jsonRepository)
        val consistencyManager = ConsistencyManager(repository, jsonRepository)
        val repositoryManager = RepositoryManager(repository, consistencyManager)

        // Test data
        val testMetadata = PDFMetadata(
            id = "test-1",
            path = "/test/sample.pdf",
            fileName = "sample.pdf",
            fileSize = 1024,
            pageCount = 5,
            createdDate = null,
            modifiedDate = null,
            title = "Test Document",
            author = "Test Author",
            subject = "Testing",
            creator = "Test Creator",
            producer = "Test Producer",
            keywords = listOf("test", "sample"),
            pdfVersion = "1.4",
            customProperties = mapOf("category" to "test", "importance" to "high"),
            contentHash = "test-hash-123",
            isEncrypted = false,
            isSignedPdf = false,
            indexedAt = Clock.System.now()
        )

        // Test repository operations
        repository.savePDF(testMetadata)
        assertEquals(1, repository.count())

        val retrieved = repository.getPDF("test-1")
        assertNotNull(retrieved)
        assertEquals("Test Document", retrieved.title)
        assertEquals("Test Author", retrieved.author)

        // Test search functionality
        val searchResults = repository.search("Test Document")
        assertEquals(1, searchResults.size)
        assertEquals("test-1", searchResults[0].id)

        val authorSearch = repository.searchByAuthor("Test Author")
        assertEquals(1, authorSearch.size)

        val propertySearch = repository.searchByProperty("category", "test")
        assertEquals(1, propertySearch.size)

        // Test initialization and consistency
        val initResult = repositoryManager.initialize()
        assertTrue(initResult is InitializationResult.Success)

        // Test backup
        val backupResult = repositoryManager.createBackup()
        assertTrue(backupResult is BackupResult.Success)

        // Test status
        val status = repositoryManager.getStatus()
        assertTrue(status.isInitialized)
        assertEquals(1, status.totalRecords)

        println("✅ Repository integration test completed successfully!")
        println("   - Stored and retrieved metadata: ✓")
        println("   - Search functionality: ✓")
        println("   - Consistency management: ✓")
        println("   - Backup functionality: ✓")
    }
}