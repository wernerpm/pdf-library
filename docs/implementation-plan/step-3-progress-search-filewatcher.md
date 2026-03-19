# Step 3: Extraction Progress, Full-Text Search, File Watching

> **Status**: NOT STARTED

## Context

After step 2 (two-phase sync), the app has a solid discovery + extraction pipeline, but lacks:
- Visibility into extraction progress (`/status` only reports `syncInProgress: Boolean`)
- Full-text PDF content search (search only covers metadata fields like title, author, keywords)
- Automatic re-sync when files change on disk (requires manual `POST /api/sync`)

This step adds all three as backend improvements. No new external dependencies — PDFBox already provides `PDFTextStripper`, and `java.nio.file.WatchService` is JDK built-in.

---

## Implementation Order

**3A → 3B → 3C** (each builds on the previous)

1. **3A: Extraction Progress + Library Stats** — simplest, self-contained
2. **3B: Full-Text PDF Search** — changes MetadataExtractor return type, adds TextContentStore
3. **3C: File Watching + Auto-Sync** — integrates with SyncService, depends on everything else working

---

## Feature 3A: Extraction Progress + Library Stats

### Problem

The current `/status` endpoint returns only `syncInProgress: Boolean` and repository metadata. During the two-phase sync (step 2), there is no visibility into extraction progress (how many files processed, how many remaining, current file). There is also no `/api/stats` endpoint for library analytics.

### New Files

**`src/main/kotlin/com/example/sync/ExtractionProgress.kt`**

```kotlin
@Serializable
data class ExtractionProgress(
    val phase: ExtractionPhase,
    val totalFiles: Int,
    val processedFiles: Int,
    val successfulFiles: Int,
    val failedFiles: Int,
    val currentFile: String?,
    val startTime: Instant?
) {
    val percentComplete: Double
        get() = if (totalFiles > 0) (processedFiles.toDouble() / totalFiles * 100) else 0.0
    val pendingFiles: Int
        get() = totalFiles - processedFiles
}

@Serializable
enum class ExtractionPhase {
    IDLE, DISCOVERING, EXTRACTING, COMPLETED, FAILED
}
```

**`src/main/kotlin/com/example/repository/LibraryStats.kt`**

```kotlin
@Serializable
data class LibraryStats(
    val totalPdfs: Int,
    val totalPages: Int,
    val totalSizeBytes: Long,
    val averagePageCount: Double,
    val averageFileSizeBytes: Long,
    val oldestPdf: Instant?,
    val newestPdf: Instant?,
    val topAuthors: List<AuthorCount>,
    val encryptedCount: Int,
    val signedCount: Int,
    val withThumbnailCount: Int,
    val pdfVersionDistribution: Map<String, Int>,
    val computedAt: Instant
)

@Serializable
data class AuthorCount(
    val author: String,
    val count: Int
)
```

### Files to Modify

**`SyncService.kt`** — Add `@Volatile var extractionProgress` field (default IDLE). Add `getExtractionProgress()` getter. Switch `performExtraction()` from `batchExtractor.extractBatch()` to `extractWithDetailedProgress()` for per-file callbacks. Track counts in the non-suspend callback, persist to manifest/repo after the batch completes. Update `performDiscovery()` → phase=DISCOVERING, extraction → EXTRACTING, completion → COMPLETED.

**`InMemoryMetadataRepository.kt`** — Add `suspend fun computeStats(): LibraryStats` that iterates cache values and aggregates on-demand (no caching needed — iterating thousands of in-memory entries is sub-millisecond).

```kotlin
suspend fun computeStats(): LibraryStats = mutex.withLock {
    val allPdfs = cache.values
    val authorCounts = allPdfs.mapNotNull { it.author }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(10)
        .map { AuthorCount(it.key, it.value) }

    LibraryStats(
        totalPdfs = allPdfs.size,
        totalPages = allPdfs.sumOf { it.pageCount },
        totalSizeBytes = allPdfs.sumOf { it.fileSize },
        averagePageCount = if (allPdfs.isNotEmpty()) allPdfs.map { it.pageCount }.average() else 0.0,
        averageFileSizeBytes = if (allPdfs.isNotEmpty()) allPdfs.sumOf { it.fileSize } / allPdfs.size else 0,
        oldestPdf = allPdfs.mapNotNull { it.createdDate }.minOrNull(),
        newestPdf = allPdfs.mapNotNull { it.createdDate }.maxOrNull(),
        topAuthors = authorCounts,
        encryptedCount = allPdfs.count { it.isEncrypted },
        signedCount = allPdfs.count { it.isSignedPdf },
        withThumbnailCount = allPdfs.count { it.thumbnailPath != null },
        pdfVersionDistribution = allPdfs.mapNotNull { it.pdfVersion }.groupingBy { it }.eachCount(),
        computedAt = Clock.System.now()
    )
}
```

