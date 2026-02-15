package com.example.scanning

import com.example.metadata.MetadataExtractor
import com.example.repository.MetadataRepository
import org.slf4j.LoggerFactory
import kotlin.jvm.JvmStatic

/**
 * Progress listener that extracts metadata and persists to repository
 * as directories are scanned. Flushes on directory completion.
 */
class RepositoryProgressListener(
    private val repository: MetadataRepository,
    private val metadataExtractor: MetadataExtractor,
    private val delegate: ScanProgressListener? = null
) : ScanProgressListener {

    companion object {
        @JvmStatic
        private val logger = LoggerFactory.getLogger(RepositoryProgressListener::class.java)
    }

    private var currentDirectory: String? = null
    private val currentDirectoryFiles = mutableListOf<PDFFileInfo>()
    private val allExtractedMetadata = mutableListOf<com.example.metadata.PDFMetadata>()

    var filesExtracted = 0
        private set
    var filesPersisted = 0
        private set
    var extractionErrors = 0
        private set
    var persistenceErrors = 0
        private set

    override suspend fun onDirectoryStarted(path: String) {
        // Flush the previous directory's files before starting a new one
        flushCurrentDirectory()

        currentDirectory = path
        currentDirectoryFiles.clear()

        delegate?.onDirectoryStarted(path)
    }

    override suspend fun onFileDiscovered(file: PDFFileInfo) {
        // Buffer files for the current directory
        currentDirectoryFiles.add(file)
        delegate?.onFileDiscovered(file)
    }

    override suspend fun onError(error: ScanError) {
        delegate?.onError(error)
    }

    override suspend fun onScanCompleted(result: ScanResult) {
        // Flush any remaining files when scan completes
        flushCurrentDirectory()

        logger.info("Repository persistence completed: $filesPersisted files persisted, $extractionErrors extraction errors, $persistenceErrors persistence errors")

        delegate?.onScanCompleted(result)
    }

    private suspend fun flushCurrentDirectory() {
        if (currentDirectoryFiles.isEmpty()) {
            return
        }

        val directoryPath = currentDirectory ?: "unknown"
        logger.debug("Flushing ${currentDirectoryFiles.size} files from directory: $directoryPath")

        // Extract metadata for all files in the current directory
        for (fileInfo in currentDirectoryFiles) {
            try {
                val metadata = metadataExtractor.extractMetadata(fileInfo)

                if (metadata != null) {
                    filesExtracted++

                    // Persist immediately after extraction
                    try {
                        repository.savePDF(metadata)
                        filesPersisted++
                        allExtractedMetadata.add(metadata)
                        logger.debug("Persisted metadata for: ${metadata.fileName}")
                    } catch (e: Exception) {
                        persistenceErrors++
                        logger.error("Failed to persist metadata for ${fileInfo.path}", e)
                    }
                } else {
                    extractionErrors++
                    logger.warn("Metadata extraction returned null for ${fileInfo.path}")
                }
            } catch (e: Exception) {
                extractionErrors++
                logger.error("Failed to extract metadata from ${fileInfo.path}", e)
            }
        }

        currentDirectoryFiles.clear()
    }

    /**
     * Get all successfully extracted and persisted metadata
     */
    fun getExtractedMetadata(): List<com.example.metadata.PDFMetadata> {
        return allExtractedMetadata.toList()
    }

    /**
     * Reset the listener state for a new scan
     */
    fun reset() {
        currentDirectory = null
        currentDirectoryFiles.clear()
        allExtractedMetadata.clear()
        filesExtracted = 0
        filesPersisted = 0
        extractionErrors = 0
        persistenceErrors = 0
    }
}
