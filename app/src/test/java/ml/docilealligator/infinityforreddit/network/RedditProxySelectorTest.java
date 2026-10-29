package ml.docilealligator.infinityforreddit.network;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;

public class RedditProxySelectorTest {
    private static Proxy proxy(int port) {
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
    }

    @Test public void ownedHostsHaveOneMandatoryRouteAndHostBoundariesAreExact() {
        Proxy tunnel = proxy(10444);
        RedditProxySelector selector = new RedditProxySelector(tunnel, null);
        for (String root : RedditProxySelector.DOMAIN_SUFFIXES) {
            for (String host : Arrays.asList(root, "child." + root, "CHILD." + root.toUpperCase(Locale.ROOT) + ".")) {
                assertEquals(Collections.singletonList(tunnel), selector.select(URI.create("https://" + host + "/")));
            }
            assertFalse(RedditProxySelector.isRedditHost(root + ".example.org"));
            assertFalse(RedditProxySelector.isRedditHost("not" + root));
        }
        for (String host : RedditProxySelector.EXACT_HOSTS) {
            assertTrue(RedditProxySelector.isRedditHost(host));
            assertFalse(RedditProxySelector.isRedditHost("other." + host));
        }
        assertFalse(RedditProxySelector.isRedditHost(null));
        assertFalse(RedditProxySelector.isRedditHost("redgifs.com"));
        assertFalse(RedditProxySelector.isRedditHost("imgur.com"));
    }

    @Test public void failureNeverReplacesRedditRouteAndOtherTrafficKeepsItsPolicy() {
        AtomicInteger notifications = new AtomicInteger();
        Proxy other = proxy(9999);
        ProxySelector fallback = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return Collections.singletonList(other); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { notifications.incrementAndGet(); }
        };
        Proxy tunnel = proxy(10444);
        RedditProxySelector selector = new RedditProxySelector(tunnel, fallback);
        URI reddit = URI.create("https://oauth.reddit.com/token");
        selector.connectFailed(reddit, tunnel.address(), new IOException());
        assertEquals(0, notifications.get());
        assertEquals(Collections.singletonList(tunnel), selector.select(reddit));
        URI external = URI.create("https://imgur.com/a");
        assertEquals(Collections.singletonList(other), selector.select(external));
        selector.connectFailed(external, other.address(), new IOException());
        assertEquals(1, notifications.get());
    }

    private static String readHeaders(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String first = reader.readLine();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {}
        return first;
    }

    @Test public void actualRedirectFromOtherHostUsesConnectAndNeverResolvesRedditOnPhone() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ServerSocket origin = new ServerSocket(0); ServerSocket tunnel = new ServerSocket(0)) {
            Future<?> redirect = pool.submit(() -> {
                try (Socket socket = origin.accept()) {
                    readHeaders(socket);
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: https://oauth.reddit.com/protected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            Future<String> connect = pool.submit(() -> {
                try (Socket socket = tunnel.accept()) {
                    String request = readHeaders(socket);
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return request;
                }
            });
            AtomicInteger redditLookups = new AtomicInteger();
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxySelector(new RedditProxySelector(proxy(tunnel.getLocalPort()), null))
                    .dns(host -> {
                        if (RedditProxySelector.isRedditHost(host)) redditLookups.incrementAndGet();
                        return Collections.singletonList(InetAddress.getLoopbackAddress());
                    }).callTimeout(5, TimeUnit.SECONDS).build();
            try (okhttp3.Response ignored = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + origin.getLocalPort() + "/").build()).execute()) {
                fail("CONNECT rejection must fail the request");
            } catch (IOException expected) {}
            assertEquals("CONNECT oauth.reddit.com:443 HTTP/1.1", connect.get(5, TimeUnit.SECONDS));
            redirect.get(5, TimeUnit.SECONDS);
            assertEquals(0, redditLookups.get());
        } finally { pool.shutdownNow(); }
    }

    @Test public void unavailableTunnelFailsWithoutDirectDnsOrFallback() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        AtomicInteger lookups = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder()
                .proxySelector(new RedditProxySelector(proxy(closedPort), null))
                .dns(host -> { lookups.incrementAndGet(); throw new UnknownHostException("Unexpected direct lookup"); })
                .callTimeout(2, TimeUnit.SECONDS).build();
        try (okhttp3.Response ignored = client.newCall(new Request.Builder().url("https://www.reddit.com/").build()).execute()) {
            fail("Unavailable tunnel must fail");
        } catch (IOException expected) {}
        assertEquals(0, lookups.get());
    }
}
