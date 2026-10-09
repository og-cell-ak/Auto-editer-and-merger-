package com.futurethinking.aivideodirector

import com.futurethinking.aivideodirector.data.AppPreferences
import com.futurethinking.aivideodirector.pipeline.TimestampScriptParser
import com.futurethinking.aivideodirector.pipeline.TimestampScenePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampPipelineTest {
    @Test
    fun parsesBracketedAndRangedTimestamps() {
        val script = """
            [00:00.000] First visual
            [00:02.500] Second visual
            00:05-00:07 Third visual
        """.trimIndent()

        val markers = TimestampScriptParser().parse(script)

        assertEquals(3, markers.size)
        assertEquals(0L, markers[0].startMs)
        assertEquals(2500L, markers[1].startMs)
        assertEquals(5000L, markers[2].startMs)
        assertNull(markers[0].explicitEndMs)
        assertNull(markers[1].explicitEndMs)
        assertEquals(7000L, markers[2].explicitEndMs)
    }

    @Test
    fun parserSupportsHourMinuteSecondForm() {
        val markers = TimestampScriptParser().parse("[01:02:03.250] Visual")
        assertEquals(3_723_250L, markers.single().startMs)
    }

    @Test
    fun plannerMapsTimestampOrderDirectlyToPdfOrder() {
        val markers = TimestampScriptParser().parse(
            """
            [00:00] Visual one
            [00:02.500] Visual two
            [00:06] Visual three
            """.trimIndent()
        )

        val pdf = listOf(
            "/tmp/pdf-page-0001.jpg",
            "/tmp/pdf-page-0002.jpg",
            "/tmp/pdf-page-0003.jpg"
        )

        val result = TimestampScenePlanner().plan(
            markers = markers,
            orderedPdfVisuals = pdf,
            audioDurationMs = 10_000L,
            preferences = AppPreferences()
        )

        val visible = result.scenes.filter { !it.isBlackFrame }
        assertEquals(3, visible.size)
        assertEquals(pdf[0], visible[0].visualPath)
        assertEquals(pdf[1], visible[1].visualPath)
        assertEquals(pdf[2], visible[2].visualPath)
        assertEquals(0L, visible[0].startMs)
        assertEquals(2500L, visible[1].startMs)
        assertEquals(6000L, visible[2].startMs)
        assertEquals(10_000L, result.scenes.last().endMs)
        assertEquals(2500L, visible[0].endMs - visible[0].startMs)
        assertEquals(3500L, visible[1].endMs - visible[1].startMs)
        assertEquals(4000L, visible[2].endMs - visible[2].startMs)
        assertTrue(visible.all { !it.isBlackFrame })
    }

    @Test
    fun timestampIntervalsDrivePanelDurationsWithoutBlankGaps() {
        val markers = TimestampScriptParser().parse(
            """
            [00:01]-[00:02] First
            [00:03]-[00:04] Second
            """.trimIndent()
        )
        val pdf = listOf(
            "/tmp/pdf-page-0001.jpg",
            "/tmp/pdf-page-0002.jpg"
        )

        val scenes = TimestampScenePlanner().plan(
            markers,
            pdf,
            5_000L,
            AppPreferences()
        ).scenes

        assertEquals(2, scenes.size)
        assertTrue(scenes.all { !it.isBlackFrame })
        // Rebase the first timestamp to zero but preserve the 2-second interval
        // between timestamp starts. Keep the final panel on screen to audio end.
        assertEquals(0L, scenes[0].startMs)
        assertEquals(2000L, scenes[0].endMs)
        assertEquals(2000L, scenes[1].startMs)
        assertEquals(5000L, scenes[1].endMs)
    }

    @Test
    fun timestampStartIntervalsOfThreeAndTwentySecondsArePreserved() {
        val markers = TimestampScriptParser().parse(
            """
            [00:03] First
            [00:06] Second
            [00:26] Third
            """.trimIndent()
        )
        val scenes = TimestampScenePlanner().plan(
            markers,
            listOf("/tmp/one.jpg", "/tmp/two.jpg", "/tmp/three.jpg"),
            30_000L,
            AppPreferences()
        ).scenes

        assertEquals(3, scenes.size)
        assertEquals(0L, scenes[0].startMs)
        assertEquals(3000L, scenes[0].endMs)
        assertEquals(3000L, scenes[1].startMs)
        assertEquals(23_000L, scenes[1].endMs)
        assertEquals(23_000L, scenes[2].startMs)
        assertEquals(30_000L, scenes[2].endMs)
        assertTrue(scenes.all { !it.isBlackFrame })
    }
}
