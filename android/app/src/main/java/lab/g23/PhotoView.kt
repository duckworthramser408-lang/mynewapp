package lab.g23

import android.content.Context
import android.graphics.*
import android.view.*
import kotlin.math.min

class PhotoView(context: Context) : View(context) {
    var before: Bitmap? = null
    var after: Bitmap? = null
    var compare = false
    private var original = false
    private var scale = 1f
    private var dx = 0f
    private var dy = 0f
    private var split = .5f
    private var lastX = 0f
    private var lastY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val zoom =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    scale = (scale * d.scaleFactor).coerceIn(1f, 8f)
                    invalidate()
                    return true
                }
            },
        )
    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onLongPress(e: MotionEvent) {
                    original = true
                    invalidate()
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scale = 1f
                    dx = 0f
                    dy = 0f
                    invalidate()
                    return true
                }
            },
        )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 13, 12))
        val image = if (original) before else after ?: before
        image ?: return
        fun draw(b: Bitmap) {
            val s = min(width.toFloat() / b.width, height.toFloat() / b.height) * scale
            val w = b.width * s
            val h = b.height * s
            canvas.drawBitmap(
                b,
                null,
                RectF(
                    (width - w) / 2 + dx,
                    (height - h) / 2 + dy,
                    (width + w) / 2 + dx,
                    (height + h) / 2 + dy,
                ),
                paint,
            )
        }
        draw(image)
        if (compare && !original) {
            canvas.save()
            canvas.clipRect(0f, 0f, width * split, height.toFloat())
            before?.let { draw(it) }
            canvas.restore()
            paint.color = Color.WHITE
            paint.strokeWidth = 2f
            canvas.drawLine(width * split, 0f, width * split, height.toFloat(), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoom.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !original) {
                    if (compare) split = (event.x / width).coerceIn(0f, 1f)
                    else if (scale > 1) {
                        dx += event.x - lastX
                        dy += event.y - lastY
                    }
                }
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                original = false
                invalidate()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

class GridOverlay(context: Context) : View(context) {
    var gridVisible = false

    override fun onDraw(c: Canvas) {
        if (!gridVisible) return
        val p =
            Paint().apply {
                color = 0x66ffffff
                strokeWidth = 1f
            }
        for (i in 1..2) {
            c.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), p)
            c.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, p)
        }
    }
}
