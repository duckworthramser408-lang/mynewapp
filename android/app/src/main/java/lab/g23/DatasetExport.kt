package lab.g23

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object DatasetExport {
    private val required =
        listOf(
            "original.jpg",
            "edited.png",
            "thumbnail_before.jpg",
            "thumbnail_after.jpg",
            "parameters.json",
            "original_parameters.json",
            "comparison.json",
            "metadata.json",
        )

    fun export(
        store: SampleStore,
        folders: List<File>,
        approvedOnly: Boolean,
        stripGps: Boolean,
        progress: (Int, Int) -> Unit,
    ): File {
        require(folders.isNotEmpty()) { "请选择样本" }
        val selected =
            folders.filter { !approvedOnly || store.metadata(it).getString("status") == "approved" }
        require(selected.isNotEmpty()) { "没有符合条件的已确认样本" }
        val originals = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        selected.forEach { folder ->
            val meta = store.metadata(folder)
            require(meta.getString("sample_id") == folder.name && ids.add(folder.name)) {
                "样本 ID 重复或不匹配"
            }
            val hashes = meta.optJSONObject("sha256") ?: error("此样本尚未保存成片，请先保存或使用一键导出自动生成。")
            (required +
                    if (File(folder, "original.dng").isFile) listOf("original.dng")
                    else emptyList())
                .forEach { name ->
                    val file = File(folder, name)
                    require(file.isFile) { "缺少 $name，请先保存成片" }
                    if (name != "metadata.json")
                        require(digest(file) == hashes.getString(name)) { "$name 校验失败" }
                }
            require(originals.add(hashes.getString("original.jpg"))) { "重复原图，请选择独立拍摄样本" }
            val doc = store.parameters(folder)
            AnalysisData.validate(
                store.comparison(folder) ?: error("缺少并列分析数据，请重新保存样本"),
                folder.name,
                store.originalParameters(folder),
                doc,
            )
            require(
                doc.getString("schema_version") == "1.0" &&
                    doc.getString("render_pipeline_version") == "1.0.0"
            )
        }
        val directory = File(store.context.cacheDir, "exports").apply { mkdirs() }
        val output = File(directory, "G23_${System.currentTimeMillis()}.zip")
        val temporary = File(directory, output.name + ".tmp")
        val manifest = JSONObject()
        val rows = mutableListOf<JSONObject>()
        val summaries = mutableListOf<LinkedHashMap<String, String>>()
        val pairedRows = StringBuilder("sample_id,parameter,label,before,after\n")
        val prefix = "G23_Dataset_Export/"
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                fun text(path: String, contents: String) {
                    zip.putNextEntry(ZipEntry(prefix + path))
                    zip.write(contents.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                selected.forEachIndexed { index, folder ->
                    val meta = store.metadata(folder)
                    val params = store.parameters(folder)
                    val rel = "samples/${folder.name}/"
                    val exportedHashes = JSONObject()
                    (required.filter { it != "metadata.json" } +
                            if (!stripGps && File(folder, "original.dng").isFile)
                                listOf("original.dng")
                            else emptyList())
                        .forEach { name ->
                            var file = File(folder, name)
                            var cleaned: File? = null
                            try {
                                if (stripGps && name == "original.jpg") {
                                    cleaned = File(directory, "gps-clean.jpg")
                                    file.copyTo(cleaned, true)
                                    val exif = ExifInterface(cleaned)
                                    listOf(
                                            ExifInterface.TAG_GPS_LATITUDE,
                                            ExifInterface.TAG_GPS_LATITUDE_REF,
                                            ExifInterface.TAG_GPS_LONGITUDE,
                                            ExifInterface.TAG_GPS_LONGITUDE_REF,
                                            ExifInterface.TAG_GPS_ALTITUDE,
                                            ExifInterface.TAG_GPS_ALTITUDE_REF,
                                            ExifInterface.TAG_GPS_TIMESTAMP,
                                            ExifInterface.TAG_GPS_DATESTAMP,
                                            ExifInterface.TAG_GPS_PROCESSING_METHOD,
                                            ExifInterface.TAG_GPS_AREA_INFORMATION,
                                            ExifInterface.TAG_GPS_DEST_LATITUDE,
                                            ExifInterface.TAG_GPS_DEST_LONGITUDE,
                                            ExifInterface.TAG_GPS_DEST_BEARING,
                                            ExifInterface.TAG_GPS_DEST_DISTANCE,
                                        )
                                        .forEach { exif.setAttribute(it, null) }
                                    exif.saveAttributes()
                                    file = cleaned
                                }
                                val hash = digest(file)
                                exportedHashes.put(name, hash)
                                manifest.put(rel + name, hash)
                                zip.putNextEntry(ZipEntry(prefix + rel + name))
                                file.inputStream().use { it.copyTo(zip, 1024 * 1024) }
                                zip.closeEntry()
                            } finally {
                                cleaned?.delete()
                            }
                        }
                    val exportedMeta =
                        JSONObject(meta.toString())
                            .put("sha256", exportedHashes)
                            .put("export_gps_removed", stripGps)
                            .put(
                                "raw_omitted_for_privacy",
                                stripGps && File(folder, "original.dng").isFile,
                            )
                    val metaText = exportedMeta.toString(2)
                    text(rel + "metadata.json", metaText)
                    manifest.put(
                        rel + "metadata.json",
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(metaText.toByteArray())
                            .joinToString("") { "%02x".format(it) },
                    )
                    val row =
                        JSONObject()
                            .put("sample_id", folder.name)
                            .put("original", rel + "original.jpg")
                            .put("edited", rel + "edited.png")
                            .put("parameters", rel + "parameters.json")
                            .put("original_parameters", rel + "original_parameters.json")
                            .put("comparison", rel + "comparison.json")
                            .put("metadata", rel + "metadata.json")
                            .put("scene", meta.getJSONObject("scene"))
                            .put("device_model", meta.getJSONObject("capture").opt("device_model"))
                            .put("rating", meta.getJSONObject("scene").opt("rating"))
                            .put("approved", meta.getString("status") == "approved")
                            .put("render_pipeline_version", "1.0.0")
                    rows.add(row)
                    val pairs = ParameterLabels.pairs(store.originalParameters(folder), params)
                    fun quoted(v: Any?) = "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""
                    for (i in 0 until pairs.length()) {
                        val pair = pairs.getJSONObject(i)
                        pairedRows
                            .append(
                                listOf(
                                        folder.name,
                                        pair.getString("parameter"),
                                        pair.getString("label"),
                                        pair.get("before"),
                                        pair.get("after"),
                                    )
                                    .joinToString(",") { quoted(it) }
                            )
                            .append('\n')
                    }

                    val csv = linkedMapOf("sample_id" to folder.name)
                    flatten(params.getJSONObject("parameters"), "", csv)
                    csv["curves_file"] = rel + "parameters.json"
                    summaries.add(csv)
                    progress(index + 1, selected.size)
                }
                text("reports/before_after_parameters.csv", pairedRows.toString())
                text("dataset.jsonl", rows.joinToString("\n", postfix = "\n") { it.toString() })
                fun escape(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
                val columns = summaries.first().keys
                text(
                    "reports/parameters_summary.csv",
                    columns.joinToString(",") { escape(it) } +
                        "\n" +
                        summaries.joinToString("\n") { row ->
                            columns.joinToString(",") { escape(row[it] ?: "") }
                        } +
                        "\n",
                )
                text(
                    "reports/dataset_summary.json",
                    JSONObject()
                        .put("count", rows.size)
                        .put("approved", rows.count { it.getBoolean("approved") })
                        .put("pipeline", "1.0.0")
                        .toString(2),
                )
                text(
                    "manifest.json",
                    JSONObject()
                        .put("schema_version", "1.0")
                        .put("sample_count", rows.size)
                        .put("sha256", manifest)
                        .toString(2),
                )
                text(
                    "README.md",
                    "# G23 Dataset\nSDR sRGB. Final snapshots are labels; editing history excluded.\nVerify manifest SHA-256 before training. Original captures are paired by sample ID.\n",
                )
            }
            check(temporary.renameTo(output))
            return output
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }
    }

    private fun flatten(doc: JSONObject, prefix: String, out: LinkedHashMap<String, String>) {
        doc.keys().forEach { key ->
            val name = if (prefix.isBlank()) key else "$prefix.$key"
            when (val v = doc.get(key)) {
                is JSONObject -> flatten(v, name, out)
                is Number -> out[name] = v.toString()
            }
        }
    }
}
