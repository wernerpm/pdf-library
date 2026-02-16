# Step 2: Two-Phase Sync (Discovery + Extraction)

> **Status**: NOT STARTED

## Problem

The current sync flow (`scanAndPersist`) is monolithic: for every directory traversed, it immediately reads each PDF into memory, parses it with PDFBox, generates a SHA-256 hash, renders a thumbnail, and persists the metadata. This means startup blocks on the most expensive operation (PDF parsing) before the application has any awareness of what's on disk.

For a library with thousands of PDFs, this can take minutes. During that time the API returns 503 for everything, and if the process crashes mid-sync, all progress is lost.

## Goal

Split sync into two distinct phases:

1. **Discovery** (fast) — Recursive filesystem listing. Produces a persisted manifest of all known PDF files with their paths, sizes, and modification times. No PDF bytes are read. This completes in seconds even for large libraries.

2. **Extraction** (slow, parallelisable, resumable) — Iterates the discovery manifest. For each entry not yet extracted, opens the PDF, extracts metadata, generates thumbnail, and persists `PDFMetadata`. Updates the manifest entry status as it goes. Can be interrupted and resumed.

## Data Model

### Extend `PDFFileInfo` (the discovery record)

`PDFFileInfo` already has exactly the right shape. Changes needed:

```kotlin
@Serializable
data class PDFFileInfo(
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Instant?,
    val discovered: Instant = Clock.System.now(),
    val status: FileStatus = FileStatus.DISCOVERED
)

@Serializable
enum class FileStatus {
    DISCOVERED,   // Found on disk, not yet processed
    EXTRACTED,    // Metadata successfully extracted and persisted
    FAILED        // Extraction attempted but failed
}
```

`PDFMetadata` remains unchanged — it is the "fully indexed" entity. `PDFFileInfo` becomes the lightweight "we know this file exists" entity.

### Discovery Manifest

A single JSON file at `$metadataStoragePath/discovery-manifest.json`:

```json
{
  "lastDiscovery": "2026-02-16T10:00:00Z",
  "scanPaths": ["/Users/foo/Documents/PDFs"],
  "files": [
    {
      "path": "/Users/foo/Documents/PDFs/paper.pdf",
      "fileName": "paper.pdf",
      "fileSize": 1048576,
      "lastModified": "2025-11-01T08:30:00Z",
      "discovered": "2026-02-16T10:00:01Z",
      "status": "DISCOVERED"
    }
  ]
}
```

This is intentionally a single file (not one-per-entry like `PDFMetadata`) because:
- The manifest is written/read as a whole during discovery
- It's small (~200 bytes per entry, so 10k PDFs = ~2MB)
- Atomic write (temp + rename) keeps it crash-safe

## Files to Create

| File | Purpose |
|---|---|
| `scanning/FileStatus.kt` | `FileStatus` enum |
| `scanning/DiscoveryManifest.kt` | `DiscoveryManifest` data class + `DiscoveryManifestManager` (load/save/update) |

## Files to Modify

| File | Changes |
|---|---|
| `scanning/PDFFileInfo.kt` | Add `@Serializable`, add `status: FileStatus` field |
| `scanning/PDFScanner.kt` | Extract `discoverFiles()` method (phase 1 only, no extraction) |
| `scanning/RepositoryProgressListener.kt` | Adapt to work with manifest-driven extraction |
| `sync/SyncService.kt` | Split `performFullSync` / `performIncrementalSync` into discovery + extraction phases |
| `Main.kt` | Startup calls discovery first, then launches extraction (possibly in background) |
| `metadata/BatchMetadataExtractor.kt` | Accept manifest entries, update status after each extraction |

## Implementation Details

### 1. `DiscoveryManifest` + `DiscoveryManifestManager`

```kotlin
@Serializable
data class DiscoveryManifest(
    val lastDiscovery: Instant,
    val scanPaths: List<String>,
    val files: List<PDFFileInfo>
)

class DiscoveryManifestManager(
    private val storageProvider: StorageProvider,
    private val manifestPath: String  // e.g. "$metadataStoragePath/discovery-manifest.json"
) {
    suspend fun load(): DiscoveryManifest?
    suspend fun save(manifest: DiscoveryManifest)
    suspend fun updateFileStatus(path: String, status: FileStatus)
}
```

