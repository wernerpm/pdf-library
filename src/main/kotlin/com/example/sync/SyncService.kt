package com.example.sync

import com.example.config.AppConfiguration
import com.example.metadata.BatchMetadataExtractor
import com.example.metadata.MetadataExtractor
import com.example.repository.MetadataRepository
import com.example.scanning.*
import com.example.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.measureTime

class SyncService(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration,
    private val repository: MetadataRepository
) {
    private val logger = LoggerFactory.getLogger(SyncService::class.java)
    private val scanner = PDFScanner(storageProvider, configuration, repository)
    private val metadataExtractor = MetadataExtractor(storageProvider, configuration.metadataStoragePath)
    private val batchExtractor = BatchMetadataExtractor(metadataExtractor)
    private val metadataStorage = com.example.storage.FileSystemStorage(configuration.metadataStoragePath)
    private val manifestManager = DiscoveryManifestManager(
        storageProvider = metadataStorage,
        manifestPath = "discovery-manifest.json"
    )

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

    /**
     * Phase 1: Fast discovery — filesystem walk, no PDF bytes read.
     * Returns the manifest with all discovered files.
     */
    suspend fun performDiscovery(): DiscoveryManifest {
        logger.info("Starting discovery phase for ${configuration.pdfScanPaths.size} scan path(s): ${configuration.pdfScanPaths}")
        val manifest = scanner.discoverFiles(
            onPartialManifest = { partial ->
                logger.info("Partial manifest: ${partial.files.size} files — saving to disk")
                manifestManager.save(partial)
            }
        )
        // Save the final deduped manifest (overwrites any partial saves)
        logger.info("Saving final manifest: ${manifest.files.size} PDFs across ${manifest.scanPaths.size} scan path(s)")
        manifestManager.save(manifest)
        logger.info("Discovery complete: ${manifest.files.size} PDFs found. Per-path breakdown: ${
            manifest.scanPaths.map { path -> "$path → ${manifest.files.count { it.path.startsWith(path) }}" }
        }")
        return manifest
    }

    /**
     * Phase 2: Extraction — iterate manifest, extract metadata for DISCOVERED entries,
     * persist to repository, update manifest status per file.
     */
    suspend fun performExtraction(manifest: DiscoveryManifest): SyncResult {
        val startTime = Clock.System.now()
        val pending = manifest.files.filter { it.status == FileStatus.DISCOVERED }

        if (pending.isEmpty()) {
            logger.info("No pending files to extract")
            return SyncResult(
                syncType = SyncType.FULL,
                filesScanned = manifest.files.size,
                filesDiscovered = manifest.files.size,
                metadataExtracted = 0,
                metadataStored = 0,
                metadataSkipped = manifest.files.count { it.status == FileStatus.EXTRACTED },
                duplicatesRemoved = 0,
                totalErrors = 0,
                scanDuration = Duration.ZERO,
                extractionDuration = Duration.ZERO,
                syncDuration = Duration.ZERO,
                errors = emptyList(),
                startTime = startTime,
                endTime = Clock.System.now()
            )
        }

        logger.info("Starting extraction phase: ${pending.size} files to process")
        var extracted = 0
        var failed = 0
        val errors = mutableListOf<SyncError>()
        val extractionDuration = measureTime {
            val extractedMetadata = batchExtractor.extractBatch(pending)

            // Persist successfully extracted metadata
            for (metadata in extractedMetadata) {
                try {
                    repository.savePDF(metadata)
                    manifestManager.updateFileStatus(metadata.path, FileStatus.EXTRACTED)
                    extracted++
                    logger.debug("Extracted and persisted: ${metadata.fileName}")
                } catch (e: Exception) {
                    manifestManager.updateFileStatus(metadata.path, FileStatus.FAILED)
                    failed++
                    errors.add(SyncError(metadata.path, e.message ?: "Persistence failed", Clock.System.now()))
                    logger.error("Failed to persist metadata for ${metadata.fileName}", e)
                }
            }

            // Mark files that failed extraction (not in extractedMetadata)
            val extractedPaths = extractedMetadata.map { it.path }.toSet()
            for (file in pending) {
                if (file.path !in extractedPaths) {
                    try {
                        manifestManager.updateFileStatus(file.path, FileStatus.FAILED)
                    } catch (e: Exception) {
                        logger.warn("Failed to update manifest status for ${file.path}", e)
                    }
                    failed++
                    errors.add(SyncError(file.path, "Metadata extraction failed", Clock.System.now()))
                }
            }
        }

        val endTime = Clock.System.now()
        logger.info("Extraction complete: $extracted extracted, $failed failed out of ${pending.size} pending")

        return SyncResult(
            syncType = SyncType.FULL,
            filesScanned = manifest.files.size,
            filesDiscovered = manifest.files.size,
            metadataExtracted = extracted,
            metadataStored = extracted,
            metadataSkipped = manifest.files.count { it.status == FileStatus.EXTRACTED },
            duplicatesRemoved = 0,
            totalErrors = errors.size,
            scanDuration = Duration.ZERO,
            extractionDuration = extractionDuration,
            syncDuration = endTime - startTime,
            errors = errors,
            startTime = startTime,
            endTime = endTime
        )
    }

    /**
     * Resume extraction from an existing manifest — only DISCOVERED entries get processed.
     */
    suspend fun resumeExtraction(): SyncResult {
        val manifest = manifestManager.load()
        if (manifest == null) {
            logger.warn("No manifest found, cannot resume extraction")
            val now = Clock.System.now()
            return SyncResult(
                syncType = SyncType.FULL,
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
                errors = listOf(SyncError("MANIFEST", "No manifest found to resume from", now)),
                startTime = now,
                endTime = now
            )
        }
        logger.info("Resuming extraction from manifest with ${manifest.files.size} total entries")
        return performExtraction(manifest)
    }

    /**
     * Full sync: discovery + extraction in sequence.
     */
    suspend fun performFullSync(): SyncResult {
        if (!syncStart()) {
            logger.warn("Full sync requested but sync is already in progress")
            return SyncResult.alreadyInProgress(SyncType.FULL)
        }

        logger.info("Starting full sync process...")
        val startTime = Clock.System.now()

        return try {
            // Phase 1: Discovery
            val manifest = performDiscovery()

            // Phase 2: Extraction
            val result = performExtraction(manifest)
            val duration = Clock.System.now() - startTime

            result.copy(
                syncType = SyncType.FULL,
                syncDuration = duration,
                startTime = startTime,
                endTime = Clock.System.now()
            ).also {
                logger.info("Full sync completed: ${it.summarize()}")
            }
        } catch (e: Exception) {
            logger.error("Full sync failed", e)
            SyncResult.failed(syncType = SyncType.FULL, startTime = startTime, error = e)
        } finally {
            syncFinish()
        }
    }

    /**
     * Incremental sync: discovery + diff against existing manifest + extract only pending.
     */
    suspend fun performIncrementalSync(): SyncResult {
        if (!syncStart()) {
            logger.warn("Incremental sync requested but sync is already in progress")
            return SyncResult.alreadyInProgress(SyncType.INCREMENTAL)
        }

        logger.info("Starting incremental sync process...")
        val startTime = Clock.System.now()

        return try {
            val existingManifest = manifestManager.load()

            // Phase 1: Discovery
            val newManifest = scanner.discoverFiles()

            // Diff against existing manifest
            val mergedManifest = if (existingManifest != null) {
                diffManifests(existingManifest, newManifest)
            } else {
                newManifest
            }

            // Save merged manifest
            manifestManager.save(mergedManifest)

            // Phase 2: Extract only DISCOVERED entries
            val result = performExtraction(mergedManifest)
            val duration = Clock.System.now() - startTime

            result.copy(
                syncType = SyncType.INCREMENTAL,
                syncDuration = duration,
                startTime = startTime,
                endTime = Clock.System.now()
            ).also {
                logger.info("Incremental sync completed: ${it.summarize()}")
            }
        } catch (e: Exception) {
            logger.error("Incremental sync failed", e)
            SyncResult.failed(syncType = SyncType.INCREMENTAL, startTime = startTime, error = e)
        } finally {
            syncFinish()
        }
    }

    /**
     * Diff new discovery against existing manifest:
     * - New files (path not in existing): DISCOVERED
     * - Changed files (size/mtime differ): reset to DISCOVERED
     * - Deleted files (in existing but not in new): removed + PDFMetadata deleted
     * - Unchanged EXTRACTED files: kept as EXTRACTED (skipped)
     */
    private suspend fun diffManifests(
        existing: DiscoveryManifest,
        new: DiscoveryManifest
    ): DiscoveryManifest {
        val existingByPath = existing.files.associateBy { it.path }
        val newByPath = new.files.associateBy { it.path }

        // Handle deleted files: in existing but not in new
        val deletedPaths = existingByPath.keys - newByPath.keys
        for (path in deletedPaths) {
            try {
                val id = path.hashCode().toString()
                repository.deletePDF(id)
                logger.debug("Removed metadata for deleted file: $path")
            } catch (e: Exception) {
                logger.warn("Failed to remove metadata for deleted file: $path", e)
            }
        }
        if (deletedPaths.isNotEmpty()) {
            logger.info("Removed ${deletedPaths.size} deleted files from manifest")
        }

        // Build merged file list
        val mergedFiles = newByPath.map { (path, newFile) ->
            val existingFile = existingByPath[path]
            when {
                // New file — not in existing manifest
                existingFile == null -> newFile.copy(status = FileStatus.DISCOVERED)
                // Changed file — size or mtime differ
                existingFile.fileSize != newFile.fileSize ||
                        existingFile.lastModified != newFile.lastModified ->
                    newFile.copy(status = FileStatus.DISCOVERED)
                // Unchanged file — keep existing status
                else -> newFile.copy(status = existingFile.status)
            }
        }

        val newCount = mergedFiles.count { existingByPath[it.path] == null }
        val changedCount = mergedFiles.count { file ->
            val existing2 = existingByPath[file.path]
            existing2 != null && file.status == FileStatus.DISCOVERED
        }
        if (newCount > 0 || changedCount > 0) {
            logger.info("Incremental diff: $newCount new, $changedCount changed, ${deletedPaths.size} deleted")
        }

        return DiscoveryManifest(
            lastDiscovery = new.lastDiscovery,
            scanPaths = new.scanPaths,
            files = mergedFiles
        )
    }

    fun performSyncWithProgress(): Flow<SyncProgress> = flow {
        emit(SyncProgress.Started(Clock.System.now()))

        try {
            emit(SyncProgress.ScanningStarted())

            // Phase 1: Discovery
            val manifest = performDiscovery()
            emit(SyncProgress.ScanningCompleted(manifest.files.size))

            // Phase 2: Extraction
            val pending = manifest.files.filter { it.status == FileStatus.DISCOVERED }
            emit(SyncProgress.ExtractionStarted(pending.size))

            val result = performExtraction(manifest)
            emit(SyncProgress.Completed(result.metadataExtracted, Clock.System.now()))

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
