package com.nammamedmate.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.application.port.out.SegmentUsagePort;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.marketing.domain.SegmentType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SegmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SegmentStore store;
  @Mock SegmentUsagePort usage;
  SegmentService service;

  private final MedmatePrincipal superAdmin = principal(AuthRole.ADMIN_SUPER);
  private final MedmatePrincipal ops = principal(AuthRole.ADMIN_OPERATIONS);
  private final MedmatePrincipal finance = principal(AuthRole.ADMIN_FINANCE);

  @BeforeEach
  void setUp() {
    service = new SegmentService(store, usage, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void listAndCreateAndGet() {
    UUID id = UUID.fromString("a0130004-0000-4000-8000-000000000004");
    Segment vip = system("VIP", id, 10, 62_000L, 1_000_000L);
    when(store.count(null)).thenReturn(1L);
    when(store.list(isNull(), eq(0), eq(20))).thenReturn(List.of(vip));
    SegmentService.PagedResult listed = service.list(finance, null, null, null);
    assertThat(listed.meta().total()).isEqualTo(1);
    assertThat(((List<?>) listed.data().get("segments"))).hasSize(1);

    when(store.findByNameIgnoreCase("High AOV")).thenReturn(Optional.empty());
    when(store.insert(any()))
        .thenAnswer(
            inv -> {
              Segment s = inv.getArgument(0);
              return s;
            });
    Map<String, Object> created =
        service.create(
            ops,
            "High AOV",
            "desc",
            List.of(
                new SegmentCriterion("city", "in", List.of("Bangalore")),
                new SegmentCriterion("avg_order_value_rs", ">", 800)));
    assertThat(created.get("status")).isEqualTo("PENDING_COMPUTE");
    assertThat(created.get("segment_type")).isEqualTo("CUSTOM");

    when(store.findById(id)).thenReturn(Optional.of(vip));
    when(store.growthChart(id, 12))
        .thenReturn(List.of(new SegmentStore.SnapshotPoint(LocalDate.of(2026, 7, 21), 10)));
    Map<String, Object> detail = service.get(ops, id);
    assertThat((List<?>) detail.get("recommended_actions")).isNotEmpty();
    assertThat(detail.get("customer_count")).isEqualTo(10);
  }

  @Test
  void createErrors() {
    assertThatThrownBy(() -> service.create(ops, " ", null, List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    "x".repeat(101),
                    null,
                    List.of(new SegmentCriterion("city", "in", List.of("A")))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(ops, "A", null, List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_CRITERIA");
    when(store.findByNameIgnoreCase("Dup"))
        .thenReturn(Optional.of(system("Dup", Ids(), 0, null, null)));
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "Dup", null, List.of(new SegmentCriterion("has_rx_orders", "=", true))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SEGMENT_NAME_EXISTS");
    assertThatThrownBy(
            () ->
                service.create(
                    finance, "A", null, List.of(new SegmentCriterion("has_rx_orders", "=", true))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void computeReturnsEnqueuedJob() {
    UUID id = Ids();
    when(store.findById(id)).thenReturn(Optional.of(custom(id)));
    UUID jobId = Ids();
    when(store.enqueueComputeJob(id, NOW)).thenReturn(jobId);
    Map<String, Object> data = service.enqueueCompute(ops, id);
    assertThat(data.get("status")).isEqualTo("ENQUEUED");
    assertThat(data.get("job_id")).isEqualTo(jobId);
  }

  @Test
  void deleteSystemForbiddenInUseAndOk() {
    UUID sys = Ids();
    when(store.findById(sys)).thenReturn(Optional.of(system("VIP", sys, 0, null, null)));
    assertThatThrownBy(() -> service.delete(superAdmin, sys))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DELETE_SYSTEM_SEGMENT");

    UUID customId = Ids();
    when(store.findById(customId)).thenReturn(Optional.of(custom(customId)));
    when(usage.isReferencedByActiveCouponOrCampaign(customId)).thenReturn(true);
    assertThatThrownBy(() -> service.delete(superAdmin, customId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SEGMENT_IN_USE");

    when(usage.isReferencedByActiveCouponOrCampaign(customId)).thenReturn(false);
    Map<String, Object> deleted = service.delete(superAdmin, customId);
    assertThat(deleted.get("deleted")).isEqualTo(true);
    verify(store).softDelete(customId, NOW);

    assertThatThrownBy(() -> service.delete(ops, customId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void listCustomersPaginated() {
    UUID id = Ids();
    when(store.findById(id)).thenReturn(Optional.of(custom(id)));
    when(store.listMembers(eq(id), eq("total_orders"), eq("asc"), eq(0), eq(20)))
        .thenReturn(
            new SegmentStore.PagedMemberships(
                List.of(
                    new SegmentStore.MembershipCustomer(
                        Ids(), "Priya", "+9198", 45, 1_450_000, NOW)),
                1));
    SegmentService.PagedResult page = service.listCustomers(ops, id, 1, 20, "total_orders", "asc");
    assertThat(((List<?>) page.data().get("customers"))).hasSize(1);
    assertThatThrownBy(() -> service.listCustomers(finance, id, 1, 20, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void notFoundAndTypeFilter() {
    UUID id = Ids();
    when(store.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(ops, id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SEGMENT_NOT_FOUND");
    when(store.count(SegmentType.SYSTEM)).thenReturn(8L);
    when(store.list(SegmentType.SYSTEM, 0, 20)).thenReturn(List.of());
    service.list(ops, "SYSTEM", 1, 20);
    assertThatThrownBy(() -> service.list(ops, "NOPE", 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.list(null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  private static MedmatePrincipal principal(AuthRole role) {
    return new MedmatePrincipal(Ids(), role, null, TokenScope.FULL, "jti");
  }

  private static UUID Ids() {
    return UUID.randomUUID();
  }

  private static Segment system(String name, UUID id, int count, Long aov, Long ltv) {
    return new Segment(
        id,
        name,
        "d",
        SegmentType.SYSTEM,
        List.of(new SegmentCriterion("total_orders", ">=", 30)),
        "READY",
        count,
        aov,
        ltv,
        NOW,
        null,
        NOW,
        NOW,
        null);
  }

  private static Segment custom(UUID id) {
    return new Segment(
        id,
        "Custom",
        "d",
        SegmentType.CUSTOM,
        List.of(new SegmentCriterion("has_rx_orders", "=", true)),
        "PENDING_COMPUTE",
        0,
        null,
        null,
        null,
        Ids(),
        NOW,
        NOW,
        null);
  }
}
