package com.nammamedmate.observability_ops.application;

import com.nammamedmate.observability_ops.application.port.out.MetricSampleStore;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort;
import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort.ZoneRiderSnapshot;
import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.AlertCandidate;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.ErrorBudget;
import com.nammamedmate.observability_ops.domain.SloDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class AlertEvaluator {

  private static final BigDecimal GMV_DROP_RATIO = new BigDecimal("0.50");
  private static final BigDecimal PAYOUT_SPIKE_FACTOR = new BigDecimal("3");
  private static final BigDecimal PAYMENT_HIGH = new BigDecimal("95");
  private static final BigDecimal PAYMENT_CRITICAL = new BigDecimal("90");
  private static final BigDecimal SLA_BREACH = new BigDecimal("80");
  private static final int ZONE_DARK_MINUTES = 30;
  private static final int PAYMENT_MIN_ATTEMPTS = 20;

  private AlertEvaluator() {}

  static List<AlertCandidate> evaluate(
      MetricSourcePort source, MetricSampleStore samples, SloStore sloStore, Instant now) {
    List<AlertCandidate> out = new ArrayList<>();
    evaluateGmv(source, out);
    evaluateZones(source, samples, now, out);
    evaluatePayout(source, out);
    evaluatePayment(source, out);
    evaluateSla(source, out);
    evaluateSloBudgets(source, sloStore, out);
    return out;
  }

  private static void evaluateGmv(MetricSourcePort source, List<AlertCandidate> out) {
    long current = source.gmvCurrentHourPaise();
    long avg = source.gmvSameHourDowAvgPaise();
    if (avg <= 0) {
      return;
    }
    BigDecimal threshold =
        BigDecimal.valueOf(avg).multiply(GMV_DROP_RATIO).setScale(0, RoundingMode.HALF_UP);
    BigDecimal value = BigDecimal.valueOf(current);
    if (value.compareTo(threshold) < 0) {
      long pctBelow =
          BigDecimal.valueOf(avg - current)
              .multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(avg), 0, RoundingMode.HALF_UP)
              .longValue();
      out.add(
          AlertCandidate.firing(
              AlertSeverity.CRITICAL,
              AlertType.GMV_DROP,
              "GMV in the last hour (Rs "
                  + (current / 100)
                  + ") is "
                  + pctBelow
                  + "% below same-hour DoW avg (Rs "
                  + (avg / 100)
                  + "). Investigate immediately.",
              "gmv",
              value,
              threshold,
              null));
    } else {
      out.add(AlertCandidate.healthy(AlertType.GMV_DROP, null, "gmv"));
    }
  }

  private static void evaluateZones(
      MetricSourcePort source, MetricSampleStore samples, Instant now, List<AlertCandidate> out) {
    for (ZoneRiderSnapshot zone : source.zoneRiders()) {
      if (zone.ridersOnline() > 0) {
        out.add(AlertCandidate.healthy(AlertType.ZONE_DARK, zone.zoneId(), "rider_online_count"));
        continue;
      }
      int zeros =
          samples.consecutiveZeroBuckets(
              "rider_online_count", zone.zoneId(), now, ZONE_DARK_MINUTES + 5);
      if (zeros > ZONE_DARK_MINUTES) {
        out.add(
            AlertCandidate.firing(
                AlertSeverity.HIGH,
                AlertType.ZONE_DARK,
                "Zone '" + zone.zoneName() + "' has had 0 online riders for " + zeros + " minutes.",
                "rider_online_count",
                BigDecimal.ZERO,
                BigDecimal.ONE,
                zone.zoneId()));
      }
    }
  }

  private static void evaluatePayout(MetricSourcePort source, List<AlertCandidate> out) {
    long lastHour = source.payoutVolumeLastHourPaise();
    long avg = source.payoutHourlyAvg7dPaise();
    if (avg <= 0) {
      return;
    }
    BigDecimal threshold = BigDecimal.valueOf(avg).multiply(PAYOUT_SPIKE_FACTOR);
    BigDecimal value = BigDecimal.valueOf(lastHour);
    if (value.compareTo(threshold) > 0) {
      out.add(
          AlertCandidate.firing(
              AlertSeverity.HIGH,
              AlertType.PAYOUT_SPIKE,
              "Payout volume last hour exceeds 3× the 7-day hourly average.",
              "payout_volume",
              value,
              threshold,
              null));
    } else {
      out.add(AlertCandidate.healthy(AlertType.PAYOUT_SPIKE, null, "payout_volume"));
    }
  }

  private static void evaluatePayment(MetricSourcePort source, List<AlertCandidate> out) {
    int attempts = source.paymentAttempts15m();
    BigDecimal rate = source.paymentSuccessRatePct15m();
    if (attempts < PAYMENT_MIN_ATTEMPTS || rate == null) {
      return;
    }
    if (rate.compareTo(PAYMENT_HIGH) < 0) {
      AlertSeverity severity =
          rate.compareTo(PAYMENT_CRITICAL) < 0 ? AlertSeverity.CRITICAL : AlertSeverity.HIGH;
      out.add(
          AlertCandidate.firing(
              severity,
              AlertType.PAYMENT_FAILURE,
              "Payment success rate " + rate + "% over 15m (n=" + attempts + ") is below 95%.",
              "payment_success_pct",
              rate,
              PAYMENT_HIGH,
              null));
    } else {
      out.add(AlertCandidate.healthy(AlertType.PAYMENT_FAILURE, null, "payment_success_pct"));
    }
  }

  private static void evaluateSla(MetricSourcePort source, List<AlertCandidate> out) {
    BigDecimal sla = source.slaAdherencePctLastHour();
    if (sla == null) {
      return;
    }
    if (sla.compareTo(SLA_BREACH) < 0) {
      out.add(
          AlertCandidate.firing(
              AlertSeverity.HIGH,
              AlertType.SLA_BREACH_RATE,
              "SLA adherence " + sla + "% in the last hour is below 80%.",
              "sla_pct",
              sla,
              SLA_BREACH,
              null));
    } else {
      out.add(AlertCandidate.healthy(AlertType.SLA_BREACH_RATE, null, "sla_pct"));
    }
  }

  private static void evaluateSloBudgets(
      MetricSourcePort source, SloStore sloStore, List<AlertCandidate> out) {
    // One platform-level SLO_ERROR_BUDGET_EXHAUSTED alert (shared type+null zone).
    AlertCandidate exhausted = null;
    for (SloDefinition def : sloStore.allDefinitions()) {
      BigDecimal current = currentFor(def, source);
      if (current == null) {
        continue;
      }
      BigDecimal remaining = ErrorBudget.remainingPct(def.targetPct(), current);
      if (ErrorBudget.exhausted(remaining)) {
        exhausted =
            AlertCandidate.firing(
                AlertSeverity.CRITICAL,
                AlertType.SLO_ERROR_BUDGET_EXHAUSTED,
                "Error budget exhausted for SLO '"
                    + def.sloName()
                    + "' (remaining "
                    + remaining
                    + "%).",
                def.metricName(),
                remaining,
                BigDecimal.ZERO,
                null);
        break;
      }
    }
    if (exhausted != null) {
      out.add(exhausted);
    } else {
      out.add(
          AlertCandidate.healthy(AlertType.SLO_ERROR_BUDGET_EXHAUSTED, null, "slo_error_budget"));
    }
  }

  private static BigDecimal currentFor(SloDefinition def, MetricSourcePort source) {
    return switch (def.sloName()) {
      case "order_sla_adherence" -> source.orderSlaPct30d();
      case "payment_success" -> source.paymentSuccessPct30d();
      case "dispatch_success" -> source.dispatchSuccessPct30d();
      case "api_p99_latency" -> source.apiP99CompliancePct30d();
      default -> null;
    };
  }
}
