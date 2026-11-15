package app.lynli.klang.importing

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImportHttpTest {
    @Test fun catalogPostUsesJsonAndReturnsMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"contents\":[]}"))
            assertEquals("{\"contents\":[]}", ImportHttp.postJson(server.url("/search").toString(), "{\"query\":\"ELEMENT.\"}"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("https://music.youtube.com", request.getHeader("Origin"))
            assertEquals("{\"query\":\"ELEMENT.\"}", request.body.readUtf8())
        }
    }

    @Test fun acceptsMetadataAndReportsServiceErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("song metadata"))
            assertEquals("song metadata", ImportHttp.getText(server.url("/track").toString()))
            server.enqueue(MockResponse().setResponseCode(429))
            val error = runCatching { ImportHttp.getText(server.url("/track").toString()) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("busy"))
        }
    }

    @Test fun stalledBodyIsBoundedByTotalCallTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("metadata").setBodyDelay(2, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()
            val error = withTimeout(1500) {
                runCatching { ImportHttp.getText(server.url("/track").toString(), httpClient = client) }.exceptionOrNull()
            }
            assertTrue(error is IOException)
        }
    }

    @Test fun cancellationClosesTheUnderlyingCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { started.countDown() }
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val request = async { ImportHttp.getText(server.url("/track").toString(), httpClient = client) }
            withTimeout(1500) { while (started.count > 0) delay(10) }
            request.cancel()
            request.join()
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        }
    }
}
