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
import androidx.activity.addCallback
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
    private var previewLut: CubeLut? = null
    private var pickerWheel: FilmWheelView? = null
    private var fullPhoto: FullPhotoView? = null
    private var albumPhotos = listOf<File>()
    private val generation = AtomicInteger(0)
    private var panel: TextView? = null
    private var screen = ""
    private var category = "基础"
    private var editorControls: LinearLayout? = null
    private var cameraLayout: CameraLayout? = null
    private var capabilityText = "正在连接相机"
    private var pendingExport: File? = null
    private val preferences by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private val saveArchive =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri
            ->
            finishFileSave(uri)
        }
    private val saveAnalysis =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri
            ->
            finishFileSave(uri)
        }

    private fun finishFileSave(uri: android.net.Uri?) {
        val file = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        background(
            "正在保存文件",
            {
                contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            },
        ) {
            AlertDialog.Builder(this)
                .setTitle("文件已保存")
                .setMessage("${file.name}\n已保存到你选择的位置，可在系统文件管理器查看。")
                .setPositiveButton("完成", null)
                .setNeutralButton("发送文件") { _, _ ->
                    val shared = FileProvider.getUriForFile(this, "$packageName.files", file)
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType(
                                    if (file.extension == "zip") "application/zip"
                                    else "application/json"
                                )
                                .putExtra(Intent.EXTRA_STREAM, shared)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "发送分析文件",
                        )
                    )
                }
                .show()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("pendingExport", pendingExport?.path)
    }

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
                        preset.optString("name", "导入预设 ${System.currentTimeMillis()}"),
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
        pendingExport = state?.getString("pendingExport")?.let { File(it) }
        rawRequested = preferences.getBoolean("raw", false)
        ratio = preferences.getString("ratio", "4:3") ?: "4:3"
        window.statusBarColor = LabUi.background
        window.navigationBarColor = LabUi.background
        onBackPressedDispatcher.addCallback(this) {
            if (!busy)
                when (screen) {
                    "LUT" -> finishLutSelection()
                    "Photo" -> datasetPage()
                    "Dataset",
                    "Editor" -> cameraPage()
                    else -> finish()
                }
        }
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

    private fun column() = LabUi.column(this)

    private fun text(s: String, size: Float = 14f) =
        LabUi.text(this, s, size).apply { setPadding(0, 6.dp(), 0, 6.dp()) }

    private fun button(s: String, action: () -> Unit) =
        LabUi.button(this, s) {
            if (!busy)
                try {
                    action()
                } catch (e: Exception) {
                    error(e)
                }
        }

    private fun row(vararg views: View) = LabUi.weightedRow(this, *views)

    private fun page(title: String) {
        fullPhoto?.let {
            val old = it.bitmap
            it.bitmap = null
            old?.recycle()
        }
        fullPhoto = null
        if (title != "LUT") pickerWheel = null
        screen = title
        generation.incrementAndGet()
        root = column().apply { setBackgroundColor(LabUi.background) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val safe = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(16.dp(), safe.top, 16.dp(), safe.bottom + 8.dp())
            ins
        }
        root.requestApplyInsets()
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
                    if (!isDestroyed) {
                        if (screen == "Camera" && capture != null) cameraLayout?.ready()
                        error(e)
                    }
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
        previewLut = null
        worker.execute {
            if (before !== old) before?.recycle()
            after?.recycle()
            old?.recycle()
        }
    }

    private fun cameraPage() {
        leaveEditor()
        page("Camera")
        capture = null
        val grid = GridOverlay(this).apply { gridVisible = preferences.getBoolean("grid", true) }
        val layout =
            CameraLayout(
                this,
                onLut = { if (!busy) lutPickerPage() },
                onShoot = { if (!busy) shoot() },
                onAlbum = { if (!busy) datasetPage() },
                onFlip = {
                    if (!busy) {
                        front = !front
                        cameraPage()
                    }
                },
                onSettings = { if (!busy) cameraSettings(grid) },
                onFlash = {
                    if (!busy) {
                        if (camera?.cameraInfo?.hasFlashUnit() != true) message("当前摄像头没有闪光灯")
                        else {
                            flash = !flash
                            cameraLayout?.setFlash(flash)
                            capture?.flashMode =
                                if (flash) ImageCapture.FLASH_MODE_ON
                                else ImageCapture.FLASH_MODE_OFF
                            Toast.makeText(
                                    this,
                                    if (flash) "闪光灯已开启" else "闪光灯已关闭",
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                },
                onGrid = {
                    grid.gridVisible = !grid.gridVisible
                    preferences.edit().putBoolean("grid", grid.gridVisible).apply()
                    grid.invalidate()
                },
                onRatio = {
                    if (!busy) {
                        ratio = it
                        preferences.edit().putString("ratio", it).apply()
                        cameraPage()
                    }
                },
            )
        cameraLayout = layout
        root.addView(layout, LinearLayout.LayoutParams(-1, -1))
        layout.setLutName(store.looks.selected().name)
        layout.selectedRatio = ratio
        layout.setFlash(flash)
        layout.exposurePanel.visibility =
            if (preferences.getBoolean("exposure", false)) View.VISIBLE else View.GONE
        val seek = layout.exposureSlider
        val ev = layout.exposureLabel
        store.photos().firstOrNull()?.let { target ->
            worker.execute {
                runCatching {
                        Imaging.decode(
                            File(target, "thumbnail_after.jpg").takeIf { it.isFile }
                                ?: File(target, "original.jpg"),
                            120,
                        )
                    }
                    .onSuccess { bitmap ->
                        runOnUiThread {
                            if (cameraLayout === layout && screen == "Camera") {
                                layout.thumbnail.setImageBitmap(bitmap)
                                layout.thumbnail.visibility = View.VISIBLE
                            } else bitmap.recycle()
                        }
                    }
            }
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            layout.status("允许相机权限，开始拍摄\n照片与数据保存在本机")
            val allow = button("允许使用相机") { permission.launch(Manifest.permission.CAMERA) }
            layout.viewfinder.addView(
                allow,
                FrameLayout.LayoutParams(-2, 48.dp(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    .apply { bottomMargin = 32.dp() },
            )
            return
        }
        val view =
            PreviewView(this).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        layout.viewfinder.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        layout.viewfinder.addView(grid, 1, FrameLayout.LayoutParams(-1, -1))
        layout.status("正在连接相机")
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val action =
                    FocusMeteringAction.Builder(
                            view.meteringPointFactory.createPoint(event.x, event.y)
                        )
                        .build()
                camera?.let {
                    if (it.cameraInfo.isFocusMeteringSupported(action))
                        it.cameraControl.startFocusAndMetering(action)
                }
                view.performClick()
            }
            true
        }
        val request = ProcessCameraProvider.getInstance(this)
        request.addListener(
            {
                if (screen != "Camera" || cameraLayout !== layout || isDestroyed) return@addListener
                try {
                    provider = request.get()
                    provider!!.unbindAll()
                    val select =
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                    require(provider!!.hasCamera(select)) { "未发现所选摄像头" }
                    val preview =
                        Preview.Builder()
                            .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
                            .build()
                            .apply { setSurfaceProvider(view.surfaceProvider) }
                    rawSupported =
                        ImageCapture.getImageCaptureCapabilities(provider!!.getCameraInfo(select))
                            .supportedOutputFormats
                            .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                    rawActive = rawRequested && rawSupported
                    capture =
                        ImageCapture.Builder()
                            .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
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
                                .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
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
                    capabilityText =
                        "摄像头编号：${camera2.cameraId}\n闪光灯：${if(info.hasFlashUnit())"支持" else "不支持"}\n原始感光数据：${if(raw)"硬件支持" else "硬件不支持"}\n原始数据与照片同拍：${if(rawSupported)"支持" else "不支持"}\n当前格式：${if(rawActive)"原始数据 + 高画质照片" else "高画质照片"}"
                    layout.captureLabel.text = if (rawActive) "原始数据 + 高画质照片" else "高画质拍摄 · 原图始终保留"
                    layout.ready()
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
                                        "曝光补偿 ${(v+range.lower)*info.exposureState.exposureCompensationStep.toFloat()} 档"
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    layout.status("相机连接失败，可打开相册或在设置中重试。")
                    error(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun cameraSettings(grid: GridOverlay) {
        val body = column().apply { setPadding(24.dp(), 8.dp(), 24.dp(), 8.dp()) }
        fun toggle(label: String, checked: Boolean, action: (Boolean) -> Unit) {
            body.addView(
                Switch(this).apply {
                    text = label
                    setTextColor(LabUi.foreground)
                    textSize = 14f
                    minHeight = 52.dp()
                    isChecked = checked
                    setOnCheckedChangeListener { _, v -> action(v) }
                }
            )
        }
        toggle("构图网格", grid.gridVisible) {
            grid.gridVisible = it
            grid.invalidate()
            preferences.edit().putBoolean("grid", it).apply()
        }
        toggle("显示曝光补偿", preferences.getBoolean("exposure", false)) {
            preferences.edit().putBoolean("exposure", it).apply()
            cameraLayout?.exposurePanel?.visibility = if (it) View.VISIBLE else View.GONE
        }
        toggle("同时保存原始感光数据（设备支持时）", rawRequested) {
            rawRequested = it
            preferences.edit().putBoolean("raw", it).apply()
        }
        toggle("导出时移除定位信息", preferences.getBoolean("stripGps", false)) {
            preferences.edit().putBoolean("stripGps", it).apply()
        }
        body.addView(text("设备能力", 13f).apply { setTextColor(LabUi.accent) })
        body.addView(text(capabilityText, 12f))
        body.addView(text("画幅选择采用无损参数裁切，原图始终保留。照片保存在应用相册内；一键导出可另存到系统文件。", 12f))
        val settingsDialog =
            AlertDialog.Builder(this)
                .setTitle("相机设置")
                .setView(ScrollView(this).apply { addView(body) })
                .setPositiveButton("完成") { _, _ -> cameraPage() }
                .create()
        body.addView(text("照片工具", 13f).apply { setTextColor(LabUi.accent) })
        body.addView(
            button("最近照片的调色与数据") {
                settingsDialog.dismiss()
                store.all().firstOrNull()?.let { openEditor(it) } ?: message("请先拍摄一张照片")
            }
        )
        body.addView(
            button("导出全部照片与分析") {
                settingsDialog.dismiss()
                exportFiles(store.all())
            }
        )
        settingsDialog.show()
    }

    private fun lutPickerPage() {
        provider?.unbindAll()
        camera = null
        capture = null
        leaveEditor()
        page("LUT")
        val items = store.looks.items
        val selected = store.looks.selected()
        val wheel =
            FilmWheelView(this, items, items.indexOfFirst { it.id == selected.id }.coerceAtLeast(0))
        pickerWheel = wheel
        root.addView(LabUi.header(this, "胶卷色调", "你的 ${items.size} 款 LUT", { finishLutSelection() }))
        root.addView(wheel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            LabUi.label(this, "上下拨动 · 自动吸附 · 箭头指向当前选择").apply {
                gravity = Gravity.CENTER
                setPadding(0, 12.dp(), 0, 16.dp())
            }
        )
        root.addView(LabUi.button(this, "使用并返回拍摄", true) { finishLutSelection() })
    }

    private fun finishLutSelection() {
        pickerWheel?.finishSelection()?.let { store.looks.select(it) }
        cameraPage()
    }

    private fun shoot() {
        val output = capture ?: return
        val captureLook = store.looks.selected()
        val captureRatio = ratio
        val target = store.newCapture()
        busy = true
        cameraLayout?.status("正在拍摄")
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
                        "正在应用 ${captureLook.name} 胶卷色调",
                        {
                            store.initialize(target, id)
                            store.looks.attach(target, captureLook)
                            val image = Imaging.decode(File(target, "original.jpg"), 1024)
                            val aspect =
                                when (captureRatio) {
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
                            store.save(target, params, false)
                        },
                    ) {
                        cameraPage()
                        Toast.makeText(
                                this@MainActivity,
                                "已保存 · ${captureLook.name}",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
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
        page("Editor")
        val view = PhotoView(this)
        photo = view
        root.addView(
            LabUi.header(
                this,
                "影像调色",
                target.name.take(23),
                { datasetPage() },
                LabUi.iconButton(this, LabIcon.Kind.EXPORT, "一键导出") { exportFiles(listOf(target)) },
            )
        )
        val tools = LabUi.row(this)
        fun tool(kind: LabIcon.Kind, label: String, action: () -> Unit) {
            tools.addView(
                LabUi.iconButton(this, kind, label) { if (!busy) action() },
                LinearLayout.LayoutParams(0, 44.dp(), 1f),
            )
        }
        tool(LabIcon.Kind.UNDO, "撤销") {
            doc = history!!.undo()
            changed(false)
            controls()
        }
        tool(LabIcon.Kind.REDO, "重做") {
            doc = history!!.redo()
            changed(false)
            controls()
        }
        tool(LabIcon.Kind.RESET, "重置调色") {
            doc = store.defaults()
            changed()
            controls()
        }
        tool(LabIcon.Kind.COMPARE, "切换并列与滑动对比") {
            view.compare = !view.compare
            view.invalidate()
        }
        tool(LabIcon.Kind.DATA, "并列数据") { showData(target, JSONObject(doc.toString())) }
        root.addView(tools)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            LabUi.label(this, "双指缩放 · 拖动查看 · 长按原图").apply {
                gravity = Gravity.CENTER
                setPadding(0, 6.dp(), 0, 6.dp())
            }
        )
        val categories = listOf("基础", "分色", "曲线", "分级", "质感", "裁切")
        val tabs = LabUi.row(this)
        fun updateTabs() {
            for (i in 0 until tabs.childCount) (tabs.getChildAt(i) as TextView).setTextColor(
                if (categories[i] == category) LabUi.accent else LabUi.muted
            )
        }
        categories.forEach { item ->
            tabs.addView(
                LabUi.text(this, item, 13f).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        category = item
                        updateTabs()
                        controls()
                    }
                },
                LinearLayout.LayoutParams(0, 44.dp(), 1f),
            )
        }
        root.addView(tabs)
        updateTabs()
        val scroll =
            ScrollView(this).apply {
                isFillViewport = false
                background = LabUi.shape(LabUi.surface, 16.dp())
                clipToOutline = true
            }
        editorControls = column().apply { setPadding(16.dp(), 8.dp(), 16.dp(), 12.dp()) }
        scroll.addView(editorControls)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                (resources.configuration.screenHeightDp * .18f).toInt().coerceIn(96, 144).dp(),
            ),
        )
        val actions = LabUi.row(this)
        fun contextAction(label: String, action: () -> Unit) {
            actions.addView(
                LabUi.text(this, label, 13f, LabUi.muted).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { if (!busy) action() }
                },
                LinearLayout.LayoutParams(0, 44.dp(), 1f),
            )
        }
        contextAction("预设") { presets() }
        contextAction("标签") { sceneDialog(target) }
        contextAction("并列数据") { showData(target, JSONObject(doc.toString())) }
        root.addView(actions)
        val saveButton = button("保存成片") { save(false) }
        saveButton.setOnLongClickListener {
            if (!busy) save(true)
            true
        }
        saveButton.contentDescription = "保存成片；长按确认为训练样本"
        root.addView(
            row(saveButton, LabUi.button(this, "一键导出", true) { exportFiles(listOf(target)) })
        )
        controls()
        val token = generation.get()
        worker.execute {
            try {
                val lut = store.looks.forPhoto(target)
                val original = Imaging.decode(File(target, "original.jpg"), 640)
                runOnUiThread {
                    if (token == generation.get()) {
                        previewLut = lut
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
        val lut = previewLut
        val token = generation.incrementAndGet()
        worker.execute {
            if (token != generation.get()) return@execute
            try {
                val result = Imaging.render(source, snapshot, lut)
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
        val label =
            text("${ParameterLabels.name(path)}  ${ParameterLabels.value(obj.get(key))}", 12f)
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
                        label.text =
                            "${ParameterLabels.name(path)}  ${ParameterLabels.value(number)}"
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
            "分色" ->
                listOf("red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta")
                    .forEach { c ->
                        listOf("hue", "saturation", "luminance").forEach { slider(p, "hsl.$c.$it") }
                    }
            "曲线" -> {
                p.addView(text("点击添加，拖动控制点；长按删除内部点。"))
                listOf("rgb", "red", "green", "blue").forEach { channel ->
                    p.addView(text(ParameterLabels.name(channel)))
                    val curves = doc.getJSONObject("parameters").getJSONObject("tone_curves")
                    val curve =
                        CurveView(this, curves.getJSONArray(channel)) { points ->
                            curves.put(channel, points)
                            changed()
                        }
                    p.addView(curve, LinearLayout.LayoutParams(-1, 180.dp()))
                    p.addView(
                        button("重置${ParameterLabels.name(channel)}") {
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
                    button("裁切范围：左、上、右、下") {
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

    private fun showData(target: File, snapshot: JSONObject) {
        val before = store.originalParameters(target)
        val analysis =
            runCatching {
                    store.comparison(target)?.also {
                        AnalysisData.validate(it, target.name, before, snapshot, store.look(target))
                    }
                }
                .getOrNull()
        val body = column()
        val panel =
            ComparisonPanel.create(
                this,
                target.name,
                store.metadata(target).getJSONObject("capture"),
                before,
                snapshot,
                analysis,
                store.look(target),
            )
        body.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions =
            row(
                button("复制参数") {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                        ClipData.newPlainText(
                            "并列参数",
                            ParameterLabels.pairs(before, snapshot).toString(2),
                        )
                    )
                    Toast.makeText(this, "已复制原始与调色参数", Toast.LENGTH_SHORT).show()
                },
                button("保存分析文件") { exportFiles(listOf(target), true) },
            )
        actions.setPadding(16.dp(), 8.dp(), 16.dp(), 16.dp())
        body.addView(actions)
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("原图与调色数据")
                .setView(body)
                .setPositiveButton("关闭", null)
                .create()
        dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .88).toInt())
    }

    private fun exportFiles(targets: List<File>, jsonOnly: Boolean = false) {
        if (busy) return
        if (targets.isEmpty()) {
            message("当前没有可导出的照片，请先拍摄。")
            return
        }
        val current = folder
        val currentSnapshot = if (screen == "Editor") JSONObject(doc.toString()) else null
        val removeGps = preferences.getBoolean("stripGps", false)
        var output: File? = null
        background(
            "正在生成原图、成片与并列分析数据",
            {
                targets.forEach { target ->
                    val snapshot =
                        if (target == current && currentSnapshot != null) currentSnapshot
                        else store.parameters(target)
                    if (!store.isRendered(target, snapshot)) {
                        val approved =
                            store.metadata(target).getString("status") == "approved" &&
                                canonicalJson(store.parameters(target)) == canonicalJson(snapshot)
                        store.save(target, snapshot, approved)
                    }
                }
                output =
                    if (jsonOnly) {
                        val target = targets.single()
                        val directory = File(cacheDir, "exports").apply { mkdirs() }
                        File(directory, "${target.name}_analysis.json").apply {
                            writeText(
                                store
                                    .comparison(target)!!
                                    .put("metadata", store.metadata(target))
                                    .toString(2)
                            )
                        }
                    } else DatasetExport.export(store, targets, false, removeGps) { _, _ -> }
            },
        ) {
            pendingExport = output
            if (jsonOnly) saveAnalysis.launch(output!!.name)
            else saveArchive.launch("G23_影像分析_${targets.size}张.zip")
        }
    }

    private fun datasetPage() {
        provider?.unbindAll()
        leaveEditor()
        page("Dataset")
        root.addView(LabUi.header(this, "相册", back = { cameraPage() }))
        val gallery = PhotoGallery(this, worker) { index -> photoPage(albumPhotos, index) }
        root.addView(gallery, LinearLayout.LayoutParams(-1, 0, 1f))
        val token = generation.get()
        worker.execute {
            val folders = store.photos()
            runOnUiThread {
                if (screen == "Dataset" && token == generation.get()) {
                    albumPhotos = folders
                    gallery.submit(folders)
                    if (folders.isEmpty())
                        Toast.makeText(this, "还没有照片，拍下你的第一卷", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun photoPage(photos: List<File>, index: Int) {
        if (index !in photos.indices) return
        page("Photo")
        root.addView(LabUi.header(this, "照片", back = { datasetPage() }))
        val view = FullPhotoView(this) { direction -> photoPage(photos, index + direction) }
        fullPhoto = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val token = generation.get()
        val target = photos[index]
        worker.execute {
            runCatching {
                    Imaging.decode(
                        File(target, "edited.png").takeIf { it.isFile }
                            ?: File(target, "original.jpg"),
                        2048,
                    )
                }
                .onSuccess { bitmap ->
                    runOnUiThread {
                        if (screen == "Photo" && token == generation.get() && fullPhoto === view)
                            view.bitmap = bitmap
                        else bitmap.recycle()
                    }
                }
                .onFailure { runOnUiThread { if (token == generation.get()) error(it) } }
        }
    }
}
