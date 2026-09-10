/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.util.UUID;

/**
 * Client for the Dolby DAP audio effect shipped in the stock vendor
 * (libswdapaidl.so, implementation UUID 9d4921da-8225-4f29-aefa-39537a04bcaa).
 *
 * Wire protocol recovered from stock MiSound (com.miui.misound): raw
 * AudioEffect parameters, little-endian int32 payloads.
 */
public class DolbyAudioEffect extends AudioEffect {

    private static final String TAG = "DashDolby";

    private static final UUID EFFECT_TYPE_DAP =
            UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");

    private static final int OPERATION = 5;

    // Global parameters.
    private static final int PARAM_DS_ON = 0x00000000;
    private static final int PARAM_PROFILE_COUNT = 0x03000000;
    private static final int PARAM_COMMIT = 0x09000000;
    private static final int PARAM_ACTIVE_PROFILE = 0x0a000000;
    private static final int PARAM_PROFILE_SETTINGS = 0x01000000;

    // Per-profile parameters.
    public static final int DAP_PARAM_HEADPHONE_VIRTUALIZER = 101;
    public static final int DAP_PARAM_VOLUME_LEVELER = 103;
    public static final int DAP_PARAM_IEQ_PRESET = 104;
    public static final int DAP_PARAM_DIALOG_ENHANCER = 105;
    public static final int DAP_PARAM_GRAPHIC_EQUALIZER = 106;
    public static final int DAP_PARAM_GRAPHIC_EQUALIZER_GAINS = 110;

    public DolbyAudioEffect() {
        super(EFFECT_TYPE_NULL, EFFECT_TYPE_DAP, 0, 0);
    }

    private static int bytesToInt(byte[] buf, int off) {
        return (buf[off] & 0xff) | ((buf[off + 1] & 0xff) << 8)
                | ((buf[off + 2] & 0xff) << 16) | ((buf[off + 3] & 0xff) << 24);
    }

    private static int intToBytes(int value, byte[] buf, int off) {
        buf[off] = (byte) (value & 0xff);
        buf[off + 1] = (byte) ((value >>> 8) & 0xff);
        buf[off + 2] = (byte) ((value >>> 16) & 0xff);
        buf[off + 3] = (byte) ((value >>> 24) & 0xff);
        return 4;
    }

    private int getIntParam(int param) {
        byte[] buf = new byte[12];
        intToBytes(param, buf, 0);
        checkStatus(getParameter(param + OPERATION, buf));
        return bytesToInt(buf, 0);
    }

    private void setIntParam(int param, int value) {
        byte[] buf = new byte[12];
        int off = intToBytes(param, buf, 0);
        off += intToBytes(1, buf, off);
        intToBytes(value, buf, off);
        checkStatus(setParameter(OPERATION, buf));
    }

    public void setDsOn(boolean on) {
        setIntParam(PARAM_DS_ON, on ? 1 : 0);
        setEnabled(on);
    }

    public int getProfileCount() {
        return getIntParam(PARAM_PROFILE_COUNT);
    }

    public void setActiveProfile(int profile) {
        setIntParam(PARAM_ACTIVE_PROFILE, profile);
    }

    public void setProfileParameter(int profile, int param, int[] values) {
        byte[] buf = new byte[(values.length + 4) * 4];
        int off = intToBytes(PARAM_PROFILE_SETTINGS, buf, 0);
        off += intToBytes(values.length + 1, buf, off);
        off += intToBytes(profile, buf, off);
        off += intToBytes(param, buf, off);
        for (int value : values) {
            off += intToBytes(value, buf, off);
        }
        checkStatus(setParameter(OPERATION, buf));
    }

    public void setProfileParameter(int profile, int param, int value) {
        setProfileParameter(profile, param, new int[]{value});
    }

    /** Persists the current settings into the vendor-side DAP database. */
    public void commit() {
        checkStatus(setParameter(OPERATION, PARAM_COMMIT));
    }

    @Override
    public boolean hasControl() {
        try {
            return super.hasControl();
        } catch (IllegalStateException e) {
            Log.e(TAG, "hasControl: " + e);
            return false;
        }
    }
}
