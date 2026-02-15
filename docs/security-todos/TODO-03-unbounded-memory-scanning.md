# TODO-03: Unbounded Memory Consumption in Scanning

**Severity:** HIGH
**Component:** Scanning
**File:** `src/main/kotlin/com/example/scanning/PDFScanner.kt:41-85`

## Description

The scanner collects all discovered files into an unbounded list:

```kotlin
val allFiles = mutableListOf<PDFFileInfo>()
for (scanPath in configuration.pdfScanPaths) {
    val result = scanPath(scanPath, progressListener)
    allFiles.addAll(result.discoveredFiles)
}
```

## Impact

- Scanning a directory tree with millions of PDFs causes OOM
- No backpressure mechanism to slow discovery when processing can't keep up
- All file metadata held in memory simultaneously

## Suggested Fix

- Process files in batches (per-directory batching already exists via `RepositoryProgressListener`, but `allFiles` still accumulates)
- Use streaming/flow-based approach instead of collecting all results
- Add a configurable max file count limit
- Consider using Kotlin `Flow` for lazy evaluation

## Affected Tests

- `PDFScannerTest` — add tests with large simulated directory structures
