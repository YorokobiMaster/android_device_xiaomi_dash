// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashwake.aov;

import me.sandai.dashwake.aov.IDashAovCallback;

interface IDashAovBridge {
    void start(IDashAovCallback callback);
    void stop();
}
