package ml.docilealligator.infinityforreddit.account;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;

/** Local account identities. The selected storage partition is fixed until the process restarts. */
public final class LocalProfiles {
    public static final String DEFAULT_ID = "default";
    private static final String PREFERENCES = "continuum_local_profiles";
    private static final String SELECTED = "selected";
    private static final String NAME_PREFIX = "name.";
    @Nullable
    private static LocalProfiles instance;

    private final SharedPreferences preferences;
    private final String defaultName;
    private final String currentId;

    private LocalProfiles(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        defaultName = context.getString(R.string.local_account_default);
        String selected = preferences.getString(SELECTED, DEFAULT_ID);
        currentId = selected != null && validId(selected)
                && (DEFAULT_ID.equals(selected) || preferences.contains(NAME_PREFIX + selected))
                ? selected : DEFAULT_ID;
    }

    public static synchronized LocalProfiles get(Context context) {
        if (instance == null) {
            instance = new LocalProfiles(context.getApplicationContext());
        }
        return instance;
    }

    public String getCurrentId() {
        return currentId;
    }

    public String getCurrentName() {
        return Objects.requireNonNullElse(preferences.getString(NAME_PREFIX + currentId, defaultName), defaultName);
    }

    public List<Profile> getProfiles() {
        List<Profile> profiles = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (!entry.getKey().startsWith(NAME_PREFIX) || !(value instanceof String)) {
                continue;
            }
            String id = entry.getKey().substring(NAME_PREFIX.length());
            if (!DEFAULT_ID.equals(id) && validId(id)) {
                profiles.add(new Profile(id, (String) value));
            }
        }
        profiles.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        profiles.add(0, new Profile(DEFAULT_ID, Objects.requireNonNullElse(
                preferences.getString(NAME_PREFIX + DEFAULT_ID, defaultName), defaultName)));
        return profiles;
    }

    public String create(String name) {
        String normalized = validateName(name, null);
        String id = UUID.randomUUID().toString();
        if (!preferences.edit().putString(NAME_PREFIX + id, normalized).commit()) {
            throw new IllegalStateException("Could not save local account");
        }
        return id;
    }

    public void renameCurrent(String name) {
        String normalized = validateName(name, currentId);
        if (!preferences.edit().putString(NAME_PREFIX + currentId, normalized).commit()) {
            throw new IllegalStateException("Could not rename local account");
        }
    }

    private String validateName(String name, @Nullable String exceptId) {
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.length() > 40) {
            throw new IllegalArgumentException("Invalid local account name");
        }
        for (Profile profile : getProfiles()) {
            if (!profile.id.equals(exceptId) && profile.name.equalsIgnoreCase(normalized)) {
                throw new IllegalArgumentException("Local account name already exists");
            }
        }
        return normalized;
    }

    /** Persist the next launch's choice without redirecting in-flight writes in this process. */
    public boolean select(String id) {
        for (Profile profile : getProfiles()) {
            if (profile.id.equals(id)) {
                return preferences.edit().putString(SELECTED, id).commit();
            }
        }
        return false;
    }

    public String storageName(String original) {
        return storageName(original, currentId);
    }

    /** Keep navigation and account identity private; proxy and appearance settings stay shared. */
    public String preferenceFileName(String original) {
        if (original.equals(SharedPreferencesUtils.CURRENT_ACCOUNT_SHARED_PREFERENCES_FILE)
                || original.equals(SharedPreferencesUtils.FRONT_PAGE_SCROLLED_POSITION_SHARED_PREFERENCES_FILE)
                || original.equals(SharedPreferencesUtils.MAIN_PAGE_TABS_SHARED_PREFERENCES_FILE)) {
            return storageName(original);
        }
        return original;
    }

    public static String storageName(String original, String profileId) {
        if (!validId(profileId)) {
            throw new IllegalArgumentException("Invalid local account id");
        }
        return DEFAULT_ID.equals(profileId) ? original : original + "__local_" + profileId;
    }

    public File filesDir(Context context) {
        return directory(context.getFilesDir());
    }

    public File cacheDir(Context context) {
        return directory(context.getCacheDir());
    }

    private File directory(File original) {
        if (DEFAULT_ID.equals(currentId)) {
            return original;
        }
        File directory = new File(original, "local_profiles/" + currentId);
        directory.mkdirs();
        return directory;
    }

    private static boolean validId(String id) {
        return DEFAULT_ID.equals(id) || id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @VisibleForTesting
    public static synchronized void resetForTests() {
        instance = null;
    }

    public static final class Profile {
        public final String id;
        public final String name;

        private Profile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
