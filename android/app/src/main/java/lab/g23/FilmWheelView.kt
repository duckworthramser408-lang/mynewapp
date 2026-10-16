package lab.g23

import android.animation.*
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import kotlin.math.*

/** Continuous wheel position; only a whole detent can be committed as a selection. */
class FilmWheelState(val count: Int, initial: Int = 0) {
    init {
        require(count > 0 && initial in 0 until count)
    }

    var position = initial.toFloat()
    val selected: Int
        get() = Math.floorMod(position.roundToInt(), count)

    fun target(velocitySteps: Float = 0f) =
        round(position + (velocitySteps * .12f).coerceIn(-2.5f, 2.5f))

    fun itemAt(slot: Int) = Math.floorMod(slot, count)
}

/** Native vector film canisters, not LUT previews. The leftmost point aligns with the arrow. */
class FilmWheelView(c: Context, val films: List<FilmLut>, initial: Int = 0) : View(c) {
    val state = FilmWheelState(films.size, initial)
    val selectedIndex
        get() = state.selected

    var onSelectionChanged: ((FilmLut) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var animator: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var downX = 0f
    private var lastY = 0f
    private var dragged = false
    private var lastHaptic = 0L
    private var notified = initial
    private val hits = mutableListOf<Pair<Int, PointF>>()
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val radius
        get() = min(width * .64f, height * .40f)

    private val centerX
        get() = width * .96f

    private val centerY
        get() = height * .5f

    private val stepPixels
        get() = max(40 * density, radius * .50f)

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        describe()
    }

    private fun describe() {
        contentDescription = "当前胶卷：${films[state.selected].name}。上下滑动选择，停下自动吸附。"
    }

