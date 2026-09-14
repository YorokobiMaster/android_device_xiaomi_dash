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

/** Detail page: one app, seven mutually exclusive profile cards. */
public class AppDetailActivity extends CollapsingToolbarBaseActivity {

    private static final String EXTRA_PACKAGE = "packageName";

    private final ThermalServiceClient mClient = new ThermalServiceClient();
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    private final Map<Integer, ImageView> mRadios = new HashMap<>();

    private String mPackageName;
    private String mStockGroup = "";
    private int mOverrideProfile = -1;

    private LinearLayout mOptionsContainer;
    private View mHeader;
    private View mFooter;
    private TextView mUnavailableText;
    private TextView mAutoSummary;

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

        mHeader = findViewById(R.id.app_header);
        mOptionsContainer = findViewById(R.id.options_container);
        mFooter = findViewById(R.id.detail_footer);
        mUnavailableText = findViewById(R.id.unavailable_text);

        buildOptionCards();
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
                    setTitle(title);
                    ((TextView) findViewById(R.id.app_label)).setText(title);
                    ((ImageView) findViewById(R.id.app_icon)).setImageDrawable(icon);
                });
            } catch (PackageManager.NameNotFoundException e) {
                runOnUiThread(this::finish);
            }
        });
    }

    private void buildOptionCards() {
        final LayoutInflater inflater = LayoutInflater.from(this);
        for (int profileId : ThermalProfiles.IDS) {
            final View card = inflater.inflate(R.layout.item_profile_option,
                    mOptionsContainer, false);
            final TextView title = card.findViewById(R.id.option_title);
            final TextView summary = card.findViewById(R.id.option_summary);
            final ImageView radio = card.findViewById(R.id.option_radio);
            title.setText(ThermalProfiles.nameRes(profileId));
            summary.setText(ThermalProfiles.summaryRes(profileId));
            if (profileId == -1) {
                mAutoSummary = summary;
            }
            mRadios.put(profileId, radio);
            card.setOnClickListener(v -> onProfileSelected(profileId));
            mOptionsContainer.addView(card);
        }
        updateSelection();
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
                mOverrideProfile = policy.getInt(
                        ThermalServiceClient.KEY_OVERRIDE_PROFILE, -1);
                mStockGroup = policy.getString(ThermalServiceClient.KEY_STOCK_GROUP, "");
                updateAutoSummary();
                updateSelection();
            }

            @Override
            public void onError() {
                showUnavailable();
            }
        });
    }

    private void onProfileSelected(int profileId) {
        if (profileId == mOverrideProfile) {
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
            entry.getValue().setImageResource(entry.getKey() == mOverrideProfile
                    ? R.drawable.ic_radio_checked : R.drawable.ic_radio_unchecked);
        }
    }

    private void updateAutoSummary() {
        if (mAutoSummary == null) {
            return;
        }
        if (mStockGroup != null && !mStockGroup.isEmpty()) {
            mAutoSummary.setText(getString(R.string.profile_auto_summary_category, mStockGroup));
        } else {
            mAutoSummary.setText(R.string.profile_auto_summary);
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
