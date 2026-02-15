# TODO-05: runBlocking Deadlock Risk in RepositoryProgressListener

**Severity:** HIGH
**Component:** Scanning
**File:** `src/main/kotlin/com/example/scanning/RepositoryProgressListener.kt:77-87`

## Description

`flushCurrentDirectory()` is a non-suspend function that uses `runBlocking` to call suspend functions:

```kotlin
private fun flushCurrentDirectory() {
    for (fileInfo in currentDirectoryFiles) {
        val metadata = runBlocking {
            metadataExtractor.extractMetadata(fileInfo)
        }
        // ...
        runBlocking {
            repository.savePDF(metadata)
        }
    }
}
```

## Impact

- If called from a coroutine with a limited dispatcher (e.g., `Dispatchers.Default`), `runBlocking` blocks the thread while waiting for the inner coroutine, which may need the same thread — causing deadlock
- Defeats the async design of the application
- Can cause thread starvation under load

## Suggested Fix

Make `flushCurrentDirectory()` a `suspend` function and propagate the suspend modifier up through the `ScanProgressListener` interface:

```kotlin
private suspend fun flushCurrentDirectory() {
    for (fileInfo in currentDirectoryFiles) {
        val metadata = metadataExtractor.extractMetadata(fileInfo)
        // ...
        repository.savePDF(metadata)
    }
}
```

This requires updating the `ScanProgressListener` callback methods to be `suspend` as well.

## Affected Tests

- Tests using `RepositoryProgressListener`
- `ScanProgressListener` interface change affects all implementations
