package com.example.scanning

interface ScanProgressListener {
    fun onDirectoryStarted(path: String)
    fun onFileDiscovered(file: PDFFileInfo)
    fun onError(error: ScanError)
    fun onScanCompleted(result: ScanResult)
}

class ConsoleScanProgressListener : ScanProgressListener {
    override fun onDirectoryStarted(path: String) {
        println("Scanning directory: $path")
    }

    override fun onFileDiscovered(file: PDFFileInfo) {
        println("Found PDF: ${file.fileName} (${file.fileSize} bytes)")
    }

    override fun onError(error: ScanError) {
        println("Error scanning ${error.path}: ${error.error}")
    }

    override fun onScanCompleted(result: ScanResult) {
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