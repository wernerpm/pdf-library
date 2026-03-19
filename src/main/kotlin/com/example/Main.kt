package com.example

import com.example.config.AppConfiguration
import com.example.config.ConfigurationManager
import com.example.repository.*
import com.example.storage.FileSystemStorage
import com.example.sync.ExtractionProgress
import com.example.sync.SyncService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

private val logger = LoggerFactory.getLogger("Main")

// Global application components
lateinit var appConfig: AppConfiguration
lateinit var repository: MetadataRepository
lateinit var repositoryManager: RepositoryManager
lateinit var syncService: SyncService
lateinit var textContentStore: TextContentStore

fun main() {
    logger.info("Starting PDF Library Server...")

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureContentNegotiation()
        configureApplication()
        configureRouting()
    }.start(wait = true)
}

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json()
    }
}

fun Application.configureApplication() {
    launch {
        try {
            logger.info("Initializing PDF Library components...")

            // Load configuration
            val configManager = ConfigurationManager()
            val config = configManager.loadConfiguration()
            appConfig = config
            logger.info("Configuration loaded: ${config.pdfScanPaths.size} scan paths configured")

            // Initialize storage
            val metadataStorage = FileSystemStorage(config.metadataStoragePath)
            logger.info("Metadata storage initialized at: ${config.metadataStoragePath}")

            // Initialize PDF file storage (unrestricted for scanning)
            val pdfStorage = FileSystemStorage("/")
            logger.info("PDF storage initialized for filesystem scanning")

            // Initialize text content store (per-PDF full-text index)
            textContentStore = TextContentStore(metadataStorage)

            // Initialize repository system with layered architecture
            // JsonRepository provides persistent storage
            val jsonRepository = JsonRepository(metadataStorage, config.metadataStoragePath)

            // InMemoryMetadataRepository provides caching layer on top of JsonRepository
            val inMemoryRepository = InMemoryMetadataRepository(jsonRepository, textContentStore)
            repository = inMemoryRepository

            // ConsistencyManager monitors consistency between cache and backing store
            val consistencyManager = ConsistencyManager(inMemoryRepository, jsonRepository)
            repositoryManager = RepositoryManager(inMemoryRepository, consistencyManager)

            // Initialize repository
            val initResult = repositoryManager.initialize()
            when (initResult) {
                is InitializationResult.Success -> {
                    logger.info("Repository initialized successfully: ${initResult.recordsLoaded} records loaded")
                }
                is InitializationResult.Error -> {
                    logger.error("Repository initialization failed", initResult.exception)
                }
            }

            // Initialize sync service (use pdfStorage for scanning, metadataStorage for metadata)
            syncService = SyncService(pdfStorage, config, repository, textContentStore)
            logger.info("Sync service initialized")

            // Register console progress listener
            syncService.addProgressListener(com.example.scanning.ConsoleScanProgressListener())
            logger.info("Console progress listener registered")

            logger.info("PDF Library Server ready! (sync running in background)")

            // Resume extraction from the existing manifest without overwriting it.
            // This preserves progress across restarts and avoids losing entries for
            // scan paths that may be temporarily unavailable (e.g. network volumes).
            // If no manifest exists yet, fall back to a full sync to build one.
            launch {
                logger.info("Starting background metadata sync...")
                val resumeResult = syncService.resumeExtraction()
                if (resumeResult.errors.any { it.path == "MANIFEST" }) {
                    logger.info("No manifest found — performing initial full sync...")
                    val fullResult = syncService.performFullSync()
                    logger.info("Initial sync complete: ${fullResult.summarize()}")
                } else {
                    logger.info("Background extraction complete: ${resumeResult.summarize()}")
                }

                // Start file watching for local paths (does not work on network volumes)
                if (appConfig.scanning.enableFileWatching) {
                    syncService.startFileWatching(this)
                }

                // Start scheduled incremental sync (useful for network volumes where WatchService doesn't work)
                val intervalMinutes = appConfig.scanning.syncIntervalMinutes
                if (intervalMinutes > 0) {
                    launch {
                        while (isActive) {
                            delay(intervalMinutes * 60_000L)
                            logger.info("Scheduled incremental sync starting (every ${intervalMinutes}m)...")
                            val result = syncService.performIncrementalSync()
                            logger.info("Scheduled sync complete: ${result.summarize()}")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to initialize PDF Library", e)
            throw e
        }
    }
}

fun Application.configureRouting() {
    routing {
        staticResources("/static", "static")

        get("/") {
            call.respondRedirect("/static/index.html")
        }

        get("/status") {
            try {
                if (::repositoryManager.isInitialized && ::syncService.isInitialized) {
                    val repositoryStatus = repositoryManager.getStatus()
                    val syncInProgress = syncService.isSyncInProgress()

                    val systemStatus = SystemStatus(
                        repository = repositoryStatus,
                        syncInProgress = syncInProgress,
                        extractionProgress = syncService.getExtractionProgress(),
                        fileWatchingEnabled = syncService.isFileWatchingEnabled()
                    )

                    call.respond(HttpStatusCode.OK, ApiResponse.success(systemStatus))
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                }
            } catch (e: Exception) {
                logger.error("Failed to get status", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Unknown error"))
            }
        }

        post("/api/sync") {
            try {
                if (!::syncService.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@post
                }
                val request = call.receive<SyncRequest>()
                val syncResult = when (request.type) {
                    "full" -> syncService.performFullSync()
                    "incremental" -> syncService.performIncrementalSync()
                    else -> throw IllegalArgumentException("Invalid sync type: ${request.type}")
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(syncResult))
            } catch (e: Exception) {
                logger.error("Sync operation failed", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Sync failed"))
            }
        }

        get("/api/pdfs") {
            try {
                if (!::repository.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@get
                }
                val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val query = call.request.queryParameters["q"]
                val sort = call.request.queryParameters["sort"] ?: "fileName"
                val order = call.request.queryParameters["order"] ?: "asc"

                val fetched = if (query.isNullOrBlank()) {
                    repository.getAllPDFs()
                } else {
                    repository.search(query)
                }

                val sorted = when (sort) {
                    "title"     -> fetched.sortedBy { (it.title ?: it.fileName).lowercase() }
                    "author"    -> fetched.sortedBy { it.author?.lowercase() ?: "" }
                    "indexedAt" -> fetched.sortedBy { it.indexedAt }
                    "fileSize"  -> fetched.sortedBy { it.fileSize }
                    else        -> fetched.sortedBy { it.fileName.lowercase() }
                }
                val allPdfs = if (order == "desc") sorted.reversed() else sorted

                // Simple pagination
                val startIndex = page * size
                val endIndex = minOf(startIndex + size, allPdfs.size)
                val paginatedPdfs = if (startIndex < allPdfs.size) {
                    allPdfs.subList(startIndex, endIndex)
                } else {
                    emptyList()
                }

                val response = PaginatedResponse(
                    data = paginatedPdfs,
                    page = page,
                    size = size,
                    total = allPdfs.size,
                    totalPages = (allPdfs.size + size - 1) / size
                )

                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            } catch (e: Exception) {
                logger.error("Failed to get PDFs", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to get PDFs"))
            }
        }

        get("/api/pdfs/{id}") {
            try {
                if (!::repository.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@get
                }
                val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing PDF ID")
                val pdf = repository.getPDF(id)

                if (pdf != null) {
                    call.respond(HttpStatusCode.OK, ApiResponse.success(pdf))
                } else {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error("PDF not found"))
                }
            } catch (e: Exception) {
                logger.error("Failed to get PDF", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to get PDF"))
            }
        }

        get("/api/thumbnails/{id}") {
            try {
                if (!::repository.isInitialized || !::appConfig.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@get
                }
                val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing PDF ID")
                val pdf = repository.getPDF(id)

                if (pdf == null) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error("PDF not found"))
                    return@get
                }

                val thumbnailRelPath = pdf.thumbnailPath
                if (thumbnailRelPath == null) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error("No thumbnail available"))
                    return@get
                }

                val thumbnailFile = Paths.get(appConfig.metadataStoragePath, thumbnailRelPath)
                if (!Files.exists(thumbnailFile)) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error("Thumbnail file not found"))
                    return@get
                }

                val bytes = Files.readAllBytes(thumbnailFile)
                call.respondBytes(bytes, ContentType.Image.PNG)
            } catch (e: Exception) {
                logger.error("Failed to get thumbnail", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to get thumbnail"))
            }
        }

        get("/api/stats") {
            try {
                if (!::repository.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@get
                }
                val stats = (repository as? InMemoryMetadataRepository)?.computeStats()
                if (stats == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("Stats not available"))
                } else {
                    call.respond(HttpStatusCode.OK, ApiResponse.success(stats))
                }
            } catch (e: Exception) {
                logger.error("Failed to compute stats", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to compute stats"))
            }
        }

        get("/api/pdfs/{id}/text") {
            try {
                if (!::repository.isInitialized || !::textContentStore.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@get
                }
                val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing PDF ID")
                val pdf = repository.getPDF(id)
                if (pdf == null) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error("PDF not found"))
                    return@get
                }
                val text = textContentStore.get(id)
                if (text == null) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error("No text content available for this PDF"))
                } else {
                    call.respondText(text, ContentType.Text.Plain)
                }
            } catch (e: Exception) {
                logger.error("Failed to get text content", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to get text content"))
            }
        }

        get("/api/search") {
            try {
                if (!::repository.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@get
                }
                val query = call.request.queryParameters["q"] ?: throw IllegalArgumentException("Missing query parameter")
                val author = call.request.queryParameters["author"]
                val title = call.request.queryParameters["title"]
                val property = call.request.queryParameters["property"]
                val value = call.request.queryParameters["value"]

                val results = when {
                    author != null -> repository.searchByAuthor(author)
                    title != null -> repository.searchByTitle(title)
                    property != null && value != null -> repository.searchByProperty(property, value)
                    else -> repository.search(query)
                }

                call.respond(HttpStatusCode.OK, ApiResponse.success(results))
            } catch (e: Exception) {
                logger.error("Search failed", e)
                call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Search failed"))
            }
        }
    }
}

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
) {
    companion object {
        fun <T> success(data: T) = ApiResponse(success = true, data = data)
        fun error(message: String) = ApiResponse<Nothing>(success = false, error = message)
    }
}

@Serializable
data class SyncRequest(
    val type: String // "full" or "incremental"
)

@Serializable
data class PaginatedResponse<T>(
    val data: List<T>,
    val page: Int,
    val size: Int,
    val total: Int,
    val totalPages: Int
)

@Serializable
data class SystemStatus(
    val repository: com.example.repository.RepositoryStatus,
    val syncInProgress: Boolean,
    val extractionProgress: ExtractionProgress? = null,
    val fileWatchingEnabled: Boolean = false
)