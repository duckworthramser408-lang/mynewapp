package lab.g23

import org.json.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisDataTest {
    private fun document() =
        JSONObject(
            """{"render_pipeline_version":"1.0.0","parameters":{"exposure_ev":0,"geometry":{"crop":null,"rotation_degrees":0},"tone_curves":{"rgb":[[0,0],[1,1]]}}}"""
        )

    @Test
    fun pairedSnapshotsRetainOriginalCurvesAndGeometry() {
        val original = document()
        val edited = document()
        edited
            .getJSONObject("parameters")
            .put("exposure_ev", 1.2)
            .getJSONObject("geometry")
            .put("crop", JSONArray("[0.1,0,0.9,1]"))
        val statistics = ImageStatistics.compute(2, 2) { _, _ -> 0xff556677.toInt() }
        val data =
            AnalysisData.create(
                "G23_test",
                JSONObject().put("iso", 100),
                original,
                edited,
                statistics,
                statistics,
            )
        AnalysisData.validate(JSONObject(data.toString()), "G23_test", original, edited)
        assertEquals(
            0.0,
            data
                .getJSONObject("before")
                .getJSONObject("parameters")
                .getJSONObject("parameters")
                .getDouble("exposure_ev"),
            0.0,
        )
        edited.getJSONObject("parameters").put("exposure_ev", 2)
        assertEquals(
            1.2,
            data
                .getJSONObject("after")
                .getJSONObject("parameters")
                .getJSONObject("parameters")
                .getDouble("exposure_ev"),
            0.0,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisData.validate(data, "G23_other", original, edited)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisData.validate(data, "G23_test", original, edited)
        }
    }

    @Test
    fun reorderedObjectsCompareSemantically() {
        assertEquals(
            canonicalJson(JSONObject("""{"b":[1,2],"a":0.0}""")),
            canonicalJson(JSONObject("""{"a":0,"b":[1.0,2.0]}""")),
        )
        assertNotEquals(canonicalJson(JSONArray("[1,2]")), canonicalJson(JSONArray("[2,1]")))
    }

    @Test
    fun statisticsAreBoundedAndHistogramsConserveSamples() {
        var calls = 0
        val stats =
            ImageStatistics.compute(6000, 4000) { _, _ ->
                calls++
                0xff804020.toInt()
            }
        assertTrue(calls <= 256 * 256)
        assertEquals(calls, stats.getInt("sample_count"))
        assertEquals(128.0, stats.getDouble("mean_red_0_255"), 0.0)
        assertEquals(64.0, stats.getDouble("mean_green_0_255"), 0.0)
        assertEquals(32.0, stats.getDouble("mean_blue_0_255"), 0.0)
        val histogram = stats.getJSONArray("luma_histogram_64")
        assertEquals(calls, (0 until histogram.length()).sumOf { histogram.getInt(it) })
    }

    @Test
    fun blackWhiteClippingAndGreySaturation() {
        val s =
            ImageStatistics.compute(2, 1) { x, _ ->
                if (x == 0) 0xff000000.toInt() else 0xffffffff.toInt()
            }
        assertEquals(.5, s.getDouble("dark_fraction"), 0.0)
        assertEquals(.5, s.getDouble("highlight_fraction"), 0.0)
        assertEquals(0.0, s.getDouble("mean_saturation_0_1"), 0.0)
    }
}
