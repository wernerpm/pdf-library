package com.example.scanning

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class PDFFileInfo(
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Instant?,
    val discovered: Instant = Clock.System.now()
)