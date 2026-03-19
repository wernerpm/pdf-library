package com.example.repository

import com.example.storage.StorageProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class TextContentStore(private val storageProvider: StorageProvider) {

    private val logger = LoggerFactory.getLogger(TextContentStore::class.java)
    private val textIndex = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()
    private val textDir = "text-content"
    private var dirEnsured = false

    suspend fun save(id: String, textContent: String) {
        mutex.withLock {
            ensureDir()
            storageProvider.write("$textDir/$id.txt", textContent.encodeToByteArray())
            textIndex[id] = textContent
        }
    }

    suspend fun get(id: String): String? = mutex.withLock {
        textIndex[id]
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            textIndex.remove(id)
            val path = "$textDir/$id.txt"
            try {
                if (storageProvider.exists(path)) storageProvider.delete(path)
            } catch (e: Exception) {
                logger.warn("Failed to delete text content for id=$id", e)
            }
        }
    }

    suspend fun loadAll() {
        mutex.withLock {
            try {
                if (!storageProvider.exists(textDir)) return@withLock
                val files = storageProvider.list(textDir)
                var loaded = 0
                for (file in files) {
                    if (!file.endsWith(".txt")) continue
                    val id = file.removeSuffix(".txt")
                    try {
                        textIndex[id] = storageProvider.read("$textDir/$file").decodeToString()
                        loaded++
                    } catch (e: Exception) {
                        logger.warn("Failed to load text content for $file", e)
                    }
                }
                dirEnsured = true
                logger.info("Loaded $loaded text content entries")
            } catch (e: Exception) {
                logger.warn("Failed to load text content directory", e)
            }
        }
    }

    fun searchContent(query: String): Set<String> {
        val terms = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptySet()
        return textIndex.entries
            .filter { (_, text) ->
                val lower = text.lowercase()
                terms.all { term -> lower.contains(term) }
            }
            .map { it.key }
            .toSet()
    }

    fun count(): Int = textIndex.size

    private suspend fun ensureDir() {
        if (!dirEnsured) {
            try { storageProvider.createDirectory(textDir) } catch (_: Exception) {}
            dirEnsured = true
        }
    }
}
