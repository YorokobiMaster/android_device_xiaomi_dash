/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.yorokobimaster.dash.faceunlock;

import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_CANCELED;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT;
import static android.hardware.biometrics.BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION;
import static android.hardware.biometrics.BiometricFaceConstants.FEATURE_REQUIRE_REQUIRE_DIVERSITY;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.biometrics.BiometricManager;
import android.hardware.face.FaceEnrollOptions;
import android.hardware.face.FaceManager;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;

/** Device-owned face enrollment screen that lets MiFace remain the sole camera client. */
public final class FaceEnrollActivity extends Activity
        implements TextureView.SurfaceTextureListener {
    private static final String TAG = "DashFaceEnroll";

    private static final String EXTRA_HARDWARE_AUTH_TOKEN = "hw_auth_token";
    private static final String EXTRA_REQUIRE_DIVERSITY = "accessibility_diversity";
    private static final String EXTRA_REQUIRE_VISION = "accessibility_vision";
    private static final String EXTRA_FINISHED_ENROLL_FACE = "finished_enrolling_face";

    private static final int RESULT_FINISHED = RESULT_FIRST_USER;
    private static final int RESULT_SKIP = RESULT_FIRST_USER + 1;
    private static final int RESULT_TIMEOUT = RESULT_FIRST_USER + 2;

    private TextureView mPreviewView;
    private ProgressBar mProgressView;
    private TextView mStatusView;
    private Button mSkipButton;
    private Button mDoneButton;

    private FaceManager mFaceManager;
    private CancellationSignal mCancellationSignal;
    private Surface mPreviewSurface;
    private byte[] mHardwareAuthToken;
    private int mUserId;
    private int mTotalSteps = -1;
    private boolean mEnrolling;
    private boolean mEnrollmentComplete;
    private boolean mResultCommitted;
    private boolean mErrorShown;

    private final FaceManager.EnrollmentCallback mEnrollmentCallback =
            new FaceManager.EnrollmentCallback() {
        @Override
        public void onEnrollmentProgress(int remaining) {
            if (mResultCommitted) {
                return;
            }
            if (mTotalSteps < 0) {
                mTotalSteps = Math.max(remaining, 1);
            }
            int completed = Math.max(0, mTotalSteps - remaining);
            mProgressView.setProgress(remaining == 0 ? 100 : completed * 100 / mTotalSteps);
            if (remaining == 0) {
                onEnrollmentComplete();
            } else {
                mStatusView.setText(getString(R.string.enroll_remaining, remaining));
            }
        }

        @Override
        public void onEnrollmentHelp(int helpMsgId, CharSequence helpString) {
            if (!mResultCommitted && !TextUtils.isEmpty(helpString)) {
                mStatusView.setText(helpString);
            }
        }

        @Override
        public void onEnrollmentError(int errMsgId, CharSequence errString) {
            mEnrolling = false;
            if (mResultCommitted || errMsgId == FACE_ERROR_CANCELED) {
                return;
            }
            Log.e(TAG, "enrollment error=" + errMsgId + " message=" + errString);
            int resultCode = errMsgId == FACE_ERROR_TIMEOUT ? RESULT_TIMEOUT : RESULT_FINISHED;
            showFatalError(errString, resultCode);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_face_enroll);

        mPreviewView = findViewById(R.id.preview);
        mProgressView = findViewById(R.id.progress);
        mStatusView = findViewById(R.id.status);
        mSkipButton = findViewById(R.id.skip);
        mDoneButton = findViewById(R.id.done);

        mSkipButton.setOnClickListener(view -> finishWithResult(RESULT_SKIP, false));
        mDoneButton.setOnClickListener(view -> finishWithResult(RESULT_FINISHED, true));

        mFaceManager = getSystemService(FaceManager.class);
        mHardwareAuthToken = getIntent().getByteArrayExtra(EXTRA_HARDWARE_AUTH_TOKEN);
        mUserId = getIntent().getIntExtra(Intent.EXTRA_USER_ID, UserHandle.myUserId());

        if (mFaceManager == null) {
            showFatalError(getText(R.string.enroll_service_unavailable), RESULT_CANCELED);
            return;
        }
        if (mHardwareAuthToken == null) {
            showFatalError(getText(R.string.enroll_missing_token), RESULT_CANCELED);
            return;
        }

        mPreviewView.setSurfaceTextureListener(this);
        if (mPreviewView.isAvailable()) {
            startEnrollment(mPreviewView.getSurfaceTexture());
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        startEnrollment(surfaceTexture);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releasePreviewSurface();
        if (mEnrolling && !mResultCommitted) {
            showFatalError(getText(R.string.enroll_surface_lost), RESULT_TIMEOUT);
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}

    @Override
    protected void onStop() {
        if (!isChangingConfigurations() && !isFinishing() && mEnrolling) {
            finishWithResult(RESULT_TIMEOUT, false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelEnrollment();
        releasePreviewSurface();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishWithResult(RESULT_CANCELED, false);
    }

    private void startEnrollment(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null || mEnrolling || mEnrollmentComplete || mResultCommitted
                || mErrorShown) {
            return;
        }

        mPreviewSurface = new Surface(surfaceTexture);
        if (!mPreviewSurface.isValid()) {
            showFatalError(getText(R.string.enroll_surface_lost), RESULT_TIMEOUT);
            return;
        }

        int[] disabledFeatures = getDisabledFeatures(getIntent());
        FaceEnrollOptions options = getFaceEnrollOptions(getIntent());
        mCancellationSignal = new CancellationSignal();
        mEnrolling = true;
        mStatusView.setText(R.string.enroll_scanning);
        Log.i(TAG, "start surfaceValid=true user=" + mUserId
                + " disabledFeatures=" + disabledFeatures.length
                + " enrollReason=" + options.getEnrollReason());

        try {
            mFaceManager.enroll(mUserId, mHardwareAuthToken, mCancellationSignal,
                    mEnrollmentCallback, disabledFeatures, mPreviewSurface,
                    false /* debugConsent */, options);
        } catch (RuntimeException e) {
            mEnrolling = false;
            Log.e(TAG, "unable to start enrollment", e);
            showFatalError(getText(R.string.enroll_service_unavailable), RESULT_FINISHED);
        }
    }

    private void onEnrollmentComplete() {
        mEnrolling = false;
        mEnrollmentComplete = true;
        mStatusView.setText(R.string.enroll_complete);
        mSkipButton.setVisibility(View.GONE);
        mDoneButton.setVisibility(View.VISIBLE);
        Log.i(TAG, "complete remaining=0");
    }

    private void showFatalError(CharSequence message, int resultCode) {
        if (mErrorShown || mResultCommitted) {
            return;
        }
        mErrorShown = true;
        cancelEnrollment();
        new AlertDialog.Builder(this)
                .setTitle(R.string.enroll_failed_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.enroll_ok,
                        (dialog, which) -> finishWithResult(resultCode, false))
                .show();
    }

    private void finishWithResult(int resultCode, boolean enrolled) {
        if (mResultCommitted) {
            return;
        }
        mResultCommitted = true;
        cancelEnrollment();
        Intent result = new Intent();
        result.putExtra(EXTRA_FINISHED_ENROLL_FACE, enrolled);
        setResult(resultCode, result);
        finish();
    }

    private void cancelEnrollment() {
        mEnrolling = false;
        if (mCancellationSignal != null && !mCancellationSignal.isCanceled()) {
            mCancellationSignal.cancel();
        }
        mCancellationSignal = null;
    }

    private void releasePreviewSurface() {
        if (mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
    }

    private static int[] getDisabledFeatures(Intent intent) {
        ArrayList<Integer> disabled = new ArrayList<>();
        if (!intent.getBooleanExtra(EXTRA_REQUIRE_DIVERSITY, true)) {
            disabled.add(FEATURE_REQUIRE_REQUIRE_DIVERSITY);
        }
        if (!intent.getBooleanExtra(EXTRA_REQUIRE_VISION, true)) {
            disabled.add(FEATURE_REQUIRE_ATTENTION);
        }
        int[] result = new int[disabled.size()];
        for (int i = 0; i < disabled.size(); i++) {
            result[i] = disabled.get(i);
        }
        return result;
    }

    private static FaceEnrollOptions getFaceEnrollOptions(Intent intent) {
        int reason = intent.getIntExtra(BiometricManager.EXTRA_ENROLL_REASON,
                FaceEnrollOptions.ENROLL_REASON_UNKNOWN);
        switch (reason) {
            case FaceEnrollOptions.ENROLL_REASON_RE_ENROLL_NOTIFICATION:
            case FaceEnrollOptions.ENROLL_REASON_SETTINGS:
            case FaceEnrollOptions.ENROLL_REASON_SUW:
                break;
            default:
                reason = FaceEnrollOptions.ENROLL_REASON_UNKNOWN;
        }
        return new FaceEnrollOptions.Builder().setEnrollReason(reason).build();
    }
}
