// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package vendor.mediatek.hardware.mtkpower;

// Client-only subset of the retained OS3.0.305.0 V3 interface. Explicit IDs
// verified against its BpMtkPowerService; not a full frozen interface or server.
interface IMtkPowerService {
    int perfCusLockHint(int hint, int durationMs, int pid) = 0;
    int perfLockReleaseSync(int handle, int reserved) = 3;
}
