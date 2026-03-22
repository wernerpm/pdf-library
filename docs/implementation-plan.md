# PDF Library Implementation Plan

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| Configuration module | DONE | JSON config, scan paths, metadata storage path |
| Storage abstraction | DONE | `StorageProvider` interface + `FileSystemStorage` with security hardening |
| PDF Scanner | DONE | Recursive traversal, dedup, symlink safety, file count limits |
| Metadata extraction | DONE | PDFBox 3.0, XMP, encrypted PDF handling, SHA-256 hashing |
| Repository + persistence | DONE | In-memory cache with JSON file backing, thread-safe |
| Two-phase sync | DONE | Fast discovery + chunked extraction, resumable, manifest-backed |
| REST API | DONE | Full CRUD, search, sync, pagination, sort/order, file proxy, manifest |
| Security hardening (local) | DONE | Path traversal, atomic writes, symlink safety, input validation |
| Thumbnail generation | DONE | PDFBox page 0 → PNG at 72 DPI → 300px |
| Frontend UI | DONE | Vanilla JS SPA: grid, search, sort, pagination, modal, sync panel |
| Full-text PDF search | DONE | Text extraction + in-memory index, scored search |
| File watching + scheduled sync | DONE | WatchService for local paths; `syncIntervalMinutes` for NAS |
| SMB resilience | DONE | `diffManifests()` guards against 0-result scan paths |
| Retry-failed | DONE | `POST /api/sync {"type":"retry-failed"}` |
| Startup incremental scan | DONE | Runs after `resumeExtraction()` on every startup |
| Manifest API | DONE | `GET /api/manifest` — counts + failed file list |
| API integration tests | DONE | 21 Ktor `testApplication` tests via `MainApiTest` |
| Authentication (passkeys + JWT) | NOT STARTED | See `step-6-auth.md` |
| Cloud deployment | NOT STARTED | See `step-7-deployment.md` |
| S3 storage backend | FUTURE | `StorageProvider` interface is ready; `S3Storage` not yet implemented |

---

## Overview

A PDF library application that provides a web-based interface for managing thousands of PDFs with thumbnail previews, metadata indexing, and flexible storage backends. Target: personal use, single-digit users, deployed on a cloud VM.

## Architecture

### 1. Storage Layer — DONE

`StorageProvider` interface with `FileSystemStorage` implementation. Pluggable — an `S3Storage` implementation can be added later without changing any business logic.

### 2. PDF Indexing & Synchronization — DONE

Two-phase sync: fast filesystem walk (phase 1) followed by chunked PDF extraction (phase 2). Resumable across restarts via `discovery-manifest.json`. SMB-safe: if a scan path returns 0 results, deletions for that path are skipped.

### 3. Backend API — DONE

All endpoints implemented, tested:

| Endpoint | Description |
|----------|-------------|
| `GET /api/pdfs` | Paginated list with search, sort, order |
| `GET /api/pdfs/{id}` | PDF metadata |
| `GET /api/pdfs/{id}/file` | Serve PDF file inline |
| `GET /api/pdfs/{id}/text` | Full text content |
| `GET /api/thumbnails/{id}` | PNG thumbnail |
| `POST /api/sync` | Trigger full / incremental / retry-failed sync |
| `GET /api/manifest` | Discovery manifest counts + failed file list |
| `GET /api/stats` | Library statistics |
| `GET /status` | System status + extraction progress |

### 4. Frontend UI — DONE

Vanilla JS SPA (`src/main/resources/static/`). CSS Grid layout, lazy-loaded thumbnails, debounced search, sort/order controls, numbered pagination (top + bottom), detail modal with full metadata + text preview, sync status panel with progress bar.

### 5. Authentication — NOT STARTED

See [`step-6-auth.md`](implementation-plan/step-6-auth.md).

WebAuthn passkeys + JWT. All API and static routes protected. No password storage. Short-lived JWTs (8h). Credentials stored in `credentials.json`.

### 6. Cloud Deployment — NOT STARTED

See [`step-7-deployment.md`](implementation-plan/step-7-deployment.md).

Docker + Caddy (automatic HTTPS). Security headers (HSTS, CSP, X-Frame-Options). Secrets via environment variables. Firewall: only port 443 exposed.

---

## Technical Decisions

### Why In-Memory Metadata?

Thousands of PDFs fit comfortably in memory (~10–50 MB of metadata). Sub-millisecond searches with no disk I/O. The `MetadataRepository` interface allows a SQLite or S3-backed implementation later if needed.

### Why Vanilla JavaScript?

No build step, no dependency churn, pure web standards. Works well for this scale — pagination makes virtual scrolling unnecessary.

### Why Kotlin + Ktor?

Type safety, coroutines for I/O-heavy PDF operations, lightweight (no Spring overhead), full access to the Java/PDFBox ecosystem.

### Why Passkeys over Passwords?

No password database to leak. Phishing-resistant by design. Modern browsers have full support. Single-digit users means no UX tradeoff at registration.

### Why Caddy over nginx?

Automatic HTTPS certificate provisioning and renewal with zero configuration. Single binary, simple Caddyfile syntax.

---

## Future Extensions

| Item | Notes |
|------|-------|
| S3 storage backend | `StorageProvider` is ready; implement `S3Storage` when needed |
| Collections / tagging | Folder-like grouping within the library |
| PDF annotations | Notes or highlights stored alongside metadata |
