package com.nammamedmate.observability_ops.application;

import com.nammamedmate.observability_ops.application.port.out.MetricSampleStore;
import com.nammamedmate.observability_ops.application.port.out.MonitoringAlertStore;
import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.AlertListStatus;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MetricSample;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import com.nammamedmate.observability_ops.domain.SloComplianceRecord;
import com.nammamedmate.observability_ops.domain.SloDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** In-memory ports for unit tests. */
final class InMemoryMonitoringStores {

  static final class Samples implements MetricSampleStore {
    private final CopyOnWriteArrayList<MetricSample> rows = new CopyOnWriteArrayList<>();

    @Override
    public void upsert(String metricName, Instant bucketTs, BigDecimal value, UUID zoneId) {
      rows.removeIf(
          s ->
              s.metricName().equals(metricName)
                  && s.bucketTs().equals(bucketTs)
                  && Objects.equals(s.zoneId(), zoneId));
      rows.add(new MetricSample(UUID.randomUUID(), metricName, bucketTs, value, zoneId));
    }

    @Override
    public Optional<Instant> latestBucketTs() {
      return rows.stream().map(MetricSample::bucketTs).max(Comparator.naturalOrder());
    }

    @Override
    public List<MetricSample> series(
        String metricName, Instant fromInclusive, Instant toExclusive) {
      return rows.stream()
          .filter(s -> s.metricName().equals(metricName) && s.zoneId() == null)
          .filter(s -> !s.bucketTs().isBefore(fromInclusive) && s.bucketTs().isBefore(toExclusive))
          .sorted(Comparator.comparing(MetricSample::bucketTs))
          .toList();
    }

    @Override
    public int consecutiveZeroBuckets(String metricName, UUID zoneId, Instant asOf, int lookback) {
      List<MetricSample> recent =
          rows.stream()
              .filter(s -> s.metricName().equals(metricName) && Objects.equals(s.zoneId(), zoneId))
              .filter(s -> !s.bucketTs().isAfter(asOf))
              .sorted(Comparator.comparing(MetricSample::bucketTs).reversed())
              .limit(lookback)
              .toList();
      int count = 0;
      for (MetricSample s : recent) {
        if (s.value() == null || s.value().compareTo(BigDecimal.ZERO) != 0) {
          break;
        }
        count++;
      }
      return count;
    }

    @Override
    public List<MetricSample> lastN(String metricName, UUID zoneId, int n) {
      return rows.stream()
          .filter(s -> s.metricName().equals(metricName) && Objects.equals(s.zoneId(), zoneId))
          .sorted(Comparator.comparing(MetricSample::bucketTs).reversed())
          .limit(n)
          .toList();
    }
  }

  static final class Alerts implements MonitoringAlertStore {
    private final Map<UUID, MonitoringAlert> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<MonitoringAlert> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<MonitoringAlert> findOpen(AlertType type, UUID zoneId) {
      return byId.values().stream()
          .filter(a -> a.type() == type && Objects.equals(a.zoneId(), zoneId) && a.isOpen())
          .findFirst();
    }

    @Override
    public List<MonitoringAlert> findOpen() {
      return byId.values().stream().filter(MonitoringAlert::isOpen).toList();
    }

    @Override
    public MonitoringAlert insert(MonitoringAlert alert) {
      byId.put(alert.id(), alert);
      return alert;
    }

    @Override
    public void updateTriggeredAt(UUID id, Instant triggeredAt) {
      MonitoringAlert a = byId.get(id);
      if (a != null) {
        byId.put(
            id,
            new MonitoringAlert(
                a.id(),
                a.severity(),
                a.type(),
                a.message(),
                a.triggeringMetric(),
                a.triggeringValue(),
                a.thresholdValue(),
                a.zoneId(),
                triggeredAt,
                a.acknowledged(),
                a.acknowledgedBy(),
                a.acknowledgedAt(),
                a.acknowledgedNotes(),
                a.autoRemediated(),
                a.resolvedAt(),
                a.resolutionReason()));
      }
    }

    @Override
    public void acknowledge(UUID id, UUID by, Instant at, String notes) {
      MonitoringAlert a = byId.get(id);
      if (a != null) {
        byId.put(
            id,
            new MonitoringAlert(
                a.id(),
                a.severity(),
                a.type(),
                a.message(),
                a.triggeringMetric(),
                a.triggeringValue(),
                a.thresholdValue(),
                a.zoneId(),
                a.triggeredAt(),
                true,
                by,
                at,
                notes,
                a.autoRemediated(),
                a.resolvedAt(),
                a.resolutionReason()));
      }
    }

