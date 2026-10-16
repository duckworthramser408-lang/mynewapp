package lab.g23

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraLayoutTest {
    private fun camera(): CameraLayout {
        val c = ApplicationProvider.getApplicationContext<Context>()
        c.setTheme(android.R.style.Theme_Material_NoActionBar)
        return CameraLayout(c, {}, {}, {}, {}, {}, {}, {}).apply { selectedRatio = "4:3" }
    }

    private fun place(view: View, w: Int, h: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
    }

    @Test
    fun shutterIsCenteredAndAlbumRemainsAtBottomLeft() {
        for ((w, h) in listOf(360 to 760, 320 to 568, 412 to 860, 760 to 360)) {
            val v = camera()
            place(v, w, h)
            val dock = v.shutter.parent as FrameLayout
            assertEquals(w / 2f, v.shutter.x + v.shutter.width / 2f, .5f)
            assertTrue(v.albumButton.right < v.shutter.left)
            assertTrue(dock.y >= v.height * .5f)
            assertTrue(v.shutter.height >= 48)
            assertTrue(v.viewfinder.height > 0)
            assertTrue(v.viewfinder.bottom <= v.previewContainer.height)
            assertEquals(.75f, v.viewfinder.width.toFloat() / v.viewfinder.height, .02f)
        }
    }

    @Test
    fun drawsActualNativeCameraLayoutWithoutFabricatedPhoto() {
        val v = camera()
        v.setLutName("理光GR")
        v.status("允许相机权限，开始拍摄\n照片与数据保存在本机")
        place(v, 360, 760)
        val bitmap = Bitmap.createBitmap(360, 760, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        val output = File("build/reports/ui/camera.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        assertFalse(v.shutter.isEnabled)
        v.ready()
        assertTrue(v.shutter.isEnabled)
        assertEquals(View.GONE, v.state.visibility)
    }
}
