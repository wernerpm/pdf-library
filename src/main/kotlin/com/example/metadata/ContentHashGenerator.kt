package com.example.metadata

import java.security.MessageDigest
import java.util.*

class ContentHashGenerator {

    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    fun generateHash(pdfBytes: ByteArray): String {
        val hashBytes = digest.clone().let { (it as MessageDigest).digest(pdfBytes) }
        return Base64.getEncoder().encodeToString(hashBytes)
    }

    fun generateMetadataHash(metadata: PDFMetadata): String {
        val content = "${metadata.title}-${metadata.author}-${metadata.pageCount}-${metadata.fileSize}"
        val hashBytes = digest.clone().let { (it as MessageDigest).digest(content.toByteArray()) }
        return Base64.getEncoder().encodeToString(hashBytes)
    }
}