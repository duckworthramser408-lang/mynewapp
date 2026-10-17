package lab.g23

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.work.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONObject

data class CameraPhoto(
    val folder: File,
    val state: String,
    val ratio: String,
    val created: Long,
    val beautyState: String = "legacy",
) {
    val id
        get() = folder.name

    val original
        get() = File(folder, "original.jpg")

    val film
        get() =
            File(folder, "film.jpg").takeIf { it.isFile }
                ?: File(folder, "edited.png").takeIf { it.isFile }

    val lutOnly
        get() = File(folder, "lut_only.jpg").takeIf { it.isFile } ?: film

    val thumbnail
        get() = File(folder, "thumbnail_after.jpg").takeIf { it.isFile } ?: film ?: original

    val developing
        get() = state == "queued" || state == "developing"
}

/** Immutable per-capture rendering recipe and job state; no lab analysis records. */
class PhotoStore internal constructor(private val context: Context) {
    val root = File(context.filesDir, "samples").apply { mkdirs() }
    val looks = LutLibrary(context)
    private val lock = Any()
    private val activeCaptures = HashSet<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    companion object {
        @Volatile private var instance: PhotoStore? = null

        fun get(context: Context): PhotoStore =
            instance
                ?: synchronized(this) {
                    instance ?: PhotoStore(context.applicationContext).also { instance = it }
                }

        private const val QUEUE = "film-develop"
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeObserver(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun changed() {
        main.post { listeners.forEach { it() } }
    }

    private fun folder(id: String): File {
        require(id.matches(Regex("G23_[A-Za-z0-9]+")))
        return File(root, id)
    }

    private fun record(dir: File) = JSONObject(File(dir, "photo.json").readText())

    private fun describe(dir: File): CameraPhoto? =
        runCatching {
                if (!File(dir, "original.jpg").isFile) return null
                val info = File(dir, "photo.json")
                if (info.isFile) {
                    val r = record(dir)
                    if (r.getString("state") == "capturing") return null
                    CameraPhoto(
                        dir,
                        r.getString("state"),
                        r.getString("ratio"),
                        r.getLong("created"),
                        r.optString("beauty_state", "legacy"),
                    )
                } else {
                    // Old installations remain viewable without creating any new lab records.
                    CameraPhoto(
                        dir,
                        if (File(dir, "edited.png").isFile) "ready" else "original",
                        "legacy",
                        dir.lastModified(),
                    )
                }
            }
            .getOrNull()

    fun photos(): List<CameraPhoto> =
        synchronized(lock) {
            root
                .listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("G23_") }
                ?.mapNotNull { describe(it) }
                ?.sortedByDescending { it.created } ?: emptyList()
        }

    fun photo(id: String): CameraPhoto? = synchronized(lock) { describe(folder(id)) }

    fun newCapture(look: FilmLut, ratio: String): File =
        synchronized(lock) {
            require(ratio in listOf("4:3", "3:2", "1:1"))
            val dir = folder("G23_" + UUID.randomUUID().toString().replace("-", ""))
            check(dir.mkdir())
            atomicText(
                File(dir, "photo.json"),
                JSONObject()
                    .put("version", 2)
                    .put(
                        "lut_intensity",
                        context
                            .getSharedPreferences("camera", Context.MODE_PRIVATE)
                            .getFloat("lut_intensity", 1f)
                            .toDouble(),
                    )
                    .put("beauty", BeautySettings.read(context).json())
                    .put("lut", look.id)
                    .put("ratio", ratio)
                    .put("created", System.currentTimeMillis())
                    .put("state", "capturing")
                    .toString(),
            )
            activeCaptures.add(dir.name)
            dir
        }

    fun captured(id: String) {
        synchronized(lock) {
            val dir = folder(id)
            require(File(dir, "original.jpg").length() > 0)
            atomicText(File(dir, "photo.json"), record(dir).put("state", "queued").toString())
            activeCaptures.remove(id)
        }
        changed()
        enqueue(id)
    }

    fun captureFailed(id: String) {
        synchronized(lock) {
            activeCaptures.remove(id)
            val dir = folder(id)
            if (!dir.exists()) return
            if (File(dir, "original.jpg").length() == 0L) dir.deleteRecursively()
            else atomicText(File(dir, "photo.json"), record(dir).put("state", "failed").toString())
        }
        changed()
    }

    private fun enqueue(id: String) {
        val request =
            OneTimeWorkRequestBuilder<FilmDevelopWorker>()
                .setInputData(workDataOf("photo" to id))
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun recover() {
        // WorkManager also restores interrupted work after process death. Duplicate recovery
        // requests are harmless: ready/deleted photographs are never rendered again.
        val pending =
            synchronized(lock) {
                root
                    .listFiles()
                    ?.filter { File(it, "photo.json").isFile }
                    ?.mapNotNull { dir ->
                        runCatching {
                                val r = record(dir)
                                if (r.getString("state") == "capturing") {
                                    if (dir.name in activeCaptures) return@runCatching null
                                    if (File(dir, "original.jpg").length() == 0L) {
                                        dir.deleteRecursively()
                                        return@runCatching null
                                    }
                                    atomicText(
                                        File(dir, "photo.json"),
                                        r.put("state", "queued").toString(),
                                    )
                                }
                                describe(dir)?.takeIf { it.developing }?.id
                            }
                            .getOrNull()
                    } ?: emptyList()
            }
        pending.forEach { enqueue(it) }
        File(context.filesDir, "photo-trash").listFiles()?.forEach { it.deleteRecursively() }
        changed()
    }

    fun retry(id: String) {
        synchronized(lock) {
            val dir = folder(id)
            val r = record(dir)
            require(r.getString("state") == "failed")
            atomicText(File(dir, "photo.json"), r.put("state", "queued").toString())
        }
        changed()
        enqueue(id)
    }

    fun redevelop(id: String) {
        synchronized(lock) {
            val dir = folder(id)
            require(File(dir, "original.jpg").isFile) { "找不到原片" }
            val recipe =
                if (File(dir, "photo.json").isFile) record(dir)
                else
                    JSONObject()
                        .put("state", "ready")
                        .put("ratio", "legacy")
                        .put("created", dir.lastModified())
            require(recipe.getString("state") in listOf("ready", "failed")) { "请等待当前冲洗完成" }
            recipe
                .put("lut", looks.selected().id)
                .put(
                    "lut_intensity",
                    context
                        .getSharedPreferences("camera", Context.MODE_PRIVATE)
                        .getFloat("lut_intensity", 1f)
                        .toDouble(),
                )
                .put("beauty", BeautySettings.read(context).json())
                .put("version", 2)
                .put("state", "queued")
            atomicText(File(dir, "photo.json"), recipe.toString())
        }
        changed()
        enqueue(id)
    }

    /** Rename first: an in-flight decoder/encoder can never resurrect a deleted photo. */
    fun delete(id: String) {
        val trash =
            synchronized(lock) {
                val dir = folder(id)
                if (!dir.exists()) return
                val bin = File(context.filesDir, "photo-trash").apply { mkdirs() }
                File(bin, dir.name + UUID.randomUUID()).also {
                    check(dir.renameTo(it)) { "删除失败，请重试" }
                }
            }
        changed()
        trash.deleteRecursively()
    }

    fun develop(
        id: String,
        stopped: () -> Boolean = { false },
        render: ((File, String, String) -> Unit)? = null,
    ) {
        val dir = folder(id)
        val recipe =
            synchronized(lock) {
                if (!dir.exists()) return
                val r = record(dir)
                if (r.getString("state") !in listOf("queued", "developing")) return
                atomicText(File(dir, "photo.json"), r.put("state", "developing").toString())
                r
            }
        changed()
        try {
            if (stopped()) return
            if (render != null) render(dir, recipe.getString("lut"), recipe.getString("ratio"))
            else {
                val beauty =
                    FilmProcessor.process(
                        context,
                        dir,
                        looks.table(recipe.getString("lut")),
                        recipe.getString("ratio"),
                        recipe,
                    )
                recipe.put("beauty_state", beauty.state)
            }
            synchronized(lock) {
                if (!dir.exists() || stopped()) return
                check(File(dir, "film.jpg.tmp").renameTo(File(dir, "film.jpg")))
                check(
                    File(dir, "thumbnail_after.jpg.tmp").renameTo(File(dir, "thumbnail_after.jpg"))
                )
                val baseline = File(dir, "lut_only.jpg.tmp")
                if (baseline.isFile) check(baseline.renameTo(File(dir, "lut_only.jpg")))
                else File(dir, "lut_only.jpg").delete()
                atomicText(File(dir, "photo.json"), recipe.put("state", "ready").toString())
            }
        } catch (e: Throwable) {
            if (e !is Exception && e !is OutOfMemoryError) throw e
            synchronized(lock) {
                if (dir.exists() && !stopped()) {
                    atomicText(File(dir, "photo.json"), recipe.put("state", "failed").toString())
                    Log.e("FilmDevelop", "冲洗失败，原片已保留", e)
                }
            }
        } finally {
            changed()
        }
    }
}

class FilmDevelopWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val id = inputData.getString("photo") ?: return Result.success()
        PhotoStore.get(applicationContext).develop(id, { isStopped })
        return if (isStopped) Result.retry() else Result.success()
    }
}

