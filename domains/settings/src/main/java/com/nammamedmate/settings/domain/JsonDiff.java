package com.nammamedmate.settings.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Minimal JSON Patch-style diff (add/remove/replace) between two JSON-like maps/lists. No external
 * dependency.
 */
public final class JsonDiff {

  private JsonDiff() {}

  public static List<Map<String, Object>> diff(Object before, Object after) {
    List<Map<String, Object>> ops = new ArrayList<>();
    walk("", before, after, ops);
    return ops;
  }

  @SuppressWarnings("unchecked")
  private static void walk(
      String path, Object before, Object after, List<Map<String, Object>> ops) {
    if (Objects.equals(before, after)) {
      return;
    }
    if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
      TreeSet<String> keys = new TreeSet<>();
      beforeMap.keySet().forEach(k -> keys.add(String.valueOf(k)));
      afterMap.keySet().forEach(k -> keys.add(String.valueOf(k)));
      for (String key : keys) {
        String child = path + "/" + escape(key);
        boolean hasBefore = beforeMap.containsKey(key);
        boolean hasAfter = afterMap.containsKey(key);
        if (hasBefore && hasAfter) {
          walk(child, beforeMap.get(key), afterMap.get(key), ops);
        } else if (hasAfter) {
          ops.add(op("add", child, null, afterMap.get(key), afterMap.get(key)));
        } else {
          ops.add(op("remove", child, beforeMap.get(key), null, null));
        }
      }
      return;
    }
    if (before instanceof List<?> beforeList && after instanceof List<?> afterList) {
      int max = Math.max(beforeList.size(), afterList.size());
      for (int i = 0; i < max; i++) {
        String child = path + "/" + i;
        Object b = i < beforeList.size() ? beforeList.get(i) : null;
        Object a = i < afterList.size() ? afterList.get(i) : null;
        if (i >= beforeList.size()) {
          ops.add(op("add", child, null, a, a));
        } else if (i >= afterList.size()) {
          ops.add(op("remove", child, b, null, null));
        } else {
          walk(child, b, a, ops);
        }
      }
      return;
    }
    if (before == null) {
      ops.add(op("add", path.isEmpty() ? "/" : path, null, after, after));
    } else if (after == null) {
      ops.add(op("remove", path.isEmpty() ? "/" : path, before, null, null));
    } else {
      ops.add(op("replace", path.isEmpty() ? "/" : path, before, after, null));
    }
  }

  private static Map<String, Object> op(
      String operation, String path, Object from, Object to, Object value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("path", path);
    m.put("op", operation);
    switch (operation) {
      case "replace" -> {
        m.put("from", from);
        m.put("to", to);
      }
      case "add" -> m.put("value", value);
      default -> m.put("from", from); // remove
    }
    return m;
  }

  private static String escape(String key) {
    return key.replace("~", "~0").replace("/", "~1");
  }
}
