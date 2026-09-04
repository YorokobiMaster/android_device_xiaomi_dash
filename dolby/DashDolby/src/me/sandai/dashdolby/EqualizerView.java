/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

public class EqualizerView extends View {

    public interface OnBandChangeListener {
        void onBandChanged(int band, int gain, boolean finished);
    }

    private static final int[] BAND_FREQUENCIES = {
            47, 234, 469, 844, 1313, 2250, 3750, 5813, 9000, 13875
    };

    private final Paint mRailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;

    private int[] mGains = new int[DolbyController.GRAPHIC_EQUALIZER_CONTROL_COUNT];
    private int mSelectedBand = 5;
    private OnBandChangeListener mListener;

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;

        int accent = resolveColor(android.R.attr.colorAccent);
        int secondary = resolveColor(android.R.attr.textColorSecondary);
        int surface = resolveColor(android.R.attr.colorBackgroundFloating);
        mRailPaint.setColor(withAlpha(secondary, 34));
        mGainPaint.setColor(accent);
        mCenterPaint.setColor(withAlpha(secondary, 100));
        mCenterPaint.setStrokeWidth(dp(1));
        mThumbPaint.setColor(surface);
        mThumbPaint.setStyle(Paint.Style.FILL);
        mThumbPaint.setShadowLayer(dp(2), 0, dp(1), withAlpha(secondary, 110));
        mTextPaint.setColor(secondary);
        mTextPaint.setTextSize(sp(10));
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(true);
        setContentDescription(getResources().getString(R.string.equalizer_view_accessibility));
    }

    public void setGains(int[] gains) {
        if (gains.length != BAND_FREQUENCIES.length) {
            throw new IllegalArgumentException("Expected " + BAND_FREQUENCIES.length
                    + " controls");
        }
        mGains = gains.clone();
        invalidate();
    }

    public int getSelectedBand() {
        return mSelectedBand;
    }

    public void setOnBandChangeListener(OnBandChangeListener listener) {
        mListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft() + dp(32);
        float right = getWidth() - getPaddingRight() - dp(6);
        float top = getPaddingTop() + dp(12);
        float bottom = getHeight() - getPaddingBottom() - dp(28);
        float center = (top + bottom) / 2f;
        float slot = (right - left) / BAND_FREQUENCIES.length;
        float railWidth = Math.min(dp(12), slot * 0.42f);
        float radius = railWidth / 2f;

        drawScale(canvas, left - dp(8), top, "+6");
        drawScale(canvas, left - dp(8), (top + center) / 2f, "+3");
        drawScale(canvas, left - dp(8), center, "0");
        drawScale(canvas, left - dp(8), (center + bottom) / 2f, "−3");
        drawScale(canvas, left - dp(8), bottom, "−6");
        canvas.drawLine(left, center, right, center, mCenterPaint);

        for (int i = 0; i < mGains.length; i++) {
            float x = left + slot * (i + 0.5f);
            float y = gainY(mGains[i], top, bottom);
            canvas.drawRoundRect(x - railWidth / 2f, top, x + railWidth / 2f, bottom,
                    radius, radius, mRailPaint);
            if (y != center) {
                canvas.drawRoundRect(x - railWidth / 2f, Math.min(y, center),
                        x + railWidth / 2f, Math.max(y, center), radius, radius, mGainPaint);
            }
            if (i == mSelectedBand) {
                canvas.drawCircle(x, y, dp(7), mGainPaint);
                canvas.drawCircle(x, y, dp(4), mThumbPaint);
            } else {
                canvas.drawCircle(x, y, dp(4), mThumbPaint);
            }
            canvas.drawText(shortFrequency(BAND_FREQUENCIES[i]), x,
                    getHeight() - getPaddingBottom() - dp(7), mTextPaint);
        }
    }

    private void drawScale(Canvas canvas, float x, float y, String label) {
        mTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(label, x, y + dp(3), mTextPaint);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                requestFocus();
                mSelectedBand = bandFromX(event.getX());
                updateGainFromY(event.getY(), false);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateGainFromY(event.getY(), false);
                return true;
            case MotionEvent.ACTION_UP:
                updateGainFromY(event.getY(), true);
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                notifyBandChanged(true);
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                selectBand(Math.max(0, mSelectedBand - 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                selectBand(Math.min(mGains.length - 1, mSelectedBand + 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                setSelectedGain(mGains[mSelectedBand] + 2, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                setSelectedGain(mGains[mSelectedBand] - 2, true);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, -6f, 6f,
                mGains[mSelectedBand] / 4f));
        info.setText(selectionDescription());
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            setSelectedGain(mGains[mSelectedBand] + 2, true);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            setSelectedGain(mGains[mSelectedBand] - 2, true);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private int bandFromX(float touchX) {
        float left = getPaddingLeft() + dp(32);
        float right = getWidth() - getPaddingRight() - dp(6);
        float slot = (right - left) / BAND_FREQUENCIES.length;
        int band = (int) ((touchX - left) / slot);
        return Math.max(0, Math.min(mGains.length - 1, band));
    }

    private void updateGainFromY(float touchY, boolean finished) {
        float top = getPaddingTop() + dp(12);
        float bottom = getHeight() - getPaddingBottom() - dp(28);
        int halfDbSteps = Math.round((bottom - touchY) * 24f / (bottom - top) - 12f);
        setSelectedGain(halfDbSteps * 2, finished);
    }

    private void setSelectedGain(int gain, boolean finished) {
        int clamped = Math.max(DolbyController.GRAPHIC_EQUALIZER_MIN_QUARTER_DB,
                Math.min(DolbyController.GRAPHIC_EQUALIZER_MAX_QUARTER_DB, gain));
        boolean changed = mGains[mSelectedBand] != clamped;
        mGains[mSelectedBand] = clamped;
        if (changed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
        notifyBandChanged(finished);
    }

    private void selectBand(int band) {
        mSelectedBand = band;
        invalidate();
        notifyBandChanged(false);
        setContentDescription(selectionDescription());
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
    }

    private void notifyBandChanged(boolean finished) {
        if (mListener != null) {
            mListener.onBandChanged(mSelectedBand, mGains[mSelectedBand], finished);
        }
        if (finished) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
    }

    private CharSequence selectionDescription() {
        return getResources().getString(R.string.equalizer_selected_band,
                formatFrequency(getContext(), mSelectedBand),
                String.format(Locale.getDefault(), "%+.2f dB", mGains[mSelectedBand] / 4f));
    }

    public static String formatFrequency(Context context, int band) {
        int frequency = BAND_FREQUENCIES[band];
        if (frequency < 1000) {
            return context.getString(R.string.equalizer_frequency_hz, frequency);
        }
        return context.getString(R.string.equalizer_frequency_khz, frequency / 1000f);
    }

    private static String shortFrequency(int frequency) {
        if (frequency < 1000) return String.valueOf(frequency);
        float khz = frequency / 1000f;
        return khz < 10f ? String.format(Locale.US, "%.1fk", khz)
                : String.format(Locale.US, "%.0fk", khz);
    }

    private float gainY(int quarterDb, float top, float bottom) {
        return bottom - (quarterDb + 24f) * (bottom - top) / 48f;
    }

    private int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        getContext().getTheme().resolveAttribute(attr, value, true);
        return value.resourceId != 0 ? getContext().getColor(value.resourceId) : value.data;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    private float dp(float value) {
        return value * mDensity;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
