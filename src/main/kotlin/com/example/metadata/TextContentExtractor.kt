package com.example.metadata

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory

class TextContentExtractor {

    private val logger = LoggerFactory.getLogger(TextContentExtractor::class.java)

    fun extractText(document: PDDocument, maxChars: Int = 500_000): String? {
        return try {
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            if (text.isBlank()) null else text.take(maxChars)
        } catch (e: Exception) {
            logger.warn("Failed to extract text content from PDF", e)
            null
        }
    }
}
