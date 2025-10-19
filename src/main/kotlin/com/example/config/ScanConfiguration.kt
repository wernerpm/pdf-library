package com.example.config

import kotlinx.serialization.Serializable

@Serializable
data class ScanConfiguration(
    val recursive: Boolean = true,
    val maxDepth: Int = 50,
    val excludePatterns: List<String> = emptyList(),
    val fileExtensions: List<String> = listOf(".pdf"),
    val validatePdfHeaders: Boolean = true,
    val followSymlinks: Boolean = false,
    val maxFileSize: Long = 500_000_000L // 500MB
)