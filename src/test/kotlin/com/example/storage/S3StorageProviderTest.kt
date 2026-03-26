package com.example.storage

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3StorageProviderTest {

    private lateinit var s3Client: S3Client
    private lateinit var provider: S3StorageProvider

    private val bucket = "test-bucket"
    private val basePath = "/data/metadata"

    @BeforeEach
    fun setUp() {
        s3Client = mockk()
        provider = S3StorageProvider(
            bucket = bucket,
            region = "us-east-1",
            basePath = basePath,
            s3Client = s3Client
        )
    }

    // ---- toKey ----

    @Test
    fun `toKey strips basePath from absolute path`() {
        assertEquals("thumbnails/abc.png", provider.toKey("/data/metadata/thumbnails/abc.png"))
    }

    @Test
    fun `toKey handles relative path`() {
        assertEquals("credentials.json", provider.toKey("credentials.json"))
    }

    @Test
    fun `toKey applies keyPrefix when set`() {
        val p = S3StorageProvider(bucket, "us-east-1", basePath, keyPrefix = "lib", s3Client = s3Client)
        assertEquals("lib/metadata/abc.json", p.toKey("metadata/abc.json"))
    }

    @Test
    fun `toKey handles path equal to basePath`() {
        assertEquals("", provider.toKey("/data/metadata"))
    }

    // ---- exists ----

    @Test
    fun `exists returns true when headObject succeeds`() = runTest {
        every { s3Client.headObject(any<HeadObjectRequest>()) } returns mockk()
        assertTrue(provider.exists("/data/metadata/foo.json"))
    }

    @Test
    fun `exists returns false for missing object with no prefix match`() = runTest {
        val s3ex = S3Exception.builder().statusCode(404).message("Not Found").build()
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws s3ex
        every { s3Client.listObjectsV2(any<ListObjectsV2Request>()) } returns
            ListObjectsV2Response.builder().keyCount(0).isTruncated(false).build()
        assertFalse(provider.exists("/data/metadata/missing.json"))
    }

    @Test
    fun `exists returns true when object missing but prefix has children`() = runTest {
        val s3ex = S3Exception.builder().statusCode(404).message("Not Found").build()
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws s3ex
        every { s3Client.listObjectsV2(any<ListObjectsV2Request>()) } returns
            ListObjectsV2Response.builder().keyCount(1).isTruncated(false).build()
        assertTrue(provider.exists("/data/metadata/thumbnails"))
    }

    // ---- read ----

    @Test
    fun `read returns object bytes`() = runTest {
        val data = "hello".toByteArray()
        @Suppress("UNCHECKED_CAST")
        val responseBytes = mockk<ResponseBytes<GetObjectResponse>>()
        every { responseBytes.asByteArray() } returns data
        every { s3Client.getObjectAsBytes(any<GetObjectRequest>()) } returns responseBytes
        assertEquals(data.toList(), provider.read("/data/metadata/hello.txt").toList())
    }

    @Test
    fun `read throws StorageException for missing object`() = runTest {
        val s3ex = S3Exception.builder().statusCode(404).message("Not Found").build()
        every { s3Client.getObjectAsBytes(any<GetObjectRequest>()) } throws s3ex
        assertThrows<StorageException> { provider.read("/data/metadata/missing.json") }
    }

    // ---- write ----

    @Test
    fun `write calls putObject with correct key and data`() = runTest {
        val slot = slot<PutObjectRequest>()
        every { s3Client.putObject(capture(slot), any<RequestBody>()) } returns mockk()
        provider.write("/data/metadata/new.json", """{"id":"1"}""".toByteArray())
        assertEquals("new.json", slot.captured.key())
        assertEquals(bucket, slot.captured.bucket())
    }

    @Test
    fun `write throws StorageException on S3 error`() = runTest {
        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws
            S3Exception.builder().statusCode(500).message("Internal error").build()
        assertThrows<StorageException> { provider.write("/data/metadata/fail.json", ByteArray(0)) }
    }

    // ---- list ----

    @Test
    fun `list returns filenames at prefix`() = runTest {
        val objects = listOf("metadata/a.json", "metadata/b.json").map { key ->
            S3Object.builder().key(key).build()
        }
        every { s3Client.listObjectsV2(any<ListObjectsV2Request>()) } returns
            ListObjectsV2Response.builder().contents(objects).isTruncated(false).build()
        val result = provider.list("/data/metadata/metadata")
        assertEquals(listOf("a.json", "b.json"), result)
    }

    @Test
    fun `list handles pagination`() = runTest {
        val page1Objects = listOf(S3Object.builder().key("metadata/a.json").build())
        val page2Objects = listOf(S3Object.builder().key("metadata/b.json").build())
        every { s3Client.listObjectsV2(match<ListObjectsV2Request> { it.continuationToken() == null }) } returns
            ListObjectsV2Response.builder().contents(page1Objects).isTruncated(true).nextContinuationToken("tok1").build()
        every { s3Client.listObjectsV2(match<ListObjectsV2Request> { it.continuationToken() == "tok1" }) } returns
            ListObjectsV2Response.builder().contents(page2Objects).isTruncated(false).build()
        val result = provider.list("/data/metadata/metadata")
        assertEquals(listOf("a.json", "b.json"), result)
    }

    // ---- delete ----

    @Test
    fun `delete calls deleteObject for key`() = runTest {
        val slot = slot<DeleteObjectRequest>()
        every { s3Client.deleteObject(capture(slot)) } returns mockk()
        every { s3Client.listObjectsV2(any<ListObjectsV2Request>()) } returns
            ListObjectsV2Response.builder().contents(emptyList()).isTruncated(false).build()
        provider.delete("/data/metadata/old.json")
        assertEquals("old.json", slot.captured.key())
    }

    @Test
    fun `delete recursively removes objects under prefix`() = runTest {
        val children = listOf(
            S3Object.builder().key("thumbnails/a.png").build(),
            S3Object.builder().key("thumbnails/b.png").build()
        )
        every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns mockk()
        every { s3Client.listObjectsV2(any<ListObjectsV2Request>()) } returns
            ListObjectsV2Response.builder().contents(children).isTruncated(false).build()
        val deleteSlot = slot<DeleteObjectsRequest>()
        every { s3Client.deleteObjects(capture(deleteSlot)) } returns mockk()
        provider.delete("/data/metadata/thumbnails")
        assertEquals(2, deleteSlot.captured.delete().objects().size)
    }

    // ---- getMetadata ----

    @Test
    fun `getMetadata returns size and modifiedAt`() = runTest {
        val lastMod = java.time.Instant.parse("2024-01-01T00:00:00Z")
        val headResp = HeadObjectResponse.builder()
            .contentLength(1024L)
            .lastModified(lastMod)
            .build()
        every { s3Client.headObject(any<HeadObjectRequest>()) } returns headResp
        val meta = provider.getMetadata("/data/metadata/file.json")
        assertEquals(1024L, meta.size)
        assertEquals(lastMod, meta.modifiedAt)
        assertFalse(meta.isDirectory)
    }

    @Test
    fun `getMetadata throws StorageException for missing object`() = runTest {
        every { s3Client.headObject(any<HeadObjectRequest>()) } throws
            S3Exception.builder().statusCode(404).message("Not Found").build()
        assertThrows<StorageException> { provider.getMetadata("/data/metadata/missing.json") }
    }

    // ---- createDirectory ----

    @Test
    fun `createDirectory is a no-op`() = runTest {
        provider.createDirectory("/data/metadata/new-dir")
        verify { s3Client wasNot Called }
    }
}
