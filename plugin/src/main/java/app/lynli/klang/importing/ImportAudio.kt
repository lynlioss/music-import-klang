package app.lynli.klang.importing

import org.json.JSONObject
import kotlin.math.roundToInt

internal data class ImportAudio(val id: String, val description: String)

/** yt-dlp returns formats in ascending quality order, including source/language preferences.
 * Keep that order (including Premium when accessible), but prefer a mix without DRC.
 */
internal fun selectOriginalAudio(info: JSONObject): ImportAudio {
    val formats = info.optJSONArray("formats")?.objects().orEmpty().filter {
        it.optString("vcodec") == "none" && !it.optBoolean("has_drm") &&
            it.optString("acodec").matches(Regex("^(opus|vorbis|mp4a\\..*|aac|flac|mp3)$")) &&
            it.stringOrNull("format_id") != null
    }
    val selected = formats.lastOrNull {
        !it.optString("format_id").contains("-drc") && !it.optString("format_note").contains("DRC", true)
    } ?: formats.lastOrNull() ?: throw IllegalArgumentException("This recording has no accessible original audio stream.")
    val codec = selected.optString("acodec").let {
        if (it.startsWith("mp4a") || it == "aac") "AAC" else it.uppercase(java.util.Locale.ROOT)
    }
    val bitrate = selected.optDouble("abr", selected.optDouble("tbr", 0.0))
    val description = buildString {
        append(codec)
        if (bitrate.isFinite() && bitrate > 0) append(" · ${bitrate.roundToInt()} kbps")
        if (selected.optString("format_id").contains("-drc")) append(" · DRC")
    }
    return ImportAudio(selected.getString("format_id"), description)
}
