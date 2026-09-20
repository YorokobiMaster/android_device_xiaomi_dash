/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

/** Pure thermal cap arithmetic. The observer owns the current values and retains them when a
 * node read fails; this layer has no freshness lease or retained-cap state of its own.
 */
final class ThermalCaps {
    record Inputs(ThermalBrightnessTable table, float temperature, float safety,
            Integer condition) {
        static final Inputs EMPTY = new Inputs(null, Float.NaN, Float.NaN, null);
    }

    static float resolve(Inputs inputs, float lux) {
        float table = Float.NaN;
        if (inputs.table() != null) {
            int condition = inputs.condition() == null ? 0 : inputs.condition();
            table = inputs.table().cap(condition, lux, inputs.temperature());
        }
        // The observer represents a valid zero safety value as NaN, which clears the cap.
        float safety = Float.isFinite(inputs.safety()) && inputs.safety() > 0
                ? inputs.safety() : Float.NaN;
        // Stock derives the table/NTC restriction first; safety can tighten it but cannot create
        // a restriction when the table has no value.
        if (Float.isNaN(table)) return Float.NaN;
        return Float.isNaN(safety) ? table : Math.min(table, safety);
    }

    /** Stock skin-temperature hysteresis: equal, NaN-pair, equal truncated integer, or <0.5 C. */
    static boolean equivalentTemperature(float a, float b) {
        if (a == b) return true;
        if (Float.isNaN(a) || Float.isNaN(b)) return Float.isNaN(a) && Float.isNaN(b);
        return ((int) a) == ((int) b) || Math.abs(a - b) < 0.5f;
    }

    /** Effective cap equality treats two unavailable (NaN) values as unchanged. */
    static boolean sameCap(float a, float b) {
        return a == b || (Float.isNaN(a) && Float.isNaN(b));
    }
}
