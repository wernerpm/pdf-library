package com.example.scanning

import kotlin.io.path.Path
import kotlin.io.path.pathString

class DuplicateDetector {

    fun removeDuplicates(files: List<PDFFileInfo>): List<PDFFileInfo> {
        return files
            .groupBy { it.canonicalPath() }
            .values
            .map { group -> group.first() }
    }

    private fun PDFFileInfo.canonicalPath(): String {
        return try {
            Path(path).normalize().pathString
        } catch (e: Exception) {
            path
        }
    }
}