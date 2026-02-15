# TODO-01: Weak Fallback Hash in ContentHashGenerator

**Severity:** CRITICAL
**Component:** Metadata Extraction
**File:** `src/main/kotlin/com/example/metadata/ContentHashGenerator.kt:14-22`

## Description

Two issues in hash generation:

1. If SHA-256 fails, the content hash silently falls back to `contentHashCode()` — a non-cryptographic 32-bit Java array hash:

```kotlin
} catch (e: Exception) {
    pdfBytes.contentHashCode().toString()  // Weak fallback
}
```

2. `generateMetadataHash()` uses `hashCode()` instead of SHA-256:

```kotlin
fun generateMetadataHash(metadata: PDFMetadata): String {
    val content = "${metadata.title}-${metadata.author}-${metadata.pageCount}-${metadata.fileSize}"
    return content.hashCode().toString()  // NOT cryptographic
}
```

## Impact

- Hash collisions are trivial with 32-bit hash space
- Deduplication can be bypassed or abused
- Two different PDFs could be treated as duplicates
- Content integrity checks are meaningless with weak hashes

## Suggested Fix

- Remove the weak fallback: return `null` or propagate the error if SHA-256 fails
- Use SHA-256 consistently for `generateMetadataHash()` as well
- Log a warning if SHA-256 is unavailable (should never happen on modern JVMs)

## Affected Tests

- `ContentHashGeneratorTest` — tests validate the current weak behavior and will need updating
