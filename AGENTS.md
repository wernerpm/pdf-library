# AGENTS.md

This file provides guidance for AI agents working with this PDF management application repository.

## Project Overview

This is a high-performance PDF management application built with Kotlin + Ktor. The system manages thousands of PDFs with metadata indexing, full-text search, content-based deduplication, and flexible storage backends.

**Technology Stack**: Kotlin 2.3.10 (JVM 21) • Ktor 3.4.0 • Apache PDFBox 3.0.6 • kotlinx-serialization 1.8.0 • kotlinx-coroutines 1.10.2 • kotlinx-datetime 0.7.1 • Gradle with Kotlin DSL

## Implementation Status

See [`docs/implementation-plan/current-status.md`](docs/implementation-plan/current-status.md) for a full breakdown of what's built, what's next, and known deviations from the original plans.

**Summary**: Steps 1a–1d and Step 2 are complete. Step 3 (progress, full-text search, file watching) is next. Frontend is after that. Backend improvements (Step 2B) are deferred until after the frontend.

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
./gradlew run           # Run the application locally (starts on http://0.0.0.0:8080)
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

```
1. ConfigurationManager loads AppConfiguration from config.json
2. FileSystemStorage created for metadata (restricted to metadataStoragePath)
3. FileSystemStorage created for PDFs (unrestricted - root path "/")
4. JsonRepository created (persistent layer using JsonPersistenceManager)
5. InMemoryMetadataRepository created (cache layer wrapping JsonRepository)
6. ConsistencyManager created (monitors cache/disk sync)
7. RepositoryManager created and initialized:
   - Loads metadata from storage into cache
   - Performs consistency check
   - Repairs inconsistencies if found
   - Validates data integrity
8. SyncService created with pdfStorage, config, and repository
9. ConsoleScanProgressListener registered
10. Background coroutine launched: attempts `resumeExtraction()` from existing manifest
    - If manifest found: extract only DISCOVERED entries (resumes after crash/restart)
    - If no manifest: falls back to `performFullSync()` (discovery + extraction)
11. Server marked as ready immediately (API starts accepting requests)
    - Previously extracted files served from persisted PDFMetadata while extraction runs
```

### Component Dependency Graph

```
Main (Ktor app)
├── ConfigurationManager
├── FileSystemStorage (metadata - restricted path)
├── FileSystemStorage (pdfs - unrestricted)
├── JsonRepository
│   └── JsonPersistenceManager
│       └── StorageProvider (metadata)
├── InMemoryMetadataRepository
│   ├── JsonRepository (backing store)
│   └── SearchEngine
├── ConsistencyManager
├── RepositoryManager
└── SyncService
    ├── PDFScanner
    │   ├── PDFValidator
    │   ├── DuplicateDetector
    │   └── RepositoryProgressListener (legacy scanAndPersist flow)
    ├── MetadataExtractor
    ├── BatchMetadataExtractor (concurrent batch helper, concurrency=2)
    ├── DiscoveryManifestManager (load/save/update manifest)
    │   └── FileSystemStorage (metadata path)
    └── MetadataRepository (persist extracted metadata)
```

### Global Singletons (Main.kt)
- `lateinit var appConfig: AppConfiguration` - Loaded application configuration
- `lateinit var repository: MetadataRepository` - InMemoryMetadataRepository instance
- `lateinit var repositoryManager: RepositoryManager`
- `lateinit var syncService: SyncService`

### Core Components

**IMPORTANT**: When you add, remove, or significantly modify any core component in the codebase (new classes, packages, or major architectural changes), you MUST update this "Core Components" section in AGENTS.md to reflect those changes. This ensures all agents have accurate information about the system architecture.

**Main Application** (`src/main/kotlin/com/example/Main.kt`)
- Ktor-based web server with REST API endpoints
- `configureApplication()` wires all components together
- `configureRouting()` defines all API routes
- Global components: `repository`, `repositoryManager`, `syncService`

