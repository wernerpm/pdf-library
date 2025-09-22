# PDF Library Implementation Plan

## Overview
A PDF library application that provides a web-based interface for managing thousands of PDFs with thumbnail previews, metadata indexing, and flexible storage backends.

## Frontend Technology Recommendation

### Vanilla JavaScript + Web Components
- **Zero build step** - works directly in browsers
- **Maximum performance** for handling thousands of thumbnails efficiently
- **Native browser support** with ~0KB framework overhead
- **Easy to learn and maintain** - no complex tooling required
- **Perfect for 2025** - aligns with industry trend toward lightweight solutions

### Key Benefits
- Excellent Core Web Vitals performance
- SEO-friendly with crawlable HTML
- No bundlers, no server-side complexity
- Ship pure .html, .js, and .css files

## Architecture Overview

### 1. Storage Engine with ACL (Week 1)

**Purpose**: Abstracted storage layer supporting multiple backends with access control

**Components**:
- **Interface**: `StorageProvider` with methods for read/write/list operations
- **Implementations**:
  - `FileSystemStorage` (primary implementation)
  - `S3Storage` (future extension)
  - `DatabaseStorage` (future extension)
- **ACL System**: Role-based permissions (read, write, admin)
- **Location**: `/storage` package

**Key Features**:
- Pluggable storage backends
- Consistent API across all storage types
- Fine-grained access control
- Easy testing with mock implementations

### 2. PDF Indexing & Synchronization (Week 1-2)

**Purpose**: Automated discovery, indexing, and thumbnail generation for PDFs

**Components**:
- **PDF Scanner**: Crawls storage for PDF files
- **Thumbnail Generator**: Uses PDFBox to create image thumbnails
- **Metadata Extractor**: Extracts comprehensive PDF metadata including custom properties
- **Metadata Repository**: Abstracted layer supporting multiple persistence strategies
- **Index Storage**: Files stored alongside PDFs (`metadata.json`, `thumbnails/`) loaded into memory
- **Sync Strategy**: Compare file timestamps for incremental updates

**Sync Process**:
1. Scan storage for PDF files
2. Compare with existing index
3. Generate thumbnails for new/modified PDFs
4. Extract and cache metadata (including custom properties)
5. Update metadata files and reload in-memory index

### 3. Backend API (Week 2)

**Purpose**: REST API for frontend communication

**Endpoints**:
- `GET /api/pdfs` - List all PDFs with pagination and filtering
- `GET /api/pdfs/{id}` - Get specific PDF details
- `GET /api/thumbnails/{id}` - Serve thumbnail images
- `POST /api/sync` - Trigger manual synchronization
- `GET /api/search?q={query}` - Search PDFs by metadata

**Features**:
- **In-Memory Search**: Sub-millisecond searches on loaded metadata
- **Pagination**: Handle large collections efficiently from memory
- **Advanced Filtering**: Search by standard and custom metadata properties
- **Error Handling**: Comprehensive error responses
- **Content Negotiation**: Support for different response formats

### 4. Frontend UI (Week 2-3)

**Purpose**: Responsive web interface for PDF browsing

**Components**:
- **Grid Layout**: Responsive thumbnail grid using CSS Grid
- **Virtual Scrolling**: Handle thousands of PDFs without performance issues
- **Search & Filter**: Real-time filtering by name, date, metadata
- **Thumbnail View**: Lazy loading for optimal performance
- **PDF Viewer**: Embedded viewer for preview/reading

**UI Features**:
- Infinite scroll with virtual rendering
- Search with debounced input
- Sort by date, name, size
- Responsive design for mobile/desktop
- Dark/light theme support

### 5. Testing Strategy (Throughout Development)

**Unit Tests**:
- 90%+ coverage for all business logic
- Mock external dependencies
- Test all storage implementations
- Comprehensive error scenario testing

**Integration Tests**:
- Storage provider implementations
- API endpoint functionality
- PDF processing pipeline
- Thumbnail generation

**Performance Tests**:
- Large dataset handling (10k+ PDFs)
- Memory usage under load
- API response times
- Frontend rendering performance

**Test Infrastructure**:
- Test containers for filesystem testing
- Mock storage for unit tests
- Automated test data generation

## Implementation Timeline

### Week 1: Core Infrastructure
1. **Day 1-2**: Storage abstraction layer + FileSystem implementation
2. **Day 3-4**: PDF indexing service with thumbnail generation
3. **Day 5**: Basic sync service

### Week 2: API and Integration
1. **Day 1-2**: REST API with caching
2. **Day 3-4**: Sync service with incremental updates
3. **Day 5**: API testing and optimization

### Week 3: Frontend and Polish
1. **Day 1-3**: Frontend UI with virtual scrolling
2. **Day 4-5**: Integration testing and performance optimization

## Project Structure

```
src/main/kotlin/com/example/
├── storage/           # Storage abstraction & implementations
│   ├── StorageProvider.kt
│   ├── FileSystemStorage.kt
│   └── ACLManager.kt
├── indexing/          # PDF scanning, metadata extraction
│   ├── PDFScanner.kt
│   ├── ThumbnailGenerator.kt
│   └── MetadataExtractor.kt
├── sync/              # Synchronization service
│   ├── SyncService.kt
│   └── IndexManager.kt
├── api/               # REST controllers
│   ├── PDFController.kt
│   └── SyncController.kt
├── metadata/          # Metadata abstraction layer
│   ├── MetadataRepository.kt
│   ├── InMemoryMetadataRepository.kt
│   └── PDFMetadata.kt
├── cache/             # Caching layer
│   └── CacheManager.kt
└── web/               # Static resources
    ├── index.html
    ├── app.js
    └── styles.css

src/test/kotlin/com/example/
├── storage/           # Storage tests
├── indexing/          # Indexing tests
├── sync/              # Sync tests
├── api/               # API tests
└── integration/       # Integration tests
```

