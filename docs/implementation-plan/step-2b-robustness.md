# Backend Improvement Phase: Resilience, Retry, and API Completeness

> **Status**: NOT STARTED
> **When**: After frontend implementation
> **Note**: These are correctness and UX improvements discovered during the step-2 review. The SMB data-loss bug (2B-1) is the only item with a correctness impact — it can be moved earlier if needed.

## Context

After step 2 (two-phase sync), the core pipeline works but has several correctness and usability gaps:

- `diffManifests()` treats a temporarily-unavailable network volume as "all files deleted", destroying metadata
- Failed files are permanently stuck as `FAILED` with no retry mechanism
- On restart, newly-added files are invisible until the user manually triggers a sync
- The list API (`GET /api/pdfs`) only shows fully-extracted PDFs — discovered and failed files are invisible
- The list API has no sorting options

---

## Implementation Order

**2B-1 → 2B-2 → 2B-3 → 2B-4 → 2B-5** (each is largely independent; 2B-1 is highest priority)

---

## Feature 2B-1: Protect `diffManifests()` from Network Volume Unavailability (HIGH)

### Problem

When a network volume (SMB/NFS) is temporarily unavailable, `PDFScanner.discoverFiles()` returns 0 files for that path. `diffManifests()` then treats all existing entries for that path as deleted and calls `repository.deletePDF()` for each one. If the user has 2600 books on a NAS and it hiccups for a second, the entire library metadata is wiped.

### Fix

In `SyncService.diffManifests()`, before deleting anything, check whether each scan path that appears to have "lost all its files" actually returned 0 results in the new discovery. If a path had N entries in the existing manifest and now has 0, skip deletions for that path and log a warning.

### Files to Modify

**`sync/SyncService.kt`** — In `diffManifests()`, group deleted paths by their scan path prefix. For each scan path from `existing.scanPaths`:
- Count how many files the *new* discovery found under that path
- If `newCountForPath == 0` but `existingCountForPath > 0`: skip all deletions for that path, log a warning like `"Scan path $path returned 0 results in new discovery but had $existingCountForPath entries — skipping deletions (volume may be temporarily unavailable)"`
- Only delete entries for paths where the new scan found at least one file (proving the volume was reachable)

```kotlin
// Group existing paths by which scan path they belong to
for (scanPath in existing.scanPaths) {
    val existingInPath = existingByPath.keys.filter { it.startsWith(scanPath) }
    val newInPath = newByPath.keys.filter { it.startsWith(scanPath) }
    if (existingInPath.isNotEmpty() && newInPath.isEmpty()) {
        logger.warn("diffManifests: scan path '$scanPath' returned 0 results but had ${existingInPath.size} existing entries — skipping deletions (volume may be temporarily unavailable)")
        // Add these paths to a skip set
    }
}
```

Then filter `deletedPaths` to exclude the skip set before calling `repository.deletePDF()`.

### Tests to Add

**`sync/DiffManifestsTest.kt`** (new file):
- Empty new scan path with existing entries → no deletions, warning logged
- Partial unavailability (one of two paths returns 0) → only safe path's deletions proceed
- Normal deletion (file genuinely removed from available path) → still deleted
- Changed file on available path → still reset to DISCOVERED

---

## Feature 2B-2: FAILED File Retry (MEDIUM)

### Problem

Files that fail extraction are marked `FAILED` in the manifest and never retried. Transient errors (e.g. SMB timeout during extraction) permanently exclude a file. There is no way to reset them without deleting the manifest.

### New API Endpoint

`POST /api/sync` with `{"type": "retry-failed"}` — resets all `FAILED` entries to `DISCOVERED` in the manifest, then runs extraction.

### Files to Modify

**`sync/SyncService.kt`** — Add `performRetryFailed()`:

