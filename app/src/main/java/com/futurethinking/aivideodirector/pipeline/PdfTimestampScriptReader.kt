package com.futurethinking.aivideodirector.pipeline

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

class PdfTimestampScriptReader(private val context: android.content.Context) {

    fun read(pdfPath: String): String {
        val file = File(pdfPath)
        require(file.exists() && file.length() > 0L) { "Timestamp script PDF is missing or empty." }

        PDFBoxResourceLoader.init(context)
        PDDocument.load(file).use { document ->
            require(document.numberOfPages > 0) { "Timestamp script PDF contains no pages." }

            val text = PDFTextStripper().apply {
                sortByPosition = true
                addMoreFormatting = false
            }.getText(document)

            val normalized = text
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")

            require(normalized.isNotBlank()) {
                "The timestamp script PDF has no readable text. Use a text-based timestamp PDF."
            }

            require(Regex("""(?m)^\s*(?:\[|\()?\s*\d{1,4}:\d{2}(?::\d{2})?(?:[.,]\d{1,3})?""").containsMatchIn(normalized)) {
                "No timestamps were found in the timestamp script PDF."
            }

            return normalized
        }
    }
}