- `save()` uses atomic write (write to temp file, rename) — same pattern as `FileSystemStorage`
- `updateFileStatus()` loads, patches, saves — acceptable since the file is small

### 2. `PDFScanner.discoverFiles()` — Phase 1

New method alongside existing `scanForPDFs()`:

```kotlin
suspend fun discoverFiles(): DiscoveryManifest
```

- Same recursive traversal logic as `scanForPDFs()` (reuse `walkDirectory`)
- Same dedup, symlink safety, exclude patterns, file count limits
- Returns a `DiscoveryManifest` with all entries in `DISCOVERED` status
- Does NOT read any PDF bytes, does NOT invoke `MetadataExtractor`
- Notifies progress listeners for UI feedback

### 3. `SyncService` — Two-Phase Orchestration

```kotlin
suspend fun performFullSync(): SyncResult {
    // Phase 1: Discovery
    val manifest = scanner.discoverFiles()
    manifestManager.save(manifest)
    logger.info("Discovery complete: ${manifest.files.size} PDFs found")

    // Phase 2: Extraction
    val pending = manifest.files.filter { it.status == FileStatus.DISCOVERED }
    val extracted = extractAll(pending)
    return buildSyncResult(manifest, extracted)
}

suspend fun resumeExtraction(): SyncResult {
    // Load existing manifest, process only DISCOVERED entries
    val manifest = manifestManager.load() ?: return SyncResult.noManifest()
    val pending = manifest.files.filter { it.status == FileStatus.DISCOVERED }
    val extracted = extractAll(pending)
    return buildSyncResult(manifest, extracted)
}
```

- `extractAll()` uses `BatchMetadataExtractor` with configurable parallelism
- After each successful extraction: `manifestManager.updateFileStatus(path, EXTRACTED)`
- After each failed extraction: `manifestManager.updateFileStatus(path, FAILED)`
- This makes the process resumable — if the app restarts, `resumeExtraction()` picks up from where it left off

### 4. Incremental Sync

For incremental sync, the discovery phase compares the new filesystem listing against the existing manifest:

- **New files** (path not in manifest): add as `DISCOVERED`
- **Changed files** (path exists but `fileSize` or `lastModified` differ): reset to `DISCOVERED`
- **Deleted files** (in manifest but not on disk): remove from manifest, delete `PDFMetadata`
- **Unchanged files** with `EXTRACTED` status: skip entirely

### 5. `Main.kt` Startup Change

```kotlin
// Phase 1: Fast discovery (blocks startup briefly)
logger.info("Discovering PDF files...")
val manifest = syncService.performDiscovery()
logger.info("Found ${manifest.files.size} PDFs")

// Phase 2: Background extraction (server is already accepting requests)
launch {
    logger.info("Starting background metadata extraction...")
    val result = syncService.performExtraction(manifest)
    logger.info("Extraction complete: ${result.summarize()}")
}

logger.info("PDF Library Server ready! (extraction running in background)")
```

The server becomes responsive almost immediately. The API can serve files from the previous extraction run (loaded from persisted `PDFMetadata` JSON files) while new/changed files are being processed in the background.

### 6. API Implications

- `GET /api/pdfs` continues to work from the `MetadataRepository` (which loads persisted `PDFMetadata` from disk at startup). Files discovered but not yet extracted simply don't appear in search results until extraction completes.
- `GET /status` should report extraction progress (e.g. `extractionProgress: { total: 1500, extracted: 800, pending: 650, failed: 50 }`).
- `POST /api/sync` triggers a new discovery + extraction cycle.

## Testing Strategy

- Unit test `DiscoveryManifestManager`: save/load/update round-trips
- Unit test `discoverFiles()`: verify no PDF bytes are read (mock `StorageProvider`, assert `read()` is never called)
- Unit test incremental diff logic: new files, changed files, deleted files, unchanged files
- Unit test resumability: create manifest with mixed statuses, verify only `DISCOVERED` entries are processed
- Integration test: full two-phase sync with real temp directory containing test PDFs

## Verification

1. `./gradlew clean build` — compiles and all tests pass
2. Discovery phase completes without reading any PDF bytes (verify via logs or metrics)
3. Extraction phase is resumable — kill mid-extraction, restart, only remaining files are processed
4. Incremental sync correctly detects new/changed/deleted files
