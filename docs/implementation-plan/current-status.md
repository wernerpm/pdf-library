# Current Implementation Status

> Last updated: 2026-03-22

## Overview

| Step | Name | Status |
|------|------|--------|
| 1a | Configuration Module + Storage Abstraction | DONE |
| 1b | PDF Scanner + File Discovery | DONE |
| 1c | Metadata Extraction (PDFBox) | DONE |
| 1d | In-Memory Repository + JSON Persistence | DONE |
| 2 | Two-Phase Sync (Discovery + Extraction) | DONE |
| 3 | Extraction Progress + Full-Text Search + File Watching | DONE |
| 4 | Frontend UI (v1 + improvements 4A/4B/4C) | DONE |
| 2B-1 | diffManifests() SMB protection | DONE |
| 2B-2 | Retry-failed sync type | DONE |
| 2B-3 | Startup incremental scan | DONE |
| 2B-4 | GET /api/manifest endpoint | DONE |
| 2B-5 | Sort/order on GET /api/pdfs | DONE |

---

## What's Built

### Step 1a — Configuration + Storage (DONE)
- `AppConfiguration` / `ScanConfiguration` loaded from `config.json` (JSON, not YAML)
- `ConfigurationManager`: load, validate, save, `~` path expansion
- `FileSystemStorage`: atomic writes (temp + rename), path traversal prevention, 500MB read cap, symlink-safe recursive delete, `createDirectory`
- Full test coverage in `FileSystemStorageTest`, `ConfigurationManagerTest`

### Step 1b — PDF Scanner (DONE)
- `PDFScanner`: recursive walk, extension filtering, size limits, `maxFiles` cap (100k), symlink loop detection via `visitedDirectories` (real path tracking)
- `PDFValidator`: `%PDF` header check (reads file bytes — disabled in config for network volumes via `validatePdfHeaders: false`)
- `DuplicateDetector`: canonical path-based dedup across multiple scan paths
- `ScanProgressListener`: suspend interface (`onDirectoryStarted`, `onFileDiscovered`, `onError`, `onScanCompleted`)
- `ConsoleScanProgressListener`: log-based progress output
- `discoverFiles()`: phase-1 only method — no PDF bytes read, incremental partial manifest saves during scan
- Tests: `PDFScannerTest`, `PDFValidatorTest`, `DuplicateDetectorTest`, `DiscoverFilesTest`

### Step 1c — Metadata Extraction (DONE)
- `MetadataExtractor`: PDFBox 3.0 (`Loader.loadPDF()`), 60s timeout per PDF, returns null on failure
- `DocumentInfoExtractor`: standard PDF info (title, author, subject, creator, producer, dates)
- `CustomPropertiesExtractor`: XMP + custom properties, XMP capped at 10MB
- `ContentHashGenerator`: SHA-256 only (no weak fallbacks)
- `ThumbnailGenerator`: page 0 → PNG at 72 DPI, scaled to 300px width
- `SecurePDFHandler`: graceful handling of encrypted/signed PDFs
- `BatchMetadataExtractor`: sequential chunks via `coroutineScope + async`, concurrency=2
  - **Bug fixed**: original `.flatten().awaitAll()` launched all files concurrently; fixed to chunked processing
- Tests: `MetadataExtractorTest`, `BatchMetadataExtractorTest`, `ContentHashGeneratorTest`, `ThumbnailGeneratorTest`

### Step 1d — Repository + Persistence (DONE)
- `MetadataRepository` interface: `getAllPDFs`, `getPDF`, `savePDF`, `deletePDF`, `search`, `searchByAuthor`, `searchByTitle`, `searchByProperty`, `count`, `loadFromStorage`, `persistToStorage`, `clear`
- `JsonRepository`: one `<id>.json` per PDF at `$metadataStoragePath/<id>.json`
- `InMemoryMetadataRepository`: `ConcurrentHashMap` cache + path/author/title secondary indices, all writes mutex-protected, persist-first ordering
- `JsonPersistenceManager`: serializes/deserializes `PDFMetadata` to individual JSON files
- `ConsistencyManager`: detects and repairs orphaned in-memory vs on-disk entries
- `RepositoryManager`: initialization, shutdown, backup, consistency check
- `SearchEngine`: relevance-scored full-text search (title +3.0, author/filename +2.0, subject/keywords +1.0, custom props +0.5), AND logic
- Tests: `RepositoryIntegrationTest`

### Step 2 — Two-Phase Sync (DONE)
- `FileStatus` enum: `DISCOVERED`, `EXTRACTED`, `FAILED`
- `PDFFileInfo` extended: added `status: FileStatus`, `metadataPath: String?`
- `DiscoveryManifest` + `DiscoveryManifestManager`: load/save (atomic), `updateFileStatus(path, status, metadataPath?)`
- `SyncService`:
  - `performDiscovery()`: phase 1, saves manifest with incremental partial saves
  - `performExtraction(manifest)`: phase 2, inline concurrency=2 chunks, persists each chunk immediately
  - `resumeExtraction()`: loads manifest, backfills old `metadataPath`-less entries, processes `DISCOVERED` only
  - `performFullSync()`: discovery + extraction, guarded by `syncInProgress`
  - `performIncrementalSync()`: discovery + `diffManifests()` diff + extraction of DISCOVERED only
  - `diffManifests()`: new → DISCOVERED, changed (size/mtime) → DISCOVERED, deleted → remove metadata, unchanged EXTRACTED → skip
- **Startup flow**: server ready immediately → `resumeExtraction()` in background → fallback to `performFullSync()` if no manifest
- API: `POST /api/sync {"type": "full"|"incremental"}`, `GET /status` (reports `syncInProgress`)
- Tests: `DiscoveryManifestTest`, `DiscoverFilesTest`, `ScanIntegrationTest`

**Known deviation from plan**: `performExtraction()` bypasses `BatchMetadataExtractor` and does inline chunked coroutines directly. `BatchMetadataExtractor` still exists and is used by legacy `scanAndPersist()` flow.

---

## What's Next

### Step 5 — API Integration Tests

Replace the `MainTest.kt` placeholder with real Ktor `testApplication` tests covering all HTTP endpoints:

- Route existence, status codes, JSON shape (ApiResponse wrapper)
- Pagination math on `GET /api/pdfs`
- Search + sort params on `GET /api/pdfs`
- 404 for missing PDF / missing thumbnail / missing text
- `POST /api/sync` valid and invalid types
- `GET /api/manifest` (404 with no manifest, 200 with manifest)
- `GET /api/stats` structure
- `GET /status` structure
- `GET /` redirect

Test infrastructure: Ktor `testApplication`, mockk for `RepositoryManager` + `SyncService`, real `InMemoryMetadataRepository` for accurate stats/search behavior.

---

## Current Live State

The server is running against:
- Scan paths: configured in `config.json` (includes SMB NAS at `/Volumes/Public/Livros/`)
- ~2686 PDFs discovered, extraction completed (all files processed as of last run)
- Metadata stored at `$metadataStoragePath` as individual `<id>.json` files
- Discovery manifest at `$metadataStoragePath/discovery-manifest.json`
