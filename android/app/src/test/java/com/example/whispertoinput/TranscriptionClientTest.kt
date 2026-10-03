package com.example.whispertoinput

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TranscriptionClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val networkClients = mutableListOf<OkHttpClient>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() {
        networkClients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdownNow()
        }
        server.shutdown()
    }

    private fun httpClient(): OkHttpClient = TranscriptionClient.newHttpClient().newBuilder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        })
        .proxy(Proxy.NO_PROXY).build().also { networkClients += it }

    private fun settings() = AppSettings(endpoint = server.url("/v1/audio/transcriptions?tenant=test")
        .newBuilder().host("gx10").build().toString())
    private fun audio() = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    @Test fun wireRequestHasCorrectMultipartBoundaryAndNoAccidentalQuery() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"text\":\"hello world\"}"))
        assertEquals("hello world", TranscriptionClient(httpClient()).transcribe(settings(), audio(), "audio/mp4"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions?tenant=test", request.path)
        assertNull(request.getHeader("Authorization"))
        val contentType = request.getHeader("Content-Type")!!
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        val boundary = contentType.substringAfter("boundary=")
        val payload = request.body.readUtf8()
        assertTrue(payload.startsWith("--$boundary\r\n"))
        assertTrue(payload.contains("name=\"model\""))
        assertTrue(payload.contains("\r\n\r\n${AppSettings.DEFAULT_MODEL}\r\n"))
        assertTrue(payload.contains("name=\"language\""))
        assertTrue(payload.contains("\r\n\r\nen\r\n"))
    }

    @Test fun plainTextResponseIsAlsoAccepted() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(" recognized text \n"))
        assertEquals("recognized text", TranscriptionClient(httpClient()).transcribe(settings(), audio(), "audio/mp4"))
    }

    @Test fun httpErrorIsSanitizedAndRecordingRemainsForRetry() = runBlocking {
        val recording = audio()
        server.enqueue(MockResponse().setResponseCode(500).setBody("private traceback unit-test-token".repeat(1000)))
        try {
            TranscriptionClient(httpClient()).transcribe(settings(), recording, "audio/mp4")
            fail("Expected error")
        } catch (e: TranscriptionException) {
            assertFalse(e.message!!.contains("private traceback"))
            assertFalse(e.message!!.contains("unit-test-token"))
            assertTrue(recording.exists())
        }
    }

    @Test fun redirectIsNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/leak")))
        server.enqueue(MockResponse().setBody("should never be requested"))
        try {
            TranscriptionClient(httpClient()).transcribe(settings(), audio(), "audio/mp4")
            fail("Expected disabled redirect")
        } catch (e: TranscriptionException) {
            assertTrue(e.message!!.contains("redirect"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun boundedChunkedBodyIsRejected() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain")
            .setChunkedBody("a".repeat(TranscriptionProtocol.MAX_RESPONSE_BYTES + 1), 8192))
        try {
            TranscriptionClient(httpClient()).transcribe(settings(), audio(), "audio/mp4")
            fail("Expected size limit")
        } catch (e: TranscriptionException) {
            assertTrue(e.message!!.contains("size limit"))
        }
    }

    @Test fun defaultTimeoutsAndRedirectPolicyAreExplicit() {
        val client = httpClient()
        assertEquals(15000, client.connectTimeoutMillis)
        assertEquals(60000, client.writeTimeoutMillis)
        assertEquals(120000, client.readTimeoutMillis)
        assertEquals(150000, client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test fun timeoutProducesSafeError() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = httpClient().newBuilder().readTimeout(100, TimeUnit.MILLISECONDS)
            .callTimeout(500, TimeUnit.MILLISECONDS).build().also { networkClients += it }
        try {
            TranscriptionClient(client).transcribe(settings(), audio(), "audio/mp4")
            fail("Expected timeout")
        } catch (e: TranscriptionException) {
            assertTrue(e.message!!.contains("timed out"))
        }
    }

    @Test fun coroutineCancellationCancelsTheActualCallAndPreservesAudio() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = AtomicReference<Call>()
        val started = CountDownLatch(1)
        val client = httpClient().newBuilder().eventListener(object : EventListener() {
            override fun callStart(value: Call) { call.set(value); started.countDown() }
        }).build().also { networkClients += it }
        val recording = audio()
        val task = async(Dispatchers.IO) { TranscriptionClient(client).transcribe(settings(), recording, "audio/mp4") }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        task.cancel()
        try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertTrue(call.get().isCanceled())
        assertTrue(recording.exists())
    }
}
