package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.BulkJobRow;
import com.nammamedmate.kernel.error.AppException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BulkMapJobProcessorTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private MedicineMappingStore store;
  @Mock private MappingService mappingService;

  private BulkMapJobProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new BulkMapJobProcessor(store, mappingService, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void processJob_successSkipFail() {
    UUID jobId = UUID.randomUUID();
    UUID med = UUID.randomUUID();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID p3 = UUID.randomUUID();
    when(store.findBulkJob(jobId))
        .thenReturn(
            Optional.of(
                new BulkJobRow(
                    jobId,
                    "BULK_MAP",
                    "QUEUED",
                    List.of(p1, p2, p3),
                    Map.of(
                        "master_medicine_id",
                        med.toString(),
                        "pharmacy_price_paise",
                        10000,
                        "initial_stock_quantity",
                        2),
                    UUID.randomUUID(),
                    NOW)));

    doAnswer(
            inv -> {
              UUID pharmacyId = inv.getArgument(0);
              if (pharmacyId.equals(p2)) {
                throw new AppException("MAPPING_ALREADY_EXISTS", "exists", 409);
              }
              if (pharmacyId.equals(p3)) {
                throw new AppException("PRICE_ABOVE_MRP", "bad", 400);
              }
              return null;
            })
        .when(mappingService)
        .createForBulk(any(), eq(med), anyLong(), anyInt());

    processor.processJob(jobId);

    verify(store).markBulkJobRunning(jobId, NOW);
    verify(store).markBulkJobCompleted(eq(jobId), eq(3), eq(1), eq(1), eq(1), any(), eq(NOW));
  }

  @Test
  void processJob_ignoresNonQueuedAndPolls() {
    UUID jobId = UUID.randomUUID();
    when(store.findBulkJob(jobId)).thenReturn(Optional.empty());
    processor.processJob(jobId);
    verify(store, never()).markBulkJobRunning(any(), any());

    UUID med = UUID.randomUUID();
    Map<String, Object> payload =
        Map.of(
            "master_medicine_id",
            med.toString(),
            "pharmacy_price_paise",
            1,
            "initial_stock_quantity",
            0);
    when(store.findQueuedBulkMapJobs(5))
        .thenReturn(
            List.of(
                new BulkJobRow(
                    jobId, "BULK_MAP", "QUEUED", List.of(), payload, UUID.randomUUID(), NOW)));
    when(store.findBulkJob(jobId))
        .thenReturn(
            Optional.of(
                new BulkJobRow(
                    jobId, "BULK_MAP", "QUEUED", List.of(), payload, UUID.randomUUID(), NOW)));
    processor.pollQueuedJobs();
    verify(store).markBulkJobRunning(jobId, NOW);
  }

  @Test
  void processJob_skipsInactiveAndNotFound_andIgnoresWrongStatus() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID med = UUID.randomUUID();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    when(store.findBulkJob(jobId))
        .thenReturn(
            Optional.of(
                new BulkJobRow(
                    jobId,
                    "BULK_MAP",
                    "QUEUED",
                    List.of(p1, p2),
                    Map.of(
                        "master_medicine_id",
                        med.toString(),
                        "pharmacy_price_paise",
                        1,
                        "initial_stock_quantity",
                        0),
                    UUID.randomUUID(),
                    NOW)));
    doAnswer(
            inv -> {
              UUID pharmacyId = inv.getArgument(0);
              if (pharmacyId.equals(p1)) {
                throw new AppException("PHARMACY_NOT_ACTIVE", "x", 403);
              }
              throw new AppException("PHARMACY_NOT_FOUND", "x", 404);
            })
        .when(mappingService)
        .createForBulk(any(), eq(med), anyLong(), anyInt());
    processor.processJob(jobId);
    verify(store).markBulkJobCompleted(eq(jobId), eq(2), eq(0), eq(0), eq(2), any(), eq(NOW));

    when(store.findBulkJob(jobId))
        .thenReturn(
            Optional.of(
                new BulkJobRow(
                    jobId, "BULK_MAP", "RUNNING", List.of(), Map.of(), UUID.randomUUID(), NOW)));
    processor.processJob(jobId);

    when(store.findBulkJob(jobId))
        .thenReturn(
            Optional.of(
                new BulkJobRow(
                    jobId, "EXPORT", "QUEUED", List.of(), "not-a-map", UUID.randomUUID(), NOW)));
    processor.processJob(jobId);

    var method = BulkMapJobProcessor.class.getDeclaredMethod("payloadMap", Object.class);
    method.setAccessible(true);
    assertThat(method.invoke(null, "not-a-map")).isEqualTo(Map.of());
  }

  @Test
  void processJob_runtimeExceptionCountsFailed() {
    UUID jobId = UUID.randomUUID();
    UUID med = UUID.randomUUID();
    UUID p1 = UUID.randomUUID();
    when(store.findBulkJob(jobId))
        .thenReturn(
            Optional.of(
                new BulkJobRow(
                    jobId,
                    "BULK_MAP",
                    "QUEUED",
                    List.of(p1),
                    Map.of(
                        "master_medicine_id",
                        med.toString(),
                        "pharmacy_price_paise",
                        1,
                        "initial_stock_quantity",
                        0),
                    UUID.randomUUID(),
                    NOW)));
    doThrow(new RuntimeException("boom"))
        .when(mappingService)
        .createForBulk(any(), any(), anyLong(), eq(0));

    processor.processJob(jobId);

    verify(store).markBulkJobCompleted(eq(jobId), eq(1), eq(0), eq(1), eq(0), any(), eq(NOW));
  }
}
