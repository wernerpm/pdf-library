package com.example.repository

import com.example.metadata.PDFMetadata
import com.example.storage.StorageProvider
import kotlin.jvm.JvmStatic

/**
 * JSON-based implementation of MetadataRepository.
 * Provides persistent storage using JSON files on disk.
 */
class JsonRepository(
    private val storageProvider: StorageProvider,
    private val metadataPath: String
) : MetadataRepository {

    companion object {
        @JvmStatic
        private val logger = org.slf4j.LoggerFactory.getLogger(JsonRepository::class.java)
    }

    private val persistenceManager = JsonPersistenceManager(storageProvider, metadataPath)

    override suspend fun getAllPDFs(): List<PDFMetadata> {
        return persistenceManager.loadAllMetadata()
    }

    override suspend fun getPDF(id: String): PDFMetadata? {
        return persistenceManager.loadMetadata(id)
    }

    override suspend fun savePDF(metadata: PDFMetadata) {
        persistenceManager.saveMetadata(metadata)
        logger.debug("Saved PDF metadata to JSON storage: ${metadata.id}")
    }

    override suspend fun deletePDF(id: String) {
        persistenceManager.deleteMetadata(id)
        logger.debug("Deleted PDF metadata from JSON storage: $id")
    }

    override suspend fun search(query: String): List<PDFMetadata> {
        // Load all metadata and perform search in memory
        val allMetadata = getAllPDFs()
        val normalizedQuery = query.lowercase()

        return allMetadata.filter { metadata ->
            metadata.fileName.lowercase().contains(normalizedQuery) ||
            metadata.title?.lowercase()?.contains(normalizedQuery) == true ||
            metadata.author?.lowercase()?.contains(normalizedQuery) == true ||
            metadata.subject?.lowercase()?.contains(normalizedQuery) == true ||
            metadata.keywords?.any { it.lowercase().contains(normalizedQuery) } == true
        }
    }

    override suspend fun searchByProperty(key: String, value: String): List<PDFMetadata> {
        val allMetadata = getAllPDFs()
        val normalizedValue = value.lowercase()

        return allMetadata.filter { metadata ->
            when (key.lowercase()) {
                "filename" -> metadata.fileName.lowercase().contains(normalizedValue)
                "title" -> metadata.title?.lowercase()?.contains(normalizedValue) == true
                "author" -> metadata.author?.lowercase()?.contains(normalizedValue) == true
                "subject" -> metadata.subject?.lowercase()?.contains(normalizedValue) == true
                "keywords" -> metadata.keywords?.any { it.lowercase().contains(normalizedValue) } == true
                else -> false
            }
        }
    }

    override suspend fun searchByAuthor(author: String): List<PDFMetadata> {
        return searchByProperty("author", author)
    }

    override suspend fun searchByTitle(title: String): List<PDFMetadata> {
        return searchByProperty("title", title)
    }

    override suspend fun count(): Long {
        return persistenceManager.getAllMetadataIds().size.toLong()
    }

    override suspend fun loadFromStorage() {
        // This is a no-op for JsonRepository since data is always loaded from storage
        logger.debug("loadFromStorage called on JsonRepository (no-op)")
    }

    override suspend fun persistToStorage() {
        // This is a no-op for JsonRepository since data is always persisted
        logger.debug("persistToStorage called on JsonRepository (no-op)")
    }

    override suspend fun clear() {
        val allIds = persistenceManager.getAllMetadataIds()
        for (id in allIds) {
            try {
                persistenceManager.deleteMetadata(id)
            } catch (e: Exception) {
                logger.error("Failed to delete metadata during clear: $id", e)
            }
        }
        logger.info("Cleared all metadata from JSON storage")
    }

    /**
     * Get all metadata IDs currently stored
     */
    suspend fun getAllMetadataIds(): Set<String> {
        return persistenceManager.getAllMetadataIds()
    }
}
