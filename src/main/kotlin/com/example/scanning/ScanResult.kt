package com.example.scanning

import com.example.metadata.PDFMetadata
import kotlinx.datetime.Instant
import kotlin.time.Duration

data class ScanResult(
    val discoveredFiles: List<PDFFileInfo>,
    val extractedMetadata: List<PDFMetadata> = emptyList(),
    val totalFilesScanned: Int,
    val totalDirectoriesScanned: Int,
    val duplicatesRemoved: Int,
    val invalidFilesSkipped: Int,
    val metadataExtractionErrors: Int = 0,
    val scanDuration: Duration,
    val extractionDuration: Duration? = null,
    val errors: List<ScanError>
)

data class ScanError(
    val path: String,
    val error: String,
    val timestamp: Instant
)