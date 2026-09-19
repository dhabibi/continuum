package ml.docilealligator.infinityforreddit.multireddit;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.activities.CreateMultiRedditActivity;
import ml.docilealligator.infinityforreddit.asynctasks.AddSubredditOrUserToMultiReddit;
import ml.docilealligator.infinityforreddit.events.ChangeAnonymousSubredditSubscriptionEvent;
import ml.docilealligator.infinityforreddit.subreddit.SubredditData;
import org.greenrobot.eventbus.EventBus;
import retrofit2.Retrofit;

/** Add a discovered community without leaving the search results. */
public final class MultiredditMembershipDialog {
    private MultiredditMembershipDialog() {}

    public static void show(BaseActivity activity, Executor executor, RedditDataRoomDatabase database,
                            Retrofit oauthRetrofit, SubredditData subreddit) {
        Handler handler = new Handler(Looper.getMainLooper());
        String account = activity.accountName;
        executor.execute(() -> {
            List<MultiReddit> multis = database.multiRedditDao().getAllMultiRedditsList(account);
            handler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                String[] names = multis.stream().map(MultiReddit::getDisplayName).toArray(String[]::new);
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, R.style.MaterialAlertDialogTheme)
                        .setTitle(activity.getString(R.string.add_community_to_multireddit, subreddit.getName()))
                        .setPositiveButton(R.string.create_multi_reddit_activity_label, (dialog, which) -> {
                            Intent intent = new Intent(activity, CreateMultiRedditActivity.class);
                            intent.putExtra(CreateMultiRedditActivity.EXTRA_INITIAL_SUBREDDIT, subreddit.getName());
                            intent.putExtra(CreateMultiRedditActivity.EXTRA_INITIAL_SUBREDDIT_ICON, subreddit.getIconUrl());
                            activity.startActivity(intent);
                        })
                        .setNegativeButton(android.R.string.cancel, null);
                if (multis.isEmpty()) {
                    builder.setMessage(R.string.create_multireddit_from_search);
                } else {
                    builder.setItems(names, (dialog, index) -> {
                        MultiReddit multi = multis.get(index);
                        Runnable success = () -> {
                            EventBus.getDefault().post(new ChangeAnonymousSubredditSubscriptionEvent());
                            Toast.makeText(activity, activity.getString(R.string.add_subreddit_or_user_to_multireddit_success,
                                    subreddit.getName(), multi.getDisplayName()), Toast.LENGTH_SHORT).show();
                        };
                        Runnable failure = () -> Toast.makeText(activity, activity.getString(
                                R.string.add_subreddit_or_user_to_multireddit_failed, subreddit.getName(), multi.getDisplayName()), Toast.LENGTH_LONG).show();
                        if (Account.isAnonymous(account)) {
                            executor.execute(() -> {
                                try {
                                    addLocal(database, multi.getPath(), subreddit);
                                    handler.post(success);
                                } catch (RuntimeException e) {
                                    handler.post(failure);
                                }
                            });
                        } else {
                            AddSubredditOrUserToMultiReddit.addSubredditOrUserToMultiReddit(oauthRetrofit,
                                    Objects.requireNonNull(activity.accessToken), multi.getPath(), subreddit.getName(),
                                    new AddSubredditOrUserToMultiReddit.AddSubredditOrUserToMultiRedditListener() {
                                        @Override public void success() { success.run(); }
                                        @Override public void failed(int code) { failure.run(); }
                                    });
                        }
                    });
                }
                builder.show();
            });
        });
    }

    static void addLocal(RedditDataRoomDatabase database, String path, SubredditData subreddit) {
        database.runInTransaction(() -> {
            MultiReddit multi = database.multiRedditDao().getMultiReddit(path, Account.ANONYMOUS_ACCOUNT);
            if (multi == null || multi.isFollowed()) throw new IllegalArgumentException("Local feed no longer exists");
            for (AnonymousMultiredditSubreddit member : database.anonymousMultiredditSubredditDao().getAllAnonymousMultiRedditSubreddits(path)) {
                if (member.getSubredditName().equalsIgnoreCase(subreddit.getName())) return;
            }
            database.anonymousMultiredditSubredditDao().insert(new AnonymousMultiredditSubreddit(
                    path, subreddit.getName(), subreddit.getIconUrl()));
        });
    }
}
