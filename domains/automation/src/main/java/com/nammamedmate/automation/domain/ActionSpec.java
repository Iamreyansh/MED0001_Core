package com.nammamedmate.automation.domain;

import java.util.Map;

public record ActionSpec(String actionId, Map<String, Object> params, boolean parallel) {

  public ActionSpec {
    params = params == null ? Map.of() : Map.copyOf(params);
  }
}
