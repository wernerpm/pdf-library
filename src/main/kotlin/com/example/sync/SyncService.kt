package com.example.sync

import com.example.config.AppConfiguration
import com.example.metadata.BatchMetadataExtractor
import com.example.metadata.MetadataExtractor
import com.example.repository.MetadataRepository
import com.example.repository.TextContentStore
import com.example.scanning.*
import com.example.storage.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.measureTime

class SyncService(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration,
    private val repository: MetadataRepository,
    private val textContentStore: TextContentStore? = null
) {
    private val logger = LoggerFactory.getLogger(SyncService::class.java)
    private val scanner = PDFScanner(storageProvider, configuration, repository)
    private val metadataExtractor = MetadataExtractor(
        storageProvider,
        configuration.metadataStoragePath,
        extractTextContent = configuration.scanning.extractTextContent,
        maxTextContentChars = configuration.scanning.maxTextContentChars
    )
    private val batchExtractor = BatchMetadataExtractor(metadataExtractor, concurrency = 2)
    private val metadataStorage = com.example.storage.FileSystemStorage(configuration.metadataStoragePath)
    private val manifestManager = DiscoveryManifestManager(
        storageProvider = metadataStorage,
        manifestPath = "discovery-manifest.json"
    )

    @Volatile
    private var syncInProgress = false

    @Volatile
    private var _extractionProgress: ExtractionProgress = ExtractionProgress.idle()

    private var fileWatcher: FileWatcher? = null

    fun getExtractionProgress(): ExtractionProgress = _extractionProgress

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
        _extractionProgress = ExtractionProgress(
            phase = ExtractionPhase.DISCOVERING,
            totalFiles = 0, processedFiles = 0, successfulFiles = 0, failedFiles = 0,
            currentFile = null, startTime = Clock.System.now()
        )
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
        _extractionProgress = ExtractionProgress(
            phase = ExtractionPhase.EXTRACTING,
            totalFiles = pending.size, processedFiles = 0, successfulFiles = 0, failedFiles = 0,
            currentFile = pending.firstOrNull()?.fileName, startTime = Clock.System.now()
        )
        var extracted = 0
        var failed = 0
        val errors = mutableListOf<SyncError>()
        val extractionDuration = measureTime {
            // Process in small concurrent chunks and persist each chunk immediately.
            // This gives incremental progress and allows crash recovery via the manifest.
            pending.chunked(2).forEach { chunk ->
                val chunkResults = coroutineScope {
                    chunk.map { fileInfo ->
                        async {
                            try {
                                metadataExtractor.extractMetadata(fileInfo)
                            } catch (e: Exception) {
                                logger.error("Failed to extract ${fileInfo.fileName}", e)
                                null
                            }
                        }
                    }.awaitAll()
                }

                val extractedPaths = chunkResults.filterNotNull().map { it.metadata.path }.toSet()
                for (result in chunkResults.filterNotNull()) {
                    val metadata = result.metadata
                    try {
                        repository.savePDF(metadata)
                        manifestManager.updateFileStatus(metadata.path, FileStatus.EXTRACTED, "${metadata.id}.json")
                        result.textContent?.let { text ->
                            try { textContentStore?.save(metadata.id, text) }
                            catch (e: Exception) { logger.warn("Failed to save text content for ${metadata.id}", e) }
                        }
                        extracted++
                    } catch (e: Exception) {
                        manifestManager.updateFileStatus(metadata.path, FileStatus.FAILED)
                        failed++
                        errors.add(SyncError(metadata.path, e.message ?: "Persistence failed", Clock.System.now()))
                        logger.error("Failed to persist metadata for ${metadata.fileName}", e)
                    }
                }
                for (file in chunk) {
                    if (file.path !in extractedPaths) {
                        try { manifestManager.updateFileStatus(file.path, FileStatus.FAILED) }
                        catch (e: Exception) { logger.warn("Failed to update manifest status for ${file.path}", e) }
                        failed++
                        errors.add(SyncError(file.path, "Metadata extraction failed", Clock.System.now()))
                    }
                }
                _extractionProgress = _extractionProgress.copy(
                    processedFiles = extracted + failed,
                    successfulFiles = extracted,
                    failedFiles = failed,
                    currentFile = chunk.lastOrNull()?.fileName
                )
                if ((extracted + failed) % 20 == 0 || (extracted + failed) == pending.size) {
                    logger.info("Extraction progress: ${extracted + failed}/${pending.size} — $extracted ok, $failed failed")
                }
            }
        }
        _extractionProgress = _extractionProgress.copy(
            phase = ExtractionPhase.COMPLETED,
            processedFiles = extracted + failed,
            successfulFiles = extracted,
            failedFiles = failed,
            currentFile = null
        )

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

        // Backfill metadataPath for any EXTRACTED entries that predate this field.
        val needsBackfill = manifest.files.count { it.status == FileStatus.EXTRACTED && it.metadataPath == null }
        val backfilledManifest = if (needsBackfill > 0) {
            logger.info("Backfilling metadataPath for $needsBackfill already-extracted entries")
            val updated = manifest.copy(
                files = manifest.files.map { file ->
                    if (file.status == FileStatus.EXTRACTED && file.metadataPath == null)
                        file.copy(metadataPath = "${file.path.hashCode()}.json")
                    else file
                }
            )
            manifestManager.save(updated)
            updated
        } else manifest

        return performExtraction(backfilledManifest)
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
            _extractionProgress = _extractionProgress.copy(phase = ExtractionPhase.FAILED)
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
                textContentStore?.delete(id)
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

    fun startFileWatching(scope: CoroutineScope) {
        fileWatcher = FileWatcher(
            scanPaths = configuration.pdfScanPaths,
            debounceMs = configuration.scanning.watchDebounceMs,
            onChangesDetected = { changes -> handleFileChanges(changes) }
        )
        fileWatcher!!.start(scope)
        logger.info("File watching started")
    }

    fun stopFileWatching() {
        fileWatcher?.stop()
        fileWatcher = null
    }

    fun isFileWatchingEnabled(): Boolean = fileWatcher != null

    private suspend fun handleFileChanges(changes: List<FileChange>) {
        logger.info("FileWatcher: ${changes.size} change(s) detected")
        for (change in changes.filter { it.type == FileChangeType.DELETED }) {
            val id = change.path.hashCode().toString()
            try {
                repository.deletePDF(id)
                textContentStore?.delete(id)
                logger.debug("FileWatcher: removed metadata for deleted: ${change.path}")
            } catch (e: Exception) {
                logger.warn("FileWatcher: failed to remove metadata for ${change.path}", e)
            }
        }
        if (changes.any { it.type != FileChangeType.DELETED }) {
            logger.info("FileWatcher: triggering incremental sync for new/changed files")
            performIncrementalSync()
        }
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
