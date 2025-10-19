package com.example.scanning

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

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
}