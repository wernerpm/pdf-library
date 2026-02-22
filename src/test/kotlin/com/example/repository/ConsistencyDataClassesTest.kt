package com.example.repository

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsistencyDataClassesTest {

    // --- ConsistencyReport ---

    @Test
    fun `ConsistencyReport is consistent when no orphans exist`() {
        val report = ConsistencyReport(
            totalInMemory = 10,
            totalPersisted = 10,
            orphanedInMemory = emptySet(),
            orphanedOnDisk = emptySet()
        )
        assertTrue(report.isConsistent)
    }

    @Test
    fun `ConsistencyReport is inconsistent when orphaned in memory`() {
        val report = ConsistencyReport(
            totalInMemory = 11,
            totalPersisted = 10,
            orphanedInMemory = setOf("extra-id"),
            orphanedOnDisk = emptySet()
        )
        assertFalse(report.isConsistent)
    }

    @Test
    fun `ConsistencyReport is inconsistent when orphaned on disk`() {
        val report = ConsistencyReport(
            totalInMemory = 10,
            totalPersisted = 11,
            orphanedInMemory = emptySet(),
            orphanedOnDisk = setOf("orphan-id")
        )
        assertFalse(report.isConsistent)
    }

    @Test
    fun `ConsistencyReport is inconsistent when orphans on both sides`() {
        val report = ConsistencyReport(
            totalInMemory = 10,
            totalPersisted = 10,
            orphanedInMemory = setOf("mem-orphan"),
            orphanedOnDisk = setOf("disk-orphan")
        )
        assertFalse(report.isConsistent)
    }

    @Test
    fun `ConsistencyReport summarize shows CONSISTENT when consistent`() {
        val report = ConsistencyReport(
            totalInMemory = 5,
            totalPersisted = 5,
            orphanedInMemory = emptySet(),
            orphanedOnDisk = emptySet()
        )
        val summary = report.summarize()
        assertTrue(summary.contains("CONSISTENT"))
        assertTrue(summary.contains("5"))
    }

    @Test
    fun `ConsistencyReport summarize shows orphan counts when inconsistent`() {
        val report = ConsistencyReport(
            totalInMemory = 12,
            totalPersisted = 10,
            orphanedInMemory = setOf("a", "b"),
            orphanedOnDisk = setOf("c")
        )
        val summary = report.summarize()
        assertTrue(summary.contains("Orphaned in memory: 2"))
        assertTrue(summary.contains("Orphaned on disk: 1"))
    }

    @Test
    fun `ConsistencyReport is consistent with zero records`() {
        val report = ConsistencyReport(
            totalInMemory = 0,
            totalPersisted = 0,
            orphanedInMemory = emptySet(),
            orphanedOnDisk = emptySet()
        )
        assertTrue(report.isConsistent)
    }

    // --- RepairResult ---

    @Test
    fun `RepairResult is successful when no errors`() {
        val result = RepairResult(
            repairedInMemory = 3,
            repairedOnDisk = 2,
            errors = emptyList()
        )
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `RepairResult is not successful when errors exist`() {
        val result = RepairResult(
            repairedInMemory = 1,
            repairedOnDisk = 0,
            errors = listOf("Failed to persist id-1")
        )
        assertFalse(result.isSuccessful)
    }

    @Test
    fun `RepairResult summarize shows repair counts`() {
        val result = RepairResult(
            repairedInMemory = 3,
            repairedOnDisk = 2,
            errors = emptyList()
        )
        val summary = result.summarize()
        assertTrue(summary.contains("3 in-memory"))
        assertTrue(summary.contains("2 on-disk"))
        assertFalse(summary.contains("error"))
    }

    @Test
    fun `RepairResult summarize includes error count when errors exist`() {
        val result = RepairResult(
            repairedInMemory = 1,
            repairedOnDisk = 0,
            errors = listOf("error 1", "error 2")
        )
        val summary = result.summarize()
        assertTrue(summary.contains("2 errors"))
    }

    @Test
    fun `RepairResult with zero repairs and no errors is successful`() {
        val result = RepairResult(
            repairedInMemory = 0,
            repairedOnDisk = 0,
            errors = emptyList()
        )
        assertTrue(result.isSuccessful)
        assertTrue(result.summarize().contains("0 in-memory"))
    }

    // --- ValidationResult ---

    @Test
    fun `ValidationResult is valid when no issues`() {
        val result = ValidationResult(
            totalRecords = 10,
            issues = emptyList()
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `ValidationResult is invalid when issues exist`() {
        val result = ValidationResult(
            totalRecords = 10,
            issues = listOf(IntegrityIssue.MissingRequiredField("id-1", "path"))
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `ValidationResult summarize shows ALL VALID when valid`() {
        val result = ValidationResult(
            totalRecords = 5,
            issues = emptyList()
        )
        val summary = result.summarize()
        assertTrue(summary.contains("ALL VALID"))
        assertTrue(summary.contains("5"))
    }

    @Test
    fun `ValidationResult summarize shows issue count when invalid`() {
        val result = ValidationResult(
            totalRecords = 10,
            issues = listOf(
                IntegrityIssue.MissingRequiredField("id-1", "path"),
                IntegrityIssue.InvalidValue("id-2", "fileSize", "negative value")
            )
        )
        val summary = result.summarize()
        assertTrue(summary.contains("2 issues"))
        assertTrue(summary.contains("10"))
    }

    @Test
    fun `ValidationResult with zero records and no issues is valid`() {
        val result = ValidationResult(totalRecords = 0, issues = emptyList())
        assertTrue(result.isValid)
    }

    // --- FullConsistencyResult ---

    @Test
    fun `FullConsistencyResult is healthy when both consistent and valid`() {
        val result = FullConsistencyResult(
            consistencyReport = ConsistencyReport(10, 10, emptySet(), emptySet()),
            validationResult = ValidationResult(10, emptyList())
        )
        assertTrue(result.isHealthy)
    }

    @Test
    fun `FullConsistencyResult is unhealthy when inconsistent`() {
        val result = FullConsistencyResult(
            consistencyReport = ConsistencyReport(10, 9, setOf("orphan"), emptySet()),
            validationResult = ValidationResult(10, emptyList())
        )
        assertFalse(result.isHealthy)
    }

    @Test
    fun `FullConsistencyResult is unhealthy when validation fails`() {
        val result = FullConsistencyResult(
            consistencyReport = ConsistencyReport(10, 10, emptySet(), emptySet()),
            validationResult = ValidationResult(10, listOf(
                IntegrityIssue.MissingRequiredField("id-1", "path")
            ))
        )
        assertFalse(result.isHealthy)
    }

    @Test
    fun `FullConsistencyResult is unhealthy when both inconsistent and invalid`() {
        val result = FullConsistencyResult(
            consistencyReport = ConsistencyReport(10, 9, setOf("orphan"), emptySet()),
            validationResult = ValidationResult(10, listOf(
                IntegrityIssue.InvalidValue("id-1", "pageCount", "negative value")
            ))
        )
        assertFalse(result.isHealthy)
    }

    // --- IntegrityIssue ---

    @Test
    fun `MissingRequiredField stores recordId and fieldName`() {
        val issue = IntegrityIssue.MissingRequiredField("rec-1", "path")
        assertEquals("rec-1", issue.recordId)
        assertEquals("path", issue.fieldName)
    }

    @Test
    fun `InvalidValue stores recordId, fieldName, and reason`() {
        val issue = IntegrityIssue.InvalidValue("rec-2", "fileSize", "negative value")
        assertEquals("rec-2", issue.recordId)
        assertEquals("fileSize", issue.fieldName)
        assertEquals("negative value", issue.reason)
    }

    @Test
    fun `DuplicateContent stores recordId and duplicateIds`() {
        val issue = IntegrityIssue.DuplicateContent("rec-3", listOf("rec-4", "rec-5"))
        assertEquals("rec-3", issue.recordId)
        assertEquals(listOf("rec-4", "rec-5"), issue.duplicateIds)
    }
}
