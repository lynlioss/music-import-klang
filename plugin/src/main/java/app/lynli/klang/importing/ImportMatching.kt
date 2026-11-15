package app.lynli.klang.importing

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Try catalog recordings first; validate full extraction and continue past unavailable candidates. */
internal suspend fun findRecording(
    song: ImportSong,
    catalog: List<JSONObject>,
    searchVideos: suspend () -> List<JSONObject>,
    extract: suspend (String) -> JSONObject,
): JSONObject {
    val attempted = mutableSetOf<String>()
    var lastFailure: Exception? = null
    suspend fun tryCandidates(candidates: List<JSONObject>): JSONObject? {
        val ranked = candidates.filter { recordingScore(song, it) >= .8 }
            .sortedByDescending { recordingScore(song, it) +
                (if (song.album.isNotBlank() && it.optString("album").equals(song.album, true)) .03 else 0.0) }
        for (candidate in ranked.take(4)) {
            val id = candidate.stringOrNull("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: continue
            if (!attempted.add(id)) continue
            try {
                val full = extract("https://music.youtube.com/watch?v=$id")
                if (full.optString("id") != id) continue
                // Catalog credits may fill omissions, never overwrite contradictory extractor metadata.
                if (candidate.optBoolean("catalog")) {
                    if (full.stringOrNull("artist") == null && full.optJSONArray("artists")?.length().let { it == null || it == 0 }) {
                        full.put("artist", candidate.optString("artist"))
                    }
                    if (full.stringOrNull("album") == null) full.put("album", candidate.optString("album"))
                    if (thumbnail(full) == null) {
                        catalogArtwork(candidate)?.let { full.put("thumbnail", it) }
                    }
                    // Only adopt the catalog title if the extracted title still matches it.
                    if (full.stringOrNull("track") == null && recordingScore(song, full) >= .8) {
                        full.put("track", candidate.optString("title"))
                    }
                    full.put("explicit", candidate.optBoolean("explicit"))
                }
                if (recordingScore(song, full) >= .8) {
                    selectOriginalAudio(full) // A metadata match with no accessible audio is not usable.
                    return full
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: com.yausername.youtubedl_android.YoutubeDL.CanceledException) {
                throw cancelled
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        return null
    }
    tryCandidates(catalog)?.let { return it }
    tryCandidates(searchVideos())?.let { return it }
    lastFailure?.let { throw it }
    throw IllegalArgumentException("No matching public recording found. Try the song's YouTube Music link.")
}

/** Enrich a linked video without replacing it with a different recording. */
internal fun catalogMetadata(song: ImportSong, candidates: List<JSONObject>): ImportSong {
    val match = candidates.filter { recordingScore(song, it) >= .9 }.maxByOrNull { recordingScore(song, it) }
        ?: return song
    return song.copy(title = match.optString("title"), artist = match.optString("artist"),
        album = song.album.ifBlank { match.optString("album") }, artwork = catalogArtwork(match) ?: song.artwork)
}

internal fun catalogArtwork(candidate: JSONObject): String? {
    val url = thumbnail(candidate) ?: return null
    // YouTube's image service accepts size directives; request the album cover rather than a list thumbnail.
    return if (runCatching { java.net.URI(url).host }.getOrNull() in setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com"))
        url.replace(Regex("=w\\d+-h\\d+[^?]*$"), "=w1200-h1200-l90-rj") else url
}
