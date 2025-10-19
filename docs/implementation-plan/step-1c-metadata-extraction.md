# Step 1c: Metadata Extraction with PDFBox

## Overview
Implement comprehensive PDF metadata extraction using Apache PDFBox, transforming discovered PDF files into rich metadata objects.

## Prerequisites
- Step 1a completed: Configuration module + Storage abstraction layer
- Step 1b completed: PDF scanner + basic file discovery

## Scope
- PDFBox integration for metadata extraction
- Comprehensive PDF metadata extraction
- Content hash generation for duplicate detection
- Error handling for corrupted/protected PDFs
- Integration with scanning results

## Implementation Details

### 1. Dependencies Update
**build.gradle.kts addition:**
```kotlin
dependencies {
    // Existing dependencies...
    implementation("org.apache.pdfbox:pdfbox:3.0.0")
    implementation("org.apache.pdfbox:pdfbox-tools:3.0.0")

    // For hashing
    implementation("org.apache.commons:commons-codec:1.16.0")
}
```

### 2. PDF Metadata Model
**PDFMetadata.kt:**
```kotlin
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
    val indexedAt: Instant = Clock.System.now()
)
```

### 3. Metadata Extractor Service
**MetadataExtractor.kt:**
```kotlin
class MetadataExtractor(
    private val storageProvider: StorageProvider
) {

    suspend fun extractMetadata(fileInfo: PDFFileInfo): PDFMetadata? {
        return try {
            val pdfBytes = storageProvider.read(fileInfo.path)
            extractFromBytes(pdfBytes, fileInfo)
        } catch (e: Exception) {
            logger.error("Failed to extract metadata from ${fileInfo.path}", e)
            null
        }
    }

    private suspend fun extractFromBytes(
        pdfBytes: ByteArray,
        fileInfo: PDFFileInfo
    ): PDFMetadata {
        return withContext(Dispatchers.IO) {
            PDDocument.load(pdfBytes).use { document ->
                buildPDFMetadata(document, fileInfo, pdfBytes)
            }
        }
    }

    private fun buildPDFMetadata(
        document: PDDocument,
        fileInfo: PDFFileInfo,
        pdfBytes: ByteArray
    ): PDFMetadata

    private fun extractDocumentInfo(document: PDDocument): DocumentInfoData
    private fun extractCustomProperties(document: PDDocument): Map<String, String>
    private fun generateContentHash(pdfBytes: ByteArray): String
    private fun parseKeywords(keywordString: String?): List<String>
}
```

### 4. Document Information Extraction
**DocumentInfoExtractor.kt:**
```kotlin
data class DocumentInfoData(
    val title: String?,
    val author: String?,
    val subject: String?,
    val creator: String?,
    val producer: String?,
    val keywords: String?,
    val creationDate: Calendar?,
    val modificationDate: Calendar?
)

class DocumentInfoExtractor {

    fun extract(document: PDDocument): DocumentInfoData {
        val docInfo = document.documentInformation
        return DocumentInfoData(
            title = docInfo.title?.trim()?.takeIf { it.isNotBlank() },
            author = docInfo.author?.trim()?.takeIf { it.isNotBlank() },
            subject = docInfo.subject?.trim()?.takeIf { it.isNotBlank() },
            creator = docInfo.creator?.trim()?.takeIf { it.isNotBlank() },
            producer = docInfo.producer?.trim()?.takeIf { it.isNotBlank() },
            keywords = docInfo.keywords?.trim()?.takeIf { it.isNotBlank() },
            creationDate = docInfo.creationDate,
            modificationDate = docInfo.modificationDate
        )
    }
}
```

### 5. Content Hash Generator
**ContentHashGenerator.kt:**
```kotlin
class ContentHashGenerator {

    fun generateHash(pdfBytes: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(pdfBytes)
            Base64.getEncoder().encodeToString(hashBytes)
        } catch (e: Exception) {
            // Fallback to simple hash
            pdfBytes.contentHashCode().toString()
        }
    }

    fun generateMetadataHash(metadata: PDFMetadata): String {
        val content = "${metadata.title}-${metadata.author}-${metadata.pageCount}-${metadata.fileSize}"
        return content.hashCode().toString()
    }
}
```

### 6. Custom Properties Extractor
**CustomPropertiesExtractor.kt:**
```kotlin
class CustomPropertiesExtractor {

    fun extract(document: PDDocument): Map<String, String> {
        val customProps = mutableMapOf<String, String>()

        try {
            // Extract XMP metadata if available
            val xmpMetadata = document.documentCatalog.metadata
            if (xmpMetadata != null) {
                customProps.putAll(extractXMPProperties(xmpMetadata))
            }

            // Extract custom document properties
            val docInfo = document.documentInformation
            docInfo.metadataKeys.forEach { key ->
                val value = docInfo.getCustomMetadataValue(key)
                if (value != null && !isStandardProperty(key)) {
                    customProps[key] = value.toString()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract custom properties", e)
        }

        return customProps
    }

    private fun extractXMPProperties(metadata: PDMetadata): Map<String, String>
    private fun isStandardProperty(key: String): Boolean
}
```

