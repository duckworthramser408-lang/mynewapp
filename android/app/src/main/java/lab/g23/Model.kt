package lab.g23

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

fun atomicText(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use {
        it.write(text.toByteArray(Charsets.UTF_8))
        it.fdSyncIfPossible()
    }
    check(tmp.renameTo(file)) { "无法写入文件：${file.name}" }
}

private fun java.io.OutputStream.fdSyncIfPossible() {
    if (this is java.io.FileOutputStream) fd.sync()
}

fun digest(file: File): String {
    val d = MessageDigest.getInstance("SHA-256")
    file.inputStream().use {
        val bytes = ByteArray(1024 * 1024)
        while (true) {
            val n = it.read(bytes)
            if (n < 0) break
            d.update(bytes, 0, n)
        }
    }
    return d.digest().joinToString("") { "%02x".format(it) }
}

class EditHistory(initial: JSONObject, persisted: String? = null) {
    private val states = mutableListOf(initial.toString())
    private var cursor = 0

    init {
        if (persisted != null)
            runCatching {
                val saved = JSONObject(persisted)
                require(saved.getString("schema_version") == "1.0")
                val array = saved.getJSONArray("states")
                val position = saved.getInt("cursor")
                require(position in 0 until array.length())
                require(array.getJSONObject(position).toString() == initial.toString())
                val restored = (0 until array.length()).map { array.getJSONObject(it).toString() }
                states.clear()
                states.addAll(restored)
                cursor = position
            }
    }

    fun current() = JSONObject(states[cursor])

    fun commit(next: JSONObject) {
        val value = next.toString()
        if (value == states[cursor]) return
        while (states.lastIndex > cursor) states.removeAt(states.lastIndex)
        states.add(value)
        cursor++
    }

    fun undo(): JSONObject {
        if (cursor > 0) cursor--
        return current()
    }

    fun redo(): JSONObject {
        if (cursor < states.lastIndex) cursor++
        return current()
    }

    fun serialize(): String =
        JSONObject()
            .put("schema_version", "1.0")
            .put("cursor", cursor)
            .put("states", JSONArray().apply { states.forEach { put(JSONObject(it)) } })
            .toString()
}

class SampleStore(val context: Context) {
    val looks = LutLibrary(context)

    fun look(folder: File) = looks.look(folder)

    fun lookFiles(folder: File): List<String> =
        if (look(folder) == null) emptyList() else listOf("look.json", "look.cube")

    val root = File(context.filesDir, "samples").apply { mkdirs() }

    fun defaults() =
        JSONObject(
            context.assets.open("default_parameters.json").bufferedReader().use { it.readText() }
        )

    fun newCapture(): File =
        File(root, "G23_" + UUID.randomUUID().toString().replace("-", "")).apply { mkdirs() }

    fun initialize(folder: File, cameraId: String) {
        atomicText(File(folder, "parameters.json"), defaults().toString(2))
        originalParameters(folder)
        val meta =
            JSONObject()
                .put("schema_version", "1.0")
                .put("sample_id", folder.name)
                .put("status", "draft")
                .put("style", "G23_positive")
                .put("capture", Imaging.captureInfo(File(folder, "original.jpg"), cameraId))
                .put(
                    "scene",
                    JSONObject()
                        .put("lighting", JSONArray())
                        .put("subjects", JSONArray())
                        .put("rating", JSONObject.NULL)
                        .put("notes", "")
                        .put("style_features", "")
                        .put("satisfied", JSONObject.NULL),
                )
        atomicText(File(folder, "metadata.json"), meta.toString(2))
    }

    fun all() =
        root
            .listFiles()
            ?.filter { it.isDirectory && File(it, "metadata.json").exists() }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun photos() =
        all().filter {
            File(it, "edited.png").isFile ||
                (!File(it, "look.json").exists() && File(it, "original.jpg").isFile)
        }

    fun metadata(folder: File) = JSONObject(File(folder, "metadata.json").readText())

    fun parameters(folder: File) = JSONObject(File(folder, "parameters.json").readText())

    /** Neutral source baseline is immutable and separate from the editable snapshot. */
    fun originalParameters(folder: File): JSONObject {
        val file = File(folder, "original_parameters.json")
        if (!file.exists()) atomicText(file, defaults().toString(2))
        return JSONObject(file.readText())
    }

