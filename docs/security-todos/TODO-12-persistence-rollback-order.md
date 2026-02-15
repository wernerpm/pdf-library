# TODO-12: Incorrect Persistence Rollback Order

**Severity:** MEDIUM
**Component:** Repository
**File:** `src/main/kotlin/com/example/repository/InMemoryMetadataRepository.kt:38-64`

## Description

The `savePDF()` method updates the in-memory cache first, then attempts to persist. On failure, it rolls back the cache, but the backing storage may have partial data:

```kotlin
override suspend fun savePDF(metadata: PDFMetadata) {
    mutex.withLock {
        cache[metadata.id] = metadata       // 1. Update cache
        updateIndices(metadata)              // 2. Update indices
        try {
            backingRepository.savePDF(metadata)  // 3. Persist
        } catch (e: Exception) {
            cache.remove(metadata.id)        // 4. Rollback cache
            removeFromIndices(metadata)      // 5. Rollback indices
            throw e
        }
    }
}
```

## Impact

- If persistence partially succeeds (e.g., file created but not fully written), the backing storage has corrupt data
- On next application restart, the corrupt data is loaded into cache
- Data integrity loss over time

## Suggested Fix

Persist first, then update cache on success:

```kotlin
override suspend fun savePDF(metadata: PDFMetadata) {
    mutex.withLock {
        backingRepository.savePDF(metadata)  // Persist first
        cache[metadata.id] = metadata        // Update cache only on success
        updateIndices(metadata)
    }
}
```

## Affected Tests

- `RepositoryIntegrationTest` — add test simulating persistence failure
