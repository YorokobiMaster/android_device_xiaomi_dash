// Copyright (C) 2026 @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

package me.sandai;

import me.sandai.IDashAovCallback;

interface IDashAovBridge {
    void start(IDashAovCallback callback);
    void stop();
}