**`Main.kt`** — Enhance `SystemStatus` to include `extractionProgress: ExtractionProgress?`. Add `GET /api/stats` endpoint.

---

## Feature 3B: Full-Text PDF Search

### Problem

Search only covers metadata fields (title, author, subject, keywords, filename, custom properties). Users cannot search for content inside PDFs — text within pages, annotations, or form fields.

### Storage Decision

Store text in **separate per-PDF `.txt` files** at `$metadataStoragePath/text-content/<id>.txt`. This keeps PDFMetadata JSON small, avoids bloating the in-memory cache, and maintains backward compatibility with existing serialized JSON. A separate `ConcurrentHashMap<String, String>` text index is loaded at startup alongside metadata.

Storage layout:
```
$metadataStoragePath/
  <id>.json              # existing PDFMetadata JSON
  text-content/
    <id>.txt             # extracted plain text, one file per PDF
  discovery-manifest.json
```

### New Files

**`src/main/kotlin/com/example/metadata/TextContentExtractor.kt`**

```kotlin
class TextContentExtractor {
    fun extractText(document: PDDocument, maxChars: Int = 500_000): String? {
        return try {
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            if (text.isBlank()) null
            else text.take(maxChars)
        } catch (e: Exception) {
            logger.warn("Failed to extract text content", e)
            null
        }
    }
}
```

**`src/main/kotlin/com/example/metadata/ExtractionResult.kt`**

```kotlin
data class ExtractionResult(
    val metadata: PDFMetadata,
    val textContent: String?
)
```

**`src/main/kotlin/com/example/repository/TextContentStore.kt`**

```kotlin
class TextContentStore(
    private val storageProvider: StorageProvider,
    private val basePath: String
) {
    private val textIndex = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()
    private val textDir = "text-content"

    suspend fun save(id: String, textContent: String)
    suspend fun get(id: String): String?
    suspend fun delete(id: String)
    suspend fun loadAll()  // bulk load at startup
    fun searchContent(query: String): Set<String>  // returns matching PDF IDs
    fun count(): Int
}
```

The `searchContent()` method uses AND logic across whitespace-separated terms, case-insensitive:
```kotlin
fun searchContent(query: String): Set<String> {
    val terms = query.lowercase().split("\\s+".toRegex())
    return textIndex.entries
        .filter { (_, text) ->
            val lower = text.lowercase()
            terms.all { term -> lower.contains(term) }
        }
        .map { it.key }
        .toSet()
}
```

### Files to Modify

**`MetadataExtractor.kt`** — Add `TextContentExtractor` field. Change return type of `extractMetadata()` to `ExtractionResult?`. In `buildPDFMetadata()`, also call `textContentExtractor.extractText(document)` and return both metadata and text.

**`PDFMetadata.kt`** — Add `val hasTextContent: Boolean = false` (default preserves backward compat with existing JSON).

**`BatchMetadataExtractor.kt`** — Update all three methods (`extractBatch`, `extractWithProgress`, `extractWithDetailedProgress`) to return `List<ExtractionResult>` instead of `List<PDFMetadata>`.

**`SyncService.kt`** — Construct `TextContentStore` internally. After extraction: save text content for each result with non-null textContent. In `diffManifests()` deletion handling: also call `textContentStore.delete(id)`.

**`SearchEngine.kt`** — Add `textContentMatchIds: Set<String> = emptySet()` parameter to `searchByQuery()`. In filtering: match if metadata matches OR id is in textContentMatchIds. In scoring: add +0.5 relevance score bonus for content matches.

