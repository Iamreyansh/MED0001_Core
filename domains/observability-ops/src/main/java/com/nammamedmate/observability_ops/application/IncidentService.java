package com.nammamedmate.observability_ops.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.application.port.out.IncidentNumberPort;
import com.nammamedmate.observability_ops.application.port.out.IncidentStore;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort;
import com.nammamedmate.observability_ops.application.port.out.MonitoringAlertStore;
import com.nammamedmate.observability_ops.application.port.out.NotificationDispatchPort;
import com.nammamedmate.observability_ops.application.port.out.OnlineAdminDirectoryPort;
import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.AffectedService;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.ErrorBudget;
import com.nammamedmate.observability_ops.domain.Incident;
import com.nammamedmate.observability_ops.domain.IncidentSeverity;
import com.nammamedmate.observability_ops.domain.IncidentStatus;
import com.nammamedmate.observability_ops.domain.IncidentStatusEntry;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.SloComplianceRecord;
import com.nammamedmate.observability_ops.domain.SloDefinition;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Set<AuthRole> WRITERS =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Set<AuthRole> READERS =
      Set.of(
          AuthRole.ADMIN_SUPER,
          AuthRole.ADMIN_OPERATIONS,
          AuthRole.ADMIN_SUPPORT,
          AuthRole.ADMIN_FINANCE);
  private static final Set<String> OPS_ROLES = Set.of("admin_super", "admin_operations");
  private static final Set<String> SUPER_ROLES = Set.of("admin_super");
  private static final int PAGE_LIMIT = 20;
  private static final long CRITICAL_AUTO_MINUTES = 15;
  private static final long HIGH_AUTO_MINUTES = 30;
  private static final long POSTMORTEM_HOURS = 48;
  private static final long REMINDER_HOURS = 24;

  private final IncidentStore incidents;
  private final IncidentNumberPort numbers;
  private final MonitoringAlertStore alerts;
  private final MetricSourcePort metrics;
  private final NotificationDispatchPort notify;
  private final OnlineAdminDirectoryPort admins;
  private final SloStore sloStore;
  private final Clock clock;

  public IncidentService(
      IncidentStore incidents,
      IncidentNumberPort numbers,
      MonitoringAlertStore alerts,
      MetricSourcePort metrics,
      NotificationDispatchPort notify,
      OnlineAdminDirectoryPort admins,
      SloStore sloStore,
      Clock clock) {
    this.incidents = incidents;
    this.numbers = numbers;
    this.alerts = alerts;
    this.metrics = metrics;
    this.notify = notify;
    this.admins = admins;
    this.sloStore = sloStore;
    this.clock = clock;
  }

  public record IncidentsPage(Map<String, Object> data, PaginationMeta meta) {
    public IncidentsPage {
      data = Map.copyOf(data);
    }
  }

  public IncidentsPage list(
      MedmatePrincipal principal,
      String statusRaw,
      String severityRaw,
      String dateFrom,
      String dateTo,
      Integer page) {
    requireReader(principal);
    int p = page == null || page < 1 ? 1 : page;
    IncidentStatus status = parseStatusOptional(statusRaw);
    IncidentSeverity severity = parseSeverityOptional(severityRaw);
    Instant from = parseInstantOptional(dateFrom);
    Instant to = parseInstantOptional(dateTo);
    IncidentStore.Page result = incidents.list(status, severity, from, to, p, PAGE_LIMIT);
    List<Map<String, Object>> rows = new ArrayList<>();
    Instant now = Instant.now(clock);
    for (Incident i : result.incidents()) {
      rows.add(toListDto(i, now));
    }
    return new IncidentsPage(
        Map.of("incidents", rows), PaginationMeta.of(p, PAGE_LIMIT, result.total()));
  }

  @Transactional
  public Map<String, Object> declare(
      MedmatePrincipal principal,
      String title,
      String severityRaw,
      String description,
      List<String> affectedServicesRaw,
      Map<String, Object> impactedMetrics) {
    requireWriter(principal);
    IncidentSeverity severity = parseSeverityRequired(severityRaw);
    List<AffectedService> services = parseServices(affectedServicesRaw);
    if (title == null || title.isBlank() || description == null || description.isBlank()) {
      throw new AppException("MISSING_REQUIRED_FIELDS", "title and description required", 400);
    }
    Instant now = Instant.now(clock);
    return toCreateDto(
        createIncident(
            title.trim(),
            severity,
            description.trim(),
            services,
            impactedMetrics == null ? Map.of() : impactedMetrics,
            metrics.gmvLastHourPaise(),
            principal.subject(),
            null,
            now));
  }

  @Transactional
  public Map<String, Object> patchStatus(
      MedmatePrincipal principal, UUID id, String statusRaw, String updateMessage) {
    requireWriter(principal);
    Incident existing =
        incidents
            .findById(id)
            .orElseThrow(() -> new AppException("INCIDENT_NOT_FOUND", "Incident not found", 404));
    if (existing.isResolved()) {
      throw new AppException("INCIDENT_ALREADY_RESOLVED", "Incident already resolved", 409);
    }
    IncidentStatus next = parseStatusForPatch(statusRaw);
    if (!existing.status().canTransitionTo(next)) {
      throw new AppException("INVALID_STATUS_TRANSITION", "Status can only move forward", 422);
    }
    Instant now = Instant.now(clock);
    String actor = principal.subject().toString();
    List<IncidentStatusEntry> history = new ArrayList<>(existing.statusHistory());
    history.add(
        new IncidentStatusEntry(next, actor, updateMessage == null ? "" : updateMessage, now));
    Incident updated =
        new Incident(
            existing.id(),
            existing.incidentNumber(),
            existing.title(),
            existing.severity(),
            existing.description(),
            next,
            existing.affectedServices(),
            existing.impactedMetrics(),
            existing.impactedGmvPaise(),
            existing.rootCause(),
            existing.fixApplied(),
            existing.preventionSteps(),
            existing.postmortemFiled(),
            existing.postmortemDeadline(),
            existing.postmortemReminderSentAt(),
            existing.detectedAt(),
            existing.resolvedAt(),
            existing.durationMinutes(),
            existing.createdBy(),
            existing.sourceAlertId(),
            history);
    incidents.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("incident_number", updated.incidentNumber());
    data.put("previous_status", existing.status().name());
    data.put("new_status", next.name());
    data.put("updated_by", actor);
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> resolve(
      MedmatePrincipal principal,
      UUID id,
      String rootCause,
      String fixApplied,
      String preventionSteps) {
    requireWriter(principal);
    Incident existing =
        incidents
            .findById(id)
            .orElseThrow(() -> new AppException("INCIDENT_NOT_FOUND", "Incident not found", 404));
    if (existing.isResolved()) {
      throw new AppException("INCIDENT_ALREADY_RESOLVED", "Incident already resolved", 409);
    }
    if (blank(rootCause) || blank(fixApplied) || blank(preventionSteps)) {
      throw new AppException(
          "MISSING_REQUIRED_FIELDS", "root_cause, fix_applied, prevention_steps required", 400);
    }
    Instant now = Instant.now(clock);
    int duration = (int) Math.max(0, ChronoUnit.MINUTES.between(existing.detectedAt(), now));
    long actualGmv =
        BigDecimal.valueOf(metrics.gmvLastHourPaise())
            .multiply(BigDecimal.valueOf(duration))
            .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP)
            .longValue();
    Instant deadline =
        existing.postmortemRequired() ? now.plus(POSTMORTEM_HOURS, ChronoUnit.HOURS) : null;
    String actor = principal.subject().toString();
    List<IncidentStatusEntry> history = new ArrayList<>(existing.statusHistory());
    history.add(new IncidentStatusEntry(IncidentStatus.RESOLVED, actor, "Resolved", now));
    Incident updated =
        new Incident(
            existing.id(),
            existing.incidentNumber(),
            existing.title(),
            existing.severity(),
            existing.description(),
            IncidentStatus.RESOLVED,
            existing.affectedServices(),
            existing.impactedMetrics(),
            actualGmv,
            rootCause.trim(),
            fixApplied.trim(),
            preventionSteps.trim(),
            existing.postmortemFiled(),
            deadline,
            existing.postmortemReminderSentAt(),
            existing.detectedAt(),
            now,
            duration,
            existing.createdBy(),
            existing.sourceAlertId(),
            history);
    incidents.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("incident_number", updated.incidentNumber());
    data.put("status", IncidentStatus.RESOLVED.name());
    data.put("resolved_at", now.toString());
    data.put("duration_minutes", duration);
    data.put("actual_impacted_gmv_rs", paiseToRs(actualGmv));
    data.put("postmortem_required", updated.postmortemRequired());
    data.put("postmortem_deadline", deadline == null ? null : deadline.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> filePostmortem(MedmatePrincipal principal, UUID id) {
    requireWriter(principal);
    Incident existing =
        incidents
            .findById(id)
            .orElseThrow(() -> new AppException("INCIDENT_NOT_FOUND", "Incident not found", 404));
    Incident updated =
        new Incident(
            existing.id(),
            existing.incidentNumber(),
            existing.title(),
            existing.severity(),
            existing.description(),
            existing.status(),
            existing.affectedServices(),
            existing.impactedMetrics(),
            existing.impactedGmvPaise(),
            existing.rootCause(),
            existing.fixApplied(),
            existing.preventionSteps(),
            true,
            existing.postmortemDeadline(),
            existing.postmortemReminderSentAt(),
            existing.detectedAt(),
            existing.resolvedAt(),
            existing.durationMinutes(),
            existing.createdBy(),
            existing.sourceAlertId(),
            existing.statusHistory());
    incidents.update(updated);
    return Map.of(
        "id", updated.id().toString(),
        "incident_number", updated.incidentNumber(),
        "postmortem_filed", true);
  }

  public Map<String, Object> sloHistory(
      MedmatePrincipal principal, String sloName, String periodFrom, String periodTo) {
    MonitoringQueryService.requireOps(principal);
    LocalDate from = parseDateOptional(periodFrom);
    LocalDate to = parseDateOptional(periodTo);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SloComplianceRecord r : sloStore.listHistory(sloName, from, to)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("slo_name", r.sloName());
      m.put("period_from", r.periodFrom().toString());
      m.put("period_to", r.periodTo().toString());
      m.put("target_pct", r.targetPct());
      m.put("actual_pct", r.actualPct());
      m.put("compliant", r.compliant());
      m.put("error_budget_consumed_pct", r.errorBudgetConsumedPct());
      m.put("incident_count", r.incidentCount());
      m.put("recorded_at", r.recordedAt().toString());
      rows.add(m);
    }
    return Map.of("history", rows);
  }

  @Transactional
  public void runAutoCreate() {
    Instant now = Instant.now(clock);
    for (MonitoringAlert alert : alerts.findOpen()) {
      if (alert.acknowledged()) {
        continue;
      }
      if (incidents.findBySourceAlertId(alert.id()).isPresent()) {
        continue;
      }
      long ageMin = ChronoUnit.MINUTES.between(alert.triggeredAt(), now);
      IncidentSeverity severity = null;
      if (alert.severity() == AlertSeverity.CRITICAL && ageMin >= CRITICAL_AUTO_MINUTES) {
        severity = IncidentSeverity.P1;
      } else if (alert.severity() == AlertSeverity.HIGH && ageMin >= HIGH_AUTO_MINUTES) {
        severity = IncidentSeverity.P2;
      }
      if (severity == null) {
        continue;
      }
      createIncident(
          "Auto: " + alert.type().name() + " " + alert.message(),
          severity,
          alert.message(),
          List.of(AffectedService.AUTOMATION_ENGINE),
          Map.of("source_alert_type", alert.type().name()),
          metrics.gmvLastHourPaise(),
          null,
          alert.id(),
          now);
    }
  }

  @Transactional
  public void runPostmortemReminders() {
    Instant cutoff = Instant.now(clock).minus(REMINDER_HOURS, ChronoUnit.HOURS);
    List<UUID> adminIds = admins.onlineAdminIds(SUPER_ROLES);
    for (Incident incident : incidents.findResolvedAwaitingPostmortemReminder(cutoff)) {
      notify.remindPostmortem(incident.id(), adminIds);
      Incident marked =
          new Incident(
              incident.id(),
              incident.incidentNumber(),
              incident.title(),
              incident.severity(),
              incident.description(),
              incident.status(),
              incident.affectedServices(),
              incident.impactedMetrics(),
              incident.impactedGmvPaise(),
              incident.rootCause(),
              incident.fixApplied(),
              incident.preventionSteps(),
              incident.postmortemFiled(),
              incident.postmortemDeadline(),
              Instant.now(clock),
              incident.detectedAt(),
              incident.resolvedAt(),
              incident.durationMinutes(),
              incident.createdBy(),
              incident.sourceAlertId(),
              incident.statusHistory());
      incidents.update(marked);
    }
  }

  @Transactional
  public void runMonthlySloSnapshot() {
    Instant now = Instant.now(clock);
    LocalDate todayIst = LocalDate.ofInstant(now, IST);
    LocalDate periodFrom = todayIst.minusMonths(1).withDayOfMonth(1);
    LocalDate periodTo = periodFrom.withDayOfMonth(periodFrom.lengthOfMonth());
    Instant fromInst = periodFrom.atStartOfDay(IST).toInstant();
    Instant toInst = periodTo.plusDays(1).atStartOfDay(IST).toInstant();
    int incidentCount = incidents.countP1P2Between(fromInst, toInst);
    for (SloDefinition def : sloStore.allDefinitions()) {
      BigDecimal actual = currentPct(def);
      if (actual == null) {
        actual = BigDecimal.ZERO;
      }
      boolean compliant = actual.compareTo(def.targetPct()) >= 0;
      BigDecimal consumed = ErrorBudget.consumedPct(def.targetPct(), actual);
      sloStore.insertHistory(
          new SloComplianceRecord(
              UUID.randomUUID(),
              def.sloName(),
              periodFrom,
              periodTo,
              def.targetPct(),
              actual,
              compliant,
              consumed,
              incidentCount,
              now));
    }
  }

  private Incident createIncident(
      String title,
      IncidentSeverity severity,
      String description,
      List<AffectedService> services,
      Map<String, Object> impactedMetrics,
      long gmvPaise,
      UUID createdBy,
      UUID sourceAlertId,
      Instant now) {
    LocalDate day = LocalDate.ofInstant(now, IST);
    String number = numbers.next(day);
    UUID id = UUID.randomUUID();
    String actor = createdBy == null ? "SYSTEM" : createdBy.toString();
    List<IncidentStatusEntry> history =
        List.of(new IncidentStatusEntry(IncidentStatus.DETECTED, actor, "Declared", now));
    Incident incident =
        new Incident(
            id,
            number,
            title,
            severity,
            description,
            IncidentStatus.DETECTED,
            services,
            impactedMetrics,
            gmvPaise,
            null,
            null,
            null,
            false,
            null,
            null,
            now,
            null,
            null,
            createdBy,
            sourceAlertId,
            history);
    incidents.insert(incident);
    if (severity == IncidentSeverity.P1 || severity == IncidentSeverity.P2) {
      notify.pageIncident(id, severity.name(), admins.onlineAdminIds(OPS_ROLES));
    }
    return incident;
  }

  private BigDecimal currentPct(SloDefinition def) {
    return switch (def.sloName()) {
      case "order_sla_adherence" -> metrics.orderSlaPct30d();
      case "payment_success" -> metrics.paymentSuccessPct30d();
      case "dispatch_success" -> metrics.dispatchSuccessPct30d();
      case "api_p99_latency" -> metrics.apiP99CompliancePct30d();
      default -> null;
    };
  }

  private static Map<String, Object> toCreateDto(Incident i) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", i.id().toString());
    data.put("incident_number", i.incidentNumber());
    data.put("title", i.title());
    data.put("severity", i.severity().name());
    data.put("status", i.status().name());
    data.put("detected_at", i.detectedAt().toString());
    data.put("created_by", i.createdBy().toString());
    return data;
  }

  private static Map<String, Object> toListDto(Incident i, Instant now) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", i.id().toString());
    m.put("incident_number", i.incidentNumber());
    m.put("title", i.title());
    m.put("severity", i.severity().name());
    m.put("status", i.status().name());
    m.put("detected_at", i.detectedAt().toString());
    m.put("resolved_at", i.resolvedAt() == null ? null : i.resolvedAt().toString());
    Integer duration = i.durationMinutes();
    if (duration == null && i.resolvedAt() == null) {
      duration = (int) Math.max(0, ChronoUnit.MINUTES.between(i.detectedAt(), now));
    }
    m.put("duration_minutes", duration);
    m.put("affected_services", i.affectedServices().stream().map(Enum::name).toList());
    m.put("impacted_gmv_rs", paiseToRs(i.impactedGmvPaise()));
    m.put("postmortem_filed", i.postmortemFiled());
    m.put("created_by", i.createdBy() == null ? "SYSTEM" : i.createdBy().toString());
    m.put(
        "status_history",
        i.statusHistory().stream()
            .map(
                e -> {
                  Map<String, Object> h = new LinkedHashMap<>();
                  h.put("status", e.status().name());
                  h.put("updated_by", e.updatedBy());
                  h.put("update_message", e.updateMessage());
                  h.put("updated_at", e.updatedAt().toString());
                  return h;
                })
            .toList());
    return m;
  }

  private static BigDecimal paiseToRs(long paise) {
    return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static IncidentSeverity parseSeverityRequired(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_SEVERITY", "severity not in P1/P2/P3", 400);
    }
    try {
      return IncidentSeverity.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_SEVERITY", "severity not in P1/P2/P3", 400);
    }
  }

  private static IncidentSeverity parseSeverityOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return IncidentSeverity.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_SEVERITY", "severity not in P1/P2/P3", 400);
    }
  }

  private static IncidentStatus parseStatusOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return IncidentStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_STATUS_TRANSITION", "Invalid status", 422);
    }
  }

  private static IncidentStatus parseStatusForPatch(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_STATUS_TRANSITION", "status required", 422);
    }
    try {
      return IncidentStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_STATUS_TRANSITION", "Invalid status transition", 422);
    }
  }

  private static List<AffectedService> parseServices(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      throw new AppException("INVALID_SERVICE", "affected_services required", 422);
    }
    List<AffectedService> out = new ArrayList<>();
    for (String s : raw) {
      if (s == null || s.isBlank()) {
        throw new AppException("INVALID_SERVICE", "Invalid affected service", 422);
      }
      try {
        out.add(AffectedService.valueOf(s.trim().toUpperCase()));
      } catch (IllegalArgumentException ex) {
        throw new AppException("INVALID_SERVICE", "Invalid affected service", 422);
      }
    }
    return out;
  }

  private static Instant parseInstantOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return Instant.parse(raw);
  }

  private static LocalDate parseDateOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return LocalDate.parse(raw);
  }

  private static void requireWriter(MedmatePrincipal principal) {
    if (principal == null || !WRITERS.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireReader(MedmatePrincipal principal) {
    if (principal == null || !READERS.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
