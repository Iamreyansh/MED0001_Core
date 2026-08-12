package com.nammamedmate.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityLogDomainTest {

  @Test
  void statusParseAndFromLog() {
    assertThat(ActivityStatus.parse("executed")).isEqualTo(ActivityStatus.EXECUTED);
    assertThat(ActivityStatus.fromLog(null)).isEqualTo(ActivityStatus.EXCEPTION);
    assertThat(ActivityStatus.fromLog(" ")).isEqualTo(ActivityStatus.EXCEPTION);
    assertThat(ActivityStatus.fromLog("FAILED")).isEqualTo(ActivityStatus.EXCEPTION);
    assertThat(ActivityStatus.fromLog("DISPATCHED")).isEqualTo(ActivityStatus.EXECUTED);
    assertThat(ActivityStatus.fromLog("DUPLICATE_EXECUTION_SKIPPED"))
        .isEqualTo(ActivityStatus.DUPLICATE_SKIPPED);
    assertThat(ActivityStatus.fromLog("SIMULATED")).isEqualTo(ActivityStatus.SIMULATED);
    assertThat(ActivityStatus.fromLog("nope")).isEqualTo(ActivityStatus.EXCEPTION);
    assertThatThrownBy(() -> ActivityStatus.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActivityStatus.parse(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ActivityStatus.parse("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rollbackableAndFinancial() {
    assertThat(RollbackableActions.isRollbackable("suspend_entity")).isTrue();
    assertThat(RollbackableActions.isRollbackable("release_payout")).isFalse();
    assertThat(RollbackableActions.isRollbackable(null)).isFalse();
    assertThat(RollbackableActions.isFinancial("mass_payout")).isTrue();
    assertThat(RollbackableActions.isFinancial("ROLLBACK")).isFalse();
    assertThat(RollbackableActions.isFinancial(null)).isFalse();
    assertThat(RollbackableActions.isFinancial("send_notification")).isFalse();
    assertThat(RollbackableActions.rollbackResult("suspend_entity")).contains("reactivated");
    assertThat(RollbackableActions.rollbackResult("apply_wallet_credit")).contains("debited");
    assertThat(RollbackableActions.rollbackResult("x")).isEqualTo("Rolled back.");
  }

  @Test
  void fromAppendCoercesAndStrips() {
    UUID rule = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    Map<String, Object> detail = new HashMap<>();
    detail.put("rule_id", rule);
    detail.put("entity_id", entity.toString());
    detail.put("entity_type", "ORDER");
    detail.put("params", Map.of("order_id", "1"));
    detail.put("actor", "AUTOMATION");
    detail.put("triggered_at", Instant.parse("2026-07-24T08:00:00Z"));
    detail.put("execution_ms", 12);
    detail.put("conditions_evaluated", List.of(Map.of("result", true), "skip"));
    detail.put("before_state", Map.of("a", 1));
    detail.put("after_state", Map.of("b", 2));
    detail.put("prefix", "[SIMULATED]");
    ActivityLogEntry e =
        ActivityLogEntry.fromAppend("auto_assign_rider", "DISPATCHED", "ok", detail);
    assertThat(e.status()).isEqualTo(ActivityStatus.EXECUTED);
    assertThat(e.ruleId()).isEqualTo(rule);
    assertThat(e.entityId()).isEqualTo(entity);
    assertThat(e.actionParams()).containsEntry("order_id", "1");
    assertThat(e.conditionsEvaluated()).hasSize(1);
    assertThat(e.beforeState()).containsEntry("a", 1);
    assertThat(e.executionMs()).isEqualTo(12);

    ActivityLogEntry empty = ActivityLogEntry.fromAppend(null, null, null, null);
    assertThat(empty.actionType()).isEmpty();
    assertThat(empty.status()).isEqualTo(ActivityStatus.EXCEPTION);
    assertThat(empty.entityType()).isEqualTo("UNKNOWN");

    ActivityLogEntry stripped =
        ActivityLogEntry.fromAppend(
            "rate_limit",
            "RATE_LIMITED",
            "limited",
            Map.of("rule_id", "not-a-uuid", "extra", "keep", "triggered_at", "bad"));
    assertThat(stripped.ruleId()).isNull();
    assertThat(stripped.actionParams()).containsEntry("extra", "keep");

    ActivityLogEntry nested =
        ActivityLogEntry.fromAppend(
            "x",
            "FAILED",
            "e",
            Map.of(
                "action_params",
                Map.of("k", "v"),
                "actor",
                "HUMAN",
                "override_by",
                UUID.randomUUID(),
                "fired_at",
                Instant.parse("2026-07-24T08:00:00Z"),
                "executed_at",
                "2026-07-24T08:00:01Z",
                "trigger_payload",
                Map.of("p", 1)));
    assertThat(nested.status()).isEqualTo(ActivityStatus.EXCEPTION);
    assertThat(nested.actor()).isEqualTo("HUMAN");
    assertThat(nested.actionParams()).containsEntry("k", "v");
    assertThat(nested.triggerPayload()).containsEntry("p", 1);

    ActivityLogEntry notList =
        ActivityLogEntry.fromAppend(
            "x", "EXECUTED", "m", Map.of("conditions_evaluated", "nope", "before_state", "x"));
    assertThat(notList.conditionsEvaluated()).isEmpty();
    assertThat(notList.beforeState()).isNull();

    ActivityLogEntry compact =
        new ActivityLogEntry(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, false, null);
    assertThat(compact.actor()).isEqualTo("AUTOMATION");
    assertThat(compact.actionParams()).isEmpty();
    assertThat(compact.status()).isEqualTo(ActivityStatus.EXCEPTION);

    ActivityLogEntry blanks =
        new ActivityLogEntry(
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            null,
            "  ",
            null,
            null,
            "a",
            Map.of(),
            List.of(),
            null,
            null,
            ActivityStatus.EXECUTED,
            "  ",
            null,
            Instant.parse("2026-07-24T08:00:00Z"),
            null,
            null,
            null,
            null,
            Instant.parse("2026-07-24T08:00:00Z"),
            false,
            null);
    assertThat(blanks.actor()).isEqualTo("AUTOMATION");
    assertThat(blanks.entityType()).isEqualTo("UNKNOWN");

    ActivityLogEntry withExecuted =
        ActivityLogEntry.fromAppend(
            "x",
            "EXECUTED",
            "m",
            Map.of("executed_at", "2026-07-24T08:00:01Z", "triggered_at", "2026-07-24T08:00:00Z"));
    assertThat(withExecuted.executedAt()).isEqualTo(Instant.parse("2026-07-24T08:00:01Z"));

    Map<String, Object> nullable = new HashMap<>();
    nullable.put(null, "v");
    nullable.put("k", null);
    nullable.put("ok", 1);
    ActivityLogEntry fromNulls =
        ActivityLogEntry.fromAppend("x", "SIMULATED", "m", Map.of("params", nullable));
    assertThat(fromNulls.actionParams()).containsEntry("ok", 1);
  }
}
