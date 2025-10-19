package com.example.metadata

import java.security.MessageDigest
import java.util.*

class ContentHashGenerator {

    fun generateHash(pdfBytes: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(pdfBytes)
            Base64.getEncoder().encodeToString(hashBytes)
        } catch (e: Exception) {
            // Fallback to simple hash
            pdfBytes.contentHashCode().toString()
        }
    }

    fun generateMetadataHash(metadata: PDFMetadata): String {
        val content = "${metadata.title}-${metadata.author}-${metadata.pageCount}-${metadata.fileSize}"
        return content.hashCode().toString()
    }
}