```kotlin
fun searchByQuery(
    metadata: Collection<PDFMetadata>,
    query: String,
    textContentMatchIds: Set<String> = emptySet()
): List<PDFMetadata> {
    val searchTerms = query.lowercase().split("\\s+".toRegex())
    return metadata.filter { pdf ->
        searchTerms.all { term -> matchesTerm(pdf, term) }
            || pdf.id in textContentMatchIds
    }.sortedByDescending { pdf ->
        calculateRelevanceScore(pdf, searchTerms, pdf.id in textContentMatchIds)
    }
}
```

**`InMemoryMetadataRepository.kt`** — Add optional `TextContentStore?` constructor parameter. In `search()`: call `textContentStore?.searchContent(query)` to get matching IDs, pass to `searchEngine.searchByQuery(..., textContentMatchIds)`. In `loadFromStorage()`: also call `textContentStore?.loadAll()`.

**`ScanConfiguration.kt`** — Add:
```kotlin
val extractTextContent: Boolean = true,
val maxTextContentChars: Int = 500_000
```

**`RepositoryProgressListener.kt`** — Update to work with `ExtractionResult` instead of `PDFMetadata` (for backward compat with legacy `scanAndPersist` flow).

**`Main.kt`** — Initialize `TextContentStore` during startup. Pass to `InMemoryMetadataRepository` and `SyncService`. Add `GET /api/pdfs/{id}/text` endpoint returning plain text.

### Memory Considerations

For 10,000 PDFs averaging 50KB of text each, the in-memory text index would consume ~500MB. The `maxTextContentChars` limit mitigates this. For very large libraries, a future optimization could use disk-backed search.

---

## Feature 3C: File Watching + Auto-Sync

### Problem

Sync is manual via `POST /api/sync` or at startup. Users must manually trigger sync when PDFs are added, modified, or deleted outside the application.

### New Files

**`src/main/kotlin/com/example/sync/FileWatcher.kt`**

```kotlin
class FileWatcher(
    private val scanPaths: List<String>,
    private val debounceMs: Long = 2000L,
    private val onChangesDetected: suspend (List<FileChange>) -> Unit
) {
    suspend fun start(scope: CoroutineScope)  // register dirs, start watch loop
    fun stop()                                  // cancel jobs, close WatchService
}

@Serializable
data class FileChange(
    val path: String,
    val type: FileChangeType,
    val timestamp: Instant
)

@Serializable
enum class FileChangeType { CREATED, MODIFIED, DELETED }
```

Design details:
- Uses `java.nio.file.WatchService` (JDK built-in, no new dependencies)
- Registers all scan path directories recursively with ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE
- Watch loop polls with 1-second timeout, filters for `.pdf` files and directories
- Auto-registers new subdirectories when ENTRY_CREATE fires for a directory
- Debounce: after each event, resets a timer. When timer fires after `debounceMs`, drains pending queue and dispatches
- OVERFLOW events are ignored (treated as "something changed, do a full incremental sync")

### Files to Modify

**`SyncService.kt`** — Add `private var fileWatcher: FileWatcher?`. Add methods:
- `fun startFileWatching(scope: CoroutineScope)` — creates and starts FileWatcher
- `fun stopFileWatching()` — stops FileWatcher
- `private suspend fun handleFileChanges(changes: List<FileChange>)` — deduplicates changes by path (keep latest), handles deletions directly (`repository.deletePDF()` + `textContentStore.delete()`), triggers `performIncrementalSync()` for creates/modifies. If sync already in progress, the changes are naturally picked up on the next sync (via `syncStart()` guard).

**`ScanConfiguration.kt`** — Add:
```kotlin
val enableFileWatching: Boolean = false,
val watchDebounceMs: Long = 2000
```
Default `false` so existing deployments are unaffected.

**`Main.kt`** — After background extraction launch, conditionally start file watching:
```kotlin
if (config.scanning.enableFileWatching) {
    syncService.startFileWatching(this@launch)
}
```

**`SystemStatus`** — Add `fileWatchingEnabled: Boolean`.

### macOS and Network Volume Notes

`WatchService` on macOS uses polling (not native FSEvents), so events may be delayed up to ~10 seconds. Acceptable for local paths.

