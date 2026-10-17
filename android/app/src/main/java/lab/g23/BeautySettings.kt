package lab.g23

import android.content.Context
import org.json.JSONObject

/** Values control a conservative algorithm; they are NOT displacement percentages. */
data class BeautySettings(
    val enabled: Boolean = true,
    val symmetry: Float = .20f,
    val folds: Float = .25f,
    val smallFace: Float = .10f,
) {
    fun json() =
        JSONObject()
            .put("version", 1)
            .put("enabled", enabled)
            .put("symmetry", symmetry.toDouble())
            .put("folds", folds.toDouble())
            .put("small_face", smallFace.toDouble())

    companion object {
        fun read(context: Context): BeautySettings {
            val p = context.getSharedPreferences("natural_beauty", Context.MODE_PRIVATE)
            return BeautySettings(
                p.getBoolean("enabled", true),
                p.getFloat("symmetry", .20f).coerceIn(0f, 1f),
                p.getFloat("folds", .25f).coerceIn(0f, 1f),
                p.getFloat("small_face", .10f).coerceIn(0f, 1f),
            )
        }

        fun snapshot(json: JSONObject?): BeautySettings {
            if (json == null)
                return BeautySettings(
                    enabled = false
                ) // Existing 0.4 captures keep their original recipe.
            require(json.getInt("version") == 1)
            fun number(key: String): Float {
                val v = json.getDouble(key).toFloat()
                require(v.isFinite() && v in 0f..1f)
                return v
            }
            return BeautySettings(
                json.getBoolean("enabled"),
                number("symmetry"),
                number("folds"),
                number("small_face"),
            )
        }
    }

    fun save(context: Context) {
        context
            .getSharedPreferences("natural_beauty", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .putFloat("symmetry", symmetry)
            .putFloat("folds", folds)
            .putFloat("small_face", smallFace)
            .apply()
    }
}
