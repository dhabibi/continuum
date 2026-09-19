package ml.docilealligator.infinityforreddit.subscribedsubreddit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.subreddit.SubredditData;

/** Imports names into local Home without requiring Reddit metadata or a network connection. */
public final class LocalSubscriptionImport {
    private LocalSubscriptionImport() {}

    /** Run on the database executor. Validate the whole input before the atomic local write. */
    public static Result importNames(RedditDataRoomDatabase database, List<String> names) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : names) {
            String name = raw.trim();
            if (!name.matches("[A-Za-z0-9_]{1,50}") || name.equalsIgnoreCase("u_")) {
                throw new IllegalArgumentException("Invalid community name");
            }
            unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        Result result = new Result();
        database.runInTransaction(() -> {
            for (Map.Entry<String, String> entry : unique.entrySet()) {
                String key = entry.getKey();
                String name = entry.getValue();
                if (database.subscribedSubredditDao().getSubscribedSubreddit(name, Account.ANONYMOUS_ACCOUNT) != null) {
                    result.existing++;
                    continue;
                }
                SubredditData cached = database.subredditDao().getSubredditData(name);
                // Local subscription IDs are storage keys. Home, unsubscribe and duplicate
                // checks all use the community name; no Reddit fullname is needed here.
                database.subscribedSubredditDao().insert(new SubscribedSubredditData(
                        cached == null ? "local:" + key : cached.getId(),
                        cached == null ? name : cached.getName(),
                        cached == null ? "" : cached.getIconUrl(), Account.ANONYMOUS_ACCOUNT, false));
                if (key.startsWith("u_")) {
                    String username = name.substring(2);
                    database.subscribedUserDao().insertIfAbsent(username,
                            cached == null ? "" : cached.getIconUrl(), Account.ANONYMOUS_ACCOUNT);
                    database.subscribedUserDao().updateFollowed(username, Account.ANONYMOUS_ACCOUNT, true);
                }
                result.added++;
            }
        });
        return result;
    }

    public static final class Result {
        public int added;
        public int existing;
    }
}
