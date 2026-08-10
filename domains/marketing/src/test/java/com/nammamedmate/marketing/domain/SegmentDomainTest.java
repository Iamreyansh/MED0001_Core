package com.nammamedmate.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SegmentDomainTest {

  private final Instant now = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  void criteriaValidatorRejectsEmptyInvalidFieldAndOperator() {
    assertThatThrownBy(() -> CriteriaValidator.validate(List.of()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_CRITERIA");
    assertThatThrownBy(
            () -> CriteriaValidator.validate(List.of(new SegmentCriterion("unknown", "=", 1))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(
                    List.of(new SegmentCriterion("city", "=", List.of("X")))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OPERATOR");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(
                    List.of(new SegmentCriterion("total_orders", "between", List.of(1)))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    List<SegmentCriterion> ok =
        CriteriaValidator.validate(List.of(new SegmentCriterion("Has_Rx_Orders", "=", true)));
    assertThat(ok.getFirst().field()).isEqualTo("has_rx_orders");
    assertThat(
            CriteriaValidator.validate(
                List.of(new SegmentCriterion("total_orders", "between", List.of(2, 9)))))
        .hasSize(1);
  }

  @Test
  void customCriteriaAndLogic() {
    CustomerMetrics goldRx =
        metrics(true, "GOLD", 5, 50_000, Instant.parse("2026-07-20T00:00:00Z"));
    CustomerMetrics silverRx =
        metrics(true, "SILVER", 5, 50_000, Instant.parse("2026-07-20T00:00:00Z"));
    List<SegmentCriterion> criteria =
        List.of(
            new SegmentCriterion("has_rx_orders", "=", true),
            new SegmentCriterion("loyalty_tier", "in", List.of("GOLD", "PLATINUM")));
    assertThat(goldRx.matchesAll(criteria, now)).isTrue();
    assertThat(silverRx.matchesAll(criteria, now)).isFalse();
    assertThat(goldRx.matchesAll(List.of(), now)).isFalse();
  }

  @Test
  void operatorsCoverNumericGeoAndMoney() {
    CustomerMetrics m =
        new CustomerMetrics(
            UUID.randomUUID(),
            "A",
            "+91",
            5,
            150_000,
            Instant.parse("2026-07-10T00:00:00Z"),
            80_000,
            false,
            20,
            1,
            "Bangalore",
            "560001",
            "NONE");
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "between", List.of(2, 9)), now))
        .isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", ">", 1000), now)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "between", List.of(1000, 2000)), now))
        .isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("avg_order_value_rs", ">=", 800), now)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("last_order_days_ago", ">=", 10), now)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("city", "in", List.of("bangalore")), now))
        .isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("pincode", "not_in", List.of("110001")), now))
        .isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "=", 5), now)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", ">", 4), now)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "<", 6), now)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "<=", 5), now)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("unknown", "=", 1), now)).isFalse();
    assertThat(m.lastOrderDaysAgo(now)).isEqualTo(14);
    assertThat(
            new CustomerMetrics(
                    m.customerId(),
                    m.name(),
                    m.phone(),
                    0,
                    0,
                    null,
                    0,
                    false,
                    1,
                    0,
                    null,
                    null,
                    null)
                .lastOrderDaysAgo(now))
        .isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void systemRulesCoverAllEight() {
    Set<String> pins = Set.of("560001");
    CustomerMetrics neu =
        new CustomerMetrics(
            UUID.randomUUID(), "n", "p", 1, 0, now, 0, false, 3, 0, "X", "560001", "NONE");
    assertThat(SystemSegmentRules.matches("NEW", neu, now, pins)).isTrue();
    assertThat(SystemSegmentRules.matches("REGULAR", metrics(false, "NONE", 5, 0, now), now, pins))
        .isTrue();
    assertThat(
            SystemSegmentRules.matches(
                "LOYAL",
                new CustomerMetrics(
                    UUID.randomUUID(), "l", "p", 8, 0, now, 0, false, 40, 3, "X", "1", "NONE"),
                now,
                pins))
        .isTrue();
    assertThat(SystemSegmentRules.matches("VIP", metrics(false, "NONE", 30, 0, now), now, pins))
        .isTrue();
    assertThat(
            SystemSegmentRules.matches("VIP", metrics(false, "NONE", 5, 1_000_001, now), now, pins))
        .isTrue();
    CustomerMetrics dormant =
        new CustomerMetrics(
            UUID.randomUUID(),
            "d",
            "p",
            2,
            0,
            Instant.parse("2026-01-01T00:00:00Z"),
            0,
            false,
            200,
            0,
            "X",
            "1",
            "NONE");
    assertThat(SystemSegmentRules.matches("DORMANT", dormant, now, pins)).isTrue();
    assertThat(SystemSegmentRules.matches("RX_USERS", metrics(true, "NONE", 1, 0, now), now, pins))
        .isTrue();
    assertThat(SystemSegmentRules.matches("HIGH_VALUE_AREA", neu, now, pins)).isTrue();
    assertThat(SystemSegmentRules.matches("HIGH_VALUE_AREA", neu, now, Set.of())).isFalse();
    assertThat(SystemSegmentRules.matches("ALL", neu, now, pins)).isTrue();
    assertThat(SystemSegmentRules.matches("NOPE", neu, now, pins)).isFalse();
  }

  @Test
  void recommendedActionsAlwaysNonEmpty() {
    for (String name :
        List.of(
            "NEW",
            "REGULAR",
            "LOYAL",
            "VIP",
            "DORMANT",
            "RX_USERS",
            "HIGH_VALUE_AREA",
            "ALL",
            "OTHER")) {
      assertThat(RecommendedActions.forSegment(name, SegmentType.SYSTEM)).isNotEmpty();
    }
    assertThat(RecommendedActions.forSegment("Custom", SegmentType.CUSTOM)).hasSize(2);
  }

  @Test
  void moneyFormats() {
    assertThat(MoneyFormats.paiseToRupees(1050)).isEqualByComparingTo("10.50");
    assertThat(MoneyFormats.rupeesToPaise(new BigDecimal("10.50"))).isEqualTo(1050);
    assertThat(MoneyFormats.criterionRupeesToPaise(100)).isEqualTo(10_000);
    assertThat(MoneyFormats.criterionRupeesToPaise("12.5")).isEqualTo(1250);
    assertThatThrownBy(() -> MoneyFormats.criterionRupeesToPaise(true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void segmentHelpers() {
    Segment s =
        new Segment(
            UUID.randomUUID(),
            "VIP",
            "d",
            SegmentType.SYSTEM,
            List.of(),
            "READY",
            0,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(s.isSystem()).isTrue();
  }

  private static CustomerMetrics metrics(
      boolean rx, String tier, int orders, long ltv, Instant lastOrder) {
    return new CustomerMetrics(
        UUID.randomUUID(),
        "n",
        "p",
        orders,
        ltv,
        lastOrder,
        1000,
        rx,
        30,
        0,
        "City",
        "560001",
        tier);
  }
}
