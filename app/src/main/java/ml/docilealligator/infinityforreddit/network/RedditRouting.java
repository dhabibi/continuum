package ml.docilealligator.infinityforreddit.network;

import android.content.Context;
import androidx.annotation.Nullable;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import ml.docilealligator.infinityforreddit.utils.APIUtils;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/** Mandatory Reddit routing for this VPS fork, including clients outside the Dagger graph. */
public final class RedditRouting {
    public static final int TUNNEL_PORT = 10444;
    private RedditRouting() {}

    public static Proxy tunnelProxy(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SharedPreferencesUtils.DEFAULT_PREFERENCES_FILE, Context.MODE_PRIVATE);
        // This protection is independent of the API-override toggle and generic proxy settings.
        // Turning either off must not silently expose Reddit traffic.
        String configured = prefs.getString(SharedPreferencesUtils.API_BASE_URL_PREF_KEY, null);
        HttpUrl base = configured == null ? null : HttpUrl.parse(configured);
        String host = base != null && base.isHttps() && base.username().isEmpty()
                && base.password().isEmpty() && !RedditProxySelector.isRedditHost(base.host())
                ? base.host() : "reddit-proxy-not-configured.invalid";
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, TUNNEL_PORT));
    }

    /** Install before ContentProviders can construct clients or start requests. */
    public static void install(Context context) {
        ProxySelector.setDefault(selector(context));
    }

    public static RedditProxySelector selector(Context context) {
        ProxySelector previous = ProxySelector.getDefault();
        // Reuse the original non-Reddit policy rather than stacking wrappers across initializations.
        if (previous instanceof RedditProxySelector) return (RedditProxySelector) previous;
        SharedPreferences prefs = context.getSharedPreferences(
                SharedPreferencesUtils.PROXY_SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE);
        if (prefs.getBoolean(SharedPreferencesUtils.PROXY_ENABLED, false)) {
            try {
                Proxy.Type type = Proxy.Type.valueOf(prefs.getString(SharedPreferencesUtils.PROXY_TYPE, "HTTP"));
                if (type != Proxy.Type.DIRECT) {
                    Proxy general = new Proxy(type, InetSocketAddress.createUnresolved(
                            prefs.getString(SharedPreferencesUtils.PROXY_HOSTNAME, "127.0.0.1"),
                            SharedPreferencesUtils.getInt(prefs, SharedPreferencesUtils.PROXY_PORT, "1080")));
                    previous = new ProxySelector() {
                        @Override public List<Proxy> select(URI uri) { return Collections.singletonList(general); }
                        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
                    };
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid optional settings cannot disable the mandatory Reddit route.
            }
        }
        return new RedditProxySelector(tunnelProxy(context), previous);
    }

    public static OkHttpClient.Builder clientBuilder(Context context) {
        RedditProxySelector policy = selector(context);
        return new OkHttpClient.Builder().proxySelector(policy)
                .addInterceptor(chain -> {
                    okhttp3.Request request = chain.request();
                    if (RedditProxySelector.isRedditHost(request.url().host()) && !request.url().isHttps()) {
                        request = request.newBuilder().url(request.url().newBuilder().scheme("https").build()).build();
                    }
                    return chain.proceed(request);
                })
                .dns(host -> {
                    if (RedditProxySelector.isRedditHost(host)) {
                        throw new java.net.UnknownHostException("Direct Reddit DNS is blocked; use the VPS tunnel");
                    }
                    return okhttp3.Dns.SYSTEM.lookup(host);
                })
                .addNetworkInterceptor(chain -> {
                    if (RedditProxySelector.isRedditHost(chain.request().url().host())
                            && (chain.connection() == null
                            || !policy.redditProxy().equals(chain.connection().route().proxy()))) {
                        throw new IOException("Reddit connection blocked: private VPS tunnel required");
                    }
                    return chain.proceed(chain.request());
                });
    }

    public static boolean isRedditUrl(@Nullable String url) {
        return url != null && RedditProxySelector.isRedditHost(Uri.parse(url).getHost());
    }

    public static String upgradeRedditUrl(String url) {
        Uri uri = Uri.parse(url);
        if (RedditProxySelector.isRedditHost(uri.getHost()) && "http".equalsIgnoreCase(uri.getScheme())) {
            return uri.buildUpon().scheme("https").build().toString();
        }
        return url;
    }
}