object FilmProcessor {
    fun crop(source: Bitmap, ratio: String): Bitmap {
        if (ratio == "legacy") return source
        val landscape =
            when (ratio) {
                "1:1" -> 1.0
                "3:2" -> 1.5
                else -> 4.0 / 3
            }
        val desired = if (source.width < source.height) 1 / landscape else landscape
        val w = minOf(source.width, (source.height * desired).toInt()).coerceAtLeast(1)
        val h = minOf(source.height, (source.width / desired).toInt()).coerceAtLeast(1)
        if (kotlin.math.abs(w - source.width) <= 1 && kotlin.math.abs(h - source.height) <= 1)
            return source
        val x = (source.width - w) / 2
        val y = (source.height - h) / 2
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            android.graphics
                .Canvas(it)
                .drawBitmap(
                    source,
                    android.graphics.Rect(x, y, x + w, y + h),
                    android.graphics.Rect(0, 0, w, h),
                    null,
                )
        }
    }

    private fun prepared(dir: File, ratio: String): Bitmap {
        val decoded = Imaging.decode(File(dir, "original.jpg"))
        try {
            val cropped =
                if (ratio == "legacy" && File(dir, "parameters.json").isFile)
                    Imaging.geometry(decoded, JSONObject(File(dir, "parameters.json").readText()))
                else crop(decoded, ratio)
            if (cropped !== decoded) decoded.recycle()
            return if (cropped.isMutable) cropped
            else cropped.copy(Bitmap.Config.ARGB_8888, true).also { cropped.recycle() }
        } catch (e: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw e
        }
    }

    fun process(
        context: Context,
        dir: File,
        lut: CubeLut,
        ratio: String,
        recipe: JSONObject,
    ): NaturalBeauty.Result {
        val start = System.nanoTime()
        val (w, h) = Imaging.dimensions(File(dir, "original.jpg"))
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        require(w > 0 && h > 0 && w.toLong() * h * 8 + 32L * 1024 * 1024 < available) {
            "可用内存不足，原片已保留，可稍后重新冲洗"
        }
        val requested =
            runCatching { BeautySettings.snapshot(recipe.optJSONObject("beauty")) }
                .getOrElse { BeautySettings(enabled = false) }
        val memoryFallback =
            requested.enabled && w.toLong() * h * 12 + 64L * 1024 * 1024 >= available
        val settings = if (memoryFallback) requested.copy(enabled = false) else requested
        val intensity = recipe.optDouble("lut_intensity", 1.0).toFloat()
        require(intensity.isFinite() && intensity in 0f..1f)
        File(dir, "lut_only.jpg.tmp").delete()
        var image: Bitmap = prepared(dir, ratio)
        val decoded = System.nanoTime()
        try {
            var beauty =
                if (memoryFallback) NaturalBeauty.Result("memory_fallback")
                else NaturalBeauty.apply(context, image, settings)
            if (beauty.state == "error_fallback") {
                image.recycle()
                image = prepared(dir, ratio)
            }
            val beautified = System.nanoTime()
            lut.apply(image, intensity)
            val filtered = System.nanoTime()
            encode(image, File(dir, "film.jpg.tmp"))
            runCatching { copyExif(File(dir, "original.jpg"), File(dir, "film.jpg.tmp")) }
                .onFailure { Log.w("FilmDevelop", "EXIF 未复制；照片方向已归一化", it) }
            Imaging.thumbnail(image, File(dir, "thumbnail_after.jpg.tmp"))
            val encoded = System.nanoTime()
            // Full-resolution baseline uses a SECOND decode of the same untouched original,
            // same crop/LUT/strength/JPEG quality. Never undo or reapply to a beautified image.
            if (beauty.state == "applied") {
                try {
                    image.recycle()
                    image = prepared(dir, ratio)
                    lut.apply(image, intensity)
                    encode(image, File(dir, "lut_only.jpg.tmp"))
                } catch (e: Throwable) {
                    if (e !is Exception && e !is OutOfMemoryError) throw e
                    File(dir, "lut_only.jpg.tmp").delete()
                    beauty = beauty.copy(state = "applied_comparison_unavailable")
                    Log.w("FilmDevelop", "对比图未生成，美颜成片已保留", e)
                }
            }
            Log.d(
                "FilmDevelop",
                "decode=${(decoded-start)/1000000}ms detect=${beauty.detectMs}ms beauty=${(beautified-decoded)/1000000-beauty.detectMs}ms lut=${(filtered-beautified)/1000000}ms encode=${(encoded-filtered)/1000000}ms baseline=${(System.nanoTime()-encoded)/1000000}ms rssHighWater=${NativeBridge.peakRssKiB()}KiB heapUsed=${(runtime.totalMemory()-runtime.freeMemory())/1024}KiB",
            )
            return beauty
        } finally {
            if (!image.isRecycled) image.recycle()
        }
    }

    private fun encode(image: Bitmap, file: File) {
        file.outputStream().use {
            check(image.compress(Bitmap.CompressFormat.JPEG, 95, it))
            it.fd.sync()
        }
    }

    private fun copyExif(source: File, target: File) {
        val before = ExifInterface(source)
        val after = ExifInterface(target)
        listOf(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_FOCAL_LENGTH,
            )
            .forEach { key -> before.getAttribute(key)?.let { after.setAttribute(key, it) } }
        after.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        after.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
        after.saveAttributes()
    }
}
