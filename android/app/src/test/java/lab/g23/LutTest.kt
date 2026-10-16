package lab.g23

import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowSystemClock

class CubeParserTest {
    private fun identity() =
        "\uFEFF# comment\nTITLE \"test\"\nLUT_3D_SIZE 2\n" +
            (0..1)
                .flatMap { b -> (0..1).flatMap { g -> (0..1).map { r -> "$r $g $b" } } }
                .joinToString("\n")

    @Test
    fun parsesCubeOrderBomCommentsAndNeutralDomain() {
        val cube = CubeLut.parse(identity().reader())
        assertEquals(2, cube.size)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), cube.values.copyOfRange(3, 6), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), cube.minimum, 0f)
    }

    @Test
    fun rejectsTruncatedNonfiniteDuplicateAnd1dData() {
        for (text in
            listOf(
                "LUT_3D_SIZE 2\n0 0 0",
                identity() + "\n1 1 1",
                identity().replace("1 1 1", "NaN 1 1"),
                identity().replace("LUT_3D_SIZE 2", "LUT_1D_SIZE 2"),
                identity().replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 2\nLUT_3D_SIZE 2"),
            )) {
            assertThrows(Exception::class.java) { CubeLut.parse(text.reader()) }
        }
    }

    @Test
    fun cyclicWheelSnapsToWholeDetentsAndBoundsFling() {
        val wheel = FilmWheelState(15, 0)
        wheel.position = -.8f
        assertEquals(14, wheel.selected)
        assertEquals(-1f, wheel.target(), 0f)
        wheel.position = 14.9f
        assertEquals(0, wheel.selected)
        assertEquals(17f, wheel.target(100f), 0f)
        assertEquals(12f, wheel.target(-100f), 0f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LutViewTest {
    private fun context() =
        ApplicationProvider.getApplicationContext<Context>().also {
            it.setTheme(android.R.style.Theme_Material_NoActionBar)
        }

    private fun layout(v: View, w: Int = 360, h: Int = 600) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
    }

    @Test
    fun all15AssetsParseAndPhotoBindingDoesNotFollowGlobalSelection() {
        val c = context()
        val library = LutLibrary(c)
        assertEquals(15, library.items.size)
        library.items.forEach { item ->
            val bytes = c.assets.open("luts/${item.file}").use { it.readBytes() }
            val hash =
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            assertEquals(item.sha256, hash)
            assertEquals(33, CubeLut.parse(bytes.toString(Charsets.UTF_8).reader()).size)
        }
        val folder = File(c.filesDir, "lut-binding").apply { mkdirs() }
        library.attach(folder, library.items[0])
        library.select(library.items[11])
        assertEquals(library.items[0].id, library.look(folder)!!.getString("id"))
        val bound = library.forPhoto(folder)!!
        assertEquals(33, bound.size)
        val expected = CubeLut.parse(c.assets.open("luts/${library.items[0].file}").reader())
        assertArrayEquals(expected.values, bound.values, 0f)
        assertEquals(library.items[11].id, library.selected().id)
        File(folder, "look.cube").appendText(" ")
        assertThrows(IllegalArgumentException::class.java) { library.forPhoto(folder) }
        folder.deleteRecursively()
    }

    @Test
    fun wheelDragChangesNameRequestsHapticAndCommitsAlignedItem() {
        val c = context()
        val library = LutLibrary(c)
        val wheel = FilmWheelView(c, library.items)
        layout(wheel)
        var changed = ""
        wheel.onSelectionChanged = { changed = it.name }
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        fun event(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, y, 0)
            wheel.onTouchEvent(e)
            e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 250f, 300f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        event(MotionEvent.ACTION_MOVE, 250f, 185f)
        assertEquals(library.items[1].name, changed)
        assertEquals(
            HapticFeedbackConstants.SEGMENT_TICK,
            Shadows.shadowOf(wheel).lastHapticFeedbackPerformed(),
        )
        val selected = wheel.finishSelection()
        assertEquals(1, wheel.selectedIndex)
        assertEquals(library.items[1].id, selected.id)
        assertEquals(1f, wheel.state.position, 0f)
        event(MotionEvent.ACTION_CANCEL, 250f, 185f)
    }

    @Test
    fun albumContainsOnlyPhotoCellsAndUsesThreeColumns() {
        val c = context()
        val folder = File(c.filesDir, "gallery-photo").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        Imaging.jpeg(bitmap, File(folder, "original.jpg"))
        bitmap.recycle()
        val gallery = PhotoGallery(c, java.util.concurrent.Executor { it.run() }, {}, {})
        gallery.submit(listOf(CameraPhoto(folder, "ready", "4:3", 0)))
        layout(gallery, 360, 600)
        assertEquals(1, gallery.adapter!!.itemCount)
        assertEquals(
            3,
            (gallery.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount,
        )
        assertTrue(gallery.childCount > 0)
        for (i in 0 until gallery.childCount) {
            val cell = gallery.getChildAt(i) as android.widget.FrameLayout
            assertEquals(2, cell.childCount)
            assertTrue(cell.getChildAt(0) is ImageView)
            assertTrue(cell.getChildAt(1) is DevelopingView)
        }
        folder.deleteRecursively()
    }

    @Test
    fun rendersFilmPickerAndReplacesCameraBrandWithLutButton() {
        val c = context()
        val library = LutLibrary(c)
        val root = LabUi.column(c)
        val wheel = FilmWheelView(c, library.items, 11)
        root.setBackgroundColor(LabUi.background)
        root.setPadding(16, 0, 16, 16)
        root.addView(LabUi.header(c, "胶卷色调", "你的 15 款 LUT", {}))
        root.addView(wheel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            LabUi.label(c, "上下拨动 · 自动吸附 · 箭头指向当前选择").apply {
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 16)
            }
        )
        root.addView(LabUi.button(c, "使用并返回拍摄", true) {})
        layout(root, 360, 760)
        val bitmap = Bitmap.createBitmap(360, 760, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val output = File("build/reports/ui/lut-picker.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var clicks = 0
        val camera = CameraLayout(c, {}, {}, {}, {}, {}, {}, {}, onLut = { clicks++ })
        camera.setLutName("理光GR")
        assertTrue(camera.lutButton.performClick())
        assertEquals(1, clicks)
        assertEquals("理光GR", camera.lutName.text.toString())
    }
}
