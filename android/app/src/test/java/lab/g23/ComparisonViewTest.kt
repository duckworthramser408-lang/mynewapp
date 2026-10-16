package lab.g23

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComparisonViewTest {
    private fun layout(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
    }

    @Test
    fun pairedPreviewDrawsOriginalOnLeftAndEditedOnRight() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val original =
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val edited =
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val view =
            PhotoView(c).apply {
                before = original
                after = edited
            }
        layout(view, 360, 240)
        val result = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(result))
        assertEquals(Color.RED, result.getPixel(90, 130))
        assertEquals(Color.BLUE, result.getPixel(270, 130))
        view.compare = true
        view.draw(Canvas(result))
        assertEquals(Color.RED, result.getPixel(90, 130))
        assertEquals(Color.BLUE, result.getPixel(270, 130))
        original.recycle()
        edited.recycle()
        result.recycle()
    }

    @Test
    fun dataTableReservesScrollableSpaceInsideTheDialog() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        c.setTheme(android.R.style.Theme_Material_NoActionBar)
        val store = SampleStore(c)
        val before = store.defaults()
        val after =
            JSONObject(before.toString()).apply {
                getJSONObject("parameters").put("exposure_ev", 1.2)
            }
        val panel =
            ComparisonPanel.create(c, "G23_test", JSONObject().put("iso", 100), before, after, null)
        layout(panel, 360, 600)
        val scroll = panel.getChildAt(2) as ScrollView
        assertTrue(scroll.measuredHeight > 300)
        assertTrue(scroll.getChildAt(0).measuredHeight > scroll.measuredHeight)
    }
}
