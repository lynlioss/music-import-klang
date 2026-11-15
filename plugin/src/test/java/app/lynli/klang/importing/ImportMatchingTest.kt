package app.lynli.klang.importing

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ImportMatchingTest {
    private val song = ImportSong("spotify:track:example", "https://open.spotify.com/track/example",
        "ELEMENT.", "Kendrick Lamar", "DAMN.", 209_000, spotify = true, explicit = true)

    private fun catalog() = YouTubeCatalog.parseSearch(JSONObject(
        requireNotNull(javaClass.getResource("/importing/element-search.json")).readText(),
    ))

    private fun playable(json: JSONObject) = json.put("formats", JSONArray().put(JSONObject()
        .put("format_id", "251").put("vcodec", "none").put("acodec", "opus").put("abr", 140)))

    @Test fun capturedElementResponseParsesRecordingCredits() {
        val rows = catalog()
        val first = rows.first()
        assertEquals("SwvzXM-EVrQ", first.getString("id"))
        assertEquals("ELEMENT.", first.getString("title"))
        assertEquals("Kendrick Lamar", first.getString("artist"))
        assertEquals("DAMN. COLLECTORS EDITION.", first.getString("album"))
        assertEquals(209, first.getInt("duration"))
        assertTrue(first.getBoolean("explicit"))
        assertTrue(recordingScore(song, first) >= .9)
        assertEquals(0.0, recordingScore(song, rows[1]), 0.0)
        assertEquals(0.0, recordingScore(song.copy(explicit = false), first), 0.0)
        assertEquals(0.0, recordingScore(song.copy(title = "ELEMENT. (Live)"), first), 0.0)
    }

    @Test fun unavailableFirstRecordingDoesNotPreventAnotherMatch() = runBlocking {
        val first = catalog().first()
        val second = JSONObject(first.toString()).put("id", "9PumlOWjXMM")
        val attempted = mutableListOf<String>()
        val full = findRecording(song, listOf(first, second), searchVideos = { error("Catalog should succeed") }) { url ->
            attempted.add(url)
            if (url.endsWith("SwvzXM-EVrQ")) throw java.io.IOException("Recording unavailable")
            playable(JSONObject().put("id", "9PumlOWjXMM").put("title", "ELEMENT.")
                .put("channel", "Kendrick Lamar - Topic").put("duration", 209))
        }
        assertEquals(2, attempted.size)
        assertEquals("9PumlOWjXMM", full.getString("id"))
        assertEquals("Kendrick Lamar", full.getString("artist"))
    }

    @Test fun catalogFailureFallsBackToValidatedVideoSearch() = runBlocking {
        val candidate = JSONObject().put("id", "SwvzXM-EVrQ").put("title", "ELEMENT.")
            .put("channel", "Kendrick Lamar - Topic").put("duration", 209)
        val full = findRecording(song, emptyList(), searchVideos = { listOf(candidate) }) { playable(JSONObject(candidate.toString())) }
        assertEquals("SwvzXM-EVrQ", full.getString("id"))
    }

    @Test fun contradictoryFullRecordingDoesNotAcceptCatalogCredits() = runBlocking {
        val error = runCatching {
            findRecording(song, catalog(), searchVideos = { emptyList() }) {
                JSONObject().put("id", "SwvzXM-EVrQ").put("track", "DNA.")
                    .put("artist", "Kendrick Lamar").put("duration", 209)
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun matchingMetadataWithoutAudioTriesAnotherRecording() = runBlocking {
        val first = catalog().first()
        val second = JSONObject(first.toString()).put("id", "9PumlOWjXMM")
        val full = findRecording(song, listOf(first, second), searchVideos = { emptyList() }) { url ->
            val candidate = if (url.endsWith("SwvzXM-EVrQ")) JSONObject(first.toString()) else playable(JSONObject(second.toString()))
            candidate
        }
        assertEquals("9PumlOWjXMM", full.getString("id"))
    }

    @Test fun linkedVideoKeepsItsSourceWhileReceivingAlbumMetadata() {
        val linked = song.copy(sourceId = "youtube:9PumlOWjXMM", url = "https://music.youtube.com/watch?v=9PumlOWjXMM",
            album = "", spotify = false)
        val result = catalogMetadata(linked, catalog())
        assertEquals(linked.url, result.url)
        assertEquals(linked.sourceId, result.sourceId)
        assertEquals("DAMN. COLLECTORS EDITION.", result.album)
        assertTrue(result.artwork.orEmpty().contains("w1200-h1200"))
    }

    @Test fun heyNowVideoMatchesCatalogWithFeaturedArtistInTitle() {
        val rows = YouTubeCatalog.parseSearch(JSONObject(
            requireNotNull(javaClass.getResource("/importing/hey-now-search.json")).readText(),
        ))
        val video = song.copy(title = "hey now", durationMs = 218_000, album = "", spotify = false, explicit = null)
        val metadata = catalogMetadata(video, rows)
        assertEquals("hey now (feat. Dody6)", metadata.title)
        assertEquals("GNX", metadata.album)
        assertEquals("Kendrick Lamar", metadata.artist)
    }

    @Test fun structuredWrongArtistCannotMatchThroughVideoTitle() {
        val candidate = JSONObject().put("title", "Kendrick Lamar - ELEMENT.").put("artist", "Cover Artist")
            .put("channel", "Kendrick Lamar").put("duration", 209)
        assertEquals(0.0, recordingScore(song, candidate), 0.0)
    }
}
