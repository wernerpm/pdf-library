package com.example.repository

import com.example.metadata.PDFMetadata
import com.example.storage.StorageProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.jvm.JvmStatic

class JsonPersistenceManager(
    private val storageProvider: StorageProvider,
    private val metadataPath: String
) {

    companion object {
        @JvmStatic
        private val logger = org.slf4j.LoggerFactory.getLogger(JsonPersistenceManager::class.java)

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }

    suspend fun saveMetadata(metadata: PDFMetadata) {
        try {
            val jsonString = json.encodeToString(metadata)
            val filePath = getMetadataFilePath(metadata.id)

            // Ensure the metadata directory exists
            storageProvider.createDirectory(metadataPath)

            storageProvider.write(filePath, jsonString.toByteArray())
            logger.debug("Saved metadata for PDF: ${metadata.id}")
        } catch (e: Exception) {
            logger.error("Failed to save metadata for ${metadata.id}", e)
            throw e
        }
    }

    suspend fun loadMetadata(id: String): PDFMetadata? {
        return try {
            val filePath = getMetadataFilePath(id)
            if (!storageProvider.exists(filePath)) {
                logger.debug("No metadata file found for ID: $id")
                return null
            }

            val jsonBytes = storageProvider.read(filePath)
            val metadata = json.decodeFromString<PDFMetadata>(jsonBytes.decodeToString())
            logger.debug("Loaded metadata for PDF: $id")
            metadata
        } catch (e: Exception) {
            logger.error("Failed to load metadata for $id", e)
            null
        }
    }

    suspend fun loadAllMetadata(): List<PDFMetadata> {
        return try {
            if (!storageProvider.exists(metadataPath)) {
                logger.info("Metadata directory does not exist: $metadataPath")
                return emptyList()
            }

            val metadataFiles = storageProvider.list(metadataPath)
                .filter { it.endsWith(".json") }

            val loadedMetadata = mutableListOf<PDFMetadata>()

            for (fileName in metadataFiles) {
                val id = fileName.removeSuffix(".json")
                loadMetadata(id)?.let { metadata ->
                    loadedMetadata.add(metadata)
                }
            }

            logger.info("Loaded ${loadedMetadata.size} metadata records from storage")
            loadedMetadata
        } catch (e: Exception) {
            logger.error("Failed to load metadata from storage", e)
            emptyList()
        }
    }

    suspend fun deleteMetadata(id: String) {
        try {
            val filePath = getMetadataFilePath(id)
            if (storageProvider.exists(filePath)) {
                storageProvider.delete(filePath)
                logger.debug("Deleted metadata file for PDF: $id")
            }
        } catch (e: Exception) {
            logger.error("Failed to delete metadata for $id", e)
            throw e
        }
    }

    suspend fun getAllMetadataIds(): Set<String> {
        return try {
            if (!storageProvider.exists(metadataPath)) {
                return emptySet()
            }

            storageProvider.list(metadataPath)
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
                .toSet()
        } catch (e: Exception) {
            logger.error("Failed to get metadata IDs", e)
            emptySet()
        }
    }

    private fun getMetadataFilePath(id: String): String {
        return "$metadataPath/$id.json"
    }
}