package app.lynli.klang.importing

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal object ImportEngine {
    @Synchronized
    fun initialize(context: Context) {
        try {
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
        } catch (error: LinkageError) {
            throw ImportEngineException(error)
        }
    }

    fun stop(processId: String) {
        try { YoutubeDL.destroyProcessById(processId) }
        catch (_: LinkageError) { /* A failed class initializer cannot own a live process. */ }
    }

    @Synchronized
    fun refreshExtractor(context: Context) {
        val prefs = context.getSharedPreferences("music_import_engine", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated", 0) < 24 * 60 * 60 * 1000L) return
        // Bundled engine remains usable when GitHub is unavailable.
        runCatching { YoutubeDL.updateYoutubeDL(context) }.onSuccess {
            prefs.edit().putLong("updated", now).apply()
        }
    }

    fun request(url: String? = null) = YoutubeDLRequest(url?.let { listOf(it) }.orEmpty()).apply {
        addOption("--ignore-config")
        addOption("--socket-timeout", 25)
        addOption("--retries", 3)
        addOption("--extractor-retries", 2)
    }

    fun info(url: String, processId: String, flat: Boolean = false): JSONObject {
        val request = request(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            if (flat) addOption("--flat-playlist")
            else addOption("--no-playlist")
        }
        return JSONObject(YoutubeDL.execute(request, processId, null).out)
    }
}

internal class ImportResolver(private val context: Context) {
    suspend fun resolve(link: ImportLink, processId: String): ImportPreview = withContext(Dispatchers.IO) {
        if (link.source == ImportSource.SPOTIFY) SpotifyMetadata.preview(link)
        else {
            ImportEngine.initialize(context)
            val json = kotlinx.coroutines.runInterruptible { ImportEngine.info(link.url, processId, flat = link.playlist) }
            coroutineContext.ensureActive()
            val entries = if (link.playlist) json.optJSONArray("entries")?.objects().orEmpty() else listOf(json)
            val songs = entries.mapNotNull { item ->
                val id = item.stringOrNull("id") ?: return@mapNotNull null
                if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) ||
                    item.optString("title") in listOf("[Private video]", "[Deleted video]")) return@mapNotNull null
                youtubeSong(item, id)
            }
            require(songs.isNotEmpty()) { "No accessible songs found. Check that the playlist is public." }
            val omitted = entries.size - songs.size
            ImportPreview(link, json.stringOrNull("title") ?: "YouTube Music", songs,
                thumbnail(json), if (omitted > 0) "$omitted unavailable entries can't be imported." else null)
        }
    }
}

internal fun youtubeSong(json: JSONObject, id: String): ImportSong {
    val artist = json.stringOrNull("artist") ?: json.stringOrNull("uploader")?.removeSuffix(" - Topic")
        ?: json.stringOrNull("channel")?.removeSuffix(" - Topic").orEmpty()
    val raw = json.stringOrNull("title") ?: "Untitled song"
    // Strip only known video presentation text; preserve recording/version names.
    val title = json.stringOrNull("track") ?: cleanVideoTitle(raw, artist)
    return ImportSong("youtube:$id", "https://music.youtube.com/watch?v=$id", title, artist,
        json.stringOrNull("album").orEmpty(), (json.optDouble("duration", 0.0) * 1000).toLong(),
        thumbnail(json), json.stringOrNull("release_date"), json.optInt("track_number"), json.optInt("disc_number"))
}