```kotlin
suspend fun performRetryFailed(): SyncResult {
    if (!syncStart()) return SyncResult.alreadyInProgress(SyncType.FULL)
    return try {
        val manifest = manifestManager.load()
            ?: return SyncResult.failed(SyncType.FULL, Clock.System.now(),
                Exception("No manifest found"))
        val failedCount = manifest.files.count { it.status == FileStatus.FAILED }
        if (failedCount == 0) {
            logger.info("No FAILED entries to retry")
            return performExtraction(manifest)
        }
        logger.info("Resetting $failedCount FAILED entries to DISCOVERED for retry")
        val resetManifest = manifest.copy(
            files = manifest.files.map { file ->
                if (file.status == FileStatus.FAILED)
                    file.copy(status = FileStatus.DISCOVERED, metadataPath = null)
                else file
            }
        )
        manifestManager.save(resetManifest)
        performExtraction(resetManifest)
    } finally {
        syncFinish()
    }
}
```

**`Main.kt`** — Add `"retry-failed"` case in the `/api/sync` route handler.

**`SyncType` enum** — Add `RETRY_FAILED` value.

### Tests to Add

- `performRetryFailed()` resets FAILED → DISCOVERED and calls extraction
- `performRetryFailed()` with no FAILED entries returns early with correct counts
- `POST /api/sync {"type": "retry-failed"}` route integration test

---

## Feature 2B-3: Startup Incremental Scan (MEDIUM)

### Problem

On restart, `resumeExtraction()` only processes entries already in the manifest as `DISCOVERED`. Any PDFs added to disk since the last sync are invisible until the user manually triggers `POST /api/sync`. This is especially problematic for the SMB library use case, where files are added externally.

### Fix

Change the startup flow in `Main.kt`: after `resumeExtraction()` finishes, run an incremental sync to pick up files added since the last manifest save.

**`Main.kt`** — Update the background launch block:

```kotlin
launch {
    logger.info("Starting background metadata sync...")
    val resumeResult = syncService.resumeExtraction()
    if (resumeResult.errors.any { it.path == "MANIFEST" }) {
        // First boot — no manifest yet
        logger.info("No manifest found — performing initial full sync...")
        val fullResult = syncService.performFullSync()
        logger.info("Initial sync complete: ${fullResult.summarize()}")
    } else {
        logger.info("Resume extraction complete: ${resumeResult.summarize()}")
        // Pick up any files added since the manifest was last saved
        logger.info("Running incremental scan to pick up new files...")
        val incrResult = syncService.performIncrementalSync()
        logger.info("Incremental scan complete: ${incrResult.summarize()}")
    }
}
```

This adds a second filesystem walk on every restart, which is fast (no PDF bytes read). On a 2600-file NAS library, discovery takes a few seconds.

### Dependency

Requires Feature 2B-1 first — the incremental scan on startup runs `diffManifests()`, which must be safe against SMB unavailability before this is enabled.

### Tests to Add

- Startup with existing manifest: verify incremental scan runs after resume
- Startup with new file added after manifest: verify new file appears in repository after startup

---

## Feature 2B-4: Manifest Status in API (MEDIUM)

### Problem

`GET /api/pdfs` only returns fully-extracted PDFs from the in-memory repository. Files stuck as `DISCOVERED` or `FAILED` in the manifest are completely invisible to API consumers. Users have no way to see what failed or what's pending.

### New Endpoint

`GET /api/manifest` — Returns manifest-level counts and the failed file list.

Response model (add to `Main.kt`):

```kotlin
@Serializable
data class ManifestStatus(
    val lastDiscovery: Instant?,
    val totalFiles: Int,
    val extracted: Int,
    val pending: Int,      // DISCOVERED
    val failed: Int,
    val failedFiles: List<String>  // paths of FAILED entries
)
```

