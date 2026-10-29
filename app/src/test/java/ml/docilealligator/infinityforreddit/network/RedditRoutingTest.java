package ml.docilealligator.infinityforreddit.network;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.net.*;
import java.io.IOException;
import java.util.Collections;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class RedditRoutingTest {
    private Context context;
    private ProxySelector original;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        original = ProxySelector.getDefault();
        ProxySelector.setDefault(null);
        context.getSharedPreferences(SharedPreferencesUtils.DEFAULT_PREFERENCES_FILE, 0).edit()
                .putString(SharedPreferencesUtils.API_BASE_URL_PREF_KEY, "https://private.example.ts.net:10443/")
                .putBoolean(SharedPreferencesUtils.ENABLE_API_KEY_OVERRIDES_PREF_KEY, false).commit();
    }
    @After public void restore() { ProxySelector.setDefault(original); }

    @Test public void disabledOverridesAndGenericProxyCannotBypassReddit() {
        context.getSharedPreferences(SharedPreferencesUtils.PROXY_SHARED_PREFERENCES_FILE, 0).edit()
                .putBoolean(SharedPreferencesUtils.PROXY_ENABLED, true)
                .putString(SharedPreferencesUtils.PROXY_TYPE, "SOCKS")
                .putString(SharedPreferencesUtils.PROXY_HOSTNAME, "another.proxy")
                .putString(SharedPreferencesUtils.PROXY_PORT, "1080").commit();
        RedditRouting.install(context);
        ProxySelector selector = ProxySelector.getDefault();
        Proxy reddit = selector.select(URI.create("https://i.redd.it/image.jpg")).get(0);
        assertEquals(Proxy.Type.HTTP, reddit.type());
        assertEquals("private.example.ts.net", ((InetSocketAddress) reddit.address()).getHostString());
        assertEquals(10444, ((InetSocketAddress) reddit.address()).getPort());
        assertEquals(Proxy.Type.SOCKS, selector.select(URI.create("https://imgur.com/a")).get(0).type());
        assertSame(selector, new OkHttpClient().proxySelector());
        assertSame(selector, RedditRouting.clientBuilder(context).build().proxySelector());
    }

    @Test public void missingEndpointIsAnUnresolvableProxyNeverDirect() {
        context.getSharedPreferences(SharedPreferencesUtils.DEFAULT_PREFERENCES_FILE, 0).edit().clear().commit();
        Proxy chosen = RedditRouting.selector(context).select(URI.create("https://reddit.com/")).get(0);
        assertEquals(Proxy.Type.HTTP, chosen.type());
        assertEquals("reddit-proxy-not-configured.invalid", ((InetSocketAddress) chosen.address()).getHostString());
    }

    @Test public void accidentalExplicitDirectOverrideIsBlockedBeforeDnsOrSocket() throws Exception {
        OkHttpClient client = RedditRouting.clientBuilder(context).proxy(Proxy.NO_PROXY).build();
        try (okhttp3.Response ignored = client.newCall(new Request.Builder().url("https://oauth.reddit.com/").build()).execute()) {
            fail("Direct override must fail");
        } catch (UnknownHostException expected) {
            assertTrue(expected.getMessage().contains("Direct Reddit DNS is blocked"));
        }
    }

    @Test public void legacyRedditLinksUpgradeWithoutChangingThirdPartyLinks() {
        assertEquals("https://old.reddit.com/r/test?q=a", RedditRouting.upgradeRedditUrl("http://old.reddit.com/r/test?q=a"));
        assertEquals("http://example.com/a", RedditRouting.upgradeRedditUrl("http://example.com/a"));
        assertFalse(RedditRouting.isRedditUrl("https://reddit.com.example.org/"));
    }
}
