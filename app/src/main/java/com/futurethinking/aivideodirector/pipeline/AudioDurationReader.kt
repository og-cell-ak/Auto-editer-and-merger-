package com.futurethinking.aivideodirector.pipeline

import android.media.MediaMetadataRetriever
import java.io.File

object AudioDurationReader {
    fun durationMs(path: String): Long {
        val file = File(path)
        require(file.exists() && file.length() > 0L) { "Audio file is missing or empty" }
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            }
        }.getOrDefault(0L)
    }
}
