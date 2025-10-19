package com.example.repository

import com.example.metadata.PDFMetadata

interface MetadataRepository {
    suspend fun getAllPDFs(): List<PDFMetadata>
    suspend fun getPDF(id: String): PDFMetadata?
    suspend fun savePDF(metadata: PDFMetadata)
    suspend fun deletePDF(id: String)
    suspend fun search(query: String): List<PDFMetadata>
    suspend fun searchByProperty(key: String, value: String): List<PDFMetadata>
    suspend fun searchByAuthor(author: String): List<PDFMetadata>
    suspend fun searchByTitle(title: String): List<PDFMetadata>
    suspend fun count(): Long
    suspend fun loadFromStorage()
    suspend fun persistToStorage()
    suspend fun clear()
}