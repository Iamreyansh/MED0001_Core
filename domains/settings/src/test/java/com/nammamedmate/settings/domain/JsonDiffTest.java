package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonDiffTest {

  @Test
  void computesPatchOps() {
    Map<String, Object> before = Map.of("status", "ACTIVE", "city", "BLR");
    Map<String, Object> after =
        Map.of("status", "SUSPENDED", "suspended_reason", "x", "tags", List.of("a"));
    var ops = JsonDiff.diff(before, after);
    assertThat(ops).anyMatch(o -> "replace".equals(o.get("op")) && "/status".equals(o.get("path")));
    assertThat(ops)
        .anyMatch(o -> "add".equals(o.get("op")) && "/suspended_reason".equals(o.get("path")));
    assertThat(ops).anyMatch(o -> "remove".equals(o.get("op")) && "/city".equals(o.get("path")));

    assertThat(JsonDiff.diff(null, Map.of("a", 1))).isNotEmpty();
    assertThat(JsonDiff.diff(Map.of("a", 1), null)).isNotEmpty();
    assertThat(JsonDiff.diff(null, "x").get(0).get("path")).isEqualTo("/");
    assertThat(JsonDiff.diff("x", null).get(0).get("path")).isEqualTo("/");
    assertThat(JsonDiff.diff(List.of(1), List.of(1, 2))).isNotEmpty();
    assertThat(JsonDiff.diff(List.of(1, 2), List.of(1))).isNotEmpty();
    assertThat(JsonDiff.diff(List.of(1), List.of(2))).isNotEmpty();
    assertThat(JsonDiff.diff("a", "a")).isEmpty();
    assertThat(JsonDiff.diff("a", "b").get(0).get("op")).isEqualTo("replace");

    Map<String, Object> nestedBefore = new HashMap<>();
    nestedBefore.put("a", null);
    nestedBefore.put("a/b", 1);
    nestedBefore.put("gone", 9);
    Map<String, Object> nestedAfter = new HashMap<>();
    nestedAfter.put("a", 1);
    nestedAfter.put("a/b", 2);
    nestedAfter.put("a", 1);
    nestedAfter.put("new", 3);
    nestedAfter.put("gone", null);
    assertThat(JsonDiff.diff(nestedBefore, nestedAfter)).isNotEmpty();
    assertThat(JsonDiff.diff(Map.of("a", 1), List.of(1))).isNotEmpty();
    assertThat(JsonDiff.diff(List.of("x"), Map.of("a", 1))).isNotEmpty();
  }
}
