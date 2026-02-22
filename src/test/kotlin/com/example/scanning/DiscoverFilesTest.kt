package com.example.scanning

import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.storage.FileMetadata
import com.example.storage.StorageProvider
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverFilesTest {

    private class MockStorageProvider : StorageProvider {
        private val files = mutableMapOf<String, ByteArray>()
        private val directories = mutableSetOf<String>()
        private val fileStructure = mutableMapOf<String, List<String>>()
        private val metadata = mutableMapOf<String, FileMetadata>()
        var readCallCount = 0
            private set

        fun addFile(path: String, content: ByteArray, size: Long = content.size.toLong()) {
            files[path] = content
            metadata[path] = FileMetadata(
                path = path,
                size = size,
                createdAt = Instant.now(),
                modifiedAt = Instant.now(),
                isDirectory = false
            )
        }

        fun addDirectory(path: String, contents: List<String> = emptyList()) {
            directories.add(path)
            fileStructure[path] = contents
            metadata[path] = FileMetadata(
                path = path,
                size = 0L,
                createdAt = Instant.now(),
                modifiedAt = Instant.now(),
                isDirectory = true
            )
        }

        override suspend fun exists(path: String): Boolean =
            files.containsKey(path) || directories.contains(path)

        override suspend fun read(path: String): ByteArray {
            readCallCount++
            return files[path] ?: throw Exception("File not found: $path")
        }

        override suspend fun write(path: String, data: ByteArray) { files[path] = data }
        override suspend fun list(path: String): List<String> = fileStructure[path] ?: emptyList()
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun getMetadata(path: String): FileMetadata =
            metadata[path] ?: throw Exception("Metadata not found: $path")
        override suspend fun createDirectory(path: String) { addDirectory(path) }
    }

    @Test
    fun `discoverFiles returns manifest with all discovered PDFs`() = runTest {
        val mockStorage = MockStorageProvider()

        mockStorage.addDirectory("/pdfs", listOf("a.pdf", "b.pdf", "readme.txt"))
        mockStorage.addFile("/pdfs/a.pdf", "%PDF-1.4\nA".toByteArray())
        mockStorage.addFile("/pdfs/b.pdf", "%PDF-1.4\nB".toByteArray())
        mockStorage.addFile("/pdfs/readme.txt", "text".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/pdfs"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(recursive = false, validatePdfHeaders = true)
        )

        val scanner = PDFScanner(mockStorage, config)
        val manifest = scanner.discoverFiles()

        assertEquals(2, manifest.files.size)
        assertEquals(listOf("/pdfs"), manifest.scanPaths)
        assertTrue(manifest.files.all { it.status == FileStatus.DISCOVERED })
        assertTrue(manifest.files.any { it.fileName == "a.pdf" })
        assertTrue(manifest.files.any { it.fileName == "b.pdf" })
    }

    @Test
    fun `discoverFiles does not read PDF bytes`() = runTest {
        val mockStorage = MockStorageProvider()

        mockStorage.addDirectory("/pdfs", listOf("a.pdf", "b.pdf"))
        mockStorage.addFile("/pdfs/a.pdf", "%PDF-1.4\nA".toByteArray())
        mockStorage.addFile("/pdfs/b.pdf", "%PDF-1.4\nB".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/pdfs"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(recursive = false, validatePdfHeaders = false)
        )

        val scanner = PDFScanner(mockStorage, config)
        scanner.discoverFiles()

        // read() should only be called for PDF header validation, which is disabled
        assertEquals(0, mockStorage.readCallCount)
    }

    @Test
    fun `discoverFiles with recursive scanning`() = runTest {
        val mockStorage = MockStorageProvider()

        mockStorage.addDirectory("/pdfs", listOf("a.pdf", "sub"))
        mockStorage.addDirectory("/pdfs/sub", listOf("b.pdf"))
        mockStorage.addFile("/pdfs/a.pdf", "%PDF-1.4\nA".toByteArray())
        mockStorage.addFile("/pdfs/sub/b.pdf", "%PDF-1.4\nB".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/pdfs"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(recursive = true, validatePdfHeaders = true)
        )

        val scanner = PDFScanner(mockStorage, config)
        val manifest = scanner.discoverFiles()

        assertEquals(2, manifest.files.size)
    }

    @Test
    fun `discoverFiles with empty directory`() = runTest {
        val mockStorage = MockStorageProvider()

        mockStorage.addDirectory("/pdfs", emptyList())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/pdfs"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(recursive = false, validatePdfHeaders = false)
        )

        val scanner = PDFScanner(mockStorage, config)
        val manifest = scanner.discoverFiles()

        assertTrue(manifest.files.isEmpty())
    }
}
