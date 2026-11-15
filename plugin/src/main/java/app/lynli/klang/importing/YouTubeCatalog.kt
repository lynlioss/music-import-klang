package app.lynli.klang.importing

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Public WEB_REMIX song search, independently implemented from observed response fields.
 * Catalog songs carry recording artists/album/duration; a video channel is not an artist credit.
 * See docs/music-imports.md for protocol references and upstream limitations.
 */
internal object YouTubeCatalog {
    suspend fun search(song: ImportSong): List<JSONObject> {
        val date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", "1.$date.01.00")
            .put("hl", "en").put("gl", "US")))
            .put("query", "${song.artist} ${song.title}")
            .put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
        return parseSearch(JSONObject(ImportHttp.postJson(
            "https://music.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString(),
        )))
    }

    suspend fun searchOrEmpty(song: ImportSong): List<JSONObject> = try {
        search(song)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A changed catalog API must not disable yt-dlp's independent search fallback.
        emptyList()
    }

    internal fun parseSearch(response: JSONObject): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 32) return
            when (value) {
                is JSONObject -> {
                    val item = value.optJSONObject("musicResponsiveListItemRenderer")
                    if (item != null) parseSong(item)?.let(results::add)
                    else value.keys().forEach { visit(value.opt(it), depth + 1) }
                }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), depth + 1)
            }
        }
        visit(response, 0)
        return results.distinctBy { it.optString("id") }
    }

    private fun parseSong(item: JSONObject): JSONObject? {
        if (item.optString("musicItemRendererDisplayPolicy").endsWith("GREY_OUT")) return null
        val columns = item.optJSONArray("flexColumns")?.objects().orEmpty().map {
            it.path("musicResponsiveListItemFlexColumnRenderer", "text")?.optJSONArray("runs")?.objects().orEmpty()
        }
        val titleRuns = columns.firstOrNull().orEmpty()
        val endpoint = titleRuns.firstOrNull()?.path("navigationEndpoint", "watchEndpoint")
            ?: item.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint", "watchEndpoint") ?: return null
        val type = endpoint.path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig")
            ?.optString("musicVideoType")
        if (type != "MUSIC_VIDEO_TYPE_ATV") return null
        val id = endpoint.stringOrNull("videoId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null
        val title = titleRuns.joinToString("") { it.optString("text") }.takeIf { it.isNotBlank() } ?: return null
        val credits = columns.drop(1).flatten()
        fun pageType(run: JSONObject) = run.path("navigationEndpoint", "browseEndpoint",
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig")?.optString("pageType")
        val artists = credits.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }
            .mapNotNull { it.stringOrNull("text") }.distinct()
        if (artists.isEmpty()) return null
        val duration = credits.firstNotNullOfOrNull { run ->
            val text = run.optString("text")
            if (!text.matches(Regex("\\d{1,3}:\\d{2}(:\\d{2})?"))) null
            else text.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
        } ?: 0L
        val explicit = item.optJSONArray("badges")?.objects().orEmpty().any {
            it.path("musicInlineBadgeRenderer", "icon")?.optString("iconType") == "MUSIC_EXPLICIT_BADGE"
        }
        return JSONObject().put("id", id).put("title", title).put("artist", artists.joinToString(", "))
            .put("artists", JSONArray(artists)).put("duration", duration).put("catalog", true)
            .put("album", credits.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.optString("text"))
            .put("explicit", explicit)
            .put("thumbnails", item.path("thumbnail", "musicThumbnailRenderer", "thumbnail")?.optJSONArray("thumbnails"))
    }
}

internal fun JSONObject.path(vararg keys: String): JSONObject? {
    var value: JSONObject = this
    for (key in keys) value = value.optJSONObject(key) ?: return null
    return value
}
