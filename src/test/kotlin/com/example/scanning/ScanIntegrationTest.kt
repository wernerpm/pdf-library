package com.example.scanning

import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.storage.FileSystemStorage
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ScanIntegrationTest {

    @Test
    fun `integration test with file system storage`() = runTest {
        // Create a temporary directory for testing
        val tempDir = File.createTempFile("scan-test", "").apply {
            delete()
            mkdirs()
        }

        try {
            // Create a test PDF file
            val testPdfFile = File(tempDir, "test.pdf")
            testPdfFile.writeBytes("%PDF-1.4\n%âãÏÓ\n1 0 obj\n<<\n/Type /Catalog\n/Pages 2 0 R\n>>\nendobj\n%%EOF".toByteArray())

            // Create a non-PDF file to ensure filtering works
            val textFile = File(tempDir, "readme.txt")
            textFile.writeText("This is not a PDF file")

            val config = AppConfiguration(
                pdfScanPaths = listOf(tempDir.absolutePath),
                metadataStoragePath = tempDir.absolutePath,
                scanning = ScanConfiguration(
                    recursive = false,
                    validatePdfHeaders = true
                )
            )

            val storage = FileSystemStorage(tempDir.absolutePath)
            val scanner = PDFScanner(storage, config)
            val progressListener = ConsoleScanProgressListener()

            val result = scanner.scanForPDFs(progressListener)

            // Verify results
            assertTrue(result.discoveredFiles.isNotEmpty())
            assertTrue(result.discoveredFiles.any { it.fileName == "test.pdf" })
            assertTrue(result.errors.isEmpty())
            assertTrue(result.scanDuration.inWholeMilliseconds > 0)

        } finally {
            // Clean up
            tempDir.deleteRecursively()
        }
    }
}