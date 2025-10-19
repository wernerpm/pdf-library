package com.example

import com.example.config.AppConfiguration
import com.example.config.ConfigurationManager
import com.example.repository.*
import com.example.storage.FileSystemStorage
import com.example.sync.SyncService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Main")

// Global application components
lateinit var repository: MetadataRepository
lateinit var repositoryManager: RepositoryManager
lateinit var syncService: SyncService

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
            logger.info("Configuration loaded: ${config.pdfScanPaths.size} scan paths configured")

            // Initialize storage
            val storage = FileSystemStorage(config.metadataStoragePath)
            logger.info("Storage initialized at: ${config.metadataStoragePath}")

            // Initialize repository system
            val persistenceManager = JsonPersistenceManager(storage, config.metadataStoragePath)
            repository = InMemoryMetadataRepository(storage, config)
            val consistencyManager = ConsistencyManager(repository as InMemoryMetadataRepository, persistenceManager)
            repositoryManager = RepositoryManager(repository as InMemoryMetadataRepository, consistencyManager)

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

            // Initialize sync service
            syncService = SyncService(storage, config, repository)
            logger.info("Sync service initialized")

            // Perform initial sync
            logger.info("Starting initial sync...")
            val syncResult = syncService.performIncrementalSync()
            logger.info("Initial sync completed: ${syncResult.summarize()}")

            logger.info("PDF Library Server fully initialized and ready!")

        } catch (e: Exception) {
            logger.error("Failed to initialize PDF Library", e)
            throw e
        }
    }
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("PDF Library - Server is running!")
        }

        get("/status") {
            try {
                if (::repositoryManager.isInitialized) {
                    val status = repositoryManager.getStatus()
                    call.respond(HttpStatusCode.OK, ApiResponse.success(status))
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
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
                val query = call.request.queryParameters["q"]

                val allPdfs = if (query.isNullOrBlank()) {
                    repository.getAllPDFs()
                } else {
                    repository.search(query)
                }

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