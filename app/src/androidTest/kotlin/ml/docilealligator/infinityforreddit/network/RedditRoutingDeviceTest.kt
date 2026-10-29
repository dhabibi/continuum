package ml.docilealligator.infinityforreddit.network

import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.InetAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.activities.WebViewActivity
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run only on a disposable emulator. Forward the deployed private tunnel to the host's port10888,
 * then pass -e tunnelUpstreamHost 10.0.2.2 -e tunnelUpstreamPort 10888 to am instrument.
 * The local relay records only counts; all TLS stays end-to-end with the public Reddit fixture.
 */
@RunWith(AndroidJUnit4::class)
class RedditRoutingDeviceTest {
    @Test fun realWebViewImagesServiceWorkersRedirectsAndNativeRequestsFailClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val upstreamHost = requireNotNull(args.getString("tunnelUpstreamHost")) { "Disposable emulator tunnel fixture is required" }
        val upstreamPort = args.getString("tunnelUpstreamPort", "10888").toInt()
        val prefs = context.getSharedPreferences(SharedPreferencesUtils.DEFAULT_PREFERENCES_FILE, 0)
        val originalBase = prefs.getString(SharedPreferencesUtils.API_BASE_URL_PREF_KEY, null)
        prefs.edit().putString(SharedPreferencesUtils.API_BASE_URL_PREF_KEY, "https://127.0.0.1:10443/").commit()
        val oldSelector = ProxySelector.getDefault()
        ProxySelector.setDefault(null)
        RedditRouting.install(context)
        Relay(upstreamHost, upstreamPort).use { relay ->
            try {
                val native = RedditRouting.clientBuilder(context).callTimeout(20, TimeUnit.SECONDS).build()
                native.newCall(Request.Builder().url(IMAGE + "?native=" + System.nanoTime()).build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.body!!.bytes().size > 100)
                }
                assertTrue("Native CONNECT was observed", relay.connects.get() > 0)
                val intent = Intent(context, WebViewActivity::class.java).setData(android.net.Uri.parse("about:blank"))
                ActivityScenario.launch<WebViewActivity>(intent).use { scenario ->
                    lateinit var web: WebView
                    scenario.onActivity { activity -> web = activity.findViewById(R.id.web_view_web_view_activity) }
                    fun load(path: String) = instrumentation.runOnMainSync {
                        web.loadUrl("http://127.0.0.1:" + relay.originPort + path)
                    }
                    fun result(): String {
                        val done = CountDownLatch(1)
                        val value = AtomicReference("")
                        instrumentation.runOnMainSync {
                            web.evaluateJavascript("String(window.imageResult)+'|'+String(window.workerResult)") {
                                value.set(it); done.countDown()
                            }
                        }
                        assertTrue(done.await(3, TimeUnit.SECONDS))
                        return value.get()
                    }
                    fun awaitResult(expected: String) {
                        val until = SystemClock.elapsedRealtime() + 30000
                        var last = ""
                        while (SystemClock.elapsedRealtime() < until) {
                            last = result()
                            if (last.contains(expected)) return
                            SystemClock.sleep(200)
                        }
                        fail("WebView result: expected $expected, got $last")
                    }
                    load("/page?first")
                    awaitResult("loaded|loaded")
                    assertTrue("Chromium used CONNECT", relay.connects.get() >= 2)
                    relay.enabled = false
                    relay.closeConnections()
                    val before = relay.connects.get()
                    load("/page?outage")
                    awaitResult("failed|failed")
                    assertTrue("Blocked Chromium attempts reached the tunnel", relay.connects.get() > before)
                    try {
                        native.newCall(Request.Builder().url(IMAGE + "?outage=" + System.nanoTime()).build()).execute().close()
                        fail("Native Reddit request bypassed an unavailable tunnel")
                    } catch (_: IOException) { }
                    relay.enabled = true
                    load("/redirect")
                    val deadline = SystemClock.elapsedRealtime() + 20000
                    val finalUrl = AtomicReference("")
                    while (SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync { finalUrl.set(web.url ?: "") }
                        if (finalUrl.get().startsWith(IMAGE)) break
                        SystemClock.sleep(200)
                    }
                    assertTrue("Cross-host redirect remains in protected WebView", finalUrl.get().startsWith(IMAGE))
                    load("/page?recovered")
                    awaitResult("loaded|loaded")
                }
                println("REAL_ANDROID_ROUTING_OK native, WebView image, service worker, redirect, outage, recovery; CONNECT count=" + relay.connects.get())
            } finally {
                prefs.edit().putString(SharedPreferencesUtils.API_BASE_URL_PREF_KEY, originalBase).commit()
                ProxySelector.setDefault(oldSelector)
            }
        }
    }

    private class Relay(val upstreamHost: String, val upstreamPort: Int) : AutoCloseable {
        @Volatile var enabled = true
        @Volatile var closed = false
        val connects = AtomicInteger()
        private val pool = Executors.newFixedThreadPool(16)
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val proxy = ServerSocket(10444, 20, InetAddress.getByName("127.0.0.1"))
        private val origin = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val originPort get() = origin.localPort

        init {
            pool.execute { accept(proxy, true) }
            pool.execute { accept(origin, false) }
        }

        private fun accept(server: ServerSocket, tunnel: Boolean) {
            while (!closed) {
                try {
                    val client = server.accept()
                    sockets.add(client)
                    pool.execute {
                        try { if (tunnel) connect(client) else page(client) }
                        catch (_: IOException) { }
                        finally { sockets.remove(client); client.close() }
                    }
                } catch (_: IOException) { if (!closed) throw AssertionError("Fixture listener failed") }
            }
        }

        private fun header(socket: Socket): String {
            socket.soTimeout = 15000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val first = reader.readLine() ?: throw IOException("Empty request")
            while (!reader.readLine().isNullOrEmpty()) { }
            return first
        }

        private fun connect(client: Socket) {
            val first = header(client)
            connects.incrementAndGet()
            if (!enabled || !first.startsWith("CONNECT ")) {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            Socket(upstreamHost, upstreamPort).use { remote ->
                sockets.add(remote)
                try {
                    remote.soTimeout = 20000
                    remote.getOutputStream().write((first + "\r\nHost: " + first.split(" ")[1] + "\r\n\r\n").toByteArray())
                    val upload = pool.submit { try { client.getInputStream().copyTo(remote.getOutputStream()) } catch (_: IOException) {} }
                    try { remote.getInputStream().copyTo(client.getOutputStream()) } finally { upload.cancel(true) }
                } finally { sockets.remove(remote) }
            }
        }

        private fun page(client: Socket) {
            val path = header(client).split(" ")[1]
            val image = IMAGE + "?devicefixture=" + System.nanoTime()
            val body: String
            if (path.startsWith("/redirect")) {
                client.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: " + image + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                return
            } else if (path.startsWith("/sw.js")) {
                body = "self.addEventListener('message',e=>{fetch('" + image + "',{mode:'no-cors',cache:'no-store'}).then(()=>e.source.postMessage('loaded')).catch(()=>e.source.postMessage('failed'));});"
            } else {
                body = "<html><body><img src='" + image + "' onload=\"window.imageResult='loaded'\" onerror=\"window.imageResult='failed'\"><script>navigator.serviceWorker.addEventListener('message',e=>window.workerResult=e.data);navigator.serviceWorker.register('/sw.js').then(()=>navigator.serviceWorker.ready).then(r=>r.active.postMessage('fetch')).catch(e=>window.workerResult='worker-error:'+e);</script></body></html>"
            }
            val bytes = body.toByteArray()
            val type = if (path.startsWith("/sw.js")) "application/javascript" else "text/html"
            client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nCache-Control: no-store\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray())
            client.getOutputStream().write(bytes)
        }

        fun closeConnections() {
            val active = synchronized(sockets) { sockets.toList() }
            active.forEach { try { it.close() } catch (_: IOException) {} }
        }
        override fun close() {
            closed = true
            proxy.close(); origin.close(); closeConnections(); pool.shutdownNow()
        }
    }
    companion object { private const val IMAGE = "https://www.redditstatic.com/desktop2x/img/favicon/favicon-32x32.png" }
}
