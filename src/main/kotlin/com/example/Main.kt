package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.auth.AuthService
import com.example.auth.AuthStore
import com.example.auth.LoginFinishRequest
import com.example.auth.LoginStartRequest
import com.example.auth.RegisterStartRequest
import com.example.config.AppConfiguration
import com.example.config.ConfigurationManager
import com.example.repository.*
import com.example.storage.FileSystemStorage
import com.example.storage.S3StorageProvider
import com.example.storage.StorageProvider
import com.example.sync.ExtractionProgress
import com.example.sync.SyncService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Date

private val logger = LoggerFactory.getLogger("Main")

// Global application components
lateinit var appConfig: AppConfiguration
lateinit var repository: MetadataRepository
lateinit var repositoryManager: RepositoryManager
lateinit var syncService: SyncService
lateinit var textContentStore: TextContentStore
lateinit var authService: AuthService
lateinit var metadataStorage: StorageProvider
lateinit var jwtSecret: String
var appUrl: String? = null

fun main() {
    logger.info("Starting PDF Library Server...")

    jwtSecret = System.getenv("JWT_SECRET")
        ?: error("JWT_SECRET environment variable is required")

    appUrl = System.getenv("APP_URL")

    // Load config synchronously so it is available for the JWT plugin setup
    appConfig = runBlocking { ConfigurationManager().loadConfiguration() }
    logger.info("Configuration loaded: ${appConfig.pdfScanPaths.size} scan path(s), rpId=${appConfig.rpId}")

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureContentNegotiation()
        configureSecurityHeaders()
        appUrl?.let { configureCors(it) }
        configureAuth()
        configureApplication()
        configureRouting()
    }.start(wait = true)
}

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) { json() }
}

fun Application.configureSecurityHeaders() {
    install(DefaultHeaders) {
        header("X-Frame-Options", "DENY")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'"
        )
    }
}

fun Application.configureCors(appUrl: String) {
    val host = appUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
    install(CORS) {
        allowHost(host, schemes = listOf("https", "http"))
        allowCredentials = true
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Post)
    }
}

fun Application.configureAuth() {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = appConfig.rpName
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(appConfig.jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("sub").asString() != null)
                    JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                if (call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true) {
                    call.respondRedirect("/login")
                } else {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("Authentication required"))
                }
            }
        }

        // Optional variant — used for bootstrap-protected register endpoints
        jwt("auth-jwt-optional") {
            realm = appConfig.rpName
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(appConfig.jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("sub").asString() != null)
                    JWTPrincipal(credential.payload) else null
            }
            // No challenge: unauthenticated requests pass through; principal is just null
        }
    }
}

