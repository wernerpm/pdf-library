# TODO-06: Concurrent Cache Read Without Mutex Protection

**Severity:** HIGH
**Component:** Repository
**File:** `src/main/kotlin/com/example/repository/InMemoryMetadataRepository.kt:30-36`

## Description

`getAllPDFs()` reads from the cache without acquiring the mutex, while all write operations are mutex-protected:

```kotlin
override suspend fun getAllPDFs(): List<PDFMetadata> {
    return cache.values.toList().sortedBy { it.fileName }
    // NOT protected by mutex
}
```

## Impact

- While `ConcurrentHashMap` is thread-safe for individual operations, `.values.toList()` is not atomic
- During concurrent modifications, the snapshot may be inconsistent (partial updates visible)
- Sorting an inconsistent list could produce incorrect results
- Same issue likely affects other read-only methods like `search()`, `count()`

## Suggested Fix

Either:
1. Wrap reads in mutex (consistent but slower):
```kotlin
override suspend fun getAllPDFs(): List<PDFMetadata> = mutex.withLock {
    cache.values.toList().sortedBy { it.fileName }
}
```

2. Or use a snapshot mechanism (better for read-heavy workloads):
```kotlin
// Use a volatile reference to an immutable snapshot
@Volatile private var snapshot: List<PDFMetadata> = emptyList()
```

## Affected Tests

- `RepositoryIntegrationTest` — add concurrent read/write test
