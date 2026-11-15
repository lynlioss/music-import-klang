package io.github.lynlioss.musicimport

import android.app.Service
import android.content.Intent
import android.os.*
import app.lynli.klang.importing.*
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** API 1 IPC: opaque provider metadata in, tagged original file out. Klang owns all library decisions. */
class MusicImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!allowed(message.sendingUid)) return
            val reply = message.replyTo ?: return
            val token = message.data.getString("token") ?: return
            val key = "${message.sendingUid}:$token"
            if (message.what == CANCEL) { jobs.remove(key)?.cancel(); ImportEngine.stop(key); return }
            if (message.what !in listOf(RESOLVE, DOWNLOAD) || jobs.containsKey(key)) return
            val payload = message.data.getString("json").orEmpty()
            val output = message.data.getParcelable("output", ParcelFileDescriptor::class.java)
            val downloading = message.what == DOWNLOAD
            val death = IBinder.DeathRecipient { jobs.remove(key)?.cancel(); ImportEngine.stop(key) }
            try { reply.binder.linkToDeath(death, 0) } catch (_: RemoteException) { output?.close(); return }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (downloading) {
                        requireNotNull(output) { "Missing output file" }
                        download(JSONObject(payload), output, key) { state -> send(reply, PROGRESS, token, state.toString()) }
                            .let { send(reply, RESULT, token, it.toString()) }
                    } else {
                        val preview = withTimeout(120_000) {
                            ImportResolver(this@MusicImportService).resolve(ImportLink.parse(payload), key)
                        }
                        val json = JSONObject().put("title", preview.title).put("collection", preview.link.playlist)
                            .put("notice", preview.notice).put("artwork", preview.artwork)
                            .put("songs", JSONArray(preview.songs.map(::songJson)))
                        // Pipe large playlists rather than exceeding Binder's transaction buffer.
                        val pipe = ParcelFileDescriptor.createPipe()
                        try {
                            reply.send(Message.obtain(null, RESULT).apply { data = Bundle().apply {
                                putString("token", token); putParcelable("metadata", pipe[0])
                            } })
                            pipe[0].close()
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                        } finally { pipe.forEach { runCatching { it.close() } } }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    ImportEngine.stop(key)
                    send(reply, FAILURE, token, "Reading this link took too long. Try again.")
                } catch (cancelled: CancellationException) {
                    ImportEngine.stop(key)
                    throw cancelled
                } catch (error: LinkageError) {
                    send(reply, FAILURE, token, "The download engine couldn't start. Update the plugin and try again.")
                } catch (error: Exception) {
                    android.util.Log.e("MusicImportPlugin", "Import failed", error)
                    send(reply, FAILURE, token, importError(error))
                } finally {
                    output?.close()
                    jobs.remove(key)
                    runCatching { reply.binder.unlinkToDeath(death, 0) }
                }
            }
            jobs[key] = job
            job.start()
        }
    })

    private fun allowed(uid: Int): Boolean = packageManager.getPackagesForUid(uid)?.contains("io.github.lynlioss.klang") == true
    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "downloads").deleteRecursively()
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { jobs.keys.forEach(ImportEngine::stop); scope.cancel(); super.onDestroy() }

    private fun send(reply: Messenger, code: Int, token: String, json: String) {
        try {
            reply.send(Message.obtain(null, code).apply { data = Bundle().apply { putString("token", token); putString("json", json) } })
        } catch (_: RemoteException) { /* Binder death cancels the owning job. */ }
    }

    private suspend fun download(payload: JSONObject, output: ParcelFileDescriptor, processId: String,
        progress: (JSONObject) -> Unit): JSONObject {
        val directory = File(cacheDir, "downloads/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            runInterruptible { ImportEngine.initialize(this); ImportEngine.refreshExtractor(this) }
            val original = readSong(payload)
            progress(JSONObject().put("phase", "matching").put("progress", 0))
            val (song, catalog) = coroutineScope {
                val metadata = async { withTimeoutOrNull(6_000) { SpotifyMetadata.enrich(original) } ?: original }
                val matches = async { if (original.spotify) YouTubeCatalog.searchOrEmpty(original) else emptyList() }
                metadata.await() to matches.await()
            }
            val info = withTimeout(120_000) {
                if (song.spotify) findRecording(song, catalog, searchVideos = {
                    runInterruptible { ImportEngine.info("ytsearch10:${song.artist} ${song.title} official audio", processId, flat = true)
                        .optJSONArray("entries")?.objects().orEmpty() }
                }, extract = { url -> runInterruptible { ImportEngine.info(url, processId) } })
                else runInterruptible { ImportEngine.info(song.url, processId) }
            }
            val metadata = if (song.spotify) song.copy(album = song.album.ifBlank { info.optString("album") },
                artwork = song.artwork ?: thumbnail(info)) else {
                val linked = youtubeSong(info, info.getString("id"))
                if (linked.album.isBlank() && linked.artist.isNotBlank()) catalogMetadata(linked, YouTubeCatalog.searchOrEmpty(linked)) else linked
            }
            val audio = selectOriginalAudio(info)
            tagInfo(info, metadata)
            val source = File(directory, "source.json").apply { writeText(info.toString()) }
            val request = ImportEngine.request().apply {
                addOption("--load-info-json", source.absolutePath); addOption("--no-playlist")
                addOption("-f", audio.id); addOption("--postprocessor-args", STREAM_COPY_ARGUMENTS)
                addOption("--extract-audio"); addOption("--audio-format", "best")
                addOption("--embed-metadata"); addOption("--embed-thumbnail"); addOption("--convert-thumbnails", "jpg")
                addOption("--concurrent-fragments", 8); addOption("--http-chunk-size", "1M")
                addOption("--fragment-retries", 5); addOption("--abort-on-unavailable-fragments")
                addOption("--newline"); addOption("--no-mtime")
                addOption("-o", File(directory, "audio.%(ext)s").absolutePath)
            }
            var lastUpdate = 0L
            runInterruptible {
                YoutubeDL.execute(request, processId) { percent, eta, line ->
                    val now = System.currentTimeMillis()
                    val tagging = listOf("[ExtractAudio]", "[Metadata]", "[EmbedThumbnail]").any(line::startsWith)
                    if (now - lastUpdate >= 300 || tagging) {
                        lastUpdate = now
                        progress(JSONObject().put("phase", if (tagging) "tagging" else "downloading")
                            .put("progress", (percent / 100).coerceIn(0f, 1f)).put("eta", eta)
                            .put("audioFormat", audio.description))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val file = directory.listFiles()?.firstOrNull { it.name.startsWith("audio.") && it.extension in
                listOf("m4a", "opus", "ogg", "flac", "mp3") && it.length() > 0 } ?: error("No playable file downloaded")
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { destination -> file.inputStream().use { it.copyTo(destination) } }
            return JSONObject().put("extension", file.extension).put("song", songJson(metadata))
                .put("audioFormat", audio.description).put("recording", info.optString("webpage_url"))
        } finally { directory.deleteRecursively() }
    }
    companion object {
        const val RESOLVE = 1
        const val DOWNLOAD = 2
        const val CANCEL = 3
        const val RESULT = 10
        const val PROGRESS = 11
        const val FAILURE = 12
    }
}
