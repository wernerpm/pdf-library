package com.example.repository

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmStatic

/**
 * Manages consistency between in-memory cache and backing storage.
 * Works with any MetadataRepository implementations.
 */
class ConsistencyManager(
    private val cachingRepository: InMemoryMetadataRepository,
    private val backingRepository: MetadataRepository
) {

    companion object {
        @JvmStatic
        private val logger = org.slf4j.LoggerFactory.getLogger(ConsistencyManager::class.java)
    }

    suspend fun performConsistencyCheck(): ConsistencyReport {
        logger.info("Starting consistency check...")

        val memoryIds = cachingRepository.getAllPDFs().map { it.id }.toSet()
        val persistedIds = backingRepository.getAllPDFs().map { it.id }.toSet()

        val orphanedInMemory = memoryIds - persistedIds
        val orphanedOnDisk = persistedIds - memoryIds

        val report = ConsistencyReport(
            totalInMemory = memoryIds.size,
            totalPersisted = persistedIds.size,
            orphanedInMemory = orphanedInMemory,
            orphanedOnDisk = orphanedOnDisk
        )

        logger.info("Consistency check completed: ${report.summarize()}")
        return report
    }

    suspend fun repairInconsistencies(report: ConsistencyReport): RepairResult {
        logger.info("Starting consistency repair...")

        var repairedInMemory = 0
        var repairedOnDisk = 0
        val errors = mutableListOf<String>()

        // Persist orphaned in-memory data
        for (id in report.orphanedInMemory) {
            try {
                cachingRepository.getPDF(id)?.let { metadata ->
                    backingRepository.savePDF(metadata)
                    repairedInMemory++
                    logger.debug("Persisted orphaned in-memory metadata: $id")
                }
            } catch (e: Exception) {
                val error = "Failed to persist metadata for $id: ${e.message}"
                logger.error(error, e)
                errors.add(error)
            }
        }

        // Load orphaned disk data
        for (id in report.orphanedOnDisk) {
            try {
                backingRepository.getPDF(id)?.let { metadata ->
                    cachingRepository.savePDF(metadata)
                    repairedOnDisk++
                    logger.debug("Loaded orphaned disk metadata: $id")
                }
            } catch (e: Exception) {
                val error = "Failed to load metadata for $id: ${e.message}"
                logger.error(error, e)
                errors.add(error)
            }
        }

        val result = RepairResult(
            repairedInMemory = repairedInMemory,
            repairedOnDisk = repairedOnDisk,
            errors = errors
        )

        logger.info("Consistency repair completed: ${result.summarize()}")
        return result
    }

    suspend fun validateDataIntegrity(): ValidationResult {
        logger.info("Starting data integrity validation...")

        val allMetadata = cachingRepository.getAllPDFs()
        val issues = mutableListOf<IntegrityIssue>()

        for (metadata in allMetadata) {
            // Check for required fields
            if (metadata.id.isBlank()) {
                issues.add(IntegrityIssue.MissingRequiredField(metadata.id, "id"))
            }
            if (metadata.path.isBlank()) {
                issues.add(IntegrityIssue.MissingRequiredField(metadata.id, "path"))
            }
            if (metadata.fileName.isBlank()) {
                issues.add(IntegrityIssue.MissingRequiredField(metadata.id, "fileName"))
            }

            // Check for logical consistency
            if (metadata.fileSize < 0) {
                issues.add(IntegrityIssue.InvalidValue(metadata.id, "fileSize", "negative value"))
            }
            if (metadata.pageCount < 0) {
                issues.add(IntegrityIssue.InvalidValue(metadata.id, "pageCount", "negative value"))
            }

            // Check for duplicate content hashes (if available)
            if (metadata.contentHash != null) {
                val duplicates = allMetadata.filter {
                    it.contentHash == metadata.contentHash && it.id != metadata.id
                }
                if (duplicates.isNotEmpty()) {
                    issues.add(IntegrityIssue.DuplicateContent(
                        metadata.id,
                        duplicates.map { it.id }
                    ))
                }
            }
        }

        val result = ValidationResult(
            totalRecords = allMetadata.size,
            issues = issues
        )

        logger.info("Data integrity validation completed: ${result.summarize()}")
        return result
    }

    suspend fun performFullConsistencyCheck(): FullConsistencyResult {
        val consistencyReport = performConsistencyCheck()
        val validationResult = validateDataIntegrity()

        return FullConsistencyResult(
            consistencyReport = consistencyReport,
            validationResult = validationResult
        )
    }
}

@Serializable
data class ConsistencyReport(
    val totalInMemory: Int,
    val totalPersisted: Int,
    val orphanedInMemory: Set<String>,
    val orphanedOnDisk: Set<String>
) {
    val isConsistent: Boolean = orphanedInMemory.isEmpty() && orphanedOnDisk.isEmpty()

    fun summarize(): String {
        return if (isConsistent) {
            "Memory: $totalInMemory, Disk: $totalPersisted - CONSISTENT"
        } else {
            "Memory: $totalInMemory, Disk: $totalPersisted - " +
                "Orphaned in memory: ${orphanedInMemory.size}, " +
                "Orphaned on disk: ${orphanedOnDisk.size}"
        }
    }
}

data class RepairResult(
    val repairedInMemory: Int,
    val repairedOnDisk: Int,
    val errors: List<String>
) {
    val isSuccessful: Boolean = errors.isEmpty()

    fun summarize(): String {
        return "Repaired $repairedInMemory in-memory, $repairedOnDisk on-disk" +
                if (errors.isNotEmpty()) ", ${errors.size} errors" else ""
    }
}

data class ValidationResult(
    val totalRecords: Int,
    val issues: List<IntegrityIssue>
) {
    val isValid: Boolean = issues.isEmpty()

    fun summarize(): String {
        return "Validated $totalRecords records" +
                if (issues.isNotEmpty()) ", found ${issues.size} issues" else " - ALL VALID"
    }
}

data class FullConsistencyResult(
    val consistencyReport: ConsistencyReport,
    val validationResult: ValidationResult
) {
    val isHealthy: Boolean = consistencyReport.isConsistent && validationResult.isValid
}

sealed class IntegrityIssue {
    abstract val recordId: String

    data class MissingRequiredField(
        override val recordId: String,
        val fieldName: String
    ) : IntegrityIssue()

    data class InvalidValue(
        override val recordId: String,
        val fieldName: String,
        val reason: String
    ) : IntegrityIssue()

    data class DuplicateContent(
        override val recordId: String,
        val duplicateIds: List<String>
    ) : IntegrityIssue()
}