## Technical Decisions and Assumptions

### Core Assumptions
- **Single JAR Deployment**: Everything must be packaged in one executable JAR
- **Filesystem Constraint**: All data stored on filesystem (no external servers)
- **Read Performance Priority**: Optimized for fast searches and queries
- **Write Performance Secondary**: Sync operations can be slower, run in background
- **Thousands of PDFs**: System designed to handle large collections efficiently

### Why In-Memory Metadata with File Persistence?
**Optimal for the target scale (thousands of PDFs) with maximum simplicity:**

**Performance Benefits**:
- **Sub-millisecond searches**: No disk I/O for queries, pure in-memory operations
- **Complex filtering**: Use Kotlin collections for advanced search logic
- **Zero latency**: All metadata immediately available
- **Simple implementation**: No database complexity or dependencies

**Storage Flexibility**:
- **Works with any storage**: S3, filesystem, cloud storage - metadata travels with PDFs
- **Easy backup**: Simple file copy operations
- **Human readable**: JSON files for debugging and manual inspection
- **Version control friendly**: Text-based format for configuration management

**Scalability Strategy**:
- **Current target**: Thousands of PDFs fit comfortably in memory (~10-50MB metadata)
- **Future migration**: Clean abstraction allows SQLite upgrade when needed
- **No code changes**: Repository pattern isolates persistence implementation

### Metadata Repository Pattern
```kotlin
interface MetadataRepository {
    suspend fun getAllPDFs(): List<PDFMetadata>
    suspend fun getPDF(id: String): PDFMetadata?
    suspend fun savePDF(metadata: PDFMetadata)
    suspend fun deletePDF(id: String)
    suspend fun search(query: String): List<PDFMetadata>
    suspend fun searchByProperty(key: String, value: String): List<PDFMetadata>
    suspend fun count(): Long
}

// Initial implementation
class InMemoryMetadataRepository(private val storageProvider: StorageProvider) : MetadataRepository {
    private val cache = mutableMapOf<String, PDFMetadata>()

    fun loadFromStorage() {
        // Load metadata.json files into memory at startup
    }

    fun persistToStorage() {
        // Save changes back to metadata.json files
    }
}
```

### Enhanced PDF Metadata Structure
```kotlin
data class PDFMetadata(
    val id: String,
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val pageCount: Int,
    val createdDate: Instant?,
    val modifiedDate: Instant?,

    // Standard PDF metadata
    val title: String?,
    val author: String?,
    val subject: String?,
    val creator: String?,        // Application that created the PDF
    val producer: String?,       // PDF library used
    val keywords: List<String> = emptyList(),

    // Custom metadata for extensibility
    val customProperties: Map<String, String> = emptyMap(),

    // System metadata
    val thumbnailPath: String?,
    val contentHash: String?,
    val indexedAt: Instant
)
```

**Custom Properties Examples**:
- **Books**: `genre=sci-fi`, `series=Dune`, `volume=1`, `isbn=978-0441013593`
- **Comics**: `series=Spider-Man`, `issue=1`, `publisher=Marvel`, `year=1963`
- **Documents**: `category=manual`, `product=iPhone`, `version=iOS-17`

### Why No External Database Server?

- **Single JAR Constraint**: Must package everything in one executable
- **Zero Setup**: No external dependencies or configuration required
- **Portability**: Run anywhere with just Java, no database installation
- **Backup Simplicity**: Standard file system backup tools work
- **Development Speed**: No database schema migrations or server management

### Why Vanilla JavaScript?
- **Performance**: No framework overhead, direct DOM manipulation
- **Maintenance**: No dependency updates or security vulnerabilities
- **Learning Curve**: Pure web standards, no framework-specific knowledge
- **Future-Proof**: Web standards evolve slowly and maintain compatibility

### Why Kotlin + Ktor?
- **Type Safety**: Reduces runtime errors
- **Coroutines**: Excellent for I/O-heavy operations like PDF processing
- **Lightweight**: Ktor provides just what we need without bloat
- **Java Ecosystem**: Access to mature libraries like PDFBox

## Performance Considerations

### Frontend
- Virtual scrolling for large lists
- Lazy loading of thumbnails
- Debounced search input
- Efficient DOM updates

### Backend
- Async processing for PDF operations
- Incremental sync to avoid full rescans
- In-memory caching for frequently accessed data
- Streaming responses for large datasets

### Storage & Metadata
- In-memory metadata for instant searches
- Efficient Kotlin collections filtering and sorting
- Lazy thumbnail loading from storage
- Optimized file system operations
- Background sync processing with incremental updates
- Custom property indexing in memory

## Future Extensions

### Storage Backends
- AWS S3 integration
- Google Cloud Storage
- Azure Blob Storage
- Database storage for metadata

### Features
- Full-text search within PDFs
- Collections/folders organization
- User authentication and authorization
- PDF annotation support
- Collaborative features

### Performance
- CDN integration for thumbnails
- Distributed caching (Redis)
- Horizontal scaling with database replication
- Background processing queues
- Migration to dedicated database server for very large collections

This plan provides a solid foundation that can evolve with your needs while maintaining simplicity and performance.