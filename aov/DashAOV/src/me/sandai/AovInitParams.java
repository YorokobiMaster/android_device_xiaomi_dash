/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

/** Exact v3 parcel layout consumed by MediaTek IAovService transaction 6. */
final class AovInitParams implements Parcelable {
    static final Creator<AovInitParams> CREATOR = new Creator<>() {
        @Override
        public AovInitParams createFromParcel(Parcel source) {
            AovInitParams params = new AovInitParams();
            params.readFromParcel(source);
            return params;
        }

        @Override
        public AovInitParams[] newArray(int size) {
            return new AovInitParams[size];
        }
    };

    int sensorId;
    int sensorWidth;
    int sensorHeight;
    int frameRate;
    int detectionMode;
    boolean debugDisableCallback;
    int xmParam1;
    int xmParam2;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        int start = dest.dataPosition();
        dest.writeInt(0);
        dest.writeInt(sensorId);
        dest.writeInt(sensorWidth);
        dest.writeInt(sensorHeight);
        dest.writeInt(frameRate);
        dest.writeInt(detectionMode);
        dest.writeBoolean(debugDisableCallback);
        dest.writeInt(xmParam1);
        dest.writeInt(xmParam2);
        int end = dest.dataPosition();
        dest.setDataPosition(start);
        dest.writeInt(end - start);
        dest.setDataPosition(end);
    }

    private void readFromParcel(Parcel source) {
        int start = source.dataPosition();
        int size = source.readInt();
        if (size < 4) {
            throw new BadParcelableException("Parcelable too small");
        }
        try {
            if (hasMore(source, start, size)) sensorId = source.readInt();
            if (hasMore(source, start, size)) sensorWidth = source.readInt();
            if (hasMore(source, start, size)) sensorHeight = source.readInt();
            if (hasMore(source, start, size)) frameRate = source.readInt();
            if (hasMore(source, start, size)) detectionMode = source.readInt();
            if (hasMore(source, start, size)) debugDisableCallback = source.readBoolean();
            if (hasMore(source, start, size)) xmParam1 = source.readInt();
            if (hasMore(source, start, size)) xmParam2 = source.readInt();
        } finally {
            finish(source, start, size);
        }
    }

    static boolean hasMore(Parcel source, int start, int size) {
        return source.dataPosition() - start < size;
    }

    static void finish(Parcel source, int start, int size) {
        if (start > Integer.MAX_VALUE - size) {
            throw new BadParcelableException("Overflow in parcelable size");
        }
        source.setDataPosition(start + size);
    }
}
