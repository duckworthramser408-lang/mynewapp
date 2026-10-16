package lab.g23

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*
import lab.g23.LabUi.dp

/** Real camera surface is attached by the Activity; this class owns only the native layout. */
class CameraLayout(
    c: Context,
    onShoot: () -> Unit,
    onAlbum: () -> Unit,
    onFlip: () -> Unit,
    onSettings: () -> Unit,
    onFlash: () -> Unit,
    onGrid: () -> Unit,
    onRatio: (String) -> Unit,
) : LinearLayout(c) {
    val previewContainer = AspectFrame(c)
    val viewfinder = FrameLayout(c)
    val flashButton = LabUi.iconButton(c, LabIcon.Kind.FLASH, "闪光灯已关闭", onFlash)
    val shutter = ShutterButton(c)
    val albumButton =
        LinearLayout(c).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
    val thumbnail =
        ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = LabUi.shape(LabUi.raised, c.dp(10), LabUi.line)
        }
    val state =
        LabUi.text(c, "正在连接相机", 12f, LabUi.muted).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(24), 0, c.dp(24), 0)
        }
    val exposurePanel = LabUi.column(c)
    val exposureLabel = LabUi.label(c, "曝光补偿  0.0 档")
    val exposureSlider = SeekBar(c)
    val captureLabel = LabUi.label(c, "高画质拍摄")
    var selectedRatio = "4:3"
        set(value) {
            field = value
            updateFrame()
            ratioViews.forEach { (label, v) ->
                v.setTextColor(if (label == value) LabUi.accent else LabUi.muted)
            }
        }

    private val ratioViews = mutableListOf<Pair<String, TextView>>()

    init {
        orientation = VERTICAL
        setBackgroundColor(LabUi.background)
        val top = LabUi.row(c)
        top.addView(flashButton, LayoutParams(c.dp(48), c.dp(60)))
        val brand =
            LabUi.column(c).apply {
                gravity = Gravity.CENTER
                addView(
                    LabUi.text(c, "G23 LAB", 18f).apply {
                        gravity = Gravity.CENTER
                        letterSpacing = .14f
                        setTypeface(typeface, Typeface.BOLD)
                    }
                )
                addView(
                    LabUi.label(c, "影像实验室").apply {
                        gravity = Gravity.CENTER
                        setPadding(0, c.dp(5), 0, 0)
                    }
                )
            }
        top.addView(brand, LayoutParams(0, c.dp(60), 1f))
        top.addView(
            LabUi.iconButton(c, LabIcon.Kind.SETTINGS, "相机设置", onSettings),
            LayoutParams(c.dp(48), c.dp(60)),
        )
        addView(top)
        viewfinder.clipToOutline = true
        viewfinder.background = LabUi.shape(Color.BLACK, c.dp(12))
        previewContainer.addView(viewfinder, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        addView(previewContainer, LayoutParams(-1, 0, 1f))
        viewfinder.addView(state, FrameLayout.LayoutParams(-1, -1))
        updateFrame()
        val ratios =
            LabUi.row(c).apply {
                gravity = Gravity.CENTER
                setPadding(0, c.dp(8), 0, 0)
            }
        listOf("4:3", "3:2", "1:1").forEach { ratio ->
            val b =
                LabUi.text(c, ratio, 13f, if (ratio == selectedRatio) LabUi.accent else LabUi.muted)
                    .apply {
                        gravity = Gravity.CENTER
                        minimumHeight = c.dp(44)
                        setOnClickListener { onRatio(ratio) }
                    }
            ratioViews.add(ratio to b)
            ratios.addView(b, LayoutParams(c.dp(62), c.dp(44)))
        }
        ratios.addView(
            LabUi.iconButton(c, LabIcon.Kind.GRID, "构图网格", onGrid),
            LayoutParams(c.dp(44), c.dp(44)),
        )
        addView(ratios)
        exposurePanel.addView(exposureLabel)
        exposurePanel.addView(exposureSlider, LayoutParams(-1, c.dp(36)))
        exposurePanel.visibility = GONE
        addView(exposurePanel)
        val dock = FrameLayout(c)
        albumButton.contentDescription = "打开相册"
        albumButton.isFocusable = true
        albumButton.isClickable = true
        val holder = FrameLayout(c)
        holder.addView(
            LabIcon(c, LabIcon.Kind.ALBUM),
            FrameLayout.LayoutParams(c.dp(23), c.dp(23), Gravity.CENTER),
        )
        holder.addView(thumbnail, FrameLayout.LayoutParams(c.dp(42), c.dp(42), Gravity.CENTER))
        thumbnail.visibility = GONE
        albumButton.addView(holder, LayoutParams(c.dp(48), c.dp(48)))
        albumButton.addView(
            LabUi.label(c, "相册").apply {
                gravity = Gravity.CENTER
                setPadding(0, c.dp(6), 0, 0)
            }
        )
        albumButton.setOnClickListener { onAlbum() }
        dock.addView(
            albumButton,
            FrameLayout.LayoutParams(c.dp(72), c.dp(88), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        shutter.isEnabled = false
        shutter.contentDescription = "拍照"
        shutter.isClickable = true
        shutter.isFocusable = true
        shutter.setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onShoot()
        }
        dock.addView(shutter, FrameLayout.LayoutParams(c.dp(84), c.dp(84), Gravity.CENTER))
        val flip =
            LabUi.column(c).apply {
                gravity = Gravity.CENTER
                addView(
                    LabUi.iconButton(c, LabIcon.Kind.FLIP, "切换前后摄像头", onFlip),
                    LayoutParams(c.dp(48), c.dp(48)),
                )
                addView(
                    LabUi.label(c, "翻转").apply {
                        gravity = Gravity.CENTER
                        setPadding(0, c.dp(6), 0, 0)
                    }
                )
            }
        dock.addView(
            flip,
            FrameLayout.LayoutParams(c.dp(72), c.dp(88), Gravity.END or Gravity.CENTER_VERTICAL),
        )
        addView(dock, LayoutParams(-1, c.dp(108)))
        captureLabel.gravity = Gravity.CENTER
        addView(captureLabel, LayoutParams(-1, c.dp(24)))
    }

    private fun updateFrame() {
        val portrait =
            when (selectedRatio) {
                "3:2" -> 2f / 3
                "1:1" -> 1f
                else -> 3f / 4
            }
        previewContainer.aspect =
            if (
                resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            )
                1 / portrait
            else portrait
    }

    fun setFlash(enabled: Boolean) {
        (flashButton.getChildAt(0) as LabIcon).tint =
            if (enabled) LabUi.accent else LabUi.foreground
        flashButton.contentDescription = if (enabled) "闪光灯已开启" else "闪光灯已关闭"
    }

    fun ready() {
        state.visibility = GONE
        shutter.isEnabled = true
        shutter.invalidate()
    }

    fun status(message: String) {
        state.text = message
        state.visibility = VISIBLE
        shutter.isEnabled = false
        shutter.invalidate()
    }
}

class ShutterButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val center = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dp(3)
        p.style = Paint.Style.STROKE
        p.strokeWidth = context.dp(2).toFloat()
        p.color = if (isEnabled) LabUi.foreground else LabUi.line
        canvas.drawCircle(center, cy, r, p)
        p.style = Paint.Style.FILL
        p.color = if (isEnabled) LabUi.foreground else LabUi.raised
        canvas.drawCircle(center, cy, r - context.dp(if (isPressed) 12 else 7), p)
    }
}

/** Size the surface during measurement, so the first frame already has the selected ratio. */
class AspectFrame(c: Context) : FrameLayout(c) {
    var aspect = .75f
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val vw = minOf(w, (h * aspect).toInt())
        val vh = minOf(h, (vw / aspect).toInt())
        for (i in 0 until childCount) getChildAt(i)
            .measure(
                MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY),
            )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val x = (width - child.measuredWidth) / 2
            val y = (height - child.measuredHeight) / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
