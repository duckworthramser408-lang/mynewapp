package lab.g23

import java.util.Locale
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** One label dictionary for sliders, comparison rows and human-readable exports. */
object ParameterLabels {
    private val words =
        mapOf(
            "exposure_ev" to "曝光",
            "contrast" to "对比度",
            "highlights" to "高光",
            "shadows" to "阴影",
            "whites" to "白色色阶",
            "blacks" to "黑色色阶",
            "temperature_shift" to "色温偏移",
            "tint" to "色调",
            "vibrance" to "自然饱和度",
            "saturation" to "饱和度",
            "hue" to "色相",
            "luminance" to "明度",
            "red" to "红色",
            "orange" to "橙色",
            "yellow" to "黄色",
            "green" to "绿色",
            "cyan" to "青色",
            "blue" to "蓝色",
            "purple" to "紫色",
            "magenta" to "洋红",
            "rgb" to "总曲线",
            "hsl" to "分色",
            "tone_curves" to "曲线",
            "color_grading" to "色彩分级",
            "midtones" to "中间调",
            "balance" to "平衡",
            "blending" to "混合范围",
            "texture" to "质感",
            "sharpness" to "锐度",
            "clarity" to "清晰度",
            "local_contrast" to "局部对比度",
            "grain_amount" to "颗粒强度",
            "grain_size" to "颗粒大小",
            "grain_seed" to "颗粒种子",
            "vignette" to "暗角",
            "fade" to "褪色",
            "geometry" to "构图",
            "crop" to "裁切范围",
            "rotation_degrees" to "旋转角度",
        )

    fun name(path: String) = path.split('.').joinToString(" · ") { words[it] ?: it }

    fun value(v: Any?): String =
        when (v) {
            null,
            JSONObject.NULL -> "—"
            is Number -> {
                val n = v.toDouble()
                if (n == floor(n)) String.format(Locale.ROOT, "%.0f", n)
                else String.format(Locale.ROOT, "%.2f", n).trimEnd('0').trimEnd('.')
            }
            is JSONArray -> v.toString()
            else -> v.toString()
        }

    fun flatten(
        doc: JSONObject,
        prefix: String = "",
        result: LinkedHashMap<String, Any> = linkedMapOf(),
    ): LinkedHashMap<String, Any> {
        doc.keys().asSequence().sorted().forEach { key ->
            val path = if (prefix.isBlank()) key else "$prefix.$key"
            val v = doc.get(key)
            if (v is JSONObject) flatten(v, path, result) else result[path] = v
        }
        return result
    }

    fun pairs(before: JSONObject, after: JSONObject): JSONArray {
        val original = flatten(before.getJSONObject("parameters"))
        val edited = flatten(after.getJSONObject("parameters"))
        val rows = JSONArray()
        require(original.keys == edited.keys) { "原图与成片参数字段不一致" }
        original.forEach { (path, value) ->
            rows.put(
                JSONObject()
                    .put("parameter", path)
                    .put("label", name(path))
                    .put("before", value)
                    .put("after", edited.getValue(path))
            )
        }
        return rows
    }
}

/** Bounded deterministic grid sampling; encoded sRGB means, not calibrated sensor measurements. */
object ImageStatistics {
    fun compute(width: Int, height: Int, pixel: (Int, Int) -> Int): JSONObject {
        require(width > 0 && height > 0)
        val stepX = max(1, ceil(width / 256.0).toInt())
        val stepY = max(1, ceil(height / 256.0).toInt())
        var count = 0
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var luminance = 0.0
        var saturation = 0.0
        var dark = 0
        var bright = 0
        val histogram = IntArray(64)
        for (y in 0 until height step stepY) for (x in 0 until width step stepX) {
            val color = pixel(x, y)
            val r = (color shr 16) and 255
            val g = (color shr 8) and 255
            val b = color and 255
            val l = .2126 * r + .7152 * g + .0722 * b
            val hi = max(r, max(g, b)) / 255.0
            val lo = min(r, min(g, b)) / 255.0
            val delta = hi - lo
            val light = (hi + lo) / 2
            red += r
            green += g
            blue += b
            luminance += l
            saturation += if (delta == 0.0) 0.0 else delta / (1 - abs(2 * light - 1))
            if (l <= 5) dark++
            if (l >= 250) bright++
            histogram[min(63, (l / 4).toInt())]++
            count++
        }
        return JSONObject()
            .put("width", width)
            .put("height", height)
            .put("sample_count", count)
            .put("sampling", "deterministic_grid_max_256x256")
            .put("color_space", "sRGB")
            .put("mean_red_0_255", red / count)
            .put("mean_green_0_255", green / count)
            .put("mean_blue_0_255", blue / count)
            .put("mean_luma_0_255", luminance / count)
            .put("mean_saturation_0_1", saturation / count)
            .put("dark_fraction", dark.toDouble() / count)
            .put("highlight_fraction", bright.toDouble() / count)
            .put("luma_histogram_64", JSONArray(histogram.toList()))
    }
}

object AnalysisData {
    fun create(
        id: String,
        capture: JSONObject,
        before: JSONObject,
        after: JSONObject,
        beforeStats: JSONObject,
        afterStats: JSONObject,
    ): JSONObject {
        require(id.matches(Regex("G23_[A-Za-z0-9_-]+"))) { "样本编号无效" }
        return JSONObject()
            .put("analysis_schema_version", "1.0")
            .put("sample_id", id)
            .put("render_pipeline_version", after.getString("render_pipeline_version"))
            .put("capture", JSONObject(capture.toString()))
            .put(
                "comparison_basis",
                "original decoded with the SAME crop and rotation as edited; source file remains unchanged",
            )
            .put(
                "before",
                JSONObject()
                    .put("image", "original.jpg")
                    .put("parameters", JSONObject(before.toString()))
                    .put("statistics", JSONObject(beforeStats.toString())),
            )
            .put(
                "after",
                JSONObject()
                    .put("image", "edited.png")
                    .put("parameters", JSONObject(after.toString()))
                    .put("statistics", JSONObject(afterStats.toString())),
            )
            .put("parameter_pairs", ParameterLabels.pairs(before, after))
    }

    fun validate(doc: JSONObject, id: String, before: JSONObject, after: JSONObject) {
        require(
            doc.getString("analysis_schema_version") == "1.0" && doc.getString("sample_id") == id
        ) {
            "分析数据的样本编号不匹配"
        }
        require(
            doc.getString("render_pipeline_version") == after.getString("render_pipeline_version")
        ) {
            "分析数据版本不匹配"
        }
        val expected = ParameterLabels.pairs(before, after)
        require(canonicalJson(doc.getJSONArray("parameter_pairs")) == canonicalJson(expected)) {
            "并列参数与最终快照不一致"
        }
        require(
            canonicalJson(doc.getJSONObject("before").getJSONObject("parameters")) ==
                canonicalJson(before) &&
                canonicalJson(doc.getJSONObject("after").getJSONObject("parameters")) ==
                    canonicalJson(after)
        ) {
            "分析快照不一致"
        }
    }
}

/** JSON object order is irrelevant; array order and numeric values are significant. */
fun canonicalJson(value: Any?): String =
    when (value) {
        null,
        JSONObject.NULL -> "null"
        is JSONObject ->
            value.keys().asSequence().sorted().joinToString(",", "{", "}") {
                JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
            }
        is JSONArray ->
            (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
