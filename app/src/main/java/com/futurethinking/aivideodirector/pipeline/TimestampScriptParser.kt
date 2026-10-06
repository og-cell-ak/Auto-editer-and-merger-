package com.futurethinking.aivideodirector.pipeline

import com.futurethinking.aivideodirector.data.TimestampMarker

class TimestampScriptParser {
    private val markerRegex = Regex(
        """^\s*(?:\[|\()?\s*(\d{1,4}):(\d{2})(?::(\d{2}))?(?:[.,](\d{1,3}))?\s*(?:\]|\))?\s*(?:[-–—]\s*(?:\[|\()?\s*(\d{1,4}):(\d{2})(?::(\d{2}))?(?:[.,](\d{1,3}))?\s*(?:\]|\))?\s*)?(?:\|\s*|:\s*)?(.*)$"""
    )

    fun parse(script: String): List<TimestampMarker> {
        require(script.isNotBlank()) { "Script is empty." }
        val markers = script.lines().mapNotNull { line ->
            val match = markerRegex.matchEntire(line) ?: return@mapNotNull null
            val start = parseTime(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
                ?: throw IllegalArgumentException("Invalid timestamp: " + line.trim())
            val end = if (match.groupValues[5].isNotBlank()) {
                parseTime(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
                    ?: throw IllegalArgumentException("Invalid ending timestamp: " + line.trim())
            } else null
            TimestampMarker(start, end, match.groupValues[9].trim(), line.trim())
        }
        require(markers.isNotEmpty()) {
            "No valid timestamps found. Use one timestamped line per PDF visual, for example [00:12.500] Visual 1."
        }
        for (i in 1 until markers.size) {
            require(markers[i].startMs > markers[i - 1].startMs) {
                "Timestamps must be strictly increasing near " + markers[i].raw
            }
            val previousEnd = markers[i - 1].explicitEndMs
            if (previousEnd != null) {
                require(previousEnd <= markers[i].startMs) {
                    "Timestamp ranges overlap near " + markers[i - 1].raw
                }
            }
        }
        markers.forEach { marker ->
            marker.explicitEndMs?.let { end ->
                require(end > marker.startMs) {
                    "Timestamp end must be after start near " + marker.raw
                }
            }
        }
        return markers
    }

    private fun parseTime(firstText: String, secondText: String, thirdText: String, fractionText: String): Long? {
        val first = firstText.toLongOrNull() ?: return null
        val second = secondText.toLongOrNull() ?: return null
        if (second !in 0..59) return null
        val third = thirdText.takeIf { it.isNotBlank() }?.toLongOrNull()
        if (third != null && third !in 0..59) return null
        val baseSeconds = if (third == null) {
            first * 60L + second
        } else {
            first * 3600L + second * 60L + third
        }
        val millis = fractionText.takeIf { it.isNotBlank() }?.let {
            it.padEnd(3, '0').take(3).toLong()
        } ?: 0L
        return baseSeconds * 1000L + millis
    }
}
