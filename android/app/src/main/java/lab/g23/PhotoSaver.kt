package lab.g23

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.File

object PhotoSaver {
    fun save(resolver: ContentResolver, source: File, name: String) {
        require(Build.VERSION.SDK_INT >= 29)
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    if (source.extension == "png") "image/png" else "image/jpeg",
                )
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/G23")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri =
            checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "无法创建相册照片"
            }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            check(
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
