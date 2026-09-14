// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main page: searchable, filterable list of per-app thermal overrides. */
public class MainActivity extends CollapsingToolbarBaseActivity
        implements AppListAdapter.Listener {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_MODIFIED = 1;
    private static final String PREF_SHOW_SYSTEM = "show_system";

    private final ThermalServiceClient mClient = new ThermalServiceClient();
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    private TextView mFilterChip;
    private TextView mErrorHint;
    private TextView mUnavailableText;
    private Switch mMasterSwitch;
    private View mListBlocker;
    private RecyclerView mRecyclerView;
    private AppListAdapter mAdapter;

    private final List<AppEntry> mAllEntries = new ArrayList<>();
    private final Map<String, Integer> mOverrides = new LinkedHashMap<>();

    private boolean mAvailable;
    private boolean mEnabled;
    private boolean mUpdatingSwitch;
    private int mFilter = FILTER_ALL;
    private String mQuery = "";
    private boolean mShowSystem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        mShowSystem = getPreferences(MODE_PRIVATE).getBoolean(PREF_SHOW_SYSTEM, false);

        mFilterChip = findViewById(R.id.filter_chip);
        mErrorHint = findViewById(R.id.error_hint);
        mUnavailableText = findViewById(R.id.unavailable_text);
        mMasterSwitch = findViewById(R.id.master_switch);
        mListBlocker = findViewById(R.id.list_blocker);
        mRecyclerView = findViewById(R.id.app_list);

        mAdapter = new AppListAdapter(this);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mAdapter);

        mFilterChip.setOnClickListener(this::showFilterPopup);
        mListBlocker.setOnTouchListener((v, event) -> true);

        mMasterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mUpdatingSwitch) {
                return;
            }
            mMasterSwitch.setEnabled(false);
            mClient.setEnabled(isChecked, success -> {
                mMasterSwitch.setEnabled(true);
                if (success) {
                    mEnabled = isChecked;
                    updateListEnabledState();
                } else {
                    mUpdatingSwitch = true;
                    mMasterSwitch.setChecked(!isChecked);
                    mUpdatingSwitch = false;
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });

        updateFilterChipText();
        showUnavailable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClient.start(this::onServiceState);
    }

    @Override
    protected void onPause() {
        mClient.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mClient.shutdown();
        mLoadExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        final MenuItem searchItem = menu.findItem(R.id.action_search);
        final EditText searchEdit = searchItem.getActionView().findViewById(R.id.search_edit);
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                searchEdit.requestFocus();
                searchEdit.post(() -> {
                    final InputMethodManager imm =
                            getSystemService(InputMethodManager.class);
                    imm.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT);
                });
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                searchEdit.setText("");
                mQuery = "";
                applyFilters();
                return true;
            }
        });
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mQuery = s == null ? "" : s.toString();
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        final MenuItem showSystem = menu.findItem(R.id.action_show_system);
        showSystem.setChecked(mShowSystem);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_show_system) {
            mShowSystem = !item.isChecked();
            item.setChecked(mShowSystem);
            final SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit();
            editor.putBoolean(PREF_SHOW_SYSTEM, mShowSystem);
            editor.apply();
            if (mAvailable) {
                loadApps();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onServiceState(boolean available, Bundle state) {
        if (!available) {
            showUnavailable();
            return;
        }
        mAvailable = true;
        mUnavailableText.setVisibility(View.GONE);
        mFilterChip.setEnabled(true);
        mMasterSwitch.setEnabled(true);

        mEnabled = state.getBoolean(ThermalServiceClient.KEY_ENABLED, false);
        mUpdatingSwitch = true;
        mMasterSwitch.setChecked(mEnabled);
        mUpdatingSwitch = false;
        updateListEnabledState();

        final String error = state.getString(ThermalServiceClient.KEY_ERROR, "");
        if (!error.isEmpty()) {
            mErrorHint.setText(getString(R.string.last_error_format, error));
            mErrorHint.setVisibility(View.VISIBLE);
        } else {
            mErrorHint.setVisibility(View.GONE);
        }

        mOverrides.clear();
        final Bundle overrides = state.getBundle(ThermalServiceClient.KEY_OVERRIDES);
        if (overrides != null) {
            for (String pkg : overrides.keySet()) {
                mOverrides.put(pkg, overrides.getInt(pkg, -1));
            }
        }
        loadApps();
    }

    private void showUnavailable() {
        mAvailable = false;
        mUnavailableText.setVisibility(View.VISIBLE);
        mFilterChip.setEnabled(false);
        mMasterSwitch.setEnabled(false);
        mAdapter.setItems(new ArrayList<>());
    }

    private void updateListEnabledState() {
        mRecyclerView.setAlpha(mEnabled ? 1.0f : 0.4f);
        mListBlocker.setVisibility(mEnabled ? View.GONE : View.VISIBLE);
    }

    private void loadApps() {
        final boolean showSystem = mShowSystem;
        final Map<String, Integer> overrides = new LinkedHashMap<>(mOverrides);
        mLoadExecutor.execute(() -> {
            final List<AppEntry> entries = buildEntries(showSystem);
            for (AppEntry entry : entries) {
                final Integer override = overrides.get(entry.packageName);
                entry.overrideProfile = override == null ? -1 : override;
            }
            final Collator collator = Collator.getInstance(Locale.getDefault());
            entries.sort((a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                if (!mAvailable) {
                    return;
                }
                mAllEntries.clear();
                mAllEntries.addAll(entries);
                applyFilters();
            });
        });
    }

    private List<AppEntry> buildEntries(boolean showSystem) {
        final PackageManager pm = getPackageManager();
        final Map<String, AppEntry> result = new LinkedHashMap<>();
        if (!showSystem) {
            final LauncherApps launcherApps = getSystemService(LauncherApps.class);
            for (LauncherActivityInfo info
                    : launcherApps.getActivityList(null, Process.myUserHandle())) {
                final String pkg = info.getApplicationInfo().packageName;
                if (!result.containsKey(pkg)) {
                    result.put(pkg, new AppEntry(pkg, info.getLabel().toString()));
                }
            }
        } else {
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                final CharSequence label = info.loadLabel(pm);
                result.put(info.packageName, new AppEntry(info.packageName,
                        label == null ? info.packageName : label.toString()));
            }
        }
        return new ArrayList<>(result.values());
    }

    private void applyFilters() {
        final String query = mQuery.toLowerCase(Locale.ROOT);
        final List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry entry : mAllEntries) {
            if (mFilter == FILTER_MODIFIED && entry.overrideProfile == -1) {
                continue;
            }
            if (!query.isEmpty()
                    && !entry.label.toLowerCase(Locale.ROOT).contains(query)
                    && !entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            filtered.add(entry);
        }
        mAdapter.setItems(filtered);
    }

    private void showFilterPopup(View anchor) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, FILTER_ALL, 0, R.string.filter_all_apps).setCheckable(true);
        popup.getMenu().add(0, FILTER_MODIFIED, 1, R.string.filter_modified).setCheckable(true);
        popup.getMenu().setGroupCheckable(0, true, true);
        popup.getMenu().findItem(mFilter).setChecked(true);
        popup.setOnMenuItemClickListener(item -> {
            mFilter = item.getItemId();
            updateFilterChipText();
            applyFilters();
            return true;
        });
        popup.show();
    }

    private void updateFilterChipText() {
        mFilterChip.setText(mFilter == FILTER_MODIFIED
                ? R.string.filter_modified : R.string.filter_all_apps);
    }

    @Override
    public void onAppClick(AppEntry entry) {
        AppDetailActivity.start(this, entry.packageName);
    }

    @Override
    public void onIconNeeded(AppEntry entry) {
        mLoadExecutor.execute(() -> {
            try {
                final Drawable icon = getPackageManager()
                        .getApplicationIcon(entry.packageName);
                IconCache.put(this, entry.packageName, icon);
                runOnUiThread(() -> {
                    final int index = mAdapter.indexOf(entry.packageName);
                    if (index >= 0) {
                        mAdapter.notifyItemChanged(index);
                    }
                });
            } catch (PackageManager.NameNotFoundException e) {
                // App vanished between listing and icon load; next reload drops it.
            }
        });
    }

    @Override
    public void onPolicyNeeded(AppEntry entry) {
        mClient.getAppPolicy(entry.packageName, new ThermalServiceClient.PolicyCallback() {
            @Override
            public void onPolicy(Bundle policy) {
                if (policy != null) {
                    entry.stockGroup = policy.getString(
                            ThermalServiceClient.KEY_STOCK_GROUP, "");
                    entry.overrideProfile = policy.getInt(
                            ThermalServiceClient.KEY_OVERRIDE_PROFILE, entry.overrideProfile);
                }
                final int index = mAdapter.indexOf(entry.packageName);
                if (index >= 0) {
                    mAdapter.notifyItemChanged(index);
                }
            }

            @Override
            public void onError() {
                // Keep the generic subtitle; no retry to avoid a binder call loop.
            }
        });
    }
}
