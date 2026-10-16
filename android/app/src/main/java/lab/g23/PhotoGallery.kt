package lab.g23

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executor
import kotlin.math.*
import lab.g23.LabUi.dp

/** A photo-only grid; long press and the preview trash button expose deletion. */
class PhotoGallery(
    c: Context,
    private val worker: Executor,
    private val open: (Int) -> Unit,
    private val delete: (CameraPhoto) -> Unit,
) : RecyclerView(c) {
    private var photos = emptyList<CameraPhoto>()
    private val main = Handler(Looper.getMainLooper())
    private var revision = 0
    private val cache =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    init {
        layoutManager = GridLayoutManager(c, 3)
        adapter = Cells()
        addItemDecoration(
            object : ItemDecoration() {
                override fun getItemOffsets(
                    out: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: State,
                ) {
                    out.set(1, 1, 1, 1)
                }
            }
        )
    }

    fun submit(items: List<CameraPhoto>) {
        revision++
        photos = items
        adapter?.notifyDataSetChanged()
    }

    private class Cell(val box: FrameLayout, val image: ImageView, val film: DevelopingView) :
        ViewHolder(box) {
        var key = ""
    }

    private inner class Cells : Adapter<Cell>() {
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): Cell {
            val box =
                object : FrameLayout(context) {
                    override fun onMeasure(w: Int, h: Int) {
                        super.onMeasure(
                            w,
                            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY),
                        )
                    }
                }
            box.layoutParams = LayoutParams(-1, -2)
            box.background = LabUi.shape(LabUi.surface, context.dp(4))
            box.clipToOutline = true
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val film = DevelopingView(context)
            box.addView(image, FrameLayout.LayoutParams(-1, -1))
            box.addView(film, FrameLayout.LayoutParams(-1, -1))
            return Cell(box, image, film).also { holder ->
                box.isFocusable = true
                box.setOnClickListener {
                    holder.bindingAdapterPosition.takeIf { it != NO_POSITION }?.let(open)
                }
                box.setOnLongClickListener {
                    holder.bindingAdapterPosition
                        .takeIf { it != NO_POSITION }
                        ?.let { delete(photos[it]) }
                    true
                }
            }
        }

        override fun onBindViewHolder(h: Cell, index: Int) {
            val photo = photos[index]
            val pending = photo.developing || photo.state == "failed"
            h.film.visibility = if (pending) View.VISIBLE else View.GONE
            h.film.label = if (photo.state == "failed") "冲洗未完成" else "冲洗中"
            h.box.contentDescription = if (pending) h.film.label else "照片，点击预览，长按删除"
            h.image.setImageDrawable(null)
            val file = photo.thumbnail
            val key = "${file.path}:${file.lastModified()}:${photo.state}"
            h.key = key
            if (pending) return
            cache.get(key)?.let {
                h.image.setImageBitmap(it)
                return
            }
            val token = revision
            worker.execute {
                runCatching { Imaging.decode(file, 320) }
                    .onSuccess { bitmap ->
                        main.post {
                            if (token == revision && isAttachedToWindow) {
                                cache.put(key, bitmap)
                                if (h.key == key) h.image.setImageBitmap(bitmap)
                            } else bitmap.recycle()
                        }
                    }
            }
        }

        override fun onViewRecycled(h: Cell) {
            h.key = ""
            h.image.setImageDrawable(null)
        }
    }

    override fun onDetachedFromWindow() {
        revision++
        super.onDetachedFromWindow()
        cache.evictAll()
    }
}