    private fun update(value: Float, haptic: Boolean = true) {
        state.position = value
        if (state.selected != notified) {
            notified = state.selected
            describe()
            onSelectionChanged?.invoke(films[notified])
            val now = SystemClock.uptimeMillis()
            if (haptic && now - lastHaptic >= 45) {
                performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
                    else HapticFeedbackConstants.CLOCK_TICK
                )
                lastHaptic = now
            }
        }
        invalidate()
    }

    fun finishSelection(): FilmLut {
        animator?.cancel()
        animator = null
        update(round(state.position), false)
        return films[state.selected]
    }

    fun snap(velocitySteps: Float = 0f) = animateTo(state.target(velocitySteps))

    fun step(direction: Int) {
        animator?.cancel()
        animateTo(round(state.position) + direction)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        val start = state.position
        if (abs(start - target) < .001f) {
            update(target, false)
            return
        }
        animator =
            ValueAnimator.ofFloat(start, target).apply {
                duration = 320L + (min(3f, abs(start - target)) * 40).toLong()
                interpolator = OvershootInterpolator(.55f)
                addUpdateListener { update(it.animatedValue as Float) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled) {
                                update(target, false)
                                animator = null
                            }
                        }
                    }
                )
                start()
            }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(LabUi.background)
        if (width <= 0 || height <= 0) return
        val cx = centerX
        val cy = centerY
        val r = radius
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = density
        p.color = LabUi.line
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 90f, 180f, false, p)
        // Fine mechanical teeth move with the canisters, between the larger detents.
        val base = floor(state.position).toInt()
        for (i in -32..32) {
            val a = (i / 8f - (state.position - base)) * .52f
            if (abs(a) > 1.6f) continue
            val outer = r + 10 * density
            val inner = outer - (if (i % 8 == 0) 9 else 4) * density
            p.color = if (i % 8 == 0) 0xff626b65.toInt() else LabUi.line
            canvas.drawLine(
                cx - cos(a) * outer,
                cy + sin(a) * outer,
                cx - cos(a) * inner,
                cy + sin(a) * inner,
                p,
            )
        }
        hits.clear()
        (base - 4..base + 4)
            .sortedByDescending { abs(it - state.position) }
            .forEach { slot ->
                val delta = slot - state.position
                val a = delta * .52f
                if (abs(a) > 1.65f) return@forEach
                val x = cx - r * cos(a)
                val y = cy + r * sin(a)
                hits.add(slot to PointF(x, y))
                val focus = (1 - min(1f, abs(delta))).coerceIn(0f, 1f)
                val scale =
                    (.82f + .18f * focus) * min(1f, width / (360 * density)).coerceAtLeast(.75f)
                canvas.save()
                canvas.translate(x, y)
                canvas.scale(density * scale, density * scale)
                canvas.rotate(-sin(a) * 9)
                canister(
                    canvas,
                    films[state.itemAt(slot)],
                    state.itemAt(slot),
                    focus,
                    (.38f + .62f * (1 - abs(a) / 1.8f)).coerceIn(.25f, 1f),
                )
                canvas.restore()
            }
        // Left-centre selection pointer, in line with the selected film's centre.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.7f * density
        p.color = LabUi.accent
        p.alpha = 255
        val tip = max(38 * density, cx - r - 39 * density)
        canvas.drawLine(12 * density, cy, tip, cy, p)
        path.reset()
        path.moveTo(tip - 7 * density, cy - 5 * density)
        path.lineTo(tip, cy)
        path.lineTo(tip - 7 * density, cy + 5 * density)
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.textSize = 10 * density
        p.color = LabUi.muted
        val labelX = width * .77f
        canvas.drawText("当前胶卷", labelX, cy - 39 * density, p)
        val name = films[state.selected].name
        val textWidth = width * .39f
        p.textSize = 19 * density
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.color = LabUi.foreground
        val lines = wrap(name, textWidth)
        lines.take(3).forEachIndexed { i, line ->
            canvas.drawText(line, labelX, cy - 5 * density + i * 26 * density, p)
        }
        p.textSize = 10 * density
        p.typeface = Typeface.DEFAULT
        p.color = LabUi.muted
        canvas.drawText(
            "${(state.selected+1).toString().padStart(2,'0')}  /  ${films.size}",
            labelX,
            cy + max(48 * density, (lines.size - 1) * 26 * density + 25 * density),
            p,
        )
    }

    private fun wrap(value: String, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        var line = ""
        value.forEach { c ->
            if (line.isNotEmpty() && p.measureText(line + c) > maxWidth) {
                result.add(line)
                line = ""
            }
            line += c
        }
        if (line.isNotEmpty()) result.add(line)
        return result
    }

    private fun canister(c: Canvas, film: FilmLut, index: Int, focus: Float, alpha: Float) {
        p.alpha = (255 * alpha).toInt()
        p.style = Paint.Style.FILL
        // Metal spool and gently shaded cylindrical body.
        p.color = 0xff1b1e1d.toInt()
        p.alpha = (255 * alpha).toInt()
        c.drawRoundRect(-7f, -50f, 7f, -32f, 3f, 3f, p)
        p.shader =
            LinearGradient(
                -29f,
                0f,
                29f,
                0f,
                intArrayOf(
                    0xff121514.toInt(),
                    0xff424943.toInt(),
                    0xff202622.toInt(),
                    0xff111412.toInt(),
                ),
                floatArrayOf(0f, .25f, .65f, 1f),
                Shader.TileMode.CLAMP,
            )
        c.drawRect(-29f, -35f, 29f, 37f, p)
        c.drawOval(-29f, 27f, 29f, 47f, p)
        p.shader = null
        p.color = film.color
        p.alpha = (255 * alpha).toInt()
        c.drawRect(-28f, -13f, 28f, 25f, p)
        c.drawOval(-28f, 16f, 28f, 33f, p)
        p.color = 0xff191e1a.toInt()
        p.alpha = (255 * alpha).toInt()
        c.drawOval(-29f, -45f, 29f, -25f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = if (focus > .5f) film.color else 0xff687168.toInt()
        p.alpha = (alpha * if (focus > .5f) 220 else 100).toInt()
        c.drawOval(-29f, -45f, 29f, -25f, p)
        c.drawArc(-29f, 27f, 29f, 47f, 0f, 180f, false, p)
        p.style = Paint.Style.FILL
        p.color = 0xff1b211b.toInt()
        p.alpha = (255 * alpha).toInt()
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 10f
        c.drawText("FILM", 0f, 3f, p)
        p.textSize = 8f
        c.drawText((index + 1).toString().padStart(2, '0'), 0f, 16f, p)
        p.color = 0xffb1bab0.toInt()
        p.alpha = (70 * alpha).toInt()
        for (i in -2..2) c.drawRect(i * 7f - 1, -36f, i * 7f + 1, -31f, p)
        p.alpha = 255
        p.shader = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                downY = e.y
                downX = e.x
                lastY = e.y
                dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (abs(e.y - downY) > slop || abs(e.x - downX) > slop) dragged = true
                if (dragged) update(state.position - (e.y - lastY) / stepPixels)
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val velocity = -(tracker?.yVelocity ?: 0f) / stepPixels
                tracker?.recycle()
                tracker = null
                if (dragged) snap(velocity)
                else {
                    val hit = hits.minByOrNull { (_, v) -> hypot(v.x - e.x, v.y - e.y) }
                    if (hit != null && hypot(hit.second.x - e.x, hit.second.y - e.y) < 60 * density)
                        animateTo(hit.first.toFloat())
                    else snap()
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle()
                tracker = null
                snap()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(key: Int, event: KeyEvent): Boolean =
        when (key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                step(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                step(-1)
                true
            }
            else -> super.onKeyDown(key, event)
        }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean =
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                step(1)
                true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                step(-1)
                true
            }
            else -> super.performAccessibilityAction(action, args)
        }
}
