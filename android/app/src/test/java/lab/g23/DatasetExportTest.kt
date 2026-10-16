package lab.g23

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DatasetExportTest {
    @Test
    fun archiveCarriesImmutableBaselinePairedCsvAndVerifiedReferences() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val store = SampleStore(c)
        val folder = store.newCapture()
        val source = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff805030.toInt())
        Imaging.jpeg(source, File(folder, "original.jpg"))
        store.initialize(folder, "test")
        val before = store.originalParameters(folder)
        val after = store.defaults()
        after.getJSONObject("parameters").put("exposure_ev", 1.2)
        atomicText(File(folder, "parameters.json"), after.toString())
        File(folder, "edited.png").outputStream().use {
            source.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Imaging.jpeg(source, File(folder, "thumbnail_before.jpg"))
        Imaging.jpeg(source, File(folder, "thumbnail_after.jpg"))
        source.recycle()
        val stats = ImageStatistics.compute(8, 6) { _, _ -> 0xff805030.toInt() }
        val comparison =
            AnalysisData.create(
                folder.name,
                store.metadata(folder).getJSONObject("capture"),
                before,
                after,
                stats,
                stats,
            )
        atomicText(File(folder, "comparison.json"), comparison.toString())
        val hashes = JSONObject()
        listOf(
                "original.jpg",
                "edited.png",
                "thumbnail_before.jpg",
                "thumbnail_after.jpg",
                "parameters.json",
                "original_parameters.json",
                "comparison.json",
            )
            .forEach { hashes.put(it, digest(File(folder, it))) }
        atomicText(
            File(folder, "metadata.json"),
            store.metadata(folder).put("sha256", hashes).toString(),
        )
        assertTrue(store.isRendered(folder, after))
        val originalHash = digest(File(folder, "original.jpg"))
        val output = DatasetExport.export(store, listOf(folder), false, false) { _, _ -> }
        ZipFile(output).use { zip ->
            val prefix = "G23_Dataset_Export/"
            val manifest =
                JSONObject(
                        zip.getInputStream(zip.getEntry(prefix + "manifest.json"))
                            .bufferedReader()
                            .readText()
                    )
                    .getJSONObject("sha256")
            val row =
                JSONObject(
                    zip.getInputStream(zip.getEntry(prefix + "dataset.jsonl"))
                        .bufferedReader()
                        .readText()
                        .trim()
                )
            for (key in
                listOf(
                    "original",
                    "edited",
                    "parameters",
                    "metadata",
                    "original_parameters",
                    "comparison",
                )) assertTrue(manifest.has(row.getString(key)))
            val csv =
                zip.getInputStream(zip.getEntry(prefix + "reports/before_after_parameters.csv"))
                    .bufferedReader()
                    .readText()
            assertTrue(csv.contains("\"exposure_ev\",\"曝光\",\"0\",\"1.2\""))
            assertTrue(csv.contains("tone_curves.rgb"))
            assertTrue(csv.contains("geometry.crop"))
            assertFalse(zip.entries().asSequence().any { it.name.contains("history.json") })
        }
        // Export infrastructure must preserve the look stage along with legacy snapshots.
        val film = store.looks.items.first { it.name == "理光GR" }
        store.looks.attach(folder, film)
        comparison.put("look", store.look(folder))
        atomicText(File(folder, "comparison.json"), comparison.toString())
        listOf("look.json", "look.cube", "comparison.json").forEach {
            hashes.put(it, digest(File(folder, it)))
        }
        atomicText(
            File(folder, "metadata.json"),
            store.metadata(folder).put("sha256", hashes).toString(),
        )
        assertTrue(store.isRendered(folder, after))
        val withLook = DatasetExport.export(store, listOf(folder), false, false) { _, _ -> }
        ZipFile(withLook).use { zip ->
            val prefix = "G23_Dataset_Export/"
            val row =
                JSONObject(
                    zip.getInputStream(zip.getEntry(prefix + "dataset.jsonl"))
                        .bufferedReader()
                        .readText()
                        .trim()
                )
            assertEquals("samples/${folder.name}/look.cube", row.getString("lut"))
            assertNotNull(zip.getEntry(prefix + row.getString("lut")))
            assertNotNull(zip.getEntry(prefix + row.getString("look")))
        }
        withLook.delete()
        assertEquals(originalHash, digest(File(folder, "original.jpg")))
        File(folder, "comparison.json").appendText(" ")
        assertFalse(store.isRendered(folder, after))
        assertThrows(IllegalArgumentException::class.java) {
            DatasetExport.export(store, listOf(folder), false, false) { _, _ -> }
        }
        folder.deleteRecursively()
        output.delete()
    }
}
