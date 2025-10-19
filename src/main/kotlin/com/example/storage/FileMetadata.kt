package com.example.storage

import java.time.Instant

data class FileMetadata(
    val path: String,
    val size: Long,
    val createdAt: Instant?,
    val modifiedAt: Instant?,
    val isDirectory: Boolean
)