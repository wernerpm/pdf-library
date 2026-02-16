package com.example.metadata

import kotlin.time.Clock
import kotlin.time.Instant
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.*

class SecurePDFHandler {

    private val logger = LoggerFactory.getLogger(SecurePDFHandler::class.java)

    fun canExtractMetadata(document: PDDocument): Boolean {
        return try {
            !document.isEncrypted || document.currentAccessPermission.canExtractContent()
        } catch (e: Exception) {
            false
        }
    }

    fun handleEncryptedPDF(pdfBytes: ByteArray, fileName: String, path: String, fileSize: Long): PDFMetadata? {
        return try {
            // Attempt to load with empty password
            val document = Loader.loadPDF(pdfBytes, "")
            try {
                if (canExtractMetadata(document)) {
                    // Extract what we can
                    extractBasicInfo(document, fileName, path, fileSize)
                } else null
            } finally {
                document.close()
            }
        } catch (e: Exception) {
            logger.debug("Could not handle encrypted PDF: $path", e)
            null
        }
    }

    fun extractBasicInfo(document: PDDocument, fileName: String, path: String, fileSize: Long): PDFMetadata? {
        return try {
            val pageCount = document.numberOfPages
            val isEncrypted = document.isEncrypted
            val version = document.version.toString()

            // Try to get basic document info if available
            val docInfo = document.documentInformation
            val title = docInfo?.title?.trim()?.takeIf { it.isNotBlank() }
            val author = docInfo?.author?.trim()?.takeIf { it.isNotBlank() }

            // Generate a simple ID based on path
            val id = path.hashCode().toString()

            PDFMetadata(
                id = id,
                path = path,
                fileName = fileName,
                fileSize = fileSize,
                pageCount = pageCount,
                createdDate = docInfo?.creationDate?.let {
                    Instant.fromEpochMilliseconds(it.timeInMillis)
                },
                modifiedDate = docInfo?.modificationDate?.let {
                    Instant.fromEpochMilliseconds(it.timeInMillis)
                },
                title = title,
                author = author,
                subject = null,
                creator = docInfo?.creator?.trim()?.takeIf { it.isNotBlank() },
                producer = docInfo?.producer?.trim()?.takeIf { it.isNotBlank() },
                keywords = emptyList(),
                pdfVersion = version,
                customProperties = emptyMap(),
                contentHash = null, // Cannot generate hash for encrypted content
                isEncrypted = isEncrypted,
                isSignedPdf = false, // Would need additional checks
                indexedAt = Clock.System.now()
            )
        } catch (e: Exception) {
            logger.warn("Failed to extract basic info from encrypted PDF: $path", e)
            null
        }
    }
}