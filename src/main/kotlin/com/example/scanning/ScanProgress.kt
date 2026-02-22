package com.example.scanning

interface ScanProgressListener {
    suspend fun onDirectoryStarted(path: String)
    suspend fun onFileDiscovered(file: PDFFileInfo)
    suspend fun onError(error: ScanError)
    suspend fun onScanCompleted(result: ScanResult)
}

interface ExtractionProgressListener {
    fun onExtractionStarted(totalFiles: Int)
    fun onFileExtracted(fileName: String, current: Int, total: Int, success: Boolean)
    fun onExtractionCompleted(extracted: Int, failed: Int, total: Int)
}

class ConsoleScanProgressListener : ScanProgressListener, ExtractionProgressListener {
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

    override fun onExtractionStarted(totalFiles: Int) {
        println("Extraction started: $totalFiles files to process")
    }

    override fun onFileExtracted(fileName: String, current: Int, total: Int, success: Boolean) {
        val status = if (success) "OK" else "FAILED"
        println("  [$current/$total] $fileName — $status")
    }

    override fun onExtractionCompleted(extracted: Int, failed: Int, total: Int) {
        println("Extraction completed:")
        println("  - Extracted: $extracted")
        println("  - Failed: $failed")
        println("  - Total: $total")
    }
}
