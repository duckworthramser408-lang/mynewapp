package lab.g23

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
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
class BeautyIntegrationTest {
    private lateinit var context: Context
    private lateinit var store: PhotoStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("natural_beauty", 0).edit().clear().commit()
        context.getSharedPreferences("camera", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setTaskExecutor(SynchronousExecutor()).setExecutor {}.build(),
        )
        store = PhotoStore(context)
        store.root.deleteRecursively()
        store.root.mkdirs()
    }

    private fun capture(): File {
        val folder = store.newCapture(store.looks.items[0], "4:3")
        Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).also { b ->
            b.eraseColor(Color.rgb(160, 120, 100))
            Imaging.jpeg(b, File(folder, "original.jpg"))
            b.recycle()
        }
        store.captured(folder.name)
        return folder
    }

    private fun rendered(folder: File, lut: String, ratio: String) {
        assertTrue(lut.isNotEmpty() && ratio.isNotEmpty())
        File(folder, "film.jpg.tmp").writeBytes(byteArrayOf(1, 2))
        File(folder, "thumbnail_after.jpg.tmp").writeBytes(byteArrayOf(3, 4))
    }

    @Test
    fun defaultsAreLightAndIndependentSettingsSurviveRestart() {
        val initial = BeautySettings.read(context)
        assertTrue(initial.enabled)
        assertEquals(.20f, initial.symmetry, 0f)
        assertEquals(.25f, initial.folds, 0f)
        assertEquals(.10f, initial.smallFace, 0f)
        val changed = initial.copy(enabled = false, folds = .63f)
        changed.save(context)
        assertEquals(changed, BeautySettings.read(context))
        assertEquals(changed, BeautySettings.snapshot(changed.json()))
        assertFalse(BeautySettings.snapshot(null).enabled)
    }

    @Test
    fun malformedAndUnknownRecipesAreRejected() {
        for (doc in
            listOf(
                BeautySettings().json().put("version", 2),
                BeautySettings().json().put("folds", -1),
                BeautySettings().json().put("small_face", "NaN"),
            )) {
            assertThrows(Exception::class.java) { BeautySettings.snapshot(doc) }
        }
    }

    @Test
    fun captureFreezesBeautyAndSameLutStrengthBeforePreferencesChange() {
        val requested = BeautySettings(symmetry = .12f, folds = .37f, smallFace = .04f)
        requested.save(context)
        context.getSharedPreferences("camera", 0).edit().putFloat("lut_intensity", .42f).commit()
        val folder = capture()
        BeautySettings(enabled = false).save(context)
        context.getSharedPreferences("camera", 0).edit().putFloat("lut_intensity", .9f).commit()
        val recipe = JSONObject(File(folder, "photo.json").readText())
        assertEquals(requested, BeautySettings.snapshot(recipe.getJSONObject("beauty")))
        assertEquals(.42, recipe.getDouble("lut_intensity"), .0001)
    }

    @Test
    fun redevelopAlwaysKeepsUntouchedOriginalAndUsesNewSnapshot() {
        val folder = capture()
        val original = digest(File(folder, "original.jpg"))
        store.develop(folder.name, render = ::rendered)
        val requested = BeautySettings(folds = .7f)
        requested.save(context)
        store.looks.select(store.looks.items[2])
        store.redevelop(folder.name)
        assertThrows(IllegalArgumentException::class.java) { store.redevelop(folder.name) }
        val recipe = JSONObject(File(folder, "photo.json").readText())
        assertEquals(requested, BeautySettings.snapshot(recipe.getJSONObject("beauty")))
        assertEquals(store.looks.items[2].id, recipe.getString("lut"))
        store.develop(folder.name, render = ::rendered)
        assertEquals(original, digest(File(folder, "original.jpg")))
    }

    @Test
    fun fullResolutionComparisonCommitsAndStaleBaselineIsRemovedOnNewRender() {
        val folder = capture()
        store.develop(
            folder.name,
            render = { d, l, r ->
                rendered(d, l, r)
                File(d, "lut_only.jpg.tmp").writeBytes(byteArrayOf(8, 9))
            },
        )
        assertEquals("lut_only.jpg", store.photo(folder.name)!!.lutOnly!!.name)
        store.redevelop(folder.name)
        store.develop(folder.name, render = ::rendered)
        assertFalse(File(folder, "lut_only.jpg").exists())
        assertEquals(store.photo(folder.name)!!.film, store.photo(folder.name)!!.lutOnly)
        store.delete(folder.name)
        assertFalse(folder.exists())
    }

    @Test
    fun bundledModelsAreExactAndDoNotRequireNetworkPermission() {
        val hashes =
            mapOf(
                "face_detection_short_range.tflite" to
                    "bbff11cebd1eb27a1e004cae0b0e63ec8c551cbf34a4451148b4908b8db3eca8",
                "face_landmark.tflite" to
                    "1055cb9d4a9ca8b8c688902a3a5194311138ba256bcc94e336d8373a5f30c814",
            )
        for ((name, expected) in hashes) {
            val bytes = context.assets.open("beauty/$name").use { it.readBytes() }
            val actual =
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            assertEquals(expected, actual)
        }
        val permissions =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS,
                )
                .requestedPermissions ?: emptyArray()
        assertFalse(permissions.contains("android.permission.INTERNET"))
    }

    @Test
    fun disabledBeautyAndUnavailableAndroidRuntimeNeverMutateImage() {
        val image = Bitmap.createBitmap(100, 120, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(133, 98, 72))
        assertEquals(
            "disabled",
            NaturalBeauty.apply(context, image, BeautySettings(enabled = false)).state,
        )
        // Robolectric has no Android LiteRT JNI. This exercises real initialization failure,
        // not mock landmarks or fake inference; model inference is separately checked on host.
        assertEquals("error_fallback", NaturalBeauty.apply(context, image, BeautySettings()).state)
        val pixels = IntArray(12000)
        image.getPixels(pixels, 0, 100, 0, 0, 100, 120)
        assertTrue(pixels.all { it == Color.rgb(133, 98, 72) })
        image.recycle()
    }

    @Test
    fun decoderNormalizesAllEightExifTransformsBeforeBeautyCoordinates() {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val orders =
            arrayOf(
                intArrayOf(0, 1, 2, 3),
                intArrayOf(1, 0, 3, 2),
                intArrayOf(3, 2, 1, 0),
                intArrayOf(2, 3, 0, 1),
                intArrayOf(0, 2, 1, 3),
                intArrayOf(2, 0, 3, 1),
                intArrayOf(3, 1, 2, 0),
                intArrayOf(1, 3, 0, 2),
            )
        val image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint()
        for (i in 0..3) {
            paint.color = colors[i]
            val x = (i % 2) * 60f
            val y = (i / 2) * 40f
            canvas.drawRect(x, y, x + 60, y + 40, paint)
        }
        for (orientation in 1..8) {
            val file = File(context.cacheDir, "orientation-$orientation.jpg")
            Imaging.jpeg(image, file)
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            // Robolectric's host Skia cannot open an Android file descriptor. ByteBuffer uses
            // the same native JPEG/EXIF decoder and production decode configuration.
            val decoded =
                Imaging.decodeSource(
                    android.graphics.ImageDecoder.createSource(
                        java.nio.ByteBuffer.wrap(file.readBytes())
                    )
                )
            assertEquals(if (orientation < 5) 120 else 80, decoded.width)
            assertEquals(if (orientation < 5) 80 else 120, decoded.height)
            for (i in 0..3) {
                val c =
                    decoded.getPixel(
                        if (i % 2 == 0) 10 else decoded.width - 11,
                        if (i / 2 == 0) 10 else decoded.height - 11,
                    )
                val expected = colors[orders[orientation - 1][i]]
                assertTrue(
                    "orientation=$orientation corner=$i",
                    kotlin.math.abs(Color.red(c) - Color.red(expected)) < 5 &&
                        kotlin.math.abs(Color.green(c) - Color.green(expected)) < 5 &&
                        kotlin.math.abs(Color.blue(c) - Color.blue(expected)) < 5,
                )
            }
            decoded.recycle()
        }
        image.recycle()
    }
}
