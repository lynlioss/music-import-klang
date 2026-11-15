package app.lynli.klang.importing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ImportTest {
    @Test fun albumPreviewRetainsEveryTrackAndSuppliesAlbumCoverAndTrackNumbers() {
        val entity = JSONObject(requireNotNull(javaClass.getResource("/importing/album-embed.json")).readText())
        val rows = entity.getJSONArray("trackList").objects().map(SpotifyMetadata::parseEmbedTrack)
        val songs = SpotifyMetadata.albumSongs(entity, rows)
        assertEquals(12, songs.size)
        assertEquals("GNX", songs.first().album)
        assertEquals("Kendrick Lamar", songs.first().artist)
        assertNotNull(songs.first().artwork)
        assertEquals(12, songs.last().trackNumber)
        assertEquals("wacced out murals", songs.first().title)
    }
    @Test fun acceptsSpotifyAlbumsAndYouTubeMusicAlbumBrowseLinks() {
        val spotify = ImportLink.parse("https://open.spotify.com/album/0hvT3yIEysuuvkK73vgdcW")
        assertEquals("album", spotify.kind)
        assertTrue(spotify.playlist)
        assertEquals(spotify, ImportLink.parse("spotify:album:0hvT3yIEysuuvkK73vgdcW"))
        val youtube = ImportLink.parse("https://music.youtube.com/browse/MPREb_wCND3crJyDP")
        assertEquals("album", youtube.kind)
        assertTrue(youtube.playlist)
    }
    private val spotifyId = "4cOdK2wGLETKBW3PvgPWqT"
    private val song = ImportSong("spotify:track:$spotifyId", "https://open.spotify.com/track/$spotifyId",
        "Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213_000,
        "https://example.com/cover.jpg", "1987-11-12", 1, 1, true)

    @Test fun canonicalizesSharedLinksWithoutTracking() {
        val link = ImportLink.parse(" https://open.spotify.com/intl-de/track/$spotifyId?si=tracking ")
        assertEquals(song.url, link.url)
        assertFalse(link.playlist)
        assertEquals(link, ImportLink.parse("spotify:track:$spotifyId"))
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            ImportLink.parse("https://youtu.be/dQw4w9WgXcQ?si=tracking").url)
    }

    @Test fun songSharedFromPlaylistDoesNotImportEntirePlaylist() {
        val link = ImportLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890123")
        assertFalse(link.playlist)
        assertTrue(ImportLink.parse("https://music.youtube.com/playlist?list=PL1234567890123").playlist)
    }

    @Test fun rejectsLookalikeHostsAndCommands() {
        listOf("https://youtube.com.attacker.org/watch?v=dQw4w9WgXcQ",
            "https://user@music.youtube.com/watch?v=dQw4w9WgXcQ", "file:///etc/passwd",
            "--exec touch /tmp/anything").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ImportLink.parse(value) }
        }
    }

    @Test fun matchesOfficialRecordingAndRejectsWrongVersions() {
        fun candidate(title: String, artist: String = "Rick Astley - Topic", duration: Int = 213) =
            JSONObject().put("title", title).put("uploader", artist).put("duration", duration)
        assertTrue(recordingScore(song, candidate(song.title)) >= .72)
        assertEquals(0.0, recordingScore(song, candidate(song.title + " (Live)")), 0.0)
        assertEquals(0.0, recordingScore(song, candidate(song.title + " (Karaoke)")), 0.0)
        assertEquals(0.0, recordingScore(song, candidate(song.title, "Cover Artist")), 0.0)
        assertEquals(0.0, recordingScore(song, candidate(song.title, duration = 350)), 0.0)
        assertTrue(recordingScore(song.copy(title = song.title + " (Live)"), candidate(song.title + " (Live)")) >= .72)
    }

    @Test fun flatSearchChannelAndStructuredArtistsBothMatch() {
        val element = song.copy(title = "ELEMENT.", artist = "Kendrick Lamar", durationMs = 209_000)
        val flat = JSONObject().put("title", "ELEMENT.").put("channel", "Kendrick Lamar - Topic").put("duration", 209)
        assertTrue(recordingScore(element, flat) >= .8)
        assertEquals(0.0, recordingScore(element, flat.put("channel", "Someone else")), 0.0)
        flat.remove("channel")
        flat.put("artists", JSONArray(listOf("Kendrick Lamar")))
        assertTrue(recordingScore(element, flat) >= .8)
        assertEquals(0.0, recordingScore(element, flat.put("title", "DNA.")), 0.0)
    }

    @Test fun cleansOnlyPresentationTextFromYouTubeTitles() {
        assertEquals("hey now", cleanVideoTitle("Kendrick Lamar - hey now (Official Audio)", "Kendrick Lamar"))
        assertEquals("hey now (Live)", cleanVideoTitle("Kendrick Lamar - hey now (Live)", "Kendrick Lamar"))
        assertEquals("Artist - hey now", cleanVideoTitle("Artist - hey now", "Other Artist"))
        val info = JSONObject().put("title", "Kendrick Lamar - hey now (Official Audio)")
            .put("uploader", "Kendrick Lamar").put("duration", 217)
        assertEquals("hey now", youtubeSong(info, "9PumlOWjXMM").title)
    }

    @Test fun unknownAlbumDoesNotInheritSourceContainerTag() {
        val info = JSONObject().put("album", "Klang")
        tagInfo(info, song.copy(album = ""))
        assertFalse(info.has("album"))
        assertEquals("", info.getString("meta_album"))
    }

    @Test fun engineInitializationErrorsHaveAnActionableMessage() {
        val error = ImportEngineException(ExceptionInInitializerError(RuntimeException("class zh is not a concrete class")))
        assertTrue(importError(error).contains("download engine"))
        assertTrue(error.cause is ExceptionInInitializerError)
        assertTrue(importError(java.io.InterruptedIOException("timeout")).contains("too long"))
    }

    @Test fun tagsUseReleaseDateAndAlbumArtwork() {
        val info = JSONObject().put("title", "Video title").put("upload_date", "20261115")
        tagInfo(info, song)
        assertEquals(song.title, info.getString("track"))
        assertEquals(song.artist, info.getString("artist"))
        assertEquals(song.album, info.getString("album"))
        assertEquals("1987-11-12", info.getString("meta_date"))
        assertEquals(1, info.getInt("track_number"))
        assertEquals(song.artwork, info.getJSONArray("thumbnails").getJSONObject(0).getString("url"))
    }

    @Test fun publicSpotifyMetadataSelectsLargestCover() {
        val json = JSONObject().put("uri", song.sourceId).put("name", song.title)
            .put("artists", JSONObject().put("items", JSONArray().put(JSONObject()
                .put("profile", JSONObject().put("name", song.artist)))))
            .put("duration", JSONObject().put("totalMilliseconds", song.durationMs))
            .put("albumOfTrack", JSONObject().put("name", song.album).put("coverArt", JSONObject()
                .put("sources", JSONArray().put(JSONObject().put("width", 64).put("url", "small"))
                    .put(JSONObject().put("width", 640).put("url", "large")))))
        val metadata = SpotifyMetadata.parsePublicTrack(json)
        assertEquals("large", metadata.artwork)
        assertEquals(song.album, metadata.album)
        assertEquals(song.artist, metadata.artist)
        json.remove("duration")
        json.put("trackDuration", JSONObject().put("totalMilliseconds", song.durationMs))
        assertEquals(song.durationMs, SpotifyMetadata.parsePublicTrack(json).durationMs)
        val state = JSONObject().put("entities", JSONObject().put("items", JSONObject().put(song.sourceId, json)))
        val html = "<script id=\"initialState\" type=\"text/plain\">" +
            Base64.getEncoder().encodeToString(state.toString().toByteArray()) + "</script>"
        assertEquals(song.title, SpotifyMetadata.publicEntity(html, song.sourceId)?.getString("name"))
    }
}