**`Main.kt`**:
```kotlin
get("/api/manifest") {
    val manifest = syncService.loadManifest()
    if (manifest == null) {
        call.respond(HttpStatusCode.NotFound, ApiResponse.error("No manifest found"))
        return@get
    }
    val status = ManifestStatus(
        lastDiscovery = manifest.lastDiscovery,
        totalFiles = manifest.files.size,
        extracted = manifest.files.count { it.status == FileStatus.EXTRACTED },
        pending = manifest.files.count { it.status == FileStatus.DISCOVERED },
        failed = manifest.files.count { it.status == FileStatus.FAILED },
        failedFiles = manifest.files.filter { it.status == FileStatus.FAILED }.map { it.path }
    )
    call.respond(HttpStatusCode.OK, ApiResponse.success(status))
}
```

**`sync/SyncService.kt`** — Add `suspend fun loadManifest(): DiscoveryManifest? = manifestManager.load()`.

### Tests to Add

- `GET /api/manifest` with no manifest → 404
- `GET /api/manifest` with mixed DISCOVERED/EXTRACTED/FAILED → correct counts
- `failedFiles` list contains correct paths

---

## Feature 2B-5: Sorting on `GET /api/pdfs` (LOW)

### Problem

The list endpoint supports `page`/`size`/`q` but no sort parameter. For a library of thousands of books, sorting by title, author, or indexed date is basic expected functionality.

### New Query Param

`GET /api/pdfs?sort=title|author|indexedAt|fileSize&order=asc|desc`

Defaults: `sort=indexedAt`, `order=desc` (most recently indexed first).

### Files to Modify

**`Main.kt`** — Parse `sort` and `order` params, apply to the paginated result list before slicing:

```kotlin
val sort = call.request.queryParameters["sort"] ?: "indexedAt"
val order = call.request.queryParameters["order"] ?: "desc"
val sorted = when (sort) {
    "title" -> allPdfs.sortedBy { it.title?.lowercase() ?: it.fileName.lowercase() }
    "author" -> allPdfs.sortedBy { it.author?.lowercase() ?: "" }
    "fileSize" -> allPdfs.sortedBy { it.fileSize }
    "indexedAt" -> allPdfs.sortedBy { it.indexedAt }
    else -> allPdfs
}
val result = if (order == "asc") sorted else sorted.reversed()
```

Apply this sort to both the paginated (`getAllPDFs`) and search (`search`) branches of the handler.

**AGENTS.md** — Update `GET /api/pdfs` docs to include `sort` and `order` params.

### Tests to Add

- Sort by title asc/desc
- Sort by author
- Sort by indexedAt (default)
- Invalid sort field falls back to default

---

## New API Endpoints Summary

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/manifest` | GET | Manifest counts: total/extracted/pending/failed + failed file paths |

## Modified Endpoints Summary

| Endpoint | Change |
|----------|--------|
| `POST /api/sync` | Add `"retry-failed"` type |
| `GET /api/pdfs` | Add `sort` and `order` query params |

## All Modified Files

| File | Changes |
|------|---------|
| `sync/SyncService.kt` | `diffManifests()` SMB guard, `performRetryFailed()`, `loadManifest()` getter |
| `Main.kt` | Startup incremental scan, `/api/manifest` endpoint, `retry-failed` sync type, sort params |
| `sync/SyncType.kt` (or inline enum) | Add `RETRY_FAILED` value |

## Testing Strategy

| File | Covers |
|------|--------|
| `sync/DiffManifestsTest.kt` | SMB unavailability guard, selective deletion |
| `sync/SyncServiceRetryTest.kt` | `performRetryFailed()` scenarios |
| `MainManifestApiTest.kt` | `/api/manifest` endpoint |
| `MainSortTest.kt` | `GET /api/pdfs` sort/order params |

## Verification

1. `./gradlew clean build` — all tests pass
2. Disconnect NAS, run incremental sync → no metadata deleted, warning logged
3. Reconnect NAS, run incremental sync → files reappear correctly
4. Mark some files FAILED, call `POST /api/sync {"type": "retry-failed"}` → they retry
5. Add a PDF to disk, restart server → file appears without manual sync trigger
6. `GET /api/manifest` → shows correct counts including failed files
7. `GET /api/pdfs?sort=title&order=asc` → sorted correctly