**Storage Layer** (`src/main/kotlin/com/example/storage/`)
- `StorageProvider` - Interface with methods: `exists`, `read`, `write`, `list`, `delete`, `getMetadata`, `createDirectory`
- `FileSystemStorage` - Primary implementation with atomic writes (temp file + rename), path traversal prevention, normalized path handling, configurable `maxReadSize` (default 500MB), symlink-safe recursive delete
- `FileMetadata` - Data class: `path`, `size`, `createdAt`, `modifiedAt`, `isDirectory`

**Scanning** (`src/main/kotlin/com/example/scanning/`)
- `PDFScanner` - Recursive file discovery with configurable depth, extension filtering, size limits, symlink loop detection via visited directory tracking, configurable `maxFiles` cap (default 100k). Key methods: `scanForPDFs()` (raw scan), `discoverFiles()` (returns `DiscoveryManifest`), `scanAndPersist()` (legacy monolithic flow)
- `PDFValidator` - Validates PDF headers (checks for `%PDF` magic bytes)
- `DuplicateDetector` - Path-based deduplication across multiple scan paths
- `FileStatus` - `@Serializable` enum: `DISCOVERED`, `EXTRACTED`, `FAILED`
- `PDFFileInfo` - `@Serializable` data class: `path`, `fileName`, `fileSize`, `lastModified`, `discovered`, `status` (FileStatus, default DISCOVERED), `metadataPath` (nullable, path to extracted `<id>.json` file)
- `DiscoveryManifest` - `@Serializable` data class: `lastDiscovery`, `scanPaths`, `files` (List<PDFFileInfo>)
- `DiscoveryManifestManager` - Manages `discovery-manifest.json` at `$metadataStoragePath/discovery-manifest.json`. Methods: `load()`, `save()` (atomic via StorageProvider), `updateFileStatus(path, status, metadataPath?)` (load-patch-save)
- `ScanResult` - Data class with discovered files, metadata, error counts, timing
- `ScanProgress` / `ScanProgressListener` - Suspend callback interface: `onDirectoryStarted`, `onFileDiscovered`, `onError`, `onScanCompleted`
- `ExtractionProgressListener` - Non-suspend callback interface: `onExtractionStarted`, `onFileExtracted`, `onExtractionCompleted`
- `ConsoleScanProgressListener` - Implements both `ScanProgressListener` and `ExtractionProgressListener`, prints progress to console
- `RepositoryProgressListener` - Extracts and persists metadata during scan (legacy flow, used by `scanAndPersist()`)

**Metadata Extraction** (`src/main/kotlin/com/example/metadata/`)
- `MetadataExtractor` - Main extractor: reads PDF bytes via StorageProvider, loads with PDFBox, orchestrates sub-extractors, returns null on error, 60s timeout per PDF
- `DocumentInfoExtractor` - Extracts standard PDF document info (title, author, subject, creator, producer, dates)
- `CustomPropertiesExtractor` - Extracts custom and XMP metadata properties (XMP capped at 10MB)
- `ContentHashGenerator` - Generates SHA-256 hashes for both content and metadata deduplication (no weak fallbacks)
- `ThumbnailGenerator` - Renders PDF page 0 at 72 DPI, scales to 300px width (configurable), returns PNG bytes. Null-safe on failure or zero-page documents.
- `SecurePDFHandler` - Gracefully handles encrypted/signed PDFs
- `BatchMetadataExtractor` - Concurrent batch extraction with configurable parallelism and progress callbacks
- `PDFMetadata` - Core data model (see Data Models section)

**Repository System** (`src/main/kotlin/com/example/repository/`)
- `MetadataRepository` - Interface with methods: `getAllPDFs`, `getPDF`, `savePDF`, `deletePDF`, `search`, `searchByProperty`, `searchByAuthor`, `searchByTitle`, `count`, `loadFromStorage`, `persistToStorage`, `clear`
- `JsonRepository` - Persistent layer: stores each PDF as `$metadataPath/$id.json`, delegates search to in-memory
- `InMemoryMetadataRepository` - Cache layer wrapping any MetadataRepository: ConcurrentHashMap + 3 secondary indices (path, author, title), all reads and writes Mutex-protected, persist-first ordering (backing store updated before cache)
- `JsonPersistenceManager` - Serializes/deserializes PDFMetadata to individual JSON files
- `RepositoryManager` - Orchestrates initialization, shutdown, backup, maintenance, consistency checks
- `ConsistencyManager` - Detects and repairs orphaned data (in-memory vs on-disk), validates data integrity
- `SearchEngine` - Full-text search with relevance scoring: title (+3.0), author/filename (+2.0), subject/keywords (+1.0), custom properties (+0.5). AND logic across all terms.

