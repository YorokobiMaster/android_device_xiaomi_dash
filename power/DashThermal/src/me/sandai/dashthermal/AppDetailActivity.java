// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Detail page: fixed scheduling policies in sections; the radio rests on the current one. */
public class AppDetailActivity extends CollapsingToolbarBaseActivity {

    private static final String EXTRA_PACKAGE = "packageName";

    private final ThermalServiceClient mClient = new ThermalServiceClient();
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    private final Map<Integer, ImageView> mRadios = new HashMap<>();

    private String mPackageName;
    private int mSelectedProfile = -1;

    private LinearLayout mOptionsContainer;
    private View mHeader;
    private View mFooter;
    private TextView mUnavailableText;

    public static void start(Context context, String packageName) {
        final Intent intent = new Intent(context, AppDetailActivity.class);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPackageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (mPackageName == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_app_detail);
        setTitle(R.string.app_name);

        mHeader = findViewById(R.id.app_header);
        mOptionsContainer = findViewById(R.id.options_container);
        mFooter = findViewById(R.id.detail_footer);
        mUnavailableText = findViewById(R.id.unavailable_text);

        buildOptions();
        loadHeader();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClient.start((available, state) -> {
            if (available) {
                loadPolicy();
            } else {
                showUnavailable();
            }
        });
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

    private void loadHeader() {
        mLoadExecutor.execute(() -> {
            final PackageManager pm = getPackageManager();
            try {
                final ApplicationInfo info = pm.getApplicationInfo(mPackageName, 0);
                final CharSequence label = info.loadLabel(pm);
                final Drawable icon = pm.getApplicationIcon(info);
                runOnUiThread(() -> {
                    final CharSequence title = label == null ? mPackageName : label;
                    ((TextView) findViewById(R.id.app_label)).setText(title);
                    ((ImageView) findViewById(R.id.app_icon)).setImageDrawable(icon);
                });
            } catch (PackageManager.NameNotFoundException e) {
                runOnUiThread(this::finish);
            }
        });
    }

    private void buildOptions() {
        addSection(R.string.section_general, ThermalProfiles.GENERAL);
        addSection(R.string.section_gaming, ThermalProfiles.GAMING);
        addSection(R.string.section_others, ThermalProfiles.OTHERS);
    }

    private void addSection(int headerRes, int[] profileIds) {
        final LayoutInflater inflater = LayoutInflater.from(this);
        final TextView header = (TextView) inflater.inflate(
                R.layout.item_section_header, mOptionsContainer, false);
        header.setText(headerRes);
        mOptionsContainer.addView(header);
        for (int profileId : profileIds) {
            addOption(profileId);
        }
    }

    private void addOption(int profileId) {
        final View card = LayoutInflater.from(this).inflate(
                R.layout.item_profile_option, mOptionsContainer, false);
        bindOption(card, profileId);
        mRadios.put(profileId, card.findViewById(R.id.option_radio));
        card.setOnClickListener(v -> onProfileSelected(profileId));
        mOptionsContainer.addView(card);
    }

    private void bindOption(View card, int profileId) {
        final TextView title = card.findViewById(R.id.option_title);
        final TextView summary = card.findViewById(R.id.option_summary);
        final int nameRes = ThermalProfiles.nameRes(profileId);
        title.setText(nameRes != 0 ? getString(nameRes)
                : getString(R.string.profile_unknown, profileId));
        final int summaryRes = ThermalProfiles.summaryRes(profileId);
        if (summaryRes != 0) {
            summary.setText(summaryRes);
        } else {
            summary.setVisibility(View.GONE);
        }
    }

    private void loadPolicy() {
        mClient.getAppPolicy(mPackageName, new ThermalServiceClient.PolicyCallback() {
            @Override
            public void onPolicy(Bundle policy) {
                if (policy == null) {
                    showUnavailable();
                    return;
                }
                showAvailable();
                mSelectedProfile = ThermalProfiles.canon(policy.getInt(
                        ThermalServiceClient.KEY_SELECTED_PROFILE, 0));
                ensureOptionVisible(mSelectedProfile);
                updateSelection();
                setOptionsEnabled(true);
            }

            @Override
            public void onError() {
                showUnavailable();
            }
        });
    }

    /** A current policy outside the listed sections gets a raw-named row in Others. */
    private void ensureOptionVisible(int profileId) {
        if (profileId == -1 || mRadios.containsKey(profileId)) {
            return;
        }
        addOption(profileId);
    }

    private void onProfileSelected(int profileId) {
        if (profileId == mSelectedProfile) {
            return;
        }
        setOptionsEnabled(false);
        mClient.setAppProfile(mPackageName, profileId, success -> {
            if (success) {
                loadPolicy();
            } else {
                setOptionsEnabled(true);
                updateSelection();
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateSelection() {
        for (Map.Entry<Integer, ImageView> entry : mRadios.entrySet()) {
            entry.getValue().setImageResource(entry.getKey() == mSelectedProfile
                    ? R.drawable.ic_radio_checked : R.drawable.ic_radio_unchecked);
        }
    }

    private void setOptionsEnabled(boolean enabled) {
        mOptionsContainer.setEnabled(enabled);
        for (int i = 0; i < mOptionsContainer.getChildCount(); i++) {
            mOptionsContainer.getChildAt(i).setEnabled(enabled);
        }
    }

    private void showAvailable() {
        mHeader.setVisibility(View.VISIBLE);
        mOptionsContainer.setVisibility(View.VISIBLE);
        mFooter.setVisibility(View.VISIBLE);
        mUnavailableText.setVisibility(View.GONE);
    }

    private void showUnavailable() {
        mHeader.setVisibility(View.GONE);
        mOptionsContainer.setVisibility(View.GONE);
        mFooter.setVisibility(View.GONE);
        mUnavailableText.setVisibility(View.VISIBLE);
    }
}
