package com.example.scanning

import com.example.config.AppConfiguration
import com.example.storage.StorageProvider
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.nio.file.Paths
import kotlin.time.measureTime

class PDFScanner(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration,
    private val repository: com.example.repository.MetadataRepository? = null
) {
    private val validator = PDFValidator(storageProvider)
    private val duplicateDetector = DuplicateDetector()
    private val metadataExtractor = com.example.metadata.MetadataExtractor(storageProvider)
    private val progressListeners = mutableListOf<ScanProgressListener>()
    private var discoveredFileCount = 0
    private val maxFiles get() = configuration.scanning.maxFiles
    private val visitedDirectories = mutableSetOf<String>()

    /**
     * Register a progress listener to receive scanning events
     */
    fun addListener(listener: ScanProgressListener) {
        progressListeners.add(listener)
    }

    /**
     * Remove a previously registered listener
     */
    fun removeListener(listener: ScanProgressListener) {
        progressListeners.remove(listener)
    }

    /**
     * Clear all registered listeners
     */
    fun clearListeners() {
        progressListeners.clear()
    }

    suspend fun scanForPDFs(progressListener: ScanProgressListener? = null): ScanResult {
        val startTime = Clock.System.now()
        discoveredFileCount = 0
        visitedDirectories.clear()
        val allFiles = mutableListOf<PDFFileInfo>()
        val errors = mutableListOf<ScanError>()
        var totalFilesScanned = 0
        var totalDirectoriesScanned = 0
        var invalidFilesSkipped = 0

        val scanDuration = measureTime {
            for (scanPath in configuration.pdfScanPaths) {
                try {
                    val result = scanPath(scanPath, progressListener)
                    allFiles.addAll(result.discoveredFiles)
                    totalFilesScanned += result.totalFilesScanned
                    totalDirectoriesScanned += result.totalDirectoriesScanned
                    invalidFilesSkipped += result.invalidFilesSkipped
                    errors.addAll(result.errors)
                } catch (e: Exception) {
                    val error = ScanError(scanPath, e.message ?: "Unknown error", Clock.System.now())
                    errors.add(error)
                    progressListener?.onError(error)
                }
            }
        }

        val filesBeforeDedup = allFiles.size
        val uniqueFiles = duplicateDetector.removeDuplicates(allFiles)
        val duplicatesRemoved = filesBeforeDedup - uniqueFiles.size

        val result = ScanResult(
            discoveredFiles = uniqueFiles,
            extractedMetadata = emptyList(),
            totalFilesScanned = totalFilesScanned,
            totalDirectoriesScanned = totalDirectoriesScanned,
            duplicatesRemoved = duplicatesRemoved,
            invalidFilesSkipped = invalidFilesSkipped,
            metadataExtractionErrors = 0,
            scanDuration = scanDuration,
            extractionDuration = null,
            errors = errors
        )

        progressListener?.onScanCompleted(result)
        return result
    }

    suspend fun scanPath(path: String, progressListener: ScanProgressListener? = null): ScanResult {
        val startTime = Clock.System.now()
        val discoveredFiles = mutableListOf<PDFFileInfo>()
        val errors = mutableListOf<ScanError>()
        var totalFilesScanned = 0
        var totalDirectoriesScanned = 0
        var invalidFilesSkipped = 0

        val scanDuration = measureTime {
            val result = walkDirectory(path, 0, progressListener)
            discoveredFiles.addAll(result.discoveredFiles)
            totalFilesScanned += result.totalFilesScanned
            totalDirectoriesScanned += result.totalDirectoriesScanned
            invalidFilesSkipped += result.invalidFilesSkipped
            errors.addAll(result.errors)
        }

        return ScanResult(
            discoveredFiles = discoveredFiles,
            totalFilesScanned = totalFilesScanned,
            totalDirectoriesScanned = totalDirectoriesScanned,
            duplicatesRemoved = 0,
            invalidFilesSkipped = invalidFilesSkipped,
            scanDuration = scanDuration,
            errors = errors
        )
    }

    private suspend fun walkDirectory(
        path: String,
        currentDepth: Int = 0,
        progressListener: ScanProgressListener? = null
    ): ScanResult {
        val discoveredFiles = mutableListOf<PDFFileInfo>()
        val errors = mutableListOf<ScanError>()
        var totalFilesScanned = 0
        var totalDirectoriesScanned = 1
        var invalidFilesSkipped = 0

        try {
            if (!storageProvider.exists(path)) {
                val error = ScanError(path, "Path does not exist", Clock.System.now())
                errors.add(error)
                return ScanResult(
                    discoveredFiles = emptyList(),
                    extractedMetadata = emptyList(),
                    totalFilesScanned = 0,
                    totalDirectoriesScanned = 0,
                    duplicatesRemoved = 0,
                    invalidFilesSkipped = 0,
                    metadataExtractionErrors = 0,
                    scanDuration = kotlin.time.Duration.ZERO,
                    extractionDuration = null,
                    errors = listOf(error)
                )
            }

            // Resolve to real path to detect symlink loops
            val realPath = try {
                Paths.get(path).toRealPath().toString()
            } catch (e: Exception) {
                path // Fall back to original path if resolution fails
            }
            if (!visitedDirectories.add(realPath)) {
                return ScanResult(
                    discoveredFiles = emptyList(),
                    extractedMetadata = emptyList(),
                    totalFilesScanned = 0,
                    totalDirectoriesScanned = 0,
                    duplicatesRemoved = 0,
                    invalidFilesSkipped = 0,
                    metadataExtractionErrors = 0,
                    scanDuration = kotlin.time.Duration.ZERO,
                    extractionDuration = null,
                    errors = emptyList()
                )
            }

            progressListener?.onDirectoryStarted(path)

            val items = storageProvider.list(path)

            for (item in items) {
                val fullPath = if (path.endsWith("/")) "$path$item" else "$path/$item"

                try {
                    if (shouldExclude(item)) {
                        continue
                    }

                    val metadata = storageProvider.getMetadata(fullPath)

                    if (metadata.isDirectory) {
                        if (configuration.scanning.recursive && currentDepth < configuration.scanning.maxDepth && discoveredFileCount < maxFiles) {
                            val subResult = walkDirectory(fullPath, currentDepth + 1, progressListener)
                            discoveredFiles.addAll(subResult.discoveredFiles)
                            totalFilesScanned += subResult.totalFilesScanned
                            totalDirectoriesScanned += subResult.totalDirectoriesScanned
                            invalidFilesSkipped += subResult.invalidFilesSkipped
                            errors.addAll(subResult.errors)
                        }
                    } else {
                        totalFilesScanned++

                        if (isPdfFile(item) && metadata.size <= configuration.scanning.maxFileSize) {
                            if (discoveredFileCount >= maxFiles) {
                                break
                            }
                            if (!configuration.scanning.validatePdfHeaders || validator.isValidPDF(fullPath)) {
                                val pdfFileInfo = PDFFileInfo(
                                    path = fullPath,
                                    fileName = item,
                                    fileSize = metadata.size,
                                    lastModified = metadata.modifiedAt?.let {
                                        kotlinx.datetime.Instant.fromEpochSeconds(it.epochSecond, it.nano)
                                    }
                                )
                                discoveredFiles.add(pdfFileInfo)
                                discoveredFileCount++
                                progressListener?.onFileDiscovered(pdfFileInfo)
                            } else {
                                invalidFilesSkipped++
                            }
                        }
                    }
                } catch (e: Exception) {
                    val error = ScanError(fullPath, e.message ?: "Unknown error", Clock.System.now())
                    errors.add(error)
                    progressListener?.onError(error)
                }
            }
        } catch (e: Exception) {
            val error = ScanError(path, e.message ?: "Unknown error", Clock.System.now())
            errors.add(error)
            progressListener?.onError(error)
        }

        return ScanResult(
            discoveredFiles = discoveredFiles,
            extractedMetadata = emptyList(),
            totalFilesScanned = totalFilesScanned,
            totalDirectoriesScanned = totalDirectoriesScanned,
            duplicatesRemoved = 0,
            invalidFilesSkipped = invalidFilesSkipped,
            metadataExtractionErrors = 0,
            scanDuration = kotlin.time.Duration.ZERO,
            extractionDuration = null,
            errors = errors
        )
    }

    private fun isPdfFile(fileName: String): Boolean {
        return configuration.scanning.fileExtensions.any { extension ->
            fileName.lowercase().endsWith(extension.lowercase())
        }
    }

    private fun shouldExclude(fileName: String): Boolean {
        return configuration.scanning.excludePatterns.any { pattern ->
            fileName.matches(globToRegex(pattern))
        }
    }

    private fun globToRegex(glob: String): Regex {
        val regex = buildString {
            append("^")
            for (char in glob) {
                when (char) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    '.' -> append("\\.")
                    '\\' -> append("\\\\")
                    '+' -> append("\\+")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    '[' -> append("\\[")
                    ']' -> append("\\]")
                    '{' -> append("\\{")
                    '}' -> append("\\}")
                    '^' -> append("\\^")
                    '$' -> append("\\$")
                    '|' -> append("\\|")
                    else -> append(char)
                }
            }
            append("$")
        }
        return Regex(regex)
    }

    /**
     * Scan for PDFs and persist metadata to repository as directories are processed.
     * This is the main controller method that combines scanning, extraction, and persistence.
     */
    suspend fun scanAndPersist(delegateListener: ScanProgressListener? = null): ScanResult {
        val repo = repository ?: throw IllegalStateException("Repository not configured for scanAndPersist")

        // Create a composite listener that notifies all registered listeners
        val compositeListener = createCompositeListener(delegateListener)

        // Create the repository progress listener that will handle extraction and persistence
        val repositoryListener = RepositoryProgressListener(repo, metadataExtractor, compositeListener)

        // Perform the scan with the repository listener
        val scanResult = scanForPDFs(repositoryListener)

        // Update the scan result with extraction and persistence metrics
        return scanResult.copy(
            extractedMetadata = repositoryListener.getExtractedMetadata(),
            metadataExtractionErrors = repositoryListener.extractionErrors,
            extractionDuration = scanResult.scanDuration // Approximate, as extraction happens during scan
        )
    }

    /**
     * Creates a composite listener that forwards events to all registered listeners
     * plus an optional delegate listener
     */
    private fun createCompositeListener(delegateListener: ScanProgressListener?): ScanProgressListener {
        return object : ScanProgressListener {
            override fun onDirectoryStarted(path: String) {
                progressListeners.forEach { it.onDirectoryStarted(path) }
                delegateListener?.onDirectoryStarted(path)
            }

            override fun onFileDiscovered(file: PDFFileInfo) {
                progressListeners.forEach { it.onFileDiscovered(file) }
                delegateListener?.onFileDiscovered(file)
            }

            override fun onError(error: ScanError) {
                progressListeners.forEach { it.onError(error) }
                delegateListener?.onError(error)
            }

            override fun onScanCompleted(result: ScanResult) {
                progressListeners.forEach { it.onScanCompleted(result) }
                delegateListener?.onScanCompleted(result)
            }
        }
    }
}