    fun comparison(folder: File): JSONObject? =
        File(folder, "comparison.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }

    fun isRendered(folder: File, doc: JSONObject): Boolean =
        runCatching {
                val hashes = metadata(folder).getJSONObject("sha256")
                require(canonicalJson(parameters(folder)) == canonicalJson(doc))
                listOf(
                        "original.jpg",
                        "edited.png",
                        "thumbnail_before.jpg",
                        "thumbnail_after.jpg",
                        "parameters.json",
                        "original_parameters.json",
                        "comparison.json",
                    )
                    .plus(lookFiles(folder))
                    .forEach { require(digest(File(folder, it)) == hashes.getString(it)) }
                AnalysisData.validate(
                    comparison(folder)!!,
                    folder.name,
                    originalParameters(folder),
                    doc,
                    look(folder),
                )
                true
            }
            .getOrDefault(false)

    fun draft(folder: File, doc: JSONObject, history: String) {
        // Once editing starts the old target is no longer a confirmed label.
        val meta = metadata(folder).put("status", "draft")
        atomicText(File(folder, "metadata.json"), meta.toString(2))
        atomicText(File(folder, "parameters.json"), doc.toString(2))
        atomicText(File(folder, "history.json"), history)
    }

    fun save(folder: File, doc: JSONObject, approved: Boolean) {
        val oldMeta = metadata(folder).put("status", "draft")
        atomicText(File(folder, "metadata.json"), oldMeta.toString(2))
        Imaging.memoryCheck(File(folder, "original.jpg"))
        val source = Imaging.decode(File(folder, "original.jpg"))
        var prepared: android.graphics.Bitmap? = null
        try {
            val input = Imaging.geometry(source, doc)
            prepared = input
            val look = look(folder)
            val output = Imaging.renderPrepared(input, doc, looks.forPhoto(folder))
            try {
                val tmp = File(folder, "edited.png.tmp")
                tmp.outputStream().use {
                    check(output.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    it.fdSyncIfPossible()
                }
                check(tmp.renameTo(File(folder, "edited.png")))
                Imaging.thumbnail(input, File(folder, "thumbnail_before.jpg"))
                Imaging.thumbnail(output, File(folder, "thumbnail_after.jpg"))
                val before =
                    ImageStatistics.compute(input.width, input.height) { x, y ->
                        input.getPixel(x, y)
                    }
                val after =
                    ImageStatistics.compute(output.width, output.height) { x, y ->
                        output.getPixel(x, y)
                    }
                val comparison =
                    AnalysisData.create(
                        folder.name,
                        metadata(folder).getJSONObject("capture"),
                        originalParameters(folder),
                        doc,
                        before,
                        after,
                    )
                comparison.put("look", look ?: JSONObject.NULL)
                atomicText(File(folder, "comparison.json"), comparison.toString(2))
            } finally {
                output.recycle()
            }
        } finally {
            if (prepared !== source) prepared?.recycle()
            source.recycle()
        }
        atomicText(File(folder, "parameters.json"), doc.toString(2))
        val hashes = JSONObject()
        listOf(
                "original.jpg",
                "edited.png",
                "thumbnail_before.jpg",
                "thumbnail_after.jpg",
                "parameters.json",
                "original_parameters.json",
                "comparison.json",
            )
            .plus(lookFiles(folder))
            .forEach { hashes.put(it, digest(File(folder, it))) }
        if (File(folder, "original.dng").isFile)
            hashes.put("original.dng", digest(File(folder, "original.dng")))
        val meta =
            metadata(folder)
                .put("status", if (approved) "approved" else "draft")
                .put("sha256", hashes)
                .put("render_pipeline_version", "1.0.0")
                .put("look", look(folder) ?: JSONObject.NULL)
                .put(
                    "style",
                    look(folder)?.getString("name") ?: oldMeta.optString("style", "G23_positive"),
                )
        atomicText(File(folder, "metadata.json"), meta.toString(2))
    }

    val presetDir = File(context.filesDir, "presets").apply { mkdirs() }

    fun savePreset(name: String, doc: JSONObject) {
        require(name.isNotBlank() && name.length <= 80)
        val id = digestText(name)
        atomicText(
            File(presetDir, "$id.json"),
            JSONObject().put("name", name).put("document", doc).toString(2),
        )
    }

    private fun digestText(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
