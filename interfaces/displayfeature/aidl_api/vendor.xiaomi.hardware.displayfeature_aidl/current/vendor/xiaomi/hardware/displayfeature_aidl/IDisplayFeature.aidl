/*
 * Copyright (C) 2026 @YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */
///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a snapshot of an AIDL file. Do not edit it manually. There are
// two cases:
// 1). this is a frozen version file - do not edit this in any case.
// 2). this is a 'current' file. If you make a backwards compatible change to
//     the interface (from the latest frozen version), the build system will
//     prompt you to update this file with `m <name>-update-api`.
//
// You must not make a backward incompatible change to any AIDL file built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

package vendor.xiaomi.hardware.displayfeature_aidl;
@VintfStability
interface IDisplayFeature {
  void notifyBrightness(int brightness);
  void registerCallback(int displayId, in vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeatureCallback callback);
  void sendMessage(int displayId, int msgId, String msg);
  void sendPanelCommand(String cmd);
  void sendPostProcCommand(int displayId, int cmd);
  void sendRefreshCommand();
  void setFeature(int displayId, int mode, int value, int cookie);
  void setFunction(int displayId, int functionId, int value, int cookie);
  void sendGamePkgName(int displayId, int type, int value, String pkgName);
  void unregisterCallback(int displayId, in vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeatureCallback callback);
}
