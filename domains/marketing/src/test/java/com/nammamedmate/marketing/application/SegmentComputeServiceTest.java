package com.nammamedmate.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.marketing.application.port.out.CustomerGeoPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyTierReadPort;
import com.nammamedmate.marketing.application.port.out.OrderSegmentMetricsPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.CustomerMetrics;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.marketing.domain.SegmentType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SegmentComputeServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SegmentStore store;
  @Mock OrderSegmentMetricsPort orders;
  @Mock CustomerGeoPort geo;
  @Mock LoyaltyTierReadPort loyalty;

  SegmentComputeService compute;
  SegmentComputeJobProcessor processor;
  SegmentNightlyScheduler nightly;

  @BeforeEach
  void setUp() {
    compute =
        new SegmentComputeService(
            store, orders, geo, loyalty, Clock.fixed(NOW, ZoneOffset.UTC), "560001,560002");
    processor = new SegmentComputeJobProcessor(store, compute);
    nightly = new SegmentNightlyScheduler(compute);
  }

  @Test
  void computeCustomAndLogicAndSystemVip() {
    UUID customId = UUID.randomUUID();
    UUID vipId = UUID.randomUUID();
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    UUID c3 = UUID.randomUUID();

    CustomerMetrics goldRx =
        new CustomerMetrics(c1, "A", "1", 5, 10_000, NOW, 1000, true, 40, 0, null, null, null);
    CustomerMetrics silverRx =
        new CustomerMetrics(c2, "B", "2", 5, 10_000, NOW, 1000, true, 40, 0, null, null, null);
    CustomerMetrics vipReach =
        new CustomerMetrics(
            c3, "C", "3", 30, 50_000, NOW, 2000, false, 100, 0, "X", "110001", "NONE");

    when(orders.listAllActiveCustomers()).thenReturn(List.of(goldRx, silverRx, vipReach));
    when(geo.findByCustomerIds(any())).thenReturn(Map.of());
    when(loyalty.tiersFor(any())).thenReturn(Map.of(c1, "GOLD", c2, "SILVER", c3, "NONE"));

    when(store.findById(customId))
        .thenReturn(
            Optional.of(
                new Segment(
                    customId,
                    "RxGold",
                    "d",
                    SegmentType.CUSTOM,
                    List.of(
                        new SegmentCriterion("has_rx_orders", "=", true),
                        new SegmentCriterion("loyalty_tier", "in", List.of("GOLD", "PLATINUM"))),
                    "PENDING_COMPUTE",
                    0,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null)));

    compute.computeSegment(customId);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UUID>> members = ArgumentCaptor.forClass(List.class);
    verify(store).replaceMemberships(eq(customId), members.capture(), eq(NOW));
    assertThat(members.getValue()).containsExactly(c1);
    verify(store).updateComputeResult(eq(customId), eq(1), any(), any(), eq(NOW), eq("READY"));
    verify(store).upsertSnapshot(eq(customId), any(), eq(1));

    when(store.findById(vipId))
        .thenReturn(
            Optional.of(
                new Segment(
                    vipId,
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
                    NOW,
                    NOW,
                    null)));
    compute.computeSegment(vipId);
    verify(store).replaceMemberships(eq(vipId), members.capture(), eq(NOW));
    assertThat(members.getValue()).contains(c3);
  }

  @Test
  void computeEmptyAndNightlyAndJobProcessor() {
    when(orders.listAllActiveCustomers()).thenReturn(List.of());
    UUID id = UUID.randomUUID();
    when(store.findById(id))
        .thenReturn(
            Optional.of(
                new Segment(
                    id,
                    "ALL",
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
    compute.computeSegment(id);
    verify(store).replaceMemberships(eq(id), eq(List.of()), eq(NOW));
    verify(store).updateComputeResult(eq(id), eq(0), eq(null), eq(0L), eq(NOW), eq("READY"));

    when(store.list(SegmentType.SYSTEM, 0, 100))
        .thenReturn(
            List.of(
                new Segment(
                    id,
                    "ALL",
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
    nightly.recomputeSystemSegments();

    UUID jobId = UUID.randomUUID();
    when(store.findJob(jobId))
        .thenReturn(Optional.of(new SegmentStore.ComputeJob(jobId, id, "QUEUED")));
    when(store.findQueuedJobs(5))
        .thenReturn(List.of(new SegmentStore.ComputeJob(jobId, id, "QUEUED")));
    processor.pollQueuedJobs();
    verify(store).markJobRunning(eq(jobId), any());
    verify(store).markJobCompleted(eq(jobId), any());

    when(store.findJob(jobId))
        .thenReturn(Optional.of(new SegmentStore.ComputeJob(jobId, id, "COMPLETED")));
    processor.processJob(jobId);
    when(store.findJob(jobId)).thenReturn(Optional.empty());
    processor.processJob(jobId);

    assertThat(SegmentComputeService.parsePincodes(null)).isEmpty();
    assertThat(SegmentComputeService.parsePincodes("")).isEmpty();
    assertThat(SegmentComputeService.parsePincodes(" 560001 , ")).containsExactly("560001");
  }

  @Test
  void jobFailureMarksFailed() {
    UUID jobId = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    when(store.findJob(jobId))
        .thenReturn(Optional.of(new SegmentStore.ComputeJob(jobId, missing, "QUEUED")));
    when(store.findById(missing)).thenReturn(Optional.empty());
    try {
      processor.processJob(jobId);
    } catch (IllegalStateException ignored) {
      // expected
    }
    verify(store).markJobFailed(eq(jobId), any(), any());
  }
}
