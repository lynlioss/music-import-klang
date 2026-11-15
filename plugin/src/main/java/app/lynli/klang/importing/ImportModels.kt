package app.lynli.klang.importing

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal enum class ImportSource(val label: String) { SPOTIFY("Spotify"), YOUTUBE("YouTube Music") }

/** Only canonical, public song/collection URLs cross into the downloader. */
internal data class ImportLink(val url: String, val source: ImportSource, val playlist: Boolean, val id: String, val kind: String = if (playlist) "playlist" else "track") {
    companion object {
        fun parse(input: String): ImportLink {
            val raw = input.trim()
            val spotifyUri = Regex("spotify:(track|playlist|album):([A-Za-z0-9]{22})").matchEntire(raw)
            if (spotifyUri != null) return spotify(spotifyUri.groupValues[1], spotifyUri.groupValues[2])
            val uri = runCatching { URI(raw) }.getOrNull()
                ?: throw IllegalArgumentException("Paste a Spotify or YouTube Music song or playlist link.")
            require(uri.scheme?.lowercase() in listOf("https", "http") && uri.userInfo == null) {
                "Paste a Spotify or YouTube Music song or playlist link."
            }
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val path = uri.path.orEmpty().trim('/')
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
            if (host == "open.spotify.com") {
                val parts = path.split('/').filterNot { it.startsWith("intl-") || it == "embed" }
                require(parts.size == 2 && parts[0] in listOf("track", "playlist", "album") &&
                    parts[1].matches(Regex("[A-Za-z0-9]{22}"))) { "Use a Spotify song, album, EP or playlist link." }
                return spotify(parts[0], parts[1])
            }
            require(host in listOf("music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be")) {
                "Use a Spotify or YouTube Music link."
            }
            if (host == "music.youtube.com" && path.startsWith("browse/MPREb_") &&
                path.removePrefix("browse/").matches(Regex("[A-Za-z0-9_-]{8,150}"))) {
                return ImportLink("https://music.youtube.com/$path", ImportSource.YOUTUBE, true,
                    path.removePrefix("browse/"), "album")
            }
            // A shared watch URL with list= still represents the selected song.
            val video = if (host == "youtu.be") path else if (path == "watch") query["v"] else null
            if (video != null && video.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                return ImportLink("https://music.youtube.com/watch?v=$video", ImportSource.YOUTUBE, false, video)
            }
            val list = query["list"]
            require(path == "playlist" && list != null && list.matches(Regex("[A-Za-z0-9_-]{10,150}"))) {
                "Use a song link or a playlist link from the Share menu."
            }
            return ImportLink("https://music.youtube.com/playlist?list=$list", ImportSource.YOUTUBE, true, list)
        }
        private fun spotify(type: String, id: String) =
            ImportLink("https://open.spotify.com/$type/$id", ImportSource.SPOTIFY, type != "track", id, type)
    }
}

internal data class ImportSong(
    val sourceId: String, val url: String, val title: String, val artist: String = "",
    val album: String = "", val durationMs: Long = 0, val artwork: String? = null,
    val date: String? = null, val trackNumber: Int = 0, val discNumber: Int = 0,
    val spotify: Boolean = false, val explicit: Boolean? = null,
)

internal const val STREAM_COPY_ARGUMENTS = "ExtractAudio+ffmpeg_o:-c:a copy"

internal data class ImportPreview(
    val link: ImportLink, val title: String, val songs: List<ImportSong>, val artwork: String? = null,
    val notice: String? = null,
)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.stringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
