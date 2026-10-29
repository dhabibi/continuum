package ml.docilealligator.infinityforreddit.customviews;

import android.content.Context;
import androidx.annotation.Nullable;
import android.content.res.Configuration;
import android.os.Build;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;
import android.webkit.WebBackForwardList;
import android.os.Bundle;
import java.util.Map;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.network.RedditRouting;
import ml.docilealligator.infinityforreddit.network.RedditWebViewProxy;

public class LollipopBugFixedWebView extends WebView {
    private boolean isAnonymous;
    private boolean proxyReady;
    private boolean proxyFailed;
    private boolean destroyed;
    @Nullable private Runnable pendingNavigation;

    private void prepareProxy() {
        getSettings().setBlockNetworkLoads(true);
        RedditWebViewProxy.prepare(getContext(), () -> {
            if (destroyed) return;
            proxyReady = true;
            getSettings().setBlockNetworkLoads(false);
            Runnable navigation = pendingNavigation;
            pendingNavigation = null;
            if (navigation != null) navigation.run();
        }, () -> {
            proxyFailed = true;
            pendingNavigation = null;
            showProxyError();
        });
    }

    private void showProxyError() {
        if (!destroyed) super.loadData(getContext().getString(R.string.reddit_proxy_webview_unavailable),
                "text/plain", "UTF-8");
    }

    private void navigateWhenReady(Runnable navigation) {
        if (destroyed) return;
        if (proxyFailed) showProxyError();
        else if (proxyReady) navigation.run();
        else pendingNavigation = navigation;
    }

    @Override
    public void loadUrl(String url) {
        navigateWhenReady(() -> super.loadUrl(RedditRouting.upgradeRedditUrl(url)));
    }

    @Override
    public void loadUrl(String url, Map<String, String> headers) {
        navigateWhenReady(() -> super.loadUrl(RedditRouting.upgradeRedditUrl(url), headers));
    }

    @Override
    public void postUrl(String url, byte[] data) {
        navigateWhenReady(() -> super.postUrl(RedditRouting.upgradeRedditUrl(url), data));
    }

    @Override
    public void loadData(String data, @Nullable String mimeType, @Nullable String encoding) {
        navigateWhenReady(() -> super.loadData(data, mimeType, encoding));
    }

    @Override
    public void loadDataWithBaseURL(@Nullable String baseUrl, String data, @Nullable String mimeType, @Nullable String encoding, @Nullable String historyUrl) {
        navigateWhenReady(() -> super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl));
    }

    @Override
    @Nullable
    public WebBackForwardList restoreState(Bundle state) {
        if (proxyReady && !destroyed) return super.restoreState(state);
        navigateWhenReady(() -> super.restoreState(state));
        return null;
    }

    @Override
    public void reload() {
        if (proxyReady && !destroyed) super.reload();
    }

    @Override
    public void destroy() {
        destroyed = true;
        pendingNavigation = null;
        super.destroy();
    }

    public LollipopBugFixedWebView(Context context) {
        super(getFixedContext(context));
        prepareProxy();
    }

    public LollipopBugFixedWebView(Context context, AttributeSet attrs) {
        super(getFixedContext(context), attrs);
        prepareProxy();
    }

    public LollipopBugFixedWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(getFixedContext(context), attrs, defStyleAttr);
        prepareProxy();
    }

    // To fix Android Lollipop WebView problem create a new configuration on that Android version only
    private static Context getFixedContext(Context context) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP
                || Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP_MR1) // Android Lollipop 5.0 & 5.1
            return context.createConfigurationContext(new Configuration());
        return context;
    }

    public void setAnonymous(boolean isAnonymous) {
        this.isAnonymous = isAnonymous;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection inputConnection = super.onCreateInputConnection(outAttrs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isAnonymous) {
            outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        }
        return inputConnection;
    }
}