**Configuration** (`src/main/kotlin/com/example/config/`)
- `ConfigurationManager` - Loads from `config.json`, creates defaults if missing, validates, supports path expansion (`~/path`)
- `AppConfiguration` - Data class: `pdfScanPaths`, `metadataStoragePath`, `scanning`
- `ScanConfiguration` - Data class: `recursive` (true), `maxDepth` (50), `excludePatterns`, `fileExtensions` ([".pdf"]), `validatePdfHeaders` (true), `followSymlinks` (false), `maxFileSize` (500MB), `maxFiles` (100k)

**Sync Service** (`src/main/kotlin/com/example/sync/`)
- `SyncService` - Orchestrates two-phase sync (discovery + extraction). Key methods:
  - `performDiscovery()` — Phase 1: calls `scanner.discoverFiles()` with incremental partial-manifest saves, returns `DiscoveryManifest`
  - `performExtraction(manifest)` — Phase 2: filters `DISCOVERED` entries, processes in chunks of 2 concurrently via `metadataExtractor` directly (not `BatchMetadataExtractor`), persists each chunk immediately, updates manifest status (EXTRACTED + metadataPath, or FAILED) per file
  - `resumeExtraction()` — Loads existing manifest, backfills `metadataPath` for old EXTRACTED entries, then calls `performExtraction()` for remaining `DISCOVERED` entries (resumable after crash)
  - `performFullSync()` — Discovery + extraction in sequence (guarded by `syncInProgress` flag)
  - `performIncrementalSync()` — Discovery + diff against existing manifest + extract only pending entries
  - `diffManifests()` — Compares new vs existing manifest: new files → DISCOVERED, changed (size/mtime) → DISCOVERED, deleted → removed + PDFMetadata deleted, unchanged EXTRACTED → skipped
  - `performSyncWithProgress()` — Flow-based sync with progress events
- Prevents concurrent syncs via `syncInProgress` flag
- `SyncResult` - `@Serializable` data class with timing, counts, errors
- `SyncType` enum: `FULL`, `INCREMENTAL`
- `SyncError` - `@Serializable` data class: `path`, `message`, `timestamp`
- `SyncProgress` - Sealed class hierarchy for streaming sync status updates

### Key Design Patterns

- **Layered Architecture**: Storage → Repository → Service → API
- **3-Tier Caching**: In-Memory (ConcurrentHashMap + indices) → JSON Files → Filesystem
- **Dependency Injection**: Components passed through constructors
- **Async Processing**: Coroutines for all I/O operations
- **Immutable Data**: Kotlin data classes for all models
- **Atomic Writes**: Temp file + rename to prevent corruption
- **Two-Phase Sync**: Discovery (fast filesystem walk, no PDF reads) → persisted manifest → Extraction (slow, resumable, parallel). Server accepts requests between phases.
- **Listener Pattern**: ScanProgressListener (suspend interface) + ExtractionProgressListener for extensible event handling
- **Resource Limits**: File size caps on reads, XMP size caps, PDF parse timeouts, max file count per scan
- **Symlink Safety**: Loop detection in scanner, guarded recursive delete in storage

### Error Handling Patterns

