# TODO-08: XMP Metadata Unbounded Read

**Severity:** MEDIUM
**Component:** Metadata Extraction
**File:** `src/main/kotlin/com/example/metadata/CustomPropertiesExtractor.kt:41`

## Description

XMP metadata stream is read entirely into memory without size limits:

```kotlin
val metadataStream = metadata.createInputStream()
val xmpContent = metadataStream.readBytes().toString(Charsets.UTF_8)
metadataStream.close()
```

## Impact

- A crafted PDF with a massive XMP metadata payload causes OOM
- No protection against decompression bombs in the metadata stream
- Memory exhaustion leading to denial of service

## Suggested Fix

Add a size limit before reading:

```kotlin
val maxXmpSize = 10 * 1024 * 1024 // 10MB limit
metadata.createInputStream().use { stream ->
    val bytes = stream.readNBytes(maxXmpSize)
    if (stream.read() != -1) {
        logger.warn("XMP metadata exceeded size limit, truncating")
    }
    val xmpContent = bytes.toString(Charsets.UTF_8)
    // ... process
}
```

## Affected Tests

- Add test with oversized XMP metadata
