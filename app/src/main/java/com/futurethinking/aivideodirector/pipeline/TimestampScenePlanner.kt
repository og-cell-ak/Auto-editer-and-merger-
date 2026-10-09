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
        val frameToleranceMs: Long,
        val diagnostics: List<String> = emptyList()
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
        val diagnostics = mutableListOf<String>()
        var visualSceneIndex = 0
        // The first timestamp establishes the script origin. Align its panel
        // to video zero and subtract this origin from every later timestamp so
        // the original intervals (3 seconds, 20 seconds, etc.) stay unchanged.
        val timestampOriginMs = markers.first().startMs

        markers.forEachIndexed { index, marker ->
            val relativeStartMs = (marker.startMs - timestampOriginMs).coerceAtLeast(0L)
            if (relativeStartMs >= audioDurationMs) {
                val delta = relativeStartMs - audioDurationMs
                if (delta <= MAX_MISMATCH_MS && index == markers.lastIndex) {
                    diagnostics += "Timestamp " + formatMs(marker.startMs) + " is " +
                        formatMs(delta) + " beyond the usable timeline. Ignored the final out-of-range marker."
                    return@forEachIndexed
                }
                require(delta <= MAX_MISMATCH_MS) {
                    "Timestamp " + formatMs(marker.startMs) + " is " +
                        formatMs(delta) + " beyond the usable timeline. This exceeds the allowed 5 second correction window."
                }
                require(index == markers.lastIndex) {
                    "A timestamp after the usable audio timeline cannot be rendered."
                }
                return@forEachIndexed
            }

            val start = if (index == 0) 0L
                else snapToFrame(relativeStartMs, fps).coerceIn(0L, audioDurationMs)
            require(index == 0 || abs(start - relativeStartMs) <= frameTolerance + 1L) {
                "Timestamp quantization exceeded frame tolerance at " + formatMs(marker.startMs) + "."
            }

            // Timestamp starts define visual changes. The current panel remains
            // visible until the next timestamp; the final panel remains visible
            // to the audio end. Never inject black placeholders between panels.
            val nextRelativeStart = markers.getOrNull(index + 1)?.startMs
                ?.minus(timestampOriginMs)
                ?.let { snapToFrame(it, fps) }
            val requestedEnd = nextRelativeStart ?: audioDurationMs
            if (requestedEnd > audioDurationMs) {
                val delta = requestedEnd - audioDurationMs
                require(delta <= MAX_MISMATCH_MS) {
                    "Timestamp end " + formatMs(requestedEnd) + " is " + formatMs(delta) +
                        " beyond audio. This exceeds the allowed 5 second correction window."
                }
                diagnostics += "Timestamp " + formatMs(requestedEnd) + " exceeds audio by " +
                    formatMs(delta) + ". Corrected to audio end " + formatMs(audioDurationMs) + "."
            }

            val end = snapToFrame(requestedEnd, fps).coerceIn(0L, audioDurationMs)
            require(end > start) {
                "Timestamp " + formatMs(marker.startMs) +
                    " leaves no visible duration at " + fps + " fps rendering."
            }

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
                requestedEndMs = marker.explicitEndMs,
                isBlackFrame = false,
                reason = "timestamp_interval_panel_scroll",
                motionDirection = direction
            )
            visualSceneIndex++
        }

        val normalized = scenes.sortedBy { it.startMs }.mapIndexed { idx, scene ->
            scene.copy(id = idx)
        }
        validate(normalized, audioDurationMs, orderedPdfVisuals, frameTolerance)
        return Result(normalized, frameTolerance, diagnostics.distinct())
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

    companion object { const val MAX_MISMATCH_MS = 5000L }

    private fun formatMs(ms: Long): String {
        val minutes = ms / 60000L
        val seconds = (ms / 1000L) % 60L
        val millis = ms % 1000L
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}
