# PDF Library

A high-performance web-based PDF management application for organizing and browsing thousands of PDFs with thumbnail previews, metadata indexing, and flexible storage backends.

## Features

- **Fast PDF Management**: Handle thousands of PDFs with sub-millisecond search performance
- **Thumbnail Previews**: Automatic thumbnail generation for visual browsing
- **Advanced Search**: Search by title, author, custom metadata, and more
- **Flexible Storage**: Pluggable storage backends (filesystem, S3, cloud storage)
- **Zero Dependencies**: Single JAR deployment with no external database required
- **Modern UI**: Responsive web interface with virtual scrolling and lazy loading
- **Custom Metadata**: Extensible metadata system for books, comics, documents, etc.

## Quick Start

### Prerequisites

- Java 17 or higher
- At least 512MB RAM (for thousands of PDFs)

### Running

```bash
java -jar pdf-library.jar
```

The application will start on `http://localhost:8080`

### Configuration

Place your PDFs in the configured storage directory. The application will automatically:
1. Scan for PDF files
2. Generate thumbnails
3. Extract metadata
4. Create a searchable index

## Architecture

### Storage Engine
- **Abstracted storage layer** supporting multiple backends
- **Access control** with role-based permissions
- **Primary implementation**: FileSystem storage
- **Future support**: S3, database, cloud storage

### PDF Processing
- **Automatic indexing** of PDF files
- **Thumbnail generation** using PDFBox
- **Metadata extraction** including custom properties
- **Incremental sync** for efficient updates

### Performance
- **In-memory metadata** for instant searches
- **Virtual scrolling** for handling large collections
- **Lazy loading** thumbnails
- **Responsive design** for mobile and desktop

## API Endpoints

- `GET /api/pdfs` - List PDFs with pagination and filtering
- `GET /api/pdfs/{id}` - Get specific PDF details
- `GET /api/thumbnails/{id}` - Serve thumbnail images
- `POST /api/sync` - Trigger manual synchronization
- `GET /api/search?q={query}` - Search PDFs by metadata

## Custom Metadata

The system supports extensible metadata for different PDF types:

**Books**: `genre`, `series`, `volume`, `isbn`
**Comics**: `series`, `issue`, `publisher`, `year`
**Documents**: `category`, `product`, `version`

## Development

### Technology Stack

- **Backend**: Kotlin + Ktor
- **Frontend**: Vanilla JavaScript + Web Components
- **PDF Processing**: PDFBox
- **Storage**: Filesystem (with pluggable backends)

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

- Full-text search within PDFs
- Collections and folder organization
- User authentication and authorization
- PDF annotation support
- Cloud storage backends (S3, Google Cloud, Azure)

## License

Apache License 2.0
