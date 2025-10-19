# Step 1d: In-Memory Repository + JSON Persistence

## Overview
Implement the metadata repository system that provides fast in-memory operations with JSON file persistence, completing the core data layer.

## Prerequisites
- Step 1a completed: Configuration module + Storage abstraction layer
- Step 1b completed: PDF scanner + basic file discovery
- Step 1c completed: Metadata extraction with PDFBox

## Scope
- In-memory metadata repository for fast operations
- JSON persistence to filesystem
- Search and filtering capabilities
- Thread-safe operations
- Data consistency and recovery

## Implementation Details

### 1. Repository Abstraction
**MetadataRepository.kt:**
```kotlin
interface MetadataRepository {
    suspend fun getAllPDFs(): List<PDFMetadata>
    suspend fun getPDF(id: String): PDFMetadata?
    suspend fun savePDF(metadata: PDFMetadata)
    suspend fun deletePDF(id: String)
    suspend fun search(query: String): List<PDFMetadata>
    suspend fun searchByProperty(key: String, value: String): List<PDFMetadata>
    suspend fun searchByAuthor(author: String): List<PDFMetadata>
    suspend fun searchByTitle(title: String): List<PDFMetadata>
    suspend fun count(): Long
    suspend fun loadFromStorage()
    suspend fun persistToStorage()
    suspend fun clear()
}
```

### 2. In-Memory Implementation
**InMemoryMetadataRepository.kt:**
```kotlin
class InMemoryMetadataRepository(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration
) : MetadataRepository {

    private val cache = ConcurrentHashMap<String, PDFMetadata>()
    private val pathIndex = ConcurrentHashMap<String, String>() // path -> id
    private val authorIndex = ConcurrentHashMap<String, MutableSet<String>>() // author -> ids
    private val titleIndex = ConcurrentHashMap<String, MutableSet<String>>() // title -> ids
    private val mutex = Mutex()

    override suspend fun getAllPDFs(): List<PDFMetadata>
    override suspend fun getPDF(id: String): PDFMetadata?
    override suspend fun savePDF(metadata: PDFMetadata)
    override suspend fun deletePDF(id: String)

    private suspend fun updateIndices(metadata: PDFMetadata)
    private suspend fun removeFromIndices(metadata: PDFMetadata)
    private fun buildSearchIndices()
}
```

### 3. JSON Persistence Layer
**JsonPersistenceManager.kt:**
```kotlin
class JsonPersistenceManager(
    private val storageProvider: StorageProvider,
    private val metadataPath: String
) {

    suspend fun saveMetadata(metadata: PDFMetadata) {
        val json = Json.encodeToString(metadata)
        val filePath = getMetadataFilePath(metadata.id)
        storageProvider.write(filePath, json.toByteArray())
    }

    suspend fun loadMetadata(id: String): PDFMetadata? {
        return try {
            val filePath = getMetadataFilePath(id)
            if (!storageProvider.exists(filePath)) return null

            val jsonBytes = storageProvider.read(filePath)
            Json.decodeFromString<PDFMetadata>(jsonBytes.decodeToString())
        } catch (e: Exception) {
            logger.error("Failed to load metadata for $id", e)
            null
        }
    }

    suspend fun loadAllMetadata(): List<PDFMetadata> {
        return try {
            storageProvider.list(metadataPath)
                .filter { it.endsWith(".json") }
                .mapNotNull { fileName ->
                    val id = fileName.removeSuffix(".json")
                    loadMetadata(id)
                }
        } catch (e: Exception) {
            logger.error("Failed to load metadata", e)
            emptyList()
        }
    }

    suspend fun deleteMetadata(id: String) {
        val filePath = getMetadataFilePath(id)
        if (storageProvider.exists(filePath)) {
            storageProvider.delete(filePath)
        }
    }

    private fun getMetadataFilePath(id: String): String {
        return "$metadataPath/$id.json"
    }
}
```

