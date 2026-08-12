package com.nammamedmate.automation.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-rule guardrails (rate limit / value caps / approval routing). */
public record Guardrails(
    RateLimit rateLimit,
    Long valueCap,
    Long requireApprovalAbove,
    boolean requireApproval,
    String onRejectAction) {

  public static final Guardrails NONE = new Guardrails(null, null, null, false, null);

  public record RateLimit(int maxFires, int perMinutes) {}

  /** Convenience for tests / STORY-002 DTOs (no require_approval / on_reject_action). */
  public Guardrails(RateLimit rateLimit, Long valueCap, Long requireApprovalAbove) {
    this(rateLimit, valueCap, requireApprovalAbove, false, null);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    if (rateLimit != null) {
      Map<String, Object> rl = new LinkedHashMap<>();
      rl.put("max_fires", rateLimit.maxFires());
      rl.put("per_minutes", rateLimit.perMinutes());
      m.put("rate_limit", rl);
    } else {
      m.put("rate_limit", null);
    }
    m.put("value_cap", valueCap);
    m.put("require_approval_above", requireApprovalAbove);
    m.put("require_approval", requireApproval);
    m.put("on_reject_action", onRejectAction);
    return m;
  }

  public static Guardrails fromMap(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return NONE;
    }
    RateLimit rl = null;
    Object rate = raw.get("rate_limit");
    if (rate instanceof Map<?, ?> rm) {
      Object max = rm.get("max_fires");
      Object per = rm.get("per_minutes");
      if (max != null && per != null) {
        rl = new RateLimit(toInt(max), toInt(per));
      }
    }
    return new Guardrails(
        rl,
        toLong(raw.get("value_cap")),
        toLong(raw.get("require_approval_above")),
        toBool(raw.get("require_approval")),
        toStr(raw.get("on_reject_action")));
  }

  private static int toInt(Object o) {
    if (o instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(String.valueOf(o));
  }

  private static Long toLong(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Number n) {
      return n.longValue();
    }
    String s = String.valueOf(o);
    if (s.isBlank() || "null".equalsIgnoreCase(s)) {
      return null;
    }
    return Long.parseLong(s);
  }

  private static boolean toBool(Object o) {
    if (o == null) {
      return false;
    }
    if (o instanceof Boolean b) {
      return b;
    }
    return "true".equalsIgnoreCase(String.valueOf(o).trim());
  }

  private static String toStr(Object o) {
    if (o == null) {
      return null;
    }
    String s = String.valueOf(o).trim();
    return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
  }
}
