# PDF Library

A personal web-based PDF library for organizing and browsing thousands of PDFs, with thumbnail previews, full-text search, and passkey authentication.

## Features

- **Fast PDF management**: handles thousands of PDFs with in-memory search
- **Thumbnail previews**: automatic generation via PDFBox
- **Full-text search**: searches titles, authors, and extracted PDF text
- **Passkey authentication**: WebAuthn passkeys + JWT, no passwords
- **Zero dependencies**: single JAR, no external database or auth service required
- **NAS-friendly**: SMB resilience, incremental sync, scheduled background scans

## Quick Start

### Prerequisites

- Java 21 or higher
- At least 512MB RAM (for thousands of PDFs)

### Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd pdf-library
   ```

2. **Create configuration file**
   ```bash
   cp config.json.base config.json
   ```

3. **Edit configuration**

   Edit `config.json`:
   ```json
   {
     "pdfScanPaths": [
       "~/.pdf-library/books"
     ],
     "metadataStoragePath": "~/.pdf-library/metadata",
     "scanning": {
       "recursive": true,
       "maxDepth": 50,
       "excludePatterns": [".*", "temp*", "*.tmp"],
       "fileExtensions": [".pdf"]
     },
     "rpId": "localhost",
     "rpName": "PDF Library",
     "jwtIssuer": "localhost"
   }
   ```

   For a real deployment replace `"localhost"` with your domain (see [Deployment](#deployment)).

4. **Set the required `JWT_SECRET` environment variable**
   ```bash
   export JWT_SECRET="$(openssl rand -hex 32)"
   ```

5. **Build and run**
   ```bash
   ./gradlew run
   ```

   Or build a JAR first:
   ```bash
   ./gradlew build
   JWT_SECRET="your-secret" java -jar build/libs/pdf-library-1.0.0.jar
   ```

The application will start on `http://localhost:8080`.

### First login

On the first run there are no registered passkeys, so the registration form is
open to anyone.

1. Open `http://localhost:8080/login`
2. Expand **Register a new device**, enter a username and display name, and click **Register this device**
3. Your browser/device will prompt you to create a passkey (Touch ID, Face ID, Windows Hello, hardware key, etc.)
4. Once registered, click **Sign in with passkey** to log in

After the first credential is saved, the registration form requires an existing
valid JWT — preventing strangers from adding themselves.

## Deployment

### Requirements

- Java 21+
- `JWT_SECRET` environment variable — a random secret of at least 32 characters.
  The server refuses to start if this is missing.
- HTTPS — passkeys require a secure context. `localhost` is exempt for local testing,
  but any remote deployment must be served over HTTPS.

### Environment variables

| Variable | Required | Description |
|---|---|---|
| `JWT_SECRET` | Yes | HMAC-256 signing secret for JWTs. Rotate to invalidate all sessions. |

### config.json — auth fields

| Field | Default | Description |
|---|---|---|
| `rpId` | `"localhost"` | WebAuthn Relying Party ID — must match the domain users visit, e.g. `"library.example.com"` |
| `rpName` | `"PDF Library"` | Human-readable app name shown by the browser passkey prompt |
| `jwtIssuer` | `"localhost"` | JWT issuer claim — set to the same value as `rpId` |

### Reverse proxy (Caddy example)

Passkeys are tied to the `rpId` domain. Put the app behind a reverse proxy that
terminates TLS:

```
library.example.com {
    reverse_proxy localhost:8080
}
```

Update `config.json`:
```json
{
  "rpId": "library.example.com",
  "rpName": "My PDF Library",
  "jwtIssuer": "library.example.com"
}
```

### Registering additional devices

Once you are logged in, open `http://your-domain/login` and use the
**Register a new device** form. The server requires a valid JWT for registration
after the first credential exists, so only authenticated users can add new passkeys.

### Revoking access

Delete or edit `credentials.json` in `metadataStoragePath` to remove passkeys.
Changing `JWT_SECRET` immediately invalidates all existing sessions.

## Architecture

### Storage
- Filesystem-backed with atomic writes (temp-file + rename)
- All metadata in a single JSON file per collection — easy to back up

### PDF processing
- Thumbnail generation and text extraction via Apache PDFBox
- Incremental sync: only processes new/changed files
- Scheduled background scans for NAS volumes where `inotify` doesn't work

### Performance
- In-memory metadata index for instant search
- Lazy-loaded thumbnails
- Responsive grid layout

## API Endpoints

All `/api/*` routes (except `/api/auth/*`) require `Authorization: Bearer <token>`.

| Endpoint | Auth | Description |
|---|---|---|
| `GET /login` | Public | Login / registration page |
| `POST /api/auth/login/start` | Public | Begin passkey authentication |
| `POST /api/auth/login/finish` | Public | Complete authentication, receive JWT |
| `POST /api/auth/register/start` | Public¹ | Begin passkey registration |
| `POST /api/auth/register/finish` | Public¹ | Complete registration |
| `POST /api/auth/logout` | JWT | Invalidate client-side token |
| `GET /api/pdfs` | JWT | List PDFs with pagination, sorting, search |
| `GET /api/pdfs/{id}` | JWT | PDF metadata |
| `GET /api/pdfs/{id}/file` | JWT | Serve the PDF file |
| `GET /api/pdfs/{id}/text` | JWT | Extracted plain text |
| `GET /api/thumbnails/{id}` | JWT | Thumbnail image |
| `GET /api/stats` | JWT | Library statistics |
| `POST /api/sync` | JWT | Trigger sync (`full`, `incremental`, `retry-failed`) |
| `GET /api/manifest` | JWT | Discovery manifest status |
| `GET /status` | JWT | Server status and extraction progress |

¹ Public only when no credentials exist (bootstrap). Requires JWT thereafter.

## Custom Metadata

The system supports extensible metadata for different PDF types:

**Books**: `genre`, `series`, `volume`, `isbn`
**Comics**: `series`, `issue`, `publisher`, `year`
**Documents**: `category`, `product`, `version`

## Development

### Technology Stack

- **Backend**: Kotlin + Ktor 3
- **Auth**: WebAuthn (Yubico) + JWT (Auth0 java-jwt)
- **Frontend**: Vanilla JavaScript
- **PDF processing**: Apache PDFBox
- **Storage**: Filesystem (atomic writes, no external database)

### Project Structure

```
src/main/kotlin/com/example/
├── storage/           # Storage abstraction & implementations
├── indexing/          # PDF scanning, metadata extraction
├── sync/              # Synchronization service
├── api/               # REST controllers
├── metadata/          # Metadata abstraction layer
└── web/               # Static web resources
```

### Building

```bash
./gradlew build
```

### Testing

```bash
./gradlew test
```

## Configuration

The application uses filesystem-based configuration. Metadata is stored alongside PDFs in `metadata.json` files, making the system portable and backup-friendly.

## Performance

- **Handles thousands of PDFs** efficiently
- **Sub-millisecond search** with in-memory indexing
- **Optimized for read operations** with background sync
- **Minimal memory footprint** (~10-50MB for metadata)

## Future Extensions

- Collections and folder organisation
- PDF annotation support

## License

Apache License 2.0
