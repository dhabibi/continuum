package ml.docilealligator.infinityforreddit.network;

import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Routes CDN URLs constructed by the app through the configured anonymous proxy. */
public final class RedditMediaProxyInterceptor implements Interceptor {
    private final HttpUrl baseUrl;

    public RedditMediaProxyInterceptor(HttpUrl baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        HttpUrl url = request.url();
        String host = url.host();
        if (host.equals("i.redd.it") || host.equals("v.redd.it")
                || host.equals("preview.redd.it") || host.equals("external-preview.redd.it")
                || host.equals("packaged-media.redd.it") || host.equals("redditmedia.com")
                || host.equals("redditstatic.com") || host.endsWith(".redditmedia.com")
                || host.endsWith(".redditstatic.com")) {
            HttpUrl proxyUrl = url.newBuilder()
                    .scheme(baseUrl.scheme()).host(baseUrl.host()).port(baseUrl.port())
                    .encodedPath(baseUrl.encodedPath() + "media/" + host + url.encodedPath())
                    .build();
            request = request.newBuilder().url(proxyUrl)
                    .removeHeader("Authorization").removeHeader("Cookie").build();
        }
        return chain.proceed(request);
    }
}
