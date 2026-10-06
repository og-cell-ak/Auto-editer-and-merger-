package com.futurethinking.aivideodirector.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.futurethinking.aivideodirector.data.Enums
import java.io.File
import kotlin.math.max
import kotlin.math.min

object SceneFrameFactory {
    fun create(source: File, destination: File, aspect: Enums.AspectRatio) {
        destination.parentFile?.mkdirs()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 1 && bounds.outHeight > 1) {
            "Unable to decode PDF visual " + source.name
        }

        val outW = aspect.width
        val outH = aspect.height
        val sample = calculateSample(bounds.outWidth, bounds.outHeight, 2200)
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: error("Unable to decode PDF visual " + source.name)

        val frame = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(frame)
            canvas.drawColor(Color.BLACK)

            val scale = min(
                outW.toFloat() / bitmap.width.toFloat(),
                outH.toFloat() / bitmap.height.toFloat()
            )
            val drawW = max(1, (bitmap.width * scale).toInt())
            val drawH = max(1, (bitmap.height * scale).toInt())
            val left = (outW - drawW) / 2f
            val top = (outH - drawH) / 2f

            canvas.drawBitmap(
                bitmap,
                null,
                RectF(left, top, left + drawW, top + drawH),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            )

            destination.outputStream().use {
                require(frame.compress(Bitmap.CompressFormat.JPEG, 92, it)) {
                    "Failed to encode render frame"
                }
            }
            require(destination.exists() && destination.length() > 4096L) {
                "Failed to create render frame"
            }
        } finally {
            bitmap.recycle()
            frame.recycle()
        }
    }

    fun createBlack(destination: File, aspect: Enums.AspectRatio) {
        destination.parentFile?.mkdirs()
        val frame = Bitmap.createBitmap(aspect.width, aspect.height, Bitmap.Config.ARGB_8888)
        try {
            frame.eraseColor(Color.BLACK)
            destination.outputStream().use {
                require(frame.compress(Bitmap.CompressFormat.JPEG, 90, it)) {
                    "Failed to create black timeline frame"
                }
            }
        } finally {
            frame.recycle()
        }
    }

    private fun calculateSample(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (max(width, height) / sample > maxDim) sample *= 2
        return max(1, sample)
    }
}
