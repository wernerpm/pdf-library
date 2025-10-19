package com.example.storage

interface StorageProvider {
    suspend fun exists(path: String): Boolean
    suspend fun read(path: String): ByteArray
    suspend fun write(path: String, data: ByteArray)
    suspend fun list(path: String): List<String>
    suspend fun delete(path: String)
    suspend fun getMetadata(path: String): FileMetadata
    suspend fun createDirectory(path: String)
}

class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)