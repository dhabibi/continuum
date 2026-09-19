package ml.docilealligator.infinityforreddit.adapters.navigationdrawer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.activities.InboxActivity;
import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.account.AccountScope;
import ml.docilealligator.infinityforreddit.multireddit.MultiReddit;
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper;
import ml.docilealligator.infinityforreddit.databinding.ItemNavDrawerMenuGroupTitleBinding;
import ml.docilealligator.infinityforreddit.databinding.ItemNavDrawerMenuItemBinding;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;

public class AccountSectionRecyclerViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_MENU_GROUP_TITLE = 1;
    private static final int VIEW_TYPE_MENU_ITEM = 2;
    private static final int ACCOUNT_SECTION_ITEMS = 5;
    private static final int ANONYMOUS_ACCOUNT_SECTION_ITEMS = 3;

    private final BaseActivity baseActivity;
    private int inboxCount;
    private final int primaryTextColor;
    private final int secondaryTextColor;
    private final int primaryIconColor;
    private boolean collapseAccountSection;
    /** The Recently Visited row is only offered while that setting is on for this account. */
    private boolean showRecentlyVisited;
    private final boolean isLoggedIn;
    private final SharedPreferences navigationDrawerSharedPreferences;
    private final String expandedPreferenceKey;
    private boolean multiRedditsExpanded;
    private final List<MultiReddit> multiReddits = new ArrayList<>();
    private final NavigationDrawerRecyclerViewMergedAdapter.ItemClickListener itemClickListener;

    public AccountSectionRecyclerViewAdapter(BaseActivity baseActivity, CustomThemeWrapper customThemeWrapper,
                                             SharedPreferences navigationDrawerSharedPreferences, String accountName,
                                             boolean showRecentlyVisited,
                                             NavigationDrawerRecyclerViewMergedAdapter.ItemClickListener itemClickListener) {
        this.baseActivity = baseActivity;
        primaryTextColor = customThemeWrapper.getPrimaryTextColor();
        secondaryTextColor = customThemeWrapper.getSecondaryTextColor();
        primaryIconColor = customThemeWrapper.getPrimaryIconColor();
        collapseAccountSection = navigationDrawerSharedPreferences.getBoolean(SharedPreferencesUtils.COLLAPSE_ACCOUNT_SECTION, false);
        this.isLoggedIn = !Account.isAnonymous(accountName);
        this.navigationDrawerSharedPreferences = navigationDrawerSharedPreferences;
        expandedPreferenceKey = AccountScope.key(accountName, "drawer_multireddits_expanded");
        multiRedditsExpanded = navigationDrawerSharedPreferences.getBoolean(expandedPreferenceKey, false);
        this.showRecentlyVisited = showRecentlyVisited;
        this.itemClickListener = itemClickListener;
    }

    public void setCollapseAccountSection(boolean collapseAccountSection) {
        this.collapseAccountSection = collapseAccountSection;
        notifyDataSetChanged();
    }

    public void setShowRecentlyVisited(boolean showRecentlyVisited) {
        if (this.showRecentlyVisited == showRecentlyVisited) {
            return;
        }
        this.showRecentlyVisited = showRecentlyVisited;
        notifyDataSetChanged();
    }

    /** Menu rows below the group title, which varies with login state and the Recently Visited setting. */
    private int menuItemCount() {
        return baseMenuItemCount() + 1 + expandedItemCount();
    }

    private int baseMenuItemCount() {
        return (isLoggedIn ? ACCOUNT_SECTION_ITEMS : ANONYMOUS_ACCOUNT_SECTION_ITEMS) + (showRecentlyVisited ? 1 : 0);
    }

    private int expandedItemCount() {
        return multiRedditsExpanded ? multiReddits.size() + 1 : 0;
    }

    public void setMultiReddits(List<MultiReddit> items) {
        multiReddits.clear();
        multiReddits.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? VIEW_TYPE_MENU_GROUP_TITLE : VIEW_TYPE_MENU_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_MENU_GROUP_TITLE) {
            return new MenuGroupTitleViewHolder(ItemNavDrawerMenuGroupTitleBinding
                    .inflate(LayoutInflater.from(parent.getContext()), parent, false));
        } else {
            return new MenuItemViewHolder(ItemNavDrawerMenuItemBinding
                    .inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof MenuGroupTitleViewHolder) {
            ((MenuGroupTitleViewHolder) holder).binding.titleTextViewItemNavDrawerMenuGroupTitle.setText(R.string.label_account);
            if (collapseAccountSection) {
                ((MenuGroupTitleViewHolder) holder).binding.collapseIndicatorImageViewItemNavDrawerMenuGroupTitle.setImageResource(R.drawable.ic_baseline_arrow_drop_up_24dp);
            } else {
                ((MenuGroupTitleViewHolder) holder).binding.collapseIndicatorImageViewItemNavDrawerMenuGroupTitle.setImageResource(R.drawable.ic_baseline_arrow_drop_down_24dp);
            }

            holder.itemView.setOnClickListener(view -> {
                if (collapseAccountSection) {
                    collapseAccountSection = !collapseAccountSection;
                    notifyItemRangeInserted(holder.getBindingAdapterPosition() + 1, menuItemCount());
                } else {
                    collapseAccountSection = !collapseAccountSection;
                    notifyItemRangeRemoved(holder.getBindingAdapterPosition() + 1, menuItemCount());
                }
                notifyItemChanged(holder.getBindingAdapterPosition());
            });
        } else if (holder instanceof MenuItemViewHolder) {
            MenuItemViewHolder itemHolder = (MenuItemViewHolder) holder;
            itemHolder.binding.expandIndicatorItemNavDrawerMenuItem.setVisibility(View.GONE);
            holder.itemView.setOnLongClickListener(null);
            ViewCompat.setStateDescription(holder.itemView, null);
            int multiPosition = isLoggedIn ? 3 : 2;
            boolean child = position > multiPosition && position <= multiPosition + expandedItemCount();
            int padding = (int) (baseActivity.getResources().getDisplayMetrics().density * (child ? 48 : 16));
            holder.itemView.setPaddingRelative(padding, holder.itemView.getPaddingTop(),
                    holder.itemView.getPaddingEnd(), holder.itemView.getPaddingBottom());
            if (child) {
                int index = position - multiPosition - 1;
                itemHolder.binding.imageViewItemNavDrawerMenuItem.setImageResource(R.drawable.ic_multi_reddit_day_night_24dp);
                if (index < multiReddits.size()) {
                    MultiReddit multi = multiReddits.get(index);
                    itemHolder.binding.textViewItemNavDrawerMenuItem.setText(multi.getDisplayName());
                    holder.itemView.setOnClickListener(view -> itemClickListener.onMultiRedditClick(multi));
                    if (!isLoggedIn) {
                        holder.itemView.setOnLongClickListener(view -> {
                            itemClickListener.onMultiRedditLongClick(multi);
                            return true;
                        });
                    }
                } else {
                    itemHolder.binding.textViewItemNavDrawerMenuItem.setText(R.string.manage_multireddits);
                    holder.itemView.setOnClickListener(view -> itemClickListener.onMenuClick(R.string.multi_reddit));
                }
                return;
            }
            int menuPosition = position > multiPosition ? position - expandedItemCount() : position;
            int stringId = 0;
            int drawableId = 0;
            boolean setOnClickListener = true;

            if (menuPosition == baseMenuItemCount() + 1) {
                stringId = R.string.find_subreddits;
                drawableId = R.drawable.ic_search_toolbar_24dp;
            } else if (isLoggedIn) {
                switch (menuPosition) {
                    case 1:
                        stringId = R.string.profile;
                        drawableId = R.drawable.ic_account_circle_day_night_24dp;
                        break;
                    case 2:
                        stringId = R.string.subscriptions;
                        drawableId = R.drawable.ic_subscriptions_bottom_app_bar_day_night_24dp;
                        break;
                    case 3:
                        stringId = R.string.multi_reddit;
                        drawableId = R.drawable.ic_multi_reddit_day_night_24dp;
                        break;
                    case 4:
                        setOnClickListener = false;
                        if (inboxCount > 0) {
                            ((MenuItemViewHolder) holder).binding.textViewItemNavDrawerMenuItem.setText(baseActivity.getString(R.string.inbox_with_count, inboxCount));
                        } else {
                            ((MenuItemViewHolder) holder).binding.textViewItemNavDrawerMenuItem.setText(R.string.inbox);
                        }
                        ((MenuItemViewHolder) holder).binding.imageViewItemNavDrawerMenuItem.setImageDrawable(ContextCompat.getDrawable(baseActivity, R.drawable.ic_inbox_day_night_24dp));
                        holder.itemView.setOnClickListener(view -> {
                            Intent intent = new Intent(baseActivity, InboxActivity.class);
                            baseActivity.startActivity(intent);
                        });
                        break;
                    case 5:
                        stringId = R.string.history;
                        drawableId = R.drawable.ic_history_day_night_24dp;
                        break;
                    default:
                        stringId = R.string.recently_visited;
                        drawableId = R.drawable.ic_access_time_day_night_24dp;
                }
            } else {
                switch (menuPosition) {
                    case 1:
                        stringId = R.string.subscriptions;
                        drawableId = R.drawable.ic_subscriptions_bottom_app_bar_day_night_24dp;
                        break;
                    case 2:
                        stringId = R.string.multi_reddit;
                        drawableId = R.drawable.ic_multi_reddit_day_night_24dp;
                        break;
                    case 3:
                        stringId = R.string.history;
                        drawableId = R.drawable.ic_history_day_night_24dp;
                        break;
                    default:
                        stringId = R.string.recently_visited;
                        drawableId = R.drawable.ic_access_time_day_night_24dp;
                }
            }

            if (stringId != 0) {
                // Display the plural label for the multireddit row, but keep stringId as the click key.
                int labelId = stringId == R.string.multi_reddit ? R.string.multi_reddits : stringId;
                ((MenuItemViewHolder) holder).binding.textViewItemNavDrawerMenuItem.setText(labelId);
                ((MenuItemViewHolder) holder).binding.imageViewItemNavDrawerMenuItem.setImageDrawable(ContextCompat.getDrawable(baseActivity, drawableId));
            }
            if (setOnClickListener) {
                int finalStringId = stringId;
                if (stringId == R.string.multi_reddit) {
                    itemHolder.binding.expandIndicatorItemNavDrawerMenuItem.setVisibility(View.VISIBLE);
                    itemHolder.binding.expandIndicatorItemNavDrawerMenuItem.setImageResource(multiRedditsExpanded
                            ? R.drawable.ic_baseline_arrow_drop_up_24dp : R.drawable.ic_baseline_arrow_drop_down_24dp);
                    ViewCompat.setStateDescription(holder.itemView, baseActivity.getString(multiRedditsExpanded
                            ? R.string.multireddits_expanded : R.string.multireddits_collapsed));
                    holder.itemView.setOnClickListener(view -> {
                        multiRedditsExpanded = !multiRedditsExpanded;
                        navigationDrawerSharedPreferences.edit().putBoolean(expandedPreferenceKey, multiRedditsExpanded).apply();
                        notifyDataSetChanged();
                    });
                } else {
                    holder.itemView.setOnClickListener(view -> itemClickListener.onMenuClick(finalStringId));
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return collapseAccountSection ? 1 : menuItemCount() + 1;
    }

    public void setInboxCount(int inboxCount) {
        if (inboxCount < 0) {
            this.inboxCount = Math.max(0, this.inboxCount + inboxCount);
        } else {
            this.inboxCount = inboxCount;
        }
        notifyDataSetChanged();
    }

    class MenuGroupTitleViewHolder extends RecyclerView.ViewHolder {
        ItemNavDrawerMenuGroupTitleBinding binding;

        MenuGroupTitleViewHolder(@NonNull ItemNavDrawerMenuGroupTitleBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            if (baseActivity.typeface != null) {
                binding.titleTextViewItemNavDrawerMenuGroupTitle.setTypeface(baseActivity.typeface);
            }
            binding.titleTextViewItemNavDrawerMenuGroupTitle.setTextColor(secondaryTextColor);
            binding.collapseIndicatorImageViewItemNavDrawerMenuGroupTitle.setColorFilter(secondaryTextColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    class MenuItemViewHolder extends RecyclerView.ViewHolder {
        ItemNavDrawerMenuItemBinding binding;

        MenuItemViewHolder(@NonNull ItemNavDrawerMenuItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            if (baseActivity.typeface != null) {
                binding.textViewItemNavDrawerMenuItem.setTypeface(baseActivity.typeface);
            }
            binding.textViewItemNavDrawerMenuItem.setTextColor(primaryTextColor);
            binding.imageViewItemNavDrawerMenuItem.setColorFilter(primaryIconColor, android.graphics.PorterDuff.Mode.SRC_IN);
            binding.expandIndicatorItemNavDrawerMenuItem.setColorFilter(primaryIconColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }
}
