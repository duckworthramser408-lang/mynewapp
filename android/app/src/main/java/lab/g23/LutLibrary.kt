package lab.g23

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.Reader
import org.json.*

/** Standard .cube grid, with red varying fastest. Data is immutable once loaded. */
class CubeLut(
    val size: Int,
    val minimum: FloatArray,
    val maximum: FloatArray,
    val values: FloatArray,
) {
    fun apply(bitmap: Bitmap) = NativeBridge.applyCube(bitmap, values, size, minimum, maximum)

    companion object {
        fun parse(reader: Reader): CubeLut {
            var size = 0
            var count = 0
            var data: FloatArray? = null
            var minimum = floatArrayOf(0f, 0f, 0f)
            var maximum = floatArrayOf(1f, 1f, 1f)
            val seen = mutableSetOf<String>()
            val whitespace = Regex("\\s+")
            reader.buffered().useLines { lines ->
                lines.forEachIndexed { index, raw ->
                    require(index < 300000 && raw.length < 1024) { "LUT 文件过大" }
                    val line = raw.removePrefix("\uFEFF").substringBefore('#').trim()
                    if (line.isEmpty()) return@forEachIndexed
                    val fields = line.split(whitespace)
                    val key = fields[0]
                    when (key) {
                        "TITLE" -> Unit
                        "LUT_3D_SIZE" -> {
                            require(seen.add(key) && fields.size == 2 && count == 0) {
                                "LUT 网格声明无效"
                            }
                            size = fields[1].toInt()
                            require(size in 2..65) { "不支持的 LUT 网格大小" }
                            data = FloatArray(size * size * size * 3)
                        }
                        "DOMAIN_MIN",
                        "DOMAIN_MAX" -> {
                            require(seen.add(key) && fields.size == 4 && count == 0) {
                                "LUT 输入范围声明无效"
                            }
                            val range =
                                fields
                                    .drop(1)
                                    .map { it.toFloat().also { v -> require(v.isFinite()) } }
                                    .toFloatArray()
                            if (key == "DOMAIN_MIN") minimum = range else maximum = range
                        }
                        else -> {
                            require(key.first() in "-+.0123456789" && fields.size == 3) {
                                "不支持的 LUT 指令或数据"
                            }
                            val target = data ?: error("LUT 缺少网格声明")
                            require(count + 3 <= target.size) { "LUT 数据行过多" }
                            fields.forEach {
                                val value = it.toFloat()
                                require(value.isFinite() && kotlin.math.abs(value) <= 64f) {
                                    "LUT 包含无效数值"
                                }
                                target[count++] = value
                            }
                        }
                    }
                }
            }
            val result = data ?: error("LUT 缺少网格数据")
            require(count == result.size && (0..2).all { minimum[it] < maximum[it] }) {
                "LUT 数据不完整或输入范围无效"
            }
            return CubeLut(size, minimum, maximum, result)
        }
    }
}

data class FilmLut(
    val id: String,
    val name: String,
    val file: String,
    val sha256: String,
    val color: Int,
    val gridSize: Int,
)

class LutLibrary(private val context: Context) {
    val items: List<FilmLut> by lazy {
        val manifest =
            JSONObject(
                context.assets.open("luts/catalog.json").bufferedReader().use { it.readText() }
            )
        val rows = manifest.getJSONArray("luts")
        (0 until rows.length())
            .map { i ->
                val row = rows.getJSONObject(i)
                FilmLut(
                    row.getString("id"),
                    row.getString("name"),
                    row.getString("file"),
                    row.getString("sha256"),
                    android.graphics.Color.parseColor(row.getString("color")),
                    row.getInt("grid_size"),
                )
            }
            .also { require(it.isNotEmpty() && it.map { v -> v.id }.distinct().size == it.size) }
    }
    private val cache = LinkedHashMap<String, CubeLut>(4, .75f, true)

    fun selected(): FilmLut {
        val id = context.getSharedPreferences("camera", Context.MODE_PRIVATE).getString("lut", null)
        return items.firstOrNull { it.id == id } ?: items.first()
    }

    fun select(item: FilmLut) {
        require(items.any { it.id == item.id })
        context
            .getSharedPreferences("camera", Context.MODE_PRIVATE)
            .edit()
            .putString("lut", item.id)
            .apply()
    }

    fun attach(folder: File, item: FilmLut) {
        require(!File(folder, "look.json").exists() && !File(folder, "look.cube").exists()) {
            "此照片已绑定胶卷色调"
        }
        val tmp = File(folder, "look.cube.tmp")
        context.assets.open("luts/${item.file}").use { source ->
            tmp.outputStream().use {
                source.copyTo(it)
                it.fd.sync()
            }
        }
        require(digest(tmp) == item.sha256) { "LUT 文件校验失败" }
        check(tmp.renameTo(File(folder, "look.cube")))
        atomicText(
            File(folder, "look.json"),
            JSONObject()
                .put("look_pipeline_version", "cube_srgb_trilinear_1.0")
                .put("id", item.id)
                .put("name", item.name)
                .put("source_name", item.name + ".cube")
                .put("sha256", item.sha256)
                .put("file", "look.cube")
                .put("grid_size", item.gridSize)
                .put("input_color_space", "sRGB")
                .put("intensity", 1.0)
                .put("applied_after", "g23_parameters_1.0.0")
                .toString(2),
        )
    }

    fun look(folder: File): JSONObject? {
        val file = File(folder, "look.json")
        val cube = File(folder, "look.cube")
        if (!file.exists() && !cube.exists()) return null
        require(file.isFile && cube.isFile) { "照片的 LUT 记录不完整" }
        return JSONObject(file.readText()).also {
            require(
                it.getString("look_pipeline_version") == "cube_srgb_trilinear_1.0" &&
                    it.getString("file") == "look.cube" &&
                    it.getDouble("intensity") == 1.0 &&
                    it.getString("input_color_space") == "sRGB" &&
                    it.getString("applied_after") == "g23_parameters_1.0.0"
            ) {
                "照片的 LUT 版本不受支持"
            }
        }
    }

    @Synchronized
    fun forPhoto(folder: File): CubeLut? {
        val look = look(folder) ?: return null
        val file = File(folder, "look.cube")
        val sha = digest(file)
        require(sha == look.getString("sha256")) { "照片绑定的 LUT 已损坏" }
        return cache[sha]
            ?: CubeLut.parse(file.reader()).also {
                require(it.size == look.getInt("grid_size"))
                if (cache.size >= 3) cache.remove(cache.keys.first())
                cache[sha] = it
            }
    }
}
