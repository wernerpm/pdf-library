package com.example.metadata

import com.example.scanning.PDFFileInfo
import com.example.storage.FileSystemStorage
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import org.slf4j.LoggerFactory

/**
 * Example demonstrating how to use the metadata extraction system
 */
object MetadataExtractionExample {

    private val logger = LoggerFactory.getLogger(MetadataExtractionExample::class.java)

    fun demonstrateMetadataExtraction() = runBlocking {
        logger.info("Demonstrating PDF metadata extraction")

        // Setup storage provider
        val storage = FileSystemStorage("/path/to/pdfs")

        // Create metadata extractor
        val extractor = MetadataExtractor(storage)
        val batchExtractor = BatchMetadataExtractor(extractor, concurrency = 4)

        // Example PDF file info (would come from PDF scanner in real usage)
        val exampleFiles = listOf(
            PDFFileInfo(
                path = "/path/to/document1.pdf",
                fileName = "document1.pdf",
                fileSize = 1024000L,
                lastModified = Clock.System.now()
            ),
            PDFFileInfo(
                path = "/path/to/document2.pdf",
                fileName = "document2.pdf",
                fileSize = 2048000L,
                lastModified = Clock.System.now()
            )
        )

        try {
            // Extract metadata from single file
            logger.info("Extracting metadata from single file...")
            val singleMetadata = extractor.extractMetadata(exampleFiles[0])
            singleMetadata?.let { metadata ->
                logger.info("Successfully extracted metadata:")
                logger.info("  Title: ${metadata.title}")
                logger.info("  Author: ${metadata.author}")
                logger.info("  Pages: ${metadata.pageCount}")
                logger.info("  Size: ${metadata.fileSize} bytes")
                logger.info("  Encrypted: ${metadata.isEncrypted}")
                logger.info("  Custom Properties: ${metadata.customProperties}")
            }

            // Extract metadata from multiple files with progress tracking
            logger.info("Extracting metadata from multiple files...")
            val batchMetadata = batchExtractor.extractWithProgress(exampleFiles) { current, total ->
                logger.info("Progress: $current/$total files processed")
            }

            logger.info("Successfully extracted metadata from ${batchMetadata.size} files")

            // Print summary
            batchMetadata.forEach { metadata ->
                logger.info("File: ${metadata.fileName}")
                logger.info("  Path: ${metadata.path}")
                logger.info("  Title: ${metadata.title ?: "No title"}")
                logger.info("  Author: ${metadata.author ?: "No author"}")
                logger.info("  Pages: ${metadata.pageCount}")
                logger.info("  Keywords: ${metadata.keywords.joinToString(", ")}")
                logger.info("  Content Hash: ${metadata.contentHash}")
                logger.info("")
            }

        } catch (e: Exception) {
            logger.error("Error during metadata extraction", e)
        }
    }
}