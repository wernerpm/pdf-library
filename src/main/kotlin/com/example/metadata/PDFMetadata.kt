package com.example.metadata

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PDFMetadata(
    val id: String,
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val pageCount: Int,
    val createdDate: Instant?,
    val modifiedDate: Instant?,

    // Standard PDF metadata
    val title: String?,
    val author: String?,
    val subject: String?,
    val creator: String?,        // Application that created the PDF
    val producer: String?,       // PDF library used
    val keywords: List<String> = emptyList(),
    val pdfVersion: String?,

    // Custom metadata for extensibility
    val customProperties: Map<String, String> = emptyMap(),

    // System metadata
    val contentHash: String?,
    val isEncrypted: Boolean = false,
    val isSignedPdf: Boolean = false,
    val thumbnailPath: String? = null,
    val indexedAt: Instant = Clock.System.now()
)