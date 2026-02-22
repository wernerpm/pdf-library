package com.example.scanning

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class PDFFileInfo(
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Instant?,
    val discovered: Instant = Clock.System.now(),
    val status: FileStatus = FileStatus.DISCOVERED
)
