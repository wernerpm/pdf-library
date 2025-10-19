package com.example.repository

import com.example.config.AppConfiguration
import com.example.metadata.PDFMetadata
import com.example.storage.StorageProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmStatic

class InMemoryMetadataRepository(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration
) : MetadataRepository {

    companion object {
        @JvmStatic
        private val logger = org.slf4j.LoggerFactory.getLogger(InMemoryMetadataRepository::class.java)
    }

    private val cache = ConcurrentHashMap<String, PDFMetadata>()
    private val pathIndex = ConcurrentHashMap<String, String>() // path -> id
    private val authorIndex = ConcurrentHashMap<String, MutableSet<String>>() // author -> ids
    private val titleIndex = ConcurrentHashMap<String, MutableSet<String>>() // title -> ids
    private val mutex = Mutex()
    private val searchEngine = SearchEngine()
    private val persistenceManager = JsonPersistenceManager(storageProvider, configuration.metadataStoragePath)

    override suspend fun getAllPDFs(): List<PDFMetadata> {
        return cache.values.toList().sortedBy { it.fileName }
    }

    override suspend fun getPDF(id: String): PDFMetadata? {
        return cache[id]
    }

    override suspend fun savePDF(metadata: PDFMetadata) {
        mutex.withLock {
            // Remove old metadata from indices if updating
            cache[metadata.id]?.let { oldMetadata ->
                removeFromIndices(oldMetadata)
            }

            // Update cache
            cache[metadata.id] = metadata

            // Update indices
            updateIndices(metadata)

            // Persist to storage
            try {
                persistenceManager.saveMetadata(metadata)
            } catch (e: Exception) {
                logger.error("Failed to persist metadata for ${metadata.id}, rolling back memory changes", e)
                // Rollback cache changes
                cache.remove(metadata.id)
                removeFromIndices(metadata)
                throw e
            }

            logger.debug("Saved PDF metadata: ${metadata.id}")
        }
    }

    override suspend fun deletePDF(id: String) {
        mutex.withLock {
            val metadata = cache.remove(id)
            if (metadata != null) {
                removeFromIndices(metadata)
                try {
                    persistenceManager.deleteMetadata(id)
                } catch (e: Exception) {
                    logger.error("Failed to delete persisted metadata for $id", e)
                    // Re-add to cache since deletion failed
                    cache[id] = metadata
                    updateIndices(metadata)
                    throw e
                }
                logger.debug("Deleted PDF metadata: $id")
            }
        }
    }

    override suspend fun search(query: String): List<PDFMetadata> {
        return searchEngine.searchByQuery(cache.values, query)
    }

    override suspend fun searchByProperty(key: String, value: String): List<PDFMetadata> {
        return searchEngine.searchByProperty(cache.values, key, value)
    }

    override suspend fun searchByAuthor(author: String): List<PDFMetadata> {
        val normalizedAuthor = author.lowercase()
        val matchingIds = authorIndex.entries
            .filter { it.key.contains(normalizedAuthor, ignoreCase = true) }
            .flatMap { it.value }
            .toSet()

        return matchingIds.mapNotNull { cache[it] }
            .sortedBy { it.fileName }
    }

    override suspend fun searchByTitle(title: String): List<PDFMetadata> {
        val normalizedTitle = title.lowercase()
        val matchingIds = titleIndex.entries
            .filter { it.key.contains(normalizedTitle, ignoreCase = true) }
            .flatMap { it.value }
            .toSet()

        return matchingIds.mapNotNull { cache[it] }
            .sortedBy { it.fileName }
    }

    override suspend fun count(): Long {
        return cache.size.toLong()
    }

    override suspend fun loadFromStorage() {
        mutex.withLock {
            logger.info("Loading metadata from storage...")
            cache.clear()
            clearIndices()

            val loadedMetadata = persistenceManager.loadAllMetadata()

            for (metadata in loadedMetadata) {
                cache[metadata.id] = metadata
                updateIndices(metadata)
            }

            logger.info("Loaded ${cache.size} PDF metadata records into memory")
        }
    }

    override suspend fun persistToStorage() {
        logger.info("Persisting ${cache.size} metadata records to storage...")
        var successCount = 0
        var errorCount = 0

        for (metadata in cache.values) {
            try {
                persistenceManager.saveMetadata(metadata)
                successCount++
            } catch (e: Exception) {
                logger.error("Failed to persist metadata for ${metadata.id}", e)
                errorCount++
            }
        }

        logger.info("Persistence completed: $successCount successful, $errorCount errors")
        if (errorCount > 0) {
            throw RuntimeException("Failed to persist $errorCount metadata records")
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            cache.clear()
            clearIndices()
            logger.info("Cleared all metadata from memory")
        }
    }

    private fun updateIndices(metadata: PDFMetadata) {
        // Update path index
        pathIndex[metadata.path] = metadata.id

        // Update author index
        metadata.author?.let { author ->
            val normalizedAuthor = author.lowercase()
            authorIndex.computeIfAbsent(normalizedAuthor) { ConcurrentHashMap.newKeySet() }
                .add(metadata.id)
        }

        // Update title index
        metadata.title?.let { title ->
            val normalizedTitle = title.lowercase()
            titleIndex.computeIfAbsent(normalizedTitle) { ConcurrentHashMap.newKeySet() }
                .add(metadata.id)
        }
    }

    private fun removeFromIndices(metadata: PDFMetadata) {
        // Remove from path index
        pathIndex.remove(metadata.path)

        // Remove from author index
        metadata.author?.let { author ->
            val normalizedAuthor = author.lowercase()
            authorIndex[normalizedAuthor]?.remove(metadata.id)
            if (authorIndex[normalizedAuthor]?.isEmpty() == true) {
                authorIndex.remove(normalizedAuthor)
            }
        }

        // Remove from title index
        metadata.title?.let { title ->
            val normalizedTitle = title.lowercase()
            titleIndex[normalizedTitle]?.remove(metadata.id)
            if (titleIndex[normalizedTitle]?.isEmpty() == true) {
                titleIndex.remove(normalizedTitle)
            }
        }
    }

    private fun clearIndices() {
        pathIndex.clear()
        authorIndex.clear()
        titleIndex.clear()
    }

    fun getByPath(path: String): PDFMetadata? {
        val id = pathIndex[path]
        return id?.let { cache[it] }
    }

    fun getMemoryUsageInfo(): RepositoryMemoryInfo {
        return RepositoryMemoryInfo(
            totalRecords = cache.size,
            pathIndexSize = pathIndex.size,
            authorIndexSize = authorIndex.size,
            titleIndexSize = titleIndex.size
        )
    }
}

@Serializable
data class RepositoryMemoryInfo(
    val totalRecords: Int,
    val pathIndexSize: Int,
    val authorIndexSize: Int,
    val titleIndexSize: Int
)