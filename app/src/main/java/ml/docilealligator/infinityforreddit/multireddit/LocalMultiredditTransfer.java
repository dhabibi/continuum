package ml.docilealligator.infinityforreddit.multireddit;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.account.LocalProfiles;

/** Moves local feeds, committing the destination before removing anything from the source. */
public final class LocalMultiredditTransfer {
    private LocalMultiredditTransfer() {}

    public static Result move(Context context, RedditDataRoomDatabase source, String targetProfileId, List<String> paths) {
        LocalProfiles profiles = LocalProfiles.get(context);
        if (profiles.getCurrentId().equals(targetProfileId)
                || profiles.getProfiles().stream().noneMatch(profile -> profile.id.equals(targetProfileId))) {
            throw new IllegalArgumentException("Choose a different local account");
        }
        RedditDataRoomDatabase target = RedditDataRoomDatabase.createForLocalProfile(context, targetProfileId);
        try {
            return moveBetweenDatabases(source, target, paths);
        } finally {
            target.close();
        }
    }

    static Result moveBetweenDatabases(RedditDataRoomDatabase source, RedditDataRoomDatabase target, List<String> paths) {
        if (source == target || paths.isEmpty()) {
            throw new IllegalArgumentException("A move needs different databases and at least one feed");
        }
        return source.runInTransaction(() -> {
            List<MultiReddit> originals = new ArrayList<>();
            for (String path : new LinkedHashSet<>(paths)) {
                MultiReddit multi = source.multiRedditDao().getMultiReddit(path, Account.ANONYMOUS_ACCOUNT);
                if (multi == null || multi.isFollowed()) {
                    throw new IllegalArgumentException("Local multireddit no longer exists");
                }
                originals.add(multi);
            }
            Result result = new Result();
            // Keep the source write lock during the copy so a concurrent edit cannot be lost.
            // Two files cannot share a Room transaction. Target-first ordering means a failed
            // source commit can leave a duplicate, but never the only copy deleted.
            target.runInTransaction(() -> {
                for (MultiReddit original : originals) {
                    String name = original.getName();
                    String path = original.getPath();
                    String displayName = original.getDisplayName();
                    int suffix = 2;
                    while (target.multiRedditDao().getMultiReddit(path, Account.ANONYMOUS_ACCOUNT) != null
                            || target.multiRedditDao().hasLocalName(name, displayName)) {
                        name = original.getName() + "_" + suffix;
                        path = "/user/-/m/" + name;
                        displayName = original.getDisplayName() + " (" + suffix + ")";
                        suffix++;
                    }
                    MultiReddit copy = new MultiReddit(path, displayName, name, original.getDescription(),
                            original.getCopiedFrom(), original.getIconUrl(), original.getVisibility(),
                            Account.ANONYMOUS_ACCOUNT, original.getNSubscribers(), original.getCreatedUTC(),
                            original.isOver18(), original.isSubscriber(), original.isFavorite());
                    target.multiRedditDao().insert(copy);
                    for (AnonymousMultiredditSubreddit member : source.anonymousMultiredditSubredditDao()
                            .getAllAnonymousMultiRedditSubreddits(original.getPath())) {
                        target.anonymousMultiredditSubredditDao().insert(new AnonymousMultiredditSubreddit(
                                path, member.getSubredditName(), member.getIconUrl()));
                    }
                    if (!displayName.equals(original.getDisplayName())) {
                        result.renamed.add(displayName);
                    }
                }
            });
            for (MultiReddit original : originals) {
                source.multiRedditDao().deleteFollowedMultiReddit(original.getPath(), Account.ANONYMOUS_ACCOUNT);
            }
            result.moved = originals.size();
            return result;
        });
    }

    public static final class Result {
        public int moved;
        public final List<String> renamed = new ArrayList<>();
    }
}
