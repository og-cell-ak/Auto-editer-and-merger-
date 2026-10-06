package com.futurethinking.aivideodirector.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Renders PDF pages and, when present, splits each page into panels using
 * full-width yellow separator lines. The resulting panel files are flattened
 * in PDF reading order and become the timestamp-to-visual sequence.
 */
class PdfVisualExtractor(private val context: android.content.Context) {

    data class Result(
        val pageCount: Int,
        val selectedPages: List<Int>,
        val visualPaths: List<String>
    )

    fun extract(
        pdfPath: String,
        outputDir: File,
        onProgress: (Int, String) -> Unit
    ): Result {
        val pdf = File(pdfPath)
        require(pdf.exists() && pdf.length() > 0L) { "PDF file is missing or empty" }
        outputDir.mkdirs()

        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount > 0) { "The PDF contains no pages" }

                val paths = ArrayList<String>()
                val sourcePages = ArrayList<Int>()

                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        // The final export is 1080p, so 1920 px is enough source detail
                        // while substantially reducing RAM, JPEG size, and encoder pressure.
                        val size = scaledSize(page.width, page.height, 1920)
                        val bitmap = Bitmap.createBitmap(
                            size.first,
                            size.second,
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )

                            val separators = findYellowSeparatorBands(bitmap)
                            val ranges = panelRanges(bitmap.height, separators)

                            ranges.forEachIndexed { panelIndex, range ->
                                val top = range.first.coerceIn(0, bitmap.height - 1)
                                val bottom = range.second.coerceIn(top + 1, bitmap.height)
                                val panelHeight = bottom - top

                                if (panelHeight >= max(32, bitmap.height / 100)) {
                                    val panel = Bitmap.createBitmap(
                                        bitmap,
                                        0,
                                        top,
                                        bitmap.width,
                                        panelHeight
                                    )
                                    try {
                                        val suffix = if (ranges.size == 1) {
                                            ""
                                        } else {
                                            "-panel-" + (panelIndex + 1).toString().padStart(3, '0')
                                        }
                                        val output = File(
                                            outputDir,
                                            "pdf-page-" +
                                                (pageIndex + 1).toString().padStart(4, '0') +
                                                suffix + ".jpg"
                                        )
                                        output.outputStream().use { stream ->
                                            require(
                                                panel.compress(
                                                    Bitmap.CompressFormat.JPEG,
                                                    90,
                                                    stream
                                                )
                                            ) {
                                                "Failed to encode PDF panel " +
                                                    (pageIndex + 1) + "-" + (panelIndex + 1)
                                            }
                                        }
                                        require(output.exists() && output.length() > 2048L) {
                                            "Failed to extract PDF panel " +
                                                (pageIndex + 1) + "-" + (panelIndex + 1)
                                        }
                                        paths += output.absolutePath
                                        sourcePages += pageIndex
                                    } finally {
                                        panel.recycle()
                                    }
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    val percent = (10 + ((pageIndex + 1) * 45 / renderer.pageCount))
                        .coerceIn(10, 55)
                    onProgress(
                        percent,
                        "Extracting PDF panels " + (pageIndex + 1) + "/" + renderer.pageCount
                    )
                }

                require(paths.isNotEmpty()) { "No usable panels were extracted from the PDF" }

                return Result(
                    pageCount = renderer.pageCount,
                    selectedPages = sourcePages,
                    visualPaths = paths
                )
            }
        } finally {
            pfd.close()
        }
    }

    /**
     * Finds horizontal bands where yellow pixels occupy most of the page width.
     * This intentionally requires broad horizontal coverage so yellow speech
     * bubbles, clothing, artwork, etc. do not become panel separators.
     */
    private fun findYellowSeparatorBands(bitmap: Bitmap): List<IntRange> {
        val w = bitmap.width
        val h = bitmap.height
        val xStep = max(1, w / 320)
        val minCoverage = 0.55f
        val candidateRows = ArrayList<Int>()

        for (y in 0 until h) {
            var yellow = 0
            var sampled = 0
            var x = 0
            while (x < w) {
                if (isSeparatorYellow(bitmap.getPixel(x, y))) yellow++
                sampled++
                x += xStep
            }
            if (sampled > 0 && yellow.toFloat() / sampled.toFloat() >= minCoverage) {
                candidateRows += y
            }
        }

        if (candidateRows.isEmpty()) return emptyList()

        val bands = ArrayList<IntRange>()
        var start = candidateRows.first()
        var previous = start

        for (i in 1 until candidateRows.size) {
            val y = candidateRows[i]
            if (y > previous + 2) {
                bands += start..previous
                start = y
            }
            previous = y
        }
        bands += start..previous

        return bands.filter { it.last - it.first + 1 <= max(40, h / 25) }
    }

    /**
     * Accept common bright yellow/golden separator colors while rejecting
     * white, gray, black and most manga artwork colors.
     */
    private fun isSeparatorYellow(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        return r >= 170 &&
            g >= 125 &&
            b <= 155 &&
            r - b >= 70 &&
            g - b >= 35 &&
            r + g >= 390
    }

    private fun panelRanges(
        height: Int,
        separators: List<IntRange>
    ): List<Pair<Int, Int>> {
        if (separators.isEmpty()) return listOf(0 to height)

        val ranges = ArrayList<Pair<Int, Int>>()
        var cursor = 0

        separators.forEach { band ->
            val separatorStart = band.first.coerceIn(0, height)
            if (separatorStart > cursor) {
                ranges += cursor to separatorStart
            }
            cursor = (band.last + 1).coerceIn(cursor, height)
        }

        if (cursor < height) {
            ranges += cursor to height
        }

        return ranges
            .filter { (top, bottom) -> bottom - top >= max(32, height / 100) }
            .ifEmpty { listOf(0 to height) }
    }

    private fun scaledSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        val scale = min(1f, maxDim.toFloat() / max(width, height).coerceAtLeast(1))
        return Pair(
            max(2, (width * scale).toInt()),
            max(2, (height * scale).toInt())
        )
    }
}
