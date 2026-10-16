package lab.g23

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Exercise the real copy and pending-asset lifecycle without external storage permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoSaverTest {
    class GalleryProvider : ContentProvider() {
        lateinit var output: File
        var inserted: ContentValues? = null
        var published = false
        var deleted = false
        var rejectPublish = false

        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            return Uri.parse("content://media/external/images/media/41")
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            s: String?,
            a: Array<out String>?,
        ): Int {
            published = values?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0
            return if (rejectPublish) 0 else 1
        }

        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
            deleted = true
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )

        override fun query(
            uri: Uri,
            p: Array<out String>?,
            s: String?,
            a: Array<out String>?,
            sort: String?,
        ): Cursor? = null

        override fun getType(uri: Uri) = "image/jpeg"
    }

    private lateinit var context: Context
    private lateinit var provider: GalleryProvider
    private lateinit var source: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val testContext = context
        provider =
            GalleryProvider().apply {
                attachInfo(
                    testContext,
                    android.content.pm.ProviderInfo().apply { authority = "media" },
                )
                output = File(testContext.cacheDir, "saved.jpg")
            }
        ShadowContentResolver.registerProviderInternal("media", provider)
        source =
            File(context.cacheDir, "source.jpg").apply { writeBytes(byteArrayOf(1, 10, 42, -1)) }
    }

    @Test
    fun saveCopiesExactOriginalAndPublishesOnlyAfterWrite() {
        PhotoSaver.save(context.contentResolver, source, "G23_原片.jpg")
        assertArrayEquals(source.readBytes(), provider.output.readBytes())
        assertEquals(
            1,
            provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt(),
        )
        assertEquals(
            "Pictures/G23",
            provider.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH),
        )
        assertTrue(provider.published)
        assertFalse(provider.deleted)
    }

    @Test
    fun failedPublishRemovesIncompleteGalleryAssetAndPreservesSource() {
        provider.rejectPublish = true
        try {
            PhotoSaver.save(context.contentResolver, source, "G23_胶片.jpg")
            fail("Publish should fail")
        } catch (expected: IllegalStateException) {}
        assertTrue(provider.deleted)
        assertTrue(source.isFile)
    }
}