internal fun cleanVideoTitle(title: String, artist: String): String {
    var result = title.trim()
    if (artist.isNotBlank()) {
        result = result.replace(Regex("^${Regex.escape(artist)}\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE), "")
    }
    return result.replace(Regex("\\s*(?:\\(|\\[)(?:official\\s+)?(?:audio|music video|video|lyric video|lyrics)(?:\\)|\\])\\s*$",
        RegexOption.IGNORE_CASE), "").trim().ifBlank { title }
}

internal fun thumbnail(json: JSONObject): String? = json.optJSONArray("thumbnails")?.objects()
    ?.maxByOrNull { it.optLong("width", 0) * it.optLong("height", 0) }?.stringOrNull("url")
    ?: json.stringOrNull("thumbnail")

/** Public metadata only. Never reads Spotify audio previews as a download source. */
internal object SpotifyMetadata {
    suspend fun preview(link: ImportLink): ImportPreview {
        val kind = link.kind
        val embedHtml = getText("https://open.spotify.com/embed/$kind/${link.id}")
        val embed = parseEmbed(embedHtml)
        val entity = embed.getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
            .getJSONObject("data").getJSONObject("entity")
        if (!link.playlist) {
            // The embed already contains title/artist/duration/artwork. Album enrichment happens in the worker.
            val song = parseEmbedTrack(entity)
            return ImportPreview(link, song.title, listOf(song), song.artwork)
        }
        val regular = try { publicEntity(getText(link.url), "spotify:$kind:${link.id}") }
        catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        val rich = regular?.optJSONObject("content")?.optJSONArray("items")?.objects().orEmpty()
            .mapNotNull { it.optJSONObject("itemV2")?.optJSONObject("data")?.let(::parsePublicTrack) }
            .associateBy { it.sourceId }
        var songs = entity.optJSONArray("trackList")?.objects().orEmpty().mapNotNull { item ->
            val uri = item.optString("uri")
            if (!uri.startsWith("spotify:track:")) return@mapNotNull null
            rich[uri] ?: parseEmbedTrack(item)
        }
        if (link.kind == "album") {
            val albumTracks = regular?.optJSONObject("tracksV2")?.optJSONArray("items")?.objects().orEmpty()
                .mapNotNull { it.optJSONObject("track")?.let(::parsePublicTrack) }.associateBy { it.sourceId }
            songs = albumSongs(entity, songs.map { albumTracks[it.sourceId] ?: it })
        }
        require(regular != null || songs.size < 100) {
            "Spotify didn't expose the full playlist count. Try again later to avoid an incomplete import."
        }
        val total = (if (link.kind == "album") regular?.optJSONObject("tracksV2") else regular?.optJSONObject("content"))
            ?.optInt("totalCount", songs.size) ?: songs.size
        require(total <= 10_000) { "This collection is too large. Import playlists of up to 10,000 songs." }
        // Public embeds currently stop at 100. Fetch remaining pages, or fail explicitly.
        if (link.kind == "album") {
            require(songs.size >= total) { "Spotify returned an incomplete album. Try again later." }
        } else if (total > songs.size) {
            val state = embed.getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
            val token = state.getJSONObject("settings").getJSONObject("session").getString("accessToken")
            songs = fullPlaylist(link.id, token, total)
        }
        require(songs.isNotEmpty()) { "No songs found. Spotify playlists must be public." }
        return ImportPreview(link, entity.stringOrNull("title") ?: "Spotify playlist", songs,
            image(entity.optJSONObject("coverArt")) ?: image(entity.optJSONObject("visualIdentity")))
    }

    suspend fun enrich(song: ImportSong): ImportSong {
        if (!song.spotify || (song.album.isNotBlank() && song.artwork != null)) return song
        // Spotify's embed is enough to match/download. Album enrichment is optional, never a gate.
        val rich = try {
            publicEntity(getText(song.url), song.sourceId)?.let(::parsePublicTrack)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null } ?: return song
        return song.copy(album = rich.album.ifBlank { song.album }, artwork = rich.artwork ?: song.artwork,
            date = rich.date ?: song.date, trackNumber = rich.trackNumber, discNumber = rich.discNumber,
            durationMs = rich.durationMs.takeIf { it > 0 } ?: song.durationMs, explicit = rich.explicit ?: song.explicit)
    }

    private suspend fun fullPlaylist(id: String, token: String, total: Int): List<ImportSong> {
        try {
            return pathfinderPlaylist(id, token, total)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* Try the public Web API if the web-player query changed. */ }
        val songs = mutableListOf<ImportSong>()
        var offset = 0
        while (offset < total) {
            val page = try {
                JSONObject(getText("https://api.spotify.com/v1/playlists/$id/tracks?limit=100&offset=$offset", token))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalArgumentException("Spotify didn't expose the full playlist ($total songs). Try again later or import smaller playlists.", error)
            }
            val items = page.getJSONArray("items").objects()
            require(items.isNotEmpty()) { "Spotify returned an incomplete playlist. Try again later." }
            items.mapNotNullTo(songs) { wrapper ->
                val item = wrapper.optJSONObject("track") ?: wrapper.optJSONObject("item")
                item?.takeIf { it.optString("type") == "track" && !it.optBoolean("is_local") }?.let(::parseApiTrack)
            }
            offset += items.size
        }
        return songs
    }

    // Spotify's web-player pagination, researched against Liner and a live public playlist.
    // The embed supplies a fresh anonymous session; neither Spotify login nor audio previews are used.
    private suspend fun pathfinderPlaylist(id: String, token: String, initialTotal: Int): List<ImportSong> {
        val songs = mutableListOf<ImportSong>()
        var offset = 0
        var total = initialTotal
        while (offset < total) {
            val variables = JSONObject().put("uri", "spotify:playlist:$id").put("offset", offset).put("limit", 100)
            val extensions = JSONObject().put("persistedQuery", JSONObject().put("version", 1)
                .put("sha256Hash", "86dde7b9d9356e2369414647cf6950cfed96e778e129cfdfc99aea6c1613b3b0"))
            fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
            val url = "https://api-partner.spotify.com/pathfinder/v1/query?operationName=fetchPlaylistContents" +
                "&variables=${encode(variables.toString())}&extensions=${encode(extensions.toString())}"
            val json = JSONObject(ImportHttp.getText(url, token, headers = mapOf(
                "app-platform" to "WebPlayer", "spotify-app-version" to "1.3.2.216.g7f3fbea1")))
            val content = json.getJSONObject("data").getJSONObject("playlistV2").getJSONObject("content")
            total = content.getInt("totalCount")
            require(total <= 10_000) { "This playlist is too large." }
            val items = content.getJSONArray("items").objects()
            require(items.isNotEmpty()) { "Spotify returned an incomplete playlist." }
            items.mapNotNullTo(songs) { item ->
                item.optJSONObject("itemV2")?.optJSONObject("data")?.takeIf { it.optString("__typename") == "Track" }
                    ?.let(::parsePublicTrack)
            }
            offset += items.size
        }
        return songs
    }

    internal fun parseEmbed(html: String): JSONObject {
        val raw = Regex("<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Spotify couldn't read this link. Make sure it is public and try again.")
        return JSONObject(raw)
    }

    internal fun publicEntity(html: String, uri: String): JSONObject? {
        val encoded = Regex("<script[^>]*id=\"initialState\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        return JSONObject(String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8))
            .optJSONObject("entities")?.optJSONObject("items")?.optJSONObject(uri)
    }

    internal fun parseEmbedTrack(json: JSONObject): ImportSong {
        val uri = json.getString("uri")
        val artist = json.optJSONArray("artists")?.objects()?.joinToString(", ") { it.optString("name") }
            ?: json.optString("subtitle").replace('\u00a0', ' ')
        return ImportSong(uri, "https://open.spotify.com/track/${uri.substringAfterLast(':')}",
            json.stringOrNull("title") ?: json.optString("name"), artist, durationMs = json.optLong("duration"),
            artwork = image(json.optJSONObject("visualIdentity")),
            date = json.optJSONObject("releaseDate")?.stringOrNull("isoString")?.take(10), spotify = true,
            explicit = if (json.isNull("isExplicit")) null else json.optBoolean("isExplicit"))
    }

    internal fun albumSongs(entity: JSONObject, songs: List<ImportSong>): List<ImportSong> {
        val artwork = image(entity.optJSONObject("visualIdentity"))
        val title = entity.stringOrNull("title").orEmpty()
        val date = entity.optJSONObject("releaseDate")?.stringOrNull("isoString")?.take(10)
        return songs.mapIndexed { index, song -> song.copy(album = title, artwork = song.artwork ?: artwork,
            date = song.date ?: date, trackNumber = song.trackNumber.takeIf { it > 0 } ?: index + 1,
            discNumber = song.discNumber.takeIf { it > 0 } ?: 1) }
    }

    internal fun parsePublicTrack(json: JSONObject): ImportSong {
        val uri = json.getString("uri")
        val album = json.optJSONObject("albumOfTrack")
        return ImportSong(uri, "https://open.spotify.com/track/${uri.substringAfterLast(':')}", json.getString("name"),
            json.optJSONObject("artists")?.optJSONArray("items")?.objects()?.joinToString(", ") {
                it.optJSONObject("profile")?.optString("name").orEmpty()
            }.orEmpty(), album?.stringOrNull("name").orEmpty(),
            (json.optJSONObject("duration") ?: json.optJSONObject("trackDuration"))?.optLong("totalMilliseconds") ?: 0,
            image(album?.optJSONObject("coverArt")), album?.optJSONObject("date")?.stringOrNull("isoString")?.take(10),
            json.optInt("trackNumber"), json.optInt("discNumber"), spotify = true,
            explicit = json.path("contentRating")?.stringOrNull("label")?.let { it == "EXPLICIT" })
    }

    private fun parseApiTrack(json: JSONObject): ImportSong {
        val album = json.optJSONObject("album")
        return ImportSong("spotify:track:${json.getString("id")}", "https://open.spotify.com/track/${json.getString("id")}",
            json.getString("name"), json.getJSONArray("artists").objects().joinToString(", ") { it.getString("name") },
            album?.stringOrNull("name").orEmpty(), json.optLong("duration_ms"),
            album?.optJSONArray("images")?.objects()?.maxByOrNull { it.optInt("width") }?.stringOrNull("url"),
            album?.stringOrNull("release_date"), json.optInt("track_number"), json.optInt("disc_number"), true,
            if (json.isNull("explicit")) null else json.optBoolean("explicit"))
    }

    private fun image(json: JSONObject?): String? {
        val images = json?.optJSONArray("sources") ?: json?.optJSONArray("image")
        return images?.objects()?.maxByOrNull { it.optInt("width", it.optInt("maxWidth")) }?.stringOrNull("url")
    }
}

internal suspend fun getText(url: String, bearer: String? = null): String = ImportHttp.getText(url, bearer)

/** Match duration, artist, title and version; reject ambiguous covers/live recordings. */
internal fun recordingScore(song: ImportSong, candidate: JSONObject): Double {
    fun words(value: String) = value.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .split(' ').filter { it.isNotBlank() }.toSet()
    val title = candidate.stringOrNull("track") ?: candidate.optString("title")
    val artists = candidate.optJSONArray("artists")?.let { list ->
        (0 until list.length()).joinToString(" ") { index ->
            list.optJSONObject(index)?.optString("name") ?: list.optString(index)
        }
    }.orEmpty()
    val structuredArtist = candidate.stringOrNull("artist") ?: artists.takeIf { it.isNotBlank() }
    val credits = structuredArtist ?: "${candidate.optString("channel")} ${candidate.optString("uploader")}"
    val text = "$title $credits".lowercase(java.util.Locale.ROOT)
    fun baseTitle(value: String) = value.replace(Regex("\\s*(?:\\(|\\[)(?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]+(?:\\)|\\])",
        RegexOption.IGNORE_CASE), "")
    val expectedTitle = words(baseTitle(song.title))
    val expectedArtist = words(song.artist.substringBefore(','))
    if (expectedTitle.isEmpty() || expectedArtist.isEmpty()) return 0.0
    val actualTitle = words(baseTitle(cleanVideoTitle(title, song.artist)))
    val titleMatch = expectedTitle.intersect(actualTitle).size.toDouble() / expectedTitle.size
    val artistMatch = expectedArtist.intersect(words(if (structuredArtist != null) credits else text)).size.toDouble() / expectedArtist.size
    if (titleMatch < .8 || artistMatch < .8) return 0.0
    if (candidate.optBoolean("catalog") && actualTitle != expectedTitle) return 0.0
    if (song.explicit != null && !candidate.isNull("explicit") && song.explicit != candidate.optBoolean("explicit")) return 0.0
    val duration = candidate.optDouble("duration", 0.0) * 1000
    val delta = abs(duration - song.durationMs)
    if (song.durationMs > 0 && duration > 0 && delta > maxOf(12_000.0, song.durationMs * .08)) return 0.0
    val versions = listOf("live", "cover", "remix", "karaoke", "instrumental", "sped", "slowed", "nightcore",
        "acoustic", "clean", "radio", "demo", "extended", "tribute", "version", "edit")
    if (versions.any { (it in actualTitle) != (it in expectedTitle) }) return 0.0
    val durationMatch = if (song.durationMs > 0 && duration > 0) (1 - delta / 15_000).coerceIn(0.0, 1.0) else .3
    val official = if (candidate.optBoolean("catalog") || text.contains("topic") || text.contains("official audio")) .05 else 0.0
    return titleMatch * .45 + artistMatch * .3 + durationMatch * .2 + official
}
