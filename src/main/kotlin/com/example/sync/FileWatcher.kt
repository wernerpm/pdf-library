package com.example.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FileWatcher(
    private val scanPaths: List<String>,
    private val debounceMs: Long = 2000L,
    private val onChangesDetected: suspend (List<FileChange>) -> Unit
) {
    private val logger = LoggerFactory.getLogger(FileWatcher::class.java)
    private val watchKeyToPath = ConcurrentHashMap<WatchKey, Path>()
    private var watchJob: Job? = null
    private var watchService: java.nio.file.WatchService? = null

    fun start(scope: CoroutineScope) {
        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws

        var registeredCount = 0
        for (scanPath in scanPaths) {
            try {
                registeredCount += registerDirectoryTree(Paths.get(scanPath), ws)
            } catch (e: IOException) {
                logger.warn("Cannot watch '$scanPath' (may be a network volume): ${e.message}")
            }
        }
        logger.info("FileWatcher: registered $registeredCount directories across ${scanPaths.size} scan path(s)")

        watchJob = scope.launch(Dispatchers.IO) {
            val pendingChanges = mutableMapOf<String, FileChange>()
            var lastEventTime = 0L

            while (isActive) {
                val key = ws.poll(1, TimeUnit.SECONDS)
                if (key != null) {
                    val dir = watchKeyToPath[key]
                    if (dir != null) {
                        for (event in key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue
                            @Suppress("UNCHECKED_CAST")
                            val typedEvent = event as WatchEvent<Path>
                            val child = dir.resolve(typedEvent.context())

                            // Register newly created subdirectories
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                                try { registerDirectoryTree(child, ws) } catch (_: IOException) {}
                            }

                            // Only track PDF file events
                            val name = typedEvent.context().toString()
                            if (!name.lowercase().endsWith(".pdf")) continue

                            val changeType = when (event.kind()) {
                                StandardWatchEventKinds.ENTRY_CREATE -> FileChangeType.CREATED
                                StandardWatchEventKinds.ENTRY_MODIFY -> FileChangeType.MODIFIED
                                StandardWatchEventKinds.ENTRY_DELETE -> FileChangeType.DELETED
                                else -> continue
                            }
                            pendingChanges[child.toString()] = FileChange(child.toString(), changeType, Clock.System.now())
                        }
                        lastEventTime = System.currentTimeMillis()
                    }
                    if (!key.reset()) watchKeyToPath.remove(key)
                }

                if (pendingChanges.isNotEmpty() && System.currentTimeMillis() - lastEventTime >= debounceMs) {
                    val changes = pendingChanges.values.toList()
                    pendingChanges.clear()
                    try {
                        onChangesDetected(changes)
                    } catch (e: Exception) {
                        logger.error("Error handling file changes", e)
                    }
                }
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchService?.close()
        watchKeyToPath.clear()
        logger.info("FileWatcher stopped")
    }

    private fun registerDirectoryTree(root: Path, ws: java.nio.file.WatchService? = watchService): Int {
        if (ws == null || !Files.exists(root) || !Files.isDirectory(root)) return 0
        var count = 0
        try {
            Files.walk(root).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { dir ->
                    try {
                        val key = dir.register(
                            ws,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE
                        )
                        watchKeyToPath[key] = dir
                        count++
                    } catch (_: IOException) {}
                }
            }
        } catch (e: Exception) {
            logger.warn("Error walking directory tree: $root", e)
        }
        return count
    }
}

@Serializable
data class FileChange(
    val path: String,
    val type: FileChangeType,
    val timestamp: Instant
)

@Serializable
enum class FileChangeType { CREATED, MODIFIED, DELETED }
