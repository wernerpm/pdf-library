package com.example.scanning

import com.example.storage.StorageProvider
import com.example.storage.StorageException

class PDFValidator(private val storageProvider: StorageProvider) {

    suspend fun isValidPDF(path: String): Boolean {
        return try {
            val header = storageProvider.read(path).take(8).toByteArray()
            header.decodeToString().startsWith("%PDF-")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getPDFVersion(path: String): String? {
        return try {
            val header = storageProvider.read(path).take(16).toByteArray()
            extractVersionFromHeader(header)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVersionFromHeader(header: ByteArray): String? {
        val headerString = header.decodeToString()
        val regex = Regex("%PDF-(\\d+\\.\\d+)")
        val matchResult = regex.find(headerString)
        return matchResult?.groupValues?.get(1)
    }
}