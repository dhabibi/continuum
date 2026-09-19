package ml.docilealligator.infinityforreddit.account;

import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.Objects;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.utils.AppRestartHelper;

/** Create, switch and rename local accounts using the drawer's existing dialog style. */
public final class LocalProfilesDialog {
    private LocalProfilesDialog() {}

    public static void showCreate(BaseActivity activity) {
        showNameDialog(activity, true);
    }

    public static void showRenameCurrent(BaseActivity activity) {
        showNameDialog(activity, false);
    }

    public static void switchTo(BaseActivity activity, String id) {
        LocalProfiles profiles = LocalProfiles.get(activity);
        if (!id.equals(profiles.getCurrentId())) {
            switchTo(activity, profiles, id);
        }
    }

    private static void showNameDialog(BaseActivity activity, boolean create) {
        LocalProfiles profiles = LocalProfiles.get(activity);
        View root = activity.getLayoutInflater().inflate(R.layout.dialog_edit_text, null);
        EditText input = Objects.requireNonNull(root.findViewById(R.id.edit_text_edit_text_dialog));
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        input.setHint(R.string.local_account_name);
        input.setTextColor(activity.getCustomThemeWrapper().getPrimaryTextColor());
        input.setHintTextColor(activity.getCustomThemeWrapper().getSecondaryTextColor());
        if (activity.typeface != null) {
            input.setTypeface(activity.typeface);
        }
        if (!create) {
            input.setText(profiles.getCurrentName());
            input.selectAll();
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.MaterialAlertDialogTheme)
                .setTitle(create ? R.string.local_account_new : R.string.local_account_rename)
                .setMessage(create ? R.string.local_account_create_message : R.string.local_account_rename_message)
                .setView(root)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> Objects.requireNonNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).setOnClickListener(view -> {
            try {
                String name = input.getText().toString();
                if (create) {
                    String id = profiles.create(name);
                    dialog.dismiss();
                    switchTo(activity, profiles, id);
                } else {
                    profiles.renameCurrent(name);
                    dialog.dismiss();
                    activity.recreate();
                }
            } catch (IllegalArgumentException e) {
                input.setError(activity.getString(R.string.local_account_invalid_name));
            } catch (IllegalStateException e) {
                Toast.makeText(activity, R.string.local_account_save_failed, Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private static void switchTo(BaseActivity activity, LocalProfiles profiles, String id) {
        if (profiles.select(id)) {
            AppRestartHelper.triggerAppRestart(activity);
        } else {
            Toast.makeText(activity, R.string.local_account_save_failed, Toast.LENGTH_LONG).show();
        }
    }
}
