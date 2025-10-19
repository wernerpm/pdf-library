# Step 1a: Configuration Module + Storage Abstraction Layer

## Overview
Implement the foundation components: configuration management system and pluggable storage abstraction layer with FileSystem implementation.

## Scope
- Configuration module with JSON-based config file
- Storage abstraction interface
- FileSystem storage implementation
- Basic project structure setup

## Implementation Details

### 1. Project Setup
**build.gradle.kts:**
```kotlin
plugins {
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    application
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

### 2. Configuration Module
**AppConfiguration.kt:**
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
```kotlin
class ConfigurationManager {
    companion object {
        private const val CONFIG_FILE = "config.json"
        private const val DEFAULT_METADATA_PATH = ".pdf-library/metadata"
    }

    suspend fun loadConfiguration(): AppConfiguration
    fun getDefaultConfiguration(): AppConfiguration
    suspend fun saveConfiguration(config: AppConfiguration)
    fun validateConfiguration(config: AppConfiguration): List<String>
}
```

**Default config.json:**
```json
{
  "pdfScanPaths": [
    "~/Documents/PDFs",
    "~/Downloads"
  ],
  "metadataStoragePath": "~/.pdf-library/metadata",
  "scanning": {
    "recursive": true,
    "maxDepth": 50,
    "excludePatterns": [".*", "temp*", "*.tmp"],
    "fileExtensions": [".pdf"]
  }
}
```

### 3. Storage Abstraction Layer
**StorageProvider.kt:**
```kotlin
interface StorageProvider {
    suspend fun exists(path: String): Boolean
    suspend fun read(path: String): ByteArray
    suspend fun write(path: String, data: ByteArray)
    suspend fun list(path: String): List<String>
    suspend fun delete(path: String)
    suspend fun getMetadata(path: String): FileMetadata
    suspend fun createDirectory(path: String)
}

data class FileMetadata(
    val path: String,
    val size: Long,
    val createdAt: Instant?,
    val modifiedAt: Instant?,
    val isDirectory: Boolean
)
```

**FileSystemStorage.kt:**
```kotlin
class FileSystemStorage(private val basePath: String) : StorageProvider {

    override suspend fun exists(path: String): Boolean
    override suspend fun read(path: String): ByteArray
    override suspend fun write(path: String, data: ByteArray)
    override suspend fun list(path: String): List<String>
    override suspend fun delete(path: String)
    override suspend fun getMetadata(path: String): FileMetadata
    override suspend fun createDirectory(path: String)

    private fun validatePath(path: String)
    private fun resolvePath(path: String): Path
}
```

### 4. Project Structure
```
src/main/kotlin/com/example/
├── config/
│   ├── AppConfiguration.kt
│   ├── ScanConfiguration.kt
│   └── ConfigurationManager.kt
├── storage/
│   ├── StorageProvider.kt
│   ├── FileSystemStorage.kt
│   └── FileMetadata.kt
└── Application.kt

src/test/kotlin/com/example/
├── config/
│   └── ConfigurationManagerTest.kt
└── storage/
    └── FileSystemStorageTest.kt

resources/
└── config.json (default configuration)
```

### 5. Key Implementation Features
- **Path Resolution**: Support for `~` expansion and relative paths
- **Validation**: Comprehensive path validation and security checks
- **Error Handling**: Proper exception handling for I/O operations
- **Atomic Operations**: Safe file writes using temp files
- **Cross-Platform**: Works on Windows, macOS, and Linux

### 6. Testing Strategy
- Unit tests for configuration loading/validation
- FileSystem storage operations testing
- Path validation and security tests
- Mock storage provider for other components

## Deliverables
After this step, you'll have:
- Working configuration system that loads from JSON
- Complete storage abstraction layer
- FileSystem implementation ready for use
- Solid foundation for next components
- Comprehensive test coverage

## Next Step
**Step 1b**: PDF scanner + basic file discovery using the storage layer