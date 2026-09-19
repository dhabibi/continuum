package ml.docilealligator.infinityforreddit.adapters.navigationdrawer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.account.LocalProfiles;
import ml.docilealligator.infinityforreddit.account.LocalProfilesDialog;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.databinding.ItemNavDrawerMenuGroupTitleBinding;
import ml.docilealligator.infinityforreddit.databinding.ItemNavDrawerMenuItemBinding;

/** Always-visible local profiles: one tap switches, long press renames the current profile. */
public final class LocalProfilesSectionRecyclerViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final BaseActivity activity;
    private final LocalProfiles profiles;
    private final List<LocalProfiles.Profile> entries;

    public LocalProfilesSectionRecyclerViewAdapter(BaseActivity activity) {
        this.activity = activity;
        profiles = LocalProfiles.get(activity);
        entries = profiles.getProfiles();
    }

    @Override public int getItemCount() { return entries.size() + 2; }
    @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return type == 0
                ? new TitleHolder(ItemNavDrawerMenuGroupTitleBinding.inflate(inflater, parent, false))
                : new ProfileHolder(ItemNavDrawerMenuItemBinding.inflate(inflater, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof TitleHolder) {
            return;
        }
        ProfileHolder row = (ProfileHolder) holder;
        row.itemView.setOnLongClickListener(null);
        ViewCompat.setStateDescription(row.itemView, null);
        row.itemView.setSelected(false);
        if (position == getItemCount() - 1) {
            row.binding.textViewItemNavDrawerMenuItem.setText(R.string.local_account_new);
            row.binding.imageViewItemNavDrawerMenuItem.setImageResource(R.drawable.ic_add_circle_outline_day_night_24dp);
            row.itemView.setOnClickListener(view -> LocalProfilesDialog.showCreate(activity));
            return;
        }
        LocalProfiles.Profile profile = entries.get(position - 1);
        boolean active = profile.id.equals(profiles.getCurrentId());
        row.binding.textViewItemNavDrawerMenuItem.setText(profile.name);
        row.binding.imageViewItemNavDrawerMenuItem.setImageResource(active
                ? R.drawable.ic_check_circle_day_night_24dp : R.drawable.ic_anonymous_day_night_24dp);
        row.itemView.setSelected(active);
        ViewCompat.setStateDescription(row.itemView, active ? activity.getString(R.string.local_account_active) : null);
        row.itemView.setOnClickListener(view -> LocalProfilesDialog.switchTo(activity, profile.id));
        if (active) {
            row.itemView.setOnLongClickListener(view -> {
                LocalProfilesDialog.showRenameCurrent(activity);
                return true;
            });
        }
    }

    private final class TitleHolder extends RecyclerView.ViewHolder {
        TitleHolder(ItemNavDrawerMenuGroupTitleBinding binding) {
            super(binding.getRoot());
            binding.titleTextViewItemNavDrawerMenuGroupTitle.setText(R.string.local_accounts);
            binding.titleTextViewItemNavDrawerMenuGroupTitle.setTextColor(activity.getCustomThemeWrapper().getSecondaryTextColor());
            binding.collapseIndicatorImageViewItemNavDrawerMenuGroupTitle.setVisibility(View.GONE);
            if (activity.typeface != null) binding.titleTextViewItemNavDrawerMenuGroupTitle.setTypeface(activity.typeface);
        }
    }

    private final class ProfileHolder extends RecyclerView.ViewHolder {
        final ItemNavDrawerMenuItemBinding binding;
        ProfileHolder(ItemNavDrawerMenuItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.textViewItemNavDrawerMenuItem.setTextColor(activity.getCustomThemeWrapper().getPrimaryTextColor());
            binding.imageViewItemNavDrawerMenuItem.setColorFilter(activity.getCustomThemeWrapper().getPrimaryIconColor());
            if (activity.typeface != null) binding.textViewItemNavDrawerMenuItem.setTypeface(activity.typeface);
        }
    }
}
