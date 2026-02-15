# Security & Robustness TODOs

Stability and correctness issues for the PDF Library application. These are bugs that can cause crashes, hangs, data corruption, or resource exhaustion regardless of deployment context.

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 1 |
| HIGH | 6 |
| MEDIUM | 5 |
| LOW | 1 |
| **Total** | **13** |

## CRITICAL

| # | Title | Component | File |
|---|-------|-----------|------|
| [01](TODO-01-weak-fallback-hash.md) | Weak Fallback Hash in ContentHashGenerator | Metadata | `ContentHashGenerator.kt:14-22` |

## HIGH

| # | Title | Component | File |
|---|-------|-----------|------|
| [02](TODO-02-pagination-input-validation.md) | No Input Validation on Pagination | API | `Main.kt:159-160` |
| [03](TODO-03-unbounded-memory-scanning.md) | Unbounded Memory in Scanning | Scanning | `PDFScanner.kt:41-85` |
| [04](TODO-04-no-symlink-loop-detection.md) | No Symlink Loop Detection | Scanning | `PDFScanner.kt:115-211` |
| [05](TODO-05-runblocking-deadlock.md) | runBlocking Deadlock Risk | Scanning | `RepositoryProgressListener.kt:77-87` |
| [06](TODO-06-concurrent-read-without-mutex.md) | Concurrent Cache Read Without Mutex | Repository | `InMemoryMetadataRepository.kt:30-36` |
| [07](TODO-07-symlink-following-in-delete.md) | Symlink Following in Recursive Delete | Storage | `FileSystemStorage.kt:119-131` |

## MEDIUM

| # | Title | Component | File |
|---|-------|-----------|------|
| [08](TODO-08-xmp-unbounded-read.md) | XMP Metadata Unbounded Read | Metadata | `CustomPropertiesExtractor.kt:41` |
| [09](TODO-09-stream-resource-leak.md) | Stream Resource Leak | Metadata | `CustomPropertiesExtractor.kt:40-42` |
| [10](TODO-10-no-pdf-parsing-timeout.md) | No Timeout on PDF Parsing | Metadata | `MetadataExtractor.kt:41` |
| [11](TODO-11-no-file-size-check-in-storage-read.md) | No File Size Check in Storage Read | Storage | `FileSystemStorage.kt:47` |
| [12](TODO-12-persistence-rollback-order.md) | Incorrect Persistence Rollback Order | Repository | `InMemoryMetadataRepository.kt:38-64` |

## LOW

| # | Title | Component | File |
|---|-------|-----------|------|
| [13](TODO-13-temp-file-cleanup-ignored.md) | Temp File Cleanup Errors Ignored | Storage | `FileSystemStorage.kt:72-77` |

## Recommended Fix Order

1. **TODO-01** — Fix weak hashing (data integrity, quick fix)
2. **TODO-02** — Pagination validation (one-liner, prevents crashes)
3. **TODO-09** — Stream resource leak (one-liner `.use()` fix)
4. **TODO-05** — runBlocking deadlock (prevents app freezes)
5. **TODO-12** — Persistence rollback order (prevents data corruption)
6. **TODO-04** — Symlink loop detection (prevents hangs)
7. **TODO-07** — Symlink following in delete (prevents accidental file deletion)
8. **TODO-10** — PDF parsing timeout (prevents hangs on corrupt PDFs)
9. **TODO-08** — XMP unbounded read (prevents OOM on malformed PDFs)
10. **TODO-11** — File size check in storage (prevents OOM)
11. **TODO-06** — Concurrent read mutex (prevents rare inconsistencies)
12. **TODO-03** — Unbounded memory scanning (prevents OOM on huge libraries)
13. **TODO-13** — Temp file cleanup logging (minor housekeeping)
