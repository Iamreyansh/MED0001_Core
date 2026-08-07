package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
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

/**
 * ponytail: in-pharmacy scheduler processes bulk jobs synchronously on submit and polls QUEUED
 * leftovers; upgrade path is outbox/SQS worker when apps/worker wiring is ready.
 */
@Component
@ConditionalOnProperty(
    name = "medmate.pharmacy.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BulkActionJobProcessor {

  private final BulkActionJobStore jobs;
  private final AdminBulkActionService bulkService;
  private final AdminPharmacyActionsService actions;
  private final Clock clock;

  public BulkActionJobProcessor(
      BulkActionJobStore jobs,
      AdminBulkActionService bulkService,
      AdminPharmacyActionsService actions,
      Clock clock) {
    this.jobs = jobs;
    this.bulkService = bulkService;
    this.actions = actions;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${medmate.pharmacy.bulk-job.poll-delay-ms:5000}")
  public void pollQueuedJobs() {
    for (JobRow job : jobs.findQueued(5)) {
      processJob(job.id());
    }
  }

  @Transactional
  public void processJob(UUID jobId) {
    JobRow job = jobs.findById(jobId).orElse(null);
    if (job == null || !"QUEUED".equals(job.status())) {
      return;
    }
    // BULK_MAP is owned by domains/catalogue BulkMapJobProcessor
    if ("BULK_MAP".equals(job.action())) {
      return;
    }

    Instant started = clock.instant();
    jobs.markRunning(jobId, started);

    if ("EXPORT".equals(job.action())) {
      processExportJob(jobId, job, started);
      return;
    }

    int processed = 0;
    int succeeded = 0;
    int failed = 0;
    int skipped = 0;
    List<Map<String, Object>> skippedPharmacies = new ArrayList<>();

    MedmatePrincipal actor =
        new MedmatePrincipal(
            job.initiatedBy(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "bulk");

    for (UUID pharmacyId : job.pharmacyIds()) {
      processed++;
      try {
        if ("SUSPEND".equals(job.action())) {
          bulkService.suspendPharmacy(
              pharmacyId, job.payload(), job.initiatedBy(), AuthRole.ADMIN_SUPER.value());
          succeeded++;
        } else if ("SEND_NOTICE".equals(job.action())) {
          AdminPharmacyActionsService.NoticeResult notice =
              actions.sendNoticeInternal(
                  actor,
                  pharmacyId,
                  stringVal(job.payload().get("channel")),
                  stringVal(job.payload().get("subject")),
                  stringVal(job.payload().get("message")),
                  stringVal(job.payload().get("priority")),
                  stringVal(job.payload().get("template_name")),
                  jobId,
                  false);
          if (notice.skipReason() != null) {
            skipped++;
            skippedPharmacies.add(skipEntry(pharmacyId, notice.skipReason()));
          } else {
            succeeded++;
          }
        } else {
          failed++;
        }
      } catch (AppException ex) {
        if (isSkippable(ex)) {
          skipped++;
          skippedPharmacies.add(skipEntry(pharmacyId, ex.code()));
        } else {
          failed++;
        }
      } catch (RuntimeException ex) {
        failed++;
      }
    }

    Instant completed = clock.instant();
    jobs.markCompleted(
        jobId, processed, succeeded, failed, skipped, skippedPharmacies, Map.of(), completed);
  }

  private void processExportJob(UUID jobId, JobRow job, Instant started) {
    int failed = 0;
    int succeeded = 0;
    Map<String, Object> resultPayload = new LinkedHashMap<>();
    try {
      String csv = bulkService.exportPharmacies(job.pharmacyIds());
      resultPayload.put("download_url", "/api/v1/admin/bulk-jobs/" + jobId + "/export.csv");
      resultPayload.put("export_rows", job.pharmacyIds().size());
      resultPayload.put("export_content", csv);
      succeeded = job.pharmacyIds().size();
    } catch (Exception ex) {
      failed = job.pharmacyIds().size();
    }
    Instant completed = clock.instant();
    jobs.markCompleted(
        jobId, job.pharmacyIds().size(), succeeded, failed, 0, List.of(), resultPayload, completed);
  }

  private static boolean isSkippable(AppException ex) {
    return "NOTICE_RATE_LIMIT_EXCEEDED".equals(ex.code())
        || "PHARMACY_NOT_FOUND".equals(ex.code())
        || "PHARMACY_NOT_ACTIVE".equals(ex.code())
        || "ALREADY_SUSPENDED".equals(ex.code());
  }

  private static Map<String, Object> skipEntry(UUID pharmacyId, String reason) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("pharmacy_id", pharmacyId.toString());
    entry.put("reason", reason);
    return entry;
  }

  private static String stringVal(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
