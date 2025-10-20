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

3. **Edit configuration** (optional)

   Edit `config.json` to customize your setup:
   ```json
   {
     "pdfScanPaths": [
       "~/.pdf-library/books",
       "~/Documents/PDFs"
     ],
     "metadataStoragePath": "~/.pdf-library/metadata",
     "scanning": {
       "recursive": true,
       "maxDepth": 50,
       "excludePatterns": [".*", "temp*", "*.tmp"],
       "fileExtensions": [".pdf"]
     }
   }
   ```

4. **Build and run the application**
   ```bash
   ./gradlew run
   ```

   Or build and run the JAR:
   ```bash
   ./gradlew build
   java -jar build/libs/pdf-library-1.0.0.jar
   ```

The application will start on `http://localhost:8080`

### Initial Setup

After starting the application:
1. Place your PDFs in the configured scan directories
2. The application will automatically scan for PDF files
3. Generate thumbnails and extract metadata
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
