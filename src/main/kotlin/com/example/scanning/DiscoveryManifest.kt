package com.example.scanning

import com.example.storage.StorageProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import org.slf4j.LoggerFactory

@Serializable
data class DiscoveryManifest(
    val lastDiscovery: Instant,
    val scanPaths: List<String>,
    val files: List<PDFFileInfo>
)

class DiscoveryManifestManager(
    private val storageProvider: StorageProvider,
    private val manifestPath: String
) {
    private val logger = LoggerFactory.getLogger(DiscoveryManifestManager::class.java)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun load(): DiscoveryManifest? {
        return try {
            if (!storageProvider.exists(manifestPath)) {
                logger.debug("No manifest found at: $manifestPath")
                return null
            }
            val bytes = storageProvider.read(manifestPath)
            val content = bytes.decodeToString()
            json.decodeFromString<DiscoveryManifest>(content)
        } catch (e: Exception) {
            logger.error("Failed to load manifest from: $manifestPath", e)
            null
        }
    }

    suspend fun save(manifest: DiscoveryManifest) {
        // NonCancellable ensures the write completes even if the parent coroutine is being
        // cancelled (e.g. on server shutdown after a long network-volume scan).
        withContext(NonCancellable) {
            try {
                val content = json.encodeToString(DiscoveryManifest.serializer(), manifest)
                storageProvider.write(manifestPath, content.encodeToByteArray())
                logger.debug("Manifest saved with ${manifest.files.size} entries")
            } catch (e: Exception) {
                logger.error("Failed to save manifest to: $manifestPath", e)
                throw e
            }
        }
    }

    suspend fun updateFileStatus(path: String, status: FileStatus, metadataPath: String? = null) {
        val manifest = load() ?: throw IllegalStateException("No manifest to update")
        val updatedFiles = manifest.files.map { file ->
            if (file.path == path) file.copy(status = status, metadataPath = metadataPath ?: file.metadataPath) else file
        }
        save(manifest.copy(files = updatedFiles))
    }
}
