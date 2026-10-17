package lab.g23

import android.graphics.*
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import org.json.JSONObject

object NativeBridge {
    init {
        System.loadLibrary("g23_android")
    }

    external fun renderInto(source: Bitmap, destination: Bitmap, json: String)

    external fun applyCube(
        bitmap: Bitmap,
        table: FloatArray,
        size: Int,
        minimum: FloatArray,
        maximum: FloatArray,
        intensity: Float,
    )

    external fun beautify(
        bitmap: Bitmap,
        landmarks: FloatArray,
        others: FloatArray,
        presence: Float,
        flipError: Float,
        settings: FloatArray,
    ): FloatArray

    external fun peakRssKiB(): Long
}

object Imaging {
    fun decode(file: File, maxSide: Int = 0): Bitmap {
        return decodeSource(ImageDecoder.createSource(file), maxSide)
    }

    internal fun decodeSource(source: ImageDecoder.Source, maxSide: Int = 0): Bitmap {
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                if (maxSide > 0 && maxOf(info.size.width, info.size.height) > maxSide) {
                    val s = maxSide.toFloat() / maxOf(info.size.width, info.size.height)
                    decoder.setTargetSize(
                        maxOf(1, (info.size.width * s).toInt()),
                        maxOf(1, (info.size.height * s).toInt()),
                    )
                }
            }
            .let { decoded ->
                if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
            }
    }

    fun geometry(source: Bitmap, doc: JSONObject): Bitmap {
        val g = doc.getJSONObject("parameters").getJSONObject("geometry")
        var image = source
        if (!g.isNull("crop")) {
            val c = g.getJSONArray("crop")
            require(c.length() == 4)
            val left = c.getDouble(0)
            val top = c.getDouble(1)
            val right = c.getDouble(2)
            val bottom = c.getDouble(3)
            require(
                left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top
            )
            val x = (left * source.width).toInt()
            val y = (top * source.height).toInt()
            image =
                Bitmap.createBitmap(
                    source,
                    x,
                    y,
                    maxOf(1, (right * source.width).toInt() - x),
                    maxOf(1, (bottom * source.height).toInt() - y),
                )
        }
        val angle = g.getInt("rotation_degrees")
        require(angle in listOf(0, 90, 180, 270))
        if (angle != 0) {
            val previous = image
            image =
                Bitmap.createBitmap(
                    previous,
                    0,
                    0,
                    previous.width,
                    previous.height,
                    Matrix().apply { postRotate(angle.toFloat()) },
                    false,
                )
            if (previous !== source && previous !== image) previous.recycle()
        }
        return image
    }

    fun render(source: Bitmap, doc: JSONObject, lut: CubeLut? = null): Bitmap {
        val input = geometry(source, doc)
        try {
            return renderPrepared(input, doc, lut)
        } finally {
            if (input !== source) input.recycle()
        }
    }

    fun renderPrepared(input: Bitmap, doc: JSONObject, lut: CubeLut? = null): Bitmap {
        val identity =
            JSONObject(doc.toString()).apply {
                getJSONObject("parameters")
                    .getJSONObject("geometry")
                    .put("crop", JSONObject.NULL)
                    .put("rotation_degrees", 0)
            }
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        try {
            NativeBridge.renderInto(input, output, identity.toString())
            lut?.apply(output)
            return output
        } catch (e: Throwable) {
            output.recycle()
            throw e
        }
    }

    fun captureInfo(file: File, cameraId: String): JSONObject {
        val e = ExifInterface(file)
        val dimensions = dimensions(file)
        fun optional(key: String): Any = e.getAttribute(key) ?: JSONObject.NULL
        fun number(key: String): Any =
            e.getAttributeDouble(key, Double.NaN).let {
                if (it.isNaN() || it <= 0) JSONObject.NULL else it
            }
        return JSONObject()
            .put("platform", "Android")
            .put("device_brand", Build.BRAND)
            .put("device_model", Build.MODEL)
            .put("os_version", Build.VERSION.RELEASE)
            .put("camera_id", cameraId)
            .put("width", dimensions.first)
            .put("height", dimensions.second)
            .put("format", "JPEG")
            .put("iso", number(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
            .put(
                "exposure_time_seconds",
                e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN).let {
                    if (it.isNaN()) JSONObject.NULL else it
                },
            )
            .put("aperture", number(ExifInterface.TAG_F_NUMBER))
            .put("focal_length", number(ExifInterface.TAG_FOCAL_LENGTH))
            .put(
                "equivalent_focal_length_35mm",
                number(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
            )
            .put("white_balance", optional(ExifInterface.TAG_WHITE_BALANCE))
            .put("captured_at", java.time.Instant.now().toString())
    }

    fun jpeg(bitmap: Bitmap, file: File) {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    }

    fun thumbnail(bitmap: Bitmap, file: File) {
        val scale = minOf(1f, 320f / maxOf(bitmap.width, bitmap.height))
        val small =
            Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, (bitmap.width * scale).toInt()),
                maxOf(1, (bitmap.height * scale).toInt()),
                true,
            )
        jpeg(small, file)
        if (small !== bitmap) small.recycle()
    }

    fun dimensions(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    fun memoryCheck(file: File) {
        val (w, h) = dimensions(file)
        require(w > 0 && h > 0)
        val needed = w.toLong() * h * 12 + 64L * 1024 * 1024
        val available =
            Runtime.getRuntime().maxMemory() -
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        require(needed < available) {
            "完整分辨率处理需要约 ${needed/1048576} MB 可用堆；当前 ${available/1048576} MB。原图已保留，请在更大内存设备重试。"
        }
    }
}