    @Override
    public void resolve(UUID id, Instant resolvedAt, String reason) {
      MonitoringAlert a = byId.get(id);
      if (a != null) {
        byId.put(
            id,
            new MonitoringAlert(
                a.id(),
                a.severity(),
                a.type(),
                a.message(),
                a.triggeringMetric(),
                a.triggeringValue(),
                a.thresholdValue(),
                a.zoneId(),
                a.triggeredAt(),
                a.acknowledged(),
                a.acknowledgedBy(),
                a.acknowledgedAt(),
                a.acknowledgedNotes(),
                a.autoRemediated(),
                resolvedAt,
                reason));
      }
    }

    @Override
    public void markAutoRemediated(UUID id, boolean value) {
      MonitoringAlert a = byId.get(id);
      if (a != null) {
        byId.put(
            id,
            new MonitoringAlert(
                a.id(),
                a.severity(),
                a.type(),
                a.message(),
                a.triggeringMetric(),
                a.triggeringValue(),
                a.thresholdValue(),
                a.zoneId(),
                a.triggeredAt(),
                a.acknowledged(),
                a.acknowledgedBy(),
                a.acknowledgedAt(),
                a.acknowledgedNotes(),
                value,
                a.resolvedAt(),
                a.resolutionReason()));
      }
    }

    @Override
    public Page list(AlertListStatus status, AlertSeverity severity, int page, int limit) {
      List<MonitoringAlert> filtered =
          byId.values().stream()
              .filter(
                  a ->
                      switch (status) {
                        case ACTIVE -> a.isOpen() && !a.acknowledged();
                        case ACKNOWLEDGED -> a.isOpen() && a.acknowledged();
                        case RESOLVED -> !a.isOpen();
                      })
              .filter(a -> severity == null || a.severity() == severity)
              .sorted(Comparator.comparing(MonitoringAlert::triggeredAt).reversed())
              .collect(Collectors.toCollection(ArrayList::new));
      long total = filtered.size();
      int from = Math.max(0, (page - 1) * limit);
      List<MonitoringAlert> slice =
          from >= filtered.size()
              ? List.of()
              : filtered.subList(from, Math.min(from + limit, filtered.size()));
      return new Page(slice, total);
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
      List<UUID> remove =
          byId.values().stream()
              .filter(a -> a.triggeredAt().isBefore(cutoff))
              .map(MonitoringAlert::id)
              .toList();
      remove.forEach(byId::remove);
      return remove.size();
    }
  }

  static final class Slos implements SloStore {
    private final List<SloDefinition> defs;
    private final Map<String, BigDecimal> previous = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SloComplianceRecord> history = new CopyOnWriteArrayList<>();

    Slos() {
      defs =
          List.of(
              new SloDefinition(
                  "order_sla_adherence",
                  "95% of orders delivered within 45 minutes",
                  new BigDecimal("95.00"),
                  "sla_pct",
                  30),
              new SloDefinition(
                  "payment_success",
                  "99% of payment captures succeed",
                  new BigDecimal("99.00"),
                  "payment_success_pct",
                  30),
              new SloDefinition(
                  "dispatch_success",
                  "98% of orders assigned within 10 minutes",
                  new BigDecimal("98.00"),
                  "dispatch_rate",
                  30),
              new SloDefinition(
                  "api_p99_latency",
                  "API P99 latency < 500ms",
                  new BigDecimal("100.00"),
                  "api_p99_compliance_pct",
                  30));
    }

    @Override
    public List<SloDefinition> allDefinitions() {
      return defs;
    }

    @Override
    public Optional<SloDefinition> byMetricName(String metricName) {
      return defs.stream().filter(d -> d.metricName().equals(metricName)).findFirst();
    }

    @Override
    public Optional<BigDecimal> previousActualPct(String sloName) {
      return Optional.ofNullable(previous.get(sloName));
    }

    void putPrevious(String sloName, BigDecimal pct) {
      previous.put(sloName, pct);
    }

    @Override
    public void insertHistory(SloComplianceRecord record) {
      history.add(record);
    }

    @Override
    public List<SloComplianceRecord> listHistory(
        String sloName, LocalDate periodFrom, LocalDate periodTo) {
      return history.stream()
          .filter(r -> sloName == null || sloName.isBlank() || r.sloName().equals(sloName))
          .filter(r -> periodFrom == null || !r.periodFrom().isBefore(periodFrom))
          .filter(r -> periodTo == null || !r.periodTo().isAfter(periodTo))
          .toList();
    }
  }

  private InMemoryMonitoringStores() {}
}
