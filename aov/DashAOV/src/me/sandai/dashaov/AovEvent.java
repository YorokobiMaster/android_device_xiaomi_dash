/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashaov;

import android.os.Parcel;
import android.os.Parcelable;

/** Read-only MediaTek AovEvent parcel. */
final class AovEvent implements Parcelable {
    static final Creator<AovEvent> CREATOR = new Creator<>() {
        @Override
        public AovEvent createFromParcel(Parcel source) {
            AovEvent event = new AovEvent();
            event.readFromParcel(source);
            return event;
        }

        @Override
        public AovEvent[] newArray(int size) {
            return new AovEvent[size];
        }
    };

    AovResult[] results;

    @Override
    public int describeContents() {
        int contents = 0;
        if (results != null) {
            for (AovResult result : results) {
                if (result != null) contents |= result.describeContents();
            }
        }
        return contents;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new UnsupportedOperationException("AOV events are read-only");
    }

    private void readFromParcel(Parcel source) {
        int start = source.dataPosition();
        int size = source.readInt();
        if (size < 4) {
            throw new android.os.BadParcelableException("Parcelable too small");
        }
        try {
            if (AovInitParams.hasMore(source, start, size)) {
                results = source.createTypedArray(AovResult.CREATOR);
            }
        } finally {
            AovInitParams.finish(source, start, size);
        }
    }
}
