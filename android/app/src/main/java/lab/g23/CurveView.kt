package lab.g23

import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.abs
import org.json.JSONArray

class CurveView(context: Context, initial: JSONArray, val changed: (JSONArray) -> Unit) :
    View(context) {
    private val points = mutableListOf<PointF>()
    private var active = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        for (i in 0 until initial.length()) {
            val p = initial.getJSONArray(i)
            points.add(PointF(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
        }
    }

    private val gesture =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onLongPress(e: MotionEvent) {
                    if (active > 0 && active < points.lastIndex) {
                        points.removeAt(active)
                        active = -1
                        emit()
                        invalidate()
                    }
                }
            },
        )

    private fun emit() {
        val a = JSONArray()
        points.forEach { a.put(JSONArray(listOf(it.x, it.y))) }
        changed(a)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(28, 30, 28))
        paint.color = 0x44555555
        paint.strokeWidth = 1f
        for (i in 0..4) {
            c.drawLine(width * i / 4f, 0f, width * i / 4f, height.toFloat(), paint)
            c.drawLine(0f, height * i / 4f, width.toFloat(), height * i / 4f, paint)
        }
        paint.color = Color.rgb(173, 187, 157)
        paint.strokeWidth = 3f
        val n = points.size
        val h = FloatArray(n - 1) { points[it + 1].x - points[it].x }
        val d = FloatArray(n - 1) { (points[it + 1].y - points[it].y) / h[it] }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            val w1 = 2 * h[i] + h[i - 1]
            val w2 = h[i] + 2 * h[i - 1]
            m[i] = if (d[i - 1] * d[i] <= 0) 0f else (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
        }
        val path = Path()
        path.moveTo(0f, (1 - points[0].y) * height)
        for (i in 0 until n - 1) path.cubicTo(
            (points[i].x + h[i] / 3) * width,
            (1 - points[i].y - m[i] * h[i] / 3) * height,
            (points[i + 1].x - h[i] / 3) * width,
            (1 - points[i + 1].y + m[i + 1] * h[i] / 3) * height,
            points[i + 1].x * width,
            (1 - points[i + 1].y) * height,
        )
        paint.style = Paint.Style.STROKE
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        points.forEach { c.drawCircle(it.x * width, (1 - it.y) * height, 8f, paint) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouchEvent(e)
        val x = (e.x / width).coerceIn(0f, 1f)
        val y = (1 - e.y / height).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                active = points.indexOfFirst { abs(it.x - x) < .06 && abs(it.y - y) < .1 }
                if (active < 0 && points.size < 32 && x > .001 && x < .999) {
                    points.add(PointF(x, y))
                    points.sortBy { it.x }
                    active = points.indexOfFirst { it.x == x }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (active >= 0) {
                    val p = points[active]
                    p.y = y
                    if (active > 0 && active < points.lastIndex)
                        p.x =
                            x.coerceIn(points[active - 1].x + .0001f, points[active + 1].x - .0001f)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                emit()
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
