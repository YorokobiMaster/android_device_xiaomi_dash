/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import java.util.Locale;

public class EqualizerPreference extends Preference {

    public interface OnGainsChangedListener {
        void onGainsChanged(int[] gains, boolean finished);
    }

    private int[] mGains = new int[DolbyController.GRAPHIC_EQUALIZER_CONTROL_COUNT];
    private OnGainsChangedListener mListener;
    private EqualizerView mEqualizerView;
    private TextView mSelectedBand;

    public EqualizerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_equalizer);
        setSelectable(false);
        setPersistent(false);
    }

    public void setGains(int[] gains) {
        mGains = gains.clone();
        if (mEqualizerView != null) {
            mEqualizerView.setGains(mGains);
            updateSelectedBand(mEqualizerView.getSelectedBand(),
                    mGains[mEqualizerView.getSelectedBand()]);
        }
    }

    public void setOnGainsChangedListener(OnGainsChangedListener listener) {
        mListener = listener;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mSelectedBand = (TextView) holder.findViewById(R.id.equalizer_selected_band);
        mEqualizerView = (EqualizerView) holder.findViewById(R.id.equalizer_view);
        mEqualizerView.setGains(mGains);
        mEqualizerView.setOnBandChangeListener((band, gain, finished) -> {
            mGains[band] = gain;
            updateSelectedBand(band, gain);
            if (mListener != null) {
                mListener.onGainsChanged(mGains.clone(), finished);
            }
        });
        updateSelectedBand(mEqualizerView.getSelectedBand(),
                mGains[mEqualizerView.getSelectedBand()]);
    }

    private void updateSelectedBand(int band, int quarterDb) {
        if (mSelectedBand == null) return;
        mSelectedBand.setText(getContext().getString(R.string.equalizer_selected_band,
                EqualizerView.formatFrequency(getContext(), band), formatGain(quarterDb)));
    }

    private static String formatGain(int quarterDb) {
        float gain = quarterDb / 4f;
        if (quarterDb % 4 == 0) {
            return String.format(Locale.getDefault(), "%+.0f dB", gain);
        }
        if (quarterDb % 2 == 0) {
            return String.format(Locale.getDefault(), "%+.1f dB", gain);
        }
        return String.format(Locale.getDefault(), "%+.2f dB", gain);
    }
}
