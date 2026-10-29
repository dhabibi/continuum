package ml.docilealligator.infinityforreddit.activities;


import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsService;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Named;
import ml.docilealligator.infinityforreddit.Infinity;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.FetchMyInfo;
import ml.docilealligator.infinityforreddit.apis.RedditAPI;
import ml.docilealligator.infinityforreddit.asynctasks.ParseAndInsertNewAccount;
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper;
import ml.docilealligator.infinityforreddit.databinding.ActivityLoginChromeCustomTabBinding;
import ml.docilealligator.infinityforreddit.events.NewUserLoggedInEvent;
import ml.docilealligator.infinityforreddit.utils.APIUtils;
import ml.docilealligator.infinityforreddit.utils.OAuthLoginHelper;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import ml.docilealligator.infinityforreddit.utils.Utils;
import org.greenrobot.eventbus.EventBus;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

public class LoginChromeCustomTabActivity extends BaseActivity {

    @Inject
    @Named("no_oauth")
    Retrofit mRetrofit;
    @Inject
    @Named("oauth")
    Retrofit mOauthRetrofit;
    @Inject
    RedditDataRoomDatabase mRedditDataRoomDatabase;
    @Inject
    @Named("default")
    SharedPreferences mSharedPreferences;
    @Inject
    @Named("current_account")
    SharedPreferences mCurrentAccountSharedPreferences;
    @Inject
    CustomThemeWrapper mCustomThemeWrapper;
    @Inject
    Executor mExecutor;
    private ActivityLoginChromeCustomTabBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ((Infinity) getApplication()).getAppComponent().inject(this);

        setImmersiveModeNotApplicableBelowAndroid16();

        super.onCreate(savedInstanceState);
        binding = ActivityLoginChromeCustomTabBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applyCustomTheme();

        if (isImmersiveInterfaceRespectForcedEdgeToEdge()) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), new OnApplyWindowInsetsListener() {
                @NonNull
                @Override
                public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
                    Insets allInsets = Utils.getInsets(insets, false, isForcedImmersiveInterface());

                    setMargins(binding.toolbarLoginChromeCustomTabActivity,
                            allInsets.left,
                            allInsets.top,
                            allInsets.right,
                            BaseActivity.IGNORE_MARGIN);

                    return WindowInsetsCompat.CONSUMED;
                }
            });
        }

        setSupportActionBar(binding.toolbarLoginChromeCustomTabActivity);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);

        Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            handleRedirectUri(intent.getData());
        } else {
            checkAndOpenLoginPage();
        }

        binding.openWebpageButtonLoginChromeCustomTabActivity.setOnClickListener(view -> {
            checkAndOpenLoginPage();
        });
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);

        Uri uri = intent.getData();

        if (uri == null) {
            binding.openWebpageButtonLoginChromeCustomTabActivity.setVisibility(View.VISIBLE);
            return;
        }

        handleRedirectUri(uri);
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
        applyAppBarLayoutAndCollapsingToolbarLayoutAndToolbarTheme(binding.appbarLayoutLoginChromeCustomTabActivity, null, binding.toolbarLoginChromeCustomTabActivity);
        binding.openWebpageButtonLoginChromeCustomTabActivity.setTextColor(mCustomThemeWrapper.getButtonTextColor());
        binding.openWebpageButtonLoginChromeCustomTabActivity.setBackgroundColor(mCustomThemeWrapper.getColorPrimaryLightTheme());
        if (typeface != null) {
            binding.openWebpageButtonLoginChromeCustomTabActivity.setTypeface(typeface);
        }
    }

    private void handleRedirectUri(@NonNull Uri uri) {
        binding.openWebpageButtonLoginChromeCustomTabActivity.setVisibility(View.GONE);

        OAuthLoginHelper.RedirectResult redirect = OAuthLoginHelper.classifyRedirect(
                uri.getQueryParameter("code"), uri.getQueryParameter("state"), uri.getQueryParameter("error"));
        switch (redirect.action) {
            case EXCHANGE_CODE:
                exchangeCodeForToken(Objects.requireNonNull(redirect.authCode));
                break;
            case ACCESS_DENIED:
                Toast.makeText(this, R.string.access_denied, Toast.LENGTH_SHORT).show();
                finish();
                break;
            case OAUTH_ERROR:
                Toast.makeText(this, getString(R.string.oauth_error_reddit_error, redirect.errorValue), Toast.LENGTH_LONG).show();
                finish();
                break;
            case STATE_MISMATCH:
            case NONE:
            default:
                Toast.makeText(this, R.string.something_went_wrong, Toast.LENGTH_SHORT).show();
                finish();
                break;
        }
    }

    private void exchangeCodeForToken(String authCode) {
        Map<String, String> params = new HashMap<>();
        params.put(APIUtils.GRANT_TYPE_KEY, "authorization_code");
        params.put("code", authCode);
        params.put(APIUtils.REDIRECT_URI_KEY, APIUtils.REDIRECT_URI);

        RedditAPI api = mRetrofit.create(RedditAPI.class);
        Call<String> accessTokenCall = api.getAccessToken(APIUtils.getHttpBasicAuthHeader(getApplicationContext()), params);
        accessTokenCall.enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                OAuthLoginHelper.TokenResult result =
                        OAuthLoginHelper.classifyTokenResponse(response);
                if (!result.isSuccess()) {
                    Toast.makeText(LoginChromeCustomTabActivity.this, OAuthLoginHelper.describeFailure(getApplicationContext(), result), Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                String accessToken = Objects.requireNonNull(result.accessToken);
                String refreshToken = Objects.requireNonNull(result.refreshToken);

                FetchMyInfo.fetchAccountInfo(mExecutor, mHandler, mOauthRetrofit,
                        mRedditDataRoomDatabase, accessToken,
                        new FetchMyInfo.FetchMyInfoListener() {
                            @Override
                            public void onFetchMyInfoSuccess(String name, String profileImageUrl, @Nullable String bannerImageUrl, int karma, boolean isMod) {
                                mCurrentAccountSharedPreferences.edit().putString(SharedPreferencesUtils.ACCESS_TOKEN, accessToken)
                                    .putString(SharedPreferencesUtils.ACCOUNT_NAME, name)
                                    .putString(SharedPreferencesUtils.ACCOUNT_IMAGE_URL, profileImageUrl).apply();
                                mCurrentAccountSharedPreferences.edit().remove(SharedPreferencesUtils.SUBSCRIBED_THINGS_SYNC_TIME).apply();
                                ParseAndInsertNewAccount.parseAndInsertNewAccount(mExecutor, new Handler(), name, accessToken, refreshToken, profileImageUrl, bannerImageUrl,
                                        karma, isMod, authCode, mRedditDataRoomDatabase.accountDao(),
                                        () -> {
                                            EventBus.getDefault().post(new NewUserLoggedInEvent());
                                            finish();
                                        });
                            }

                            @Override
                            public void onFetchMyInfoFailed(boolean parseFailed) {
                                if (parseFailed) {
                                    Toast.makeText(LoginChromeCustomTabActivity.this, R.string.parse_user_info_error, Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(LoginChromeCustomTabActivity.this, R.string.cannot_fetch_user_info, Toast.LENGTH_SHORT).show();
                                }

                                finish();
                            }
                        });
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                OAuthLoginHelper.TokenResult result = OAuthLoginHelper.classifyThrowable(t);
                Toast.makeText(LoginChromeCustomTabActivity.this, OAuthLoginHelper.describeFailure(getApplicationContext(), result), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void checkAndOpenLoginPage() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

}
