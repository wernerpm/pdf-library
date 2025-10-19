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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration

class SyncService(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration,
    private val repository: MetadataRepository
) {
    private val logger = LoggerFactory.getLogger(SyncService::class.java)
    private val scanner = PDFScanner(storageProvider, configuration)
    private val metadataExtractor = MetadataExtractor(storageProvider)
    private val batchExtractor = BatchMetadataExtractor(metadataExtractor)

    suspend fun performFullSync(): SyncResult {
        logger.info("Starting full sync process...")
        val startTime = Clock.System.now()

        return try {
            // Step 1: Scan for PDF files
            logger.info("Scanning for PDF files...")
            val scanResult = scanner.scanForPDFs()
            logger.info("Scan completed: ${scanResult.discoveredFiles.size} files found")

            // Step 2: Extract metadata for discovered files
            logger.info("Extracting metadata from ${scanResult.discoveredFiles.size} files...")
            val extractedMetadata = batchExtractor.extractBatch(scanResult.discoveredFiles)
            logger.info("Metadata extraction completed: ${extractedMetadata.size} files processed")

            // Step 3: Store metadata in repository
            logger.info("Storing metadata in repository...")
            var storedCount = 0
            var skippedCount = 0
            val errors = mutableListOf<SyncError>()

            for (metadata in extractedMetadata) {
                try {
                    // Check if we already have this file (by path)
                    val existing = repository.getAllPDFs().find { it.path == metadata.path }
                    if (existing == null || existing.contentHash != metadata.contentHash) {
                        repository.savePDF(metadata)
                        storedCount++
                        logger.debug("Stored metadata for: ${metadata.fileName}")
                    } else {
                        skippedCount++
                        logger.debug("Skipped unchanged file: ${metadata.fileName}")
                    }
                } catch (e: Exception) {
                    val error = SyncError(
                        path = metadata.path,
                        message = "Failed to store metadata: ${e.message}",
                        timestamp = Clock.System.now()
                    )
                    errors.add(error)
                    logger.error("Failed to store metadata for ${metadata.path}", e)
                }
            }

            val duration = Clock.System.now() - startTime

            val result = SyncResult(
                syncType = SyncType.FULL,
                filesScanned = scanResult.totalFilesScanned,
                filesDiscovered = scanResult.discoveredFiles.size,
                metadataExtracted = extractedMetadata.size,
                metadataStored = storedCount,
                metadataSkipped = skippedCount,
                duplicatesRemoved = scanResult.duplicatesRemoved,
                totalErrors = scanResult.errors.size + errors.size,
                scanDuration = scanResult.scanDuration,
                extractionDuration = scanResult.extractionDuration ?: Duration.ZERO,
                syncDuration = duration,
                errors = errors,
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
        }
    }

    suspend fun performIncrementalSync(): SyncResult {
        logger.info("Starting incremental sync process...")
        val startTime = Clock.System.now()

        return try {
            // For incremental sync, we check modification times
            val scanResult = scanner.scanForPDFs()
            val existingFiles = repository.getAllPDFs().associateBy { it.path }

            // Find new or modified files
            val filesToProcess = scanResult.discoveredFiles.filter { fileInfo ->
                val existing = existingFiles[fileInfo.path]
                existing == null ||
                (fileInfo.lastModified != null && fileInfo.lastModified!! > existing.indexedAt)
            }

            logger.info("Found ${filesToProcess.size} new/modified files to process")

            if (filesToProcess.isEmpty()) {
                val duration = Clock.System.now() - startTime
                return SyncResult(
                    syncType = SyncType.INCREMENTAL,
                    filesScanned = scanResult.totalFilesScanned,
                    filesDiscovered = scanResult.discoveredFiles.size,
                    metadataExtracted = 0,
                    metadataStored = 0,
                    metadataSkipped = scanResult.discoveredFiles.size,
                    duplicatesRemoved = 0,
                    totalErrors = 0,
                    scanDuration = scanResult.scanDuration,
                    extractionDuration = Duration.ZERO,
                    syncDuration = duration,
                    errors = emptyList(),
                    startTime = startTime,
                    endTime = Clock.System.now()
                )
            }

            // Extract metadata for new/modified files
            val extractedMetadata = batchExtractor.extractBatch(filesToProcess)

            // Store metadata
            var storedCount = 0
            val errors = mutableListOf<SyncError>()

            for (metadata in extractedMetadata) {
                try {
                    repository.savePDF(metadata)
                    storedCount++
                    logger.debug("Updated metadata for: ${metadata.fileName}")
                } catch (e: Exception) {
                    val error = SyncError(
                        path = metadata.path,
                        message = "Failed to store metadata: ${e.message}",
                        timestamp = Clock.System.now()
                    )
                    errors.add(error)
                    logger.error("Failed to store metadata for ${metadata.path}", e)
                }
            }

            val duration = Clock.System.now() - startTime

            val result = SyncResult(
                syncType = SyncType.INCREMENTAL,
                filesScanned = scanResult.totalFilesScanned,
                filesDiscovered = scanResult.discoveredFiles.size,
                metadataExtracted = extractedMetadata.size,
                metadataStored = storedCount,
                metadataSkipped = scanResult.discoveredFiles.size - filesToProcess.size,
                duplicatesRemoved = scanResult.duplicatesRemoved,
                totalErrors = errors.size,
                scanDuration = scanResult.scanDuration,
                extractionDuration = scanResult.extractionDuration ?: Duration.ZERO,
                syncDuration = duration,
                errors = errors,
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
        }
    }

    fun performSyncWithProgress(): Flow<SyncProgress> = flow {
        emit(SyncProgress.Started(Clock.System.now()))

        try {
            emit(SyncProgress.ScanningStarted())

            val progressListener = object : ScanProgressListener {
                override fun onDirectoryStarted(path: String) {
                    // Can emit directory progress if needed
                }

                override fun onFileDiscovered(file: com.example.scanning.PDFFileInfo) {
                    // Can emit file discovery progress if needed
                }

                override fun onError(error: com.example.scanning.ScanError) {
                    // Can emit scan errors if needed
                }

                override fun onScanCompleted(result: ScanResult) {
                    // This will be handled after the scan completes
                }
            }

            val scanResult = scanner.scanForPDFs(progressListener)

            emit(SyncProgress.ExtractionStarted(scanResult.discoveredFiles.size))

            val extractedMetadata = batchExtractor.extractBatch(scanResult.discoveredFiles)

            emit(SyncProgress.ExtractionCompleted(extractedMetadata.size))
            emit(SyncProgress.StoringStarted(extractedMetadata.size))

            var storedCount = 0
            for (metadata in extractedMetadata) {
                try {
                    repository.savePDF(metadata)
                    storedCount++
                    emit(SyncProgress.MetadataStored(metadata.fileName, storedCount, extractedMetadata.size))
                } catch (e: Exception) {
                    emit(SyncProgress.Error("Failed to store ${metadata.fileName}: ${e.message}"))
                }
            }

            emit(SyncProgress.Completed(storedCount, Clock.System.now()))

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