package com.example.scanning

interface ScanProgressListener {
    suspend fun onDirectoryStarted(path: String)
    suspend fun onFileDiscovered(file: PDFFileInfo)
    suspend fun onError(error: ScanError)
    suspend fun onScanCompleted(result: ScanResult)
}

class ConsoleScanProgressListener : ScanProgressListener {
    override suspend fun onDirectoryStarted(path: String) {
        println("Scanning directory: $path")
    }

    override suspend fun onFileDiscovered(file: PDFFileInfo) {
        println("Found PDF: ${file.fileName} (${file.fileSize} bytes)")
    }

    override suspend fun onError(error: ScanError) {
        println("Error scanning ${error.path}: ${error.error}")
    }

    override suspend fun onScanCompleted(result: ScanResult) {
        println("Scan completed:")
        println("  - Files discovered: ${result.discoveredFiles.size}")
        println("  - Total files scanned: ${result.totalFilesScanned}")
        println("  - Directories scanned: ${result.totalDirectoriesScanned}")
        println("  - Duplicates removed: ${result.duplicatesRemoved}")
        println("  - Invalid files skipped: ${result.invalidFilesSkipped}")
        println("  - Duration: ${result.scanDuration}")
        if (result.errors.isNotEmpty()) {
            println("  - Errors: ${result.errors.size}")
        }
    }
}