package lab.g23

import android.content.Context
import android.graphics.*
import android.os.*
import android.util.LruCache
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.*
import lab.g23.LabUi.dp

/** A photo grid only: no editing, selection, data, filter or export controls. */
class PhotoGallery(c: Context, private val executor: Executor, private val open: (Int) -> Unit) :
    RecyclerView(c) {
    private var photos = listOf<File>()
    private val cache =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private val main = Handler(Looper.getMainLooper())
    private var revision = 0

    init {
        layoutManager = GridLayoutManager(c, 3)
        adapter = PhotoAdapter()
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addItemDecoration(
            object : ItemDecoration() {
                override fun getItemOffsets(
                    out: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: State,
                ) {
                    out.set(c.dp(2), c.dp(2), c.dp(2), c.dp(2))
                }
            }
        )
    }

    fun submit(folders: List<File>) {
        photos = folders
        revision++
        adapter!!.notifyDataSetChanged()
    }

    class Cell(val image: ImageView) : ViewHolder(image) {
        var bound = ""
    }

    private inner class PhotoAdapter : Adapter<Cell>() {
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val image =
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = LabUi.shape(LabUi.surface, context.dp(4))
                    clipToOutline = true
                    layoutParams = LayoutParams(-1, 1)
                    contentDescription = "照片"
                    isFocusable = true
                }
            return Cell(image)
        }

        override fun onBindViewHolder(holder: Cell, position: Int) {
            val folder = photos[position]
            val file =
                File(folder, "thumbnail_after.jpg").takeIf { it.isFile }
                    ?: File(folder, "original.jpg")
            holder.bound = file.path
            holder.image.layoutParams.height =
                ((width - paddingLeft - paddingRight) / 3 - context.dp(4)).coerceAtLeast(
                    context.dp(80)
                )
            holder.image.setImageDrawable(null)
            holder.image.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != NO_POSITION) open(index)
            }
            val hit = cache[file.path]
            if (hit != null) {
                holder.image.setImageBitmap(hit)
                return
            }
            val token = revision
            executor.execute {
                runCatching { Imaging.decode(file, 320) }
                    .onSuccess { bitmap ->
                        main.post {
                            if (isAttachedToWindow && token == revision) {
                                cache.put(file.path, bitmap)
                                if (holder.bound == file.path) holder.image.setImageBitmap(bitmap)
                            } else bitmap.recycle()
                        }
                    }
            }
        }

        override fun onViewRecycled(holder: Cell) {
            holder.bound = ""
            holder.image.setImageDrawable(null)
        }
    }

    override fun onDetachedFromWindow() {
        revision++
        cache.evictAll()
        super.onDetachedFromWindow()
    }
}

/** Full photo browsing with zoom/pan and horizontal paging; no editing overlays. */
class FullPhotoView(c: Context, private val page: (Int) -> Unit) : View(c) {
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            scale = 1f
            dx = 0f
            dy = 0f
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var dx = 0f
    private var dy = 0f
    private var x = 0f
    private var y = 0f
    private var startX = 0f
    private var startY = 0f
    private var pinched = false
    private val zoom =
        ScaleGestureDetector(
            c,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    pinched = true
                    scale = (scale * d.scaleFactor).coerceIn(1f, 8f)
                    if (scale <= 1f) {
                        dx = 0f
                        dy = 0f
                    }
                    invalidate()
                    return true
                }
            },
        )
    private val gestures =
        GestureDetector(
            c,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scale = if (scale > 1) 1f else 2.5f
                    dx = 0f
                    dy = 0f
                    pinched = true
                    invalidate()
                    return true
                }
            },
        )

    init {
        isClickable = true
        contentDescription = "照片，双指缩放，左右滑动浏览"
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(LabUi.background)
        val b = bitmap ?: return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height) * scale
        val w = b.width * s
        val h = b.height * s
        c.drawBitmap(
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        zoom.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = e.x
                y = e.y
                startX = x
                startY = y
                pinched = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && scale > 1) {
                    dx += e.x - x
                    dy += e.y - y
                    invalidate()
                }
                x = e.x
                y = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (
                    scale <= 1 &&
                        !pinched &&
                        abs(e.x - startX) > context.dp(64) &&
                        abs(e.x - startX) > abs(e.y - startY) * 1.3f
                )
                    page(if (e.x < startX) 1 else -1)
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
