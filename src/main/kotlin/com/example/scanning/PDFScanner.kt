package com.example.scanning

import com.example.config.AppConfiguration
import com.example.storage.StorageProvider
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.measureTime

class PDFScanner(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration
) {
    private val validator = PDFValidator(storageProvider)
    private val duplicateDetector = DuplicateDetector()

    suspend fun scanForPDFs(progressListener: ScanProgressListener? = null): ScanResult {
        val startTime = Clock.System.now()
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
                        if (configuration.scanning.recursive && currentDepth < configuration.scanning.maxDepth) {
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
}