# AGENTS.md

This file provides guidance for AI agents working with this PDF management application repository.

## Project Overview

This is a high-performance PDF management application built with Kotlin + Ktor. The system manages thousands of PDFs with thumbnail generation, metadata indexing, and flexible storage backends.

**Technology Stack**: Kotlin (JVM 21) • Ktor 2.3.5 • Apache PDFBox 3.0.0 • kotlinx-serialization • Gradle with Kotlin DSL

## Dev Environment Tips

- Use `./gradlew projects` to see all available project modules instead of scanning with `ls`
- Check `config.json` for runtime configuration before making changes to scan paths or metadata storage
- Use `./gradlew tasks` to see all available Gradle tasks for the project
- The main application class is `src/main/kotlin/com/example/Main.kt` - start there for understanding initialization flow
- Configuration files use JSON format - validate JSON syntax before committing changes

## Common Development Commands

### Building and Running
```bash
./gradlew build         # Build the project
./gradlew test          # Run all tests
./gradlew run           # Run the application locally (starts on http://localhost:8080)
./gradlew clean build   # Clean build from scratch
java -jar build/libs/pdf-library-1.0.0.jar    # Run the built JAR directly
```

### Development Workflow
```bash
./gradlew compileKotlin      # Compile Kotlin sources only
./gradlew classes            # Compile all classes
./gradlew installDist        # Create distribution in build/install
```

## Architecture Overview

### Initialization Flow
Config → Storage → Repository → Sync → Server

### Core Components

**IMPORTANT**: When you add, remove, or significantly modify any core component in the codebase (new classes, packages, or major architectural changes), you MUST update this "Core Components" section in AGENTS.md to reflect those changes. This ensures all agents have accurate information about the system architecture.

**Main Application** (`src/main/kotlin/com/example/Main.kt`)
- Ktor-based web server with REST API endpoints
- Global components: `repository`, `repositoryManager`, `syncService`

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

## Testing Instructions

- All tests are located under `src/test/kotlin/com/example/`
- Run `./gradlew test` to execute the full test suite before committing
- Run `./gradlew test --tests "ClassName.testMethodName"` to run a specific test
- Tests are organized by component (unit tests, integration tests, demo classes)
- The build should pass all tests before merging any changes
- Add or update tests for any code you change, even if nobody asked
- Integration tests may require temporary files - they handle cleanup automatically
- Use MockK for mocking in unit tests
- Check test output in `build/reports/tests/test/index.html` for detailed results

### Test Categories
- **Unit Tests**: Individual component behavior
- **Integration Tests**: System interactions and data flow
- **Demo Classes**: Development examples and usage patterns

## API Endpoints Reference

- `GET /` - Health check
- `GET /status` - System status
- `POST /api/sync` - Trigger sync (body: `{"type": "full|incremental"}`)
- `GET /api/pdfs` - List PDFs with pagination (`?page=0&size=50&q=search`)
- `GET /api/pdfs/{id}` - Get specific PDF metadata
- `GET /api/search` - Search PDFs (`?q=query&author=&title=&property=&value=`)

## Configuration

The application uses `config.json` in the project root:
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

## PR Instructions

- Always run `./gradlew test` before committing
- Run `./gradlew build` to ensure no compilation errors
- Verify the application starts correctly with `./gradlew run` (Ctrl+C to stop)
- Check that any new dependencies are properly declared in `build.gradle.kts`
- Update relevant documentation if you change configuration structure or API endpoints
- Ensure code follows Kotlin coding conventions
- Write clear commit messages describing the changes and their purpose

## Common Troubleshooting

- If build fails with "Cannot find symbol", run `./gradlew clean build`
- If tests fail with file system errors, check that test resources are accessible
- If the server won't start, verify `config.json` exists and paths are valid
- For PDF processing errors, ensure PDFBox can access and read the PDF files
- Check that JVM 21 or higher is installed and active