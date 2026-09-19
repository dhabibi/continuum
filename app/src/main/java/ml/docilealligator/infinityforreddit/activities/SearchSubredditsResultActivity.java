package ml.docilealligator.infinityforreddit.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Named;
import ml.docilealligator.infinityforreddit.Infinity;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper;
import ml.docilealligator.infinityforreddit.databinding.ActivitySearchSubredditsResultBinding;
import ml.docilealligator.infinityforreddit.events.SwitchAccountEvent;
import ml.docilealligator.infinityforreddit.fragments.SubredditListingFragment;
import ml.docilealligator.infinityforreddit.subreddit.SubredditData;
import ml.docilealligator.infinityforreddit.thing.SortType;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import ml.docilealligator.infinityforreddit.utils.Utils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public class SearchSubredditsResultActivity extends BaseActivity implements ActivityToolbarInterface {

    static final String EXTRA_QUERY = "EQ";
    static final String EXTRA_IS_MULTI_SELECTION = "EIMS";
    static final String RETURN_EXTRA_SELECTED_SUBREDDITS = "RESS";
    public static final String EXTRA_BROWSE = "EB";

    private static final String FRAGMENT_OUT_STATE = "FOS";
    private static final String QUERY_STATE = "QS";

    @Nullable
    Fragment mFragment;
    private String query = "";
    @Inject
    @Named("default")
    SharedPreferences mSharedPreferences;
    @Inject
    @Named("current_account")
    SharedPreferences mCurrentAccountSharedPreferences;
    @Inject
    @Named("sort_type")
    SharedPreferences mSortTypeSharedPreferences;
    @Inject
    CustomThemeWrapper mCustomThemeWrapper;
    private ActivitySearchSubredditsResultBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ((Infinity) getApplication()).getAppComponent().inject(this);

        super.onCreate(savedInstanceState);

        binding = ActivitySearchSubredditsResultBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        EventBus.getDefault().register(this);

        applyCustomTheme();

        attachSliderPanelIfApplicable();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Window window = getWindow();

            if (isChangeStatusBarIconColor()) {
                addOnOffsetChangedListener(binding.appbarLayoutSearchSubredditsResultActivity);
            }

            if (isImmersiveInterfaceRespectForcedEdgeToEdge()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.setDecorFitsSystemWindows(false);
                } else {
                    window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                }

                ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), new OnApplyWindowInsetsListener() {
                    @NonNull
                    @Override
                    public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
                        Insets allInsets = Utils.getInsets(insets, false, isForcedImmersiveInterface());

                        setMargins(binding.toolbarSearchSubredditsResultActivity,
                                allInsets.left,
                                allInsets.top,
                                allInsets.right,
                                BaseActivity.IGNORE_MARGIN);

                        binding.frameLayoutSearchSubredditsResultActivity.setPadding(allInsets.left, 0, allInsets.right, allInsets.bottom);

                        return insets;
                    }
                });
                //adjustToolbar(binding.toolbarSearchSubredditsResultActivity);
            }
        }

        setSupportActionBar(binding.toolbarSearchSubredditsResultActivity);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        setToolbarGoToTop(binding.toolbarSearchSubredditsResultActivity);

        boolean browse = getIntent().getBooleanExtra(EXTRA_BROWSE, false);
        query = Objects.requireNonNullElse(savedInstanceState == null
                ? getIntent().getStringExtra(EXTRA_QUERY) : savedInstanceState.getString(QUERY_STATE), "");
        mFragment = savedInstanceState == null || !savedInstanceState.containsKey(FRAGMENT_OUT_STATE)
                ? null
                : getSupportFragmentManager().getFragment(savedInstanceState, FRAGMENT_OUT_STATE);
        if (browse) {
            setTitle(R.string.find_subreddits);
            binding.searchViewSubreddits.setVisibility(View.VISIBLE);
            binding.searchViewSubreddits.setBackgroundColor(mCustomThemeWrapper.getBackgroundColor());
            for (int id : new int[]{androidx.appcompat.R.id.search_mag_icon, androidx.appcompat.R.id.search_close_btn,
                    androidx.appcompat.R.id.search_go_btn, androidx.appcompat.R.id.search_voice_btn}) {
                ImageView icon = binding.searchViewSubreddits.findViewById(id);
                if (icon != null) {
                    icon.setColorFilter(mCustomThemeWrapper.getPrimaryIconColor());
                }
            }
            SearchView.SearchAutoComplete input = binding.searchViewSubreddits.findViewById(androidx.appcompat.R.id.search_src_text);
            if (input != null) {
                input.setTextColor(mCustomThemeWrapper.getPrimaryTextColor());
                input.setHintTextColor(mCustomThemeWrapper.getSecondaryTextColor());
                if (typeface != null) {
                    input.setTypeface(typeface);
                }
            }
            binding.searchHelpSubreddits.setTextColor(mCustomThemeWrapper.getSecondaryTextColor());
            binding.searchHelpSubreddits.setVisibility(mFragment == null && query.isEmpty() ? View.VISIBLE : View.GONE);
            binding.searchViewSubreddits.setQuery(query, false);
            binding.searchViewSubreddits.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String text) {
                    String next = text.trim();
                    if (!next.isEmpty() && (!next.equals(query) || mFragment == null)) {
                        query = next;
                        showResults();
                    }
                    binding.searchViewSubreddits.clearFocus();
                    Utils.hideKeyboard(SearchSubredditsResultActivity.this);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String text) {
                    return false;
                }
            });
        }
        if (mFragment == null && (!browse || !query.isEmpty())) {
            showResults();
        }
    }

    private void showResults() {
        SubredditListingFragment fragment = new SubredditListingFragment();
        Bundle bundle = new Bundle();
        bundle.putString(SubredditListingFragment.EXTRA_QUERY, query);
        bundle.putBoolean(SubredditListingFragment.EXTRA_COMMUNITY_DETAILS,
                getIntent().getBooleanExtra(EXTRA_BROWSE, false));
        bundle.putBoolean(SubredditListingFragment.EXTRA_IS_GETTING_SUBREDDIT_INFO,
                !getIntent().getBooleanExtra(EXTRA_BROWSE, false));
        bundle.putBoolean(SubredditListingFragment.EXTRA_IS_MULTI_SELECTION,
                getIntent().getBooleanExtra(EXTRA_IS_MULTI_SELECTION, false));
        fragment.setArguments(bundle);
        mFragment = fragment;
        binding.searchHelpSubreddits.setVisibility(View.GONE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.frame_layout_search_subreddits_result_activity, fragment)
                .commit();
    }

    @Override
    public SharedPreferences getDefaultSharedPreferences() {
        return mSharedPreferences;
    }

    @Override
    public SharedPreferences getCurrentAccountSharedPreferences() {
        return mCurrentAccountSharedPreferences;
    }

    @Override
    public CustomThemeWrapper getCustomThemeWrapper() {
        return mCustomThemeWrapper;
    }

    @Override
    protected void applyCustomTheme() {
        binding.getRoot().setBackgroundColor(mCustomThemeWrapper.getBackgroundColor());
        applyAppBarLayoutAndCollapsingToolbarLayoutAndToolbarTheme(binding.appbarLayoutSearchSubredditsResultActivity, null, binding.toolbarSearchSubredditsResultActivity);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (getIntent().getBooleanExtra(EXTRA_BROWSE, false)) {
            getMenuInflater().inflate(R.menu.find_subreddits, menu);
        }
        if (getIntent().getBooleanExtra(EXTRA_IS_MULTI_SELECTION, false)) {
            getMenuInflater().inflate(R.menu.search_subreddits_result_activity, menu);
        }
        applyMenuItemTheme(menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem activity = menu.findItem(R.id.action_find_subreddits_activity);
        if (activity != null) {
            boolean active = "ACTIVITY".equalsIgnoreCase(mSortTypeSharedPreferences.getString(
                    SharedPreferencesUtils.SORT_TYPE_SEARCH_SUBREDDIT, "RELEVANCE"));
            activity.setChecked(active);
            menu.findItem(R.id.action_find_subreddits_relevance).setChecked(!active);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_find_subreddits_activity || item.getItemId() == R.id.action_find_subreddits_relevance) {
            SortType.Type type = item.getItemId() == R.id.action_find_subreddits_activity
                    ? SortType.Type.ACTIVITY : SortType.Type.RELEVANCE;
            mSortTypeSharedPreferences.edit().putString(SharedPreferencesUtils.SORT_TYPE_SEARCH_SUBREDDIT, type.name()).apply();
            if (mFragment != null && mFragment.isAdded()) {
                ((SubredditListingFragment) mFragment).changeSortType(new SortType(type));
            }
            invalidateOptionsMenu();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_save_search_subreddits_result_activity) {
            if (mFragment != null) {
                ArrayList<SubredditData> selectedSubreddits = ((SubredditListingFragment) mFragment).getSelectedSubredditNames();
                Intent returnIntent = new Intent();
                returnIntent.putParcelableArrayListExtra(RETURN_EXTRA_SELECTED_SUBREDDITS, selectedSubreddits);
                setResult(Activity.RESULT_OK, returnIntent);
                finish();
            }
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(QUERY_STATE, query);
        if (mFragment != null && mFragment.isAdded()) {
            getSupportFragmentManager().putFragment(outState, FRAGMENT_OUT_STATE, mFragment);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe
    public void onAccountSwitchEvent(SwitchAccountEvent event) {
        finish();
    }

    @Override
    public void onLongPress() {
        if (mFragment != null) {
            ((SubredditListingFragment) mFragment).goBackToTop();
        }
    }
}
