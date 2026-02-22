package com.example.metadata

import kotlin.time.Clock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentHashGeneratorTest {

    private val generator = ContentHashGenerator()

    @Test
    fun `generateHash should produce consistent hashes for same content`() {
        val content = "This is test PDF content".toByteArray()

        val hash1 = generator.generateHash(content)
        val hash2 = generator.generateHash(content)

        assertEquals(hash1, hash2)
    }

    @Test
    fun `generateHash should produce different hashes for different content`() {
        val content1 = "This is test PDF content 1".toByteArray()
        val content2 = "This is test PDF content 2".toByteArray()

        val hash1 = generator.generateHash(content1)
        val hash2 = generator.generateHash(content2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `generateHash should return valid base64 string`() {
        val content = "Test content".toByteArray()

        val hash = generator.generateHash(content)

        assertTrue(hash.isNotBlank())
        // Basic base64 validation - should not contain invalid characters
        assertTrue(hash.matches(Regex("^[A-Za-z0-9+/=]*$")))
    }

    @Test
    fun `generateMetadataHash should return valid base64 string`() {
        val metadata = PDFMetadata(
            id = "test-id",
            path = "/test/path.pdf",
            fileName = "test.pdf",
            fileSize = 1000L,
            pageCount = 5,
            createdDate = null,
            modifiedDate = null,
            title = "Test Title",
            author = "Test Author",
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

        val hash = generator.generateMetadataHash(metadata)

        assertTrue(hash.isNotBlank())
        assertTrue(hash.matches(Regex("^[A-Za-z0-9+/=]*$")))
    }

    @Test
    fun `generateMetadataHash should produce consistent hashes for same metadata`() {
        val metadata = PDFMetadata(
            id = "test-id",
            path = "/test/path.pdf",
            fileName = "test.pdf",
            fileSize = 1000L,
            pageCount = 5,
            createdDate = null,
            modifiedDate = null,
            title = "Test Title",
            author = "Test Author",
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

        val hash1 = generator.generateMetadataHash(metadata)
        val hash2 = generator.generateMetadataHash(metadata)

        assertEquals(hash1, hash2)
    }

    @Test
    fun `generateMetadataHash should produce different hashes for different metadata`() {
        val metadata1 = PDFMetadata(
            id = "test-id-1",
            path = "/test/path1.pdf",
            fileName = "test1.pdf",
            fileSize = 1000L,
            pageCount = 5,
            createdDate = null,
            modifiedDate = null,
            title = "Test Title 1",
            author = "Test Author 1",
            subject = null,
            creator = null,
            producer = null,
            keywords = emptyList(),
            pdfVersion = "1.4",
            customProperties = emptyMap(),
            contentHash = "test-hash-1",
            isEncrypted = false,
            isSignedPdf = false,
            indexedAt = Clock.System.now()
        )

        val metadata2 = PDFMetadata(
            id = "test-id-2",
            path = "/test/path2.pdf",
            fileName = "test2.pdf",
            fileSize = 2000L,
            pageCount = 10,
            createdDate = null,
            modifiedDate = null,
            title = "Test Title 2",
            author = "Test Author 2",
            subject = null,
            creator = null,
            producer = null,
            keywords = emptyList(),
            pdfVersion = "1.4",
            customProperties = emptyMap(),
            contentHash = "test-hash-2",
            isEncrypted = false,
            isSignedPdf = false,
            indexedAt = Clock.System.now()
        )

        val hash1 = generator.generateMetadataHash(metadata1)
        val hash2 = generator.generateMetadataHash(metadata2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `generateHash handles empty byte array`() {
        val hash = generator.generateHash(ByteArray(0))
        assertTrue(hash.isNotBlank())
        assertTrue(hash.matches(Regex("^[A-Za-z0-9+/=]*$")))
    }

    @Test
    fun `generateHash produces consistent hash for empty byte array`() {
        val hash1 = generator.generateHash(ByteArray(0))
        val hash2 = generator.generateHash(ByteArray(0))
        assertEquals(hash1, hash2)
    }

    @Test
    fun `generateMetadataHash with all null optional fields`() {
        val metadata = PDFMetadata(
            id = "test-id",
            path = "/test/path.pdf",
            fileName = "test.pdf",
            fileSize = 1000L,
            pageCount = 5,
            createdDate = null,
            modifiedDate = null,
            title = null,
            author = null,
            subject = null,
            creator = null,
            producer = null,
            keywords = emptyList(),
            pdfVersion = null,
            customProperties = emptyMap(),
            contentHash = null,
            indexedAt = Clock.System.now()
        )

        val hash = generator.generateMetadataHash(metadata)
        assertTrue(hash.isNotBlank())
    }

    @Test
    fun `generateMetadataHash differs when title changes`() {
        val base = PDFMetadata(
            id = "id", path = "/p.pdf", fileName = "p.pdf",
            fileSize = 100L, pageCount = 1,
            createdDate = null, modifiedDate = null,
            title = "Title A", author = "Author",
            subject = null, creator = null, producer = null,
            keywords = emptyList(), pdfVersion = null,
            customProperties = emptyMap(), contentHash = null,
            indexedAt = Clock.System.now()
        )
        val modified = base.copy(title = "Title B")

        assertNotEquals(
            generator.generateMetadataHash(base),
            generator.generateMetadataHash(modified)
        )
    }

    @Test
    fun `generateMetadataHash differs when author changes`() {
        val base = PDFMetadata(
            id = "id", path = "/p.pdf", fileName = "p.pdf",
            fileSize = 100L, pageCount = 1,
            createdDate = null, modifiedDate = null,
            title = "Title", author = "Author A",
            subject = null, creator = null, producer = null,
            keywords = emptyList(), pdfVersion = null,
            customProperties = emptyMap(), contentHash = null,
            indexedAt = Clock.System.now()
        )
        val modified = base.copy(author = "Author B")

        assertNotEquals(
            generator.generateMetadataHash(base),
            generator.generateMetadataHash(modified)
        )
    }

    @Test
    fun `generateMetadataHash differs when pageCount changes`() {
        val base = PDFMetadata(
            id = "id", path = "/p.pdf", fileName = "p.pdf",
            fileSize = 100L, pageCount = 1,
            createdDate = null, modifiedDate = null,
            title = "Title", author = "Author",
            subject = null, creator = null, producer = null,
            keywords = emptyList(), pdfVersion = null,
            customProperties = emptyMap(), contentHash = null,
            indexedAt = Clock.System.now()
        )
        val modified = base.copy(pageCount = 99)

        assertNotEquals(
            generator.generateMetadataHash(base),
            generator.generateMetadataHash(modified)
        )
    }

    @Test
    fun `generateMetadataHash differs when fileSize changes`() {
        val base = PDFMetadata(
            id = "id", path = "/p.pdf", fileName = "p.pdf",
            fileSize = 100L, pageCount = 1,
            createdDate = null, modifiedDate = null,
            title = "Title", author = "Author",
            subject = null, creator = null, producer = null,
            keywords = emptyList(), pdfVersion = null,
            customProperties = emptyMap(), contentHash = null,
            indexedAt = Clock.System.now()
        )
        val modified = base.copy(fileSize = 999L)

        assertNotEquals(
            generator.generateMetadataHash(base),
            generator.generateMetadataHash(modified)
        )
    }
}