package com.example.repository

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.example.metadata.PDFMetadata
import kotlin.jvm.JvmStatic
import kotlin.time.Duration

class RepositoryManager(
    private val repository: InMemoryMetadataRepository,
    private val consistencyManager: ConsistencyManager
) {

    companion object {
        @JvmStatic
        private val logger = org.slf4j.LoggerFactory.getLogger(RepositoryManager::class.java)

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }

    private var initializationTime: Instant? = null
    private var lastConsistencyCheck: Instant? = null

    suspend fun initialize(): InitializationResult {
        val startTime = Clock.System.now()
        logger.info("Initializing repository manager...")

        return try {
            // Load metadata from storage
            logger.info("Loading metadata from storage...")
            repository.loadFromStorage()

            val count = repository.count()
            logger.info("Loaded $count PDF metadata records")

            // Perform consistency check
            logger.info("Performing consistency check...")
            val report = consistencyManager.performConsistencyCheck()
            lastConsistencyCheck = Clock.System.now()

            if (!report.isConsistent) {
                logger.warn("Detected consistency issues: ${report.summarize()}")
                val repairResult = consistencyManager.repairInconsistencies(report)
                logger.info("Consistency repair completed: ${repairResult.summarize()}")

                if (!repairResult.isSuccessful) {
                    logger.warn("Some consistency issues could not be repaired automatically")
                }
            }

            // Perform data validation
            val validationResult = consistencyManager.validateDataIntegrity()
            if (!validationResult.isValid) {
                logger.warn("Data integrity issues found: ${validationResult.summarize()}")
            }

            initializationTime = Clock.System.now()
            val duration = initializationTime!! - startTime

            val result = InitializationResult.Success(
                recordsLoaded = count,
                consistencyReport = report,
                validationResult = validationResult,
                initializationDuration = duration
            )

            logger.info("Repository initialization completed successfully in ${duration}")
            result

        } catch (e: Exception) {
            logger.error("Repository initialization failed", e)
            InitializationResult.Error(e.message ?: "Unknown error", e)
        }
    }

    suspend fun shutdown(): ShutdownResult {
        logger.info("Shutting down repository manager...")

        return try {
            val startTime = Clock.System.now()
            val recordCount = repository.count()

            logger.info("Persisting $recordCount metadata records to storage...")
            repository.persistToStorage()

            val duration = Clock.System.now() - startTime

            val result = ShutdownResult.Success(
                recordsPersisted = recordCount,
                shutdownDuration = duration
            )

            logger.info("Repository shutdown completed successfully in ${duration}")
            result

        } catch (e: Exception) {
            logger.error("Repository shutdown failed", e)
            ShutdownResult.Error(e.message ?: "Unknown error", e)
        }
    }

    suspend fun performMaintenanceCheck(): MaintenanceResult {
        logger.info("Performing repository maintenance check...")

        val startTime = Clock.System.now()

        return try {
            // Run full consistency check
            val fullResult = consistencyManager.performFullConsistencyCheck()
            lastConsistencyCheck = Clock.System.now()

            // Get memory usage info
            val memoryInfo = repository.getMemoryUsageInfo()

            // Calculate metrics
            val metrics = calculateMetrics()

            val result = MaintenanceResult.Success(
                fullConsistencyResult = fullResult,
                memoryInfo = memoryInfo,
                metrics = metrics,
                checkDuration = Clock.System.now() - startTime
            )

            logger.info("Maintenance check completed: ${result.summarize()}")
            result

        } catch (e: Exception) {
            logger.error("Maintenance check failed", e)
            MaintenanceResult.Error(e.message ?: "Unknown error", e)
        }
    }

    suspend fun createBackup(backupPath: String? = null): BackupResult {
        val timestamp = Clock.System.now()
        val actualBackupPath = backupPath ?: "backup-${timestamp.epochSeconds}"

        logger.info("Creating backup to: $actualBackupPath")

        return try {
            val allMetadata = repository.getAllPDFs()
            val memoryInfo = repository.getMemoryUsageInfo()

            val backupData = BackupData(
                timestamp = timestamp,
                version = "1.0",
                metadata = allMetadata,
                memoryInfo = memoryInfo,
                totalRecords = allMetadata.size
            )

            val jsonString = json.encodeToString(backupData)

            val result = BackupResult.Success(
                path = actualBackupPath,
                recordCount = allMetadata.size,
                backupData = jsonString
            )

            logger.info("Backup created successfully: ${allMetadata.size} records")
            result

        } catch (e: Exception) {
            logger.error("Backup creation failed", e)
            BackupResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun getStatus(): RepositoryStatus {
        val recordCount = repository.count()
        val memoryInfo = repository.getMemoryUsageInfo()
        val metrics = calculateMetrics()

        return RepositoryStatus(
            isInitialized = initializationTime != null,
            initializationTime = initializationTime,
            lastConsistencyCheck = lastConsistencyCheck,
            totalRecords = recordCount,
            memoryInfo = memoryInfo,
            metrics = metrics
        )
    }

    private suspend fun calculateMetrics(): RepositoryMetrics {
        val allMetadata = repository.getAllPDFs()

        // Simple search time measurement
        val searchStartTime = Clock.System.now()
        repository.search("test")
        val searchDuration = Clock.System.now() - searchStartTime

        return RepositoryMetrics(
            totalRecords = allMetadata.size.toLong(),
            averageSearchTime = searchDuration,
            lastPersistTime = initializationTime
        )
    }
}

@Serializable
data class BackupData(
    val timestamp: Instant,
    val version: String,
    val metadata: List<PDFMetadata>,
    val memoryInfo: RepositoryMemoryInfo,
    val totalRecords: Int
)

@Serializable
data class RepositoryMetrics(
    val totalRecords: Long,
    val averageSearchTime: Duration,
    val lastPersistTime: Instant?
)

@Serializable
data class RepositoryStatus(
    val isInitialized: Boolean,
    val initializationTime: Instant?,
    val lastConsistencyCheck: Instant?,
    val totalRecords: Long,
    val memoryInfo: RepositoryMemoryInfo,
    val metrics: RepositoryMetrics
)

sealed class InitializationResult {
    data class Success(
        val recordsLoaded: Long,
        val consistencyReport: ConsistencyReport,
        val validationResult: ValidationResult,
        val initializationDuration: Duration
    ) : InitializationResult()

    data class Error(
        val message: String,
        val exception: Throwable
    ) : InitializationResult()
}

sealed class ShutdownResult {
    data class Success(
        val recordsPersisted: Long,
        val shutdownDuration: Duration
    ) : ShutdownResult()

    data class Error(
        val message: String,
        val exception: Throwable
    ) : ShutdownResult()
}

sealed class MaintenanceResult {
    data class Success(
        val fullConsistencyResult: FullConsistencyResult,
        val memoryInfo: RepositoryMemoryInfo,
        val metrics: RepositoryMetrics,
        val checkDuration: Duration
    ) : MaintenanceResult() {
        fun summarize(): String {
            return "Records: ${memoryInfo.totalRecords}, " +
                    "Consistent: ${fullConsistencyResult.consistencyReport.isConsistent}, " +
                    "Valid: ${fullConsistencyResult.validationResult.isValid}, " +
                    "Duration: ${checkDuration}"
        }
    }

    data class Error(
        val message: String,
        val exception: Throwable
    ) : MaintenanceResult()
}

sealed class BackupResult {
    data class Success(
        val path: String,
        val recordCount: Int,
        val backupData: String
    ) : BackupResult()

    data class Error(
        val message: String
    ) : BackupResult()
}