package com.example.repository

import com.example.metadata.PDFMetadata
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmStatic

/**
 * In-memory caching implementation of MetadataRepository.
 * Delegates persistence to a backing repository while maintaining fast in-memory access.
 */
class InMemoryMetadataRepository(
    private val backingRepository: MetadataRepository,
    private val textContentStore: TextContentStore? = null
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

    override suspend fun getAllPDFs(): List<PDFMetadata> = mutex.withLock {
        cache.values.toList().sortedBy { it.fileName }
    }

    override suspend fun getPDF(id: String): PDFMetadata? = mutex.withLock {
        cache[id]
    }

    override suspend fun savePDF(metadata: PDFMetadata) {
        mutex.withLock {
            // Persist to backing storage first to avoid corrupt state on restart
            backingRepository.savePDF(metadata)

            // Only update cache after successful persistence
            cache[metadata.id]?.let { oldMetadata ->
                removeFromIndices(oldMetadata)
            }
            cache[metadata.id] = metadata
            updateIndices(metadata)

            logger.debug("Saved PDF metadata: ${metadata.id}")
        }
    }

    override suspend fun deletePDF(id: String) {
        mutex.withLock {
            val metadata = cache[id] ?: return@withLock

            // Delete from backing storage first
            backingRepository.deletePDF(id)

            // Only update cache after successful deletion
            cache.remove(id)
            removeFromIndices(metadata)

            logger.debug("Deleted PDF metadata: $id")
        }
    }

    override suspend fun search(query: String): List<PDFMetadata> = mutex.withLock {
        val textMatchIds = textContentStore?.searchContent(query) ?: emptySet()
        searchEngine.searchByQuery(cache.values, query, textMatchIds)
    }

    override suspend fun searchByProperty(key: String, value: String): List<PDFMetadata> = mutex.withLock {
        searchEngine.searchByProperty(cache.values, key, value)
    }

    override suspend fun searchByAuthor(author: String): List<PDFMetadata> = mutex.withLock {
        val normalizedAuthor = author.lowercase()
        val matchingIds = authorIndex.entries
            .filter { it.key.contains(normalizedAuthor, ignoreCase = true) }
            .flatMap { it.value }
            .toSet()

        matchingIds.mapNotNull { cache[it] }
            .sortedBy { it.fileName }
    }

    override suspend fun searchByTitle(title: String): List<PDFMetadata> = mutex.withLock {
        val normalizedTitle = title.lowercase()
        val matchingIds = titleIndex.entries
            .filter { it.key.contains(normalizedTitle, ignoreCase = true) }
            .flatMap { it.value }
            .toSet()

        matchingIds.mapNotNull { cache[it] }
            .sortedBy { it.fileName }
    }

    override suspend fun count(): Long = mutex.withLock {
        cache.size.toLong()
    }

    override suspend fun loadFromStorage() {
        mutex.withLock {
            logger.info("Loading metadata from backing storage...")
            cache.clear()
            clearIndices()

            val loadedMetadata = backingRepository.getAllPDFs()

            for (metadata in loadedMetadata) {
                cache[metadata.id] = metadata
                updateIndices(metadata)
            }

            logger.info("Loaded ${cache.size} PDF metadata records into memory")
        }
        textContentStore?.loadAll()
    }

    override suspend fun persistToStorage() = mutex.withLock {
        logger.info("Persisting ${cache.size} metadata records to backing storage...")
        var successCount = 0
        var errorCount = 0

        for (metadata in cache.values) {
            try {
                backingRepository.savePDF(metadata)
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

    suspend fun computeStats(): LibraryStats = mutex.withLock {
        val allPdfs = cache.values
        val authorCounts = allPdfs.mapNotNull { it.author }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(10)
            .map { AuthorCount(it.key, it.value) }
        LibraryStats(
            totalPdfs = allPdfs.size,
            totalPages = allPdfs.sumOf { it.pageCount },
            totalSizeBytes = allPdfs.sumOf { it.fileSize },
            averagePageCount = if (allPdfs.isNotEmpty()) allPdfs.map { it.pageCount }.average() else 0.0,
            averageFileSizeBytes = if (allPdfs.isNotEmpty()) allPdfs.sumOf { it.fileSize } / allPdfs.size else 0L,
            oldestPdf = allPdfs.mapNotNull { it.createdDate }.minOrNull(),
            newestPdf = allPdfs.mapNotNull { it.createdDate }.maxOrNull(),
            topAuthors = authorCounts,
            encryptedCount = allPdfs.count { it.isEncrypted },
            signedCount = allPdfs.count { it.isSignedPdf },
            withThumbnailCount = allPdfs.count { it.thumbnailPath != null },
            pdfVersionDistribution = allPdfs.mapNotNull { it.pdfVersion }.groupingBy { it }.eachCount(),
            computedAt = Clock.System.now()
        )
    }

    suspend fun getByPath(path: String): PDFMetadata? = mutex.withLock {
        val id = pathIndex[path]
        id?.let { cache[it] }
    }

    suspend fun getMemoryUsageInfo(): RepositoryMemoryInfo = mutex.withLock {
        RepositoryMemoryInfo(
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