package ml.docilealligator.infinityforreddit.network;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import ml.docilealligator.infinityforreddit.customviews.LollipopBugFixedWebView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class RedditWebViewProxyTest {
    @Test public void initialNavigationWaitsForChromiumProxyCallback() {
        Context context = ApplicationProvider.getApplicationContext();
        try (MockedStatic<RedditWebViewProxy> proxy = mockStatic(RedditWebViewProxy.class)) {
            AtomicReference<Runnable> ready = new AtomicReference<>();
            proxy.when(() -> RedditWebViewProxy.prepare(any(), any(), any())).thenAnswer(call -> {
                ready.set(call.getArgument(1)); return null;
            });
            LollipopBugFixedWebView view = new LollipopBugFixedWebView(context);
            view.loadUrl("http://www.reddit.com/login");
            assertNull(Shadows.shadowOf(view).getLastLoadedUrl());
            assertTrue(view.getSettings().getBlockNetworkLoads());
            ready.get().run();
            assertEquals("https://www.reddit.com/login", Shadows.shadowOf(view).getLastLoadedUrl());
            assertFalse(view.getSettings().getBlockNetworkLoads());
            view.destroy();
        }
    }

    @Test public void unsupportedProviderNeverLoadsRemotePage() {
        try (MockedStatic<WebViewFeature> features = mockStatic(WebViewFeature.class)) {
            features.when(() -> WebViewFeature.isFeatureSupported(anyString())).thenReturn(false);
            LollipopBugFixedWebView view = new LollipopBugFixedWebView(ApplicationProvider.getApplicationContext());
            view.loadUrl("https://www.reddit.com/login");
            view.reload();
            assertNull(Shadows.shadowOf(view).getLastLoadedUrl());
            assertTrue(view.getSettings().getBlockNetworkLoads());
            view.destroy();
        }
    }

    @Test public void destroyedViewerCannotLoadWhenDelayedProxyCallbackArrives() {
        try (MockedStatic<RedditWebViewProxy> proxy = mockStatic(RedditWebViewProxy.class)) {
            AtomicReference<Runnable> ready = new AtomicReference<>();
            proxy.when(() -> RedditWebViewProxy.prepare(any(), any(), any())).thenAnswer(call -> {
                ready.set(call.getArgument(1)); return null;
            });
            LollipopBugFixedWebView view = new LollipopBugFixedWebView(ApplicationProvider.getApplicationContext());
            view.loadUrl("https://www.reddit.com/");
            view.destroy();
            ready.get().run();
            assertNull(Shadows.shadowOf(view).getLastLoadedUrl());
        }
    }

    @Test public void unsupportedProxyAlsoBlocksPersistedServiceWorkers() {
        androidx.webkit.ServiceWorkerControllerCompat controller = mock(androidx.webkit.ServiceWorkerControllerCompat.class);
        androidx.webkit.ServiceWorkerWebSettingsCompat settings = mock(androidx.webkit.ServiceWorkerWebSettingsCompat.class);
        when(controller.getServiceWorkerWebSettings()).thenReturn(settings);
        try (MockedStatic<WebViewFeature> features = mockStatic(WebViewFeature.class);
             MockedStatic<androidx.webkit.ServiceWorkerControllerCompat> workers = mockStatic(androidx.webkit.ServiceWorkerControllerCompat.class)) {
            features.when(() -> WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)).thenReturn(true);
            workers.when(androidx.webkit.ServiceWorkerControllerCompat::getInstance).thenReturn(controller);
            Runnable ready = mock(Runnable.class);
            Runnable unavailable = mock(Runnable.class);
            RedditWebViewProxy.prepare(ApplicationProvider.getApplicationContext(), ready, unavailable);
            verify(settings).setBlockNetworkLoads(true);
            verify(settings, never()).setBlockNetworkLoads(false);
            verify(ready, never()).run();
            verify(unavailable).run();
        }
    }
}
