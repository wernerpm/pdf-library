package com.example.scanning

import kotlin.time.Clock
import kotlin.time.Instant

data class PDFFileInfo(
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Instant?,
    val discovered: Instant = Clock.System.now()
)