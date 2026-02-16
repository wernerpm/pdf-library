package com.example.metadata

import com.example.scanning.PDFFileInfo
import com.example.storage.StorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

class MetadataExtractor(
    private val storageProvider: StorageProvider,
    private val thumbnailStoragePath: String? = null
) {

    private val logger = LoggerFactory.getLogger(MetadataExtractor::class.java)
    private val documentInfoExtractor = DocumentInfoExtractor()
    private val customPropertiesExtractor = CustomPropertiesExtractor()
    private val contentHashGenerator = ContentHashGenerator()
    private val securePDFHandler = SecurePDFHandler()
    private val thumbnailGenerator = ThumbnailGenerator()

    suspend fun extractMetadata(fileInfo: PDFFileInfo): PDFMetadata? {
        return try {
            logger.debug("Extracting metadata from: ${fileInfo.path}")
            val pdfBytes = storageProvider.read(fileInfo.path)
            extractFromBytes(pdfBytes, fileInfo)
        } catch (e: Exception) {
            logger.error("Failed to extract metadata from ${fileInfo.path}", e)
            null
        }
    }

    private suspend fun extractFromBytes(
        pdfBytes: ByteArray,
        fileInfo: PDFFileInfo
    ): PDFMetadata? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(PDF_PARSE_TIMEOUT_MS) {
                try {
                    val document = Loader.loadPDF(pdfBytes)
                    try {
                        buildPDFMetadata(document, fileInfo, pdfBytes)
                    } finally {
                        document.close()
                    }
                } catch (e: Exception) {
                    logger.debug("Failed to load PDF, trying encrypted handler: ${fileInfo.path}", e)
                    // Try to handle as encrypted PDF
                    securePDFHandler.handleEncryptedPDF(
                        pdfBytes,
                        fileInfo.fileName,
                        fileInfo.path,
                        fileInfo.fileSize
                    )
                }
            } ?: run {
                logger.warn("PDF parsing timed out after ${PDF_PARSE_TIMEOUT_MS}ms for ${fileInfo.path}")
                null
            }
        }
    }

    companion object {
        private const val PDF_PARSE_TIMEOUT_MS = 60_000L // 60 seconds
    }

    private fun buildPDFMetadata(
        document: PDDocument,
        fileInfo: PDFFileInfo,
        pdfBytes: ByteArray
    ): PDFMetadata {
        // Check if we can extract metadata from this document
        if (document.isEncrypted && !securePDFHandler.canExtractMetadata(document)) {
            return securePDFHandler.extractBasicInfo(document, fileInfo.fileName, fileInfo.path, fileInfo.fileSize)
                ?: throw IllegalStateException("Cannot extract metadata from encrypted PDF")
        }

        // Extract document information
        val docInfo = documentInfoExtractor.extract(document)

        // Extract custom properties
        val customProperties = customPropertiesExtractor.extract(document)

        // Generate content hash
        val contentHash = contentHashGenerator.generateHash(pdfBytes)

        // Parse keywords
        val keywords = parseKeywords(docInfo.keywords)

        // Generate ID based on path
        val id = fileInfo.path.hashCode().toString()

        // Get PDF version
        val pdfVersion = document.version.toString()

        // Check if document is signed (basic check)
        val isSignedPdf = try {
            document.signatureDictionaries.isNotEmpty()
        } catch (e: Exception) {
            false
        }

        // Generate and save thumbnail
        val thumbnailRelativePath = generateAndSaveThumbnail(document, id)

        return PDFMetadata(
            id = id,
            path = fileInfo.path,
            fileName = fileInfo.fileName,
            fileSize = fileInfo.fileSize,
            pageCount = document.numberOfPages,
            createdDate = docInfo.creationDate?.let {
                Instant.fromEpochMilliseconds(it.timeInMillis)
            },
            modifiedDate = docInfo.modificationDate?.let {
                Instant.fromEpochMilliseconds(it.timeInMillis)
            },
            title = docInfo.title,
            author = docInfo.author,
            subject = docInfo.subject,
            creator = docInfo.creator,
            producer = docInfo.producer,
            keywords = keywords,
            pdfVersion = pdfVersion,
            customProperties = customProperties,
            contentHash = contentHash,
            isEncrypted = document.isEncrypted,
            isSignedPdf = isSignedPdf,
            thumbnailPath = thumbnailRelativePath,
            indexedAt = Clock.System.now()
        )
    }

    private fun generateAndSaveThumbnail(document: PDDocument, id: String): String? {
        if (thumbnailStoragePath == null) return null

        return try {
            val pngBytes = thumbnailGenerator.generateThumbnail(document) ?: return null

            val thumbnailDir = Paths.get(thumbnailStoragePath, "thumbnails")
            Files.createDirectories(thumbnailDir)

            val thumbnailFile = thumbnailDir.resolve("$id.png")
            Files.write(thumbnailFile, pngBytes)

            "thumbnails/$id.png"
        } catch (e: Exception) {
            logger.warn("Failed to save thumbnail for PDF id=$id", e)
            null
        }
    }

    private fun parseKeywords(keywordString: String?): List<String> {
        if (keywordString.isNullOrBlank()) return emptyList()

        return keywordString
            .split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}