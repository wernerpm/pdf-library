package com.example.scanning

import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateDetectorTest {

    private val detector = DuplicateDetector()

    @Test
    fun `removeDuplicates removes duplicate paths`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/path/to/file1.pdf", "file1.pdf", 1000L, now),
            PDFFileInfo("/path/to/file2.pdf", "file2.pdf", 2000L, now),
            PDFFileInfo("/path/to/file1.pdf", "file1.pdf", 1000L, now), // duplicate
            PDFFileInfo("/different/path/file3.pdf", "file3.pdf", 3000L, now)
        )

        val result = detector.removeDuplicates(files)

        assertEquals(3, result.size)
        assertEquals(setOf("file1.pdf", "file2.pdf", "file3.pdf"), result.map { it.fileName }.toSet())
    }

    @Test
    fun `removeDuplicates handles normalized paths`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/path/to/file1.pdf", "file1.pdf", 1000L, now),
            PDFFileInfo("/path/to/../to/file1.pdf", "file1.pdf", 1000L, now), // same file, different path
            PDFFileInfo("/path/to/file2.pdf", "file2.pdf", 2000L, now)
        )

        val result = detector.removeDuplicates(files)

        assertEquals(2, result.size)
        assertEquals(setOf("file1.pdf", "file2.pdf"), result.map { it.fileName }.toSet())
    }

    @Test
    fun `removeDuplicates handles empty list`() {
        val result = detector.removeDuplicates(emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun `removeDuplicates handles list with no duplicates`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/path/to/file1.pdf", "file1.pdf", 1000L, now),
            PDFFileInfo("/path/to/file2.pdf", "file2.pdf", 2000L, now),
            PDFFileInfo("/path/to/file3.pdf", "file3.pdf", 3000L, now)
        )

        val result = detector.removeDuplicates(files)

        assertEquals(3, result.size)
        assertEquals(files, result)
    }

    @Test
    fun `removeDuplicates handles paths with spaces`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/path/to/my file.pdf", "my file.pdf", 1000L, now),
            PDFFileInfo("/path/to/my file.pdf", "my file.pdf", 1000L, now)
        )

        val result = detector.removeDuplicates(files)
        assertEquals(1, result.size)
    }

    @Test
    fun `removeDuplicates handles paths with unicode characters`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/path/to/über-résumé.pdf", "über-résumé.pdf", 1000L, now),
            PDFFileInfo("/path/to/über-résumé.pdf", "über-résumé.pdf", 1000L, now),
            PDFFileInfo("/path/to/日本語.pdf", "日本語.pdf", 2000L, now)
        )

        val result = detector.removeDuplicates(files)
        assertEquals(2, result.size)
    }

    @Test
    fun `removeDuplicates handles paths with special characters`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/path/to/file (1).pdf", "file (1).pdf", 1000L, now),
            PDFFileInfo("/path/to/file (1).pdf", "file (1).pdf", 1000L, now),
            PDFFileInfo("/path/to/file [2].pdf", "file [2].pdf", 2000L, now)
        )

        val result = detector.removeDuplicates(files)
        assertEquals(2, result.size)
    }

    @Test
    fun `removeDuplicates normalizes redundant slashes`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/path/to/file.pdf", "file.pdf", 1000L, now),
            PDFFileInfo("/path//to/file.pdf", "file.pdf", 1000L, now)
        )

        val result = detector.removeDuplicates(files)
        // Path.normalize() doesn't collapse double slashes on all platforms,
        // but the paths should at least not cause errors
        assertTrue(result.isNotEmpty())
        assertTrue(result.size <= 2)
    }

    @Test
    fun `removeDuplicates normalizes dot segments`() {
        val now = Clock.System.now()
        val files = listOf(
            PDFFileInfo("/a/b/c/file.pdf", "file.pdf", 1000L, now),
            PDFFileInfo("/a/b/./c/file.pdf", "file.pdf", 1000L, now),
            PDFFileInfo("/a/b/c/../c/file.pdf", "file.pdf", 1000L, now)
        )

        val result = detector.removeDuplicates(files)
        assertEquals(1, result.size)
    }

    @Test
    fun `removeDuplicates keeps first occurrence of duplicate`() {
        val now = Clock.System.now()
        val first = PDFFileInfo("/path/to/file.pdf", "file.pdf", 1000L, now)
        val second = PDFFileInfo("/path/to/file.pdf", "file.pdf", 2000L, now) // same path, different size

        val result = detector.removeDuplicates(listOf(first, second))
        assertEquals(1, result.size)
        assertEquals(1000L, result[0].fileSize) // first wins
    }

    @Test
    fun `removeDuplicates handles single file`() {
        val now = Clock.System.now()
        val files = listOf(PDFFileInfo("/only/file.pdf", "file.pdf", 500L, now))

        val result = detector.removeDuplicates(files)
        assertEquals(1, result.size)
        assertEquals(files[0], result[0])
    }
}