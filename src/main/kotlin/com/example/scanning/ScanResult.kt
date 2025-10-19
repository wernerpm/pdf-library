package com.example.scanning

import kotlinx.datetime.Instant
import kotlin.time.Duration

data class ScanResult(
    val discoveredFiles: List<PDFFileInfo>,
    val totalFilesScanned: Int,
    val totalDirectoriesScanned: Int,
    val duplicatesRemoved: Int,
    val invalidFilesSkipped: Int,
    val scanDuration: Duration,
    val errors: List<ScanError>
)

data class ScanError(
    val path: String,
    val error: String,
    val timestamp: Instant
)