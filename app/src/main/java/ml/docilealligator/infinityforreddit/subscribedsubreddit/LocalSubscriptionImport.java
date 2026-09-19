package ml.docilealligator.infinityforreddit.subscribedsubreddit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.apis.RedditAPI;
import ml.docilealligator.infinityforreddit.subscribeduser.SubscribedUserData;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import retrofit2.Response;
import retrofit2.Retrofit;

/** Resolves a legacy link's names in batches, then adds them to anonymous Home's local lists. */
public final class LocalSubscriptionImport {
    private static final int BATCH_SIZE = 50;

    private LocalSubscriptionImport() {}

    /** Run on the database executor. A failed request leaves the subscription lists unchanged. */
    public static Result importNames(Retrofit retrofit, RedditDataRoomDatabase database,
                                     List<String> names) throws IOException, JSONException {
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (!isSubscribed(database, name)) {
                missing.add(name);
            }
        }

        RedditAPI api = retrofit.create(RedditAPI.class);
        Map<String, SubscribedSubredditData> resolved = new HashMap<>();
        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            List<String> batch = missing.subList(start, Math.min(start + BATCH_SIZE, missing.size()));
            Response<String> response = api.getSubredditsInfo(Collections.emptyMap(), String.join(",", batch)).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Subreddit lookup failed: HTTP " + response.code());
            }
            JSONArray children = new JSONObject(response.body()).getJSONObject("data").getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                JSONObject data = children.getJSONObject(i).getJSONObject("data");
                String name = data.optString("display_name");
                String id = data.optString("name");
                if (name.isEmpty() || !id.startsWith("t5_") || data.isNull("subscribers")) {
                    continue;
                }
                String icon = data.optString("community_icon", "");
                if (icon.isEmpty()) {
                    icon = data.optString("icon_img", "");
                }
                resolved.put(name.toLowerCase(Locale.ROOT), new SubscribedSubredditData(
                        id, name, icon, Account.ANONYMOUS_ACCOUNT, false));
            }
        }

        Result result = new Result();
        database.runInTransaction(() -> {
            for (String name : names) {
                // Recheck inside the transaction: another subscription may have arrived during
                // the lookup. Keep its icon, favorite marker and saved-user state intact.
                if (isSubscribed(database, name)) {
                    result.existing++;
                    continue;
                }
                SubscribedSubredditData data = resolved.get(name.toLowerCase(Locale.ROOT));
                if (data == null) {
                    result.unavailable.add(name);
                    continue;
                }
                if (isUser(name)) {
                    String username = data.getName().substring(2);
                    database.subscribedUserDao().insertIfAbsent(username, data.getIconUrl(), Account.ANONYMOUS_ACCOUNT);
                    database.subscribedUserDao().updateFollowed(username, Account.ANONYMOUS_ACCOUNT, true);
                } else {
                    database.subscribedSubredditDao().insert(data);
                }
                result.added++;
            }
        });
        return result;
    }

    private static boolean isUser(String name) {
        return name.regionMatches(true, 0, "u_", 0, 2);
    }

    private static boolean isSubscribed(RedditDataRoomDatabase database, String name) {
        if (isUser(name)) {
            SubscribedUserData user = database.subscribedUserDao().getSubscribedUser(name.substring(2), Account.ANONYMOUS_ACCOUNT);
            return user != null && user.isFollowed();
        }
        return database.subscribedSubredditDao().getSubscribedSubreddit(name, Account.ANONYMOUS_ACCOUNT) != null;
    }

    public static final class Result {
        public int added;
        public int existing;
        public final List<String> unavailable = new ArrayList<>();
    }
}
