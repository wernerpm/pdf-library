package com.example.metadata

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ThumbnailGenerator(
    private val targetWidth: Int = DEFAULT_WIDTH
) {

    private val logger = LoggerFactory.getLogger(ThumbnailGenerator::class.java)

    fun generateThumbnail(document: PDDocument): ByteArray? {
        return try {
            if (document.numberOfPages == 0) {
                logger.debug("PDF has no pages, skipping thumbnail generation")
                return null
            }

            val renderer = PDFRenderer(document)
            val image = renderer.renderImageWithDPI(0, RENDER_DPI, ImageType.RGB)

            val scaled = scaleImage(image)

            val outputStream = ByteArrayOutputStream()
            ImageIO.write(scaled, "PNG", outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            logger.warn("Failed to generate thumbnail", e)
            null
        }
    }

    private fun scaleImage(image: BufferedImage): BufferedImage {
        if (image.width <= targetWidth) {
            return image
        }

        val scaleFactor = targetWidth.toDouble() / image.width
        val newHeight = (image.height * scaleFactor).toInt()

        val scaled = BufferedImage(targetWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            graphics.drawImage(image, 0, 0, targetWidth, newHeight, null)
        } finally {
            graphics.dispose()
        }
        return scaled
    }

    companion object {
        const val DEFAULT_WIDTH = 300
        const val RENDER_DPI = 72f
    }
}
