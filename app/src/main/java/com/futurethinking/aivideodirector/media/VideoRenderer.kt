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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.abs

class VideoRenderer(private val context: Context) {
    suspend fun render(
        scenes: List<ScenePlan>,
        narrationPath: String?,
        preferences: AppPreferences,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
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

                val effects = if (scene.isBlackFrame || scene.motionDirection == null) {
                    Effects(emptyList(), emptyList())
                } else {
                    Effects(
                        emptyList(),
                        listOf(buildSubtlePan(scene.motionDirection, durationMs))
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
            frameDir.deleteRecursively()
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Applies a very small, bounded Ken-Burns-style translation.
     *
     * The image is always scaled above 1.0 before translation. This is the
     * critical guard against exposing the transformed frame's transparent/
     * empty area. Translation stays far inside the available crop margin.
     */
    private fun buildSubtlePan(
        direction: Enums.MotionDirection,
        durationMs: Long
    ): MatrixTransformation = MatrixTransformation { presentationTimeUs ->
        val durationUs = (durationMs * 1000L).coerceAtLeast(1L)
        val progress = (presentationTimeUs.toDouble() / durationUs.toDouble())
            .coerceIn(0.0, 1.0)
        val eased = (0.5 - 0.5 * kotlin.math.cos(progress * Math.PI)).toFloat()

        val scale = 1.018f + (0.010f * eased)
        val travel = 0.006f
        val tx = when (direction) {
            Enums.MotionDirection.LEFT_TO_RIGHT -> -travel + (2f * travel * eased)
            Enums.MotionDirection.RIGHT_TO_LEFT -> travel - (2f * travel * eased)
            else -> 0f
        }
        val ty = when (direction) {
            Enums.MotionDirection.TOP_TO_BOTTOM -> -travel + (2f * travel * eased)
            Enums.MotionDirection.BOTTOM_TO_TOP -> travel - (2f * travel * eased)
            else -> 0f
        }

        Matrix().apply {
            setScale(scale, scale)
            postTranslate(tx, ty)
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
                abs(duration - expectedDurationMs) > tolerance ->
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
