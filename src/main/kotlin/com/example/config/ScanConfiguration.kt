package com.example.config

import kotlinx.serialization.Serializable

@Serializable
data class ScanConfiguration(
    val recursive: Boolean = true,
    val maxDepth: Int = 50,
    val excludePatterns: List<String> = emptyList(),
    val fileExtensions: List<String> = listOf(".pdf")
)