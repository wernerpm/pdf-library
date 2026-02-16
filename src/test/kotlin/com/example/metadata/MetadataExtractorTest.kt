package com.example.metadata

import com.example.scanning.PDFFileInfo
import com.example.storage.StorageProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataExtractorTest {

    private val mockStorageProvider = mockk<StorageProvider>()
    private val extractor = MetadataExtractor(mockStorageProvider)

    @Test
    fun `extractMetadata should return null for invalid file`() = runTest {
        val fileInfo = PDFFileInfo(
            path = "/test/invalid.pdf",
            fileName = "invalid.pdf",
            fileSize = 100L,
            lastModified = Clock.System.now()
        )

        coEvery { mockStorageProvider.read("/test/invalid.pdf") } throws Exception("File not found")

        val result = extractor.extractMetadata(fileInfo)

        assertNull(result)
    }

    @Test
    fun `extractMetadata should return null for invalid PDF content`() = runTest {
        val fileInfo = PDFFileInfo(
            path = "/test/invalid.pdf",
            fileName = "invalid.pdf",
            fileSize = 100L,
            lastModified = Clock.System.now()
        )

        coEvery { mockStorageProvider.read("/test/invalid.pdf") } returns "invalid pdf content".toByteArray()

        val result = extractor.extractMetadata(fileInfo)

        assertNull(result)
    }

    @Test
    fun `extractMetadata should handle empty file gracefully`() = runTest {
        val fileInfo = PDFFileInfo(
            path = "/test/empty.pdf",
            fileName = "empty.pdf",
            fileSize = 0L,
            lastModified = Clock.System.now()
        )

        coEvery { mockStorageProvider.read("/test/empty.pdf") } returns ByteArray(0)

        val result = extractor.extractMetadata(fileInfo)

        assertNull(result)
    }

    // Note: Testing with actual PDF content would require sample PDF files
    // For now, we're testing error conditions and basic structure
}