package com.example.metadata

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThumbnailGeneratorTest {

    private val generator = ThumbnailGenerator()

    @Test
    fun `generateThumbnail should return PNG bytes for valid PDF`() {
        val document = PDDocument()
        document.addPage(PDPage())

        val result = generator.generateThumbnail(document)
        document.close()

        assertNotNull(result)
        assertTrue(result.size > 4)
        // Check PNG magic header: 0x89 0x50 0x4E 0x47
        assertEquals(0x89.toByte(), result[0])
        assertEquals(0x50.toByte(), result[1])
        assertEquals(0x4E.toByte(), result[2])
        assertEquals(0x47.toByte(), result[3])
    }

    @Test
    fun `generateThumbnail should return null for zero-page document`() {
        val document = PDDocument()

        val result = generator.generateThumbnail(document)
        document.close()

        assertNull(result)
    }

    @Test
    fun `generateThumbnail with custom width should produce image of that width`() {
        val customWidth = 150
        val customGenerator = ThumbnailGenerator(targetWidth = customWidth)

        val document = PDDocument()
        document.addPage(PDPage())

        val result = customGenerator.generateThumbnail(document)
        document.close()

        assertNotNull(result)
        val image = ImageIO.read(ByteArrayInputStream(result))
        assertNotNull(image)
        assertEquals(customWidth, image.width)
    }

    @Test
    fun `generateThumbnail should preserve aspect ratio`() {
        val document = PDDocument()
        // Default PDPage is US Letter: 612 x 792 points
        document.addPage(PDPage())

        val result = generator.generateThumbnail(document)
        document.close()

        assertNotNull(result)
        val image = ImageIO.read(ByteArrayInputStream(result))
        assertNotNull(image)

        // 612 x 792 scaled to 300px width -> height should be ~388
        assertEquals(ThumbnailGenerator.DEFAULT_WIDTH, image.width)
        val expectedHeight = (792.0 / 612.0 * ThumbnailGenerator.DEFAULT_WIDTH).toInt()
        assertEquals(expectedHeight, image.height)
    }
}