### 7. Security and Error Handling
**SecurePDFHandler.kt:**
```kotlin
class SecurePDFHandler {

    fun canExtractMetadata(document: PDDocument): Boolean {
        return try {
            !document.isEncrypted || document.currentAccessPermission.canExtractContent
        } catch (e: Exception) {
            false
        }
    }

    fun handleEncryptedPDF(pdfBytes: ByteArray): PDFMetadata? {
        return try {
            // Attempt to load with empty password
            PDDocument.load(pdfBytes, "").use { document ->
                if (canExtractMetadata(document)) {
                    // Extract what we can
                    extractBasicInfo(document)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractBasicInfo(document: PDDocument): PDFMetadata?
}
```

### 8. Batch Processing
**BatchMetadataExtractor.kt:**
```kotlin
class BatchMetadataExtractor(
    private val metadataExtractor: MetadataExtractor,
    private val concurrency: Int = 4
) {

    suspend fun extractBatch(files: List<PDFFileInfo>): List<PDFMetadata> {
        return files
            .chunked(concurrency)
            .map { chunk ->
                chunk.map { fileInfo ->
                    async {
                        metadataExtractor.extractMetadata(fileInfo)
                    }
                }
            }
            .flatten()
            .awaitAll()
            .filterNotNull()
    }

    suspend fun extractWithProgress(
        files: List<PDFFileInfo>,
        progressCallback: (Int, Int) -> Unit
    ): List<PDFMetadata>
}
```

### 9. Integration with Scanner
**Enhanced ScanResult.kt:**
```kotlin
data class ScanResult(
    val discoveredFiles: List<PDFFileInfo>,
    val extractedMetadata: List<PDFMetadata>,
    val totalFilesScanned: Int,
    val totalDirectoriesScanned: Int,
    val duplicatesRemoved: Int,
    val invalidFilesSkipped: Int,
    val metadataExtractionErrors: Int,
    val scanDuration: Duration,
    val extractionDuration: Duration,
    val errors: List<ScanError>
)
```

### 10. Project Structure Addition
```
src/main/kotlin/com/example/
├── metadata/
│   ├── MetadataExtractor.kt
│   ├── DocumentInfoExtractor.kt
│   ├── CustomPropertiesExtractor.kt
│   ├── ContentHashGenerator.kt
│   ├── SecurePDFHandler.kt
│   ├── BatchMetadataExtractor.kt
│   └── PDFMetadata.kt

src/test/kotlin/com/example/
├── metadata/
│   ├── MetadataExtractorTest.kt
│   ├── ContentHashGeneratorTest.kt
│   └── BatchMetadataExtractorTest.kt

src/test/resources/
├── sample-pdfs/
│   ├── simple.pdf
│   ├── encrypted.pdf
│   ├── with-metadata.pdf
│   └── corrupted.pdf
```

### 11. Usage Example
**Application.kt integration:**
```kotlin
class Application {
    suspend fun scanAndExtractMetadata() {
        val config = configurationManager.loadConfiguration()
        val storage = FileSystemStorage(config.metadataStoragePath)
        val scanner = PDFScanner(storage, config)
        val extractor = BatchMetadataExtractor(MetadataExtractor(storage))

        // Scan for PDFs
        val scanResult = scanner.scanForPDFs()
        println("Found ${scanResult.discoveredFiles.size} PDFs")

        // Extract metadata
        val metadata = extractor.extractWithProgress(scanResult.discoveredFiles) { current, total ->
            println("Extracting metadata: $current/$total")
        }

        println("Extracted metadata from ${metadata.size} PDFs")
    }
}
```

### 12. Performance Optimizations
- **Streaming**: Process large PDFs without loading entirely into memory
- **Caching**: Cache frequently accessed metadata
- **Parallel Processing**: Concurrent metadata extraction
- **Memory Management**: Proper disposal of PDDocument resources

## Testing Strategy
- Unit tests with sample PDF files
- Performance tests with large PDFs
- Error handling tests with corrupted files
- Security tests with encrypted/protected PDFs
- Memory leak tests for resource management

## Deliverables
After this step, you'll have:
- Complete PDF metadata extraction system
- Robust handling of various PDF types
- Content hashing for duplicate detection
- Batch processing capabilities
- Ready for persistence layer (Step 1d)

## Next Step
**Step 1d**: In-memory repository + JSON persistence system