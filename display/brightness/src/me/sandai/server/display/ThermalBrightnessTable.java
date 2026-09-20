/* Copyright (C) 2026 GitHub @YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
package me.sandai.server.display;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

/** Stock half-open lux/temperature intervals, loaded only on the policy worker. */
final class ThermalBrightnessTable {
    private record Temperature(float min, float max, float nits) { }
    private record Lux(float min, float max, List<Temperature> temperatures) { }
    private final Map<Integer, List<Lux>> mConditions = new HashMap<>();

    ThermalBrightnessTable(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
        if (!root.getTagName().equals("thermal-brightness-config")) {
            throw new IllegalArgumentException("Wrong thermal root");
        }
        for (Element condition : children(root, "thermal-condition-item")) {
            int id = Integer.parseInt(text(condition, "identifier"));
            List<Lux> luxes = new ArrayList<>();
            for (Element lux : children(condition, "lux-temperature-pair")) {
                List<Temperature> temperatures = new ArrayList<>();
                for (Element temp : children(lux, "temperature-brightness-pair")) {
                    float min = number(temp, "min-inclusive");
                    float max = number(temp, "max-exclusive");
                    float nits = number(temp, "nit");
                    if (min >= max || nits <= 0 || (!temperatures.isEmpty()
                            && min != temperatures.get(temperatures.size() - 1).max)) {
                        throw new IllegalArgumentException("Invalid thermal interval");
                    }
                    temperatures.add(new Temperature(min, max, nits));
                }
                float min = number(lux, "min-inclusive");
                float max = number(lux, "max-exclusive");
                if (min < 0 || min >= max || temperatures.isEmpty() || (!luxes.isEmpty()
                        && min != luxes.get(luxes.size() - 1).max)) {
                    throw new IllegalArgumentException("Invalid lux interval");
                }
                luxes.add(new Lux(min, max, temperatures));
            }
            if (luxes.isEmpty() || luxes.get(0).min != 0
                    || mConditions.put(id, luxes) != null) {
                throw new IllegalArgumentException("Invalid thermal condition");
            }
        }
        if (!mConditions.containsKey(0)) throw new IllegalArgumentException("No default condition");
    }

    float cap(int condition, float lux, float temperature) {
        if (!Float.isFinite(lux) || lux < 0 || !Float.isFinite(temperature)) return Float.NaN;
        List<Lux> bands = mConditions.getOrDefault(condition, mConditions.get(0));
        Lux highest = null;
        for (Lux band : bands) {
            if (highest == null || band.max > highest.max) highest = band;
            if (lux < band.min || lux >= band.max) continue;
            return temperatureCap(band, temperature);
        }
        // Stock selects the highest configured lux band for values above its final maximum.
        return highest != null && lux >= highest.max
                ? temperatureCap(highest, temperature) : Float.NaN;
    }

    private static float temperatureCap(Lux band, float temperature) {
        // Below the configured minimum is an uncapped/no-table state, not an infinite table cap.
        if (temperature < band.temperatures.get(0).min) return Float.NaN;
        for (Temperature t : band.temperatures) {
            if (temperature >= t.min && temperature < t.max) return t.nits;
        }
        return Float.NaN;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) result.add(e);
        }
        return result;
    }

    private static String text(Element e, String name) {
        List<Element> nodes = children(e, name);
        if (nodes.size() != 1) throw new IllegalArgumentException("Missing/duplicate " + name);
        return nodes.get(0).getTextContent().trim();
    }

    private static float number(Element e, String name) {
        float f = Float.parseFloat(text(e, name));
        if (!Float.isFinite(f)) throw new IllegalArgumentException("Nonfinite " + name);
        return f;
    }
}
