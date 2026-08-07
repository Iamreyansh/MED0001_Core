package com.nammamedmate.catalogue.application;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.BulkJobRow;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ponytail: processes BULK_MAP jobs in-process on submit + short poll; upgrade → SQS worker. */
@Component
@ConditionalOnProperty(
    name = "medmate.catalogue.bulk-map.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BulkMapJobProcessor {

  private final MedicineMappingStore store;
  private final MappingService mappingService;
  private final Clock clock;

  public BulkMapJobProcessor(
      MedicineMappingStore store, MappingService mappingService, Clock clock) {
    this.store = store;
    this.mappingService = mappingService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${medmate.catalogue.bulk-map.poll-delay-ms:5000}")
  public void pollQueuedJobs() {
    for (BulkJobRow job : store.findQueuedBulkMapJobs(5)) {
      processJob(job.id());
    }
  }

  @Transactional
  public void processJob(UUID jobId) {
    BulkJobRow job = store.findBulkJob(jobId).orElse(null);
    if (job == null || !"QUEUED".equals(job.status()) || !"BULK_MAP".equals(job.action())) {
      return;
    }

    Instant started = clock.instant();
    store.markBulkJobRunning(jobId, started);

    Map<?, ?> payload = payloadMap(job.payload());
    UUID medicineId = UUID.fromString(String.valueOf(payload.get("master_medicine_id")));
    long pricePaise = ((Number) payload.get("pharmacy_price_paise")).longValue();
    int stock = ((Number) payload.get("initial_stock_quantity")).intValue();

    int processed = 0;
    int succeeded = 0;
    int failed = 0;
    int skipped = 0;
    List<Object> skippedPharmacies = new ArrayList<>();

    for (UUID pharmacyId : job.pharmacyIds()) {
      processed++;
      try {
        mappingService.createForBulk(pharmacyId, medicineId, pricePaise, stock);
        succeeded++;
      } catch (AppException ex) {
        if ("MAPPING_ALREADY_EXISTS".equals(ex.code())
            || "PHARMACY_NOT_ACTIVE".equals(ex.code())
            || "PHARMACY_NOT_FOUND".equals(ex.code())) {
          skipped++;
          skippedPharmacies.add(skipEntry(pharmacyId, ex.code()));
        } else {
          failed++;
        }
      } catch (RuntimeException ex) {
        failed++;
      }
    }

    store.markBulkJobCompleted(
        jobId, processed, succeeded, failed, skipped, skippedPharmacies, clock.instant());
  }

  @SuppressWarnings("unchecked")
  private static Map<?, ?> payloadMap(Object payload) {
    if (payload instanceof Map<?, ?> map) {
      return map;
    }
    return Map.of();
  }

  private static Map<String, Object> skipEntry(UUID pharmacyId, String reason) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("pharmacy_id", pharmacyId.toString());
    entry.put("reason", reason);
    return entry;
  }
}
