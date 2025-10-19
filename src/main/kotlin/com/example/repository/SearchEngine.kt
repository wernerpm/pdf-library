package com.example.repository

import com.example.metadata.PDFMetadata

class SearchEngine {

    fun searchByQuery(
        metadata: Collection<PDFMetadata>,
        query: String
    ): List<PDFMetadata> {
        if (query.isBlank()) return metadata.toList()

        val searchTerms = query.lowercase().split("\\s+".toRegex())

        return metadata.filter { pdf ->
            searchTerms.all { term ->
                matchesTerm(pdf, term)
            }
        }.sortedByDescending { pdf ->
            calculateRelevanceScore(pdf, searchTerms)
        }
    }

    fun searchByProperty(
        metadata: Collection<PDFMetadata>,
        key: String,
        value: String
    ): List<PDFMetadata> {
        return metadata.filter { pdf ->
            when (key.lowercase()) {
                "author" -> pdf.author?.contains(value, ignoreCase = true) == true
                "title" -> pdf.title?.contains(value, ignoreCase = true) == true
                "subject" -> pdf.subject?.contains(value, ignoreCase = true) == true
                "keywords" -> pdf.keywords.any { it.contains(value, ignoreCase = true) }
                else -> pdf.customProperties[key]?.contains(value, ignoreCase = true) == true
            }
        }
    }

    private fun matchesTerm(pdf: PDFMetadata, term: String): Boolean {
        return listOfNotNull(
            pdf.fileName,
            pdf.title,
            pdf.author,
            pdf.subject,
            pdf.creator,
            pdf.producer
        ).any { field ->
            field.contains(term, ignoreCase = true)
        } || pdf.keywords.any { keyword ->
            keyword.contains(term, ignoreCase = true)
        } || pdf.customProperties.values.any { value ->
            value.contains(term, ignoreCase = true)
        }
    }

    private fun calculateRelevanceScore(
        pdf: PDFMetadata,
        searchTerms: List<String>
    ): Double {
        var score = 0.0

        searchTerms.forEach { term ->
            // Title matches get highest score
            if (pdf.title?.contains(term, ignoreCase = true) == true) score += 3.0
            // Author matches get medium score
            if (pdf.author?.contains(term, ignoreCase = true) == true) score += 2.0
            // Filename matches get medium score
            if (pdf.fileName.contains(term, ignoreCase = true)) score += 2.0
            // Other fields get lower score
            if (pdf.subject?.contains(term, ignoreCase = true) == true) score += 1.0
            if (pdf.keywords.any { it.contains(term, ignoreCase = true) }) score += 1.0
            // Custom properties get lower score
            if (pdf.customProperties.values.any { it.contains(term, ignoreCase = true) }) score += 0.5
        }

        return score
    }
}