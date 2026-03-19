package com.example.metadata

import com.example.scanning.PDFFileInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchMetadataExtractorTest {

    private val mockMetadataExtractor = mockk<MetadataExtractor>()
    private val batchExtractor = BatchMetadataExtractor(mockMetadataExtractor, concurrency = 2)

    @Test
    fun `extractBatch should process all files`() = runTest {
        val files = listOf(
            PDFFileInfo("/test/file1.pdf", "file1.pdf", 100L, Clock.System.now()),
            PDFFileInfo("/test/file2.pdf", "file2.pdf", 200L, Clock.System.now()),
            PDFFileInfo("/test/file3.pdf", "file3.pdf", 300L, Clock.System.now())
        )

        val expectedMetadata = files.map { file ->
            PDFMetadata(
                id = file.path.hashCode().toString(),
                path = file.path,
                fileName = file.fileName,
                fileSize = file.fileSize,
                pageCount = 1,
                createdDate = null,
                modifiedDate = null,
                title = null,
                author = null,
                subject = null,
                creator = null,
                producer = null,
                keywords = emptyList(),
                pdfVersion = "1.4",
                customProperties = emptyMap(),
                contentHash = "test-hash",
                isEncrypted = false,
                isSignedPdf = false,
                indexedAt = Clock.System.now()
            )
        }

        files.forEachIndexed { index, file ->
            coEvery { mockMetadataExtractor.extractMetadata(file) } returns ExtractionResult(expectedMetadata[index], null)
        }

        val results = batchExtractor.extractBatch(files)

        assertEquals(3, results.size)
        assertEquals(expectedMetadata.map { it.path }, results.map { it.metadata.path })
    }

    @Test
    fun `extractBatch should handle extraction failures gracefully`() = runTest {
        val files = listOf(
            PDFFileInfo("/test/valid.pdf", "valid.pdf", 100L, Clock.System.now()),
            PDFFileInfo("/test/invalid.pdf", "invalid.pdf", 200L, Clock.System.now())
        )

        val validMetadata = PDFMetadata(
            id = "valid-id",
            path = "/test/valid.pdf",
            fileName = "valid.pdf",
            fileSize = 100L,
            pageCount = 1,
            createdDate = null,
            modifiedDate = null,
            title = null,
            author = null,
            subject = null,
            creator = null,
            producer = null,
            keywords = emptyList(),
            pdfVersion = "1.4",
            customProperties = emptyMap(),
            contentHash = "test-hash",
            isEncrypted = false,
            isSignedPdf = false,
            indexedAt = Clock.System.now()
        )

        coEvery { mockMetadataExtractor.extractMetadata(files[0]) } returns ExtractionResult(validMetadata, null)
        coEvery { mockMetadataExtractor.extractMetadata(files[1]) } returns null

        val results = batchExtractor.extractBatch(files)

        assertEquals(1, results.size)
        assertEquals("/test/valid.pdf", results[0].metadata.path)
    }

    @Test
    fun `extractWithProgress should call progress callback`() = runTest {
        val files = listOf(
            PDFFileInfo("/test/file1.pdf", "file1.pdf", 100L, Clock.System.now()),
            PDFFileInfo("/test/file2.pdf", "file2.pdf", 200L, Clock.System.now())
        )

        val progressCalls = mutableListOf<Pair<Int, Int>>()

        files.forEach { file ->
            coEvery { mockMetadataExtractor.extractMetadata(file) } returns null
        }

        val results = batchExtractor.extractWithProgress(files) { current, total ->
            progressCalls.add(current to total)
        }

        assertTrue(results.isEmpty()) // All extractions return null
        assertEquals(2, progressCalls.size)
        assertTrue(progressCalls.all { it.second == 2 }) // Total should always be 2
        assertEquals(listOf(1, 2), progressCalls.map { it.first }.sorted()) // Should have called with 1 and 2
    }
}