/** Film canister placeholder while the photograph develops off screen. */
class DevelopingView(c: Context) : View(c) {
    var label = "冲洗中"
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(LabUi.surface)
        val s = min(width / 120f, height / 140f)
        c.save()
        c.translate(width / 2f, height / 2f)
        c.scale(s, s)
        p.shader =
            LinearGradient(
                -23f,
                0f,
                23f,
                0f,
                intArrayOf(
                    0xff373d38.toInt(),
                    0xffb8c7a8.toInt(),
                    0xff687463.toInt(),
                    0xff222723.toInt(),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        c.drawRoundRect(-23f, -43f, 23f, 20f, 5f, 5f, p)
        p.shader = null
        p.color = LabUi.line
        c.drawRoundRect(-27f, -47f, 27f, -37f, 4f, 4f, p)
        c.drawRoundRect(-27f, 16f, 27f, 25f, 4f, 4f, p)
        p.color = LabUi.background
        c.drawRoundRect(-15f, -24f, 15f, 2f, 3f, 3f, p)
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 11f
        p.color = LabUi.accent
        c.drawText("35", 0f, -7f, p)
        p.typeface = Typeface.DEFAULT
        p.textSize = 12f
        p.color = LabUi.foreground
        c.drawText(label, 0f, 51f, p)
        c.restore()
    }
}

/** Both images share one geometry and transform. Drag the single dividing line. */
class FullPhotoView(c: Context, private val page: (Int) -> Unit) : View(c) {
    var before: Bitmap? = null
    var after: Bitmap? = null
    var split = .5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var dx = 0f
    private var dy = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dividing = false
    private var pinched = false
    private val zoom =
        ScaleGestureDetector(
            c,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    pinched = true
                    scale = (scale * d.scaleFactor).coerceIn(1f, 6f)
                    if (scale == 1f) {
                        dx = 0f
                        dy = 0f
                    }
                    invalidate()
                    return true
                }
            },
        )

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "原片与胶片，拖动竖线对比，双指缩放，竖线外左右滑动浏览"
    }

    fun replace(original: Bitmap?, film: Bitmap?) {
        val oldBefore = before
        val oldAfter = after
        before = original
        after = film
        scale = 1f
        dx = 0f
        dy = 0f
        invalidate()
        if (oldBefore !== original) oldBefore?.recycle()
        if (oldAfter !== film && oldAfter !== oldBefore) oldAfter?.recycle()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(LabUi.background)
        val original = before ?: return
        val s = min(width.toFloat() / original.width, height.toFloat() / original.height) * scale
        val w = original.width * s
        val h = original.height * s
        val rect =
            RectF(
                (width - w) / 2 + dx,
                (height - h) / 2 + dy,
                (width + w) / 2 + dx,
                (height + h) / 2 + dy,
            )
        val film = after
        c.drawBitmap(film ?: original, null, rect, paint)
        if (film == null) return
        val line = width * split
        c.save()
        c.clipRect(0f, 0f, line, height.toFloat())
        c.drawBitmap(original, null, rect, paint)
        c.restore()
        paint.color = LabUi.foreground
        paint.strokeWidth = context.dp(1).toFloat()
        c.drawLine(line, max(0f, rect.top), line, min(height.toFloat(), rect.bottom), paint)
        paint.color = LabUi.background
        c.drawCircle(line, height / 2f, context.dp(17).toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.color = LabUi.foreground
        c.drawCircle(line, height / 2f, context.dp(17).toFloat(), paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = context.dp(17).toFloat()
        c.drawText("‹ ›", line, height / 2f + context.dp(5), paint)
        paint.textSize = context.dp(11).toFloat()
        fun label(value: String, x: Float) {
            paint.color = 0xaa0f1112.toInt()
            c.drawRoundRect(
                x - context.dp(23),
                context.dp(12).toFloat(),
                x + context.dp(23),
                context.dp(36).toFloat(),
                context.dp(8).toFloat(),
                context.dp(8).toFloat(),
                paint,
            )
            paint.color = LabUi.foreground
            c.drawText(value, x, context.dp(28).toFloat(), paint)
        }
        label("原片", context.dp(32).toFloat())
        label("胶片", width - context.dp(32).toFloat())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        zoom.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x
                startY = e.y
                lastX = e.x
                lastY = e.y
                pinched = false
                dividing = after != null && abs(e.x - width * split) < context.dp(32)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinched = true
                dividing = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !pinched) {
                    if (dividing) split = e.x / width
                    else if (scale > 1) {
                        dx += e.x - lastX
                        dy += e.y - lastY
                        invalidate()
                    }
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (
                    !dividing &&
                        !pinched &&
                        scale <= 1 &&
                        abs(e.x - startX) > context.dp(64) &&
                        abs(e.x - startX) > abs(e.y - startY) * 1.3f
                )
                    page(if (e.x < startX) 1 else -1)
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        if (
            after != null &&
                action in
                    listOf(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    )
        ) {
            split +=
                if (
                    action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                )
                    .1f
                else -.1f
            return true
        }
        return super.performAccessibilityAction(action, args)
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (after != null) {
            info.isScrollable = true
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }
}
