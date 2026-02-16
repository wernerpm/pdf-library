package com.example.sync

import com.example.config.AppConfiguration
import com.example.metadata.BatchMetadataExtractor
import com.example.metadata.MetadataExtractor
import com.example.repository.MetadataRepository
import com.example.scanning.PDFScanner
import com.example.scanning.ScanProgressListener
import com.example.scanning.ScanResult
import com.example.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration

class SyncService(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration,
    private val repository: MetadataRepository
) {
    private val logger = LoggerFactory.getLogger(SyncService::class.java)
    private val scanner = PDFScanner(storageProvider, configuration, repository)

    @Volatile
    private var syncInProgress = false

    /**
     * Register a progress listener to receive scanning events
     */
    fun addProgressListener(listener: ScanProgressListener) {
        scanner.addListener(listener)
    }

    /**
     * Remove a previously registered listener
     */
    fun removeProgressListener(listener: ScanProgressListener) {
        scanner.removeListener(listener)
    }

    /**
     * Clear all registered listeners
     */
    fun clearProgressListeners() {
        scanner.clearListeners()
    }

    /**
     * Check if a sync operation is currently in progress
     */
    fun isSyncInProgress(): Boolean = syncInProgress

    /**
     * Mark sync as started. Returns false if sync is already in progress.
     */
    private fun syncStart(): Boolean {
        synchronized(this) {
            if (syncInProgress) {
                return false
            }
            syncInProgress = true
            return true
        }
    }

    /**
     * Mark sync as finished
     */
    private fun syncFinish() {
        synchronized(this) {
            syncInProgress = false
        }
    }

    suspend fun performFullSync(): SyncResult {
        // Check if sync is already in progress
        if (!syncStart()) {
            logger.warn("Full sync requested but sync is already in progress")
            return SyncResult.alreadyInProgress(SyncType.FULL)
        }

        logger.info("Starting full sync process...")
        val startTime = Clock.System.now()

        return try {
            // Scanner now handles scanning, extraction, and persistence all in one
            logger.info("Scanning for PDFs and extracting metadata...")
            val scanResult = scanner.scanAndPersist()

            logger.info("Full sync completed: ${scanResult.discoveredFiles.size} files found, " +
                    "${scanResult.extractedMetadata.size} metadata extracted and persisted")

            val duration = Clock.System.now() - startTime

            val result = SyncResult(
                syncType = SyncType.FULL,
                filesScanned = scanResult.totalFilesScanned,
                filesDiscovered = scanResult.discoveredFiles.size,
                metadataExtracted = scanResult.extractedMetadata.size,
                metadataStored = scanResult.extractedMetadata.size, // All extracted metadata is persisted
                metadataSkipped = 0, // No skipping in full sync with new approach
                duplicatesRemoved = scanResult.duplicatesRemoved,
                totalErrors = scanResult.errors.size + scanResult.metadataExtractionErrors,
                scanDuration = scanResult.scanDuration,
                extractionDuration = scanResult.extractionDuration ?: Duration.ZERO,
                syncDuration = duration,
                errors = scanResult.errors.map { SyncError(it.path, it.error, it.timestamp) },
                startTime = startTime,
                endTime = Clock.System.now()
            )

            logger.info("Full sync completed: ${result.summarize()}")
            result

        } catch (e: Exception) {
            logger.error("Full sync failed", e)
            SyncResult.failed(
                syncType = SyncType.FULL,
                startTime = startTime,
                error = e
            )
        } finally {
            syncFinish()
        }
    }

    suspend fun performIncrementalSync(): SyncResult {
        // Check if sync is already in progress
        if (!syncStart()) {
            logger.warn("Incremental sync requested but sync is already in progress")
            return SyncResult.alreadyInProgress(SyncType.INCREMENTAL)
        }

        logger.info("Starting incremental sync process...")
        val startTime = Clock.System.now()

        return try {
            // For incremental sync, we use the same scanAndPersist approach
            // The repository layer can handle duplicate detection via content hashes
            logger.info("Scanning for PDFs and extracting metadata...")
            val scanResult = scanner.scanAndPersist()

            logger.info("Incremental sync completed: ${scanResult.discoveredFiles.size} files found, " +
                    "${scanResult.extractedMetadata.size} metadata extracted and persisted")

            val duration = Clock.System.now() - startTime

            val result = SyncResult(
                syncType = SyncType.INCREMENTAL,
                filesScanned = scanResult.totalFilesScanned,
                filesDiscovered = scanResult.discoveredFiles.size,
                metadataExtracted = scanResult.extractedMetadata.size,
                metadataStored = scanResult.extractedMetadata.size,
                metadataSkipped = 0,
                duplicatesRemoved = scanResult.duplicatesRemoved,
                totalErrors = scanResult.errors.size + scanResult.metadataExtractionErrors,
                scanDuration = scanResult.scanDuration,
                extractionDuration = scanResult.extractionDuration ?: Duration.ZERO,
                syncDuration = duration,
                errors = scanResult.errors.map { SyncError(it.path, it.error, it.timestamp) },
                startTime = startTime,
                endTime = Clock.System.now()
            )

            logger.info("Incremental sync completed: ${result.summarize()}")
            result

        } catch (e: Exception) {
            logger.error("Incremental sync failed", e)
            SyncResult.failed(
                syncType = SyncType.INCREMENTAL,
                startTime = startTime,
                error = e
            )
        } finally {
            syncFinish()
        }
    }

    fun performSyncWithProgress(): Flow<SyncProgress> = flow {
        emit(SyncProgress.Started(Clock.System.now()))

        try {
            emit(SyncProgress.ScanningStarted())

            val progressListener = object : com.example.scanning.ScanProgressListener {
                override suspend fun onDirectoryStarted(path: String) {
                    // Directory progress is handled internally by RepositoryProgressListener
                }

                override suspend fun onFileDiscovered(file: com.example.scanning.PDFFileInfo) {
                    // Could emit file discovery progress if needed in the future
                }

                override suspend fun onError(error: com.example.scanning.ScanError) {
                    // Could emit scan errors if needed
                }

                override suspend fun onScanCompleted(result: com.example.scanning.ScanResult) {
                    // Scan completion is handled below
                }
            }

            // Scanner now handles scanning, extraction, and persistence
            val scanResult = scanner.scanAndPersist(progressListener)

            emit(SyncProgress.Completed(scanResult.extractedMetadata.size, Clock.System.now()))

        } catch (e: Exception) {
            emit(SyncProgress.Failed(e.message ?: "Unknown error", Clock.System.now()))
        }
    }
}

