package com.futurethinking.aivideodirector.pipeline

import com.futurethinking.aivideodirector.data.AppPreferences
import com.futurethinking.aivideodirector.data.Enums
import com.futurethinking.aivideodirector.data.ScenePlan
import com.futurethinking.aivideodirector.data.TimestampMarker
import kotlin.math.abs
import kotlin.math.roundToLong

class TimestampScenePlanner {
    data class Result(
        val scenes: List<ScenePlan>,
        val frameToleranceMs: Long
    )

    fun plan(
        markers: List<TimestampMarker>,
        orderedPdfVisuals: List<String>,
        audioDurationMs: Long,
        preferences: AppPreferences
    ): Result {
        require(markers.isNotEmpty()) { "No timestamps were supplied." }
        require(audioDurationMs > 0L) { "Audio duration is unavailable." }
        require(markers.size <= orderedPdfVisuals.size) {
            "The script contains " + markers.size +
                " timestamped visuals but the PDF contains only " +
                orderedPdfVisuals.size + " pages."
        }

        val fps = preferences.fps.coerceIn(24, 60)
        val frameTolerance = (1000.0 / fps / 2.0).roundToLong().coerceAtLeast(1L)
        val scenes = mutableListOf<ScenePlan>()
        var cursor = 0L

        fun addBlack(start: Long, end: Long) {
            if (end <= start) return
            scenes += ScenePlan(
                id = scenes.size,
                startMs = start,
                endMs = end,
                narrationText = "",
                visualPath = null,
                pdfOrdinal = null,
                requestedStartMs = start,
                requestedEndMs = end,
                isBlackFrame = true,
                reason = "no_pdf_visual_scheduled",
                motionDirection = null
            )
        }

        var visualSceneIndex = 0

        markers.forEachIndexed { index, marker ->
            require(marker.startMs < audioDurationMs) {
                "Timestamp " + formatMs(marker.startMs) +
                    " is outside the audio duration " + formatMs(audioDurationMs) + "."
            }

            val start = snapToFrame(marker.startMs, fps).coerceIn(0L, audioDurationMs)
            require(abs(start - marker.startMs) <= frameTolerance + 1L) {
                "Timestamp quantization exceeded frame tolerance at " + formatMs(marker.startMs) + "."
            }

            val nextStart = markers.getOrNull(index + 1)?.startMs?.let { snapToFrame(it, fps) }
            val explicitEnd = marker.explicitEndMs
            val requestedEnd = explicitEnd ?: nextStart ?: audioDurationMs
            require(requestedEnd > marker.startMs) {
                "Timestamp duration is empty near " + marker.raw
            }
            require(requestedEnd <= audioDurationMs) {
                "Timestamp end " + formatMs(requestedEnd) + " is outside the audio duration."
            }

            val end = snapToFrame(requestedEnd, fps).coerceIn(0L, audioDurationMs)
            require(end > start) {
                "Timestamp " + formatMs(marker.startMs) +
                    " is too close for " + fps + " fps rendering."
            }

            if (start > cursor) addBlack(cursor, start)

            val direction = when (visualSceneIndex % 4) {
                0 -> Enums.MotionDirection.LEFT_TO_RIGHT
                1 -> Enums.MotionDirection.RIGHT_TO_LEFT
                2 -> Enums.MotionDirection.TOP_TO_BOTTOM
                else -> Enums.MotionDirection.BOTTOM_TO_TOP
            }

            scenes += ScenePlan(
                id = scenes.size,
                startMs = start,
                endMs = end,
                narrationText = marker.body,
                visualPath = orderedPdfVisuals[index],
                pdfOrdinal = index + 1,
                requestedStartMs = marker.startMs,
                requestedEndMs = explicitEnd,
                isBlackFrame = false,
                reason = "timestamp_sequence_pdf_order_subtle_pan",
                motionDirection = direction
            )
            visualSceneIndex++
            cursor = end

            val nextAbsolute = markers.getOrNull(index + 1)?.startMs?.let { snapToFrame(it, fps) }
            if (explicitEnd != null && nextAbsolute != null && end < nextAbsolute) {
                addBlack(end, nextAbsolute)
                cursor = nextAbsolute
            }
            if (index == markers.lastIndex && explicitEnd != null && end < audioDurationMs) {
                addBlack(end, audioDurationMs)
                cursor = audioDurationMs
            }
        }

        if (scenes.first().startMs > 0L) {
            addBlack(0L, scenes.first().startMs)
        }

        val normalized = scenes.sortedBy { it.startMs }.mapIndexed { idx, scene ->
            scene.copy(id = idx)
        }
        validate(normalized, audioDurationMs, orderedPdfVisuals, frameTolerance)
        return Result(normalized, frameTolerance)
    }

    private fun validate(
        scenes: List<ScenePlan>,
        audioDurationMs: Long,
        orderedPdfVisuals: List<String>,
        frameTolerance: Long
    ) {
        require(scenes.isNotEmpty()) { "No timeline scenes were created." }
        require(scenes.first().startMs == 0L) { "Timeline must begin at zero." }

        for (i in 1 until scenes.size) {
            require(scenes[i].startMs == scenes[i - 1].endMs) {
                "Timeline gap or overlap near scene " + scenes[i].id
            }
        }

        var expectedPdfOrdinal = 1
        scenes.filter { !it.isBlackFrame }.forEach { scene ->
            require(scene.pdfOrdinal == expectedPdfOrdinal) {
                "PDF visual order violated at scene " + scene.id
            }
            require(scene.visualPath == orderedPdfVisuals[expectedPdfOrdinal - 1]) {
                "Visual path does not match PDF order at scene " + scene.id
            }
            require(scene.motionDirection != null) {
                "Visual scene has no motion direction at scene " + scene.id
            }
            expectedPdfOrdinal++
        }

        require(abs(scenes.last().endMs - audioDurationMs) <= frameTolerance + 2L) {
            "Timeline does not match audio duration. Expected " +
                audioDurationMs + " ms, got " + scenes.last().endMs + " ms."
        }
    }

    private fun snapToFrame(ms: Long, fps: Int): Long =
        (ms.toDouble() * fps / 1000.0).roundToLong()
            .let { frames -> (frames.toDouble() * 1000.0 / fps).roundToLong() }

    private fun formatMs(ms: Long): String {
        val minutes = ms / 60000L
        val seconds = (ms / 1000L) % 60L
        val millis = ms % 1000L
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}
