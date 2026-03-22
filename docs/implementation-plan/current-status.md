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
| 2B-1 | diffManifests() SMB protection | NOT STARTED |
| 2B-2 | Retry-failed sync type | NOT STARTED |
| 2B-3 | Startup incremental scan | NOT STARTED |
| 2B-4 | GET /api/manifest endpoint | NOT STARTED |
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


### Step 4 — Frontend UI (DONE, including 4A/4B/4C)
See [`step-4-frontend.md`](step-4-frontend.md).

All three planned improvements are built:
- **4A**: Numbered page buttons with ellipsis (`getPageSlots()` in `app.js`)
- **4B**: Pagination at top and bottom of grid
- **4C**: "Open PDF" button in modal — serves via `GET /api/pdfs/{id}/file` HTTP proxy (better than `file://` URL)

### Step 3 — Extraction Progress + Full-Text Search + File Watching (DONE)
See [`step-3-progress-search-filewatcher.md`](step-3-progress-search-filewatcher.md).

### Unplanned Feature: Scheduled Sync (DONE)
`ScanConfiguration.syncIntervalMinutes` (default 0 = disabled) triggers `performIncrementalSync()` every N minutes via a background loop in `Main.kt`. This is a practical alternative to 2B-3 for network volumes where `WatchService` doesn't work.

### Backend Improvement Phase (step 2B)
See [`step-2b-robustness.md`](step-2b-robustness.md). Eligible to start now:

- **2B-1** (HIGH): `diffManifests()` SMB protection — skip deletions when a scan path returns 0 results but had N existing entries
- **2B-2** (MEDIUM): `POST /api/sync {"type": "retry-failed"}` — reset FAILED entries and re-extract
- **2B-3** (MEDIUM): Startup incremental scan after `resumeExtraction()` (depends on 2B-1; scheduled sync via `syncIntervalMinutes` is a workaround)
- **2B-4** (MEDIUM): `GET /api/manifest` endpoint — discovered/extracted/failed counts + failed file paths
- **2B-5** (LOW): DONE — sort/order params on `GET /api/pdfs`

> **Note**: 2B-1 is the only item with a real correctness risk (data loss on NAS hiccup). Implement first.

---

## Current Live State

The server is running against:
- Scan paths: configured in `config.json` (includes SMB NAS at `/Volumes/Public/Livros/`)
- ~2686 PDFs discovered, extraction completed (all files processed as of last run)
- Metadata stored at `$metadataStoragePath` as individual `<id>.json` files
- Discovery manifest at `$metadataStoragePath/discovery-manifest.json`
