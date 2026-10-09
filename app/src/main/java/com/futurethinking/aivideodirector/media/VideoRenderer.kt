package com.futurethinking.aivideodirector.media

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.futurethinking.aivideodirector.data.AppPreferences
import com.futurethinking.aivideodirector.data.Enums
import com.futurethinking.aivideodirector.data.ScenePlan
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.max

class VideoRenderer(private val context: Context) {
    suspend fun render(
        scenes: List<ScenePlan>,
        narrationPath: String?,
        preferences: AppPreferences,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
        MediaExportGate.withLock {
        require(scenes.isNotEmpty()) { "No timeline scenes were generated" }
        val narration = File(narrationPath ?: "")
        require(narration.exists() && narration.length() > 4096L) {
            "Narration audio is missing or empty"
        }

        val expectedDurationMs = scenes.maxOf { it.endMs }
        require(expectedDurationMs > 0L) { "Timeline duration is invalid" }

        outputFile.parentFile?.mkdirs()
        val frameDir = File(outputFile.parentFile, "." + outputFile.nameWithoutExtension + "_frames")
        frameDir.deleteRecursively()
        frameDir.mkdirs()

        val temp = File(outputFile.parentFile, "." + outputFile.nameWithoutExtension + ".rendering.mp4")
        if (temp.exists()) temp.delete()

        val specialPanelScrollDetector = SpecialPanelScrollDetector(context)

        try {
            val frameItems = scenes.mapIndexed { index, scene ->
                val blackFrame = File(frameDir, "scene-" + index.toString().padStart(4, '0') + ".jpg")
                val visualSource: File?
                val inputImage: File
                if (scene.isBlackFrame) {
                    SceneFrameFactory.createBlack(blackFrame, preferences.aspectRatio)
                    visualSource = null
                    inputImage = blackFrame
                } else {
                    val path = requireNotNull(scene.visualPath) {
                        "PDF visual is missing for scene " + index
                    }
                    val source = File(path)
                    require(source.exists() && source.isFile && source.length() > 0L) {
                        "PDF visual is missing or empty: " + path
                    }
                    // Keep the original extracted panel dimensions. Fitting a tall
                    // panel into a fixed-size JPEG here loses its scrollable extent.
                    visualSource = source
                    inputImage = source
                }

                onProgress(
                    20 + ((index + 1) * 45 / scenes.size.coerceAtLeast(1))
                )

                val durationMs = scene.durationMs.coerceAtLeast(34L)
                val item = MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(inputImage))
                    .setImageDurationMs(durationMs)
                    .build()

                // Only the exact "Manhwa Talks 007" marker in the panel's
                // upper-left area selects 70% zoom. Ordinary text elsewhere
                // must not trigger it; every other panel uses 20% extra zoom.
                val hasMarker = visualSource?.let {
                    specialPanelScrollDetector.containsTargetText(it)
                } ?: false
                val zoom = if (hasMarker) 1.7f else 1.2f
                val effects = Effects(
                    emptyList(),
                    listOf(buildFixedZoomScroll(
                        durationMs,
                        preferences.aspectRatio.width,
                        preferences.aspectRatio.height,
                        zoom
                    ))
                )

                EditedMediaItem.Builder(item)
                    .setEffects(effects)
                    .setFrameRate(preferences.fps.coerceIn(24, 60))
                    .setDurationUs(durationMs * 1000L)
                    .build()
            }

            val videoSequence = EditedMediaItemSequence.withVideoFrom(frameItems)
            val audioItem = EditedMediaItem.Builder(
                MediaItem.fromUri(android.net.Uri.fromFile(narration))
            ).build()
            val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
            val composition = Composition.Builder(videoSequence, audioSequence).build()

            onProgress(68)
            awaitExport(composition, temp.absolutePath, onProgress)
            require(temp.exists() && temp.length() > 8192L) {
                "Renderer completed without a usable MP4"
            }

            val issue = validateExport(temp, expectedDurationMs, preferences.fps)
            require(issue == null) { "Rendered MP4 failed validation: " + issue }

            if (outputFile.exists() && !outputFile.delete()) {
                error("Unable to replace previous output MP4")
            }
            check(temp.renameTo(outputFile)) {
                "Unable to finalize output MP4"
            }
            onProgress(98)
        } finally {
            specialPanelScrollDetector.close()
            frameDir.deleteRecursively()
            if (temp.exists()) temp.delete()
        }
        }
    }

    /**
     * Map the original panel into the requested output geometry without
     * flattening it into a pre-sized frame. The panel starts top-aligned at a
     * constant zoom; a linear translation carries its lower edge to the
     * viewport over exactly this scene's timestamp duration.
     *
     * MatrixTransformation coordinates are normalized device coordinates
     * (-1..1), not pixels. Pixel translations send the image out of view.
     */
    private fun buildFixedZoomScroll(
        durationMs: Long,
        outputWidth: Int,
        outputHeight: Int,
        zoom: Float
    ): MatrixTransformation {
        val durationUs = (durationMs * 1000L).coerceAtLeast(1L)
        val firstPresentationTimeUs = AtomicLong(Long.MIN_VALUE)

        return object : MatrixTransformation {
            @Volatile
            private var scaleX = zoom

            @Volatile
            private var scaleY = zoom

            override fun configure(inputWidth: Int, inputHeight: Int): Size {
                val inputAspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
                val outputAspect = outputWidth.toFloat() / outputHeight.coerceAtLeast(1)
                // Cover the output without stretching or leaving bars. For a
                // tall panel scaleY contains its full height; for a wide panel
                // scaleX contains its horizontal overflow.
                scaleX = max(1f, inputAspect / outputAspect) * zoom
                scaleY = max(1f, outputAspect / inputAspect) * zoom
                return Size(outputWidth, outputHeight)
            }

            override fun getMatrix(presentationTimeUs: Long): Matrix {
                val first = firstPresentationTimeUs.updateAndGet { current ->
                    if (current == Long.MIN_VALUE) presentationTimeUs else current
                }
                val localTimeUs = (presentationTimeUs - first).coerceIn(0L, durationUs)
                val progress = (localTimeUs.toDouble() / durationUs.toDouble())
                    .coerceIn(0.0, 1.0).toFloat()

                // Translation is exactly the normalized overflow: top aligned
                // at the start, bottom aligned at the end. Linear timing makes
                // 3-second panels move faster than 20-second panels and avoids
                // easing spikes. The image moves upward as the viewer reads down.
                val overflow = (scaleY - 1f).coerceAtLeast(0f)
                val translationY = overflow * (2f * progress - 1f)

                return Matrix().apply {
                    setScale(scaleX, scaleY)
                    postTranslate(0f, translationY)
                }
            }
        }
    }

    private suspend fun awaitExport(
        composition: Composition,
        path: String,
        onProgress: (Int) -> Unit
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            if (!continuation.isActive) return@post

            val holder = ProgressHolder()
            val done = AtomicBoolean(false)
            var transformer: Transformer? = null

            val poller = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    transformer?.let {
                        if (it.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(68 + (holder.progress * 28 / 100).coerceIn(0, 28))
                        }
                    }
                    if (!done.get()) handler.postDelayed(this, 400L)
                }
            }

            val listener = object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacks(poller)
                    continuation.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacks(poller)
                    continuation.resumeWithException(exportException)
                }
            }

            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()

            continuation.invokeOnCancellation {
                handler.removeCallbacks(poller)
                runCatching { transformer?.cancel() }
            }

            transformer?.start(composition, path)
            handler.post(poller)
        }
    }

    private fun validateExport(file: File, expectedDurationMs: Long, fps: Int): String? {
        val tolerance = maxOf(75L, kotlin.math.ceil(2000.0 / fps.toDouble()).toLong())
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            when {
                duration <= 0L -> "invalid_duration"
                abs(duration - expectedDurationMs) > 5000L ->
                    "duration_mismatch_expected_" + expectedDurationMs + "_actual_" + duration
                retriever.getFrameAtTime(
                    0L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) == null -> "first_frame_not_decodable"
                else -> null
            }
        } catch (t: Throwable) {
            "decoder_error_" + t.javaClass.simpleName
        } finally {
            retriever.release()
        }
    }
}
