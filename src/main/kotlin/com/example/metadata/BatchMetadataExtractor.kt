package com.example.metadata

import com.example.scanning.PDFFileInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

class BatchMetadataExtractor(
    private val metadataExtractor: MetadataExtractor,
    private val concurrency: Int = 4
) {

    private val logger = LoggerFactory.getLogger(BatchMetadataExtractor::class.java)

    suspend fun extractBatch(files: List<PDFFileInfo>): List<PDFMetadata> {
        logger.info("Starting batch metadata extraction for ${files.size} files")

        return coroutineScope {
            files
                .chunked(concurrency)
                .map { chunk ->
                    chunk.map { fileInfo ->
                        async {
                            try {
                                metadataExtractor.extractMetadata(fileInfo)
                            } catch (e: Exception) {
                                logger.error("Failed to extract metadata from ${fileInfo.path}", e)
                                null
                            }
                        }
                    }
                }
                .flatten()
                .awaitAll()
                .filterNotNull()
        }
    }

    suspend fun extractWithProgress(
        files: List<PDFFileInfo>,
        progressCallback: (Int, Int) -> Unit
    ): List<PDFMetadata> {
        logger.info("Starting batch metadata extraction with progress tracking for ${files.size} files")

        val results = mutableListOf<PDFMetadata>()
        var processed = 0

        return coroutineScope {
            files
                .chunked(concurrency)
                .forEach { chunk ->
                    val chunkResults = chunk.map { fileInfo ->
                        async {
                            try {
                                val result = metadataExtractor.extractMetadata(fileInfo)
                                processed++
                                progressCallback(processed, files.size)
                                result
                            } catch (e: Exception) {
                                logger.error("Failed to extract metadata from ${fileInfo.path}", e)
                                processed++
                                progressCallback(processed, files.size)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()

                    results.addAll(chunkResults)
                }

            results
        }
    }

    suspend fun extractWithDetailedProgress(
        files: List<PDFFileInfo>,
        progressCallback: (current: Int, total: Int, currentFile: String, extracted: PDFMetadata?) -> Unit
    ): List<PDFMetadata> {
        logger.info("Starting detailed batch metadata extraction for ${files.size} files")

        val results = mutableListOf<PDFMetadata>()
        var processed = 0

        return coroutineScope {
            files
                .chunked(concurrency)
                .forEach { chunk ->
                    val chunkResults = chunk.map { fileInfo ->
                        async {
                            try {
                                val result = metadataExtractor.extractMetadata(fileInfo)
                                processed++
                                progressCallback(processed, files.size, fileInfo.fileName, result)
                                result
                            } catch (e: Exception) {
                                logger.error("Failed to extract metadata from ${fileInfo.path}", e)
                                processed++
                                progressCallback(processed, files.size, fileInfo.fileName, null)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()

                    results.addAll(chunkResults)
                }

            results
        }
    }
}