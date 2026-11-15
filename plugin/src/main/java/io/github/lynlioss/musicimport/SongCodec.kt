package io.github.lynlioss.musicimport

import app.lynli.klang.importing.ImportSong
import app.lynli.klang.importing.stringOrNull
import org.json.JSONObject

internal fun songJson(song: ImportSong): JSONObject {
    val result = JSONObject()
    result.put("id", song.sourceId)
    result.put("url", song.url)
    result.put("title", song.title)
    result.put("artist", song.artist)
    result.put("album", song.album)
    result.put("duration", song.durationMs)
    result.put("artwork", song.artwork)
    result.put("date", song.date)
    result.put("track", song.trackNumber)
    result.put("disc", song.discNumber)
    result.put("spotify", song.spotify)
    result.put("explicit", song.explicit)
    return result
}
internal fun readSong(json: JSONObject): ImportSong {
    val id = json.getString("id")
    val url = json.getString("url")
    val title = json.getString("title")
    val artist = json.optString("artist")
    val album = json.optString("album")
    val duration = json.optLong("duration")
    val artwork = json.stringOrNull("artwork")
    val date = json.stringOrNull("date")
    val track = json.optInt("track")
    val disc = json.optInt("disc")
    val spotify = json.optBoolean("spotify")
    val explicit = if (json.isNull("explicit")) null else json.optBoolean("explicit")
    return ImportSong(id, url, title, artist, album, duration, artwork, date, track, disc, spotify, explicit)
}