### 4. Search Implementation
**SearchEngine.kt:**
```kotlin
class SearchEngine {

    fun searchByQuery(
        metadata: Collection<PDFMetadata>,
        query: String
    ): List<PDFMetadata> {
        val searchTerms = query.lowercase().split("\\s+".toRegex())

        return metadata.filter { pdf ->
            searchTerms.all { term ->
                matchesTerm(pdf, term)
            }
        }.sortedByDescending { pdf ->
            calculateRelevanceScore(pdf, searchTerms)
        }
    }

    fun searchByProperty(
        metadata: Collection<PDFMetadata>,
        key: String,
        value: String
    ): List<PDFMetadata> {
        return metadata.filter { pdf ->
            when (key.lowercase()) {
                "author" -> pdf.author?.contains(value, ignoreCase = true) == true
                "title" -> pdf.title?.contains(value, ignoreCase = true) == true
                "subject" -> pdf.subject?.contains(value, ignoreCase = true) == true
                "keywords" -> pdf.keywords.any { it.contains(value, ignoreCase = true) }
                else -> pdf.customProperties[key]?.contains(value, ignoreCase = true) == true
            }
        }
    }

    private fun matchesTerm(pdf: PDFMetadata, term: String): Boolean {
        return listOfNotNull(
            pdf.fileName,
            pdf.title,
            pdf.author,
            pdf.subject,
            pdf.creator,
            pdf.producer
        ).any { field ->
            field.contains(term, ignoreCase = true)
        } || pdf.keywords.any { keyword ->
            keyword.contains(term, ignoreCase = true)
        }
    }

    private fun calculateRelevanceScore(
        pdf: PDFMetadata,
        searchTerms: List<String>
    ): Double {
        var score = 0.0

        searchTerms.forEach { term ->
            // Title matches get highest score
            if (pdf.title?.contains(term, ignoreCase = true) == true) score += 3.0
            // Author matches get medium score
            if (pdf.author?.contains(term, ignoreCase = true) == true) score += 2.0
            // Filename matches get medium score
            if (pdf.fileName.contains(term, ignoreCase = true)) score += 2.0
            // Other fields get lower score
            if (pdf.subject?.contains(term, ignoreCase = true) == true) score += 1.0
            if (pdf.keywords.any { it.contains(term, ignoreCase = true) }) score += 1.0
        }

        return score
    }
}
```

### 5. Data Consistency and Recovery
**ConsistencyManager.kt:**
```kotlin
class ConsistencyManager(
    private val repository: InMemoryMetadataRepository,
    private val persistenceManager: JsonPersistenceManager
) {

    suspend fun performConsistencyCheck(): ConsistencyReport {
        val memoryIds = repository.getAllPDFs().map { it.id }.toSet()
        val persistedIds = persistenceManager.loadAllMetadata().map { it.id }.toSet()

        return ConsistencyReport(
            totalInMemory = memoryIds.size,
            totalPersisted = persistedIds.size,
            orphanedInMemory = memoryIds - persistedIds,
            orphanedOnDisk = persistedIds - memoryIds
        )
    }

    suspend fun repairInconsistencies(report: ConsistencyReport) {
        // Persist orphaned in-memory data
        report.orphanedInMemory.forEach { id ->
            repository.getPDF(id)?.let { metadata ->
                persistenceManager.saveMetadata(metadata)
            }
        }

        // Load orphaned disk data
        report.orphanedOnDisk.forEach { id ->
            persistenceManager.loadMetadata(id)?.let { metadata ->
                repository.savePDF(metadata)
            }
        }
    }
}

data class ConsistencyReport(
    val totalInMemory: Int,
    val totalPersisted: Int,
    val orphanedInMemory: Set<String>,
    val orphanedOnDisk: Set<String>
) {
    val isConsistent: Boolean = orphanedInMemory.isEmpty() && orphanedOnDisk.isEmpty()
}
```

