package app.lynli.klang.importing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportAudioTest {
    private fun audio(id: String, codec: String, rate: Int) = JSONObject().put("format_id", id)
        .put("vcodec", "none").put("acodec", codec).put("abr", rate)
    private fun info(vararg formats: JSONObject) = JSONObject().put("formats", JSONArray(formats.toList()))

    @Test fun keepsBestSourceCodecAndRejectsDrcWhenOriginalIsAvailable() {
        val result = selectOriginalAudio(info(audio("249", "opus", 50), audio("140", "mp4a.40.2", 129),
            audio("251", "opus", 140), audio("251-drc", "opus", 141)))
        assertEquals("251", result.id)
        assertEquals("OPUS · 140 kbps", result.description)
    }

    @Test fun premiumOrLosslessSourcesAreNotCappedToItag251() {
        assertEquals("141", selectOriginalAudio(info(audio("251", "opus", 140), audio("141", "mp4a.40.2", 256))).id)
        assertEquals("FLAC · 900 kbps", selectOriginalAudio(info(audio("lossless", "flac", 900))).description)
    }

    @Test fun excludesVideoDrmAndFormatsThatWouldRequireTranscoding() {
        assertThrows(IllegalArgumentException::class.java) {
            selectOriginalAudio(info(audio("18", "aac", 128).put("vcodec", "h264"),
                audio("protected", "aac", 256).put("has_drm", true), audio("other", "ac3", 320)))
        }
    }
}
