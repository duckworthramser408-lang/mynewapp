package lab.g23

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/** Always receives a fresh normalized original, never a previously beautified/LUT image. */
object NaturalBeauty {
    data class Result(
        val state: String,
        val detectMs: Long = 0,
        val geometryMs: Float = 0f,
        val foldsMs: Float = 0f,
        val scratchBytes: Long = 0,
        val changed: Long = 0,
    )

    fun apply(context: Context, image: Bitmap, settings: BeautySettings): Result {
        if (!settings.enabled || maxOf(settings.symmetry, settings.folds, settings.smallFace) == 0f)
            return Result("disabled")
        val start = System.nanoTime()
        try {
            val face = LocalFaceDetector(context).use { it.detect(image) }
            val detected = System.nanoTime()
            if (face == null) return Result("no_reliable_face", (detected - start) / 1000000)
            val output =
                NativeBridge.beautify(
                    image,
                    face.points,
                    face.others,
                    face.presence,
                    face.flipError,
                    floatArrayOf(settings.symmetry, settings.folds, settings.smallFace),
                )
            require(output.size == 12)
            val state =
                if (output[0] > 0) "applied"
                else
                    when (output[1].toInt()) {
                        2 -> "pose_expression_skipped"
                        3 -> "edge_overlap_skipped"
                        4 -> "dark_skipped"
                        else -> "unreliable_skipped"
                    }
            Log.d(
                "NaturalBeauty",
                "state=$state detect=${(detected-start)/1000000}ms geometry=${output[7]}ms folds=${output[8]}ms scratch=${output[9].toLong()} bytes maxMove=${output[5]}px changed=${output[10].toLong()} rssHighWater=${output[11].toLong()}KiB",
            )
            return Result(
                state,
                (detected - start) / 1000000,
                output[7],
                output[8],
                output[9].toLong(),
                output[10].toLong(),
            )
        } catch (e: Throwable) {
            if (e !is Exception && e !is OutOfMemoryError && e !is LinkageError) throw e
            // C++ commits only after success; detector/model errors never touch input pixels.
            Log.w("NaturalBeauty", "本地美颜不可用，回退正常 LUT", e)
            return Result("error_fallback", (System.nanoTime() - start) / 1000000)
        }
    }

    fun message(state: String) =
        when (state) {
            "applied" -> "自然美颜 · 仅处理可靠主脸"
            "applied_comparison_unavailable" -> "美颜成片已保存 · 对比图暂不可用"
            "disabled" -> "本张美颜关闭 · 仅 LUT"
            "no_reliable_face" -> "没有可靠主脸 · 已保留仅 LUT 成片"
            "pose_expression_skipped" -> "姿态或表情不适合 · 已保留仅 LUT 成片"
            "edge_overlap_skipped" -> "边缘或人物重叠 · 已保留仅 LUT 成片"
            "dark_skipped" -> "光线不足 · 已保留仅 LUT 成片"
            "unreliable_skipped" -> "本张未作美颜调整 · 仅 LUT"
            "error_fallback" -> "美颜未完成 · 已保存正常 LUT 成片"
            "memory_fallback" -> "可用内存不足 · 已保存正常 LUT 成片"
            else -> "拖动竖线 · 原片与胶片"
        }
}