**Important**: `WatchService` does not work on network volumes (SMB/NFS/AFP). Events are never delivered for paths under `/Volumes/...` or other mounted network shares. If any configured scan paths are on a NAS or network drive, file watching will silently do nothing for those paths.

For network volume use cases, a **scheduled incremental sync** is the practical alternative:

**`ScanConfiguration.kt`** — Add alongside file watching config:
```kotlin
val syncIntervalMinutes: Int = 0  // 0 = disabled; >0 = run incremental sync every N minutes
```

**`Main.kt`** — After starting the file watcher, also start a scheduled sync coroutine if configured:
```kotlin
val intervalMinutes = config.scanning.syncIntervalMinutes
if (intervalMinutes > 0) {
    launch {
        while (isActive) {
            delay(intervalMinutes * 60_000L)
            logger.info("Scheduled incremental sync starting (every ${intervalMinutes}m)...")
            val result = syncService.performIncrementalSync()
            logger.info("Scheduled sync complete: ${result.summarize()}")
        }
    }
}
```

File watching and scheduled sync can be enabled simultaneously — they complement each other (file watching handles local paths in near-real-time, scheduled sync handles network volumes).

---

## New API Endpoints

| Endpoint | Method | Response |
|----------|--------|----------|
| `/api/stats` | GET | `ApiResponse<LibraryStats>` — library analytics |
| `/api/pdfs/{id}/text` | GET | Plain text content (`text/plain`), 404 if unavailable |

### Modified Endpoints

| Endpoint | Change |
|----------|--------|
| `GET /status` | Now includes `extractionProgress` and `fileWatchingEnabled` |
| `GET /api/search?q=...` | Transparently searches text content alongside metadata |
| `POST /api/sync` | Incremental sync also handles text content deletion/creation |

---

## All New Files

| File | Purpose |
|------|---------|
| `sync/ExtractionProgress.kt` | Progress data model + ExtractionPhase enum |
| `repository/LibraryStats.kt` | Stats data model + AuthorCount |
| `metadata/TextContentExtractor.kt` | PDFTextStripper wrapper |
| `metadata/ExtractionResult.kt` | Wrapper: PDFMetadata + textContent |
| `repository/TextContentStore.kt` | Text persistence + in-memory search index |
| `sync/FileWatcher.kt` | WatchService + debounced change detection |

## All Modified Files

| File | Changes |
|------|---------|
| `sync/SyncService.kt` | Progress tracking, TextContentStore, FileWatcher |
| `metadata/MetadataExtractor.kt` | TextContentExtractor, return ExtractionResult |
| `metadata/BatchMetadataExtractor.kt` | Work with ExtractionResult |
| `metadata/PDFMetadata.kt` | Add `hasTextContent` field |
| `repository/SearchEngine.kt` | textContentMatchIds for content search |
| `repository/InMemoryMetadataRepository.kt` | TextContentStore, computeStats() |
| `config/ScanConfiguration.kt` | Text extraction + file watching config |
| `Main.kt` | New endpoints, component wiring, file watcher startup |
| `scanning/RepositoryProgressListener.kt` | Update for ExtractionResult |

## Testing Strategy

| File | Covers |
|------|--------|
| `sync/ExtractionProgressTest.kt` | Computed properties, phase transitions |
| `repository/LibraryStatsTest.kt` | Stats on empty/single/multiple PDFs, null fields |
| `metadata/TextContentExtractorTest.kt` | Text extraction, encrypted PDFs, empty PDFs |
| `repository/TextContentStoreTest.kt` | Save/load/delete/search round-trips |
| `sync/FileWatcherTest.kt` | Directory registration, debouncing, PDF filtering |
| `repository/SearchEngineContentTest.kt` | Content search + relevance scoring with text matches |

## Verification

1. `./gradlew clean build` — compiles, all tests pass
2. `./gradlew run` — `/status` shows extraction progress during background extraction
3. `GET /api/stats` returns library analytics
4. Search for text inside a PDF (not in metadata) — content search works
5. `GET /api/pdfs/{id}/text` returns extracted text
6. Enable file watching in config, add/modify/delete a PDF on disk — auto-sync triggers
7. Kill mid-extraction, restart — resume works with text content
8. Update `AGENTS.md` with new architecture
