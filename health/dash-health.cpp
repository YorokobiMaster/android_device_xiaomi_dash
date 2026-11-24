/*
 * Copyright (C) 2026 YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "ChargingControlService.h"

int main() {
  return dash::runChargingControlService("vendor.system.health.IChargingControl/default");
}
