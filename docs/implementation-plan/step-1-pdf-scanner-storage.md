# Step 1: PDF Scanner and Storage Module (Kotlin) - DONE

> **Status**: All components in this step are fully implemented with additional security hardening applied (13 fixes covering thread safety, symlink protection, file size limits, persist-first ordering, timeout protection, and more). See AGENTS.md for full details.

## Overview
Implement the storage abstraction layer with FileSystem implementation and PDF indexing service using Kotlin/Ktor, following the architecture defined in the main implementation plan.

## Scope
- Create storage abstraction layer with FileSystem implementation
- Implement PDF scanner for configured filesystem paths
- Extract comprehensive PDF metadata using PDFBox
- Store metadata in JSON format alongside PDFs
- Build in-memory metadata repository
- Thumbnail generation added (ThumbnailGenerator renders page 0 → PNG)
- No API endpoints (covered in Step 3)

## Implementation Details

### 1. Storage Abstraction Layer
Create pluggable storage backend system:

**StorageProvider Interface:**
```kotlin
interface StorageProvider {
    suspend fun exists(path: String): Boolean
    suspend fun read(path: String): ByteArray
    suspend fun write(path: String, data: ByteArray)
    suspend fun list(path: String): List<String>
    suspend fun delete(path: String)
    suspend fun getMetadata(path: String): FileMetadata
}
```

**FileSystemStorage Implementation:**
- Direct filesystem operations using Kotlin coroutines
- Path validation and security checks
- Atomic write operations for metadata files
- Support for large file handling

### 2. PDF Scanner Service
**PDFScanner.kt:**
- Recursive directory traversal using `Files.walk()`
- PDF detection by extension and MIME type validation
- File accessibility checks
- Duplicate detection by canonical path
- Configurable scan depth and exclusion patterns

### 3. Metadata Extraction
**MetadataExtractor.kt using PDFBox:**
Extract comprehensive metadata:
- Standard PDF properties (title, author, subject, creator, producer)
- Technical metadata (page count, PDF version, file size)
- File system metadata (creation/modification dates)
- Content hash for duplicate detection
- Custom properties support for future extensibility

### 4. In-Memory Metadata Repository
**InMemoryMetadataRepository.kt:**
- Load all metadata.json files into memory at startup
- Provide fast search and filtering operations
- Persist changes back to filesystem
- Thread-safe operations for concurrent access

### 5. Core Components Structure
```
src/main/kotlin/com/example/
├── storage/
│   ├── StorageProvider.kt
│   ├── FileSystemStorage.kt
│   └── FileMetadata.kt
├── indexing/
│   ├── PDFScanner.kt
│   └── MetadataExtractor.kt
├── metadata/
│   ├── MetadataRepository.kt
│   ├── InMemoryMetadataRepository.kt
│   └── PDFMetadata.kt
└── config/
    ├── AppConfiguration.kt
    ├── ScanConfiguration.kt
    └── ConfigurationManager.kt
```

### 6. Dependencies (build.gradle.kts)
```kotlin
dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("ch.qos.logback:logback-classic:1.4.11")
}
```

### 7. PDF Metadata Data Class
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
    val creator: String?,
    val producer: String?,
    val keywords: List<String> = emptyList(),

    // Custom metadata for extensibility
    val customProperties: Map<String, String> = emptyMap(),

    // System metadata
    val contentHash: String?,
    val indexedAt: Instant
)
```

### 8. Configuration Module
**config.json file structure:**
```json
{
  "pdfScanPaths": [
    "/Users/username/Documents/PDFs",
    "/Users/username/Downloads",
    "/storage/shared/documents"
  ],
  "metadataStoragePath": "/Users/username/.pdf-library/metadata",
  "scanning": {
    "recursive": true,
    "maxDepth": 50,
    "excludePatterns": [".*", "temp*", "*.tmp"],
    "fileExtensions": [".pdf"]
  }
}
```

**Configuration Data Classes:**
```kotlin
@Serializable
data class AppConfiguration(
    val pdfScanPaths: List<String>,
    val metadataStoragePath: String,
    val scanning: ScanConfiguration
)

@Serializable
data class ScanConfiguration(
    val recursive: Boolean = true,
    val maxDepth: Int = 50,
    val excludePatterns: List<String> = emptyList(),
    val fileExtensions: List<String> = listOf(".pdf")
)
```

**ConfigurationManager.kt:**
- Load config.json from application directory or user home
- Validate paths exist and are accessible
- Provide defaults if config file doesn't exist
- Hot-reload configuration changes

### 9. Metadata Storage Format
Metadata stored as `metadata.json` files alongside PDFs:
```json
{
  "id": "uuid-generated-identifier",
  "path": "/storage/documents/example.pdf",
  "fileName": "example.pdf",
  "fileSize": 1024576,
  "pageCount": 42,
  "createdDate": "2024-01-01T12:00:00Z",
  "modifiedDate": "2024-01-01T12:00:00Z",
  "title": "Document Title",
  "author": "Author Name",
  "subject": "Document Subject",
  "creator": "LibreOffice Writer",
  "producer": "PDFBox",
  "keywords": ["keyword1", "keyword2"],
  "customProperties": {},
  "contentHash": "sha256-hash",
  "indexedAt": "2024-01-01T12:00:00Z"
}
```

### 10. Key Implementation Features
- **Coroutine-based**: All I/O operations use Kotlin coroutines for non-blocking execution
- **Type Safety**: Leveraging Kotlin's type system for compile-time error prevention
- **Memory Efficiency**: Stream-based processing for large files
- **Error Handling**: Comprehensive error handling with proper logging
- **Testing Ready**: Clean interfaces for easy mocking and testing

## Next Steps
After completion of this step:
- **Step 2**: Add thumbnail generation using PDFBox - DONE
- **Step 3**: Implement REST API endpoints with Ktor - DONE (implemented as part of Step 1)
- **Step 4**: Build sync service with incremental updates - DONE (implemented as part of Step 1)
- **Step 5**: Create frontend UI with vanilla JavaScript - NOT STARTED