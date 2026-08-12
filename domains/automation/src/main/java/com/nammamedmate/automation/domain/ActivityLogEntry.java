package com.nammamedmate.automation.domain;

import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One append-only automation activity row (plus optional join fields for reads). */
public record ActivityLogEntry(
    UUID id,
    UUID ruleId,
    String ruleName,
    UUID workflowExecutionId,
    UUID triggerEventId,
    String triggerEvent,
    Map<String, Object> triggerPayload,
    Instant triggerFiredAt,
    String entityType,
    UUID entityId,
    String entityName,
    String actionType,
    Map<String, Object> actionParams,
    List<Map<String, Object>> conditionsEvaluated,
    Map<String, Object> beforeState,
    Map<String, Object> afterState,
    ActivityStatus status,
    String actor,
    UUID overrideBy,
    Instant triggeredAt,
    Instant executedAt,
    Integer executionMs,
    UUID referencesActionId,
    String errorMessage,
    Instant createdAt,
    boolean rolledBack,
    UUID rollbackActionId) {

  public ActivityLogEntry {
    triggerPayload = Map.copyOf(copyMap(triggerPayload));
    actionParams = Map.copyOf(copyMap(actionParams));
    conditionsEvaluated =
        conditionsEvaluated == null ? List.of() : List.copyOf(conditionsEvaluated);
    beforeState = beforeState == null ? null : Map.copyOf(copyMap(beforeState));
    afterState = afterState == null ? null : Map.copyOf(copyMap(afterState));
    actor = actor == null || actor.isBlank() ? "AUTOMATION" : actor;
    entityType = entityType == null || entityType.isBlank() ? "UNKNOWN" : entityType;
    actionType = actionType == null ? "" : actionType;
    status = status == null ? ActivityStatus.EXCEPTION : status;
  }

  @SuppressWarnings("unchecked")
  public static ActivityLogEntry fromAppend(
      String actionType, String status, String message, Map<String, Object> detail) {
    Map<String, Object> d = detail == null ? Map.of() : detail;
    Map<String, Object> params;
    Object nested = d.get("params");
    if (nested == null) {
      nested = d.get("action_params");
    }
    if (nested instanceof Map<?, ?> m) {
      params = castMap(m);
    } else {
      params = stripKnown(d);
    }
    Object conds = d.get("conditions_evaluated");
    List<Map<String, Object>> evaluated = List.of();
    if (conds instanceof List<?> list) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          out.add(castMap(m));
        }
      }
      evaluated = out;
    }
    Instant triggered = parseInstant(d.get("triggered_at"));
    Instant executed = parseInstant(d.get("executed_at"));
    Instant now = Instant.now();
    if (triggered == null) {
      triggered = now;
    }
    ActivityStatus st = ActivityStatus.fromLog(status);
    if (st == ActivityStatus.EXECUTED && executed == null) {
      executed = now;
    }
    return new ActivityLogEntry(
        Ids.newId(),
        parseUuid(d.get("rule_id")),
        stringVal(d.get("rule_name")),
        parseUuid(d.get("workflow_execution_id")),
        parseUuid(d.get("trigger_event_id")),
        stringVal(first(d.get("trigger_event"), d.get("trigger_id"))),
        mapVal(d.get("trigger_payload")),
        parseInstant(d.get("fired_at")),
        stringVal(first(d.get("entity_type"), "UNKNOWN")),
        parseUuid(d.get("entity_id")),
        stringVal(d.get("entity_name")),
        actionType == null ? "" : actionType,
        params,
        evaluated,
        mapOrNull(d.get("before_state")),
        mapOrNull(d.get("after_state")),
        st,
        stringVal(first(d.get("actor"), "AUTOMATION")),
        parseUuid(first(d.get("override_by"), d.get("actor_id"))),
        triggered,
        executed,
        intVal(d.get("execution_ms")),
        parseUuid(d.get("references_action_id")),
        message,
        now,
        false,
        null);
  }

  private static Map<String, Object> stripKnown(Map<String, Object> d) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : d.entrySet()) {
      if (!KNOWN.contains(e.getKey())) {
        out.put(e.getKey(), e.getValue());
      }
    }
    return out;
  }

  private static final java.util.Set<String> KNOWN =
      java.util.Set.of(
          "rule_id",
          "rule_name",
          "workflow_execution_id",
          "trigger_event_id",
          "trigger_event",
          "trigger_id",
          "trigger_payload",
          "fired_at",
          "entity_type",
          "entity_id",
          "entity_name",
          "params",
          "action_params",
          "conditions_evaluated",
          "before_state",
          "after_state",
          "actor",
          "override_by",
          "actor_id",
          "triggered_at",
          "executed_at",
          "execution_ms",
          "references_action_id",
          "prefix");

  private static Object first(Object a, Object b) {
    return a != null ? a : b;
  }

  private static String stringVal(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static UUID parseUuid(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof UUID u) {
      return u;
    }
    try {
      return UUID.fromString(String.valueOf(o));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static Instant parseInstant(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Instant i) {
      return i;
    }
    try {
      return Instant.parse(String.valueOf(o));
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static Integer intVal(Object o) {
    if (o instanceof Number n) {
      return n.intValue();
    }
    return null;
  }

  private static Map<String, Object> mapVal(Object o) {
    if (o instanceof Map<?, ?> m) {
      return castMap(m);
    }
    return Map.of();
  }

  private static Map<String, Object> mapOrNull(Object o) {
    if (o instanceof Map<?, ?> m) {
      return castMap(m);
    }
    return null;
  }

  private static Map<String, Object> castMap(Map<?, ?> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : m.entrySet()) {
      if (e.getKey() != null && e.getValue() != null) {
        out.put(String.valueOf(e.getKey()), e.getValue());
      }
    }
    return out;
  }

  private static Map<String, Object> copyMap(Map<String, Object> m) {
    if (m == null || m.isEmpty()) {
      return Map.of();
    }
    return castMap(m);
  }
}
