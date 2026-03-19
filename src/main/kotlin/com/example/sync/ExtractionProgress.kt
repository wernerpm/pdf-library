package com.example.sync

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ExtractionProgress(
    val phase: ExtractionPhase,
    val totalFiles: Int,
    val processedFiles: Int,
    val successfulFiles: Int,
    val failedFiles: Int,
    val currentFile: String?,
    val startTime: Instant?
) {
    val percentComplete: Double
        get() = if (totalFiles > 0) processedFiles.toDouble() / totalFiles * 100.0 else 0.0

    val pendingFiles: Int
        get() = totalFiles - processedFiles

    companion object {
        fun idle() = ExtractionProgress(ExtractionPhase.IDLE, 0, 0, 0, 0, null, null)
    }
}

@Serializable
enum class ExtractionPhase {
    IDLE, DISCOVERING, EXTRACTING, COMPLETED, FAILED
}
