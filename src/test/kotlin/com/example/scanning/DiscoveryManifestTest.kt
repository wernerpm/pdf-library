package com.example.scanning

import com.example.storage.FileMetadata
import com.example.storage.StorageProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class DiscoveryManifestTest {

    private class InMemoryStorageProvider : StorageProvider {
        private val files = mutableMapOf<String, ByteArray>()
        private val directories = mutableSetOf<String>()

        override suspend fun exists(path: String): Boolean = files.containsKey(path) || directories.contains(path)
        override suspend fun read(path: String): ByteArray = files[path] ?: throw Exception("Not found: $path")
        override suspend fun write(path: String, data: ByteArray) { files[path] = data }
        override suspend fun list(path: String): List<String> = emptyList()
        override suspend fun delete(path: String) { files.remove(path) }
        override suspend fun getMetadata(path: String): FileMetadata = throw UnsupportedOperationException()
        override suspend fun createDirectory(path: String) { directories.add(path) }
    }

    private fun createTestManifest(files: List<PDFFileInfo> = emptyList()): DiscoveryManifest {
        return DiscoveryManifest(
            lastDiscovery = Clock.System.now(),
            scanPaths = listOf("/test/path"),
            files = files
        )
    }

    private fun createTestFileInfo(
        path: String = "/test/file.pdf",
        fileName: String = "file.pdf",
        fileSize: Long = 1024,
        status: FileStatus = FileStatus.DISCOVERED
    ): PDFFileInfo {
        return PDFFileInfo(
            path = path,
            fileName = fileName,
            fileSize = fileSize,
            lastModified = Clock.System.now(),
            status = status
        )
    }

    @Test
    fun `save and load manifest round-trip`() = runTest {
        val storage = InMemoryStorageProvider()
        val manager = DiscoveryManifestManager(storage, "manifest.json")

        val file1 = createTestFileInfo("/docs/a.pdf", "a.pdf", 100)
        val file2 = createTestFileInfo("/docs/b.pdf", "b.pdf", 200)
        val manifest = createTestManifest(listOf(file1, file2))

        manager.save(manifest)
        val loaded = manager.load()

        assertNotNull(loaded)
        assertEquals(2, loaded.files.size)
        assertEquals(listOf("/test/path"), loaded.scanPaths)
        assertEquals("/docs/a.pdf", loaded.files[0].path)
        assertEquals("/docs/b.pdf", loaded.files[1].path)
        assertEquals(FileStatus.DISCOVERED, loaded.files[0].status)
    }

    @Test
    fun `load returns null when no manifest exists`() = runTest {
        val storage = InMemoryStorageProvider()
        val manager = DiscoveryManifestManager(storage, "manifest.json")

        assertNull(manager.load())
    }

    @Test
    fun `updateFileStatus updates specific file`() = runTest {
        val storage = InMemoryStorageProvider()
        val manager = DiscoveryManifestManager(storage, "manifest.json")

        val manifest = createTestManifest(listOf(
            createTestFileInfo("/docs/a.pdf", "a.pdf", 100),
            createTestFileInfo("/docs/b.pdf", "b.pdf", 200)
        ))
        manager.save(manifest)

        manager.updateFileStatus("/docs/a.pdf", FileStatus.EXTRACTED)

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals(FileStatus.EXTRACTED, loaded.files.first { it.path == "/docs/a.pdf" }.status)
        assertEquals(FileStatus.DISCOVERED, loaded.files.first { it.path == "/docs/b.pdf" }.status)
    }

    @Test
    fun `updateFileStatus to FAILED`() = runTest {
        val storage = InMemoryStorageProvider()
        val manager = DiscoveryManifestManager(storage, "manifest.json")

        val manifest = createTestManifest(listOf(
            createTestFileInfo("/docs/a.pdf", "a.pdf")
        ))
        manager.save(manifest)

        manager.updateFileStatus("/docs/a.pdf", FileStatus.FAILED)

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals(FileStatus.FAILED, loaded.files[0].status)
    }

    @Test
    fun `manifest preserves all PDFFileInfo fields`() = runTest {
        val storage = InMemoryStorageProvider()
        val manager = DiscoveryManifestManager(storage, "manifest.json")

        val now = Clock.System.now()
        val file = PDFFileInfo(
            path = "/docs/test.pdf",
            fileName = "test.pdf",
            fileSize = 12345,
            lastModified = now,
            discovered = now,
            status = FileStatus.EXTRACTED
        )
        val manifest = DiscoveryManifest(
            lastDiscovery = now,
            scanPaths = listOf("/docs"),
            files = listOf(file)
        )

        manager.save(manifest)
        val loaded = manager.load()

        assertNotNull(loaded)
        val loadedFile = loaded.files[0]
        assertEquals(file.path, loadedFile.path)
        assertEquals(file.fileName, loadedFile.fileName)
        assertEquals(file.fileSize, loadedFile.fileSize)
        assertEquals(file.status, loadedFile.status)
    }

    @Test
    fun `manifest with empty files list`() = runTest {
        val storage = InMemoryStorageProvider()
        val manager = DiscoveryManifestManager(storage, "manifest.json")

        val manifest = createTestManifest(emptyList())
        manager.save(manifest)

        val loaded = manager.load()
        assertNotNull(loaded)
        assertTrue(loaded.files.isEmpty())
    }
}
