package lab.g23

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.*
import android.util.Rational
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import lab.g23.LabUi.dp

class MainActivity : ComponentActivity() {
    private lateinit var store: PhotoStore
    private lateinit var root: LinearLayout
    private val io = Executors.newFixedThreadPool(2)
    private val generation = AtomicInteger()
    private val queries = AtomicInteger()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var front = false
    private var flash = false
    private var ratio = "4:3"
    private var captureInFlight = false
    private var screen = ""
    private var pickerWheel: FilmWheelView? = null
    private var fullPhoto: FullPhotoView? = null
    private var gallery: PhotoGallery? = null
    private var emptyAlbum: TextView? = null
    private var albumPhotos = emptyList<CameraPhoto>()
    private var currentPhoto: CameraPhoto? = null
    private var photoKey = ""
    private var cameraLayout: CameraLayout? = null
    private var thumbnail: Bitmap? = null
    private var thumbnailKey = ""
    private var pendingSave: File? = null
    private val preferences by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private val observer: () -> Unit = {
        when (screen) {
            "Camera" -> cameraLayout?.let { refreshThumbnail(it) }
            "Dataset" -> refreshAlbum()
            "Photo" -> refreshPhoto()
        }
    }
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) cameraPage() else message("请在系统设置中允许相机权限，即可拍摄照片。")
        }
    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) {
            finishDocumentSave(it)
        }
    private val savePngDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
            finishDocumentSave(it)
        }

    private fun finishDocumentSave(uri: android.net.Uri?) {
        val source = pendingSave
        pendingSave = null
        if (uri == null || source == null) return
        io.execute {
            try {
                contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                toast("图片已保存")
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = PhotoStore.get(this)
        ratio =
            preferences.getString("ratio", "4:3")?.takeIf { it in listOf("4:3", "3:2", "1:1") }
                ?: "4:3"
        pendingSave = state?.getString("pendingSave")?.let { File(it) }
        window.statusBarColor = LabUi.background
        window.navigationBarColor = LabUi.background
        onBackPressedDispatcher.addCallback(this) {
            if (!captureInFlight)
                when (screen) {
                    "LUT" -> finishLutSelection()
                    "Photo" -> datasetPage()
                    "Dataset" -> cameraPage()
                    else -> finish()
                }
        }
        io.execute { runCatching { store.recover() }.onFailure { showError(it) } }
        cameraPage()
    }

    override fun onStart() {
        super.onStart()
        store.observe(observer)
        observer()
    }

    override fun onStop() {
        store.removeObserver(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("pendingSave", pendingSave?.path)
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        provider?.unbindAll()
        io.shutdown()
        super.onDestroy()
    }

    private fun column() = LabUi.column(this)

    private fun Int.dp() = this@MainActivity.dp(this)

    private fun text(s: String, size: Float = 14f) =
        LabUi.text(this, s, size).apply { setPadding(0, 6.dp(), 0, 6.dp()) }

    private fun button(s: String, action: () -> Unit) =
        LabUi.button(this, s) { if (!captureInFlight) action() }

    private fun message(s: String) {
        if (!isFinishing && !isDestroyed)
            AlertDialog.Builder(this).setMessage(s).setPositiveButton("确定", null).show()
    }

    private fun error(e: Throwable) = message(e.message ?: "操作未完成，请重试")

    private fun showError(e: Throwable) {
        runOnUiThread { error(e) }
    }

    private fun toast(s: String) {
        runOnUiThread { if (!isDestroyed) Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    private fun page(title: String) {
        generation.incrementAndGet()
        photoKey = ""
        currentPhoto = null
        gallery = null
        emptyAlbum = null
        fullPhoto?.replace(null, null)
        fullPhoto = null
        if (title != "LUT") pickerWheel = null
        if (title != "Camera") {
            provider?.unbindAll()
            camera = null
            capture = null
        }
        cameraLayout = null
        thumbnailKey = ""
        screen = title
        root = column().apply { setBackgroundColor(LabUi.background) }
        setContentView(root)
        thumbnail?.recycle()
        thumbnail = null
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val safe = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(16.dp(), safe.top, 16.dp(), safe.bottom + 8.dp())
            ins
        }
        root.requestApplyInsets()
    }

    private fun cameraPage() {
        page("Camera")
        capture = null
        val grid = GridOverlay(this).apply { gridVisible = preferences.getBoolean("grid", true) }
        val layout =
            CameraLayout(
                this,
                onLut = { if (!captureInFlight) lutPickerPage() },
                onShoot = { if (!captureInFlight) shoot() },
                onAlbum = { if (!captureInFlight) datasetPage() },
                onFlip = {
                    if (!captureInFlight) {
                        front = !front
                        cameraPage()
                    }
                },
                onSettings = { if (!captureInFlight) cameraSettings(grid) },
                onFlash = {
                    if (!captureInFlight) {
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
                    if (!captureInFlight) {
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
        refreshThumbnail(layout)
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            layout.status("允许相机权限，开始拍摄\n照片保存在本机相册")
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
                    capture =
                        ImageCapture.Builder()
                            .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setJpegQuality(100)
                            .setFlashMode(
                                if (flash) ImageCapture.FLASH_MODE_ON
                                else ImageCapture.FLASH_MODE_OFF
                            )
                            .build()
                    camera = provider!!.bindToLifecycle(this, select, preview, capture)
                    val info = camera!!.cameraInfo
                    layout.captureLabel.text = "留住此刻 · 原片与胶片"
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
        body.addView(text("照片默认保存在应用相册。打开照片，点保存可选择原片或胶片，另存到系统相册。", 12f))
        body.addView(text("版本 ${BuildConfig.VERSION_NAME}", 12f))
        AlertDialog.Builder(this)
            .setTitle("相机设置")
            .setView(body)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun lutPickerPage() {
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
        if (captureInFlight) return
        val target =
            try {
                store.newCapture(store.looks.selected(), ratio)
            } catch (e: Exception) {
                error(e)
                return
            }
        captureInFlight = true
        cameraLayout?.shutter?.isEnabled = false
        val parts = ratio.split(":").map { it.toInt() }
        val landscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        output.targetRotation = window.decorView.display?.rotation ?: Surface.ROTATION_0
        output.setCropAspectRatio(
            if (landscape) Rational(parts[0], parts[1]) else Rational(parts[1], parts[0])
        )
        val repository = store
        output.takePicture(
            ImageCapture.OutputFileOptions.Builder(File(target, "original.jpg")).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    // The callback must finish registering the job even if the Activity was
                    // destroyed.
                    try {
                        repository.captured(target.name)
                    } catch (e: Exception) {
                        showError(e)
                    }
                    captureInFlight = false
                    if (!isDestroyed && screen == "Camera") cameraLayout?.ready()
                }

                override fun onError(e: ImageCaptureException) {
                    runCatching { repository.captureFailed(target.name) }
                    captureInFlight = false
                    if (!isDestroyed) {
                        cameraLayout?.ready()
                        error(e)
                    }
                    // Preserve any delivered JPEG; remove only an empty, failed capture.
                }
            },
        )
    }

    private fun refreshThumbnail(layout: CameraLayout) {
        if (io.isShutdown) return
        val token = generation.get()
        val query = queries.incrementAndGet()
        io.execute {
            val photo = store.photos().firstOrNull() ?: return@execute
            val file = photo.thumbnail
            val key = "${file.path}:${file.lastModified()}:${photo.state}"
            if (key == thumbnailKey) return@execute
            runCatching { Imaging.decode(file, 120) }
                .onSuccess { bitmap ->
                    runOnUiThread {
                        if (
                            token == generation.get() &&
                                query == queries.get() &&
                                screen == "Camera" &&
                                cameraLayout === layout
                        ) {
                            val old = thumbnail
                            thumbnail = bitmap
                            thumbnailKey = key
                            layout.thumbnail.setImageBitmap(bitmap)
                            layout.thumbnail.visibility = View.VISIBLE
                            old?.recycle()
                        } else bitmap.recycle()
                    }
                }
        }
    }

    private fun datasetPage() {
        page("Dataset")
        root.addView(LabUi.header(this, "相册", back = { cameraPage() }))
        val frame = FrameLayout(this)
        val grid =
            PhotoGallery(
                this,
                io,
                { index -> albumPhotos.getOrNull(index)?.let { photoPage(it) } },
                { confirmDelete(it) },
            )
        gallery = grid
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        emptyAlbum =
            text("还没有照片\n拍下第一张胶片吧", 15f).apply {
                gravity = Gravity.CENTER
                setTextColor(LabUi.muted)
            }
        frame.addView(emptyAlbum, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshAlbum()
    }

    private fun refreshAlbum() {
        if (io.isShutdown) return
        val token = generation.get()
        val query = queries.incrementAndGet()
        io.execute {
            val photos = store.photos()
            runOnUiThread {
                if (token == generation.get() && query == queries.get() && screen == "Dataset") {
                    albumPhotos = photos
                    gallery?.submit(photos)
                    emptyAlbum?.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun photoPage(photo: CameraPhoto) {
        page("Photo")
        currentPhoto = photo
        root.addView(
            LabUi.header(
                this,
                "照片",
                back = { datasetPage() },
                action =
                    LabUi.iconButton(this, LabIcon.Kind.DELETE, "删除照片") {
                        currentPhoto?.let { confirmDelete(it) }
                    },
            )
        )
        val view =
            FullPhotoView(this) { delta ->
                val token = generation.get()
                val id = currentPhoto?.id
                io.execute {
                    val photos = store.photos()
                    val index = photos.indexOfFirst { it.id == id }
                    val next = photos.getOrNull(index + delta)
                    runOnUiThread {
                        if (token == generation.get() && screen == "Photo" && next != null)
                            photoPage(next)
                    }
                }
            }
        fullPhoto = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            LabUi.label(this, "拖动竖线 · 原片与胶片").apply {
                gravity = Gravity.CENTER
                minHeight = 36.dp()
            }
        )
        root.addView(LabUi.button(this, "保存图片", true) { currentPhoto?.let { chooseSave(it) } })
        refreshPhoto()
    }

    private fun refreshPhoto() {
        val id = currentPhoto?.id ?: return
        if (io.isShutdown) return
        val token = generation.get()
        val query = queries.incrementAndGet()
        val view = fullPhoto ?: return
        io.execute {
            val photo = store.photo(id) ?: return@execute
            val key = "${photo.id}:${photo.state}:${photo.film?.lastModified()}"
            if (key == photoKey) return@execute
            var original: Bitmap? = null
            var film: Bitmap? = null
            try {
                val source = Imaging.decode(photo.original, 1600)
                original =
                    if (photo.ratio == "legacy" && photo.film != null) {
                        val params = File(photo.folder, "parameters.json")
                        if (params.isFile)
                            Imaging.geometry(source, org.json.JSONObject(params.readText()))
                        else source
                    } else FilmProcessor.crop(source, photo.ratio)
                if (original !== source) source.recycle()
                film = photo.film?.let { Imaging.decode(it, 1600) }
                val before = original
                val after = film
                runOnUiThread {
                    if (
                        token == generation.get() &&
                            query == queries.get() &&
                            screen == "Photo" &&
                            currentPhoto?.id == id
                    ) {
                        currentPhoto = photo
                        photoKey = key
                        view.replace(before, after)
                        if (photo.developing) (root.getChildAt(2) as TextView).text = "冲洗中 · 可先保存原片"
                        else if (photo.state == "failed") {
                            (root.getChildAt(2) as TextView).text = "冲洗未完成 · 点此重新冲洗"
                            root.getChildAt(2).setOnClickListener {
                                io.execute {
                                    runCatching { store.retry(id) }.onFailure { showError(it) }
                                }
                            }
                        } else {
                            (root.getChildAt(2) as TextView).text = "拖动竖线 · 原片与胶片"
                            root.getChildAt(2).setOnClickListener(null)
                        }
                    } else {
                        before?.recycle()
                        after?.recycle()
                    }
                }
            } catch (e: Exception) {
                original?.recycle()
                film?.recycle()
                runOnUiThread {
                    if (
                        token == generation.get() &&
                            query == queries.get() &&
                            screen == "Photo" &&
                            store.photo(id) != null
                    )
                        error(e)
                }
            }
        }
    }

    private fun confirmDelete(photo: CameraPhoto) {
        AlertDialog.Builder(this)
            .setTitle("删除这张照片？")
            .setMessage("应用内的原片与胶片会一起删除。已保存到系统相册的副本保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    try {
                        store.delete(photo.id)
                        runOnUiThread {
                            if (screen == "Photo" && currentPhoto?.id == photo.id) datasetPage()
                        }
                    } catch (e: Exception) {
                        showError(e)
                    }
                }
            }
            .show()
    }

    private fun chooseSave(photo: CameraPhoto) {
        val fresh = store.photo(photo.id) ?: return
        val body = column().apply { setPadding(24.dp(), 8.dp(), 24.dp(), 16.dp()) }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("保存到系统相册")
                .setView(body)
                .setNegativeButton("取消", null)
                .create()
        body.addView(
            LabUi.button(this, "保存原片") {
                dialog.dismiss()
                saveImage(fresh.original, "原片")
            }
        )
        body.addView(LabUi.space(this, 8))
        body.addView(
            LabUi.button(this, if (fresh.developing) "胶片冲洗中" else "保存胶片", true) {
                    fresh.film?.let {
                        dialog.dismiss()
                        saveImage(it, "胶片")
                    }
                }
                .apply {
                    isEnabled = fresh.film != null && !fresh.developing
                    alpha = if (isEnabled) 1f else .4f
                }
        )
        if (fresh.state == "failed") body.addView(LabUi.label(this, "原片已保留。返回预览可重新冲洗。"))
        dialog.show()
    }

    private fun saveImage(file: File, kind: String) {
        val name =
            "G23_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",java.util.Locale.CHINA).format(java.util.Date())}_${kind}.${file.extension}"
        if (Build.VERSION.SDK_INT < 29) {
            pendingSave = file
            if (file.extension == "png") savePngDocument.launch(name) else saveDocument.launch(name)
            return
        }
        io.execute {
            try {
                PhotoSaver.save(contentResolver, file, name)
                toast("$kind 已保存到系统相册")
            } catch (e: Exception) {
                showError(e)
            }
        }
    }
}
