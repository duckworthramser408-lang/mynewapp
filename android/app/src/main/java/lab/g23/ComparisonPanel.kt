package lab.g23

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import lab.g23.LabUi.dp
import org.json.JSONObject

/** Original and final values always share a row, including unchanged values. */
object ComparisonPanel {
    fun create(
        c: Context,
        id: String,
        capture: JSONObject,
        before: JSONObject,
        after: JSONObject,
        analysis: JSONObject?,
    ): LinearLayout {
        val body = LabUi.column(c).apply { setPadding(c.dp(16), 0, c.dp(16), c.dp(16)) }
        body.addView(LabUi.label(c, id).apply { setPadding(0, c.dp(12), 0, c.dp(12)) })
        val heading = values(c, "参数", "原始", "调色后", false, true)
        body.addView(heading)
        val scroll = ScrollView(c)
        val content = LabUi.column(c)
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun section(s: String) {
            content.addView(
                LabUi.text(c, s, 13f, LabUi.accent).apply { setPadding(0, c.dp(22), 0, c.dp(10)) }
            )
        }
        fun row(name: String, a: String, b: String) {
            content.addView(values(c, name, a, b, a != b))
            content.addView(LabUi.divider(c))
        }
        section("拍摄记录 · 两侧共用同一次拍摄")
        val names =
            mapOf(
                "platform" to "系统平台",
                "device_brand" to "设备品牌",
                "os_version" to "系统版本",
                "format" to "照片格式",
                "equivalent_focal_length_35mm" to "等效焦距（毫米）",
                "device_model" to "设备型号",
                "camera_id" to "摄像头编号",
                "captured_at" to "拍摄时间",
                "iso" to "感光度",
                "exposure_time_seconds" to "快门时间（秒）",
                "aperture" to "光圈",
                "focal_length" to "焦距",
                "white_balance" to "白平衡",
                "width" to "原图宽度",
                "height" to "原图高度",
            )
        capture.keys().forEach { key ->
            val v = ParameterLabels.value(capture.get(key))
            row(names[key] ?: key, v, v)
        }
        section("色调参数 · 原始基线与当前快照")
        val pairs = ParameterLabels.pairs(before, after)
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            row(
                p.getString("label"),
                ParameterLabels.value(p.get("before")),
                ParameterLabels.value(p.get("after")),
            )
        }
        section("图像统计 · 相同裁切与旋转范围")
        if (analysis == null)
            content.addView(LabUi.text(c, "保存或一键导出后生成完整分辨率的采样统计。当前参数已实时列出。", 12f, LabUi.muted))
        else {
            val a = analysis.getJSONObject("before").getJSONObject("statistics")
            val b = analysis.getJSONObject("after").getJSONObject("statistics")
            val labels =
                mapOf(
                    "width" to "对齐图像宽度",
                    "height" to "对齐图像高度",
                    "sample_count" to "采样像素数",
                    "mean_red_0_255" to "平均红色",
                    "mean_green_0_255" to "平均绿色",
                    "mean_blue_0_255" to "平均蓝色",
                    "mean_luma_0_255" to "平均亮度",
                    "mean_saturation_0_1" to "平均饱和度",
                    "dark_fraction" to "暗部占比",
                    "highlight_fraction" to "高光占比",
                )
            labels.forEach { (key, label) ->
                if (a.has(key) && b.has(key))
                    row(label, ParameterLabels.value(a.get(key)), ParameterLabels.value(b.get(key)))
            }
            content.addView(
                LabUi.text(
                        c,
                        "统计使用固定网格，每侧最多 256 × 256 个采样点。亮度为 sRGB 编码值；直方图包含在导出的分析文件中。",
                        11f,
                        LabUi.muted,
                    )
                    .apply { setPadding(0, c.dp(16), 0, 0) }
            )
        }
        return body
    }

    private fun values(
        c: Context,
        label: String,
        a: String,
        b: String,
        changed: Boolean,
        heading: Boolean = false,
    ) =
        LabUi.row(c).apply {
            setPadding(0, c.dp(11), 0, c.dp(11))
            listOf(label, a, b).forEachIndexed { i, v ->
                addView(
                    LabUi.text(
                            c,
                            v,
                            if (heading) 12f else 11f,
                            if (i == 2 && changed) LabUi.accent
                            else if (heading) LabUi.muted else LabUi.foreground,
                        )
                        .apply {
                            gravity = if (i == 0) Gravity.START else Gravity.CENTER
                            setPadding(if (i == 0) 0 else c.dp(5), 0, c.dp(5), 0)
                            if (heading) setTypeface(typeface, Typeface.BOLD)
                        },
                    LinearLayout.LayoutParams(0, -2, if (i == 0) 1.25f else 1f),
                )
            }
        }
}
