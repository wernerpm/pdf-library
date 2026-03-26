package com.example.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfiguration(
    val pdfScanPaths: List<String>,
    val metadataStoragePath: String,
    val scanning: ScanConfiguration,
    val rpId: String = "localhost",
    val rpName: String = "PDF Library",
    val jwtIssuer: String = "localhost",
    // Optional S3 storage — when s3Bucket is set, metadata/thumbnails/credentials go to S3
    val s3Bucket: String? = null,
    val s3Region: String? = null,
    val s3KeyPrefix: String = "",
    val s3EndpointUrl: String? = null
)