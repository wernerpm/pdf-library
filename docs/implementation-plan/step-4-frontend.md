# Step 4 — Frontend UI

> Status: DONE (v1) — improvements planned below

## Goals

Serve a browser UI from the Ktor server so the user can browse, search, and manage their PDF library without needing a separate frontend server.

## Technical Approach

- **Vanilla JS** — no build step, no frameworks, works out-of-the-box
- **Static resources** via Ktor `staticResources("/static", "static")` (built into `ktor-server-core`)
- Files live under `src/main/resources/static/`
- `GET /` redirects to `/static/index.html`

## Files

```
src/main/resources/static/
  index.html   — single-page app shell
  app.css      — responsive grid, cards, modal, dark-friendly
  app.js       — fetch-based API client, state machine, event handlers
```

## Features

### Library Grid
- Responsive CSS grid of PDF cards
- Each card: thumbnail (via `/api/thumbnails/{id}`), title, author, filename, page count
- Fallback placeholder when no thumbnail available
- Click card → opens detail modal

### Search
- Debounced text input (300ms) — uses `GET /api/pdfs?q=`
- Full-text search backed by `TextContentStore` (step 3B)
- Clear button

### Sort & Order
- Sort by: filename (default), title, author, indexedAt, fileSize
- Order: asc / desc
- Requires sort/order params added to `GET /api/pdfs` backend

### Pagination
- Previous / Next buttons + page indicator
- Page size selector (25 / 50 / 100)
- Shown at top **and** bottom of the grid

### Detail Modal
- Full metadata table (all PDFMetadata fields)
- Text content preview — first 2000 chars from `/api/pdfs/{id}/text`
- **"Open PDF" link** — opens the local file path in a new tab (`file://` URL) so the PDF opens in the browser or OS handler

### Sync Panel
- Current status badge (IDLE / DISCOVERING / EXTRACTING / COMPLETED / FAILED)
- Extraction progress bar (percentComplete from `/status`)
- "Full Sync" and "Incremental Sync" buttons → `POST /api/sync`
- Auto-polls `/status` every 3s while sync in progress

### Stats Bar
- Total PDFs, total pages, total size, encrypted count
- Top authors list
- Loaded once on startup from `GET /api/stats`

## Planned Improvements

### 4A — Numbered page buttons
Replace the plain "Page N of M" label with a compact page-number bar:
```
← 1 2 3 4 5 … 40 →
```
- Show up to 7 slots: always first, always last, current ±2, ellipsis where there are gaps
- Current page highlighted
- Applies to both top and bottom pagination bars

### 4B — Pagination top and bottom
Duplicate the pagination controls so they appear above the grid (below the toolbar) **and** below the grid. Both are kept in sync from the same state — a single `renderPagination()` call updates both.

### 4C — Open PDF from detail modal
The modal "Open PDF" button constructs a `file://` URL from `pdf.path` and opens it with `window.open(url, '_blank')`. This lets the OS/browser handle the PDF directly. The button is shown below the metadata table.

---

## Backend Changes (Main.kt)

```kotlin
// Add import
import io.ktor.server.http.content.*

// In configureRouting():
staticResources("/static", "static")

// Change GET "/" handler:
get("/") {
    call.respondRedirect("/static/index.html")
}

// Add sort/order to GET /api/pdfs:
val sort = call.request.queryParameters["sort"] ?: "fileName"
val order = call.request.queryParameters["order"] ?: "asc"
val allPdfs = /* existing fetch */ .let { list ->
    val sorted = when (sort) {
        "title"     -> list.sortedBy { it.title?.lowercase() ?: it.fileName.lowercase() }
        "author"    -> list.sortedBy { it.author?.lowercase() ?: "" }
        "indexedAt" -> list.sortedBy { it.indexedAt }
        "fileSize"  -> list.sortedBy { it.fileSize }
        else        -> list.sortedBy { it.fileName.lowercase() }
    }
    if (order == "desc") sorted.reversed() else sorted
}
```
