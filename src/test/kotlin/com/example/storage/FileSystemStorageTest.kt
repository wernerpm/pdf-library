package com.example.storage

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileSystemStorageTest {

    private lateinit var tempDir: Path
    private lateinit var storage: FileSystemStorage

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("storage-test")
        storage = FileSystemStorage(tempDir.toString())
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `should create base directory if it does not exist`() {
        val nonExistentDir = tempDir.resolve("new-storage")
        assertFalse(Files.exists(nonExistentDir))

        val newStorage = FileSystemStorage(nonExistentDir.toString())
        assertTrue(Files.exists(nonExistentDir))
    }

    @Test
    fun `should check if file exists`() = runTest {
        val testFile = "test.txt"
        assertFalse(storage.exists(testFile))

        storage.write(testFile, "content".toByteArray())
        assertTrue(storage.exists(testFile))
    }

    @Test
    fun `should write and read file`() = runTest {
        val testFile = "test.txt"
        val content = "Hello, World!".toByteArray()

        storage.write(testFile, content)
        val readContent = storage.read(testFile)

        assertContentEquals(content, readContent)
    }

    @Test
    fun `should create parent directories when writing file`() = runTest {
        val testFile = "subdir/nested/test.txt"
        val content = "nested content".toByteArray()

        storage.write(testFile, content)
        assertTrue(storage.exists(testFile))

        val readContent = storage.read(testFile)
        assertContentEquals(content, readContent)
    }

    @Test
    fun `should list directory contents`() = runTest {
        storage.write("file1.txt", "content1".toByteArray())
        storage.write("file2.txt", "content2".toByteArray())
        storage.createDirectory("subdir")

        val contents = storage.list(".")
        assertTrue(contents.contains("file1.txt"))
        assertTrue(contents.contains("file2.txt"))
        assertTrue(contents.contains("subdir"))
    }

    @Test
    fun `should delete file`() = runTest {
        val testFile = "test.txt"
        storage.write(testFile, "content".toByteArray())
        assertTrue(storage.exists(testFile))

        storage.delete(testFile)
        assertFalse(storage.exists(testFile))
    }

    @Test
    fun `should delete directory recursively`() = runTest {
        val testDir = "testdir"
        storage.createDirectory(testDir)
        storage.write("$testDir/file.txt", "content".toByteArray())
        storage.createDirectory("$testDir/subdir")
        storage.write("$testDir/subdir/nested.txt", "nested".toByteArray())

        assertTrue(storage.exists(testDir))
        assertTrue(storage.exists("$testDir/file.txt"))

        storage.delete(testDir)
        assertFalse(storage.exists(testDir))
        assertFalse(storage.exists("$testDir/file.txt"))
    }

    @Test
    fun `should get file metadata`() = runTest {
        val testFile = "test.txt"
        val content = "Hello, World!".toByteArray()
        storage.write(testFile, content)

        val metadata = storage.getMetadata(testFile)

        assertEquals(testFile, metadata.path)
        assertEquals(content.size.toLong(), metadata.size)
        assertFalse(metadata.isDirectory)
        assertNotNull(metadata.modifiedAt)
    }

    @Test
    fun `should get directory metadata`() = runTest {
        val testDir = "testdir"
        storage.createDirectory(testDir)

        val metadata = storage.getMetadata(testDir)

        assertEquals(testDir, metadata.path)
        assertTrue(metadata.isDirectory)
    }

    @Test
    fun `should create directory`() = runTest {
        val testDir = "newdir"
        assertFalse(storage.exists(testDir))

        storage.createDirectory(testDir)
        assertTrue(storage.exists(testDir))

        val metadata = storage.getMetadata(testDir)
        assertTrue(metadata.isDirectory)
    }

    @Test
    fun `should create nested directories`() = runTest {
        val nestedDir = "level1/level2/level3"
        assertFalse(storage.exists(nestedDir))

        storage.createDirectory(nestedDir)
        assertTrue(storage.exists(nestedDir))
        assertTrue(storage.exists("level1"))
        assertTrue(storage.exists("level1/level2"))
    }

    @Test
    fun `should throw exception when reading non-existent file`() = runTest {
        assertThrows<StorageException> {
            storage.read("nonexistent.txt")
        }
    }

    @Test
    fun `should throw exception when reading directory as file`() = runTest {
        storage.createDirectory("testdir")

        assertThrows<StorageException> {
            storage.read("testdir")
        }
    }

    @Test
    fun `should throw exception when listing non-existent directory`() = runTest {
        assertThrows<StorageException> {
            storage.list("nonexistent")
        }
    }

    @Test
    fun `should throw exception when listing file as directory`() = runTest {
        storage.write("test.txt", "content".toByteArray())

        assertThrows<StorageException> {
            storage.list("test.txt")
        }
    }

    @Test
    fun `should throw exception for path traversal attempts`() = runTest {
        assertThrows<StorageException> {
            storage.read("../outside.txt")
        }

        assertThrows<StorageException> {
            storage.write("subdir/../../outside.txt", "content".toByteArray())
        }
    }

    @Test
    fun `should handle absolute paths correctly when within base directory`() = runTest {
        val absolutePath = tempDir.resolve("absolute-test.txt").toString()
        val content = "absolute content".toByteArray()

        storage.write(absolutePath, content)
        assertTrue(storage.exists(absolutePath))

        val readContent = storage.read(absolutePath)
        assertContentEquals(content, readContent)
    }

    @Test
    fun `should reject absolute paths outside base directory`() = runTest {
        val outsidePath = "/tmp/outside.txt"

        assertThrows<StorageException> {
            storage.write(outsidePath, "content".toByteArray())
        }
    }

    @Test
    fun `should not fail when deleting non-existent file`() = runTest {
        // Should not throw exception
        storage.delete("nonexistent.txt")
    }

    @Test
    fun `should throw exception when getting metadata for non-existent file`() = runTest {
        assertThrows<StorageException> {
            storage.getMetadata("nonexistent.txt")
        }
    }

    @Test
    fun `should throw exception when creating directory over existing file`() = runTest {
        storage.write("test.txt", "content".toByteArray())

        assertThrows<StorageException> {
            storage.createDirectory("test.txt")
        }
    }
}