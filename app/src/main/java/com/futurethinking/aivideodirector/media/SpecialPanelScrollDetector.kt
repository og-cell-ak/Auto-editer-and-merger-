package com.futurethinking.aivideodirector.media

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Detects the exact panel marker used to request the long-panel scroll.
 *
 * The marker must be recognised by OCR and must be located in the upper-left
 * area of the panel. Normal panels therefore remain completely untouched.
 */
class SpecialPanelScrollDetector(context: Context) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val appContext = context.applicationContext

    suspend fun containsTargetText(source: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromFilePath(appContext, Uri.fromFile(source))
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (!continuation.isActive) return@addOnSuccessListener

                        val width = image.width.coerceAtLeast(1)
                        val height = image.height.coerceAtLeast(1)

                        val topLeftLines = result.textBlocks
                            .flatMap { block -> block.lines }
                            .filter { line ->
                                val box = line.boundingBox ?: return@filter false
                                box.left <= width * TOP_LEFT_MAX_X &&
                                    box.top <= height * TOP_LEFT_MAX_Y
                            }
                            .map { it.text }

                        val matched = topLeftLines.any(::matchesTargetText) ||
                            matchesTargetText(topLeftLines.joinToString(" "))

                        continuation.resume(matched)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(false)
                    }
            } catch (_: Throwable) {
                if (continuation.isActive) continuation.resume(false)
            }
        }

    /** Returns true when OCR finds any visible text in the supplied panel image. */
    suspend fun containsAnyText(source: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromFilePath(appContext, Uri.fromFile(source))
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) {
                            continuation.resume(
                                result.textBlocks.any { block ->
                                    block.lines.any { line -> line.text.isNotBlank() }
                                }
                            )
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(true)
                    }
            } catch (_: Throwable) {
                // If OCR cannot read the image, do not misclassify it as textless.
                if (continuation.isActive) continuation.resume(true)
            }
        }

    override fun close() {
        recognizer.close()
    }

    companion object {
        const val TARGET_TEXT = "Manhwa Talks 007"
        private const val TOP_LEFT_MAX_X = 0.55f
        private const val TOP_LEFT_MAX_Y = 0.40f

        fun matchesTargetText(text: String): Boolean {
            val normalized = text
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
            return normalized.contains("manhwa talks 007")
        }
    }
}
