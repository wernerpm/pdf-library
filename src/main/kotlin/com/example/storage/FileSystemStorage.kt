package com.example.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.util.EnumSet
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import kotlin.io.path.*

class FileSystemStorage(
    private val basePath: String,
    private val maxReadSize: Long = DEFAULT_MAX_READ_SIZE
) : StorageProvider {

    companion object {
        const val DEFAULT_MAX_READ_SIZE = 500_000_000L // 500MB
    }

    private val basePathNormalized = Paths.get(basePath).toAbsolutePath().normalize()

    init {
        if (!basePathNormalized.exists()) {
            try {
                Files.createDirectories(basePathNormalized)
            } catch (e: IOException) {
                throw StorageException("Failed to create base directory: $basePath", e)
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = resolvePath(path)
            resolvedPath.exists()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = resolvePath(path)

            if (!resolvedPath.exists()) {
                throw StorageException("File not found: $path")
            }

            if (resolvedPath.isDirectory()) {
                throw StorageException("Cannot read directory as file: $path")
            }

            val fileSize = Files.size(resolvedPath)
            if (fileSize > maxReadSize) {
                throw StorageException("File too large to read: $path ($fileSize bytes, max $maxReadSize)")
            }

            resolvedPath.readBytes()
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Failed to read file: $path", e)
        }
    }

    override suspend fun write(path: String, data: ByteArray): Unit = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = resolvePath(path)

            // Create parent directories if they don't exist
            resolvedPath.parent?.let { parentPath ->
                if (!parentPath.exists()) {
                    Files.createDirectories(parentPath)
                }
            }

            // Write to temporary file first, then rename for atomic operation
            val tempFile = Files.createTempFile(resolvedPath.parent, "temp", ".tmp")
            try {
                tempFile.writeBytes(data)
                Files.move(tempFile, resolvedPath, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // Clean up temp file if operation failed
                try {
                    Files.deleteIfExists(tempFile)
                } catch (cleanupException: Exception) {
                    // Ignore cleanup errors
                }
                throw e
            }
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Failed to write file: $path", e)
        }
    }

    override suspend fun list(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = resolvePath(path)

            if (!resolvedPath.exists()) {
                throw StorageException("Directory not found: $path")
            }

            if (!resolvedPath.isDirectory()) {
                throw StorageException("Path is not a directory: $path")
            }

            Files.list(resolvedPath).use { stream ->
                stream.map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Failed to list directory: $path", e)
        }
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = resolvePath(path)

            if (!resolvedPath.exists()) {
                return@withContext // Already deleted
            }

            if (resolvedPath.isDirectory()) {
                // Delete directory recursively, but don't follow symlinks outside base path
                Files.walkFileTree(resolvedPath, EnumSet.noneOf(FileVisitOption::class.java), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val normalized = file.toAbsolutePath().normalize()
                        if (normalized.startsWith(basePathNormalized)) {
                            Files.delete(file)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        val normalized = dir.toAbsolutePath().normalize()
                        if (normalized.startsWith(basePathNormalized)) {
                            Files.delete(dir)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // If this directory is a symlink pointing outside base, delete the link itself and skip
                        if (Files.isSymbolicLink(dir)) {
                            val normalized = dir.toRealPath().normalize()
                            if (!normalized.startsWith(basePathNormalized)) {
                                Files.delete(dir) // Deletes the symlink, not the target
                                return FileVisitResult.SKIP_SUBTREE
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                Files.delete(resolvedPath)
            }
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Failed to delete: $path", e)
        }
    }

    override suspend fun getMetadata(path: String): FileMetadata = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = resolvePath(path)

            if (!resolvedPath.exists()) {
                throw StorageException("File not found: $path")
            }

            val attributes = Files.readAttributes(resolvedPath, BasicFileAttributes::class.java)

            FileMetadata(
                path = path,
                size = attributes.size(),
                createdAt = attributes.creationTime()?.toInstant(),
                modifiedAt = attributes.lastModifiedTime()?.toInstant(),
                isDirectory = attributes.isDirectory
            )
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Failed to get metadata for: $path", e)
        }
    }

    override suspend fun createDirectory(path: String): Unit = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = resolvePath(path)

            if (resolvedPath.exists() && !resolvedPath.isDirectory()) {
                throw StorageException("Path exists but is not a directory: $path")
            }

            if (!resolvedPath.exists()) {
                Files.createDirectories(resolvedPath)
            }
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Failed to create directory: $path", e)
        }
    }

    private fun validatePath(resolvedPath: Path) {
        val normalized = resolvedPath.toAbsolutePath().normalize()
        if (!normalized.startsWith(basePathNormalized)) {
            throw StorageException("Path is outside base directory: $resolvedPath")
        }
    }

    private fun resolvePath(path: String): Path {
        val resolved = if (Paths.get(path).isAbsolute) {
            Paths.get(path).normalize()
        } else {
            basePathNormalized.resolve(path).normalize()
        }
        validatePath(resolved)
        return resolved
    }
}