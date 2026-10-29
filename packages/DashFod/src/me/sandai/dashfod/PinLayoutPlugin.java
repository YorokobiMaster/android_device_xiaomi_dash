/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.dashfod;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.annotations.Requires;

/** Applies the selected RRO geometry inside SystemUI without recreating PIN input. */
@Requires(target = OverlayPlugin.class, version = OverlayPlugin.VERSION)
public final class PinLayoutPlugin implements OverlayPlugin {
    private static final String TAG = "DashPinPlugin";
    private static final String SYSTEMUI = "com.android.systemui";
    private Context mHostContext;
    private View mRoot;
    private int mBouncerId;
    private int mMarkerId;
    private int mIconSize;
    private int mBottomMargin;

    private final ViewTreeObserver.OnGlobalLayoutListener mLayoutListener = this::applyGeometry;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())
                    || (intent.getData() != null
                    && SYSTEMUI.equals(intent.getData().getSchemeSpecificPart()))) {
                readGeometry();
            }
        }
    };

    @Override
    public void onCreate(Context sysuiContext, Context pluginContext) {
        mHostContext = sysuiContext;
        IntentFilter overlays = new IntentFilter(Intent.ACTION_OVERLAY_CHANGED);
        overlays.addDataScheme("package");
        mHostContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, overlays,
                null, null, Context.RECEIVER_EXPORTED);
        mHostContext.registerReceiverAsUser(mReceiver, UserHandle.ALL,
                new IntentFilter(Intent.ACTION_USER_SWITCHED),
                null, null, Context.RECEIVER_EXPORTED);
        readGeometry();
    }

    @Override
    public void setup(View statusBar, View navBar) {
        if (mRoot != null) {
            mRoot.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutListener);
        }
        mRoot = statusBar;
        mRoot.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
        applyGeometry();
        Log.i(TAG, "Attached to SystemUI");
    }

    @Override
    public void onDestroy() {
        if (mRoot != null) {
            mRoot.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutListener);
            mRoot = null;
        }
        mHostContext.unregisterReceiver(mReceiver);
    }

    private void readGeometry() {
        try {
            // Match OverlayManager's user-specific lookup instead of a cached window context.
            Resources resources = mHostContext.createContextAsUser(
                    UserHandle.of(ActivityManager.getCurrentUser()), 0)
                    .getPackageManager().getResourcesForApplication(SYSTEMUI);
            int bouncerId = resources.getIdentifier("keyguard_bouncer_container", "id", SYSTEMUI);
            int markerId = resources.getIdentifier("dash_pin_udfps_marker", "id", SYSTEMUI);
            int size = resources.getDimensionPixelSize(
                    resources.getIdentifier("dash_pin_udfps_icon_size", "dimen", SYSTEMUI));
            int margin = resources.getDimensionPixelSize(
                    resources.getIdentifier("dash_pin_udfps_bottom_margin", "dimen", SYSTEMUI));
            if (bouncerId == 0 || markerId == 0) {
                throw new Resources.NotFoundException("Missing PIN view ids");
            }
            mBouncerId = bouncerId;
            mMarkerId = markerId;
            mIconSize = size;
            mBottomMargin = margin;
            applyGeometry();
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            Log.e(TAG, "Cannot read PIN geometry", e);
        }
    }

    private void applyGeometry() {
        if (mRoot == null || mMarkerId == 0) return;
        View bouncer = mRoot.findViewById(mBouncerId);
        if (bouncer == null || bouncer.getVisibility() != View.VISIBLE) return;
        View marker = bouncer.findViewById(mMarkerId);
        if (!(marker instanceof ViewGroup)) return;
        View icon = ((ViewGroup) marker).getChildAt(0);
        if (icon == null) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) icon.getLayoutParams();
        if (params.width == mIconSize && params.height == mIconSize
                && params.bottomMargin == mBottomMargin) return;
        params.width = mIconSize;
        params.height = mIconSize;
        params.bottomMargin = mBottomMargin;
        icon.setLayoutParams(params);
        Log.i(TAG, "Applied PIN marker size=" + mIconSize + " bottomMargin=" + mBottomMargin);
    }
}
