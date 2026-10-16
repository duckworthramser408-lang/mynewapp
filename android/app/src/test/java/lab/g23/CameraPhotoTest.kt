package lab.g23

import android.content.Context
import android.graphics.*
import android.view.*
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraPhotoTest {
    private lateinit var context: Context
    private lateinit var store: PhotoStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Keep real WorkManager persistence, but hold native rendering for explicit lifecycle
        // tests.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setTaskExecutor(SynchronousExecutor()).setExecutor {}.build(),
        )
        // Each Robolectric application has a distinct filesDir; simulate a fresh process here.
        store = PhotoStore(context)
        store.root.deleteRecursively()
        store.root.mkdirs()
    }

    private fun capture(index: Int = 0, ratio: String = "4:3"): File {
        val dir = store.newCapture(store.looks.items[index], ratio)
        val bitmap =
            Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        Imaging.jpeg(bitmap, File(dir, "original.jpg"))
        bitmap.recycle()
        store.captured(dir.name)
        return dir
    }

    private fun rendered(dir: File, lut: String, ratio: String) {
        assertTrue(lut.startsWith("cube_"))
        assertTrue(ratio.isNotBlank())
        File(dir, "film.jpg.tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "thumbnail_after.jpg.tmp").writeBytes(byteArrayOf(4, 5))
    }

    @Test
    fun newPhotosFreezeTheirOwnLutAndNeverWriteLabData() {
        val first = capture(0, "1:1")
        val second = capture(1, "3:2")
        val one = JSONObject(File(first, "photo.json").readText())
        val two = JSONObject(File(second, "photo.json").readText())
        assertEquals(store.looks.items[0].id, one.getString("lut"))
        assertEquals(store.looks.items[1].id, two.getString("lut"))
        assertEquals("1:1", one.getString("ratio"))
        assertEquals("3:2", two.getString("ratio"))
        for (dir in listOf(first, second)) assertEquals(
            setOf("photo.json", "original.jpg"),
            dir.list()!!.toSet(),
        )
        assertEquals(2, store.photos().count { it.developing })
    }

    @Test
    fun successfulDevelopCommitsFilmAndThumbnailAndPreservesOriginal() {
        val dir = capture()
        val original = digest(File(dir, "original.jpg"))
        store.develop(dir.name, render = ::rendered)
        assertEquals("ready", store.photo(dir.name)!!.state)
        assertEquals("film.jpg", store.photo(dir.name)!!.film!!.name)
        assertEquals(original, digest(File(dir, "original.jpg")))
        assertEquals(
            setOf("original.jpg", "photo.json", "film.jpg", "thumbnail_after.jpg"),
            dir.list()!!.toSet(),
        )
        store.develop(dir.name, render = { _, _, _ -> fail("A ready photo was rendered twice") })
    }

    @Test
    fun anotherCaptureCanBeRegisteredWhileDeveloping() {
        val dir = capture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            store.develop(
                dir.name,
                render = { d, l, r ->
                    started.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    rendered(d, l, r)
                },
            )
        }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            val next = capture(2)
            assertEquals("queued", store.photo(next.name)!!.state)
            assertEquals("developing", store.photo(dir.name)!!.state)
        } finally {
            release.countDown()
            thread.join(10000)
        }
        assertFalse(thread.isAlive)
    }

    @Test
    fun deletingDuringDevelopNeverResurrectsThePhoto() {
        val dir = capture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            store.develop(
                dir.name,
                render = { d, l, r ->
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    rendered(d, l, r)
                },
            )
        }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            store.delete(dir.name)
            assertNull(store.photo(dir.name))
        } finally {
            release.countDown()
            thread.join(10000)
        }
        assertFalse(thread.isAlive)
        assertFalse(dir.exists())
        assertTrue(store.photos().isEmpty())
    }

    @Test
    fun failedDevelopCanBeRetriedAndDoesNotBlockNextPhoto() {
        val first = capture()
        val second = capture(1)
        store.develop(
            first.name,
            render = { _, _, _ -> throw IllegalStateException("test failure") },
        )
        assertEquals("failed", store.photo(first.name)!!.state)
        assertTrue(File(first, "original.jpg").isFile)
        store.develop(second.name, render = ::rendered)
        assertEquals("ready", store.photo(second.name)!!.state)
        store.retry(first.name)
        assertEquals("queued", store.photo(first.name)!!.state)
        store.develop(first.name, render = ::rendered)
        assertEquals("ready", store.photo(first.name)!!.state)
    }

    @Test
    fun recoverFindsInterruptedJpegAndDiscardsEmptyCapture() {
        val dir = capture()
        val info =
            JSONObject(File(dir, "photo.json").readText())
                .put("state", "capturing")
                .put("created", 0)
        atomicText(File(dir, "photo.json"), info.toString())
        val empty = store.newCapture(store.looks.items[0], "4:3")
        val data = JSONObject(File(empty, "photo.json").readText()).put("created", 0)
        atomicText(File(empty, "photo.json"), data.toString())
        PhotoStore(context).recover()
        assertEquals("queued", store.photo(dir.name)!!.state)
        assertFalse(empty.exists())
    }

    @Test
    fun restartRecoversDeliveredJpegImmediatelyWithoutAgeDelay() {
        val dir = capture()
        val info = JSONObject(File(dir, "photo.json").readText()).put("state", "capturing")
        atomicText(File(dir, "photo.json"), info.toString())
        PhotoStore(context).recover()
        assertEquals("queued", store.photo(dir.name)!!.state)
    }

    @Test
    fun recoveryDoesNotTouchAnActiveCaptureInThisProcess() {
        val dir = store.newCapture(store.looks.items[0], "4:3")
        File(dir, "original.jpg").writeBytes(byteArrayOf(1, 2, 3))
        store.recover()
        assertEquals("capturing", JSONObject(File(dir, "photo.json").readText()).getString("state"))
        assertTrue(dir.exists())
        assertNull(store.photo(dir.name))
        store.captured(dir.name)
        assertEquals("queued", store.photo(dir.name)!!.state)
    }

    @Test
    fun failedCameraCaptureKeepsDeliveredBytesAndRemovesOnlyEmptyCapture() {
        val dir = capture()
        store.captureFailed(dir.name)
        assertTrue(File(dir, "original.jpg").isFile)
        assertEquals("failed", store.photo(dir.name)!!.state)
        val empty = store.newCapture(store.looks.items[0], "4:3")
        store.captureFailed(empty.name)
        assertFalse(empty.exists())
    }

    @Test
    fun workerStopLeavesDurableRecipeForResumption() {
        val dir = capture()
        store.develop(
            dir.name,
            stopped = { true },
            render = { _, _, _ -> fail("Stopped worker must not render") },
        )
        assertEquals("developing", store.photo(dir.name)!!.state)
        store.recover()
        store.develop(dir.name, render = ::rendered)
        assertEquals("ready", store.photo(dir.name)!!.state)
    }

    @Test
    fun memoryFailurePreservesOriginalAndQueueCanContinue() {
        val first = capture()
        val second = capture(1)
        store.develop(
            first.name,
            render = { _, _, _ -> throw OutOfMemoryError("test allocation failure") },
        )
        assertEquals("failed", store.photo(first.name)!!.state)
        assertTrue(File(first, "original.jpg").isFile)
        store.develop(second.name, render = ::rendered)
        assertEquals("ready", store.photo(second.name)!!.state)
    }

    @Test
    fun legacyPhotosRemainViewableAndDeletable() {
        val dir = File(store.root, "G23_legacy").apply { mkdir() }
        File(dir, "original.jpg").writeBytes(byteArrayOf(1))
        File(dir, "edited.png").writeBytes(byteArrayOf(2))
        assertEquals("edited.png", store.photos().single().film!!.name)
        store.delete(dir.name)
        assertTrue(store.photos().isEmpty())
    }

    @Test
    fun bundledLutTableIsReusedAcrossPhotos() {
        val id = store.looks.items[11].id
        assertSame(store.looks.table(id), store.looks.table(id))
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
    }

    @Test
    fun dividerRevealsPixelsWithoutPagingAndCanShowBothExtremes() {
        var pages = 0
        val view = FullPhotoView(context) { pages += it }
        val before =
            Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val after =
            Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        view.replace(before, after)
        layout(view, 360, 480)
        val bitmap = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertEquals(Color.RED, bitmap.getPixel(100, 350))
        assertEquals(Color.BLUE, bitmap.getPixel(260, 350))
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(0, 10, action, x, 350f, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 180f)
        touch(MotionEvent.ACTION_MOVE, 300f)
        touch(MotionEvent.ACTION_UP, 300f)
        assertEquals(300f / 360, view.split, .001f)
        assertEquals(0, pages)
        view.draw(Canvas(bitmap))
        assertEquals(Color.RED, bitmap.getPixel(260, 350))
        view.split = 0f
        view.draw(Canvas(bitmap))
        assertEquals(Color.BLUE, bitmap.getPixel(100, 350))
        view.split = 1f
        view.draw(Canvas(bitmap))
        assertEquals(Color.RED, bitmap.getPixel(260, 350))
        view.split = .5f
        val out = File("build/reports/ui/photo-comparison.png")
        out.parentFile!!.mkdirs()
        view.draw(Canvas(bitmap))
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        view.replace(null, null)
        bitmap.recycle()
    }

    @Test
    fun pendingAlbumContainsFilmPlaceholderAndSupportsDelete() {
        val dir = capture()
        var deleted = ""
        val gallery = PhotoGallery(context, { it.run() }, {}, { deleted = it.id })
        gallery.submit(store.photos())
        layout(gallery, 360, 600)
        assertEquals(1, gallery.adapter!!.itemCount)
        val cell = gallery.getChildAt(0) as android.widget.FrameLayout
        val placeholder = cell.getChildAt(1) as DevelopingView
        assertEquals(View.VISIBLE, placeholder.visibility)
        assertEquals("冲洗中", placeholder.label)
        cell.performLongClick()
        assertEquals(dir.name, deleted)
        val bitmap = Bitmap.createBitmap(360, 600, Bitmap.Config.ARGB_8888)
        gallery.draw(Canvas(bitmap))
        val out = File("build/reports/ui/album-developing.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun originalAndFilmPreviewUseIdenticalCrop() {
        val source = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        val square = FilmProcessor.crop(source, "1:1")
        assertEquals(120, square.width)
        assertEquals(120, square.height)
        square.recycle()
        source.recycle()
    }
}
