package ml.docilealligator.infinityforreddit.multireddit;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.account.LocalProfiles;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.events.RefreshMultiRedditsEvent;
import org.greenrobot.eventbus.EventBus;

public final class LocalMultiredditMoveDialog {
    private LocalMultiredditMoveDialog() {}

    public static void showAll(BaseActivity activity, Executor executor, RedditDataRoomDatabase database) {
        executor.execute(() -> {
            List<MultiReddit> entries = database.multiRedditDao().getAllMultiRedditsList(Account.ANONYMOUS_ACCOUNT);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (entries.isEmpty()) {
                    Toast.makeText(activity, R.string.no_local_multireddits_to_move, Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = entries.stream().map(MultiReddit::getDisplayName).toArray(String[]::new);
                boolean[] selected = new boolean[entries.size()];
                AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.MaterialAlertDialogTheme)
                        .setTitle(R.string.move_local_multireddits)
                        .setMultiChoiceItems(labels, selected, (d, index, checked) -> {
                            selected[index] = checked;
                            boolean any = false;
                            for (boolean value : selected) any |= value;
                            Objects.requireNonNull(((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE)).setEnabled(any);
                        })
                        .setPositiveButton(R.string.move_multireddits_next, null)
                        .setNeutralButton(android.R.string.selectAll, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                dialog.setOnShowListener(ignored -> {
                    Objects.requireNonNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).setEnabled(false);
                    Objects.requireNonNull(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)).setOnClickListener(view -> {
                        for (int i = 0; i < selected.length; i++) {
                            selected[i] = true;
                            Objects.requireNonNull(dialog.getListView()).setItemChecked(i, true);
                        }
                        Objects.requireNonNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).setEnabled(true);
                    });
                    Objects.requireNonNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).setOnClickListener(view -> {
                        List<MultiReddit> chosen = new ArrayList<>();
                        for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(entries.get(i));
                        if (!chosen.isEmpty()) {
                            dialog.dismiss();
                            showDestination(activity, executor, database, chosen);
                        }
                    });
                });
                dialog.show();
            });
        });
    }

    public static void showDestination(BaseActivity activity, Executor executor, RedditDataRoomDatabase database,
                                       List<MultiReddit> entries) {
        LocalProfiles profiles = LocalProfiles.get(activity);
        List<LocalProfiles.Profile> destinations = new ArrayList<>(profiles.getProfiles());
        destinations.removeIf(profile -> profile.id.equals(profiles.getCurrentId()));
        if (destinations.isEmpty()) {
            Toast.makeText(activity, R.string.create_local_account_before_move, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = destinations.stream().map(profile -> profile.name).toArray(String[]::new);
        new MaterialAlertDialogBuilder(activity, R.style.MaterialAlertDialogTheme)
                .setTitle(R.string.move_to_local_account)
                .setItems(names, (dialog, index) -> {
                    LocalProfiles.Profile destination = destinations.get(index);
                    List<String> paths = entries.stream().map(MultiReddit::getPath).collect(java.util.stream.Collectors.toList());
                    executor.execute(() -> {
                        try {
                            LocalMultiredditTransfer.Result result = LocalMultiredditTransfer.move(
                                    activity.getApplicationContext(), database, destination.id, paths);
                            new Handler(Looper.getMainLooper()).post(() -> {
                                EventBus.getDefault().post(new RefreshMultiRedditsEvent());
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                String message = activity.getString(R.string.local_multireddits_moved, result.moved, destination.name);
                                if (!result.renamed.isEmpty()) {
                                    message += "\n" + activity.getString(R.string.local_multireddits_renamed, String.join(", ", result.renamed));
                                }
                                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                            });
                        } catch (RuntimeException e) {
                            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(activity,
                                    R.string.local_multireddits_move_failed, Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
