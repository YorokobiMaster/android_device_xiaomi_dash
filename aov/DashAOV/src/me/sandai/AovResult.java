/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai;

import android.os.Parcel;
import android.os.Parcelable;

/** Read-only MediaTek AovResult parcel. */
final class AovResult implements Parcelable {
    static final Creator<AovResult> CREATOR = new Creator<>() {
        @Override
        public AovResult createFromParcel(Parcel source) {
            AovResult result = new AovResult();
            result.readFromParcel(source);
            return result;
        }

        @Override
        public AovResult[] newArray(int size) {
            return new AovResult[size];
        }
    };

    String key;
    AovResultData data;

    @Override
    public int describeContents() {
        return data == null ? 0 : data.describeContents();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new UnsupportedOperationException("AOV results are read-only");
    }

    private void readFromParcel(Parcel source) {
        int start = source.dataPosition();
        int size = source.readInt();
        if (size < 4) {
            throw new android.os.BadParcelableException("Parcelable too small");
        }
        try {
            if (AovInitParams.hasMore(source, start, size)) key = source.readString();
            if (AovInitParams.hasMore(source, start, size)) {
                data = source.readTypedObject(AovResultData.CREATOR);
            }
        } finally {
            AovInitParams.finish(source, start, size);
        }
    }
}
