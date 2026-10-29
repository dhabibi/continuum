package ml.docilealligator.infinityforreddit.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.WebViewFeature;
import java.net.InetSocketAddress;

/** WebView has its own network stack; the Java ProxySelector does not govern it. */
public final class RedditWebViewProxy {
    private RedditWebViewProxy() {}

    public static ProxyConfig configuration(InetSocketAddress address) {
        String proxyHost = address.getHostString();
        if (proxyHost.contains(":")) proxyHost = "[" + proxyHost + "]";
        ProxyConfig.Builder builder = new ProxyConfig.Builder()
                .addProxyRule("http://" + proxyHost + ":" + address.getPort())
                .setReverseBypassEnabled(true);
        for (String domain : RedditProxySelector.DOMAIN_SUFFIXES) {
            builder.addBypassRule(domain).addBypassRule("*." + domain)
                    .addBypassRule(domain + ".").addBypassRule("*." + domain + ".");
        }
        for (String host : RedditProxySelector.EXACT_HOSTS) {
            builder.addBypassRule(host).addBypassRule(host + ".");
        }
        // No direct fallback. Redirects, subresources and WebSockets share Chromium's proxy rules.
        return builder.build();
    }

    public static void prepare(Context context, Runnable ready, Runnable unavailable) {
        try {
            boolean workers = WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS);
            // Persisted service workers have their own network gate. Close it even when the
            // provider cannot install our proxy, rather than only blocking visible WebViews.
            if (workers) ServiceWorkerControllerCompat.getInstance().getServiceWorkerWebSettings()
                    .setBlockNetworkLoads(true);
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
                    || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)
                    || (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) && !workers)) {
                unavailable.run();
                return;
            }
            InetSocketAddress address = (InetSocketAddress) RedditRouting.selector(context).redditProxy().address();
            if (address.getHostString().endsWith(".invalid")) {
                unavailable.run();
                return;
            }
            ProxyController.getInstance().setProxyOverride(configuration(address),
                    command -> new Handler(Looper.getMainLooper()).post(command), () -> {
                        if (workers) ServiceWorkerControllerCompat.getInstance().getServiceWorkerWebSettings()
                                .setBlockNetworkLoads(false);
                        ready.run();
                    });
        } catch (RuntimeException failure) {
            unavailable.run();
        }
    }
}
