package com.example.metadata

data class ExtractionResult(
    val metadata: PDFMetadata,
    val textContent: String?
)
