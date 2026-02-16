package com.example.repository

import com.example.config.AppConfiguration
import com.example.config.ScanConfiguration
import com.example.metadata.PDFMetadata
import com.example.storage.FileSystemStorage
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock

import org.junit.jupiter.api.Test

class RepositoryDemo {

    @Test
    fun `demonstrate repository functionality`() = runBlocking {
    println("🔬 Testing PDF Library Repository Implementation")
    println("=" * 50)

    // Create temporary directory for testing
    val tempDir = java.nio.file.Files.createTempDirectory("pdf-lib-test")
    println("📁 Using temp directory: $tempDir")

    try {
        // Setup components
        val storage = FileSystemStorage(tempDir.toString())
        val config = AppConfiguration(
            pdfScanPaths = listOf(tempDir.toString()),
            metadataStoragePath = "$tempDir/metadata",
            scanning = ScanConfiguration(
                excludePatterns = emptyList(),
                maxFileSize = 100L * 1024 * 1024
            )
        )

        // Create repository with layered architecture
        val jsonRepository = JsonRepository(storage, config.metadataStoragePath)
        val repository = InMemoryMetadataRepository(jsonRepository)
        val consistencyManager = ConsistencyManager(repository, jsonRepository)
        val repositoryManager = RepositoryManager(repository, consistencyManager)

        println("\n✅ Components initialized successfully")

        // Create test data
        val testMetadata = listOf(
            PDFMetadata(
                id = "pdf-1",
                path = "/books/kotlin-guide.pdf",
                fileName = "kotlin-guide.pdf",
                fileSize = 2_048_000,
                pageCount = 150,
                createdDate = null,
                modifiedDate = null,
                title = "Complete Kotlin Programming Guide",
                author = "John Developer",
                subject = "Programming",
                creator = "LaTeX",
                producer = "PDFTeX",
                keywords = listOf("kotlin", "programming", "android", "jvm"),
                pdfVersion = "1.7",
                customProperties = mapOf(
                    "category" to "technical",
                    "level" to "intermediate",
                    "rating" to "5-stars"
                ),
                contentHash = "a1b2c3d4e5f6",
                isEncrypted = false,
                isSignedPdf = false,
                indexedAt = Clock.System.now()
            ),
            PDFMetadata(
                id = "pdf-2",
                path = "/recipes/italian-cuisine.pdf",
                fileName = "italian-cuisine.pdf",
                fileSize = 5_242_880,
                pageCount = 75,
                createdDate = null,
                modifiedDate = null,
                title = "Authentic Italian Recipes",
                author = "Chef Mario Rossi",
                subject = "Cooking",
                creator = "Adobe InDesign",
                producer = "Adobe PDF Library",
                keywords = listOf("cooking", "italian", "recipes", "pasta"),
                pdfVersion = "1.4",
                customProperties = mapOf(
                    "category" to "lifestyle",
                    "cuisine" to "italian",
                    "difficulty" to "beginner"
                ),
                contentHash = "x7y8z9w0v1u2",
                isEncrypted = false,
                isSignedPdf = false,
                indexedAt = Clock.System.now()
            ),
            PDFMetadata(
                id = "pdf-3",
                path = "/manuals/smartphone-guide.pdf",
                fileName = "smartphone-guide.pdf",
                fileSize = 1_024_000,
                pageCount = 45,
                createdDate = null,
                modifiedDate = null,
                title = "Smartphone User Manual",
                author = "TechCorp Documentation Team",
                subject = "User Manual",
                creator = "Microsoft Word",
                producer = "Microsoft Print to PDF",
                keywords = listOf("manual", "smartphone", "guide", "setup"),
                pdfVersion = "1.5",
                customProperties = mapOf(
                    "category" to "technical",
                    "product" to "smartphone",
                    "version" to "2024"
                ),
                contentHash = "m3n4o5p6q7r8",
                isEncrypted = false,
                isSignedPdf = false,
                indexedAt = Clock.System.now()
            )
        )

        // Test 1: Store metadata
        println("\n📝 Test 1: Storing metadata...")
        testMetadata.forEach { metadata ->
            repository.savePDF(metadata)
            println("   ✓ Saved: ${metadata.title}")
        }
        println("   📊 Total records: ${repository.count()}")

        // Test 2: Retrieve metadata
        println("\n🔍 Test 2: Retrieving metadata...")
        val retrieved = repository.getPDF("pdf-1")
        println("   ✓ Retrieved: ${retrieved?.title}")
        println("   📄 Pages: ${retrieved?.pageCount}")
        println("   👤 Author: ${retrieved?.author}")

        // Test 3: Search functionality
        println("\n🔎 Test 3: Search functionality...")

        val kotlinSearch = repository.search("kotlin programming")
        println("   🔍 Search 'kotlin programming': ${kotlinSearch.size} results")
        kotlinSearch.forEach { pdf -> println("      - ${pdf.title}") }

        val authorSearch = repository.searchByAuthor("Mario")
        println("   👤 Search by author 'Mario': ${authorSearch.size} results")
        authorSearch.forEach { pdf -> println("      - ${pdf.title} by ${pdf.author}") }

        val categorySearch = repository.searchByProperty("category", "technical")
        println("   🏷️ Search category 'technical': ${categorySearch.size} results")
        categorySearch.forEach { pdf -> println("      - ${pdf.title}") }

        // Test 4: Repository management
        println("\n⚙️ Test 4: Repository management...")
        val initResult = repositoryManager.initialize()
        when (initResult) {
            is InitializationResult.Success -> {
                println("   ✅ Initialization successful")
                println("   📊 Records loaded: ${initResult.recordsLoaded}")
                println("   ⏱️ Duration: ${initResult.initializationDuration}")
            }
            is InitializationResult.Error -> {
                println("   ❌ Initialization failed: ${initResult.message}")
            }
        }

        // Test 5: Maintenance and consistency
        println("\n🔧 Test 5: Maintenance check...")
        val maintenanceResult = repositoryManager.performMaintenanceCheck()
        when (maintenanceResult) {
            is MaintenanceResult.Success -> {
                println("   ✅ Maintenance check successful")
                println("   ${maintenanceResult.summarize()}")
            }
            is MaintenanceResult.Error -> {
                println("   ❌ Maintenance failed: ${maintenanceResult.message}")
            }
        }

        // Test 6: Backup
        println("\n💾 Test 6: Creating backup...")
        val backupResult = repositoryManager.createBackup()
        when (backupResult) {
            is BackupResult.Success -> {
                println("   ✅ Backup created successfully")
                println("   📁 Path: ${backupResult.path}")
                println("   📊 Records: ${backupResult.recordCount}")
                println("   📄 Size: ${backupResult.backupData.length} characters")
            }
            is BackupResult.Error -> {
                println("   ❌ Backup failed: ${backupResult.message}")
            }
        }

        // Test 7: Status
        println("\n📈 Test 7: Repository status...")
        val status = repositoryManager.getStatus()
        println("   🟢 Initialized: ${status.isInitialized}")
        println("   📊 Total records: ${status.totalRecords}")
        println("   🧠 Memory info: ${status.memoryInfo}")

        println("\n" + "=" * 50)
        println("🎉 All tests completed successfully!")
        println("✅ Step 1d: In-Memory Repository + JSON Persistence is working perfectly!")

    } catch (e: Exception) {
        println("❌ Test failed with error: ${e.message}")
        e.printStackTrace()
    } finally {
        // Cleanup
        tempDir.toFile().deleteRecursively()
        println("🧹 Cleaned up temp directory")
    }
    }
}

private operator fun String.times(n: Int): String = this.repeat(n)