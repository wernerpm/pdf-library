# Step 1b: PDF Scanner + Basic File Discovery - DONE

> **Status**: Fully implemented. Includes recursive directory traversal, duplicate detection, configurable `maxFiles` limit (100k default), symlink loop prevention via `toRealPath()` visited-directory tracking, `ScanProgressListener` with suspend methods, and comprehensive error reporting.

## Overview
Implement the PDF scanning service that discovers PDF files across configured directories using the storage abstraction layer from Step 1a.

## Prerequisites
- Step 1a completed: Configuration module + Storage abstraction layer

## Scope
- PDF file discovery service
- Recursive directory traversal
- File filtering and validation
- Basic duplicate detection
- Integration with configuration system

## Implementation Details

### 1. PDF Scanner Service
**PDFScanner.kt:**
```kotlin
class PDFScanner(
    private val storageProvider: StorageProvider,
    private val configuration: AppConfiguration
) {
    suspend fun scanForPDFs(): List<PDFFileInfo>
    suspend fun scanPath(path: String): List<PDFFileInfo>

    private suspend fun walkDirectory(
        path: String,
        currentDepth: Int = 0
    ): List<PDFFileInfo>

    private fun isPdfFile(fileName: String): Boolean
    private fun shouldExclude(fileName: String): Boolean
    private suspend fun validatePdfFile(path: String): Boolean
}

data class PDFFileInfo(
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Instant?,
    val discovered: Instant = Clock.System.now()
)
```

### 2. File Discovery Logic
**Core scanning algorithm:**
1. Iterate through configured `pdfScanPaths`
2. For each path, perform recursive traversal (if enabled)
3. Filter files by extension and exclusion patterns
4. Validate PDF files (basic header check)
5. Collect file metadata for discovered PDFs
6. Remove duplicates by canonical path

### 3. PDF File Validation
**PDFValidator.kt:**
```kotlin
class PDFValidator(private val storageProvider: StorageProvider) {

    suspend fun isValidPDF(path: String): Boolean {
        return try {
            val header = storageProvider.read(path).take(8)
            header.startsWith("%PDF-".toByteArray())
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getPDFVersion(path: String): String? {
        return try {
            val header = storageProvider.read(path).take(16)
            extractVersionFromHeader(header)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVersionFromHeader(header: ByteArray): String?
}
```

### 4. Scanning Configuration
Enhanced configuration for scanning behavior:
```kotlin
@Serializable
data class ScanConfiguration(
    val recursive: Boolean = true,
    val maxDepth: Int = 50,
    val excludePatterns: List<String> = emptyList(),
    val fileExtensions: List<String> = listOf(".pdf"),
    val validatePdfHeaders: Boolean = true,
    val followSymlinks: Boolean = false,
    val maxFileSize: Long = 500_000_000L // 500MB
)
```

### 5. Duplicate Detection
**DuplicateDetector.kt:**
```kotlin
class DuplicateDetector {

    fun removeDuplicates(files: List<PDFFileInfo>): List<PDFFileInfo> {
        return files
            .groupBy { it.canonicalPath() }
            .values
            .map { group -> group.first() }
    }

    private fun PDFFileInfo.canonicalPath(): String {
        return try {
            Paths.get(path).toRealPath().toString()
        } catch (e: Exception) {
            path
        }
    }
}
```

### 6. Scanning Results
**ScanResult.kt:**
```kotlin
data class ScanResult(
    val discoveredFiles: List<PDFFileInfo>,
    val totalFilesScanned: Int,
    val totalDirectoriesScanned: Int,
    val duplicatesRemoved: Int,
    val invalidFilesSkipped: Int,
    val scanDuration: Duration,
    val errors: List<ScanError>
)

data class ScanError(
    val path: String,
    val error: String,
    val timestamp: Instant
)
```

### 7. Progress Tracking
**ScanProgress.kt:**
```kotlin
interface ScanProgressListener {
    fun onDirectoryStarted(path: String)
    fun onFileDiscovered(file: PDFFileInfo)
    fun onError(error: ScanError)
    fun onScanCompleted(result: ScanResult)
}

class ConsoleScanProgressListener : ScanProgressListener {
    // Implementation for console output
}
```

### 8. Project Structure Addition
```
src/main/kotlin/com/example/
├── scanning/
│   ├── PDFScanner.kt
│   ├── PDFValidator.kt
│   ├── DuplicateDetector.kt
│   ├── ScanResult.kt
│   ├── ScanProgress.kt
│   └── PDFFileInfo.kt

src/test/kotlin/com/example/
├── scanning/
│   ├── PDFScannerTest.kt
│   ├── PDFValidatorTest.kt
│   └── DuplicateDetectorTest.kt
```

### 9. Integration Example
**Usage in Application.kt:**
```kotlin
class Application {
    suspend fun scanPDFs() {
        val config = configurationManager.loadConfiguration()
        val storage = FileSystemStorage(config.metadataStoragePath)
        val scanner = PDFScanner(storage, config)
        val progressListener = ConsoleScanProgressListener()

        val result = scanner.scanForPDFs()

        println("Discovered ${result.discoveredFiles.size} PDF files")
        println("Scan completed in ${result.scanDuration}")
    }
}
```

### 10. Performance Considerations
- **Concurrent scanning**: Use coroutines for parallel directory traversal
- **Memory efficiency**: Stream processing for large directories
- **I/O optimization**: Batch file operations where possible
- **Early termination**: Respect maxDepth and file size limits

### 11. Error Handling
- Graceful handling of permission errors
- Continue scanning despite individual file errors
- Detailed error reporting with paths and causes
- Configurable error tolerance levels

## Testing Strategy
- Unit tests with mock storage provider
- Integration tests with real filesystem
- Performance tests with large directory structures
- Edge case testing (symlinks, special characters, etc.)

## Deliverables
After this step, you'll have:
- Complete PDF file discovery system
- Robust file validation and filtering
- Duplicate detection capability
- Progress tracking and error reporting
- Ready for metadata extraction (Step 1c)

## Next Step
**Step 1c**: Metadata extraction with PDFBox integration