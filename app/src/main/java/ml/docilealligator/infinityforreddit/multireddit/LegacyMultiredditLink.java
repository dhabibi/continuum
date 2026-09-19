package ml.docilealligator.infinityforreddit.multireddit;

import androidx.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses combined subreddit links without making a network request. */
public final class LegacyMultiredditLink {
    private LegacyMultiredditLink() {}

    /** Returns unique names in link order, or an empty list for invalid input. */
    public static List<String> parse(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String value = raw.trim();
        if (!value.contains("://")) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("/r/")) {
                value = "https://reddit.com" + value;
            } else if (lower.startsWith("r/")) {
                value = "https://reddit.com/" + value;
            } else if (lower.startsWith("reddit.com/") || lower.startsWith("www.reddit.com/")
                    || lower.startsWith("old.reddit.com/") || lower.startsWith("m.reddit.com/")) {
                value = "https://" + value;
            } else if (!value.contains("/") && value.contains("+")) {
                value = "https://reddit.com/r/" + value;
            } else {
                return Collections.emptyList();
            }
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Collections.emptyList();
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || host == null || uri.getUserInfo() != null || path == null) {
            return Collections.emptyList();
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!host.equals("reddit.com") && !host.endsWith(".reddit.com")) {
            return Collections.emptyList();
        }
        String[] segments = path.split("/", -1);
        if (segments.length < 3 || !"r".equalsIgnoreCase(segments[1])) {
            return Collections.emptyList();
        }

        // Decode only this segment: an encoded slash is an invalid name, not a path boundary.
        // URI preserves literal '+' while decoding %2B, unlike form decoding.
        String decoded = URI.create("/" + segments[2]).getPath();
        if (decoded == null) {
            return Collections.emptyList();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String name : decoded.substring(1).split("\\+")) {
            if (name.isEmpty()) {
                continue;
            }
            if (!name.matches("[A-Za-z0-9_]{1,50}")) {
                return Collections.emptyList();
            }
            unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return new ArrayList<>(unique.values());
    }
}
