# TODO-04: No Symlink Loop Detection in Scanner

**Severity:** HIGH
**Component:** Scanning
**File:** `src/main/kotlin/com/example/scanning/PDFScanner.kt:115-211`

## Description

The recursive `walkDirectory()` method does not track visited directories. If a symlink creates a cycle (e.g., `dir -> ../dir`), the scanner enters infinite recursion until it hits `maxDepth` or stack overflow.

```kotlin
if (configuration.scanning.recursive && currentDepth < configuration.scanning.maxDepth) {
    val subResult = walkDirectory(fullPath, currentDepth + 1, progressListener)
    // No check if fullPath was already visited
}
```

## Impact

- Stack overflow from recursive symlink loops
- Infinite scanning of the same directories
- CPU and memory exhaustion

## Suggested Fix

Track visited directories by canonical path:

```kotlin
private val visitedDirectories = mutableSetOf<String>()

private suspend fun walkDirectory(path: String, currentDepth: Int, ...): ScanResult {
    val canonicalPath = Paths.get(path).toRealPath().toString()
    if (!visitedDirectories.add(canonicalPath)) {
        return emptyScanResult() // Already visited
    }
    // ... continue scanning
}
```

Also consider respecting `ScanConfiguration.followSymlinks` — currently the flag exists but isn't checked during traversal.

## Affected Tests

- `PDFScannerTest` — add test with symlink loop scenario