### 6. Repository Manager
**RepositoryManager.kt:**
```kotlin
class RepositoryManager(
    private val repository: InMemoryMetadataRepository,
    private val consistencyManager: ConsistencyManager
) {

    suspend fun initialize() {
        logger.info("Loading metadata from storage...")
        repository.loadFromStorage()

        val count = repository.count()
        logger.info("Loaded $count PDF metadata records")

        // Perform consistency check
        val report = consistencyManager.performConsistencyCheck()
        if (!report.isConsistent) {
            logger.warn("Detected consistency issues: $report")
            consistencyManager.repairInconsistencies(report)
            logger.info("Consistency issues repaired")
        }
    }

    suspend fun shutdown() {
        logger.info("Persisting metadata to storage...")
        repository.persistToStorage()
        logger.info("Repository shutdown complete")
    }

    suspend fun backup(): BackupResult {
        val timestamp = Clock.System.now()
        val backupPath = "backup-${timestamp.epochSeconds}"

        return try {
            val allMetadata = repository.getAllPDFs()
            val backupData = BackupData(
                timestamp = timestamp,
                version = "1.0",
                metadata = allMetadata
            )

            val json = Json.encodeToString(backupData)
            // Save backup logic here

            BackupResult.Success(backupPath, allMetadata.size)
        } catch (e: Exception) {
            BackupResult.Error(e.message ?: "Unknown error")
        }
    }
}

@Serializable
data class BackupData(
    val timestamp: Instant,
    val version: String,
    val metadata: List<PDFMetadata>
)

sealed class BackupResult {
    data class Success(val path: String, val recordCount: Int) : BackupResult()
    data class Error(val message: String) : BackupResult()
}
```

### 7. Project Structure Addition
```
src/main/kotlin/com/example/
├── repository/
│   ├── MetadataRepository.kt
│   ├── InMemoryMetadataRepository.kt
│   ├── JsonPersistenceManager.kt
│   ├── SearchEngine.kt
│   ├── ConsistencyManager.kt
│   └── RepositoryManager.kt

src/test/kotlin/com/example/
├── repository/
│   ├── InMemoryMetadataRepositoryTest.kt
│   ├── JsonPersistenceManagerTest.kt
│   ├── SearchEngineTest.kt
│   └── ConsistencyManagerTest.kt
```

### 8. Complete Integration Example
**Application.kt - Full Pipeline:**
```kotlin
class Application {
    private lateinit var repositoryManager: RepositoryManager
    private lateinit var repository: MetadataRepository

    suspend fun start() {
        val config = configurationManager.loadConfiguration()
        val storage = FileSystemStorage(config.metadataStoragePath)

        // Initialize repository
        val persistenceManager = JsonPersistenceManager(storage, config.metadataStoragePath)
        val repository = InMemoryMetadataRepository(storage, config)
        val consistencyManager = ConsistencyManager(repository, persistenceManager)
        repositoryManager = RepositoryManager(repository, consistencyManager)

        repositoryManager.initialize()
        this.repository = repository
    }

    suspend fun fullScanAndIndex() {
        // Scan for PDFs
        val scanner = PDFScanner(storage, config)
        val scanResult = scanner.scanForPDFs()

        // Extract metadata
        val extractor = BatchMetadataExtractor(MetadataExtractor(storage))
        val metadata = extractor.extractBatch(scanResult.discoveredFiles)

        // Save to repository
        metadata.forEach { pdf ->
            repository.savePDF(pdf)
        }

        println("Indexed ${metadata.size} PDFs")
    }

    suspend fun searchPDFs(query: String): List<PDFMetadata> {
        return repository.search(query)
    }

    suspend fun shutdown() {
        repositoryManager.shutdown()
    }
}
```

### 9. Performance Features
- **Concurrent Operations**: Thread-safe access with ConcurrentHashMap
- **Indexing**: Pre-built indices for common search patterns
- **Lazy Loading**: Load metadata on-demand for large collections
- **Batch Operations**: Efficient bulk operations
- **Memory Management**: Configurable cache size limits

### 10. Monitoring and Metrics
**RepositoryMetrics.kt:**
```kotlin
data class RepositoryMetrics(
    val totalRecords: Long,
    val memoryUsage: Long,
    val averageSearchTime: Duration,
    val cacheHitRate: Double,
    val lastPersistTime: Instant?
)
```

## Testing Strategy
- Unit tests for all repository operations
- Performance tests with large datasets
- Concurrency tests for thread safety
- Persistence tests for data integrity
- Search relevance tests

## Deliverables
After this step, you'll have:
- Complete metadata repository system
- Fast in-memory operations with persistence
- Comprehensive search capabilities
- Data consistency and recovery features
- Ready for API layer integration

## Next Steps
With all Step 1 sub-steps complete, you can proceed to:
- **Step 2**: Thumbnail generation using PDFBox
- **Step 3**: REST API implementation with Ktor
- **Step 4**: Sync service with incremental updates