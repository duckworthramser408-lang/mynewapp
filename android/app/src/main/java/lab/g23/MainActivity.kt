package lab.g23

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.*

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {
    private lateinit var store: SampleStore
    private lateinit var root: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var rawRequested = false
    private var rawSupported = false
    private var rawActive = false
    private var front = false
    private var flash = false
    private var ratio = "4:3"
    private var busy = false
    private var folder: File? = null
    private var doc = JSONObject()
    private var history: EditHistory? = null
    private var photo: PhotoView? = null
    private var previewSource: Bitmap? = null
    private val generation = AtomicInteger(0)
    private var panel: TextView? = null
    private var screen = ""
    private var category = "基础"
    private var editorControls: LinearLayout? = null
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) cameraPage() else message("需要相机权限才能拍摄；照片仅保存在本机。可在系统设置开启权限。")
        }
    private val importPreset =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                background("导入预设") {
                    val text =
                        contentResolver.openInputStream(it)!!.use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val block = ByteArray(4096)
                            while (true) {
                                val n = stream.read(block)
                                if (n < 0) break
                                require(out.size() + n <= 65536) { "预设大于 64 KB" }
                                out.write(block, 0, n)
                            }
                            out.toString("UTF-8")
                        }
                    val preset = JSONObject(text)
                    val data = preset.optJSONObject("document") ?: preset
                    validatePreset(data)
                    store.savePreset(
                        preset.optString("name", "Imported ${System.currentTimeMillis()}"),
                        data,
                    )
                }
            }
        }
    private val exportPreset =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri
            ->
            uri?.let {
                val text = doc.toString(2)
                background("导出预设") {
                    contentResolver.openOutputStream(it)!!.use { s -> s.write(text.toByteArray()) }
                }
            }
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = SampleStore(this)
        window.statusBarColor = Color.rgb(16, 17, 16)
        window.navigationBarColor = Color.rgb(16, 17, 16)
        cameraPage()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        provider?.unbindAll()
        worker.shutdown()
        super.onDestroy()
    }

    private fun message(s: String) {
        if (!isFinishing)
            AlertDialog.Builder(this).setMessage(s).setPositiveButton("确定", null).show()
    }

    private fun error(e: Throwable) {
        message(e.message ?: e.javaClass.simpleName)
    }

    private fun column() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }

    private fun text(s: String, size: Float = 14f) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.rgb(220, 224, 217))
            setPadding(4, 8, 4, 8)
        }

    private fun button(s: String, action: () -> Unit) =
        Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener {
                if (!busy)
                    try {
                        action()
                    } catch (e: Exception) {
                        error(e)
                    }
            }
        }

    private fun row(vararg views: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach {
                addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun page(title: String) {
        screen = title.substringAfterLast(" / ")
        generation.incrementAndGet()
        root = column()
        root.setBackgroundColor(Color.rgb(16, 17, 16))
        val heading = text(title, 21f)
        root.addView(heading)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val safe = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(12, safe.top + 8, 12, safe.bottom + 8)
            ins
        }
        root.requestApplyInsets()
    }

    private fun nav() {
        root.addView(row(button("Camera") { cameraPage() }, button("Dataset") { datasetPage() }))
    }

    private fun background(label: String, action: () -> Unit) = background(label, action, {})

    private fun background(label: String, action: () -> Unit, onDone: () -> Unit) {
        if (busy) return
        busy = true
        val dialog = AlertDialog.Builder(this).setMessage(label).setCancelable(false).create()
        dialog.show()
        worker.execute {
            try {
                action()
                runOnUiThread {
                    busy = false
                    dialog.dismiss()
                    if (!isDestroyed) onDone()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    busy = false
                    dialog.dismiss()
                    if (!isDestroyed) error(e)
                }
            }
        }
    }

    private fun leaveEditor() {
        generation.incrementAndGet()
        val view = photo
        val before = view?.before
        val after = view?.after
        photo = null
        panel = null
        editorControls = null
        val old = previewSource
        previewSource = null
        worker.execute {
            if (before !== old) before?.recycle()
            after?.recycle()
            old?.recycle()
        }
    }

    private fun cameraPage() {
        leaveEditor()
        page("G23 Lab / Camera")
        nav()
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            root.addView(text("需要摄像头权限以实时预览及拍照；照片默认仅存本机，不上传。"))
            root.addView(button("允许使用相机") { permission.launch(Manifest.permission.CAMERA) })
            return
        }
        val frame = FrameLayout(this)
        val view =
            PreviewView(this).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        val grid = GridOverlay(this)
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val capabilities = text("正在连接真实摄像头…")
        root.addView(capabilities)
        root.addView(
            row(
                button("切换") {
                    front = !front
                    cameraPage()
                },
                button("闪光") {
                    val c = camera ?: return@button
                    if (!c.cameraInfo.hasFlashUnit()) message("该摄像头无闪光灯")
                    else {
                        flash = !flash
                        capture?.flashMode =
                            if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        message(if (flash) "闪光开启" else "闪光关闭")
                    }
                },
                button("网格") {
                    grid.gridVisible = !grid.gridVisible
                    grid.invalidate()
                },
                button(ratio) {
                    val options = arrayOf("4:3", "3:2", "1:1")
                    AlertDialog.Builder(this)
                        .setTitle("比例均从原生 JPEG 居中裁切；原图保留")
                        .setItems(options) { _, i ->
                            ratio = options[i]
                            cameraPage()
                        }
                        .show()
                },
            )
        )
        root.addView(
            button(if (rawRequested) "RAW + JPEG 已请求" else "可选 RAW + JPEG") {
                rawRequested = !rawRequested
                cameraPage()
            }
        )
        val ev = text("曝光补偿")
        root.addView(ev)
        val seek = SeekBar(this)
        root.addView(seek)
        root.addView(button("●  拍摄高质量 JPEG") { shoot() })
        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                camera
                    ?.cameraControl
                    ?.startFocusAndMetering(
                        FocusMeteringAction.Builder(view.meteringPointFactory.createPoint(e.x, e.y))
                            .build()
                    )
                view.performClick()
            }
            true
        }
        val request = ProcessCameraProvider.getInstance(this)
        request.addListener(
            {
                if (screen != "Camera" || isDestroyed) return@addListener
                try {
                    provider = request.get()
                    provider!!.unbindAll()
                    val select =
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                    require(provider!!.hasCamera(select)) { "未发现所选摄像头" }
                    val preview =
                        Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
                    rawSupported =
                        ImageCapture.getImageCaptureCapabilities(provider!!.getCameraInfo(select))
                            .supportedOutputFormats
                            .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                    rawActive = rawRequested && rawSupported
                    capture =
                        ImageCapture.Builder()
                            .setOutputFormat(
                                if (rawActive) ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                                else ImageCapture.OUTPUT_FORMAT_JPEG
                            )
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setJpegQuality(100)
                            .setFlashMode(
                                if (flash) ImageCapture.FLASH_MODE_ON
                                else ImageCapture.FLASH_MODE_OFF
                            )
                            .build()
                    try {
                        camera = provider!!.bindToLifecycle(this, select, preview, capture)
                    } catch (e: IllegalArgumentException) {
                        if (!rawActive) throw e
                        provider!!.unbindAll()
                        rawActive = false
                        capture =
                            ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setJpegQuality(100)
                                .build()
                        camera = provider!!.bindToLifecycle(this, select, preview, capture)
                        message("此设备的 RAW+JPEG 流组合不可用，已降级高质量 JPEG。")
                    }
                    val info = camera!!.cameraInfo
                    val camera2 = Camera2CameraInfo.from(info)
                    val raw =
                        camera2
                            .getCameraCharacteristic(
                                android.hardware.camera2.CameraCharacteristics
                                    .REQUEST_AVAILABLE_CAPABILITIES
                            )
                            ?.contains(
                                android.hardware.camera2.CameraCharacteristics
                                    .REQUEST_AVAILABLE_CAPABILITIES_RAW
                            ) == true
                    capabilities.text =
                        "ID ${camera2.cameraId} · 闪光 ${info.hasFlashUnit()} · RAW 硬件 $raw\n当前采集：${if(rawActive)"RAW + JPEG" else "JPEG"} · RAW 同拍 $rawSupported。第三方采集不等同系统相机 HDR。"
                    val range = info.exposureState.exposureCompensationRange
                    seek.max = range.upper - range.lower
                    seek.progress = -range.lower
                    seek.isEnabled = info.exposureState.isExposureCompensationSupported
                    seek.setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onStartTrackingTouch(s: SeekBar) {}

                            override fun onStopTrackingTouch(s: SeekBar) {}

                            override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                                if (user) {
                                    camera!!
                                        .cameraControl
                                        .setExposureCompensationIndex(v + range.lower)
                                    ev.text =
                                        "曝光补偿 ${(v+range.lower)*info.exposureState.exposureCompensationStep.toFloat()} EV"
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    error(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun shoot() {
        val output = capture ?: return
        val target = store.newCapture()
        busy = true
        val formats = mutableSetOf<Int>()
        var finished = false
        val callback =
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(e: ImageCaptureException) {
                    if (finished) return
                    finished = true
                    busy = false
                    rawRequested = false
                    error(e)
                    cameraPage()
                }

                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (finished || isDestroyed) return
                    formats.add(result.imageFormat)
                    if (
                        rawActive &&
                            !(formats.contains(ImageFormat.JPEG) &&
                                formats.contains(ImageFormat.RAW_SENSOR))
                    )
                        return
                    finished = true
                    busy = false
                    val id =
                        camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId } ?: "unknown"
                    background(
                        "保存原始照片",
                        {
                            store.initialize(target, id)
                            val image = Imaging.decode(File(target, "original.jpg"), 1024)
                            val aspect =
                                when (ratio) {
                                    "3:2" -> 1.5
                                    "1:1" -> 1.0
                                    else -> 4.0 / 3
                                }
                            val desired = if (image.width < image.height) 1 / aspect else aspect
                            val actual = image.width.toDouble() / image.height
                            val crop =
                                if (actual > desired) {
                                    val width = desired / actual
                                    JSONArray(listOf((1 - width) / 2, 0.0, (1 + width) / 2, 1.0))
                                } else {
                                    val height = actual / desired
                                    JSONArray(listOf(0.0, (1 - height) / 2, 1.0, (1 + height) / 2))
                                }
                            image.recycle()
                            val params = store.parameters(target)
                            params
                                .getJSONObject("parameters")
                                .getJSONObject("geometry")
                                .put("crop", crop)
                            atomicText(File(target, "parameters.json"), params.toString(2))
                        },
                    ) {
                        openEditor(target)
                    }
                }
            }
        val jpeg = ImageCapture.OutputFileOptions.Builder(File(target, "original.jpg")).build()
        if (rawActive)
            output.takePicture(
                ImageCapture.OutputFileOptions.Builder(File(target, "original.dng")).build(),
                jpeg,
                ContextCompat.getMainExecutor(this),
                callback,
            )
        else output.takePicture(jpeg, ContextCompat.getMainExecutor(this), callback)
    }

    private fun openEditor(target: File) {
        background("读取样本与编辑历史", { require(File(target, "parameters.json").isFile) }) {
            openEditorLoaded(target)
        }
    }

    private fun openEditorLoaded(target: File) {
        provider?.unbindAll()
        leaveEditor()
        folder = target
        doc = store.parameters(target)
        history = EditHistory(doc, File(target, "history.json").takeIf { it.exists() }?.readText())
        page("G23 Lab / Editor")
        val view = PhotoView(this)
        photo = view
        root.addView(
            row(
                button("返回") { datasetPage() },
                button("对比") {
                    view.compare = !view.compare
                    view.invalidate()
                },
                button("撤销") {
                    doc = history!!.undo()
                    changed(false)
                    controls()
                },
                button("重做") {
                    doc = history!!.redo()
                    changed(false)
                    controls()
                },
                button("重置") {
                    doc = store.defaults()
                    changed()
                    controls()
                },
            )
        )
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("${target.name.take(16)} · 预览长边 640px · 双指缩放 / 拖动 · 长按原图 · 对比分割线", 11f))
        val categories = arrayOf("基础", "HSL", "曲线", "分级", "质感", "裁切")
        val spinner =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        categories,
                    )
                setSelection(categories.indexOf(category).coerceAtLeast(0))
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(p: AdapterView<*>?) {}

                        override fun onItemSelected(
                            p: AdapterView<*>?,
                            v: View?,
                            position: Int,
                            id: Long,
                        ) {
                            category = categories[position]
                            controls()
                        }
                    }
            }
        root.addView(spinner)
        val scroll = ScrollView(this)
        val controls = column()
        editorControls = controls
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 230.dp()))
        val data = text("▸ 参数与样本记录（点击展开）", 12f)
        panel = data
        var expanded = false
        data.setOnClickListener {
            expanded = !expanded
            data.text =
                if (expanded) store.metadata(target).toString(2) + "\n" + doc.toString(2)
                else "▸ 参数与样本记录（点击展开）"
        }
        val dataScroll = ScrollView(this)
        dataScroll.addView(data)
        root.addView(dataScroll, LinearLayout.LayoutParams(-1, 65.dp()))
        root.addView(
            row(
                button("复制 JSON") {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                        ClipData.newPlainText("G23 parameters", doc.toString(2))
                    )
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                },
                button("标签 / 评分") { sceneDialog(target) },
                button("预设") { presets() },
            )
        )
        root.addView(row(button("保存草稿成片") { save(false) }, button("保存为 G23 训练样本") { save(true) }))
        controls()
        val token = generation.get()
        worker.execute {
            try {
                val original = Imaging.decode(File(target, "original.jpg"), 640)
                runOnUiThread {
                    if (token == generation.get()) {
                        previewSource = original
                        view.before = original
                        requestRender()
                    } else original.recycle()
                }
            } catch (e: Throwable) {
                runOnUiThread { error(e) }
            }
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun save(approved: Boolean) {
        val target = folder ?: return
        val snapshot = JSONObject(doc.toString())
        background("完整分辨率渲染及校验…", { store.save(target, snapshot, approved) }) {
            message(if (approved) "已确认训练样本；原图、成片和参数已配对保存。" else "草稿及成片已保存。")
        }
    }

    private fun changed(commit: Boolean = true) {
        if (commit) history?.commit(doc)
        val target = folder ?: return
        val snapshot = JSONObject(doc.toString())
        val historyText = history?.serialize() ?: return
        worker.execute {
            try {
                store.draft(target, snapshot, historyText)
            } catch (e: Throwable) {
                runOnUiThread { error(e) }
            }
        }
        requestRender()
    }

    private fun requestRender() {
        val source = previewSource ?: return
        val view = photo ?: return
        val snapshot = JSONObject(doc.toString())
        val token = generation.incrementAndGet()
        worker.execute {
            if (token != generation.get()) return@execute
            try {
                val result = Imaging.render(source, snapshot)
                val geometric = Imaging.geometry(source, snapshot)
                val aligned =
                    if (geometric === source) source.copy(Bitmap.Config.ARGB_8888, false)
                    else geometric
                runOnUiThread {
                    if (token == generation.get() && photo === view) {
                        val previous = view.after
                        val previousBefore = view.before
                        view.before = aligned
                        view.after = result
                        view.invalidate()
                        previous?.recycle()
                        if (previousBefore !== source) previousBefore?.recycle()
                    } else {
                        result.recycle()
                        aligned.recycle()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { if (token == generation.get()) error(e) }
            }
        }
    }

    private fun field(path: String): Pair<JSONObject, String> {
        val parts = path.split('.')
        var obj = doc.getJSONObject("parameters")
        parts.dropLast(1).forEach { obj = obj.getJSONObject(it) }
        return obj to parts.last()
    }

    private fun slider(
        parent: LinearLayout,
        path: String,
        min: Int = -100,
        max: Int = 100,
        scale: Double = 1.0,
    ) {
        val (obj, key) = field(path)
        val label = text("$path  ${obj.getDouble(key)}", 12f)
        parent.addView(label)
        val s = SeekBar(this)
        s.max = max - min
        s.progress = (obj.getDouble(key) / scale).toInt() - min
        parent.addView(s)
        s.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(v: SeekBar) {}

                override fun onProgressChanged(v: SeekBar, value: Int, user: Boolean) {
                    if (user && !busy) {
                        val number = (value + min) * scale
                        obj.put(key, number)
                        label.text = "$path  $number"
                        requestRender()
                    }
                }

                override fun onStopTrackingTouch(v: SeekBar) {
                    changed()
                }
            }
        )
        label.setOnClickListener {
            val (defaults, name) =
                run {
                    val previous = doc
                    doc = store.defaults()
                    val f = field(path)
                    doc = previous
                    f
                }
            obj.put(key, defaults.get(name))
            changed()
            controls()
        }
    }

    private fun controls() {
        val p = editorControls ?: return
        p.removeAllViews()
        if (!doc.has("parameters")) return
        when (category) {
            "基础" -> {
                slider(p, "exposure_ev", -50, 50, .1)
                listOf(
                        "contrast",
                        "highlights",
                        "shadows",
                        "whites",
                        "blacks",
                        "temperature_shift",
                        "tint",
                        "vibrance",
                        "saturation",
                    )
                    .forEach { slider(p, it) }
            }
            "HSL" ->
                listOf("red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta")
                    .forEach { c ->
                        listOf("hue", "saturation", "luminance").forEach { slider(p, "hsl.$c.$it") }
                    }
            "曲线" -> {
                p.addView(text("点击添加，拖动控制点；长按删除内部点。"))
                listOf("rgb", "red", "green", "blue").forEach { channel ->
                    p.addView(text(channel))
                    val curves = doc.getJSONObject("parameters").getJSONObject("tone_curves")
                    val curve =
                        CurveView(this, curves.getJSONArray(channel)) { points ->
                            curves.put(channel, points)
                            changed()
                        }
                    p.addView(curve, LinearLayout.LayoutParams(-1, 180.dp()))
                    p.addView(
                        button("重置 $channel") {
                            curves.put(channel, JSONArray("[[0,0],[1,1]]"))
                            changed()
                            controls()
                        }
                    )
                }
            }
            "分级" -> {
                listOf("shadows", "midtones", "highlights").forEach { zone ->
                    slider(p, "color_grading.$zone.hue", 0, 360)
                    slider(p, "color_grading.$zone.saturation", 0, 100)
                    slider(p, "color_grading.$zone.luminance")
                }
                slider(p, "color_grading.balance")
                slider(p, "color_grading.blending", 0, 100)
            }
            "质感" -> {
                listOf(
                        "sharpness",
                        "clarity",
                        "local_contrast",
                        "grain_amount",
                        "grain_size",
                        "vignette",
                        "fade",
                    )
                    .forEach {
                        slider(
                            p,
                            "texture.$it",
                            if (it in listOf("clarity", "local_contrast", "vignette")) -100 else 0,
                            100,
                        )
                    }
                p.addView(
                    button("固定颗粒种子") {
                        val input =
                            EditText(this).apply {
                                setText(
                                    doc.getJSONObject("parameters")
                                        .getJSONObject("texture")
                                        .getLong("grain_seed")
                                        .toString()
                                )
                                inputType = 2
                            }
                        AlertDialog.Builder(this)
                            .setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                try {
                                    val n = input.text.toString().toLong()
                                    require(n in 0..4294967295L)
                                    doc.getJSONObject("parameters")
                                        .getJSONObject("texture")
                                        .put("grain_seed", n)
                                    changed()
                                } catch (e: Exception) {
                                    error(e)
                                }
                            }
                            .show()
                    }
                )
            }
            "裁切" -> {
                val g = doc.getJSONObject("parameters").getJSONObject("geometry")
                p.addView(
                    button("顺时针旋转 90°") {
                        g.put("rotation_degrees", (g.getInt("rotation_degrees") + 90) % 360)
                        changed()
                    }
                )
                p.addView(
                    button("裁切坐标 [left,top,right,bottom]") {
                        val input =
                            EditText(this).apply {
                                setText(
                                    if (g.isNull("crop")) "[0,0,1,1]"
                                    else g.getJSONArray("crop").toString()
                                )
                            }
                        AlertDialog.Builder(this)
                            .setTitle("相对于归一化原图，旋转前裁切")
                            .setView(input)
                            .setPositiveButton("应用") { _, _ ->
                                try {
                                    val a = JSONArray(input.text.toString())
                                    require(a.length() == 4)
                                    val v = (0..3).map { a.getDouble(it) }
                                    require(v.all { it in 0.0..1.0 } && v[0] < v[2] && v[1] < v[3])
                                    g.put("crop", a)
                                    changed()
                                } catch (e: Exception) {
                                    error(e)
                                }
                            }
                            .show()
                    }
                )
                p.addView(
                    button("恢复完整画幅") {
                        g.put("crop", JSONObject.NULL).put("rotation_degrees", 0)
                        changed()
                    }
                )
            }
        }
    }

    private fun sceneDialog(target: File) {
        val meta = store.metadata(target)
        val scene = meta.getJSONObject("scene")
        val form = column()
        val lighting =
            EditText(this).apply {
                hint = "光照标签（英文逗号分隔，可多选）"
                setText(
                    scene.getJSONArray("lighting").let {
                        (0 until it.length()).joinToString(",") { i -> it.getString(i) }
                    }
                )
            }
        val subjects =
            EditText(this).apply {
                hint = "内容标签（英文逗号分隔）"
                setText(
                    scene.getJSONArray("subjects").let {
                        (0 until it.length()).joinToString(",") { i -> it.getString(i) }
                    }
                )
            }
        form.addView(
            text(
                "光照：晴天 / 阴天 / 逆光 / 室内自然光 / 室内暖光 / 夜景 / 混合光 / 其他\n内容：人像 / 建筑 / 街道 / 植物 / 天空 / 食物 / 夜景灯光 / 其他",
                11f,
            )
        )
        form.addView(lighting)
        form.addView(subjects)
        val note =
            EditText(this).apply {
                hint = "调色备注"
                setText(scene.optString("notes"))
            }
        val style =
            EditText(this).apply {
                hint = "希望强调的风格特征"
                setText(scene.optString("style_features"))
            }
        val rating =
            RatingBar(this).apply {
                numStars = 5
                stepSize = 1f
                this.rating = scene.optDouble("rating", 0.0).toFloat()
            }
        val satisfied =
            CheckBox(this).apply {
                text = "对本次调色满意"
                isChecked = scene.optBoolean("satisfied", false)
            }
        form.addView(note)
        form.addView(style)
        form.addView(rating)
        form.addView(satisfied)
        AlertDialog.Builder(this)
            .setTitle("人工标签")
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                scene
                    .put(
                        "lighting",
                        JSONArray(
                            lighting.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        ),
                    )
                    .put(
                        "subjects",
                        JSONArray(
                            subjects.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        ),
                    )
                    .put("notes", note.text.toString())
                    .put("style_features", style.text.toString())
                    .put(
                        "rating",
                        if (rating.rating > 0) rating.rating.toInt() else JSONObject.NULL,
                    )
                    .put("satisfied", satisfied.isChecked)
                background("保存标签") {
                    atomicText(
                        File(target, "metadata.json"),
                        store.metadata(target).put("scene", scene).toString(2),
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun validatePreset(data: JSONObject) {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            Imaging.render(bitmap, data).recycle()
        } finally {
            bitmap.recycle()
        }
    }

    private fun presets() {
        val files = store.presetDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        val names = files.map { JSONObject(it.readText()).getString("name") }
        val options = arrayOf("保存 / 覆盖当前命名预设", "导入 JSON", "导出当前 JSON", *names.toTypedArray())
        AlertDialog.Builder(this)
            .setTitle("参数预设")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this)
                        AlertDialog.Builder(this)
                            .setTitle("预设名称（同名覆盖）")
                            .setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                try {
                                    store.savePreset(input.text.toString(), doc)
                                } catch (e: Exception) {
                                    error(e)
                                }
                            }
                            .show()
                    }
                    1 -> importPreset.launch(arrayOf("application/json", "text/plain"))
                    2 -> exportPreset.launch("G23_preset.json")
                    else -> {
                        val file = files[which - 3]
                        AlertDialog.Builder(this)
                            .setItems(arrayOf("应用", "删除")) { _, action ->
                                if (action == 1) file.delete()
                                else
                                    try {
                                        val data =
                                            JSONObject(file.readText()).getJSONObject("document")
                                        validatePreset(data)
                                        doc = data
                                        changed()
                                        controls()
                                    } catch (e: Exception) {
                                        error(e)
                                    }
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun datasetPage() {
        provider?.unbindAll()
        leaveEditor()
        page("G23 Lab / Dataset")
        nav()
        val selected = mutableSetOf<File>()
        val status =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        arrayOf("all", "approved", "draft"),
                    )
            }
        root.addView(status)
        val filter =
            EditText(this).apply {
                hint = "场景 / 内容标签"
                setTextColor(Color.WHITE)
            }
        root.addView(filter)
        val deviceFilter =
            EditText(this).apply {
                hint = "设备型号"
                setTextColor(Color.WHITE)
            }
        val dateFilter =
            EditText(this).apply {
                hint = "拍摄日期 YYYY-MM-DD（可填年月前缀）"
                setTextColor(Color.WHITE)
            }
        root.addView(deviceFilter)
        root.addView(dateFilter)
        val grid = GridLayout(this).apply { columnCount = 2 }
        val scroll = ScrollView(this)
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var pageIndex = 0
        val pageLabel = text("")
        fun matching() =
            store.all().filter {
                val m = store.metadata(it)
                (status.selectedItem == "all" || m.getString("status") == status.selectedItem) &&
                    m.getJSONObject("scene")
                        .toString()
                        .contains(filter.text.toString(), ignoreCase = true) &&
                    m.getJSONObject("capture")
                        .optString("device_model")
                        .contains(deviceFilter.text.toString(), ignoreCase = true) &&
                    m.getJSONObject("capture")
                        .optString("captured_at")
                        .startsWith(dateFilter.text.toString())
            }

        fun refresh(reset: Boolean = true) {
            if (reset) {
                selected.clear()
                pageIndex = 0
            }
            grid.removeAllViews()
            val matches = matching()
            val pages = maxOf(1, (matches.size + 35) / 36)
            pageIndex = pageIndex.coerceIn(0, pages - 1)
            pageLabel.text = "${pageIndex+1} / $pages · ${matches.size} 条 · 已选 ${selected.size}"
            matches.drop(pageIndex * 36).take(36).forEach { target ->
                val meta = store.metadata(target)
                val cell = column()
                val thumbnail =
                    File(target, "thumbnail_after.jpg").takeIf { it.exists() }
                        ?: File(target, "original.jpg")
                val bitmap =
                    if (thumbnail.name == "original.jpg") Imaging.decode(thumbnail, 240)
                    else BitmapFactory.decodeFile(thumbnail.path)
                val image =
                    ImageView(this).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                cell.addView(image, LinearLayout.LayoutParams(-1, 110.dp()))
                val check =
                    CheckBox(this).apply {
                        text = "${target.name.take(12)}\n${meta.getString("status")}"
                        isChecked = selected.contains(target)
                        setTextColor(Color.WHITE)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(target) else selected.remove(target)
                        }
                    }
                cell.addView(check)
                cell.addView(
                    row(
                        button("编辑") { openEditor(target) },
                        button("对照") {
                            val pair = column()
                            listOf("original.jpg", "edited.png").forEach { n ->
                                if (File(target, n).exists()) {
                                    val v = ImageView(this)
                                    v.setImageBitmap(Imaging.decode(File(target, n), 640))
                                    v.adjustViewBounds = true
                                    pair.addView(v)
                                }
                            }
                            AlertDialog.Builder(this)
                                .setView(pair)
                                .setPositiveButton("关闭", null)
                                .show()
                        },
                    )
                )
                grid.addView(
                    cell,
                    GridLayout.LayoutParams().apply {
                        width = resources.displayMetrics.widthPixels / 2 - 24
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    },
                )
            }
        }
        root.addView(
            row(
                button("筛选 / 刷新") { refresh() },
                button("全选筛选结果") {
                    selected.addAll(matching())
                    for (i in 0 until grid.childCount) {
                        val cell = grid.getChildAt(i) as LinearLayout
                        (cell.getChildAt(1) as CheckBox).isChecked = true
                    }
                },
                button("删除所选") {
                    AlertDialog.Builder(this)
                        .setMessage("永久删除所选 ${selected.size} 条样本及原图？")
                        .setPositiveButton("删除") { _, _ ->
                            selected.toList().forEach { it.deleteRecursively() }
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
            )
        )
        root.addView(
            row(
                button("上一页") {
                    pageIndex--
                    refresh(false)
                },
                pageLabel,
                button("下一页") {
                    pageIndex++
                    refresh(false)
                },
            )
        )
        val approved =
            CheckBox(this).apply {
                text = "仅导出 approved"
                isChecked = true
                setTextColor(Color.WHITE)
            }
        val gps =
            CheckBox(this).apply {
                text = "导出时移除 GPS（本机原图不变）"
                isChecked = true
                setTextColor(Color.WHITE)
            }
        root.addView(approved)
        root.addView(gps)
        val progress = text("")
        root.addView(progress)
        root.addView(
            button("导出所选 ZIP / 系统分享") {
                val targets = selected.toList()
                val onlyApproved = approved.isChecked
                val removeGps = gps.isChecked
                var output: File? = null
                background(
                    "正在校验及导出；进度见页面…",
                    {
                        output =
                            DatasetExport.export(store, targets, onlyApproved, removeGps) { n, t ->
                                runOnUiThread { progress.text = "导出 $n / $t" }
                            }
                    },
                ) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", output!!)
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "保存 / 分享 G23 数据集",
                        )
                    )
                }
            }
        )
        refresh()
    }
}
