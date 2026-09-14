// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

/** One installed application row in the main list. */
final class AppEntry {

    final String packageName;
    final String label;

    /** Override from the getState() overrides bundle; -1 means automatic. */
    int overrideProfile = -1;

    /** Lazily loaded through getAppPolicy(); null until loaded. */
    String stockGroup;
    boolean policyRequested;

    AppEntry(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }
}
