package app.lynli.klang.importing

import org.json.JSONArray
import org.json.JSONObject

internal fun tagInfo(info: JSONObject, song: ImportSong) {
    info.put("title", song.title)
    info.put("track", song.title)
    info.put("artist", song.artist.ifBlank { "Unknown artist" })
    info.put("artists", JSONArray(listOf(song.artist.ifBlank { "Unknown artist" })))
    if (song.album.isNotBlank()) info.put("album", song.album)
    else {
        info.remove("album")
        // Explicitly clear any container album inherited from the source.
        info.put("meta_album", "")
    }
    song.date?.let {
        info.put("release_date", it.replace("-", "").take(8))
        info.put("meta_date", it)
    }
    info.put("album_artist", song.albumArtist.ifBlank { song.artist }.ifBlank { "Unknown artist" })
    if (song.trackNumber > 0) info.put("track_number", song.trackNumber)
    if (song.discNumber > 0) info.put("disc_number", song.discNumber)
    song.artwork?.let { url ->
        info.put("thumbnail", url)
        info.put("thumbnails", JSONArray().put(JSONObject().put("url", url).put("id", "cover")))
    }
}

internal class ImportEngineException(cause: LinkageError) : Exception(
    "The bundled download engine couldn't start. Update Klang and try again.", cause,
)

internal fun importError(error: Exception): String {
    val raw = error.message.orEmpty()
    return when {
        error is ImportEngineException -> error.message.orEmpty()
        error is java.io.InterruptedIOException -> "The music service took too long to respond. Try again."
        raw.contains("Sign in", true) || raw.contains("bot", true) -> "YouTube requires sign-in for this recording. Try another public song link."
        raw.contains("429") -> "The service is rate limiting requests. Wait a few minutes, then retry."
        raw.contains("403") -> "The audio source refused access. Retry later or choose another recording."
        raw.contains("timed out", true) || raw.contains("resolve host", true) -> "Connection interrupted. Check your network and retry."
        error is IllegalArgumentException || error is IllegalStateException -> raw.take(240).ifBlank { "Couldn't import this song. Try again." }
        else -> "Couldn't download this recording. Retry or use another public song link."
    }
}
