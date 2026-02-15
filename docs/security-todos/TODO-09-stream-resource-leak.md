# TODO-09: Stream Resource Leak in CustomPropertiesExtractor

**Severity:** MEDIUM
**Component:** Metadata Extraction
**File:** `src/main/kotlin/com/example/metadata/CustomPropertiesExtractor.kt:40-42`

## Description

The XMP metadata stream is closed in the try block, not in a finally block. If `readBytes()` throws, the stream is never closed:

```kotlin
try {
    val metadataStream = metadata.createInputStream()
    val xmpContent = metadataStream.readBytes().toString(Charsets.UTF_8)
    metadataStream.close()  // Never reached if readBytes() throws
}
```

## Impact

- Resource leak: open file handles accumulate over time
- Eventually leads to "too many open files" errors
- Gradual degradation under heavy load

## Suggested Fix

Use Kotlin's `.use()` extension for automatic resource cleanup:

```kotlin
try {
    metadata.createInputStream().use { stream ->
        val xmpContent = stream.readBytes().toString(Charsets.UTF_8)
        // ... process
    }
}
```

## Affected Tests

- Add test that verifies streams are closed even on error
