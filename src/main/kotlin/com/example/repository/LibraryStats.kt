package com.example.repository

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class LibraryStats(
    val totalPdfs: Int,
    val totalPages: Int,
    val totalSizeBytes: Long,
    val averagePageCount: Double,
    val averageFileSizeBytes: Long,
    val oldestPdf: Instant?,
    val newestPdf: Instant?,
    val topAuthors: List<AuthorCount>,
    val encryptedCount: Int,
    val signedCount: Int,
    val withThumbnailCount: Int,
    val pdfVersionDistribution: Map<String, Int>,
    val computedAt: Instant
)

@Serializable
data class AuthorCount(val author: String, val count: Int)
