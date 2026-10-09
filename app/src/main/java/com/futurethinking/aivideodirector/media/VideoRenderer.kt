package com.futurethinking.aivideodirector.media

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
                val frame = File(frameDir, "scene-" + index.toString().padStart(4, '0') + ".jpg")
                if (scene.isBlackFrame) {
                    SceneFrameFactory.createBlack(frame, preferences.aspectRatio)
                } else {
                    val path = requireNotNull(scene.visualPath) {
                        "PDF visual is missing for scene " + index
                    }
                    val source = File(path)
                    require(source.exists() && source.isFile && source.length() > 0L) {
                        "PDF visual is missing or empty: " + path
                    }
                    SceneFrameFactory.create(source, frame, preferences.aspectRatio)
                }

                onProgress(
                    20 + ((index + 1) * 45 / scenes.size.coerceAtLeast(1))
                )

                val durationMs = scene.durationMs.coerceAtLeast(34L)
                val item = MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(frame))
                    .setImageDurationMs(durationMs)
                    .build()

                val hasMarker = if (scene.isBlackFrame) {
                    false
                } else {
                    val source = requireNotNull(scene.visualPath).let(::File)
                    specialPanelScrollDetector.containsTargetText(source) ||
                        specialPanelScrollDetector.containsTargetText(frame)
                }
                val hasText = if (scene.isBlackFrame) {
                    false
                } else {
                    specialPanelScrollDetector.containsAnyText(frame)
                }

                // Preserve the existing Manhwa Talks 007 motion. Otherwise,
                // all panels scroll downward at a fixed zoom selected by OCR:
                // textless = 1.2x (20%); text present = 1.5x (50%).
                val effects = when {
                    hasMarker -> Effects(
                        emptyList(),
                        listOf(buildManhwaScroll(durationMs))
                    )
                    else -> Effects(
                        emptyList(),
                        listOf(buildFixedZoomScroll(durationMs, preferences.aspectRatio.height, if (hasText) 1.5f else 1.2f))
                    )
                }

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
     * Smoothly scrolls a special long panel from its upper half to its lower
     * half. The 2x scale makes roughly 50% of the source frame visible at a
     * time. The motion occupies the entire scene duration, so a five-second
     * scene scrolls over five seconds and a ten-second scene scrolls over ten.
     */
    private fun buildManhwaScroll(durationMs: Long): MatrixTransformation {
        val durationUs = (durationMs * 1000L).coerceAtLeast(1L)
        val firstPresentationTimeUs = AtomicLong(Long.MIN_VALUE)

        return MatrixTransformation { presentationTimeUs ->
            // Give every special panel its own time origin. This prevents a
            // later special panel from inheriting an already-completed scroll.
            val first = firstPresentationTimeUs.updateAndGet { current ->
                if (current == Long.MIN_VALUE) presentationTimeUs else current
            }
            val localTimeUs = (presentationTimeUs - first).coerceIn(0L, durationUs)
            val progress = localTimeUs.toDouble() / durationUs.toDouble()

            // Smoothstep gives a continuous, gentle start and finish with no
            // sudden jump between the upper and lower halves.
            val eased = (progress * progress * (3.0 - 2.0 * progress)).toFloat()
            val verticalTranslation = -1.0f + (2.0f * eased)

            Matrix().apply {
                setScale(2.0f, 2.0f)
                postTranslate(0f, verticalTranslation)
            }
        }
    }

    /**
     * Keeps a constant zoom for this panel while smoothly moving the image
     * upward in frame coordinates, making the visible viewport scroll down.
     * Textless panels use 1.2x (20%); panels with text use 1.5x (50%).
     */
    private fun buildFixedZoomScroll(
        durationMs: Long,
        frameHeight: Int,
        scale: Float
    ): MatrixTransformation {
        val durationUs = (durationMs * 1000L).coerceAtLeast(1L)
        val firstPresentationTimeUs = AtomicLong(Long.MIN_VALUE)

        return MatrixTransformation { presentationTimeUs ->
            val first = firstPresentationTimeUs.updateAndGet { current ->
                if (current == Long.MIN_VALUE) presentationTimeUs else current
            }
            val localTimeUs = (presentationTimeUs - first).coerceIn(0L, durationUs)
            val progress = localTimeUs.toDouble() / durationUs.toDouble()
            val eased = (progress * progress * (3.0 - 2.0 * progress)).toFloat()
            val verticalTranslation = -frameHeight * 0.12f * eased

            Matrix().apply {
                setScale(scale, scale)
                postTranslate(0f, verticalTranslation)
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
