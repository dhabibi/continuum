package ml.docilealligator.infinityforreddit.network;

import java.io.IOException;
import androidx.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Reddit routes have exactly one choice: the private VPS tunnel. Never return DIRECT for them. */
public final class RedditProxySelector extends ProxySelector {
    // Keep in sync with deploy/reddit-proxy/reddit_tunnel.py and its policy tests.
    public static final List<String> DOMAIN_SUFFIXES = Collections.unmodifiableList(Arrays.asList(
            "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com", "redditmail.com",
            "reddithelp.com", "redditinc.com", "redditstatus.com", "redditspace.com"));
    public static final List<String> EXACT_HOSTS = Collections.unmodifiableList(Arrays.asList(
            "reddit.app.link", "reddit-uploaded-media.s3-accelerate.amazonaws.com",
            "reddit-uploaded-video.s3-accelerate.amazonaws.com"));
    private final Proxy redditProxy;
    @Nullable private final ProxySelector otherTraffic;

    public RedditProxySelector(Proxy redditProxy, @Nullable ProxySelector otherTraffic) {
        if (redditProxy == null || redditProxy.type() != Proxy.Type.HTTP) {
            throw new IllegalArgumentException("Reddit requires an HTTP tunnel proxy");
        }
        this.redditProxy = redditProxy;
        this.otherTraffic = otherTraffic;
    }

    public static boolean isRedditHost(@Nullable String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (EXACT_HOSTS.contains(host)) return true;
        for (String domain : DOMAIN_SUFFIXES) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    public Proxy redditProxy() {
        return redditProxy;
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri == null) throw new IllegalArgumentException("URI is required");
        if (isRedditHost(uri.getHost())) return Collections.singletonList(redditProxy);
        return otherTraffic == null ? Collections.singletonList(Proxy.NO_PROXY) : otherTraffic.select(uri);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        // The platform's selector must not blacklist this route or introduce a direct fallback.
        if (!isRedditHost(uri.getHost()) && otherTraffic != null) {
            otherTraffic.connectFailed(uri, address, failure);
        }
    }
}
