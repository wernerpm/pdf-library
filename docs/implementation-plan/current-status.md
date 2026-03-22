# Current Implementation Status

> Last updated: 2026-03-22

## Overview

| Step | Name | Status |
|------|------|--------|
| 1a | Configuration Module + Storage Abstraction | DONE |
| 1b | PDF Scanner + File Discovery | DONE |
| 1c | Metadata Extraction (PDFBox) | DONE |
| 1d | In-Memory Repository + JSON Persistence | DONE |
| 2 | Two-Phase Sync (Discovery + Extraction) | DONE |
| 3 | Extraction Progress + Full-Text Search + File Watching | DONE |
| 4 | Frontend UI (v1 + improvements 4A/4B/4C) | DONE |
| 2B | Backend Improvements (SMB, retry, startup scan, manifest API) | DONE |
| 5 | API Integration Tests | DONE |
| 6 | Authentication (Passkeys + JWT) | NOT STARTED |
| 7 | Cloud Deployment (Docker + Caddy + HTTPS + security headers) | NOT STARTED |
| 8 | CI Pipeline (GitHub Actions: test + build image + push to GHCR) | NOT STARTED |

---

## What's Built

### Step 1a — Configuration + Storage (DONE)
- `AppConfiguration` / `ScanConfiguration` loaded from `config.json`
- `ConfigurationManager`: load, validate, save, `~` path expansion
- `FileSystemStorage`: atomic writes (temp + rename), path traversal prevention, 500 MB read cap, symlink-safe recursive delete
- Tests: `FileSystemStorageTest`, `ConfigurationManagerTest`

### Step 1b — PDF Scanner (DONE)
- `PDFScanner`: recursive walk, extension filtering, size limits, 100k file cap, symlink loop detection
- `PDFValidator`: `%PDF` header check (disableable via `validatePdfHeaders: false` for NAS)
- `DuplicateDetector`: canonical path-based dedup across multiple scan paths
- `discoverFiles()`: phase-1 only — no PDF bytes read, incremental partial manifest saves
- Tests: `PDFScannerTest`, `PDFValidatorTest`, `DuplicateDetectorTest`, `DiscoverFilesTest`

### Step 1c — Metadata Extraction (DONE)
- `MetadataExtractor`: PDFBox 3.0, 60s timeout per PDF
- `DocumentInfoExtractor`: title, author, subject, creator, producer, dates
- `CustomPropertiesExtractor`: XMP + custom properties
- `ContentHashGenerator`: SHA-256
- `ThumbnailGenerator`: page 0 → PNG at 72 DPI → 300px width
- `SecurePDFHandler`: graceful handling of encrypted/signed PDFs
- Tests: `MetadataExtractorTest`, `BatchMetadataExtractorTest`, `ContentHashGeneratorTest`, `ThumbnailGeneratorTest`

### Step 1d — Repository + Persistence (DONE)
- `JsonRepository`: one `<id>.json` per PDF
- `InMemoryMetadataRepository`: `ConcurrentHashMap` cache + secondary indices, mutex-protected
- `ConsistencyManager`: detects and repairs orphaned in-memory vs on-disk entries
- `SearchEngine`: relevance-scored full-text search (title +3.0, author/filename +2.0, subject/keywords +1.0)
- Tests: `RepositoryIntegrationTest`, `SearchEngineTest`

### Step 2 — Two-Phase Sync (DONE)
- `performDiscovery()` / `performExtraction()` / `resumeExtraction()` / `performFullSync()` / `performIncrementalSync()`
- `diffManifests()`: SMB-safe — skips deletions when a scan path returns 0 results
- Startup flow: server ready → `resumeExtraction()` → startup incremental scan → file watching/scheduled sync
- Tests: `DiscoveryManifestTest`, `DiscoverFilesTest`, `DiffManifestsTest`

### Step 3 — Extraction Progress + Full-Text Search + File Watching (DONE)
- `ExtractionProgress` / `ExtractionPhase` with `GET /status` polling
- `TextContentExtractor` + `TextContentStore`: per-PDF full text, `GET /api/pdfs/{id}/text`
- `FileWatcher`: WatchService-based for local paths; `syncIntervalMinutes` for NAS volumes

### Step 4 — Frontend UI (DONE)
- Vanilla JS SPA: grid, search (debounced), sort/order, numbered pagination (top + bottom), detail modal
- Text content preview in modal, PDF served via `GET /api/pdfs/{id}/file` HTTP proxy
- Sync panel: status badge, progress bar, full/incremental sync buttons

### Step 2B — Backend Improvements (DONE)
- **2B-1**: `diffManifests()` SMB guard
- **2B-2**: `POST /api/sync {"type":"retry-failed"}` + `SyncType.RETRY_FAILED`
- **2B-3**: Startup incremental scan after `resumeExtraction()`
- **2B-4**: `GET /api/manifest` → `ManifestStatus` with counts + failed file paths
- **2B-5**: `sort` / `order` params on `GET /api/pdfs`
- Tests: `SyncServiceRetryTest`

### Step 5 — API Integration Tests (DONE)
- 21 `testApplication` tests in `MainApiTest`: pagination, search, sort, 404s, sync types,
  manifest, stats, status, redirect
- Uses real `InMemoryMetadataRepository` + mockk for `RepositoryManager` / `SyncService`

---

## What's Next

### Step 6 — Authentication (Passkeys + JWT)
See [`step-6-auth.md`](step-6-auth.md).

- WebAuthn passkey registration and login ceremonies
- JWT issued on successful authentication (8h expiry), validated as Ktor middleware
- All `/api/*` and `/static/*` routes protected; auth endpoints and login page public
- Credentials stored in `credentials.json` alongside other metadata
- No passwords, no user management complexity

### Step 7 — Cloud Deployment
See [`step-7-deployment.md`](step-7-deployment.md).

- `Dockerfile` (multi-stage Gradle build → JRE runtime image)
- `docker-compose.yml` with Caddy reverse proxy (automatic HTTPS via Let's Encrypt)
- `Caddyfile`: HTTPS termination, security headers, rate limiting on auth endpoints
- Ktor: HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, CORS locked to own domain, CSP
- Secrets via environment variables (`JWT_SECRET`, `APP_URL`)
- Firewall: only port 443 externally exposed

### Step 8 — CI Pipeline
See [`step-8-ci.md`](step-8-ci.md).

**Tool: GitHub Actions + GHCR** (both free — see step doc for cost breakdown).

- On every push/PR to `main`: `./gradlew build` with Gradle cache (~2 min)
- On push to `main` only: Docker build + push to GHCR with GHA layer cache (~3 min)
- Image tagged as `:latest` and `:<git-sha>` for rollback support
- `GITHUB_TOKEN` used for GHCR auth — no secrets to configure
- Dockerfile updated with BuildKit `--mount=type=cache` for Gradle dependencies

---

## Current Live State

- Scan paths: configured in `config.json` (includes SMB NAS at `/Volumes/Public/Livros/`)
- ~2686 PDFs discovered, extraction completed
- Metadata stored at `$metadataStoragePath` as individual `<id>.json` files
- Discovery manifest at `$metadataStoragePath/discovery-manifest.json`
