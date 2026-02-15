package com.example.metadata

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.slf4j.LoggerFactory

class CustomPropertiesExtractor {

    private val logger = LoggerFactory.getLogger(CustomPropertiesExtractor::class.java)

    fun extract(document: PDDocument): Map<String, String> {
        val customProps = mutableMapOf<String, String>()

        try {
            // Extract XMP metadata if available
            val xmpMetadata = document.documentCatalog.metadata
            if (xmpMetadata != null) {
                customProps.putAll(extractXMPProperties(xmpMetadata))
            }

            // Extract custom document properties
            val docInfo = document.documentInformation
            docInfo.metadataKeys.forEach { key ->
                val value = docInfo.getCustomMetadataValue(key)
                if (value != null && !isStandardProperty(key)) {
                    customProps[key] = value.toString()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract custom properties", e)
        }

        return customProps
    }

    private fun extractXMPProperties(metadata: PDMetadata): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        try {
            metadata.createInputStream().use { stream ->
                val maxXmpSize = 10 * 1024 * 1024 // 10MB limit
                val bytes = stream.readNBytes(maxXmpSize)
                val xmpContent = bytes.toString(Charsets.UTF_8)

                if (xmpContent.isNotBlank()) {
                    properties["xmp_content_length"] = xmpContent.length.toString()
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed to extract XMP properties", e)
        }
        return properties
    }

    private fun isStandardProperty(key: String): Boolean {
        val standardProperties = setOf(
            "Title", "Author", "Subject", "Creator", "Producer",
            "Keywords", "CreationDate", "ModDate", "Trapped"
        )
        return standardProperties.contains(key)
    }
}