fun Application.configureApplication() {
    launch {
        try {
            logger.info("Initializing PDF Library components...")

            val s3Bucket = appConfig.s3Bucket
            val s3Region = appConfig.s3Region
            metadataStorage = if (s3Bucket != null && s3Region != null) {
                logger.info("Using S3 metadata storage: bucket=$s3Bucket, region=$s3Region")
                S3StorageProvider(
                    bucket = s3Bucket,
                    region = s3Region,
                    basePath = appConfig.metadataStoragePath,
                    keyPrefix = appConfig.s3KeyPrefix,
                    endpointUrl = appConfig.s3EndpointUrl
                )
            } else {
                logger.info("Using filesystem metadata storage at: ${appConfig.metadataStoragePath}")
                FileSystemStorage(appConfig.metadataStoragePath)
            }

            val pdfStorage = FileSystemStorage("/")

            textContentStore = TextContentStore(metadataStorage)

            val jsonRepository = JsonRepository(metadataStorage, appConfig.metadataStoragePath)
            val inMemoryRepository = InMemoryMetadataRepository(jsonRepository, textContentStore)
            repository = inMemoryRepository

            val consistencyManager = ConsistencyManager(inMemoryRepository, jsonRepository)
            repositoryManager = RepositoryManager(inMemoryRepository, consistencyManager)

            val initResult = repositoryManager.initialize()
            when (initResult) {
                is InitializationResult.Success ->
                    logger.info("Repository initialized: ${initResult.recordsLoaded} records loaded")
                is InitializationResult.Error ->
                    logger.error("Repository initialization failed", initResult.exception)
            }

            val authStore = AuthStore(metadataStorage)
            authStore.loadFromDisk()
            authService = AuthService(appConfig.rpId, appConfig.rpName, authStore)
            logger.info("Auth service initialized")

            syncService = SyncService(pdfStorage, appConfig, repository, textContentStore, metadataStorage)
            syncService.addProgressListener(com.example.scanning.ConsoleScanProgressListener())
            logger.info("PDF Library Server ready!")

            launch {
                logger.info("Starting background metadata sync...")
                val resumeResult = syncService.resumeExtraction()
                if (resumeResult.errors.any { it.path == "MANIFEST" }) {
                    logger.info("No manifest found — performing initial full sync...")
                    val fullResult = syncService.performFullSync()
                    logger.info("Initial sync complete: ${fullResult.summarize()}")
                } else {
                    logger.info("Background extraction complete: ${resumeResult.summarize()}")
                    logger.info("Running incremental scan...")
                    val incrResult = syncService.performIncrementalSync()
                    logger.info("Startup incremental scan complete: ${incrResult.summarize()}")
                }

                if (appConfig.scanning.enableFileWatching) {
                    syncService.startFileWatching(this)
                }

                val intervalMinutes = appConfig.scanning.syncIntervalMinutes
                if (intervalMinutes > 0) {
                    launch {
                        while (isActive) {
                            delay(intervalMinutes * 60_000L)
                            logger.info("Scheduled incremental sync (every ${intervalMinutes}m)...")
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

        // ---- Public: login page + assets ----

        get("/login") {
            val bytes = Thread.currentThread().contextClassLoader
                .getResourceAsStream("login.html")
                ?.readBytes()
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondBytes(bytes, ContentType.Text.Html)
        }

        get("/login.js") {
            val bytes = Thread.currentThread().contextClassLoader
                .getResourceAsStream("login.js")
                ?.readBytes()
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondBytes(bytes, ContentType.Text.JavaScript)
        }

        // ---- Public: auth endpoints ----

        route("/api/auth") {

            // Register start — requires JWT once any credential exists (bootstrap protection)
            authenticate("auth-jwt-optional") {
                post("/register/start") {
                    if (!::authService.isInitialized) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                        return@post
                    }
                    if (authService.hasCredentials() && call.principal<JWTPrincipal>() == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("Authentication required"))
                        return@post
                    }
                    try {
                        val req = call.receive<RegisterStartRequest>()
                        val resp = authService.startRegistration(req.username, req.displayName)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(resp))
                    } catch (e: Exception) {
                        logger.error("register/start failed", e)
                        call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed"))
                    }
                }

                post("/register/finish") {
                    if (!::authService.isInitialized) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                        return@post
                    }
                    if (authService.hasCredentials() && call.principal<JWTPrincipal>() == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("Authentication required"))
                        return@post
                    }
                    try {
                        val req = call.receive<RegisterFinishRequest>()
                        authService.finishRegistration(
                            req.sessionId,
                            Json.encodeToString(req.credential),
                            req.username,
                            req.displayName
                        )
                        call.respond(HttpStatusCode.OK, ApiResponse.success("Credential registered"))
                    } catch (e: Exception) {
                        logger.error("register/finish failed", e)
                        call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed"))
                    }
                }
            }

            post("/login/start") {
                if (!::authService.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@post
                }
                try {
                    val req = call.receive<LoginStartRequest>()
                    val resp = authService.startLogin(req.username?.takeIf { it.isNotBlank() })
                    call.respond(HttpStatusCode.OK, ApiResponse.success(resp))
                } catch (e: Exception) {
                    logger.error("login/start failed", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed"))
                }
            }

            post("/login/finish") {
                if (!::authService.isInitialized) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                    return@post
                }
                try {
                    val req = call.receive<LoginFinishRequest>()
                    val username = authService.finishLogin(
                        req.sessionId,
                        Json.encodeToString(req.credential)
                    )
                    val token = issueJwt(username)
                    call.respond(HttpStatusCode.OK, ApiResponse.success(LoginTokenResponse(token, username)))
                } catch (e: Exception) {
                    logger.error("login/finish failed", e)
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse.error(e.message ?: "Login failed"))
                }
            }
        }

        // ---- Protected routes ----

        authenticate("auth-jwt") {

            staticResources("/static", "static")

            get("/") {
                call.respondRedirect("/static/index.html")
            }

            post("/api/auth/logout") {
                call.respond(HttpStatusCode.OK, ApiResponse.success("Logged out"))
            }

            get("/status") {
                try {
                    if (::repositoryManager.isInitialized && ::syncService.isInitialized) {
                        call.respond(HttpStatusCode.OK, ApiResponse.success(SystemStatus(
                            repository = repositoryManager.getStatus(),
                            syncInProgress = syncService.isSyncInProgress(),
                            extractionProgress = syncService.getExtractionProgress(),
                            fileWatchingEnabled = syncService.isFileWatchingEnabled()
                        )))
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
                        "retry-failed" -> syncService.performRetryFailed()
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

                    val fetched = if (query.isNullOrBlank()) repository.getAllPDFs() else repository.search(query)
                    val sorted = when (sort) {
                        "title"     -> fetched.sortedBy { (it.title ?: it.fileName).lowercase() }
                        "author"    -> fetched.sortedBy { it.author?.lowercase() ?: "" }
                        "indexedAt" -> fetched.sortedBy { it.indexedAt }
                        "fileSize"  -> fetched.sortedBy { it.fileSize }
                        else        -> fetched.sortedBy { it.fileName.lowercase() }
                    }
                    val allPdfs = if (order == "desc") sorted.reversed() else sorted
                    val startIndex = page * size
                    val paginatedPdfs = if (startIndex < allPdfs.size)
                        allPdfs.subList(startIndex, minOf(startIndex + size, allPdfs.size)) else emptyList()

                    call.respond(HttpStatusCode.OK, ApiResponse.success(PaginatedResponse(
                        data = paginatedPdfs, page = page, size = size,
                        total = allPdfs.size, totalPages = (allPdfs.size + size - 1) / size
                    )))
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
                    if (pdf != null) call.respond(HttpStatusCode.OK, ApiResponse.success(pdf))
                    else call.respond(HttpStatusCode.NotFound, ApiResponse.error("PDF not found"))
                } catch (e: Exception) {
                    logger.error("Failed to get PDF", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to get PDF"))
                }
            }

            get("/api/pdfs/{id}/file") {
                try {
                    if (!::repository.isInitialized) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                        return@get
                    }
                    val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing PDF ID")
                    val pdf = repository.getPDF(id)
                    if (pdf == null) {
                        call.respond(HttpStatusCode.NotFound, ApiResponse.error("PDF not found"))
                        return@get
                    }
                    val file = Paths.get(pdf.path)
                    if (!Files.exists(file)) {
                        call.respond(HttpStatusCode.NotFound, ApiResponse.error("File not found on disk"))
                        return@get
                    }
                    call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"${pdf.fileName}\"")
                    call.respondFile(file.toFile())
                } catch (e: Exception) {
                    logger.error("Failed to serve PDF file", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to serve PDF"))
                }
            }

            get("/api/thumbnails/{id}") {
                try {
                    if (!::repository.isInitialized || !::metadataStorage.isInitialized) {
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
                    val thumbnailPath = Paths.get(appConfig.metadataStoragePath, thumbnailRelPath).toString()
                    if (!metadataStorage.exists(thumbnailPath)) {
                        call.respond(HttpStatusCode.NotFound, ApiResponse.error("Thumbnail file not found"))
                        return@get
                    }
                    call.respondBytes(metadataStorage.read(thumbnailPath), ContentType.Image.PNG)
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
                    if (stats == null)
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("Stats not available"))
                    else
                        call.respond(HttpStatusCode.OK, ApiResponse.success(stats))
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
                    if (text == null)
                        call.respond(HttpStatusCode.NotFound, ApiResponse.error("No text content available"))
                    else
                        call.respondText(text, ContentType.Text.Plain)
                } catch (e: Exception) {
                    logger.error("Failed to get text content", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to get text content"))
                }
            }

            get("/api/manifest") {
                try {
                    if (!::syncService.isInitialized) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                        return@get
                    }
                    val manifest = syncService.loadManifest()
                    if (manifest == null) {
                        call.respond(HttpStatusCode.NotFound, ApiResponse.error("No manifest found — run a sync first"))
                        return@get
                    }
                    call.respond(HttpStatusCode.OK, ApiResponse.success(ManifestStatus(
                        lastDiscovery = manifest.lastDiscovery,
                        totalFiles = manifest.files.size,
                        extracted = manifest.files.count { it.status == com.example.scanning.FileStatus.EXTRACTED },
                        pending = manifest.files.count { it.status == com.example.scanning.FileStatus.DISCOVERED },
                        failed = manifest.files.count { it.status == com.example.scanning.FileStatus.FAILED },
                        failedFiles = manifest.files
                            .filter { it.status == com.example.scanning.FileStatus.FAILED }
                            .map { it.path }
                    )))
                } catch (e: Exception) {
                    logger.error("Failed to load manifest", e)
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse.error(e.message ?: "Failed to load manifest"))
                }
            }

            get("/api/search") {
                try {
                    if (!::repository.isInitialized) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiResponse.error("System not ready"))
                        return@get
                    }
                    val query = call.request.queryParameters["q"]
                        ?: throw IllegalArgumentException("Missing query parameter")
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
}

private fun issueJwt(username: String): String =
    JWT.create()
        .withIssuer(appConfig.jwtIssuer)
        .withSubject(username)
        .withExpiresAt(Date(System.currentTimeMillis() + 8 * 3600 * 1000L))
        .sign(Algorithm.HMAC256(jwtSecret))

// ---- Shared request/response types ----

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
data class SyncRequest(val type: String)

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

@Serializable
data class ManifestStatus(
    val lastDiscovery: kotlin.time.Instant,
    val totalFiles: Int,
    val extracted: Int,
    val pending: Int,
    val failed: Int,
    val failedFiles: List<String>
)

@Serializable
data class LoginTokenResponse(val token: String, val username: String)

@Serializable
data class RegisterFinishRequest(
    val sessionId: String,
    val credential: JsonElement,
    val username: String,
    val displayName: String
)
