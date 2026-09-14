#!/usr/bin/env python3
# Copyright (C) 2026 GitHub @YorokobiMaster
# SPDX-License-Identifier: Apache-2.0
"""Run after committing: python3 device/xiaomi/dash/display/brightness/tests/verify_resources.py."""
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    device = Path(__file__).resolve().parents[3]
    overlay = device / "rro/DashFrameworkResOverlay"
    resources = {}
    for path in (overlay / "res/values").glob("*.xml"):
        for node in ET.parse(path).getroot():
            name = node.get("name")
            assert name not in resources, f"Duplicate resource: {name}"
            resources[name] = node
    lux = [0.0] + [float(n.text) for n in resources["config_autoBrightnessLevels"]]
    nits = [float(n.text) for n in resources["config_autoBrightnessDisplayValuesNits"]]
    assert len(lux) == len(nits) == 129
    assert all(a < b for a, b in zip(lux, lux[1:]))
    assert all(a <= b for a, b in zip(nits, nits[1:]))
    assert (lux[0], lux[-1], nits[0], nits[-1]) == (0, 100000, 3.5, 3500)
    for name, impl in (("config_deviceAmbientPolicy", "DashAmbientPolicy"),
                       ("config_deviceBrightnessPolicy", "DashBrightnessPolicy")):
        assert resources[name].text == f"me.sandai.server.display.{impl}"
    assert resources["config_displayLightSensorType"].text == "android.sensor.light"
    assert "config_autoBrightnessLcdBacklightValues" not in resources
    android = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(overlay / "AndroidManifest.xml").find("overlay")
    assert manifest.get(android + "targetPackage") == "android"
    assert manifest.get(android + "isStatic") == "true"
    assert manifest.get(android + "requiredSystemPropertyName") is None
    assert "product_specific: true" in (overlay / "Android.bp").read_text()
    config = device / "display/brightness/config"
    ddc = ET.parse(config / "display_id_4627039422300187648.xml")
    assert ddc.find(".//luxToBrightnessMapping") is None
    points = [(float(p.findtext("value")), float(p.findtext("nits")))
              for p in ddc.findall("screenBrightnessMap/point")]
    assert points == [(0.000854597, 2), (0.499939, 600), (0.593761, 800),
                      (0.836223, 2000), (1, 3500)]
    assert ddc.findtext("ambientLightHorizonLong") == "1500"
    assert ddc.findtext("ambientLightHorizonShort") == "1000"
    assert ddc.findtext("screenBrightnessRampFastIncrease") == "0.3010011"
    assert ddc.findtext("screenBrightnessRampSlowDecrease") == "0.24081309"
    thermal = ET.parse(config / "multi_factor_thermal_brightness_control.xml")
    assert thermal.getroot().tag == "thermal-brightness-config"
    assert thermal.find("thermal-condition-item[identifier='0']") is not None
    print("Brightness resource consistency passed (source only; not runtime overlay activation).")


if __name__ == "__main__":
    main()
