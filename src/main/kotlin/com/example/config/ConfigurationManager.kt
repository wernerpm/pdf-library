package com.example.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

class ConfigurationManager {
    companion object {
        private const val CONFIG_FILE = "config.json"
        private const val DEFAULT_METADATA_PATH = ".pdf-library/metadata"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun loadConfiguration(): AppConfiguration = withContext(Dispatchers.IO) {
        val configPath = Paths.get(System.getProperty("user.dir")).resolve(CONFIG_FILE)

        if (!configPath.exists()) {
            val defaultConfig = getDefaultConfiguration()
            saveConfiguration(defaultConfig)
            return@withContext defaultConfig
        }

        try {
            val configText = configPath.readText()
            json.decodeFromString<AppConfiguration>(configText)
        } catch (e: Exception) {
            throw ConfigurationException("Failed to load configuration from $CONFIG_FILE", e)
        }
    }

    fun getDefaultConfiguration(): AppConfiguration {
        val userHome = System.getProperty("user.home")
        return AppConfiguration(
            pdfScanPaths = listOf(
                "$userHome/Documents/PDFs",
                "$userHome/Downloads"
            ),
            metadataStoragePath = "$userHome/$DEFAULT_METADATA_PATH",
            scanning = ScanConfiguration()
        )
    }

    suspend fun saveConfiguration(config: AppConfiguration): Unit = withContext(Dispatchers.IO) {
        val validationErrors = validateConfiguration(config)
        if (validationErrors.isNotEmpty()) {
            throw ConfigurationException("Configuration validation failed: ${validationErrors.joinToString(", ")}")
        }

        try {
            val configText = json.encodeToString(config)
            val configPath = Paths.get(System.getProperty("user.dir")).resolve(CONFIG_FILE)
            Files.writeString(configPath, configText)
        } catch (e: Exception) {
            throw ConfigurationException("Failed to save configuration to $CONFIG_FILE", e)
        }
    }

    fun validateConfiguration(config: AppConfiguration): List<String> {
        val errors = mutableListOf<String>()

        if (config.pdfScanPaths.isEmpty()) {
            errors.add("At least one PDF scan path must be specified")
        }

        config.pdfScanPaths.forEach { path ->
            if (path.isBlank()) {
                errors.add("PDF scan path cannot be blank")
            }
        }

        if (config.metadataStoragePath.isBlank()) {
            errors.add("Metadata storage path cannot be blank")
        }

        if (config.scanning.maxDepth <= 0) {
            errors.add("Max depth must be greater than 0")
        }

        if (config.scanning.fileExtensions.isEmpty()) {
            errors.add("At least one file extension must be specified")
        }

        config.scanning.fileExtensions.forEach { ext ->
            if (!ext.startsWith(".")) {
                errors.add("File extension '$ext' must start with a dot")
            }
        }

        return errors
    }

    fun expandPath(path: String): String {
        return when {
            path.startsWith("~/") -> {
                val userHome = System.getProperty("user.home")
                path.replaceFirst("~/", "$userHome/")
            }
            path.startsWith("~") -> {
                val userHome = System.getProperty("user.home")
                path.replaceFirst("~", userHome)
            }
            else -> path
        }
    }
}

class ConfigurationException(message: String, cause: Throwable? = null) : Exception(message, cause)