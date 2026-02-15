# TODO-11: No File Size Check in StorageProvider.read()

**Severity:** MEDIUM
**Component:** Storage
**File:** `src/main/kotlin/com/example/storage/FileSystemStorage.kt:47`

## Description

The `read()` method loads the entire file into memory without checking its size:

```kotlin
override suspend fun read(path: String): ByteArray {
    val resolvedPath = resolvePath(path)
    if (!resolvedPath.exists()) {
        throw StorageException("File not found: $path")
    }
    return resolvedPath.readBytes()  // No size limit
}
```

The `maxFileSize` (500MB) is only enforced in `PDFScanner`, not in the storage layer.

## Impact

- Any code that calls `storageProvider.read()` directly can trigger OOM with large files
- The storage layer provides no defense against oversized files

## Suggested Fix

Add an optional size limit to the read method:

```kotlin
override suspend fun read(path: String): ByteArray {
    val resolvedPath = resolvePath(path)
    if (!resolvedPath.exists()) {
        throw StorageException("File not found: $path")
    }
    val fileSize = Files.size(resolvedPath)
    if (fileSize > maxReadSize) {
        throw StorageException("File too large to read: $path ($fileSize bytes)")
    }
    return resolvedPath.readBytes()
}
```

## Affected Tests

- `FileSystemStorageTest` — add test for oversized file rejection
