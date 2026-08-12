package com.nammamedmate.automation.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** AND-only condition evaluator (BR-3 / BR-5 + eq/not_eq). */
public final class ConditionEvaluator {

  private final Clock clock;

  public ConditionEvaluator(Clock clock) {
    this.clock = clock;
  }

  public record EvalResult(boolean met, List<Map<String, Object>> evaluated) {
    public EvalResult {
      evaluated = evaluated == null ? List.of() : List.copyOf(evaluated);
    }
  }

  public EvalResult evaluate(List<ConditionSpec> conditions, Map<String, Object> payload) {
    List<Map<String, Object>> rows = new ArrayList<>();
    boolean allMet = true;
    Map<String, Object> ctx = payload == null ? Map.of() : payload;
    for (ConditionSpec spec : conditions == null ? List.<ConditionSpec>of() : conditions) {
      Object resolved = spec == null ? null : resolve(ctx, spec.field());
      boolean result = evalOne(spec, ctx);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("field", spec == null ? null : spec.field());
      row.put("operator", spec == null ? null : spec.operator());
      row.put("value", spec == null ? null : spec.value());
      row.put("resolved_value", resolved);
      row.put("result", result);
      rows.add(row);
      if (!result) {
        allMet = false;
      }
    }
    return new EvalResult(allMet, List.copyOf(rows));
  }

  private boolean evalOne(ConditionSpec spec, Map<String, Object> payload) {
    if (spec == null || spec.operator() == null || spec.operator().isBlank()) {
      return false;
    }
    String op = spec.operator().trim().toLowerCase(Locale.ROOT);
    Object actual = resolve(payload, spec.field());
    Object expected = spec.value();
    return switch (op) {
      case "eq" -> Objects.equals(stringify(actual), stringify(expected));
      case "not_eq" -> !Objects.equals(stringify(actual), stringify(expected));
      case "amount_gt", "count_gt", "risk_score_gt" -> compareNumber(actual, expected) > 0;
      case "amount_lt", "lt" -> compareNumber(actual, expected) < 0;
      case "zone_in", "segment_in", "day_of_week_in", "in" -> inSet(actual, expected, op);
      case "plan_tier_eq", "priority_eq", "health_band_eq" ->
          Objects.equals(stringify(actual), stringify(expected));
      case "time_of_day_between" -> timeBetween(expected, payload);
      default -> false;
    };
  }

  private boolean timeBetween(Object expected, Map<String, Object> payload) {
    List<?> bounds = asList(expected);
    if (bounds.size() < 2) {
      return false;
    }
    LocalTime start = LocalTime.parse(stringify(bounds.get(0)));
    LocalTime end = LocalTime.parse(stringify(bounds.get(1)));
    Instant ref = resolveInstant(payload.get("fired_at"));
    LocalTime now =
        ZonedDateTime.ofInstant(ref != null ? ref : clock.instant(), ZoneOffset.UTC).toLocalTime();
    if (start.equals(end)) {
      return true;
    }
    if (start.isBefore(end)) {
      return !now.isBefore(start) && !now.isAfter(end);
    }
    // Overnight window e.g. 22:00–06:00
    return !now.isBefore(start) || !now.isAfter(end);
  }

  private boolean inSet(Object actual, Object expected, String op) {
    if ("day_of_week_in".equals(op) && actual == null) {
      DayOfWeek dow = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).getDayOfWeek();
      actual = dow.name();
    }
    String needle = stringify(actual);
    if (needle == null) {
      return false;
    }
    for (Object item : asList(expected)) {
      if (needle.equalsIgnoreCase(stringify(item))) {
        return true;
      }
    }
    return false;
  }

  public static Object resolve(Map<String, Object> payload, String field) {
    if (field == null || field.isBlank()) {
      return null;
    }
    if (payload.containsKey(field)) {
      return payload.get(field);
    }
    String[] parts = field.split("\\.");
    Object cur = payload;
    for (String part : parts) {
      if (!(cur instanceof Map<?, ?> map)) {
        // fallback: last segment as flat key
        return payload.get(parts[parts.length - 1]);
      }
      cur = map.get(part);
      if (cur == null) {
        Object flat = payload.get(parts[parts.length - 1]);
        return flat != null ? flat : payload.get(field);
      }
    }
    return cur;
  }

  private static int compareNumber(Object actual, Object expected) {
    BigDecimal a = toDecimal(actual);
    BigDecimal b = toDecimal(expected);
    if (a == null || b == null) {
      return -1;
    }
    return a.compareTo(b);
  }

  private static BigDecimal toDecimal(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    try {
      return new BigDecimal(stringify(v));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static List<?> asList(Object v) {
    if (v instanceof List<?> list) {
      return list;
    }
    if (v instanceof Collection<?> c) {
      return List.copyOf(c);
    }
    if (v instanceof Object[] arr) {
      return List.of(arr);
    }
    if (v == null) {
      return List.of();
    }
    return List.of(v);
  }

  private static String stringify(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private static Instant resolveInstant(Object v) {
    if (v instanceof Instant i) {
      return i;
    }
    if (v == null) {
      return null;
    }
    try {
      return Instant.parse(stringify(v));
    } catch (Exception ex) {
      return null;
    }
  }
}
