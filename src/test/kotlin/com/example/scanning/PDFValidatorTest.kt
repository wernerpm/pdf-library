package com.example.scanning

import com.example.storage.FileMetadata
import com.example.storage.StorageProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PDFValidatorTest {

    private class MockStorageProvider : StorageProvider {
        private val files = mutableMapOf<String, ByteArray>()

        fun addFile(path: String, content: ByteArray) {
            files[path] = content
        }

        override suspend fun exists(path: String): Boolean = files.containsKey(path)

        override suspend fun read(path: String): ByteArray =
            files[path] ?: throw Exception("File not found: $path")

        override suspend fun write(path: String, data: ByteArray) {
            files[path] = data
        }

        override suspend fun list(path: String): List<String> = emptyList()

        override suspend fun delete(path: String) {
            files.remove(path)
        }

        override suspend fun getMetadata(path: String): FileMetadata {
            throw NotImplementedError("Not needed for this test")
        }

        override suspend fun createDirectory(path: String) {
            // No-op for tests
        }
    }

    @Test
    fun `isValidPDF returns true for valid PDF file`() = runTest {
        val mockStorage = MockStorageProvider()
        val pdfContent = "%PDF-1.4\n%âãÏÓ".toByteArray()
        mockStorage.addFile("/test.pdf", pdfContent)

        val validator = PDFValidator(mockStorage)
        assertTrue(validator.isValidPDF("/test.pdf"))
    }

    @Test
    fun `isValidPDF returns false for invalid PDF file`() = runTest {
        val mockStorage = MockStorageProvider()
        val invalidContent = "Not a PDF file".toByteArray()
        mockStorage.addFile("/test.txt", invalidContent)

        val validator = PDFValidator(mockStorage)
        assertFalse(validator.isValidPDF("/test.txt"))
    }

    @Test
    fun `isValidPDF returns false for non-existent file`() = runTest {
        val mockStorage = MockStorageProvider()
        val validator = PDFValidator(mockStorage)
        assertFalse(validator.isValidPDF("/nonexistent.pdf"))
    }

    @Test
    fun `getPDFVersion extracts version correctly`() = runTest {
        val mockStorage = MockStorageProvider()
        val pdfContent = "%PDF-1.7\n%âãÏÓ".toByteArray()
        mockStorage.addFile("/test.pdf", pdfContent)

        val validator = PDFValidator(mockStorage)
        assertEquals("1.7", validator.getPDFVersion("/test.pdf"))
    }

    @Test
    fun `getPDFVersion returns null for invalid PDF`() = runTest {
        val mockStorage = MockStorageProvider()
        val invalidContent = "Not a PDF file".toByteArray()
        mockStorage.addFile("/test.txt", invalidContent)

        val validator = PDFValidator(mockStorage)
        assertEquals(null, validator.getPDFVersion("/test.txt"))
    }
}