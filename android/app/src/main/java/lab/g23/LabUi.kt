package lab.g23

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared native controls. Dimensions are dp, including minimum touch targets. */
object LabUi {
    val background = Color.rgb(15, 17, 18)
    val surface = Color.rgb(25, 28, 29)
    val raised = Color.rgb(34, 38, 39)
    val line = Color.rgb(49, 54, 54)
    val foreground = Color.rgb(235, 235, 226)
    val muted = Color.rgb(148, 156, 155)
    val accent = Color.rgb(184, 199, 168)

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    fun shape(color: Int, radius: Int = 16, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(1, stroke)
        }

    fun text(c: Context, value: String, size: Float = 14f, color: Int = foreground) =
        TextView(c).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun row(c: Context) =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun label(c: Context, value: String) = text(c, value, 11f, muted)

    fun button(c: Context, value: String, primary: Boolean = false, action: () -> Unit) =
        text(c, value, 14f, if (primary) background else foreground).apply {
            gravity = Gravity.CENTER
            minHeight = c.dp(48)
            setPadding(c.dp(16), 0, c.dp(16), 0)
            background =
                RippleDrawable(
                    ColorStateList.valueOf(0x22ffffff),
                    shape(if (primary) accent else raised, c.dp(14)),
                    null,
                )
            setTypeface(typeface, if (primary) Typeface.BOLD else Typeface.NORMAL)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    fun field(c: Context, hintText: String, value: String = "") =
        EditText(c).apply {
            hint = hintText
            setText(value)
            textSize = 14f
            setTextColor(LabUi.foreground)
            setHintTextColor(muted)
            minHeight = c.dp(48)
            setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10))
            isSingleLine = true
            background = shape(surface, c.dp(12))
        }

    fun space(c: Context, size: Int) =
        View(c).apply { layoutParams = LinearLayout.LayoutParams(c.dp(size), c.dp(size)) }

    fun divider(c: Context) =
        View(c).apply {
            setBackgroundColor(line)
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(1))
        }

    fun weightedRow(c: Context, vararg views: View): LinearLayout =
        row(c).apply {
            views.forEachIndexed { i, v ->
                addView(
                    v,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = c.dp(8)
                    },
                )
            }
        }

    fun iconButton(c: Context, kind: LabIcon.Kind, description: String, action: () -> Unit) =
        FrameLayout(c).apply {
            contentDescription = description
            minimumWidth = c.dp(48)
            minimumHeight = c.dp(48)
            background =
                RippleDrawable(
                    ColorStateList.valueOf(0x22ffffff),
                    shape(Color.TRANSPARENT, c.dp(24)),
                    null,
                )
            addView(LabIcon(c, kind), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    fun header(
        c: Context,
        title: String,
        subtitle: String? = null,
        back: (() -> Unit)? = null,
        action: View? = null,
    ) =
        row(c).apply {
            if (back != null)
                addView(
                    iconButton(c, LabIcon.Kind.BACK, "返回", back),
                    LinearLayout.LayoutParams(c.dp(44), c.dp(52)),
                )
            val titles = column(c)
            titles.addView(text(c, title, 20f).apply { setTypeface(typeface, Typeface.BOLD) })
            if (subtitle != null)
                titles.addView(label(c, subtitle).apply { setPadding(0, c.dp(5), 0, 0) })
            addView(titles, LinearLayout.LayoutParams(0, c.dp(64), 1f))
            if (action != null)
                addView(
                    action,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, c.dp(48)),
                )
        }
}

/** Small outline icons drawn as vectors; no emoji or network assets. */
class LabIcon(context: Context, val kind: Kind) : View(context) {
    enum class Kind {
        BACK,
        ALBUM,
        SETTINGS,
        FLIP,
        FLASH,
        GRID,
        EXPORT,
        SAVE,
        CHECK,
        CLOSE,
        UNDO,
        REDO,
        RESET,
        COMPARE,
        DATA,
        DELETE,
        FILTER,
        CAMERA,
        COPY,
        PLUS,
    }

    var tint = LabUi.foreground
        set(value) {
            field = value
            invalidate()
        }

