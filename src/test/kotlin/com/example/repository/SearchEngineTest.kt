package com.example.repository

import com.example.metadata.PDFMetadata
import kotlin.time.Clock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEngineTest {

    private val engine = SearchEngine()
    private val now = Clock.System.now()

    private fun pdf(
        id: String = "1",
        fileName: String = "file.pdf",
        title: String? = null,
        author: String? = null,
        subject: String? = null,
        creator: String? = null,
        producer: String? = null,
        keywords: List<String> = emptyList(),
        customProperties: Map<String, String> = emptyMap()
    ) = PDFMetadata(
        id = id,
        path = "/test/$fileName",
        fileName = fileName,
        fileSize = 1000L,
        pageCount = 1,
        createdDate = null,
        modifiedDate = null,
        title = title,
        author = author,
        subject = subject,
        creator = creator,
        producer = producer,
        keywords = keywords,
        pdfVersion = "1.4",
        customProperties = customProperties,
        contentHash = null,
        indexedAt = now
    )

    // --- searchByQuery: basic behavior ---

    @Test
    fun `blank query returns all metadata`() {
        val pdfs = listOf(pdf(id = "1"), pdf(id = "2"))
        val results = engine.searchByQuery(pdfs, "   ")
        assertEquals(2, results.size)
    }

    @Test
    fun `empty collection returns empty results`() {
        val results = engine.searchByQuery(emptyList(), "test")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `no matches returns empty results`() {
        val pdfs = listOf(pdf(fileName = "report.pdf", title = "Annual Report"))
        val results = engine.searchByQuery(pdfs, "nonexistent")
        assertTrue(results.isEmpty())
    }

    // --- searchByQuery: field matching ---

    @Test
    fun `matches by fileName`() {
        val pdfs = listOf(pdf(fileName = "kotlin-guide.pdf"), pdf(fileName = "java-guide.pdf"))
        val results = engine.searchByQuery(pdfs, "kotlin")
        assertEquals(1, results.size)
        assertEquals("kotlin-guide.pdf", results[0].fileName)
    }

    @Test
    fun `matches by title`() {
        val pdfs = listOf(pdf(id = "1", title = "Machine Learning Basics"), pdf(id = "2", title = "Cooking 101"))
        val results = engine.searchByQuery(pdfs, "machine")
        assertEquals(1, results.size)
        assertEquals("Machine Learning Basics", results[0].title)
    }

    @Test
    fun `matches by author`() {
        val pdfs = listOf(pdf(id = "1", author = "Alice Smith"), pdf(id = "2", author = "Bob Jones"))
        val results = engine.searchByQuery(pdfs, "alice")
        assertEquals(1, results.size)
        assertEquals("Alice Smith", results[0].author)
    }

    @Test
    fun `matches by subject`() {
        val pdfs = listOf(pdf(id = "1", subject = "Computer Science"), pdf(id = "2", subject = "Biology"))
        val results = engine.searchByQuery(pdfs, "biology")
        assertEquals(1, results.size)
    }

    @Test
    fun `matches by creator`() {
        val pdfs = listOf(pdf(id = "1", creator = "LibreOffice"), pdf(id = "2", creator = "Word"))
        val results = engine.searchByQuery(pdfs, "libreoffice")
        assertEquals(1, results.size)
    }

    @Test
    fun `matches by producer`() {
        val pdfs = listOf(pdf(id = "1", producer = "PDFBox 3.0"), pdf(id = "2", producer = "iText"))
        val results = engine.searchByQuery(pdfs, "pdfbox")
        assertEquals(1, results.size)
    }

    @Test
    fun `matches by keyword`() {
        val pdfs = listOf(
            pdf(id = "1", keywords = listOf("science", "research")),
            pdf(id = "2", keywords = listOf("cooking", "recipes"))
        )
        val results = engine.searchByQuery(pdfs, "research")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun `matches by custom property value`() {
        val pdfs = listOf(
            pdf(id = "1", customProperties = mapOf("genre" to "sci-fi")),
            pdf(id = "2", customProperties = mapOf("genre" to "romance"))
        )
        val results = engine.searchByQuery(pdfs, "sci-fi")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    // --- searchByQuery: case insensitivity ---

    @Test
    fun `search is case insensitive`() {
        val pdfs = listOf(pdf(title = "Kotlin Programming"))
        assertEquals(1, engine.searchByQuery(pdfs, "KOTLIN").size)
        assertEquals(1, engine.searchByQuery(pdfs, "kotlin").size)
        assertEquals(1, engine.searchByQuery(pdfs, "Kotlin").size)
    }

    // --- searchByQuery: multi-term (AND logic) ---

    @Test
    fun `multi-term query requires all terms to match`() {
        val pdfs = listOf(
            pdf(id = "1", title = "Kotlin Programming Guide"),
            pdf(id = "2", title = "Kotlin Reference"),
            pdf(id = "3", title = "Java Programming Guide")
        )
        val results = engine.searchByQuery(pdfs, "kotlin guide")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun `multi-term can match across different fields`() {
        val pdfs = listOf(
            pdf(id = "1", title = "Advanced Algorithms", author = "Alice Smith"),
            pdf(id = "2", title = "Advanced Cooking", author = "Bob Jones")
        )
        val results = engine.searchByQuery(pdfs, "advanced alice")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    // --- searchByQuery: relevance scoring ---

    @Test
    fun `title match ranks higher than filename match`() {
        val pdfs = listOf(
            pdf(id = "filename-match", fileName = "kotlin.pdf"),
            pdf(id = "title-match", title = "Kotlin")
        )
        val results = engine.searchByQuery(pdfs, "kotlin")
        assertEquals(2, results.size)
        assertEquals("title-match", results[0].id)
    }

    @Test
    fun `author match ranks higher than subject match`() {
        val pdfs = listOf(
            pdf(id = "subject-match", subject = "Kotlin"),
            pdf(id = "author-match", author = "Kotlin Expert")
        )
        val results = engine.searchByQuery(pdfs, "kotlin")
        assertEquals(2, results.size)
        assertEquals("author-match", results[0].id)
    }

    @Test
    fun `title plus author match ranks highest`() {
        val pdfs = listOf(
            pdf(id = "keyword-only", keywords = listOf("kotlin")),
            pdf(id = "title-only", title = "Kotlin Guide"),
            pdf(id = "title-and-author", title = "Kotlin Guide", author = "Kotlin Expert")
        )
        val results = engine.searchByQuery(pdfs, "kotlin")
        assertEquals(3, results.size)
        assertEquals("title-and-author", results[0].id)
        assertEquals("title-only", results[1].id)
    }

    @Test
    fun `custom property match has lowest score`() {
        val pdfs = listOf(
            pdf(id = "custom", customProperties = mapOf("tag" to "kotlin")),
            pdf(id = "subject", subject = "Kotlin topics")
        )
        val results = engine.searchByQuery(pdfs, "kotlin")
        assertEquals(2, results.size)
        assertEquals("subject", results[0].id)
    }

    // --- searchByQuery: null field handling ---

    @Test
    fun `handles pdfs with all null optional fields`() {
        val pdfs = listOf(pdf(id = "1", fileName = "report.pdf"))
        val results = engine.searchByQuery(pdfs, "report")
        assertEquals(1, results.size)
    }

    @Test
    fun `does not match on null fields`() {
        val pdfs = listOf(pdf(id = "1", title = null, author = null))
        val results = engine.searchByQuery(pdfs, "anything")
        assertTrue(results.isEmpty())
    }

    // --- searchByProperty ---

    @Test
    fun `searchByProperty by author`() {
        val pdfs = listOf(
            pdf(id = "1", author = "Alice Smith"),
            pdf(id = "2", author = "Bob Jones"),
            pdf(id = "3", author = null)
        )
        val results = engine.searchByProperty(pdfs, "author", "alice")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun `searchByProperty by title`() {
        val pdfs = listOf(
            pdf(id = "1", title = "Kotlin Guide"),
            pdf(id = "2", title = "Java Guide")
        )
        val results = engine.searchByProperty(pdfs, "title", "kotlin")
        assertEquals(1, results.size)
    }

    @Test
    fun `searchByProperty by subject`() {
        val pdfs = listOf(pdf(id = "1", subject = "Computer Science"))
        val results = engine.searchByProperty(pdfs, "subject", "computer")
        assertEquals(1, results.size)
    }

    @Test
    fun `searchByProperty by keywords`() {
        val pdfs = listOf(
            pdf(id = "1", keywords = listOf("ai", "machine-learning")),
            pdf(id = "2", keywords = listOf("cooking"))
        )
        val results = engine.searchByProperty(pdfs, "keywords", "machine")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun `searchByProperty by custom property`() {
        val pdfs = listOf(
            pdf(id = "1", customProperties = mapOf("genre" to "sci-fi", "series" to "Dune")),
            pdf(id = "2", customProperties = mapOf("genre" to "romance"))
        )
        val results = engine.searchByProperty(pdfs, "genre", "sci-fi")
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test
    fun `searchByProperty key is case insensitive for standard fields`() {
        val pdfs = listOf(pdf(id = "1", author = "Alice"))
        assertEquals(1, engine.searchByProperty(pdfs, "AUTHOR", "Alice").size)
        assertEquals(1, engine.searchByProperty(pdfs, "Author", "Alice").size)
    }

    @Test
    fun `searchByProperty custom key is case sensitive`() {
        val pdfs = listOf(pdf(id = "1", customProperties = mapOf("Genre" to "sci-fi")))
        assertEquals(0, engine.searchByProperty(pdfs, "genre", "sci-fi").size)
        assertEquals(1, engine.searchByProperty(pdfs, "Genre", "sci-fi").size)
    }

    @Test
    fun `searchByProperty value is case insensitive`() {
        val pdfs = listOf(pdf(id = "1", author = "Alice Smith"))
        assertEquals(1, engine.searchByProperty(pdfs, "author", "ALICE").size)
        assertEquals(1, engine.searchByProperty(pdfs, "author", "alice smith").size)
    }

    @Test
    fun `searchByProperty with no matches returns empty`() {
        val pdfs = listOf(pdf(id = "1", author = "Alice"))
        val results = engine.searchByProperty(pdfs, "author", "Bob")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchByProperty partial match works`() {
        val pdfs = listOf(pdf(id = "1", title = "Introduction to Machine Learning"))
        val results = engine.searchByProperty(pdfs, "title", "machine")
        assertEquals(1, results.size)
    }
}
