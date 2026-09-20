/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import android.util.Spline;

import com.android.server.display.DeviceBrightnessCurvePolicy;

import java.util.ArrayList;
import java.util.List;

/** Stock dash five-region user-point curve shaping in physical-nits space. */
public final class DashBrightnessCurvePolicy implements DeviceBrightnessCurvePolicy {
    private static final float[] INTERVALS = {0.0f, 30.0f, 300.0f, 3000.0f, 20000.0f};
    private static final float MIN_TANGENT = 0.0323f;
    private static final float DARK_ANCHOR_LUX = 600.0f;
    private static final float DARK_PULL_UP_LIMIT_NITS = 106.0f;
    private static final float DARK_PULL_UP_MAX_TANGENT = 1.49266f;
    private static final float INDOOR_TANGENT = 0.0754386f;
    private static final float SECOND_OUTDOOR_MIN_TANGENT = 0.01f;
    private static final float DARK_CONNECT_RATIO = 0.7f;

    private float[] mLux;
    private int mUserIndex;
    private Spline mDefaultNits;
    private Spline mNitsToBrightness;
    private Spline mBrightnessToNits;
    private float mMinimumNits;
    private float mMaximumLux;

    public DashBrightnessCurvePolicy() {}

    @Override
    public boolean smoothCurve(float[] lux, float[] brightness, int index, float[] defaultLux,
            float[] defaultNits, Spline nitsToBrightness, Spline brightnessToNits) {
        if (!validInputs(lux, brightness, index, defaultLux, defaultNits,
                nitsToBrightness, brightnessToNits)) {
            return false;
        }
        mLux = lux;
        mUserIndex = index;
        mDefaultNits = Spline.createLinearSpline(defaultLux, defaultNits);
        mNitsToBrightness = nitsToBrightness;
        mBrightnessToNits = brightnessToNits;
        mMinimumNits = brightnessToNits.interpolate(0.0f);
        mMaximumLux = defaultLux[defaultLux.length - 1];

        float userLux = lux[index];
        float userNits = brightnessToNits.interpolate(brightness[index]);
        List<Segment> segments = buildCurve(userLux, userNits);
        List<Point> points = flatten(segments);
        if (points.size() < 2) return false;

        float[] shapedLux = new float[points.size()];
        float[] shapedNits = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            shapedLux[i] = points.get(i).lux;
            shapedNits[i] = points.get(i).nits;
            if (i > 0 && shapedLux[i] <= shapedLux[i - 1]) return false;
        }
        Spline result = Spline.createLinearSpline(shapedLux, shapedNits);
        for (int i = 0; i < lux.length; i++) {
            float nits = Math.max(mMinimumNits, result.interpolate(lux[i]));
            brightness[i] = nitsToBrightness.interpolate(nits);
            if (i > 0) brightness[i] = Math.max(brightness[i - 1], brightness[i]);
        }
        return true;
    }

    private static boolean validInputs(float[] lux, float[] brightness, int index,
            float[] defaultLux, float[] defaultNits, Spline nitsToBrightness,
            Spline brightnessToNits) {
        return lux != null && brightness != null && defaultLux != null && defaultNits != null
                && nitsToBrightness != null && brightnessToNits != null
                && lux.length == brightness.length && lux.length >= 2
                && defaultLux.length == defaultNits.length && defaultLux.length >= 2
                && index >= 0 && index < lux.length
                && Float.isFinite(lux[index]) && lux[index] >= INTERVALS[0]
                && Float.isFinite(brightness[index]);
    }

    private List<Segment> buildCurve(float lux, float nits) {
        Segment dark = new DarkSegment();
        Segment indoor = new IndoorSegment();
        Segment firstOutdoor = new FirstOutdoorSegment();
        Segment secondOutdoor = new SecondOutdoorSegment();
        Segment thirdOutdoor = new ThirdOutdoorSegment();
        if (lux >= INTERVALS[0] && lux < INTERVALS[1]) {
            dark.create(lux, nits);
            indoor.connectLeft(dark);
            firstOutdoor.connectLeft(indoor);
            secondOutdoor.connectLeft(firstOutdoor);
            thirdOutdoor.connectLeft(secondOutdoor);
        } else if (lux < INTERVALS[2]) {
            indoor.create(lux, nits);
            dark.connectRight(indoor);
            firstOutdoor.connectLeft(indoor);
            secondOutdoor.connectLeft(firstOutdoor);
            thirdOutdoor.connectLeft(secondOutdoor);
        } else if (lux < INTERVALS[3]) {
            firstOutdoor.create(lux, nits);
            indoor.connectRight(firstOutdoor);
            dark.connectRight(indoor);
            secondOutdoor.connectLeft(firstOutdoor);
            thirdOutdoor.connectLeft(secondOutdoor);
        } else if (lux < INTERVALS[4]) {
            secondOutdoor.create(lux, nits);
            firstOutdoor.connectRight(secondOutdoor);
            indoor.connectRight(firstOutdoor);
            dark.connectRight(indoor);
            thirdOutdoor.connectLeft(secondOutdoor);
        } else {
            thirdOutdoor.create(lux, nits);
            firstOutdoor.connectRight(thirdOutdoor);
            indoor.connectRight(firstOutdoor);
            dark.connectRight(indoor);
        }
        List<Segment> result = new ArrayList<>(5);
        result.add(dark);
        result.add(indoor);
        result.add(firstOutdoor);
        result.add(secondOutdoor);
        result.add(thirdOutdoor);
        return result;
    }

    private static List<Point> flatten(List<Segment> segments) {
        List<Point> result = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.points.isEmpty()) continue;
            int start = result.isEmpty() ? 0 : 1;
            for (int i = start; i < segment.points.size(); i++) {
                Point point = segment.points.get(i);
                if (!result.isEmpty() && point.lux == result.get(result.size() - 1).lux) {
                    // Stock can emit the same boundary twice with the same value.
                    result.set(result.size() - 1, point);
                } else {
                    result.add(point);
                }
            }
        }
        return result;
    }

    private void copyDefault(float startLux, float endLux, List<Point> points, float difference,
            float ratio) {
        for (float lux : mLux) {
            if (lux > endLux) break;
            if (lux >= startLux) {
                float coefficient = 1.0f - ((endLux - lux) * ratio / (endLux - startLux));
                points.add(new Point(lux, mDefaultNits.interpolate(lux) - difference * coefficient));
            }
        }
    }

    private void createPullDown(float lux, float nits, List<Point> points, float headLux,
            float tailLux, float minimumHeadTangent, float minimumTailTangent) {
        float defaultTangent = (mDefaultNits.interpolate(tailLux) - mDefaultNits.interpolate(0.0f))
                / tailLux;
        float headTangent = minimumHeadTangent;
        if (lux != headLux) {
            headTangent = Math.max(minimumHeadTangent,
                    (nits - mDefaultNits.interpolate(headLux)) / (lux - headLux));
        }
        points.add(new Point(headLux, nits - (lux - headLux) * headTangent));
        if (lux != headLux) points.add(new Point(lux, nits));

        float tailTangent = (nits - mDefaultNits.interpolate(0.0f)) / lux;
        if (tailTangent > defaultTangent) {
            points.add(new Point(tailLux, mDefaultNits.interpolate(tailLux)));
            return;
        }
        tailTangent = Math.max(tailTangent, minimumTailTangent);
        points.add(new Point(tailLux, nits + (tailLux - lux) * tailTangent));
    }

    private void createPullUp(float lux, float nits, List<Point> points, float headLux,
            float tailLux, float anchorLux, float anchorNits) {
        float tangent = (nits - anchorNits) / (lux - anchorLux);
        points.add(new Point(headLux, nits - (lux - headLux) * tangent));
        points.add(new Point(tailLux, nits + (tailLux - lux) * tangent));
    }

    private void connectPullUpTail(Point head, float tailLux, float minimumTangent,
            List<Point> points) {
        points.add(head);
        float tangent = minimumTangent;
        if (tailLux != head.lux) {
            tangent = Math.max(minimumTangent,
                    (mDefaultNits.interpolate(tailLux) - head.nits) / (tailLux - head.lux));
        }
        points.add(new Point(tailLux, head.nits + (tailLux - head.lux) * tangent));
    }

    private abstract static class Segment {
        final List<Point> points = new ArrayList<>();
        abstract void create(float lux, float nits);
        abstract void connectLeft(Segment left);
        abstract void connectRight(Segment right);
    }

    private final class DarkSegment extends Segment {
        private final float mHead = INTERVALS[0];
        private final float mTail = INTERVALS[1];

        @Override
        void create(float lux, float nits) {
            if (mDefaultNits.interpolate(lux) > nits) {
                if (lux <= 3.0f) {
                    copyDefault(mHead, mTail, points,
                            mDefaultNits.interpolate(lux) - nits, 0.0f);
                } else {
                    createPullDown(lux, nits, points, mHead, mTail,
                            MIN_TANGENT, MIN_TANGENT);
                }
                return;
            }
            float difference = mDefaultNits.interpolate(lux) - nits;
            float tailNits = mDefaultNits.interpolate(mTail) - difference;
            float tangent = (DARK_PULL_UP_LIMIT_NITS - nits) / (mTail - lux);
            if (tailNits < DARK_PULL_UP_LIMIT_NITS) {
                copyDefault(mHead, mTail, points, difference, 0.0f);
                return;
            }
            tangent = Math.max(tangent, DARK_PULL_UP_MAX_TANGENT);
            points.add(new Point(mHead, nits - (lux - mHead) * tangent));
            points.add(new Point(mTail, nits + (mTail - lux) * tangent));
        }

        @Override void connectLeft(Segment left) {}

        @Override
        void connectRight(Segment right) {
            if (right.points.isEmpty()) return;
            Point tail = right.points.get(0);
            if (tail.nits == mDefaultNits.interpolate(mTail)) {
                copyDefault(mHead, mTail, points, 0.0f, 0.0f);
            } else if (mDefaultNits.interpolate(tail.lux) > tail.nits) {
                create(tail.lux, tail.nits);
            } else {
                copyDefault(mHead, mTail, points,
                        mDefaultNits.interpolate(tail.lux) - tail.nits, DARK_CONNECT_RATIO);
            }
        }
    }

    private final class IndoorSegment extends Segment {
        private final float mHead = INTERVALS[1];
        private final float mTail = INTERVALS[2];

        @Override
        void create(float lux, float nits) {
            float tangent = (mDefaultNits.interpolate(DARK_ANCHOR_LUX) - nits)
                    / (DARK_ANCHOR_LUX - lux);
            tangent = Math.max(tangent, MIN_TANGENT);
            points.add(new Point(mHead, nits - (lux - mHead) * INDOOR_TANGENT));
            points.add(new Point(lux, nits));
            points.add(new Point(mTail, nits + (mTail - lux) * tangent));
        }

        @Override
        void connectLeft(Segment left) {
            if (left.points.isEmpty()) return;
            Point head = left.points.get(left.points.size() - 1);
            if (head.nits == mDefaultNits.interpolate(mHead)) {
                copyDefault(mHead, mTail, points, 0.0f, 0.0f);
            } else if (mDefaultNits.interpolate(head.lux) > head.nits) {
                points.add(head);
                float tangent = (mDefaultNits.interpolate(DARK_ANCHOR_LUX) - head.nits)
                        / (DARK_ANCHOR_LUX - head.lux);
                points.add(new Point(mTail, head.nits + (mTail - head.lux) * tangent));
            } else {
                points.add(head);
                float tangent = Math.max(MIN_TANGENT,
                        (mDefaultNits.interpolate(DARK_ANCHOR_LUX) - head.nits)
                                / (DARK_ANCHOR_LUX - head.lux));
                points.add(new Point(mTail, head.nits + (mTail - head.lux) * tangent));
            }
        }

        @Override
        void connectRight(Segment right) {
            if (right.points.isEmpty()) return;
            Point tail = right.points.get(0);
            if (tail.nits == mDefaultNits.interpolate(mTail)) {
                copyDefault(mHead, mTail, points, 0.0f, 0.0f);
            } else if (mDefaultNits.interpolate(tail.lux) > tail.nits) {
                float tangent = Math.max(MIN_TANGENT,
                        (tail.nits - mDefaultNits.interpolate(mHead)) / (mTail - mHead));
                points.add(new Point(mHead, tail.nits - (tail.lux - mHead) * tangent));
                points.add(tail);
            } else {
                points.add(new Point(mHead, mDefaultNits.interpolate(mHead)));
                points.add(tail);
            }
        }
    }

    private final class FirstOutdoorSegment extends Segment {
        private final float mHead = INTERVALS[2];
        private final float mTail = INTERVALS[3];
        private final float mAnchorNits;

        FirstOutdoorSegment() {
            float tangent = (mDefaultNits.interpolate(mTail) - mDefaultNits.interpolate(mHead))
                    / (mTail - mHead);
            mAnchorNits = mDefaultNits.interpolate(mTail) - (mTail - INTERVALS[1]) * tangent;
        }

        @Override
        void create(float lux, float nits) {
            if (mDefaultNits.interpolate(lux) > nits) {
                createPullDown(lux, nits, points, mHead, mTail,
                        MIN_TANGENT, MIN_TANGENT);
            } else {
                createPullUp(lux, nits, points, mHead, mTail,
                        INTERVALS[1], mAnchorNits);
            }
        }

        @Override
        void connectLeft(Segment left) {
            if (left.points.isEmpty()) return;
            Point head = left.points.get(left.points.size() - 1);
            if (head.nits == mDefaultNits.interpolate(mHead)) {
                copyDefault(mHead, mTail, points, 0.0f, 0.0f);
            } else if (mDefaultNits.interpolate(head.lux) > head.nits) {
                points.add(head);
                copyDefault(DARK_ANCHOR_LUX, mTail, points, 0.0f, 0.0f);
            } else {
                points.add(head);
                float tangent = (mDefaultNits.interpolate(DARK_ANCHOR_LUX) - head.nits)
                        / (DARK_ANCHOR_LUX - head.lux);
                if (tangent > MIN_TANGENT) {
                    copyDefault(DARK_ANCHOR_LUX, mTail, points, 0.0f, 0.0f);
                } else {
                    Point anchor = new Point(DARK_ANCHOR_LUX,
                            head.nits + (DARK_ANCHOR_LUX - head.lux) * MIN_TANGENT);
                    connectPullUpTail(anchor, mTail, MIN_TANGENT, points);
                }
            }
        }

        @Override
        void connectRight(Segment right) {
            if (right.points.isEmpty()) return;
            Point tail = right.points.get(0);
            if (tail.nits == mDefaultNits.interpolate(mTail)) {
                copyDefault(mHead, mTail, points, 0.0f, 0.0f);
            } else {
                create(tail.lux, tail.nits);
            }
        }
    }

    private final class SecondOutdoorSegment extends Segment {
        private final float mHead = INTERVALS[3];
        private final float mTail = INTERVALS[4];
        private final float mAnchorNits;

        SecondOutdoorSegment() {
            float tangent = (mDefaultNits.interpolate(mTail) - mDefaultNits.interpolate(mHead))
                    / (mTail - mHead);
            mAnchorNits = mDefaultNits.interpolate(mTail) - (mTail - INTERVALS[1]) * tangent;
        }

        @Override
        void create(float lux, float nits) {
            if (mDefaultNits.interpolate(lux) > nits) {
                createPullDown(lux, nits, points, mHead, mTail,
                        SECOND_OUTDOOR_MIN_TANGENT, SECOND_OUTDOOR_MIN_TANGENT);
            } else {
                createPullUp(lux, nits, points, mHead, mTail,
                        INTERVALS[1], mAnchorNits);
            }
        }

        @Override
        void connectLeft(Segment left) {
            if (left.points.isEmpty()) return;
            Point head = left.points.get(left.points.size() - 1);
            if (head.nits == mDefaultNits.interpolate(mHead)) {
                copyDefault(mHead, mTail, points, 0.0f, 0.0f);
            } else if (mDefaultNits.interpolate(head.lux) > head.nits) {
                create(head.lux, head.nits);
            } else {
                connectPullUpTail(head, mTail, SECOND_OUTDOOR_MIN_TANGENT, points);
            }
        }

        @Override
        void connectRight(Segment right) {
            if (right.points.isEmpty()) return;
            Point tail = right.points.get(0);
            if (tail.nits == mDefaultNits.interpolate(mTail)) {
                copyDefault(mHead, mTail, points, 0.0f, 0.0f);
            } else {
                create(tail.lux, tail.nits);
            }
        }
    }

    private final class ThirdOutdoorSegment extends Segment {
        private final float mHead = INTERVALS[3];
        private final float mTail = Math.max(mMaximumLux, mHead + 1.0f);

        @Override
        void create(float lux, float nits) {
            float lastNits = 0.0f;
            for (int i = 0; i < mLux.length; i++) {
                if (mLux[i] < mHead) continue;
                float shaped;
                if (i < mUserIndex) {
                    float ratio = (mHead - mLux[i]) / (mHead - mLux[mUserIndex]);
                    shaped = mDefaultNits.interpolate(mLux[i])
                            + (nits - mDefaultNits.interpolate(mLux[mUserIndex]))
                            * Math.max(0.0f, ratio);
                    shaped = Math.min(nits, shaped);
                } else {
                    shaped = mDefaultNits.interpolate(mLux[i])
                            + nits - mDefaultNits.interpolate(mLux[mUserIndex]);
                }
                lastNits = Math.max(lastNits, shaped);
                points.add(new Point(mLux[i], lastNits));
            }
        }

        @Override
        void connectLeft(Segment left) {
            if (left.points.isEmpty()) return;
            Point head = left.points.get(left.points.size() - 1);
            float lastNits = head.nits;
            for (float lux : mLux) {
                if (lux < head.lux) continue;
                float ratio = (mTail - lux) / (mTail - head.lux);
                float shaped = mDefaultNits.interpolate(lux)
                        + (head.nits - mDefaultNits.interpolate(head.lux))
                        * Math.max(0.0f, ratio);
                lastNits = Math.max(lastNits, shaped);
                points.add(new Point(lux, lastNits));
            }
        }

        @Override void connectRight(Segment right) {}
    }

    private record Point(float lux, float nits) {}
}
