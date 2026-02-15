# TODO-10: No Timeout on PDF Parsing Operations

**Severity:** MEDIUM
**Component:** Metadata Extraction
**File:** `src/main/kotlin/com/example/metadata/MetadataExtractor.kt:41`

## Description

PDF loading and parsing has no timeout:

```kotlin
val document = Loader.loadPDF(pdfBytes)
```

## Impact

- Malicious PDFs with infinite loops or extremely complex structures can hang the parser indefinitely
- A single malicious file blocks the processing thread/coroutine forever
- In batch processing, this stalls the entire sync operation

## Suggested Fix

Wrap PDF parsing in a coroutine timeout:

```kotlin
val document = withTimeoutOrNull(30_000) {
    withContext(Dispatchers.IO) {
        Loader.loadPDF(pdfBytes)
    }
} ?: run {
    logger.warn("PDF parsing timed out for ${fileInfo.path}")
    return null
}
```

## Affected Tests

- Add test with timeout behavior (mock a slow-loading PDF)
