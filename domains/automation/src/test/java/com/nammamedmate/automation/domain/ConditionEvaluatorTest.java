package com.nammamedmate.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T08:30:00Z"), ZoneOffset.UTC);
  private final ConditionEvaluator evaluator = new ConditionEvaluator(clock);

  @Test
  void andLogic_allOperators() {
    var result =
        evaluator.evaluate(
            List.of(
                new ConditionSpec("amount_paise", "amount_gt", 1000),
                new ConditionSpec("amount_paise", "amount_lt", 9000),
                new ConditionSpec("zone_id", "zone_in", List.of("Z1", "Z2")),
                new ConditionSpec("plan_tier", "plan_tier_eq", "GROWTH"),
                new ConditionSpec("priority", "priority_eq", "HIGH"),
                new ConditionSpec("segment", "segment_in", List.of("VIP")),
                new ConditionSpec("health_band", "health_band_eq", "GREEN"),
                new ConditionSpec("risk_score", "risk_score_gt", 50),
                new ConditionSpec("count", "count_gt", 2),
                new ConditionSpec("priority", "eq", "HIGH"),
                new ConditionSpec("priority", "not_eq", "LOW"),
                new ConditionSpec("fired_at", "time_of_day_between", List.of("08:00", "09:00")),
                new ConditionSpec(null, "day_of_week_in", List.of("FRIDAY")),
                new ConditionSpec("order.zone_id", "eq", "nested-z"),
                new ConditionSpec("plan_tier", "in", List.of("STARTER", "GROWTH")),
                new ConditionSpec("amount_paise", "lt", 9000)),
            Map.of(
                "amount_paise",
                5000,
                "zone_id",
                "Z1",
                "plan_tier",
                "GROWTH",
                "priority",
                "HIGH",
                "segment",
                "VIP",
                "health_band",
                "GREEN",
                "risk_score",
                80,
                "count",
                3,
                "fired_at",
                "2026-07-24T08:30:00Z",
                "order",
                Map.of("zone_id", "nested-z")));

    assertThat(result.met()).isTrue();
    assertThat(result.evaluated()).hasSize(16);
  }

  @Test
  void failsUnknownOperatorAndOvernightWindow() {
    var fail = evaluator.evaluate(List.of(new ConditionSpec("x", "unknown", 1)), Map.of("x", 1));
    assertThat(fail.met()).isFalse();

    var overnight =
        evaluator.evaluate(
            List.of(new ConditionSpec("t", "time_of_day_between", List.of("22:00", "06:00"))),
            Map.of("fired_at", "2026-07-24T08:30:00Z"));
    assertThat(overnight.met()).isFalse();

    var overnightOk =
        evaluator.evaluate(
            List.of(new ConditionSpec("t", "time_of_day_between", List.of("22:00", "06:00"))),
            Map.of("fired_at", "2026-07-24T23:00:00Z"));
    assertThat(overnightOk.met()).isTrue();

    var equalWindow =
        evaluator.evaluate(
            List.of(new ConditionSpec("t", "time_of_day_between", List.of("08:30", "08:30"))),
            Map.of());
    assertThat(equalWindow.met()).isTrue();

    var badBounds =
        evaluator.evaluate(
            List.of(new ConditionSpec("t", "time_of_day_between", List.of("08:00"))), Map.of());
    assertThat(badBounds.met()).isFalse();
  }

  @Test
  void resolveFallbacksAndNulls() {
    assertThat(ConditionEvaluator.resolve(Map.of("a", 1), "missing")).isNull();
    assertThat(ConditionEvaluator.resolve(Map.of("zone_id", "Z"), "order.zone_id")).isEqualTo("Z");
    assertThat(evaluator.evaluate(null, null).met()).isTrue();
    assertThat(evaluator.evaluate(List.of(new ConditionSpec("x", null, 1)), Map.of("x", 1)).met())
        .isFalse();
    assertThat(
            evaluator
                .evaluate(List.of(new ConditionSpec("x", "amount_gt", "x")), Map.of("x", "y"))
                .met())
        .isFalse();
    assertThat(
            evaluator
                .evaluate(List.of(new ConditionSpec("z", "zone_in", List.of("A"))), Map.of())
                .met())
        .isFalse();
  }
}
