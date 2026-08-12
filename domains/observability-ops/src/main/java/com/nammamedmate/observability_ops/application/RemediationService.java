package com.nammamedmate.observability_ops.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.application.port.out.ApiErrorRatePort;
import com.nammamedmate.observability_ops.application.port.out.MonitoringAlertStore;
import com.nammamedmate.observability_ops.application.port.out.NotificationDispatchPort;
import com.nammamedmate.observability_ops.application.port.out.OnlineAdminDirectoryPort;
import com.nammamedmate.observability_ops.application.port.out.PaymentJobRetryPort;
import com.nammamedmate.observability_ops.application.port.out.PharmacyThrottlePort;
import com.nammamedmate.observability_ops.application.port.out.PlaybookAuditPort;
import com.nammamedmate.observability_ops.application.port.out.RemediationLogStore;
import com.nammamedmate.observability_ops.application.port.out.RemediationPlaybookStore;
import com.nammamedmate.observability_ops.application.port.out.RiderNotifyPort;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationLogEntry;
import com.nammamedmate.observability_ops.domain.RemediationPlaybook;
import com.nammamedmate.observability_ops.domain.RemediationStatus;
import com.nammamedmate.observability_ops.domain.RemediationTriggerType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemediationService {

  private static final Set<AuthRole> OPS = Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final int PAGE_LIMIT = 20;
  private static final long MANUAL_RATE_LIMIT_MINUTES = 5;
  private static final Set<String> SUPER_ROLES = Set.of("admin_super");

  private final RemediationPlaybookStore playbooks;
  private final RemediationLogStore logs;
  private final MonitoringAlertStore alerts;
  private final RiderNotifyPort riders;
  private final PharmacyThrottlePort pharmacies;
  private final PaymentJobRetryPort payments;
  private final ApiErrorRatePort apiErrors;
  private final PlaybookAuditPort audit;
  private final NotificationDispatchPort notify;
  private final OnlineAdminDirectoryPort admins;
  private final Clock clock;

  public RemediationService(
      RemediationPlaybookStore playbooks,
      RemediationLogStore logs,
      MonitoringAlertStore alerts,
      RiderNotifyPort riders,
      PharmacyThrottlePort pharmacies,
      PaymentJobRetryPort payments,
      ApiErrorRatePort apiErrors,
      PlaybookAuditPort audit,
      NotificationDispatchPort notify,
      OnlineAdminDirectoryPort admins,
      Clock clock) {
    this.playbooks = playbooks;
    this.logs = logs;
    this.alerts = alerts;
    this.riders = riders;
    this.pharmacies = pharmacies;
    this.payments = payments;
    this.apiErrors = apiErrors;
    this.audit = audit;
    this.notify = notify;
    this.admins = admins;
    this.clock = clock;
  }

  public record ActionsPage(Map<String, Object> data, PaginationMeta meta) {
    public ActionsPage {
      data = Map.copyOf(data);
    }
  }

  public ActionsPage listActions(
      MedmatePrincipal principal,
      String actionType,
      String status,
      String dateFrom,
      String dateTo,
      Integer page) {
    requireOps(principal);
    int p = page == null || page < 1 ? 1 : page;
    RemediationActionType type = parseActionOptional(actionType);
    RemediationStatus st = parseStatusOptional(status);
    Instant from = parseInstantOptional(dateFrom);
    Instant to = parseInstantOptional(dateTo);
    RemediationLogStore.Page result = logs.list(type, st, from, to, p, PAGE_LIMIT);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (RemediationLogEntry e : result.entries()) {
      rows.add(toActionDto(e));
    }
    return new ActionsPage(
        Map.of("remediation_actions", rows), PaginationMeta.of(p, PAGE_LIMIT, result.total()));
  }

  @Transactional
  public Map<String, Object> triggerManual(
      MedmatePrincipal principal,
      String actionTypeRaw,
      String targetEntityType,
      UUID targetEntityId,
      String reason) {
    requireOps(principal);
    RemediationActionType actionType = parseActionRequired(actionTypeRaw);
    if (targetEntityId == null) {
      throw new AppException("ENTITY_NOT_FOUND", "target_entity_id not found", 400);
    }
    Instant now = Instant.now(clock);
    Instant windowStart = now.minus(MANUAL_RATE_LIMIT_MINUTES, ChronoUnit.MINUTES);
    if (logs.countByActionAndTargetSince(actionType, targetEntityId, windowStart) > 0) {
      throw new AppException("RATE_LIMITED", "Same action + entity triggered too recently", 429);
    }
    String entityType =
        targetEntityType == null || targetEntityType.isBlank()
            ? defaultEntityType(actionType)
            : targetEntityType;
    ensureEntityExists(actionType, targetEntityId);

    UUID id = UUID.randomUUID();
    Map<String, Object> seedDetails = new LinkedHashMap<>();
    if (reason != null && !reason.isBlank()) {
      seedDetails.put("reason", reason);
    }
    RemediationLogEntry initiated =
        logs.insert(
            new RemediationLogEntry(
                id,
                null,
                null,
                actionType,
                RemediationTriggerType.MANUAL,
                entityType,
                targetEntityId,
                seedDetails,
                RemediationStatus.INITIATED,
                principal.subject(),
                now,
                null,
                null));
    executeAndComplete(initiated, null, Map.of());
    return Map.of(
        "remediation_id",
        id,
        "action_type",
        actionType.name(),
        "target_entity_id",
        targetEntityId,
        "status",
        RemediationStatus.INITIATED.name(),
        "triggered_at",
        now.toString());
  }

  public Map<String, Object> listPlaybooks(MedmatePrincipal principal) {
    requireOps(principal);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (RemediationPlaybook pb : playbooks.findAll()) {
      rows.add(toPlaybookDto(pb));
    }
    return Map.of("playbooks", rows);
  }

  @Transactional
  public Map<String, Object> patchPlaybook(
      MedmatePrincipal principal, UUID id, Boolean isEnabled, Map<String, Object> thresholdPatch) {
    if (principal == null || principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "admin_super required", 403);
    }
    RemediationPlaybook existing =
        playbooks
            .findById(id)
            .orElseThrow(
                () -> new AppException("PLAYBOOK_NOT_FOUND", "Playbook id not found", 404));
    Map<String, Object> merged = new LinkedHashMap<>(existing.threshold());
    if (thresholdPatch != null && !thresholdPatch.isEmpty()) {
      validateThresholdPatch(thresholdPatch);
      merged.putAll(thresholdPatch);
    }
    boolean enabled = isEnabled == null ? existing.enabled() : isEnabled;
    Instant now = Instant.now(clock);
    Map<String, Object> before = auditSnapshot(existing);
    RemediationPlaybook updated = playbooks.update(id, enabled, merged, principal.subject(), now);
    Map<String, Object> after = auditSnapshot(updated);
    audit.record(id, principal.subject(), before, after);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("playbook_id", updated.id());
    data.put("is_enabled", updated.enabled());
    data.put("threshold", updated.threshold());
    data.put("updated_by", principal.subject());
    data.put("updated_at", now.toString());
    return data;
  }

  /** Cron entry: evaluate open alerts + stub-sourced conditions for enabled playbooks. */
  @Transactional
  public void runAutoCycle() {
    Instant now = Instant.now(clock);
    processOpenAlerts(now);
    processFillRate(now);
    processPaymentRetries(now);
    processApiErrorRate(now);
  }

  private void processOpenAlerts(Instant now) {
    for (MonitoringAlert alert : alerts.findOpen()) {
      if (alert.autoRemediated()) {
        continue;
      }
      // Dedicated processors own these alert types (fill-rate / payment / API).
      if (alert.type() == AlertType.LOW_FILL_RATE) {
        continue;
      }
      if (alert.type() == AlertType.PAYMENT_JOB_FAILURE) {
        continue;
      }
      if (alert.type() == AlertType.API_ERROR_RATE_HIGH) {
        continue;
      }
      Optional<RemediationPlaybook> pbOpt = playbooks.findByAlertType(alert.type());
      if (pbOpt.isEmpty()) {
        continue;
      }
      if (!pbOpt.get().enabled()) {
        continue;
      }
      RemediationPlaybook pb = pbOpt.get();
      UUID targetId = resolveTarget(alert, pb.autoRemediationAction());
      if (targetId == null) {
        continue;
      }
      executeAuto(
          pb, alert, targetId, defaultEntityType(pb.autoRemediationAction()), now, Map.of());
    }
  }

  private void processFillRate(Instant now) {
    Optional<RemediationPlaybook> pbOpt = playbooks.findByAlertType(AlertType.LOW_FILL_RATE);
    if (pbOpt.isEmpty()) {
      return;
    }
    if (!pbOpt.get().enabled()) {
      return;
    }
    RemediationPlaybook pb = pbOpt.get();
    BigDecimal fillMax = number(pb.threshold(), "fill_rate_pct", 70);
    int consec = intVal(pb.threshold(), "consecutive_days", 3);
    int throttlePct = intVal(pb.threshold(), "throttle_pct", 30);
    for (PharmacyThrottlePort.PharmacyFillSnapshot snap :
        pharmacies.candidatesForThrottle(fillMax, consec)) {
      MonitoringAlert alert =
          alerts
              .findOpen(AlertType.LOW_FILL_RATE, snap.pharmacyId())
              .orElseGet(
                  () ->
                      alerts.insert(
                          new MonitoringAlert(
                              UUID.randomUUID(),
                              AlertSeverity.HIGH,
                              AlertType.LOW_FILL_RATE,
                              "Pharmacy '"
                                  + snap.name()
                                  + "' fill_rate below "
                                  + fillMax
                                  + "% for "
                                  + consec
                                  + " days.",
                              "fill_rate_pct",
                              snap.fillRatePct(),
                              fillMax,
                              snap.pharmacyId(),
                              now,
                              false,
                              null,
                              null,
                              null,
                              false,
                              null,
                              null)));
      if (alert.autoRemediated()) {
        continue;
      }
      executeAuto(
          pb, alert, snap.pharmacyId(), "PHARMACY", now, Map.of("throttle_pct", throttlePct));
    }
    BigDecimal recoveryMin = number(pb.threshold(), "recovery_fill_rate_pct", 80);
    int recoveryDays = intVal(pb.threshold(), "recovery_consecutive_days", 2);
    for (PharmacyThrottlePort.PharmacyFillSnapshot snap :
        pharmacies.candidatesForRecovery(recoveryMin, recoveryDays)) {
      pharmacies.recoverCap(snap.pharmacyId());
    }
  }

  private void processPaymentRetries(Instant now) {
    Optional<RemediationPlaybook> pbOpt = playbooks.findByAlertType(AlertType.PAYMENT_JOB_FAILURE);
    if (pbOpt.isEmpty()) {
      return;
    }
    if (!pbOpt.get().enabled()) {
      return;
    }
    RemediationPlaybook pb = pbOpt.get();
    int delay = intVal(pb.threshold(), "retry_delay_minutes", 5);
    int maxRetries = intVal(pb.threshold(), "max_retries", 3);
    for (PaymentJobRetryPort.FailedJob job : payments.jobsReadyForRetry(now, delay, maxRetries)) {
      if (job.failedRetryCount() >= maxRetries) {
        raisePaymentExhausted(job.jobId(), maxRetries, now);
        continue;
      }
      MonitoringAlert alert =
          alerts
              .findOpen(AlertType.PAYMENT_JOB_FAILURE, job.jobId())
              .orElseGet(
                  () ->
                      alerts.insert(
                          new MonitoringAlert(
                              UUID.randomUUID(),
                              AlertSeverity.HIGH,
                              AlertType.PAYMENT_JOB_FAILURE,
                              "Payment job " + job.jobId() + " failed; auto-retry scheduled.",
                              "payment_job",
                              BigDecimal.ONE,
                              BigDecimal.ZERO,
                              job.jobId(),
                              job.failedAt(),
                              false,
                              null,
                              null,
                              null,
                              false,
                              null,
                              null)));
      RemediationStatus status = executeAuto(pb, alert, job.jobId(), "PAYMENT_JOB", now, Map.of());
      if (status == RemediationStatus.FAILED
          && payments.failedRetryCount(job.jobId()) >= maxRetries) {
        raisePaymentExhausted(job.jobId(), maxRetries, now);
      }
    }
  }

  private void raisePaymentExhausted(UUID jobId, int maxRetries, Instant now) {
    payments.markExhausted(jobId);
    alerts
        .findOpen(AlertType.PAYMENT_JOB_FAILURE, jobId)
        .ifPresent(a -> alerts.resolve(a.id(), now, "RETRIES_EXHAUSTED"));
    MonitoringAlert critical =
        alerts.insert(
            new MonitoringAlert(
                UUID.randomUUID(),
                AlertSeverity.CRITICAL,
                AlertType.PAYMENT_JOB_FAILURE,
                "Payment job "
                    + jobId
                    + " failed after "
                    + maxRetries
                    + " auto-retries. Human intervention required.",
                "payment_job_retries",
                BigDecimal.valueOf(maxRetries),
                BigDecimal.valueOf(maxRetries),
                jobId,
                now,
                false,
                null,
                null,
                null,
                false,
                null,
                null));
    alerts.markAutoRemediated(critical.id(), true);
    List<UUID> targets = admins.onlineAdminIds(SUPER_ROLES);
    notify.pageCritical(critical.id(), AlertType.PAYMENT_JOB_FAILURE.name(), targets);
  }

  private void processApiErrorRate(Instant now) {
    Optional<RemediationPlaybook> pbOpt = playbooks.findByAlertType(AlertType.API_ERROR_RATE_HIGH);
    if (pbOpt.isEmpty()) {
      return;
    }
    if (!pbOpt.get().enabled()) {
      return;
    }
    RemediationPlaybook pb = pbOpt.get();
    BigDecimal rate = number(pb.threshold(), "error_rate_pct", 5);
    int window = intVal(pb.threshold(), "window_minutes", 5);
    for (ApiErrorRatePort.HotEndpoint ep : apiErrors.endpointsAbove(rate, window)) {
      Optional<MonitoringAlert> open =
          alerts.findOpen(AlertType.API_ERROR_RATE_HIGH, ep.syntheticEntityId());
      if (open.isEmpty()) {
        MonitoringAlert created =
            alerts.insert(
                new MonitoringAlert(
                    UUID.randomUUID(),
                    AlertSeverity.CRITICAL,
                    AlertType.API_ERROR_RATE_HIGH,
                    "API error rate "
                        + ep.errorRatePct()
                        + "% on "
                        + ep.endpoint()
                        + " exceeds "
                        + rate
                        + "%.",
                    "api_error_rate_pct",
                    ep.errorRatePct(),
                    rate,
                    ep.syntheticEntityId(),
                    now,
                    false,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null));
        executeAuto(
            pb,
            created,
            ep.syntheticEntityId(),
            "API_ENDPOINT",
            now,
            Map.of("endpoint", ep.endpoint()));
        continue;
      }
      if (open.get().autoRemediated()) {
        continue;
      }
      executeAuto(
          pb,
          open.get(),
          ep.syntheticEntityId(),
          "API_ENDPOINT",
          now,
          Map.of("endpoint", ep.endpoint()));
    }
  }

  private RemediationStatus executeAuto(
      RemediationPlaybook pb,
      MonitoringAlert alert,
      UUID targetId,
      String entityType,
      Instant now,
      Map<String, Object> extra) {
    UUID id = UUID.randomUUID();
    RemediationLogEntry initiated =
        logs.insert(
            new RemediationLogEntry(
                id,
                alert.id(),
                pb.id(),
                pb.autoRemediationAction(),
                RemediationTriggerType.AUTO,
                entityType,
                targetId,
                Map.copyOf(extra),
                RemediationStatus.INITIATED,
                null,
                now,
                null,
                null));
    RemediationStatus status = executeAndComplete(initiated, pb, extra);
    if (status == RemediationStatus.SUCCESS) {
      alerts.markAutoRemediated(alert.id(), true);
      playbooks.touchLastTriggered(pb.id(), now);
    }
    return status;
  }

  private RemediationStatus executeAndComplete(
      RemediationLogEntry entry, RemediationPlaybook pb, Map<String, Object> extra) {
    Instant now = Instant.now(clock);
    try {
      Map<String, Object> details = new LinkedHashMap<>(entry.actionDetails());
      details.putAll(extra);
      RemediationActionType action = entry.actionType();
      if (action == RemediationActionType.REQUEST_RIDERS) {
        int max = 3;
        int cooldown = 2;
        if (pb != null) {
          max = intVal(pb.threshold(), "max_notifications_per_rider", 3);
          cooldown = intVal(pb.threshold(), "notification_cooldown_hours", 2);
        }
        RiderNotifyPort.NotifyResult r =
            riders.notifyOfflineRiders(entry.targetEntityId(), max, cooldown);
        details.put("riders_notified", r.ridersNotified());
        details.put("notifications_sent", r.notificationsSent());
        details.put("target_entity_name", r.zoneName());
      } else if (action == RemediationActionType.THROTTLE_PHARMACY) {
        int throttlePct =
            extra.containsKey("throttle_pct")
                ? ((Number) extra.get("throttle_pct")).intValue()
                : 30;
        PharmacyThrottlePort.ThrottleResult r =
            pharmacies
                .throttleByPercent(entry.targetEntityId(), throttlePct)
                .orElseThrow(
                    () -> new AppException("ENTITY_NOT_FOUND", "target_entity_id not found", 400));
        details.put("previous_order_cap", r.previousCap());
        details.put("new_order_cap", r.newCap());
        details.put("throttle_reason", "fill_rate below threshold");
        details.put("target_entity_name", r.pharmacyName());
      } else if (action == RemediationActionType.RETRY_PAYMENT_JOB) {
        boolean ok = payments.retry(entry.targetEntityId());
        details.put("retry_succeeded", ok);
        if (!ok) {
          logs.complete(
              entry.id(), RemediationStatus.FAILED, details, now, "Payment job retry failed");
          return RemediationStatus.FAILED;
        }
      } else if (action == RemediationActionType.PAGE_ON_CALL) {
        List<UUID> targets = admins.onlineAdminIds(SUPER_ROLES);
        notify.pageCritical(entry.alertId(), AlertType.API_ERROR_RATE_HIGH.name(), targets);
        details.put("admins_paged", targets.size());
        details.put("admin_ids", targets.stream().map(UUID::toString).toList());
      } else {
        // CLEAR_CACHE / PAUSE_PROMOTION
        details.put("noop", true);
      }
      logs.complete(entry.id(), RemediationStatus.SUCCESS, details, now, null);
      return RemediationStatus.SUCCESS;
    } catch (RuntimeException ex) {
      logs.complete(
          entry.id(), RemediationStatus.FAILED, entry.actionDetails(), now, ex.getMessage());
      throw ex;
    }
  }

  private UUID resolveTarget(MonitoringAlert alert, RemediationActionType action) {
    return alert.zoneId();
  }

  private void ensureEntityExists(RemediationActionType action, UUID id) {
    boolean ok =
        switch (action) {
          case REQUEST_RIDERS -> riders.zoneExists(id);
          case THROTTLE_PHARMACY -> pharmacies.pharmacyExists(id);
          case RETRY_PAYMENT_JOB -> payments.jobExists(id);
          case PAGE_ON_CALL, CLEAR_CACHE, PAUSE_PROMOTION -> true;
        };
    if (!ok) {
      throw new AppException("ENTITY_NOT_FOUND", "target_entity_id not found", 400);
    }
  }

  private static String defaultEntityType(RemediationActionType action) {
    if (action == RemediationActionType.REQUEST_RIDERS) {
      return "ZONE";
    }
    if (action == RemediationActionType.THROTTLE_PHARMACY) {
      return "PHARMACY";
    }
    if (action == RemediationActionType.RETRY_PAYMENT_JOB) {
      return "PAYMENT_JOB";
    }
    if (action == RemediationActionType.PAGE_ON_CALL) {
      return "API_ENDPOINT";
    }
    if (action == RemediationActionType.CLEAR_CACHE) {
      return "CACHE";
    }
    return "PROMOTION";
  }

  private void validateThresholdPatch(Map<String, Object> patch) {
    for (Map.Entry<String, Object> e : patch.entrySet()) {
      if (!(e.getValue() instanceof Number n)) {
        throw new AppException("INVALID_THRESHOLD", "Threshold value out of allowed range", 422);
      }
      double v = n.doubleValue();
      // ponytail: single broad range; story keys share 1..180 envelope
      if (v < 1 || v > 180) {
        throw new AppException("INVALID_THRESHOLD", "Threshold value out of allowed range", 422);
      }
    }
  }

  private Map<String, Object> auditSnapshot(RemediationPlaybook pb) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("is_enabled", pb.enabled());
    m.put("threshold", pb.threshold());
    return m;
  }

  private Map<String, Object> toPlaybookDto(RemediationPlaybook pb) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", pb.id());
    m.put("alert_type", pb.alertType().name());
    m.put("auto_remediation_action", pb.autoRemediationAction().name());
    m.put("description", pb.description());
    m.put("threshold", pb.threshold());
    m.put("is_enabled", pb.enabled());
    m.put(
        "last_triggered_at", pb.lastTriggeredAt() == null ? null : pb.lastTriggeredAt().toString());
    return m;
  }

  private Map<String, Object> toActionDto(RemediationLogEntry e) {
    Map<String, Object> details = new LinkedHashMap<>(e.actionDetails());
    Object name = details.remove("target_entity_name");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", e.id());
    m.put("alert_id", e.alertId());
    m.put("action_type", e.actionType().name());
    m.put("trigger_type", e.triggerType().name());
    m.put("target_entity_type", e.targetEntityType());
    m.put("target_entity_id", e.targetEntityId());
    m.put("target_entity_name", name);
    m.put("action_details", details);
    m.put("status", e.status().name());
    m.put("triggered_at", e.triggeredAt().toString());
    m.put("completed_at", e.completedAt() == null ? null : e.completedAt().toString());
    m.put("triggered_by", e.triggeredBy() == null ? "SYSTEM" : e.triggeredBy().toString());
    return m;
  }

  private static RemediationActionType parseActionRequired(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_ACTION_TYPE", "action_type not in allowed set", 400);
    }
    try {
      return RemediationActionType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_ACTION_TYPE", "action_type not in allowed set", 400);
    }
  }

  private static RemediationActionType parseActionOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return RemediationActionType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_ACTION_TYPE", "action_type not in allowed set", 400);
    }
  }

  private static RemediationStatus parseStatusOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return RemediationStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_ACTION_TYPE", "status not in allowed set", 400);
    }
  }

  private static Instant parseInstantOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return Instant.parse(raw);
  }

  private static BigDecimal number(Map<String, Object> threshold, String key, double def) {
    Object v = threshold.get(key);
    if (v instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    return BigDecimal.valueOf(def);
  }

  private static int intVal(Map<String, Object> threshold, String key, int def) {
    Object v = threshold.get(key);
    if (v instanceof Number n) {
      return n.intValue();
    }
    return def;
  }

  private static void requireOps(MedmatePrincipal principal) {
    if (principal == null || !OPS.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
