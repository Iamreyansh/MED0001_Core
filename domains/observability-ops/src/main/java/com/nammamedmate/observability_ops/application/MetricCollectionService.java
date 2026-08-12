package com.nammamedmate.observability_ops.application;

import com.nammamedmate.observability_ops.application.port.out.MetricSampleStore;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort.ZoneRiderSnapshot;
import com.nammamedmate.observability_ops.application.port.out.MonitoringAlertStore;
import com.nammamedmate.observability_ops.application.port.out.NotificationDispatchPort;
import com.nammamedmate.observability_ops.application.port.out.OnlineAdminDirectoryPort;
import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.AlertCandidate;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetricCollectionService {

  private static final int HEALTHY_INTERVALS_TO_RESOLVE = 2;
  private static final Set<String> PAGE_ROLES = Set.of("admin_super", "admin_operations");

  private final MetricSourcePort source;
  private final MetricSampleStore samples;
  private final MonitoringAlertStore alerts;
  private final SloStore sloStore;
  private final NotificationDispatchPort notify;
  private final OnlineAdminDirectoryPort admins;
  private final Clock clock;
  private final Map<String, Integer> healthyStreak = new ConcurrentHashMap<>();

  public MetricCollectionService(
      MetricSourcePort source,
      MetricSampleStore samples,
      MonitoringAlertStore alerts,
      SloStore sloStore,
      NotificationDispatchPort notify,
      OnlineAdminDirectoryPort admins,
      Clock clock) {
    this.source = source;
    this.samples = samples;
    this.alerts = alerts;
    this.sloStore = sloStore;
    this.notify = notify;
    this.admins = admins;
    this.clock = clock;
  }

  @Transactional
  public void collectAndEvaluate() {
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MINUTES);
    writeSamples(now);
    List<AlertCandidate> candidates = AlertEvaluator.evaluate(source, samples, sloStore, now);
    for (AlertCandidate c : candidates) {
      apply(c, now);
    }
  }

  public int purgeOlderThanDays(int days) {
    Instant cutoff = Instant.now(clock).minus(days, ChronoUnit.DAYS);
    return alerts.purgeOlderThan(cutoff);
  }

  private void writeSamples(Instant bucket) {
    // ponytail: ceiling = single Postgres; upgrade → TimescaleDB hypertable
    samples.upsert("gmv", bucket, BigDecimal.valueOf(source.gmvLastHourPaise()), null);
    samples.upsert("order_count", bucket, BigDecimal.valueOf(source.ordersPerMinute()), null);
    samples.upsert("dispatch_rate", bucket, source.dispatchSuccessRatePct(), null);
    samples.upsert("sla_pct", bucket, source.slaAdherencePctLastHour(), null);
    samples.upsert("payment_success_pct", bucket, source.paymentSuccessRatePct15m(), null);
    samples.upsert(
        "payout_volume", bucket, BigDecimal.valueOf(source.payoutVolumeLastHourPaise()), null);
    for (ZoneRiderSnapshot z : source.zoneRiders()) {
      samples.upsert(
          "rider_online_count", bucket, BigDecimal.valueOf(z.ridersOnline()), z.zoneId());
    }
  }

  private void apply(AlertCandidate c, Instant now) {
    String key = streakKey(c.type(), c.zoneId());
    if (c.healthy()) {
      int streak = healthyStreak.merge(key, 1, Integer::sum);
      Optional<MonitoringAlert> open = alerts.findOpen(c.type(), c.zoneId());
      if (open.isPresent() && streak >= HEALTHY_INTERVALS_TO_RESOLVE) {
        alerts.resolve(open.get().id(), now, "AUTO_RESOLVED");
        healthyStreak.remove(key);
      }
      return;
    }
    healthyStreak.put(key, 0);
    Optional<MonitoringAlert> existing = alerts.findOpen(c.type(), c.zoneId());
    if (existing.isPresent()) {
      alerts.updateTriggeredAt(existing.get().id(), now);
      return;
    }
    MonitoringAlert created =
        alerts.insert(
            new MonitoringAlert(
                UUID.randomUUID(),
                c.severity(),
                c.type(),
                c.message(),
                c.triggeringMetric(),
                c.triggeringValue(),
                c.thresholdValue(),
                c.zoneId(),
                now,
                false,
                null,
                null,
                null,
                false,
                null,
                null));
    if (c.severity() == AlertSeverity.CRITICAL) {
      List<UUID> targets = admins.onlineAdminIds(PAGE_ROLES);
      notify.pageCritical(created.id(), c.type().name(), targets);
    }
  }

  private static String streakKey(AlertType type, UUID zoneId) {
    return type.name() + ":" + (zoneId == null ? "_" : zoneId);
  }
}
