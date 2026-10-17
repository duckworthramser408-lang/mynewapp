package lab.g23

import android.content.Context
import android.graphics.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.json.JSONArray
import org.tensorflow.lite.Interpreter

/** Genuine BlazeFace + 468 point FaceMesh, static RGB inference. No network code. */
class LocalFaceDetector(context: Context) : Closeable {
    data class Box(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val eyes: FloatArray,
        val score: Float,
    ) {
        val area
            get() = width * height

        val cx
            get() = x + width / 2

        val cy
            get() = y + height / 2
    }

    data class Face(
        val points: FloatArray,
        val others: FloatArray,
        val presence: Float,
        val flipError: Float,
        val detected: Int,
    )

    private val app = context.applicationContext
    private val detector = load("face_detection_short_range.tflite")
    private val mesh =
        try {
            load("face_landmark.tflite")
        } catch (e: Throwable) {
            detector.close()
            throw e
        }
    private val mirror: IntArray
    private val anchors = buildList {
        for ((grid, count) in listOf(16 to 2, 8 to 6)) for (y in 0 until grid) for (x in
            0 until grid) repeat(count) { add((x + .5f) / grid to (y + .5f) / grid) }
    }

    init {
        try {
            mirror =
                JSONArray(
                        app.assets.open("beauty/mirror_indices.json").bufferedReader().use {
                            it.readText()
                        }
                    )
                    .let { a -> IntArray(468) { a.getInt(it) } }
            require(detector.getInputTensor(0).shape().contentEquals(intArrayOf(1, 128, 128, 3)))
            require(detector.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 896, 16)))
            require(detector.getOutputTensor(1).shape().contentEquals(intArrayOf(1, 896, 1)))
            require(mesh.getInputTensor(0).shape().contentEquals(intArrayOf(1, 192, 192, 3)))
            require(
                mesh.getOutputTensor(0).numElements() == 1404 &&
                    mesh.getOutputTensor(1).numElements() == 1
            )
            require(anchors.size == 896 && mirror.size == 468)
            require(mirror.indices.all { mirror[it] in 0..467 && mirror[mirror[it]] == it })
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun load(name: String): Interpreter {
        val expected =
            when (name) {
                "face_detection_short_range.tflite" ->
                    "bbff11cebd1eb27a1e004cae0b0e63ec8c551cbf34a4451148b4908b8db3eca8"
                else -> "1055cb9d4a9ca8b8c688902a3a5194311138ba256bcc94e336d8373a5f30c814"
            }
        val data = app.assets.open("beauty/$name").use { it.readBytes() }
        val sha =
            java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
                "%02x".format(it)
            }
        require(sha == expected) { "本地人脸模型校验失败" }
        fun create(accelerated: Boolean): Interpreter {
            val buffer =
                ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).apply {
                    put(data)
                    rewind()
                }
            return Interpreter(
                buffer,
                Interpreter.Options().setNumThreads(2).setUseXNNPACK(accelerated),
            )
        }
        return try {
            create(true)
        } catch (e: RuntimeException) {
            create(false)
        }
    }

    private fun floats(n: Int) = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x.coerceIn(-80f, 80f)))

    private fun tensor(bitmap: Bitmap, rangeMinusOne: Boolean): ByteBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return floats(pixels.size * 3).apply {
            val divisor = if (rangeMinusOne) 127.5f else 255f
            val offset = if (rangeMinusOne) 1f else 0f
            for (c in pixels) {
                putFloat(Color.red(c) / divisor - offset)
                putFloat(Color.green(c) / divisor - offset)
                putFloat(Color.blue(c) / divisor - offset)
            }
            rewind()
        }
    }

    private fun detections(image: Bitmap): List<Box> {
        val scale = min(128f / image.width, 128f / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val px = (128 - dw) / 2
        val py = (128 - dh) / 2
        val input = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val reg = floats(896 * 16)
        val scores = floats(896)
        try {
            Canvas(input).apply {
                drawColor(Color.BLACK)
                drawBitmap(
                    image,
                    null,
                    RectF(px, py, px + dw, py + dh),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            detector.runForMultipleInputsOutputs(
                arrayOf(tensor(input, true)),
                mapOf(0 to reg, 1 to scores),
            )
        } finally {
            input.recycle()
        }
        reg.rewind()
        scores.rewind()
        val raw = FloatArray(896 * 16)
        reg.asFloatBuffer().get(raw)
        val logits = FloatArray(896)
        scores.asFloatBuffer().get(logits)
        val rows = mutableListOf<Box>()
        for (i in 0 until 896) {
            val score = sigmoid(logits[i])
            if (score < .45f) continue
            val o = i * 16
            val anchor = anchors[i]
            fun x(v: Float) = ((v + anchor.first * 128) - px) / dw
            fun y(v: Float) = ((v + anchor.second * 128) - py) / dh
            val width = raw[o + 2] / dw
            val height = raw[o + 3] / dh
            if (width <= 0 || height <= 0 || !width.isFinite() || !height.isFinite()) continue
            rows.add(
                Box(
                    x(raw[o]) - width / 2,
                    y(raw[o + 1]) - height / 2,
                    width,
                    height,
                    FloatArray(4) { k -> if (k % 2 == 0) x(raw[o + 4 + k]) else y(raw[o + 4 + k]) },
                    score,
                )
            )
        }
        val ordered = rows.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<Box>()
        while (ordered.isNotEmpty() && result.size < 9) {
            val top = ordered.first()
            val group = ordered.filter { iou(top, it) > .3f }
            ordered.removeAll(group.toSet())
            if (group.isEmpty()) break
            val total = group.sumOf { it.score.toDouble() }.toFloat()
            fun average(f: (Box) -> Float) =
                group.sumOf { (f(it) * it.score).toDouble() }.toFloat() / total
            result.add(
                Box(
                    average { it.x },
                    average { it.y },
                    average { it.width },
                    average { it.height },
                    FloatArray(4) { k -> average { it.eyes[k] } },
                    top.score,
                )
            )
        }
        return result
    }

    private fun iou(a: Box, b: Box): Float {
        val area =
            max(0f, min(a.x + a.width, b.x + b.width) - max(a.x, b.x)) *
                max(0f, min(a.y + a.height, b.y + b.height) - max(a.y, b.y))
        return area / max(.00001f, a.area + b.area - area)
    }

    /** Crop uses eye rotation and a square 1.5x detector box, matching the source graph. */
    private fun crop(image: Bitmap, box: Box): Pair<Bitmap, Matrix> {
        val angle =
            atan2(
                (box.eyes[3] - box.eyes[1]) * image.height,
                (box.eyes[2] - box.eyes[0]) * image.width,
            )
        val side = max(box.width * image.width, box.height * image.height) * 1.5f
        val cx = box.cx * image.width
        val cy = box.cy * image.height
        val cs = cos(angle)
        val sn = sin(angle)
        val toImage =
            Matrix().apply {
                setValues(
                    floatArrayOf(
                        side / 192 * cs,
                        -side / 192 * sn,
                        cx - side * .5f * (cs - sn),
                        side / 192 * sn,
                        side / 192 * cs,
                        cy - side * .5f * (sn + cs),
                        0f,
                        0f,
                        1f,
                    )
                )
            }
        val inverse = Matrix()
        check(toImage.invert(inverse))
        val out = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(image, inverse, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out to toImage
    }

    private fun landmarks(crop: Bitmap): Pair<FloatArray, Float> {
        val positions = floats(1404)
        val presence = floats(1)
        mesh.runForMultipleInputsOutputs(
            arrayOf(tensor(crop, false)),
            mapOf(0 to positions, 1 to presence),
        )
        positions.rewind()
        presence.rewind()
        val p = FloatArray(1404)
        positions.asFloatBuffer().get(p)
        require(p.all { it.isFinite() }) { "人脸关键点无效" }
        return p to sigmoid(presence.float)
    }

    fun detect(output: Bitmap): Face? {
        val scale = min(1f, 960f / max(output.width, output.height))
        val small =
            Bitmap.createScaledBitmap(
                output,
                max(1, (output.width * scale).roundToInt()),
                max(1, (output.height * scale).roundToInt()),
                true,
            )
        try {
            val boxes = detections(small).sortedByDescending { it.area }
            if (boxes.size > 8) return null
            for ((index, box) in boxes.withIndex()) {
                if (
                    box.score < .70f ||
                        min(box.width * output.width, box.height * output.height) < 80
                )
                    continue
                val (crop, toImage) = crop(small, box)
                try {
                    val (raw, presence) = landmarks(crop)
                    if (presence < .85f) continue
                    val flipped = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                    val verify =
                        try {
                            Canvas(flipped).apply {
                                scale(-1f, 1f, 96f, 96f)
                                drawBitmap(crop, 0f, 0f, null)
                            }
                            landmarks(flipped)
                        } finally {
                            flipped.recycle()
                        }
                    if (verify.second < .85f) continue
                    var consistency = 0f
                    val stable =
                        intArrayOf(
                            33,
                            133,
                            263,
                            362,
                            168,
                            1,
                            98,
                            327,
                            61,
                            291,
                            234,
                            454,
                            152,
                            10,
                            93,
                            323,
                        )
                    for (i in stable) {
                        val j = mirror[i]
                        val dx = (raw[i * 3] + verify.first[j * 3]) / 192 - 1
                        val dy = (raw[i * 3 + 1] - verify.first[j * 3 + 1]) / 192
                        consistency += dx * dx + dy * dy
                    }
                    consistency = sqrt(consistency / stable.size)
                    if (consistency > .025f) continue
                    val xy = FloatArray(936) { i -> raw[(i / 2) * 3 + i % 2] }
                    toImage.mapPoints(xy)
                    val side = max(box.width * small.width, box.height * small.height) * 1.5f
                    val points =
                        FloatArray(1404) { i ->
                            when (i % 3) {
                                0 -> xy[(i / 3) * 2] / small.width
                                1 -> xy[(i / 3) * 2 + 1] / small.height
                                else -> raw[i] / 192 * side / small.width
                            }
                        }
                    val others =
                        boxes
                            .filterIndexed { i, _ -> i != index }
                            .flatMap { listOf(it.x, it.y, it.x + it.width, it.y + it.height) }
                            .toFloatArray()
                    return Face(
                        points,
                        others,
                        min(presence, verify.second),
                        consistency,
                        boxes.size,
                    )
                } finally {
                    crop.recycle()
                }
            }
            return null
        } finally {
            if (small !== output) small.recycle()
        }
    }

    override fun close() {
        try {
            mesh.close()
        } finally {
            detector.close()
        }
    }
}