@Serializable
data class SyncResult(
    val syncType: SyncType,
    val filesScanned: Int,
    val filesDiscovered: Int,
    val metadataExtracted: Int,
    val metadataStored: Int,
    val metadataSkipped: Int,
    val duplicatesRemoved: Int,
    val totalErrors: Int,
    val scanDuration: Duration,
    val extractionDuration: Duration,
    val syncDuration: Duration,
    val errors: List<SyncError>,
    val startTime: Instant,
    val endTime: Instant
) {
    fun summarize(): String {
        return "Scanned: $filesScanned, Discovered: $filesDiscovered, " +
                "Extracted: $metadataExtracted, Stored: $metadataStored, " +
                "Skipped: $metadataSkipped, Errors: $totalErrors, " +
                "Duration: $syncDuration"
    }

    companion object {
        fun failed(syncType: SyncType, startTime: Instant, error: Throwable): SyncResult {
            val endTime = Clock.System.now()
            return SyncResult(
                syncType = syncType,
                filesScanned = 0,
                filesDiscovered = 0,
                metadataExtracted = 0,
                metadataStored = 0,
                metadataSkipped = 0,
                duplicatesRemoved = 0,
                totalErrors = 1,
                scanDuration = Duration.ZERO,
                extractionDuration = Duration.ZERO,
                syncDuration = endTime - startTime,
                errors = listOf(SyncError("SYNC_FAILED", error.message ?: "Unknown error", endTime)),
                startTime = startTime,
                endTime = endTime
            )
        }

        fun alreadyInProgress(syncType: SyncType): SyncResult {
            val now = Clock.System.now()
            return SyncResult(
                syncType = syncType,
                filesScanned = 0,
                filesDiscovered = 0,
                metadataExtracted = 0,
                metadataStored = 0,
                metadataSkipped = 0,
                duplicatesRemoved = 0,
                totalErrors = 1,
                scanDuration = Duration.ZERO,
                extractionDuration = Duration.ZERO,
                syncDuration = Duration.ZERO,
                errors = listOf(SyncError("SYNC_IN_PROGRESS", "A sync operation is already in progress", now)),
                startTime = now,
                endTime = now
            )
        }
    }
}

@Serializable
enum class SyncType {
    FULL, INCREMENTAL
}

@Serializable
data class SyncError(
    val path: String,
    val message: String,
    val timestamp: Instant
)

sealed class SyncProgress {
    data class Started(val timestamp: Instant) : SyncProgress()
    data class ScanningStarted(val timestamp: Instant = Clock.System.now()) : SyncProgress()
    data class ScanningCompleted(val filesFound: Int) : SyncProgress()
    data class ExtractionStarted(val totalFiles: Int) : SyncProgress()
    data class ExtractionCompleted(val extractedCount: Int) : SyncProgress()
    data class StoringStarted(val totalToStore: Int) : SyncProgress()
    data class MetadataStored(val fileName: String, val current: Int, val total: Int) : SyncProgress()
    data class Completed(val totalStored: Int, val timestamp: Instant) : SyncProgress()
    data class Failed(val error: String, val timestamp: Instant) : SyncProgress()
    data class Error(val message: String) : SyncProgress()
}