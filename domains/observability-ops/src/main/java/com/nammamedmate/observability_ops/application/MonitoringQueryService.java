package com.nammamedmate.observability_ops.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.observability_ops.application.port.out.MetricSampleStore;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort.ZoneRiderSnapshot;
import com.nammamedmate.observability_ops.application.port.out.MonitoringAlertStore;
import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.AlertListStatus;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.ErrorBudget;
import com.nammamedmate.observability_ops.domain.MetricSample;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.SloDefinition;
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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class MonitoringQueryService {

  private static final Set<Integer> PERIODS = Set.of(60, 180, 360, 1440);
  private static final Set<String> METRICS =
      Set.of(
          "gmv",
          "order_count",
          "dispatch_rate",
          "sla_pct",
          "payment_success_pct",
          "rider_online_count");
  private static final Set<String> FINANCE_METRICS = Set.of("gmv", "payment_success_pct");
  private static final Set<AuthRole> ALERT_READERS =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_SUPPORT);
  private static final int PAGE_LIMIT = 20;

  private final MetricSourcePort source;
  private final MetricSampleStore samples;
  private final MonitoringAlertStore alerts;
  private final SloStore sloStore;
  private final Clock clock;

  public MonitoringQueryService(
      MetricSourcePort source,
      MetricSampleStore samples,
      MonitoringAlertStore alerts,
      SloStore sloStore,
      Clock clock) {
    this.source = source;
    this.samples = samples;
    this.alerts = alerts;
    this.sloStore = sloStore;
    this.clock = clock;
  }

  public Map<String, Object> realtime(MedmatePrincipal principal) {
    requireOps(principal);
    try {
      Instant now = Instant.now(clock);
      Instant latest = samples.latestBucketTs().orElse(null);
      long age = latest == null ? Long.MAX_VALUE : ChronoUnit.SECONDS.between(latest, now);
      Map<String, Object> zoneCoverage = zoneCoverage();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("gmv_last_hour_paise", source.gmvLastHourPaise());
      data.put("orders_per_minute", source.ordersPerMinute());
      data.put("dispatch_success_rate_pct", source.dispatchSuccessRatePct());
      data.put("sla_adherence_pct", source.slaAdherencePctLastHour());
      data.put("payment_success_rate_pct", source.paymentSuccessRatePct15m());
      data.put("zone_coverage", zoneCoverage);
      data.put("active_automations", source.activeAutomations());
      data.put("pending_approvals", source.pendingApprovals());
      data.put("as_of", latest == null ? now.toString() : latest.toString());
      data.put("data_age_seconds", age == Long.MAX_VALUE ? age : Math.max(0, age));
      return data;
    } catch (DataAccessException ex) {
      throw new AppException("METRICS_UNAVAILABLE", "Metrics store unreachable", 503);
    }
  }

  public record AlertsPage(Map<String, Object> data, PaginationMeta meta) {
    public AlertsPage {
      data = Map.copyOf(data);
    }
  }

  public AlertsPage alerts(
      MedmatePrincipal principal, String statusRaw, String severityRaw, Integer pageRaw) {
    requireAlertReader(principal);
    AlertListStatus status = AlertListStatus.from(statusRaw);
    AlertSeverity severity = null;
    if (severityRaw != null && !severityRaw.isBlank()) {
      try {
        severity = AlertSeverity.valueOf(severityRaw.trim().toUpperCase());
      } catch (IllegalArgumentException ignored) {
        severity = null;
      }
    }
    int page = pageRaw == null || pageRaw < 1 ? 1 : pageRaw;
    MonitoringAlertStore.Page result = alerts.list(status, severity, page, PAGE_LIMIT);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (MonitoringAlert a : result.alerts()) {
      rows.add(toAlertRow(a));
    }
    Map<String, Object> data = Map.of("alerts", rows);
    return new AlertsPage(data, PaginationMeta.of(page, PAGE_LIMIT, result.total()));
  }

  public Map<String, Object> acknowledge(MedmatePrincipal principal, UUID alertId, String notes) {
    requireOps(principal);
    MonitoringAlert alert =
        alerts
            .findById(alertId)
            .orElseThrow(() -> new AppException("ALERT_NOT_FOUND", "Alert ID not found", 404));
    if (alert.acknowledged()) {
      throw new AppException("ALREADY_ACKNOWLEDGED", "Alert already acknowledged", 409);
    }
    Instant at = Instant.now(clock);
    alerts.acknowledge(alertId, principal.subject(), at, notes);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("alert_id", alertId.toString());
    data.put("acknowledged", true);
    data.put("acknowledged_by", principal.subject().toString());
    data.put("acknowledged_at", at.toString());
    return data;
  }

  public Map<String, Object> metrics(
      MedmatePrincipal principal, String metricName, Integer periodMinutes) {
    requireMetricsAccess(principal, metricName);
    if (metricName == null || !METRICS.contains(metricName)) {
      throw new AppException("INVALID_METRIC", "metric_name not in supported set", 400);
    }
    int period = periodMinutes == null ? 60 : periodMinutes;
    if (!PERIODS.contains(period)) {
      throw new AppException("INVALID_PERIOD", "period_minutes not in allowed set", 400);
    }
    try {
      Instant end = Instant.now(clock).truncatedTo(ChronoUnit.MINUTES);
      Instant start = end.minus(period, ChronoUnit.MINUTES);
      List<MetricSample> stored =
          samples.series(metricName, start, end.plus(1, ChronoUnit.MINUTES));
      Map<Instant, BigDecimal> byTs = new LinkedHashMap<>();
      for (MetricSample s : stored) {
        byTs.putIfAbsent(s.bucketTs(), s.value());
      }
      List<Map<String, Object>> points = new ArrayList<>(period);
      BigDecimal current = null;
      for (int i = 0; i < period; i++) {
        Instant ts = start.plus(i, ChronoUnit.MINUTES);
        BigDecimal value = byTs.get(ts);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("timestamp", ts.toString());
        point.put("value", value);
        points.add(point);
        if (value != null) {
          current = value;
        }
      }
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("metric_name", metricName);
      data.put("period_minutes", period);
      data.put("data_points", points);
      data.put("current_value", current);
      data.put(
          "slo_target",
          sloStore.byMetricName(metricName).map(SloDefinition::targetPct).orElse(null));
      return data;
    } catch (DataAccessException ex) {
      throw new AppException("METRICS_UNAVAILABLE", "Metrics store unreachable", 503);
    }
  }

  public Map<String, Object> slo(MedmatePrincipal principal) {
    requireOps(principal);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SloDefinition def : sloStore.allDefinitions()) {
      BigDecimal current = currentPct(def);
      BigDecimal remaining = ErrorBudget.remainingPct(def.targetPct(), current);
      boolean compliant = current != null && current.compareTo(def.targetPct()) >= 0;
      String trend = trend(def.sloName(), current);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("slo_name", def.sloName());
      row.put("description", def.description());
      row.put("target_pct", def.targetPct());
      row.put("current_pct_30d", current);
      row.put("error_budget_remaining_pct", remaining);
      row.put("last_30d_pct", current);
      row.put("trend", trend);
      row.put("compliant", compliant);
      rows.add(row);
    }
    return Map.of("slos", rows);
  }

  private BigDecimal currentPct(SloDefinition def) {
    return switch (def.sloName()) {
      case "order_sla_adherence" -> source.orderSlaPct30d();
      case "payment_success" -> source.paymentSuccessPct30d();
      case "dispatch_success" -> source.dispatchSuccessPct30d();
      case "api_p99_latency" -> source.apiP99CompliancePct30d();
      default -> null;
    };
  }

  private String trend(String sloName, BigDecimal current) {
    Optional<BigDecimal> prev = sloStore.previousActualPct(sloName);
    if (current == null || prev.isEmpty()) {
      return "STABLE";
    }
    if (current.compareTo(prev.get().subtract(BigDecimal.ONE)) < 0) {
      return "DEGRADING";
    }
    return "STABLE";
  }

  private Map<String, Object> zoneCoverage() {
    int healthy = 0;
    int stretched = 0;
    int dark = 0;
    for (ZoneRiderSnapshot z : source.zoneRiders()) {
      if (z.ridersOnline() <= 0) {
        dark++;
      } else if (z.ridersOnline() < z.demandThreshold()) {
        stretched++;
      } else {
        healthy++;
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("healthy", healthy);
    m.put("stretched", stretched);
    m.put("dark", dark);
    return m;
  }

  private static Map<String, Object> toAlertRow(MonitoringAlert a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", a.id().toString());
    m.put("severity", a.severity().name());
    m.put("type", a.type().name());
    m.put("message", a.message());
    m.put("triggered_at", a.triggeredAt().toString());
    m.put("acknowledged", a.acknowledged());
    m.put("acknowledged_by", a.acknowledgedBy() == null ? null : a.acknowledgedBy().toString());
    m.put("acknowledged_at", a.acknowledgedAt() == null ? null : a.acknowledgedAt().toString());
    m.put("auto_remediated", a.autoRemediated());
    m.put("resolved_at", a.resolvedAt() == null ? null : a.resolvedAt().toString());
    return m;
  }

  static void requireOps(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  static void requireAlertReader(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    if (!ALERT_READERS.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  static void requireMetricsAccess(MedmatePrincipal principal, String metricName) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    AuthRole role = principal.role();
    if (role == AuthRole.ADMIN_SUPER || role == AuthRole.ADMIN_OPERATIONS) {
      return;
    }
    if (role == AuthRole.ADMIN_FINANCE) {
      if (metricName == null || !FINANCE_METRICS.contains(metricName)) {
        throw new AppException("FORBIDDEN", "Role cannot access this metric", 403);
      }
      return;
    }
    throw new AppException("FORBIDDEN", "Insufficient role", 403);
  }
}
