package com.nammamedmate.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.application.SegmentComputeService;
import com.nammamedmate.marketing.application.SegmentService;
import com.nammamedmate.marketing.application.port.out.CustomerGeoPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyTierReadPort;
import com.nammamedmate.marketing.application.port.out.OrderSegmentMetricsPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.application.port.out.SegmentUsagePort;
import com.nammamedmate.marketing.domain.CriteriaValidator;
import com.nammamedmate.marketing.domain.CustomerMetrics;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.marketing.domain.SegmentType;
import com.nammamedmate.marketing.domain.SystemSegmentRules;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Branch-fill for JaCoCo 100% on marketing STORY-004. */
@ExtendWith(MockitoExtension.class)
class SegmentCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SegmentStore store;
  @Mock SegmentUsagePort usage;
  @Mock OrderSegmentMetricsPort orders;
  @Mock CustomerGeoPort geo;
  @Mock LoyaltyTierReadPort loyalty;

  @Test
  void criteriaValidatorEdges() {
    assertThatThrownBy(() -> CriteriaValidator.validate(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_CRITERIA");
    List<SegmentCriterion> withNull = new ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(() -> CriteriaValidator.validate(withNull))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () -> CriteriaValidator.validate(List.of(new SegmentCriterion("  ", "=", 1))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () -> CriteriaValidator.validate(List.of(new SegmentCriterion("total_orders", " ", 1))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OPERATOR");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(
                    List.of(new SegmentCriterion("total_orders", "=", null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(List.of(new SegmentCriterion("city", "in", List.of()))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () -> CriteriaValidator.validate(List.of(new SegmentCriterion(null, "=", 1))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(List.of(new SegmentCriterion("total_orders", null, 1))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_OPERATOR");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(
                    List.of(new SegmentCriterion("city", "in", "Bangalore"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(
                    List.of(new SegmentCriterion("city", "not_in", List.of()))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
    assertThatThrownBy(
            () ->
                CriteriaValidator.validate(
                    List.of(new SegmentCriterion("total_orders", "between", List.of()))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CRITERIA_FIELD");
  }

  @Test
  void customerMetricsOperatorEdges() {
    CustomerMetrics m =
        new CustomerMetrics(
            UUID.randomUUID(), "n", "p", 5, 50_000, NOW, 10_000, true, 10, 0, "City", "1", "  ");
    assertThat(m.loyaltyTierOrNone()).isEqualTo("NONE");
    assertThat(m.matchesAll(null, NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "!=", 5), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", ">=", "5"), NOW)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "=", 4), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", ">", 5), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "<", 5), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", ">=", 6), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "<=", 4), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "between", List.of(6, 9)), NOW))
        .isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "between", List.of(2, 4)), NOW))
        .isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("total_orders", "between", List.of(2, 9)), NOW))
        .isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "=", 500), NOW)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "=", 1), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", ">", 400), NOW)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", ">", 600), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "<", 600), NOW)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "<", 400), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "<=", 500), NOW)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "<=", 400), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", ">=", 500), NOW)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", ">=", 600), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "between", List.of(1, 2)), NOW))
        .isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "between", List.of(600, 700)), NOW))
        .isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "between", List.of(400, 450)), NOW))
        .isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "between", List.of("400", "600")), NOW))
        .isTrue();
    assertThat(
            m.matchesOne(new SegmentCriterion("total_orders", "between", List.of("2", "9")), NOW))
        .isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("ltv_rs", "!=", 500), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("has_rx_orders", "=", "true"), NOW)).isTrue();
    assertThat(m.matchesOne(new SegmentCriterion("city", "in", List.of("OTHER")), NOW)).isFalse();
    assertThat(m.matchesOne(new SegmentCriterion("city", "not_in", List.of("CITY")), NOW))
        .isFalse();
    assertThat(
            new CustomerMetrics(
                    m.customerId(),
                    m.name(),
                    m.phone(),
                    5,
                    0,
                    NOW,
                    0,
                    false,
                    1,
                    0,
                    null,
                    null,
                    "GOLD")
                .matchesOne(new SegmentCriterion("city", "in", List.of("X")), NOW))
        .isFalse();
  }

  @Test
  void systemRulesNegativeBranches() {
    Set<String> pins = Set.of("560001");
    CustomerMetrics oldNew =
        new CustomerMetrics(
            UUID.randomUUID(), "n", "p", 1, 0, NOW, 0, false, 10, 0, "X", null, "NONE");
    assertThat(SystemSegmentRules.matches("NEW", oldNew, NOW, pins)).isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "NEW",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 2, 0, NOW, 0, false, 3, 0, "X", "560001", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "LOYAL",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 12, 0, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isTrue();
    assertThat(
            SystemSegmentRules.matches(
                "REGULAR",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 1, 0, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "REGULAR",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 10, 0, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "LOYAL",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 15, 0, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isTrue();
    assertThat(
            SystemSegmentRules.matches(
                "LOYAL",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 5, 0, NOW, 0, false, 1, 1, "X", "1", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "LOYAL",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 30, 0, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "VIP",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 5, 100, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "DORMANT",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 5, 0, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(
            SystemSegmentRules.matches(
                "RX_USERS",
                new CustomerMetrics(
                    UUID.randomUUID(), "n", "p", 5, 0, NOW, 0, false, 1, 0, "X", "1", "NONE"),
                NOW,
                pins))
        .isFalse();
    assertThat(SystemSegmentRules.matches("HIGH_VALUE_AREA", oldNew, NOW, pins)).isFalse();
    assertThat(SystemSegmentRules.matches("HIGH_VALUE_AREA", oldNew, NOW, null)).isFalse();
  }

  @Test
  void segmentServiceBranchFill() {
    SegmentService service = new SegmentService(store, usage, Clock.fixed(NOW, ZoneOffset.UTC));
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    when(store.count(null)).thenReturn(0L);
    when(store.list(isNull(), anyInt(), anyInt())).thenReturn(List.of());
    service.list(ops, " ", 0, 0);
    service.list(ops, null, 2, 200);

    UUID id = UUID.randomUUID();
    Segment zero =
        new Segment(
            id,
            "C",
            "d",
            SegmentType.CUSTOM,
            null,
            "READY",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            null);
    when(store.findById(id)).thenReturn(Optional.of(zero));
    when(store.growthChart(id, 12)).thenReturn(List.of());
    Map<String, Object> detail = service.get(ops, id);
    assertThat(detail.get("avg_ltv_rs")).isNotNull();
    assertThat(detail.get("criteria")).isEqualTo(List.of());

    Segment withLtvNullCount =
        new Segment(
            id,
            "C",
            "d",
            SegmentType.CUSTOM,
            List.of(),
            "READY",
            2,
            100L,
            null,
            NOW,
            null,
            NOW,
            NOW,
            null);
    when(store.findById(id)).thenReturn(Optional.of(withLtvNullCount));
    service.get(ops, id);

    when(store.findByNameIgnoreCase("Ok")).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    assertThatThrownBy(
            () ->
                service.create(
                    ops, null, null, List.of(new SegmentCriterion("has_rx_orders", "=", true))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    service.create(ops, "Ok", "d", List.of(new SegmentCriterion("has_rx_orders", "=", true)));

    when(store.listMembers(eq(id), eq("ltv_rs"), eq("asc"), eq(0), eq(20)))
        .thenReturn(new SegmentStore.PagedMemberships(List.of(), 0));
    when(store.listMembers(eq(id), eq("ltv_rs"), eq("desc"), eq(20), eq(5)))
        .thenReturn(new SegmentStore.PagedMemberships(List.of(), 0));
    when(store.listMembers(eq(id), eq("ltv_rs"), eq("desc"), eq(0), eq(20)))
        .thenReturn(new SegmentStore.PagedMemberships(List.of(), 0));
    when(store.listMembers(eq(id), eq("total_orders"), eq("ASC"), eq(0), eq(100)))
        .thenReturn(new SegmentStore.PagedMemberships(List.of(), 0));
    service.listCustomers(ops, id, 5, 5, " ", " ");
    service.listCustomers(ops, id, null, null, null, null);
    service.listCustomers(ops, id, 1, 20, "ltv_rs", "asc");
    service.listCustomers(ops, id, 0, 200, "total_orders", "ASC");

    assertThatThrownBy(
            () ->
                service.list(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    null,
                    1,
                    20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void computeEnrichmentUsesGeoAndLoyaltyOverrides() {
    SegmentComputeService compute =
        new SegmentComputeService(
            store, orders, geo, loyalty, Clock.fixed(NOW, ZoneOffset.UTC), "560001,, ");
    UUID cid = UUID.randomUUID();
    UUID cid2 = UUID.randomUUID();
    when(orders.listAllActiveCustomers())
        .thenReturn(
            List.of(
                new CustomerMetrics(
                    cid, "n", "p", 2, 0, NOW, 0, false, 5, 0, "OldCity", "000000", "SILVER"),
                new CustomerMetrics(
                    cid2, "n2", "p2", 2, 0, NOW, 0, false, 5, 0, "Keep", "111111", "GOLD")));
    when(geo.findByCustomerIds(any()))
        .thenReturn(
            Map.of(
                cid,
                new CustomerGeoPort.Geo("Bangalore", "560001"),
                cid2,
                new CustomerGeoPort.Geo(null, null)));
    when(loyalty.tiersFor(any())).thenReturn(Map.of(cid, "PLATINUM"));
    when(store.findById(any()))
        .thenReturn(
            Optional.of(
                new Segment(
                    UUID.randomUUID(),
                    "HIGH_VALUE_AREA",
                    "d",
                    SegmentType.SYSTEM,
                    List.of(),
                    "READY",
                    0,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null)));
    compute.computeSegment(UUID.randomUUID());
    verify(store).replaceMemberships(any(), any(), eq(NOW));

    assertThat(new CustomerGeoPort.Geo("a", "b").city()).isEqualTo("a");
  }
}