    private val p =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.65f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save()
        c.scale(width / 24f, height / 24f)
        p.color = tint
        path.reset()
        fun line(vararg xy: Float) {
            path.moveTo(xy[0], xy[1])
            for (i in 2 until xy.size step 2) path.lineTo(xy[i], xy[i + 1])
            c.drawPath(path, p)
            path.reset()
        }
        fun rect(l: Float, t: Float, r: Float, b: Float) = c.drawRoundRect(l, t, r, b, 3f, 3f, p)
        when (kind) {
            Kind.BACK -> line(15f, 5f, 8f, 12f, 15f, 19f)
            Kind.ALBUM -> {
                rect(3f, 4f, 21f, 21f)
                c.drawCircle(8f, 9f, 1.3f, p)
                line(4f, 18f, 10f, 13f, 14f, 16f, 18f, 11f, 21f, 14f)
            }
            Kind.SETTINGS -> {
                c.drawCircle(12f, 12f, 3.2f, p)
                for (i in 0..7) {
                    val a = i * Math.PI / 4
                    val x = kotlin.math.cos(a).toFloat()
                    val y = kotlin.math.sin(a).toFloat()
                    c.drawLine(12 + 7 * x, 12 + 7 * y, 12 + 9 * x, 12 + 9 * y, p)
                }
                c.drawCircle(12f, 12f, 7f, p)
            }
            Kind.FLIP -> {
                c.drawArc(4f, 4f, 20f, 20f, 210f, 135f, false, p)
                c.drawArc(4f, 4f, 20f, 20f, 30f, 135f, false, p)
                line(15f, 4f, 20f, 4f, 20f, 9f)
                line(4f, 15f, 4f, 20f, 9f, 20f)
            }
            Kind.FLASH -> line(13f, 2f, 5f, 14f, 11f, 14f, 10f, 22f, 19f, 9f, 13f, 9f, 13f, 2f)
            Kind.GRID -> {
                rect(3f, 3f, 21f, 21f)
                line(9f, 3f, 9f, 21f)
                line(15f, 3f, 15f, 21f)
                line(3f, 9f, 21f, 9f)
                line(3f, 15f, 21f, 15f)
            }
            Kind.EXPORT -> {
                line(12f, 3f, 12f, 15f)
                line(7f, 8f, 12f, 3f, 17f, 8f)
                line(4f, 13f, 4f, 21f, 20f, 21f, 20f, 13f)
            }
            Kind.SAVE -> {
                rect(4f, 3f, 20f, 21f)
                rect(8f, 13f, 16f, 21f)
                line(8f, 3f, 8f, 9f, 16f, 9f, 16f, 3f)
            }
            Kind.CHECK -> line(5f, 12f, 10f, 17f, 20f, 6f)
            Kind.CLOSE -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            Kind.UNDO -> {
                c.drawArc(5f, 6f, 21f, 20f, 220f, 250f, false, p)
                line(3f, 4f, 3f, 11f, 10f, 11f)
            }
            Kind.REDO -> {
                c.drawArc(3f, 6f, 19f, 20f, 70f, 250f, false, p)
                line(21f, 4f, 21f, 11f, 14f, 11f)
            }
            Kind.RESET -> {
                c.drawArc(4f, 4f, 20f, 20f, 210f, 300f, false, p)
                line(3f, 3f, 3f, 10f, 10f, 10f)
            }
            Kind.COMPARE -> {
                rect(3f, 4f, 21f, 20f)
                line(12f, 2f, 12f, 22f)
                line(6f, 15f, 9f, 10f)
                line(15f, 14f, 18f, 9f)
            }
            Kind.DATA -> {
                line(4f, 4f, 4f, 20f, 21f, 20f)
                line(8f, 16f, 8f, 11f)
                line(13f, 16f, 13f, 6f)
                line(18f, 16f, 18f, 9f)
            }
            Kind.DELETE -> {
                line(4f, 6f, 20f, 6f)
                line(7f, 6f, 8f, 21f, 16f, 21f, 17f, 6f)
                line(9f, 3f, 15f, 3f)
                line(10f, 10f, 10f, 17f)
                line(14f, 10f, 14f, 17f)
            }
            Kind.FILTER -> {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 20f, 12f)
                line(4f, 18f, 20f, 18f)
                c.drawCircle(9f, 6f, 2f, p)
                c.drawCircle(15f, 12f, 2f, p)
                c.drawCircle(10f, 18f, 2f, p)
            }
            Kind.CAMERA -> {
                rect(3f, 6f, 21f, 21f)
                line(7f, 6f, 9f, 3f, 15f, 3f, 17f, 6f)
                c.drawCircle(12f, 13f, 4f, p)
            }
            Kind.COPY -> {
                rect(8f, 7f, 21f, 21f)
                line(16f, 3f, 3f, 3f, 3f, 17f)
            }
            Kind.PLUS -> {
                line(12f, 4f, 12f, 20f)
                line(4f, 12f, 20f, 12f)
            }
        }
        c.restore()
    }
}
