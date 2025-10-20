# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a high-performance PDF management application built with Kotlin + Ktor. The system manages thousands of PDFs with thumbnail generation, metadata indexing, and flexible storage backends.

## Common Development Commands

### Building and Testing
```bash
./gradlew build         # Build the project
./gradlew test          # Run all tests
./gradlew run           # Run the application locally (starts on http://localhost:8080)
```

### Running the Application
```bash
java -jar build/libs/pdf-library-1.0.0.jar    # Run the built JAR
```

## Architecture

### Core Components

**Main Application** (`src/main/kotlin/com/example/Main.kt`)
- Ktor-based web server with REST API endpoints
- Global components: `repository`, `repositoryManager`, `syncService`
- Initialization flow: Config → Storage → Repository → Sync → Server

**Storage Layer** (`src/main/kotlin/com/example/storage/`)
- `StorageProvider` interface for pluggable backends
- `FileSystemStorage` primary implementation
- `FileMetadata` data model for file information

**Scanning & Metadata** (`src/main/kotlin/com/example/scanning/`, `src/main/kotlin/com/example/metadata/`)
- `PDFScanner` handles file discovery and validation
- `MetadataExtractor` extracts PDF metadata using PDFBox
- `BatchMetadataExtractor` for bulk processing
- `DuplicateDetector` for content-based deduplication

**Repository System** (`src/main/kotlin/com/example/repository/`)
- `MetadataRepository` interface with `InMemoryMetadataRepository` implementation
- `RepositoryManager` handles initialization and lifecycle
- `JsonPersistenceManager` for filesystem-based persistence
- `SearchEngine` for full-text search capabilities
- `ConsistencyManager` ensures data integrity

**Configuration** (`src/main/kotlin/com/example/config/`)
- `ConfigurationManager` loads from `config.json`
- `AppConfiguration` main config data class
- `ScanConfiguration` for scan-specific settings

**Sync Service** (`src/main/kotlin/com/example/sync/`)
- `SyncService` orchestrates incremental and full syncing
- Integrates scanning, metadata extraction, and repository updates

### Key Design Patterns

- **Layered Architecture**: Storage → Repository → Service → API
- **Dependency Injection**: Components passed through constructors
- **Async Processing**: Coroutines for I/O operations
- **Immutable Data**: Kotlin data classes for metadata
- **Error Handling**: Result types and exception propagation

### Configuration

The application uses `config.json` for configuration:
```json
{
    "pdfScanPaths": ["/path/to/pdfs"],
    "metadataStoragePath": "/path/to/metadata",
    "scanning": {
        "maxDepth": 25,
        "excludePatterns": ["*.tmp"]
    }
}
```

### API Endpoints

- `GET /` - Health check
- `GET /status` - System status
- `POST /api/sync` - Trigger sync (body: `{"type": "full|incremental"}`)
- `GET /api/pdfs` - List PDFs with pagination (`?page=0&size=50&q=search`)
- `GET /api/pdfs/{id}` - Get specific PDF
- `GET /api/search` - Search PDFs (`?q=query&author=&title=&property=&value=`)

### Technology Stack

- **Language**: Kotlin with JVM target 21
- **Web Framework**: Ktor 2.3.5
- **PDF Processing**: Apache PDFBox 3.0.0
- **Serialization**: kotlinx-serialization
- **Testing**: JUnit 5, MockK
- **Build Tool**: Gradle with Kotlin DSL

### Testing Strategy

Tests are organized by component under `src/test/kotlin/com/example/`:
- Unit tests for individual components
- Integration tests for system interactions
- Demo classes for development examples