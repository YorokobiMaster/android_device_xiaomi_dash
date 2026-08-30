// Copyright (C) 2026 @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai.dashaov;

import me.sandai.dashaov.IDashAovCallback;

interface IDashAovBridge {
    void start(IDashAovCallback callback);
    void stop();
}
