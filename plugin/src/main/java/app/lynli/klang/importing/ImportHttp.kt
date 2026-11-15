package app.lynli.klang.importing

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Call timeout includes redirects and the response body; coroutine cancellation cancels the socket. */
internal object ImportHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun getText(url: String, bearer: String? = null, httpClient: OkHttpClient = client,
        headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Language", "en")
            .apply {
                bearer?.let { header("Authorization", "Bearer $it") }
                headers.forEach { (key, value) -> header(key, value) }
            }.build()
        return execute(request, httpClient)
    }

    suspend fun postJson(url: String, json: String, httpClient: OkHttpClient = client): String = execute(
        Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build(), httpClient,
    )

    private suspend fun execute(request: Request, httpClient: OkHttpClient): String = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        require(it.isSuccessful) {
                            when (it.code) {
                                429 -> "The music service is busy. Try again in a few minutes."
                                401, 403, 404 -> "This link isn't publicly accessible. Check its sharing settings."
                                else -> "Couldn't read the music link (${it.code}). Try again."
                            }
                        }
                        val body = requireNotNull(it.body) { "The music service returned an empty response." }
                        val limit = 4 * 1024 * 1024L
                        val source = body.source()
                        require(!source.request(limit + 1)) { "The music service returned too much metadata." }
                        source.readUtf8()
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }
}
