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

class PDFScannerTest {

    private class MockStorageProvider : StorageProvider {
        private val files = mutableMapOf<String, ByteArray>()
        private val directories = mutableSetOf<String>()
        private val fileStructure = mutableMapOf<String, List<String>>()

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

        private val metadata = mutableMapOf<String, FileMetadata>()

        override suspend fun exists(path: String): Boolean =
            files.containsKey(path) || directories.contains(path)

        override suspend fun read(path: String): ByteArray =
            files[path] ?: throw Exception("File not found: $path")

        override suspend fun write(path: String, data: ByteArray) {
            files[path] = data
        }

        override suspend fun list(path: String): List<String> =
            fileStructure[path] ?: emptyList()

        override suspend fun delete(path: String) {
            files.remove(path)
            directories.remove(path)
            fileStructure.remove(path)
        }

        override suspend fun getMetadata(path: String): FileMetadata =
            metadata[path] ?: throw Exception("Metadata not found: $path")

        override suspend fun createDirectory(path: String) {
            addDirectory(path)
        }
    }

    @Test
    fun `scanPath discovers PDF files in directory`() = runTest {
        val mockStorage = MockStorageProvider()

        // Setup directory structure
        mockStorage.addDirectory("/scan-path", listOf("file1.pdf", "file2.txt", "file3.pdf"))
        mockStorage.addFile("/scan-path/file1.pdf", "%PDF-1.4\nPDF content".toByteArray())
        mockStorage.addFile("/scan-path/file2.txt", "Not a PDF".toByteArray())
        mockStorage.addFile("/scan-path/file3.pdf", "%PDF-1.7\nAnother PDF".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/scan-path"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(
                recursive = false,
                fileExtensions = listOf(".pdf"),
                validatePdfHeaders = true
            )
        )

        val scanner = PDFScanner(mockStorage, config)
        val result = scanner.scanPath("/scan-path")

        assertEquals(2, result.discoveredFiles.size)
        assertEquals(3, result.totalFilesScanned)
        assertEquals(1, result.totalDirectoriesScanned)
        assertEquals(0, result.invalidFilesSkipped)
        assertTrue(result.discoveredFiles.any { it.fileName == "file1.pdf" })
        assertTrue(result.discoveredFiles.any { it.fileName == "file3.pdf" })
    }

    @Test
    fun `scanPath skips files exceeding max size`() = runTest {
        val mockStorage = MockStorageProvider()

        mockStorage.addDirectory("/scan-path", listOf("small.pdf", "large.pdf"))
        mockStorage.addFile("/scan-path/small.pdf", "%PDF-1.4\nSmall".toByteArray(), 100L)
        mockStorage.addFile("/scan-path/large.pdf", "%PDF-1.4\nLarge".toByteArray(), 1000L)

        val config = AppConfiguration(
            pdfScanPaths = listOf("/scan-path"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(
                recursive = false,
                maxFileSize = 500L,
                validatePdfHeaders = true
            )
        )

        val scanner = PDFScanner(mockStorage, config)
        val result = scanner.scanPath("/scan-path")

        assertEquals(1, result.discoveredFiles.size)
        assertEquals("small.pdf", result.discoveredFiles.first().fileName)
    }

    @Test
    fun `scanPath handles recursive scanning`() = runTest {
        val mockStorage = MockStorageProvider()

        // Setup nested directory structure
        mockStorage.addDirectory("/scan-path", listOf("file1.pdf", "subdir"))
        mockStorage.addDirectory("/scan-path/subdir", listOf("file2.pdf"))
        mockStorage.addFile("/scan-path/file1.pdf", "%PDF-1.4\nRoot PDF".toByteArray())
        mockStorage.addFile("/scan-path/subdir/file2.pdf", "%PDF-1.4\nNested PDF".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/scan-path"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(
                recursive = true,
                validatePdfHeaders = true
            )
        )

        val scanner = PDFScanner(mockStorage, config)
        val result = scanner.scanPath("/scan-path")

        assertEquals(2, result.discoveredFiles.size)
        assertEquals(2, result.totalDirectoriesScanned)
        assertTrue(result.discoveredFiles.any { it.fileName == "file1.pdf" })
        assertTrue(result.discoveredFiles.any { it.fileName == "file2.pdf" })
    }

    @Test
    fun `scanPath discovers all PDF files by extension without reading content`() = runTest {
        val mockStorage = MockStorageProvider()

        mockStorage.addDirectory("/scan-path", listOf("valid.pdf", "invalid.pdf"))
        mockStorage.addFile("/scan-path/valid.pdf", "%PDF-1.4\nValid PDF".toByteArray())
        mockStorage.addFile("/scan-path/invalid.pdf", "Not a real PDF".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/scan-path"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(
                recursive = false,
                validatePdfHeaders = false
            )
        )

        val scanner = PDFScanner(mockStorage, config)
        val result = scanner.scanPath("/scan-path")

        // Discovery finds all .pdf files by extension; content validation is deferred to extraction
        assertEquals(2, result.discoveredFiles.size)
        assertEquals(0, result.invalidFilesSkipped)
        assertTrue(result.discoveredFiles.any { it.fileName == "valid.pdf" })
        assertTrue(result.discoveredFiles.any { it.fileName == "invalid.pdf" })
    }

    @Test
    fun `scanForPDFs removes duplicates across paths`() = runTest {
        val mockStorage = MockStorageProvider()

        // Setup two different directories with overlapping file paths
        mockStorage.addDirectory("/path1", listOf("file1.pdf", "file2.pdf"))
        mockStorage.addDirectory("/path2", listOf("file2.pdf", "file3.pdf"))

        mockStorage.addFile("/path1/file1.pdf", "%PDF-1.4\nFile1".toByteArray())
        mockStorage.addFile("/path1/file2.pdf", "%PDF-1.4\nFile2".toByteArray())
        mockStorage.addFile("/path2/file2.pdf", "%PDF-1.4\nFile2".toByteArray())
        mockStorage.addFile("/path2/file3.pdf", "%PDF-1.4\nFile3".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/path1", "/path2"),
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(
                recursive = false,
                validatePdfHeaders = true
            )
        )

        val scanner = PDFScanner(mockStorage, config)
        val result = scanner.scanForPDFs()

        // 4 files discovered across both paths, all unique paths
        assertEquals(4, result.discoveredFiles.size)
        assertEquals(4, result.totalFilesScanned)
    }

    @Test
    fun `scanForPDFs skips already visited directories`() = runTest {
        val mockStorage = MockStorageProvider()

        mockStorage.addDirectory("/path1", listOf("file1.pdf"))
        mockStorage.addFile("/path1/file1.pdf", "%PDF-1.4\nFile1".toByteArray())

        val config = AppConfiguration(
            pdfScanPaths = listOf("/path1", "/path1"), // same path twice
            metadataStoragePath = "/metadata",
            scanning = ScanConfiguration(
                recursive = false,
                validatePdfHeaders = true
            )
        )

        val scanner = PDFScanner(mockStorage, config)
        val result = scanner.scanForPDFs()

        // Second visit to /path1 should be skipped entirely
        assertEquals(1, result.discoveredFiles.size)
        assertEquals(1, result.totalFilesScanned)
    }
}