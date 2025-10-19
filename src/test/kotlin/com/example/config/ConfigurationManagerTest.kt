package com.example.config

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationManagerTest {

    private lateinit var tempDir: Path
    private lateinit var configManager: ConfigurationManager
    private lateinit var originalUserDir: String

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("config-test")
        originalUserDir = System.getProperty("user.dir")
        System.setProperty("user.dir", tempDir.toString())
        configManager = ConfigurationManager()
    }

    @AfterEach
    fun tearDown() {
        System.setProperty("user.dir", originalUserDir)
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `should create default configuration when config file does not exist`() = runTest {
        val config = configManager.loadConfiguration()

        val userHome = System.getProperty("user.home")
        assertEquals(
            listOf(
                "$userHome/Documents/PDFs",
                "$userHome/Downloads"
            ),
            config.pdfScanPaths
        )
        assertEquals("$userHome/.pdf-library/metadata", config.metadataStoragePath)
        assertTrue(config.scanning.recursive)
        assertEquals(50, config.scanning.maxDepth)
        assertEquals(listOf(".pdf"), config.scanning.fileExtensions)
    }

    @Test
    fun `should load existing configuration file`() = runTest {
        val configContent = """
            {
              "pdfScanPaths": ["/custom/path"],
              "metadataStoragePath": "/custom/metadata",
              "scanning": {
                "recursive": false,
                "maxDepth": 10,
                "excludePatterns": ["test*"],
                "fileExtensions": [".pdf", ".PDF"]
              }
            }
        """.trimIndent()

        val configFile = tempDir.resolve("config.json")
        Files.writeString(configFile, configContent)

        val config = configManager.loadConfiguration()

        assertEquals(listOf("/custom/path"), config.pdfScanPaths)
        assertEquals("/custom/metadata", config.metadataStoragePath)
        assertFalse(config.scanning.recursive)
        assertEquals(10, config.scanning.maxDepth)
        assertEquals(listOf("test*"), config.scanning.excludePatterns)
        assertEquals(listOf(".pdf", ".PDF"), config.scanning.fileExtensions)
    }

    @Test
    fun `should save configuration to file`() = runTest {
        val config = AppConfiguration(
            pdfScanPaths = listOf("/test/path"),
            metadataStoragePath = "/test/metadata",
            scanning = ScanConfiguration(
                recursive = true,
                maxDepth = 25,
                excludePatterns = listOf("*.tmp"),
                fileExtensions = listOf(".pdf")
            )
        )

        configManager.saveConfiguration(config)

        val configFile = tempDir.resolve("config.json")
        assertTrue(Files.exists(configFile))

        val savedConfig = configManager.loadConfiguration()
        assertEquals(config, savedConfig)
    }

    @Test
    fun `should validate configuration correctly`() {
        val validConfig = AppConfiguration(
            pdfScanPaths = listOf("/valid/path"),
            metadataStoragePath = "/valid/metadata",
            scanning = ScanConfiguration()
        )

        val errors = configManager.validateConfiguration(validConfig)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `should detect empty pdf scan paths`() {
        val invalidConfig = AppConfiguration(
            pdfScanPaths = emptyList(),
            metadataStoragePath = "/valid/metadata",
            scanning = ScanConfiguration()
        )

        val errors = configManager.validateConfiguration(invalidConfig)
        assertTrue(errors.contains("At least one PDF scan path must be specified"))
    }

    @Test
    fun `should detect blank paths`() {
        val invalidConfig = AppConfiguration(
            pdfScanPaths = listOf(""),
            metadataStoragePath = "",
            scanning = ScanConfiguration()
        )

        val errors = configManager.validateConfiguration(invalidConfig)
        assertTrue(errors.contains("PDF scan path cannot be blank"))
        assertTrue(errors.contains("Metadata storage path cannot be blank"))
    }

    @Test
    fun `should detect invalid max depth`() {
        val invalidConfig = AppConfiguration(
            pdfScanPaths = listOf("/valid/path"),
            metadataStoragePath = "/valid/metadata",
            scanning = ScanConfiguration(maxDepth = 0)
        )

        val errors = configManager.validateConfiguration(invalidConfig)
        assertTrue(errors.contains("Max depth must be greater than 0"))
    }

    @Test
    fun `should detect invalid file extensions`() {
        val invalidConfig = AppConfiguration(
            pdfScanPaths = listOf("/valid/path"),
            metadataStoragePath = "/valid/metadata",
            scanning = ScanConfiguration(fileExtensions = listOf("pdf"))
        )

        val errors = configManager.validateConfiguration(invalidConfig)
        assertTrue(errors.contains("File extension 'pdf' must start with a dot"))
    }

    @Test
    fun `should throw exception when saving invalid configuration`() = runTest {
        val invalidConfig = AppConfiguration(
            pdfScanPaths = emptyList(),
            metadataStoragePath = "",
            scanning = ScanConfiguration()
        )

        assertThrows<ConfigurationException> {
            configManager.saveConfiguration(invalidConfig)
        }
    }

    @Test
    fun `should expand tilde in paths`() {
        val userHome = System.getProperty("user.home")

        assertEquals("$userHome/Documents", configManager.expandPath("~/Documents"))
        assertEquals(userHome, configManager.expandPath("~"))
        assertEquals("/absolute/path", configManager.expandPath("/absolute/path"))
        assertEquals("relative/path", configManager.expandPath("relative/path"))
    }
}