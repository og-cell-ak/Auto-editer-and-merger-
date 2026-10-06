package com.futurethinking.aivideodirector.pipeline

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.futurethinking.aivideodirector.data.ScenePlan
import java.io.File
import kotlin.math.abs

object QualityControl {
    data class Report(val ok: Boolean, val issues: List<String>)

    fun inspectTimeline(
        scenes: List<ScenePlan>,
        audioDurationMs: Long,
        orderedPdfVisuals: List<String>,
        frameToleranceMs: Long
    ): Report {
        val issues = mutableListOf<String>()
        if (scenes.isEmpty()) issues += "timeline_empty"
        if (audioDurationMs <= 0L) issues += "audio_duration_invalid"

        if (scenes.isNotEmpty()) {
            if (scenes.first().startMs != 0L) issues += "timeline_does_not_start_at_zero"
            for (i in scenes.indices) {
                val scene = scenes[i]
                if (scene.endMs <= scene.startMs) issues += "non_positive_scene_duration_" + scene.id
                if (i > 0 && scene.startMs != scenes[i - 1].endMs) issues += "timeline_discontinuity_near_" + scene.id
                if (scene.isBlackFrame) {
                    if (scene.visualPath != null || scene.pdfOrdinal != null) issues += "black_scene_contains_pdf_asset_" + scene.id
                } else {
                    val ordinal = scene.pdfOrdinal
                    if (ordinal == null || ordinal < 1) {
                        issues += "missing_pdf_ordinal_" + scene.id
                    } else {
                        val expected = orderedPdfVisuals.getOrNull(ordinal - 1)
                        if (expected == null || expected != scene.visualPath) issues += "pdf_order_mismatch_" + scene.id
                    }
                    if (scene.visualPath == null || !File(scene.visualPath).exists()) issues += "missing_pdf_visual_" + scene.id
                    if (scene.motionDirection == null) issues += "missing_motion_plan_" + scene.id
                    if (abs(scene.startMs - scene.requestedStartMs) > frameToleranceMs + 1L) issues += "timestamp_quantization_exceeded_" + scene.id
                    scene.requestedEndMs?.let { requestedEnd ->
                        if (abs(scene.endMs - requestedEnd) > frameToleranceMs + 1L) issues += "timestamp_end_quantization_exceeded_" + scene.id
                    }
                }
            }
            val orderedScenes = scenes.filter { !it.isBlackFrame }
            orderedScenes.forEachIndexed { index, scene ->
                if (scene.pdfOrdinal != index + 1) issues += "pdf_sequence_not_contiguous_at_" + scene.id
            }
            if (abs(scenes.last().endMs - audioDurationMs) > frameToleranceMs + 2L) issues += "timeline_audio_duration_mismatch"
        }
        return Report(issues.isEmpty(), issues.distinct())
    }

    fun inspectRenderedFile(file: File, expectedDurationMs: Long, requireAudio: Boolean): String? {
        if (!file.exists()) return "file_missing"
        if (file.length() <= 8192L) return "file_too_small"
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video = false
            var audio = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime == "video/avc") video = true
                if (mime == "audio/mp4a-latm") audio = true
            }
            if (!video) return "missing_h264_video_track"
            if (requireAudio && !audio) return "missing_aac_audio_track"
        } catch (t: Throwable) {
            return "container_error_" + t.javaClass.simpleName
        } finally {
            extractor.release()
        }
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                when {
                    duration <= 0L -> "invalid_duration"
                    abs(duration - expectedDurationMs) > 3000L -> "duration_mismatch"
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) == null -> "first_frame_not_decodable"
                    else -> null
                }
            }
        }.getOrElse { "decoder_error_" + it.javaClass.simpleName }
    }
}
