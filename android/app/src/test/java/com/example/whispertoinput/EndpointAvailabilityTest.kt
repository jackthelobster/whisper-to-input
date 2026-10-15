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
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EndpointAvailabilityTest {
    private val settings = AppSettings(
        endpoint = "https://main.example/v1/audio/transcriptions", apiKey = "main-secret",
        fallbackEndpoint = "https://fallback.example/v1/audio/transcriptions", fallbackApiKey = "fallback-secret",
        fallbackModel = "whisper-1", fallbackLanguage = "fr"
    )
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<OkHttpClient>()

    @After fun tearDown() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdownNow()
        }
        servers.forEach { it.shutdown() }
    }

    @Test fun manualRefreshChecksBothEvenWhenMainRecovers() = runBlocking {
        val checked = mutableListOf<AppSettings>()
        val selector = selector { checked += it; true }
        assertEquals(settings, selector.select(settings, checkBoth = true))
        assertEquals(listOf(settings.endpoint, settings.fallbackEndpoint), checked.map { it.endpoint })
        assertEquals(settings.fallbackApiKey, checked.last().apiKey)
    }

    @Test fun manualRefreshRestoresEitherRecoveredEndpoint() = runBlocking {
        var recovered: String? = null
        val selector = selector { it.endpoint == recovered }
        assertNull(selector.select(settings))
        recovered = settings.fallbackEndpoint
        assertEquals(recovered, selector.select(settings)?.endpoint)
        recovered = settings.endpoint
        assertEquals(recovered, selector.select(settings)?.endpoint)
    }

    @Test fun healthyMainChecksBoth() = runBlocking {
        val checked = mutableListOf<String>()
        val selector = selector { checked += it.endpoint; true }
        assertEquals(settings, selector.select(settings))
        assertEquals(listOf(settings.endpoint, settings.fallbackEndpoint), checked)
    }

    @Test fun failedMainImmediatelyChecksFallbackWithItsOwnCredential() = runBlocking {
        val checked = mutableListOf<AppSettings>()
        val selector = selector { checked += it; it.endpoint == settings.fallbackEndpoint }
        val selected = selector.select(settings)!!
        assertEquals(listOf(settings.endpoint, settings.fallbackEndpoint), checked.map { it.endpoint })
        assertEquals("fallback-secret", selected.apiKey)
        assertEquals(settings.fallbackModel, selected.model)
        assertEquals(settings.fallbackLanguage, selected.language)
        assertEquals("", selected.fallbackEndpoint)
    }

    @Test fun recoveredMainWinsOnNextParallelCheck() = runBlocking {
        var mainAvailable = false
        val checked = mutableListOf<String>()
        val selector = selector {
            checked += it.endpoint
            it.endpoint == settings.fallbackEndpoint || mainAvailable
        }
        assertEquals(settings.fallbackEndpoint, selector.select(settings)!!.endpoint)
        mainAvailable = true
        checked.clear()
        assertEquals(settings.endpoint, selector.select(settings)!!.endpoint)
        assertEquals(listOf(settings.endpoint, settings.fallbackEndpoint), checked)
    }

    @Test fun neitherAvailableReturnsNoEndpoint() = runBlocking {
        val checked = mutableListOf<String>()
        assertNull(selector { checked += it.endpoint; false }.select(settings))
        assertEquals(listOf(settings.endpoint, settings.fallbackEndpoint), checked)
    }

    @Test fun optionalFallbackIsNeverCheckedWhenBlank() = runBlocking {
        var count = 0
        assertNull(selector { count++; false }.select(settings.copy(fallbackEndpoint = "")))
        assertEquals(1, count)
    }

    @Test fun probesRunConcurrentlyAndPreferPrimary() = kotlinx.coroutines.test.runTest {
        val started = mutableSetOf<String>()
        val bothStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val selector = EndpointAvailability {
            started += it.endpoint
            if (started.size == 2) bothStarted.complete(Unit)
            bothStarted.await()
            kotlinx.coroutines.delay(3000)
            ProbeResult.Ready
        }
        val before = testScheduler.currentTime
        assertEquals(settings, selector.select(settings, checkBoth = true))
        assertEquals(3000L, testScheduler.currentTime - before)
    }

    @Test fun healthyPrimaryDoesNotWaitForDeadFallback() = kotlinx.coroutines.test.runTest {
        var fallbackCancelled = false
        val selector = EndpointAvailability {
            if (it.endpoint == settings.endpoint) ProbeResult.Ready else {
                try { kotlinx.coroutines.awaitCancellation() } finally { fallbackCancelled = true }
            }
        }
        assertEquals(settings, selector.select(settings))
        assertTrue(fallbackCancelled)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun cancellingSelectionCancelsBothProbes() = kotlinx.coroutines.test.runTest {
        var cancelled = 0
        val selector = EndpointAvailability {
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled++ }
        }
        val task = async { selector.select(settings) }
        testScheduler.runCurrent()
        task.cancel()
        task.join()
        assertEquals(2, cancelled)
    }

    private fun selector(probe: suspend (AppSettings) -> Boolean) = EndpointAvailability {
        if (probe(it)) ProbeResult.Ready else ProbeResult.Unreachable
    }

    private fun server() = MockWebServer().apply { start(); servers += this }
    private fun client() = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }).proxy(Proxy.NO_PROXY).build().also { clients += it }
    private fun settings(server: MockWebServer) = AppSettings(endpoint = server.url("/prefix/v1/audio/transcriptions")
        .newBuilder().host("gx10").build().toString())
    private fun models() = MockResponse().setBody("{\"data\":[{\"id\":\"${AppSettings.DEFAULT_MODEL}\"}]}")

    @Test fun probeUsesModelsWithoutAudioAndPreservesPathPrefix() = runBlocking {
        val server = server()
        server.enqueue(models())
        assertTrue(EndpointProbe(client()).isAvailable(settings(server)))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/prefix/v1/models", request.path)
        assertEquals(0L, request.bodySize)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun errorsMissingModelsAndInvalidBodiesAreUnavailable() = runBlocking {
        val server = server()
        val probe = EndpointProbe(client())
        for (response in listOf(
            MockResponse().setResponseCode(503), MockResponse().setResponseCode(401),
            MockResponse().setResponseCode(404), MockResponse().setBody("<html>error</html>"),
            MockResponse().setBody("{\"data\":[{\"id\":\"other-model\"}]}"),
            MockResponse().setBody("x".repeat(70 * 1024))
        )) {
            server.enqueue(response)
            assertFalse(probe.isAvailable(settings(server)))
        }
    }

    @Test fun probeReportsHttpModelAndProtocolFailuresSeparately() = runBlocking {
        val server = server()
        val probe = EndpointProbe(client())
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ProbeResult.HttpError(401), probe.check(settings(server)))
        server.enqueue(MockResponse().setBody("{\"data\":[]}"))
        assertEquals(ProbeResult.ModelMissing(AppSettings.DEFAULT_MODEL), probe.check(settings(server)))
        server.enqueue(MockResponse().setBody("invalid json"))
        assertEquals(ProbeResult.InvalidResponse, probe.check(settings(server)))
        val target = settings(server)
        server.shutdown()
        assertEquals(ProbeResult.Unreachable, probe.check(target))
    }

    @Test fun probeDistinguishesHeaderAndBodyTimeouts() = runBlocking {
        val server = server()
        val client = client().newBuilder().readTimeout(150, TimeUnit.MILLISECONDS)
            .build().also { clients += it }
        val probe = EndpointProbe(client)
        server.enqueue(models().setHeadersDelay(500, TimeUnit.MILLISECONDS))
        assertEquals(ProbeResult.Timeout, probe.check(settings(server)))
        server.enqueue(models().setBodyDelay(500, TimeUnit.MILLISECONDS))
        assertEquals(ProbeResult.Timeout, probe.check(settings(server)))
        assertEquals(2, server.requestCount)
    }

    @Test fun overallCallTimeoutIsReportedAsTimeout() = runBlocking {
        val server = server()
        server.enqueue(models().setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val client = client().newBuilder().callTimeout(150, TimeUnit.MILLISECONDS)
            .build().also { clients += it }
        assertEquals(ProbeResult.Timeout, EndpointProbe(client).check(settings(server)))
        assertEquals(1, server.requestCount)
    }

    @Test fun selectionRetainsTimeoutEvenWhenFallbackWorks() = runBlocking {
        val selector = EndpointAvailability {
            if (it.endpoint == settings.endpoint) ProbeResult.Timeout else ProbeResult.Ready
        }
        val result = selector.inspect(settings)
        assertTrue(result.timedOut)
        assertEquals(settings.fallbackEndpoint, result.selected?.endpoint)
        assertFalse(EndpointSelection(null, ProbeResult.Unreachable).timedOut)
    }

    @Test fun probeRefusesRedirectAndInsecureCredentials() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/leak")))
        val probe = EndpointProbe(client())
        assertFalse(probe.isAvailable(settings(server)))
        assertFalse(probe.isAvailable(settings(server).copy(apiKey = "secret")))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationCancelsProbeCall() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = AtomicReference<Call>()
        val started = CountDownLatch(1)
        val client = client().newBuilder().eventListener(object : EventListener() {
            override fun callStart(value: Call) { call.set(value); started.countDown() }
        }).build().also { clients += it }
        val task = async(Dispatchers.IO) { EndpointProbe(client).isAvailable(settings(server)) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        task.cancel()
        try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertTrue(call.get().isCanceled())
    }
}
