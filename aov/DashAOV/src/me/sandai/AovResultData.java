/*
 * Copyright (C) 2026 @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

/** Read-only representation of MediaTek AovResultData's stable AIDL union. */
final class AovResultData implements Parcelable {
    static final int TAG_VEC_BYTE = 1;
    static final int TAG_INT = 2;

    static final Creator<AovResultData> CREATOR = new Creator<>() {
        @Override
        public AovResultData createFromParcel(Parcel source) {
            return new AovResultData(source);
        }

        @Override
        public AovResultData[] newArray(int size) {
            return new AovResultData[size];
        }
    };

    private int mTag;
    private Object mValue;

    private AovResultData(Parcel source) {
        readFromParcel(source);
    }

    int getTag() {
        return mTag;
    }

    byte[] getByteVector() {
        return mTag == TAG_VEC_BYTE ? (byte[]) mValue : null;
    }

    int getInt() {
        if (mTag != TAG_INT) {
            throw new IllegalStateException("AOV union tag is " + mTag + ", not int");
        }
        return (Integer) mValue;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new UnsupportedOperationException("AOV result data is read-only");
    }

    private void readFromParcel(Parcel source) {
        mTag = source.readInt();
        switch (mTag) {
            case 0 -> mValue = source.readByte();
            case 1 -> mValue = source.createByteArray();
            case 2 -> mValue = source.readInt();
            case 3 -> mValue = source.createIntArray();
            case 4 -> mValue = source.readString();
            case 5 -> mValue = source.readBoolean();
            case 6 -> mValue = source.createBooleanArray();
            case 7 -> mValue = source.readLong();
            case 8 -> mValue = source.createLongArray();
            case 9 -> mValue = source.readFloat();
            case 10 -> mValue = source.createFloatArray();
            case 11 -> mValue = source.readDouble();
            case 12 -> mValue = source.createDoubleArray();
            default -> throw new BadParcelableException("Unknown AOV union tag " + mTag);
        }
    }
}
