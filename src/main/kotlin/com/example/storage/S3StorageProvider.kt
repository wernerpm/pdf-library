package com.example.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.net.URI
import java.nio.file.Paths
import java.time.Instant

/**
 * StorageProvider backed by Amazon S3 (or any S3-compatible store).
 *
 * Paths passed by callers are absolute filesystem-style paths (e.g. "/data/metadata/abc.json").
 * [basePath] is stripped from the front to derive the S3 object key so that the same
 * path conventions used by FileSystemStorage work here without changes to callers.
 *
 * "Directories" don't exist in S3; createDirectory() is a no-op, and list() uses the
 * key prefix + "/" delimiter to enumerate objects at one level.
 *
 * @param bucket       S3 bucket name
 * @param region       AWS region string, e.g. "us-east-1"
 * @param basePath     Base directory that callers use as their root (matches metadataStoragePath)
 * @param keyPrefix    Optional prefix prepended to every S3 key (no leading or trailing slash)
 * @param endpointUrl  Override endpoint URL — useful for LocalStack or MinIO in tests/dev
 * @param s3Client     Injected S3Client; defaults to one built from the other params
 */
class S3StorageProvider(
    private val bucket: String,
    private val region: String,
    private val basePath: String,
    private val keyPrefix: String = "",
    private val endpointUrl: String? = null,
    private val s3Client: S3Client = buildClient(region, endpointUrl)
) : StorageProvider {

    companion object {
        private val logger = LoggerFactory.getLogger(S3StorageProvider::class.java)

        fun buildClient(region: String, endpointUrl: String?): S3Client {
            val builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
            if (endpointUrl != null) {
                builder.endpointOverride(URI.create(endpointUrl))
                builder.forcePathStyle(true)
            }
            return builder.build()
        }
    }

    private val basePathNormalized: String =
        Paths.get(basePath).toAbsolutePath().normalize().toString()

    /** Convert a caller-supplied path (absolute or relative) to an S3 object key. */
    internal fun toKey(path: String): String {
        val normalized = Paths.get(path).normalize().toString()
        val relative = if (normalized.startsWith(basePathNormalized)) {
            normalized.removePrefix(basePathNormalized).trimStart('/', '\\')
        } else {
            path.trimStart('/', '\\')
        }
        return if (keyPrefix.isNotEmpty()) "$keyPrefix/$relative" else relative
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        val key = toKey(path)
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            true
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                // Not a single object — check if it's a virtual "directory" (any objects under prefix)
                val prefix = if (key.endsWith("/")) key else "$key/"
                val resp = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(1).build()
                )
                (resp.keyCount() ?: 0) > 0
            } else {
                throw StorageException("Failed to check existence: $path", e)
            }
        }
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        val key = toKey(path)
        try {
            s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()
            ).asByteArray()
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) throw StorageException("File not found: $path")
            throw StorageException("Failed to read: $path", e)
        } catch (e: Exception) {
            throw StorageException("Failed to read: $path", e)
        }
    }

    override suspend fun write(path: String, data: ByteArray): Unit = withContext(Dispatchers.IO) {
        val key = toKey(path)
        try {
            s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(data)
            )
        } catch (e: Exception) {
            throw StorageException("Failed to write: $path", e)
        }
    }

    override suspend fun list(path: String): List<String> = withContext(Dispatchers.IO) {
        val key = toKey(path)
        val prefix = if (key.endsWith("/")) key else "$key/"
        try {
            val results = mutableListOf<String>()
            var token: String? = null
            do {
                val reqBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .delimiter("/")
                token?.let { reqBuilder.continuationToken(it) }
                val resp = s3Client.listObjectsV2(reqBuilder.build())
                results.addAll(resp.contents().map { it.key().substringAfterLast('/') }.filter { it.isNotEmpty() })
                token = if (resp.isTruncated == true) resp.nextContinuationToken() else null
            } while (token != null)
            results.sorted()
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException("Failed to list: $path", e)
        }
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val key = toKey(path)
        try {
            // Delete the object itself (no-op if it doesn't exist — S3 delete is idempotent)
            s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(key).build()
            )
            // Also delete any objects under this prefix (recursive "directory" delete)
            val prefix = if (key.endsWith("/")) key else "$key/"
            var token: String? = null
            do {
                val listBuilder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                token?.let { listBuilder.continuationToken(it) }
                val listResp = s3Client.listObjectsV2(listBuilder.build())
                val objects = listResp.contents()
                if (objects.isNotEmpty()) {
                    val toDelete = objects.map { ObjectIdentifier.builder().key(it.key()).build() }
                    s3Client.deleteObjects(
                        DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(toDelete).build())
                            .build()
                    )
                }
                token = if (listResp.isTruncated == true) listResp.nextContinuationToken() else null
            } while (token != null)
        } catch (e: Exception) {
            throw StorageException("Failed to delete: $path", e)
        }
    }

    override suspend fun getMetadata(path: String): FileMetadata = withContext(Dispatchers.IO) {
        val key = toKey(path)
        try {
            val resp = s3Client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build()
            )
            FileMetadata(
                path = path,
                size = resp.contentLength() ?: 0L,
                createdAt = null,
                modifiedAt = resp.lastModified(),
                isDirectory = false
            )
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) throw StorageException("File not found: $path")
            throw StorageException("Failed to get metadata: $path", e)
        } catch (e: Exception) {
            throw StorageException("Failed to get metadata: $path", e)
        }
    }

    /** S3 has no real directories — this is a no-op. */
    override suspend fun createDirectory(path: String) = Unit
}
