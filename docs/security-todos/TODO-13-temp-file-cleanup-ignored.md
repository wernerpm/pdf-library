# TODO-13: Temp File Cleanup Errors Silently Ignored

**Severity:** LOW
**Component:** Storage
**File:** `src/main/kotlin/com/example/storage/FileSystemStorage.kt:72-77`

## Description

When the main write operation fails, temp file cleanup errors are silently swallowed:

```kotlin
try {
    Files.deleteIfExists(tempFile)
} catch (cleanupException: Exception) {
    // Ignore cleanup errors
}
```

## Impact

- Orphaned temp files accumulate on disk over time
- Disk space exhaustion in long-running deployments
- Temp files may contain sensitive PDF data left on disk

## Suggested Fix

Log cleanup failures and consider a periodic temp file cleanup job:

```kotlin
try {
    Files.deleteIfExists(tempFile)
} catch (cleanupException: Exception) {
    logger.warn("Failed to clean up temp file: ${tempFile.fileName}", cleanupException)
}
```

Additionally, consider a startup sweep of old `.tmp` files in the metadata directory.

## Affected Tests

- No direct test changes needed
