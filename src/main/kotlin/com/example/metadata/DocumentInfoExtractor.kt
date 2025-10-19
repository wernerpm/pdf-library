package com.example.metadata

import org.apache.pdfbox.pdmodel.PDDocument
import java.util.*

data class DocumentInfoData(
    val title: String?,
    val author: String?,
    val subject: String?,
    val creator: String?,
    val producer: String?,
    val keywords: String?,
    val creationDate: Calendar?,
    val modificationDate: Calendar?
)

class DocumentInfoExtractor {

    fun extract(document: PDDocument): DocumentInfoData {
        val docInfo = document.documentInformation
        return DocumentInfoData(
            title = docInfo.title?.trim()?.takeIf { it.isNotBlank() },
            author = docInfo.author?.trim()?.takeIf { it.isNotBlank() },
            subject = docInfo.subject?.trim()?.takeIf { it.isNotBlank() },
            creator = docInfo.creator?.trim()?.takeIf { it.isNotBlank() },
            producer = docInfo.producer?.trim()?.takeIf { it.isNotBlank() },
            keywords = docInfo.keywords?.trim()?.takeIf { it.isNotBlank() },
            creationDate = docInfo.creationDate,
            modificationDate = docInfo.modificationDate
        )
    }
}