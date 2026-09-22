package ml.docilealligator.infinityforreddit.multireddit;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.events.ChangeAnonymousSubredditSubscriptionEvent;
import ml.docilealligator.infinityforreddit.subreddit.SubredditSubscription;
import ml.docilealligator.infinityforreddit.subscribedsubreddit.LocalSubscriptionImport;
import org.greenrobot.eventbus.EventBus;
import retrofit2.Retrofit;

/** Adds a multireddit's current members to the selected account's subscriptions. */
public final class MultiredditFrontPage {
    private MultiredditFrontPage() {}

    public static void add(Activity activity, Executor executor, RedditDataRoomDatabase database,
                           Retrofit retrofit, Retrofit oauthRetrofit, String accountName,
                           @Nullable String accessToken, String path) {
        new Request(activity, executor, database, retrofit, oauthRetrofit, accountName, accessToken)
                .load(path);
    }

    private static final class Request implements FetchMultiRedditInfo.FetchMultiRedditInfoListener {
        private final Activity activity;
        private final Executor executor;
        private final RedditDataRoomDatabase database;
        private final Retrofit retrofit;
        private final Retrofit oauthRetrofit;
        private final String accountName;
        @Nullable private final String accessToken;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<String> names = new ArrayList<>();
        private int added;
        private int existing;
        private int failed;

        Request(Activity activity, Executor executor, RedditDataRoomDatabase database,
                Retrofit retrofit, Retrofit oauthRetrofit, String accountName,
                @Nullable String accessToken) {
            this.activity = activity;
            this.executor = executor;
            this.database = database;
            this.retrofit = retrofit;
            this.oauthRetrofit = oauthRetrofit;
            this.accountName = accountName;
            this.accessToken = accessToken;
        }

        void load(String path) {
            if (path == null || path.isEmpty()) {
                failed();
                return;
            }
            showMessage(activity.getString(R.string.multireddit_front_page_loading));
            if (Account.isAnonymous(accountName)) {
                if (path.startsWith("/user/-/m/")) {
                    FetchMultiRedditInfo.anonymousFetchMultiRedditInfo(executor, handler, database, path, this);
                } else {
                    FetchMultiRedditInfo.publicFetchMultiRedditInfo(executor, handler, retrofit, path, this);
                }
            } else {
                FetchMultiRedditInfo.fetchMultiRedditInfo(executor, handler, oauthRetrofit, accessToken, path, this);
            }
        }

        @Override
        public void success(MultiReddit multiReddit) {
            TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (multiReddit.getSubreddits() != null) {
                for (ExpandedSubredditInMultiReddit member : multiReddit.getSubreddits()) {
                    if (seen.add(member.getName())) names.add(member.getName());
                }
            }
            if (names.isEmpty()) {
                showMessage(activity.getString(R.string.multireddit_front_page_empty));
            } else if (Account.isAnonymous(accountName)) {
                executor.execute(() -> {
                    try {
                        LocalSubscriptionImport.Result result = LocalSubscriptionImport.importNames(database, names);
                        handler.post(() -> {
                            added = result.added;
                            existing = result.existing;
                            if (added > 0) {
                                EventBus.getDefault().post(new ChangeAnonymousSubredditSubscriptionEvent());
                            }
                            showResult();
                        });
                    } catch (Exception e) {
                        handler.post(() -> showMessage(activity.getString(R.string.import_subscriptions_failed)));
                    }
                });
            } else {
                subscribeNext(0);
            }
        }

        /** Use the normal Reddit subscription operation, one at a time, for signed-in accounts. */
        private void subscribeNext(int index) {
            if (index == names.size()) {
                showResult();
                return;
            }
            String name = names.get(index);
            executor.execute(() -> {
                boolean alreadySubscribed = database.subscribedSubredditDao()
                        .getSubscribedSubreddit(name, accountName) != null;
                handler.post(() -> {
                    if (alreadySubscribed) {
                        existing++;
                        subscribeNext(index + 1);
                        return;
                    }
                    SubredditSubscription.subscribeToSubreddit(executor, handler, oauthRetrofit,
                            retrofit, accessToken, name, accountName, database,
                            new SubredditSubscription.SubredditSubscriptionListener() {
                                @Override
                                public void onSubredditSubscriptionSuccess() {
                                    added++;
                                    subscribeNext(index + 1);
                                }

                                @Override
                                public void onSubredditSubscriptionFail() {
                                    failed++;
                                    subscribeNext(index + 1);
                                }
                            });
                });
            });
        }

        @Override
        public void failed() {
            showMessage(activity.getString(R.string.error_getting_multi_reddit_data));
        }

        private void showResult() {
            showMessage(failed == 0
                    ? activity.getString(R.string.import_subscriptions_result, added, existing)
                    : activity.getString(R.string.multireddit_front_page_partial, added, existing, failed));
        }

        private void showMessage(String message) {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        }
    }
}
