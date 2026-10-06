package com.futurethinking.aivideodirector.data

object Enums {
    enum class AspectRatio(val label: String, val width: Int, val height: Int) {
        WIDE_16_9("16:9", 1920, 1080), VERTICAL_9_16("9:16", 1080, 1920),
        SQUARE_1_1("1:1", 1440, 1440), SOCIAL_4_5("4:5", 1080, 1350)
    }
    enum class Resolution(val label: String, val width: Int, val height: Int) { FHD_1080("1080p",1920,1080) }
    enum class MotionDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT, TOP_TO_BOTTOM, BOTTOM_TO_TOP }
}
data class AppPreferences(
    val aspectRatio: Enums.AspectRatio = Enums.AspectRatio.WIDE_16_9,
    val resolution: Enums.Resolution = Enums.Resolution.FHD_1080,
    val fps: Int = 30, val exportFormat: String = "mp4"
)
data class Project(
    val id: String, var title: String, var script: String = "",
    var timestampPdfPath: String? = null, var timestampPdfName: String? = null,
    var pdfPath: String? = null, var pdfName: String? = null, var pdfPageCount: Int = 0,
    var audioPath: String? = null, val visualPaths: MutableList<String> = mutableListOf(),
    var preferences: AppPreferences = AppPreferences(), var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(), var durationMs: Long = 0L,
    var outputPath: String? = null, var state: String = "DRAFT", var lastError: String? = null,
    var scenePlanJson: String? = null, var progress: Int = 0,
    var isMerged: Boolean = false, var mergeItemsJson: String? = null
)
data class TimestampMarker(val startMs: Long, val explicitEndMs: Long?, val body: String, val raw: String)
data class ScenePlan(
    val id: Int, val startMs: Long, val endMs: Long, val narrationText: String,
    val visualPath: String?, val pdfOrdinal: Int?, val requestedStartMs: Long,
    val requestedEndMs: Long?, val isBlackFrame: Boolean = false,
    val reason: String = "timestamp_sequence_pdf_order",
    val motionDirection: Enums.MotionDirection? = null
) { val durationMs: Long get() = (endMs-startMs).coerceAtLeast(0L) }