- **Storage Layer**: All exceptions wrapped in `StorageException`. Atomic writes prevent corruption. Path validation prevents directory traversal. File size validated before reading. Recursive delete guards against symlinks escaping base directory. Temp file cleanup failures logged.
- **Repository Layer**: Persist-first ordering (backing store updated before cache on save/delete). All reads and writes mutex-protected for consistency. ConsistencyManager catches and logs during repair.
- **Scanning/Extraction**: MetadataExtractor returns `null` on errors or timeout (60s per PDF). XMP metadata reads capped at 10MB. Streams use `.use{}` for guaranteed cleanup. RepositoryProgressListener counts extraction errors. `ScanError` captures path, message, timestamp. Symlink loops detected via visited directory tracking.
- **Sync Service**: `SyncResult.failed()` creates error result. `finally` block ensures `syncInProgress` cleanup. Manifest updated per-file during extraction (EXTRACTED/FAILED) for crash resumability. `diffManifests()` logs warnings for failed deletions.
- **Configuration**: Validation returns list of errors (doesn't throw). Path expansion handles missing properties.
- **API Layer**: `try-catch` with HTTP status code mapping: 503 if not initialized, 404 for missing resources, 500 for unexpected errors.

**Custom Exceptions**: `ConfigurationException`, `StorageException`

### Data Models

**PDFMetadata** (`com.example.metadata.PDFMetadata`) - Core model, `@Serializable`
```
id, path, fileName, fileSize, pageCount, createdDate, modifiedDate,
title, author, subject, creator, producer, keywords, pdfVersion,
customProperties, contentHash, isEncrypted, isSignedPdf, thumbnailPath, indexedAt
```

**API Response Models** (defined in `Main.kt`):
- `ApiResponse<T>` - Generic wrapper: `success`, `data`, `error`
- `PaginatedResponse<T>` - Paginated wrapper: `data`, `page`, `size`, `total`, `totalPages`
- `SystemStatus` - Repository status + sync state
- `SyncRequest` - `type` field for sync endpoint

**Scanning/Sync Models**:
- `FileStatus` - Enum: `DISCOVERED`, `EXTRACTED`, `FAILED`
- `PDFFileInfo` - Lightweight file record: `path`, `fileName`, `fileSize`, `lastModified`, `discovered`, `status`, `metadataPath` (nullable — set to `"<id>.json"` after successful extraction)
- `DiscoveryManifest` - Persisted manifest: `lastDiscovery`, `scanPaths`, `files` (List<PDFFileInfo>). Stored at `$metadataStoragePath/discovery-manifest.json` (~200 bytes/entry)
- `SyncResult` - Sync outcome: timing, counts (scanned, discovered, extracted, stored, skipped, errors)
- `SyncProgress` - Sealed class: Started, ScanningStarted, ScanningCompleted, ExtractionStarted, Completed, Failed, Error

**Repository Models** (defined in repository classes):
- `ConsistencyReport` - Orphaned data in-memory vs on-disk
- `ValidationResult` - Data integrity issues
- `RepairResult` - Consistency repair outcomes
- `RepositoryMemoryInfo` - Cache statistics (record count, index sizes)
- `RepositoryStatus` - Overall health and metrics
- `RepositoryMetrics` - Performance metrics (avg search time, last persist time)
- `BackupData` - Serializable backup container

### Serialization

- **Framework**: kotlinx-serialization with JSON format
- **Config**: `prettyPrint = true`, `ignoreUnknownKeys = true`
- **Storage Format**: One JSON file per PDF at `$metadataStoragePath/$id.json`. Discovery manifest at `$metadataStoragePath/discovery-manifest.json`
- **API**: Automatic via Ktor ContentNegotiation plugin

## API Endpoints Reference

All API endpoints return JSON wrapped in `ApiResponse<T>`: `{ "success": bool, "data": T?, "error": string? }`.
All endpoints return HTTP 503 if the system is not initialized.

### `GET /` - Health Check
- **Response**: Plain text `"PDF Library - Server is running!"`

### `GET /status` - System Status
- **Response**: `ApiResponse<SystemStatus>` containing repository initialization state, record counts, memory info (index sizes), metrics, and sync-in-progress flag

### `POST /api/sync` - Trigger Sync
- **Request Body**: `{ "type": "full" | "incremental" }`
- **Response**: `ApiResponse<SyncResult>` with filesScanned, filesDiscovered, metadataExtracted, metadataStored, metadataSkipped, duplicatesRemoved, totalErrors, timing durations, error details
- **Behavior**: Returns error if sync already in progress. Full sync runs discovery + extraction for all configured paths. Incremental sync diffs new discovery against existing manifest — only new/changed files are extracted, deleted files are cleaned up, unchanged EXTRACTED files are skipped.

### `GET /api/pdfs` - List PDFs (Paginated)
- **Query Params**: `page` (default 0, min 0), `size` (default 50, range 1-500), `q` (optional search query)
- **Response**: `ApiResponse<PaginatedResponse<PDFMetadata>>` with `data`, `page`, `size`, `total`, `totalPages`
- **Behavior**: If `q` is provided, performs full-text search. Otherwise returns all PDFs paginated.

### `GET /api/pdfs/{id}` - Get PDF by ID
- **Path Param**: `id` - unique PDF metadata ID
- **Response**: `ApiResponse<PDFMetadata>` or HTTP 404

### `GET /api/thumbnails/{id}` - Get PDF Thumbnail
- **Path Param**: `id` - unique PDF metadata ID
- **Response**: PNG image bytes with `Content-Type: image/png`, or HTTP 404 if PDF not found or no thumbnail available

### `GET /api/search` - Advanced Search
- **Query Params**: `q` (general query), `author`, `title`, `property`, `value`
- **Response**: `ApiResponse<List<PDFMetadata>>`
- **Search Priority**:
  1. If `author` provided: case-insensitive substring match on author
  2. If `title` provided: case-insensitive substring match on title
  3. If `property` + `value` provided: custom property key-value match
  4. Otherwise: full-text search using `q` with relevance ranking

## Sync Flow Detail

### Two-Phase Sync Architecture

Sync is split into two phases to allow the server to start accepting requests quickly:

### Phase 1: Discovery (fast, blocks briefly)
```
1. PDFScanner.discoverFiles()
   └─ scanForPDFs() → walkDirectory() recursively for each configured path
      └─ For each directory:
         ├─ Validate files (PDF header check if enabled)
         ├─ Filter by extension, size, exclude patterns
         └─ Emit onFileDiscovered events
2. Wrap results into DiscoveryManifest (all entries status=DISCOVERED)
3. DiscoveryManifestManager.save() → atomic write to discovery-manifest.json
4. Return manifest (server can start accepting requests now)
```

### Phase 2: Extraction (slow, resumable, runs in background)
```
1. Filter manifest for entries with status=DISCOVERED
2. Process in chunks of 2 concurrently (via coroutineScope + async):
   └─ For each file in chunk:
       ├─ MetadataExtractor.extractMetadata()
       │   ├─ Read PDF bytes via StorageProvider
       │   ├─ Load with PDFBox
       │   ├─ DocumentInfoExtractor → standard metadata
       │   ├─ CustomPropertiesExtractor → XMP + custom props
       │   ├─ ContentHashGenerator → SHA-256 hash
       │   ├─ ThumbnailGenerator → PNG thumbnail
       │   └─ On failure: try SecurePDFHandler, then return null
       └─ Return PDFMetadata or null
3. After each chunk resolves (awaitAll), persist immediately:
   ├─ For each successful result: repository.savePDF() + manifestManager.updateFileStatus(EXTRACTED, metadataPath)
   └─ For each failed file: manifestManager.updateFileStatus(FAILED)
4. Return SyncResult with counts and timing
```

### Full Sync (`POST /api/sync` with `{"type": "full"}`)
- Runs Phase 1 → Phase 2 sequentially within `syncInProgress` guard

### Incremental Sync (`POST /api/sync` with `{"type": "incremental"}`)
```
1. Load existing manifest (if any)
2. Run Phase 1 (new discovery)
3. Diff new discovery against existing manifest:
   ├─ New files (path not in existing): status=DISCOVERED
   ├─ Changed files (size or mtime differ): reset to DISCOVERED
   ├─ Deleted files (in existing, not in new): remove + delete PDFMetadata
   └─ Unchanged EXTRACTED files: keep status (skipped)
4. Save merged manifest
5. Run Phase 2 on merged manifest (only DISCOVERED entries processed)
```

### Startup Flow (Main.kt)
```
1. Server marked ready, API starts accepting requests immediately
2. Background coroutine: syncService.resumeExtraction()
   ├─ If manifest found: extract only DISCOVERED entries (crash-safe resume)
   │   Previously extracted files available via repository while extraction runs
   └─ If no manifest: syncService.performFullSync() (discovery + extraction)
```

### Resume After Crash
```
1. syncService.resumeExtraction()
2. Loads existing discovery-manifest.json
3. Processes only entries with status=DISCOVERED (skips EXTRACTED/FAILED)
```

## Project Structure

```
src/main/kotlin/com/example/
├── Main.kt                              # Entry point, server, routing
├── config/
│   ├── AppConfiguration.kt              # Main config data class
│   ├── ConfigurationManager.kt          # Config loading/validation/saving
│   └── ScanConfiguration.kt             # Scan settings data class
├── metadata/
│   ├── PDFMetadata.kt                   # Core PDF metadata model
│   ├── MetadataExtractor.kt             # Main extractor (orchestrates sub-extractors)
│   ├── DocumentInfoExtractor.kt         # Standard PDF info extraction
│   ├── CustomPropertiesExtractor.kt     # Custom + XMP metadata
│   ├── ContentHashGenerator.kt          # SHA-256 content hashing
│   ├── ThumbnailGenerator.kt           # PDF page 0 → PNG thumbnail
│   ├── SecurePDFHandler.kt              # Encrypted PDF handling
│   ├── BatchMetadataExtractor.kt        # Concurrent batch extraction
│   └── MetadataExtractionExample.kt     # Usage example
├── repository/
│   ├── MetadataRepository.kt            # Repository interface
│   ├── JsonRepository.kt                # JSON file persistence
│   ├── InMemoryMetadataRepository.kt    # In-memory cache + indices
│   ├── JsonPersistenceManager.kt        # JSON serialization
│   ├── RepositoryManager.kt             # Lifecycle orchestration
│   ├── ConsistencyManager.kt            # Cache/disk consistency
│   └── SearchEngine.kt                  # Full-text search + ranking
├── scanning/
│   ├── PDFScanner.kt                    # File discovery engine (discoverFiles + scanForPDFs)
│   ├── PDFValidator.kt                  # PDF header validation
│   ├── DuplicateDetector.kt             # Path-based deduplication
│   ├── FileStatus.kt                    # DISCOVERED/EXTRACTED/FAILED enum
│   ├── PDFFileInfo.kt                   # Discovered file data class (with status)
│   ├── DiscoveryManifest.kt             # Manifest data class + DiscoveryManifestManager
│   ├── ScanResult.kt                    # Scan result data class
│   ├── ScanProgress.kt                  # Progress listeners (scan + extraction) + console impl
│   └── RepositoryProgressListener.kt    # Extract + persist during scan (legacy flow)
├── storage/
│   ├── StorageProvider.kt               # Storage interface
│   ├── FileSystemStorage.kt             # Filesystem implementation
│   └── FileMetadata.kt                  # File metadata data class
└── sync/
    └── SyncService.kt                   # Two-phase sync (discovery + extraction)

src/test/kotlin/com/example/
├── MainTest.kt                          # Application endpoint tests
├── SyncIntegrationDemo.kt              # Sync demo
├── config/
│   └── ConfigurationManagerTest.kt      # Config load/save/validate tests
├── metadata/
│   ├── MetadataExtractorTest.kt         # Extraction with mocked storage
│   ├── BatchMetadataExtractorTest.kt    # Batch processing tests
│   ├── ContentHashGeneratorTest.kt      # Hash consistency tests
│   └── ThumbnailGeneratorTest.kt        # Thumbnail generation tests
├── repository/
│   ├── RepositoryIntegrationTest.kt     # Full repo workflow tests
│   └── RepositoryDemo.kt               # Repository usage demo
├── scanning/
│   ├── PDFScannerTest.kt               # Discovery + filtering tests
│   ├── PDFValidatorTest.kt             # Header validation tests
│   ├── DuplicateDetectorTest.kt         # Deduplication tests
│   ├── DiscoveryManifestTest.kt         # Manifest save/load/update round-trip tests
│   ├── DiscoverFilesTest.kt            # discoverFiles() + no-byte-read verification
│   └── ScanIntegrationTest.kt          # Real filesystem integration
└── storage/
    └── FileSystemStorageTest.kt         # Storage ops + security tests
```

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

### Test Patterns Used

- **MockK-based unit tests**: `coEvery` for coroutine mocking (MetadataExtractorTest, BatchMetadataExtractorTest)
- **Custom mock implementations**: Hand-written MockStorageProvider classes (PDFScannerTest, PDFValidatorTest)
- **Integration tests with TempDir**: JUnit `@TempDir` for real filesystem tests (RepositoryIntegrationTest, ScanIntegrationTest, FileSystemStorageTest)
- **Coroutine testing**: `kotlinx.coroutines.test.runTest` for all async tests
- **Edge cases covered**: Empty files, invalid PDFs, non-existent paths, path traversal attempts, duplicate detection, concurrent processing

### Test Categories
- **Unit Tests**: Individual component behavior (extractors, validators, hash generator)
- **Integration Tests**: System interactions with real filesystem (scanning, repository persistence)
- **Demo Classes**: Development examples and usage patterns (SyncIntegrationDemo, RepositoryDemo)

## Configuration

The application uses `config.json` in the project root (created with defaults if missing):
```json
{
    "pdfScanPaths": ["/path/to/pdfs"],
    "metadataStoragePath": "/path/to/metadata",
    "scanning": {
        "recursive": true,
        "maxDepth": 25,
        "excludePatterns": ["*.tmp"],
        "fileExtensions": [".pdf"],
        "validatePdfHeaders": true,
        "followSymlinks": false,
        "maxFileSize": 500000000,
        "maxFiles": 100000
    }
}
```

A `config.json.base` template is also available in the project root.

**Defaults** (when config.json is missing):
- PDF scan paths: `$HOME/Documents/PDFs`, `$HOME/Downloads`
- Metadata storage: `$HOME/.pdf-library/metadata`
- Validation: at least one scan path required, extensions must start with dot, max depth > 0

## Dependencies (build.gradle.kts)

| Category | Library | Version |
|----------|---------|---------|
| Web | ktor-server-core, netty, html-builder, content-negotiation, partial-content | 3.4.0 |
| Serialization | ktor-serialization-kotlinx-json, kotlinx-serialization-json | 3.4.0 / 1.8.0 |
| PDF | pdfbox, pdfbox-tools | 3.0.6 |
| Async | kotlinx-coroutines-core | 1.10.2 |
| DateTime | kotlinx-datetime | 0.7.1 |
| HTML | kotlinx-html-jvm | 0.12.0 |
| Hashing | commons-codec | 1.19.0 |
| Logging | logback-classic | 1.5.31 |
| Test | junit-jupiter, kotlin-test-junit5, ktor-server-tests, mockk, coroutines-test | 5.14.2 / 1.14.9 |

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

## Adding New Features - Quick Reference

### Adding a new API endpoint
1. Define route in `Main.kt` inside `configureRouting()`
2. Follow the `ApiResponse<T>` wrapper pattern for consistent responses
3. Check system readiness with the existing pattern (503 if not initialized)
4. Add `@Serializable` to any new request/response data classes

### Adding a new metadata field
1. Add field to `PDFMetadata` data class in `metadata/PDFMetadata.kt`
2. Extract the value in `MetadataExtractor` (or create a new sub-extractor)
3. Update `SearchEngine` if the field should be searchable
4. Update `InMemoryMetadataRepository` if the field needs an index

### Adding a new storage backend
1. Implement the `StorageProvider` interface in `storage/`
2. Wire it in `Main.kt`'s `configureApplication()` function

### Adding a new repository implementation
1. Implement the `MetadataRepository` interface in `repository/`
2. Wire it in `Main.kt`'s `configureApplication()` function
3. Can be wrapped with `InMemoryMetadataRepository` for caching
