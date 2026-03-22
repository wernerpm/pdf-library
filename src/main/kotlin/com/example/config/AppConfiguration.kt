package com.example.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfiguration(
    val pdfScanPaths: List<String>,
    val metadataStoragePath: String,
    val scanning: ScanConfiguration,
    val rpId: String = "localhost",
    val rpName: String = "PDF Library",
    val jwtIssuer: String = "localhost"
)