package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminBulkActionService {

  static final int MAX_PHARMACIES = 100;
  private static final int BULK_RATE_LIMIT = 5;
  private static final int POLL_RATE_LIMIT = 30;
  private static final int WINDOW = 60;
  private static final Set<String> ACTIONS = Set.of("SUSPEND", "SEND_NOTICE", "EXPORT");
  private static final Set<String> SUSPEND_TYPES = Set.of("TEMPORARY", "PERMANENT");

  private final BulkActionJobStore jobs;
  private final AdminPharmacyStore pharmacies;
  private final AdminPharmacyActionsService actions;
  private final AuditLogStore auditLog;
  private final OutboxPublisher outbox;
  private final ObjectProvider<BulkActionJobProcessor> processor;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public AdminBulkActionService(
      BulkActionJobStore jobs,
      AdminPharmacyStore pharmacies,
      AdminPharmacyActionsService actions,
      AuditLogStore auditLog,
      OutboxPublisher outbox,
      ObjectProvider<BulkActionJobProcessor> processor,
      RateLimiter rateLimiter,
      Clock clock) {
    this.jobs = jobs;
    this.pharmacies = pharmacies;
    this.actions = actions;
    this.auditLog = auditLog;
    this.outbox = outbox;
    this.processor = processor;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> submitBulkAction(
      MedmatePrincipal principal,
      List<UUID> pharmacyIds,
      String action,
      Map<String, Object> payload) {
    requireBulkRole(principal);
    rateLimit("admin:pharmacies:bulk:" + principal.subject(), BULK_RATE_LIMIT);

    if (pharmacyIds == null || pharmacyIds.isEmpty()) {
      throw new AppException("PHARMACY_IDS_REQUIRED", "pharmacy_ids is required", 400);
    }
    if (pharmacyIds.size() > MAX_PHARMACIES) {
      throw new AppException("TOO_MANY_PHARMACIES", "Maximum 100 pharmacy IDs per request", 400);
    }
    if (action == null || !ACTIONS.contains(action)) {
      throw new AppException("INVALID_ACTION", "action is not valid", 400);
    }
    if ("SUSPEND".equals(action) && principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN_SUSPEND", "Only admin_super may bulk suspend", 403);
    }

    Map<String, Object> normalizedPayload =
        payload == null ? Map.of() : new LinkedHashMap<>(payload);
    validatePayload(action, normalizedPayload);

    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    jobs.insert(
        new JobRow(
            jobId,
            action,
            normalizedPayload,
            List.copyOf(pharmacyIds),
            "QUEUED",
            pharmacyIds.size(),
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            principal.subject(),
            null,
            null,
            now));

    processor.ifAvailable(p -> p.processJob(jobId));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", jobId.toString());
    data.put("action", action);
    data.put("total_pharmacies", pharmacyIds.size());
    data.put("status", "QUEUED");
    data.put("estimated_completion_seconds", estimateSeconds(pharmacyIds.size()));
    data.put("poll_url", "/api/v1/admin/bulk-jobs/" + jobId);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getJobStatus(MedmatePrincipal principal, UUID jobId) {
    requireBulkRole(principal);
    rateLimit("admin:bulk-jobs:get:" + principal.subject(), POLL_RATE_LIMIT);

    JobRow job =
        jobs.findById(jobId)
            .orElseThrow(() -> new AppException("JOB_NOT_FOUND", "Bulk job not found", 404));
    return toJobMap(job);
  }

  void suspendPharmacy(
      UUID pharmacyId, Map<String, Object> payload, UUID actorId, String actorRole) {
    AdminDetailRow row =
        pharmacies
            .findDetail(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    if ("SUSPENDED".equals(row.status())) {
      throw new AppException("ALREADY_SUSPENDED", "Pharmacy already suspended", 409);
    }
    String suspendType = String.valueOf(payload.get("suspend_type"));
    String reason = String.valueOf(payload.get("reason"));
    boolean canReapply = !"PERMANENT".equals(suspendType);
    Instant now = clock.instant();
    pharmacies.suspend(pharmacyId, suspendType, canReapply, now);
    auditLog.append(
        new AuditLogRecord(
            Ids.newId(),
            "pharmacy",
            pharmacyId,
            "PHARMACY_SUSPENDED",
            actorId,
            actorRole,
            Map.of("reason", reason, "suspend_type", suspendType, "bulk", true),
            null,
            now));
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.suspended",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "channels",
                List.of("WHATSAPP", "EMAIL"),
                "template",
                "PHARMACY_SUSPENDED",
                "reason",
                reason)));
  }

  String exportPharmacies(List<UUID> pharmacyIds) throws IOException {
    List<AdminListRow> rows = pharmacies.listByIds(pharmacyIds);
    LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (Writer writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8)) {
      writer.write(
          "# Namma MedMate Pharmacy Export | " + day + " | Total rows: " + rows.size() + "\n");
      writer.write(
          "code,business_name,owner_name,phone,email,zone,status,plan,is_online,rating,orders_today,gmv_today,fill_rate_pct,commission_pct,net_payout,created_at\n");
      for (AdminListRow row : rows) {
        writer.write(csv(row.code()));
        writer.write(',');
        writer.write(csv(row.businessName()));
        writer.write(',');
        writer.write(csv(row.ownerName()));
        writer.write(',');
        writer.write(csv(row.phone()));
        writer.write(',');
        writer.write(csv(row.email()));
        writer.write(',');
        writer.write(csv(row.zoneName()));
        writer.write(',');
        writer.write(csv(row.status()));
        writer.write(',');
        writer.write(csv(row.plan()));
        writer.write(',');
        writer.write(Boolean.toString(row.online()));
        writer.write(',');
        writer.write(row.rating() == null ? "0.00" : row.rating().toPlainString());
        writer.write(',');
        writer.write(Integer.toString(row.ordersToday()));
        writer.write(',');
        writer.write(paiseToRupees(row.gmvTodayPaise()).toPlainString());
        writer.write(',');
        writer.write(row.fillRatePct() == null ? "0.00" : row.fillRatePct().toPlainString());
        writer.write(',');
        writer.write(row.commissionPct() == null ? "0.00" : row.commissionPct().toPlainString());
        writer.write(',');
        writer.write(paiseToRupees(row.netPayoutPaise()).toPlainString());
        writer.write(',');
        writer.write(row.createdAt() == null ? "" : row.createdAt().toString());
        writer.write('\n');
      }
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  static Map<String, Object> toJobMap(JobRow job) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", job.id().toString());
    data.put("action", job.action());
    data.put("status", job.status());
    data.put("total_pharmacies", job.totalPharmacies());
    data.put("processed", job.processed());
    data.put("succeeded", job.succeeded());
    data.put("failed", job.failed());
    data.put("skipped", job.skipped());
    if (job.skippedPharmacies() != null && !job.skippedPharmacies().isEmpty()) {
      data.put("skipped_pharmacies", job.skippedPharmacies());
    }
    if (job.startedAt() != null) {
      data.put("started_at", job.startedAt().toString());
    }
    if (job.completedAt() != null) {
      data.put("completed_at", job.completedAt().toString());
    }
    if (job.resultPayload() != null && !job.resultPayload().isEmpty()) {
      data.putAll(job.resultPayload());
    }
    return data;
  }

  private void validatePayload(String action, Map<String, Object> payload) {
    if ("SUSPEND".equals(action)) {
      String reason = stringVal(payload.get("reason"));
      String suspendType = stringVal(payload.get("suspend_type"));
      if (reason == null || reason.isBlank()) {
        throw new AppException("PAYLOAD_INCOMPLETE", "reason required for SUSPEND", 400);
      }
      if (suspendType == null || !SUSPEND_TYPES.contains(suspendType)) {
        throw new AppException("PAYLOAD_INCOMPLETE", "suspend_type required for SUSPEND", 400);
      }
      return;
    }
    if ("SEND_NOTICE".equals(action)) {
      String channel = stringVal(payload.get("channel"));
      String message = stringVal(payload.get("message"));
      if (channel == null || message == null || message.isBlank()) {
        throw new AppException("PAYLOAD_INCOMPLETE", "channel and message required", 400);
      }
      if ("WHATSAPP".equals(channel) && stringVal(payload.get("template_name")) == null) {
        throw new AppException("PAYLOAD_INCOMPLETE", "template_name required for WHATSAPP", 400);
      }
      if (("EMAIL".equals(channel) || "IN_APP".equals(channel))
          && stringVal(payload.get("subject")) == null) {
        throw new AppException("PAYLOAD_INCOMPLETE", "subject required for EMAIL/IN_APP", 400);
      }
      return;
    }
    if ("EXPORT".equals(action)) {
      // no extra payload
    }
  }

  private static int estimateSeconds(int count) {
    return Math.max(5, count);
  }

  private static void requireBulkRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static String stringVal(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String csv(String value) {
    if (value == null) {
      return "";
    }
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  private static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }
}
