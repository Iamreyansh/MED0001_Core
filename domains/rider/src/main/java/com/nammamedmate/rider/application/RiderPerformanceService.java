package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderAssignmentStatsPort;
import com.nammamedmate.rider.application.port.out.RiderAssignmentStatsPort.Stats;
import com.nammamedmate.rider.application.port.out.RiderBadgeStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.PeriodTotals;
import com.nammamedmate.rider.domain.CodFloatLimits;
import com.nammamedmate.rider.domain.IncentiveRules;
import com.nammamedmate.rider.domain.PayoutCycle;
import com.nammamedmate.rider.domain.PayoutCycle.Window;
import com.nammamedmate.rider.domain.PerformanceRates;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderPerformanceService {

  private final RiderStore riders;
  private final RiderTripEarningsStore earnings;
  private final RiderAssignmentStatsPort assignmentStats;
  private final RiderBadgeStore badges;
  private final DeliveryZoneStore zones;
  private final PlatformPricingConfigStore config;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public RiderPerformanceService(
      RiderStore riders,
      RiderTripEarningsStore earnings,
      RiderAssignmentStatsPort assignmentStats,
      RiderBadgeStore badges,
      DeliveryZoneStore zones,
      PlatformPricingConfigStore config,
      OutboxPublisher outbox,
      Clock clock) {
    this.riders = riders;
    this.earnings = earnings;
    this.assignmentStats = assignmentStats;
    this.badges = badges;
    this.zones = zones;
    this.config = config;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> riderPerformance(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.RIDER) {
      throw new AppException("FORBIDDEN", "Rider role required", 403);
    }
    return buildPerformance(principal.subject(), false);
  }

  @Transactional
  public Map<String, Object> adminPerformance(MedmatePrincipal principal, UUID riderId) {
    requireOpsOrFinance(principal);
    return buildPerformance(riderId, true);
  }

  private Map<String, Object> buildPerformance(UUID riderId, boolean adminView) {
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    Instant now = clock.instant();
    LocalDate today = PayoutCycle.istDate(now);
    Window week = PayoutCycle.current(now);
    PeriodTotals todayT = earnings.sumForRider(riderId, today, today);
    PeriodTotals weekT = earnings.sumForRider(riderId, week.from(), week.to());
    var life = earnings.lifetime(riderId);
    Stats stats = assignmentStats.statsForRider(riderId);
    BigDecimal acceptance = PerformanceRates.ratePct(stats.accepted(), stats.assigned());
    BigDecimal cancel = PerformanceRates.ratePct(stats.cancelled(), stats.assigned());
    BigDecimal onTime =
        life.totalTrips() == 0
            ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
            : PerformanceRates.ratePct(earnings.countOnTime(riderId), life.totalTrips());

    int rated = earnings.countRated(riderId);
    BigDecimal rawAvg = earnings.avgRating(riderId).orElse(rider.avgRating());
    // Story note: min 5 ratings before public display; admin always sees profile avg.
    BigDecimal avgRating = adminView ? rawAvg : (rated >= 5 ? rawAvg : null);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", rider.id().toString());
    if (adminView) {
      data.put("name", rider.name());
      String zoneName =
          rider.primaryZoneId() == null
              ? null
              : zones.findById(rider.primaryZoneId()).map(z -> z.name()).orElse(null);
      data.put("zone_name", zoneName);
    }
    data.put("trips_total", life.totalTrips());
    data.put("trips_today", todayT.trips());
    data.put("trips_this_week", weekT.trips());
    data.put("on_time_pct", onTime.doubleValue());
    data.put("acceptance_rate_pct", acceptance.doubleValue());
    data.put(
        "avg_rating",
        avgRating == null ? null : avgRating.setScale(2, RoundingMode.HALF_UP).doubleValue());
    Double avgPickup = stats.avgPickupMinutes();
    Double avgDelivery = stats.avgDeliveryMinutes();
    data.put("avg_pickup_minutes", avgPickup != null ? avgPickup : 0.0);
    data.put("avg_delivery_minutes", avgDelivery != null ? avgDelivery : 0.0);
    data.put("cancel_rate_pct", cancel.doubleValue());
    data.put("total_distance_km", earnings.totalDistanceKm(riderId).doubleValue());

    if (adminView) {
      long limit = CodFloatLimits.resolvePaise(config);
      data.put("cod_in_hand", CodFloatLimits.paiseToRupees(rider.codInHandPaise()));
      data.put("cod_float_limit", CodFloatLimits.paiseToRupees(limit));
      data.put("risk_status", CodFloatLimits.riskStatus(rider.codInHandPaise(), limit));
      data.put("daily_streak_days", rider.dailyStreakDays());
      List<Map<String, Object>> alerts = new ArrayList<>();
      int threshold = IncentiveRules.acceptanceAlertThresholdPct(config);
      if (stats.assigned() > 0 && acceptance.doubleValue() < threshold) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("type", "ACCEPTANCE_RATE_LOW");
        alert.put("value", acceptance + "% lifetime");
        alert.put("threshold", threshold + "%");
        alerts.add(alert);
        publishAcceptanceAlert(rider, acceptance, threshold);
      }
      data.put("alerts", alerts);
    } else {
      List<Map<String, Object>> badgeList = new ArrayList<>();
      for (var b : badges.listForRider(riderId)) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("badge", b.badge());
        m.put("earned_at", b.earnedAt().toString());
        badgeList.add(m);
      }
      data.put("badges", badgeList);
    }
    return data;
  }

  private void publishAcceptanceAlert(RiderRecord rider, BigDecimal acceptance, int threshold) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rider_id", rider.id().toString());
    payload.put("rider_name", rider.name());
    payload.put("acceptance_rate_pct", acceptance.doubleValue());
    payload.put("threshold_pct", threshold);
    outbox.publish(
        DomainEvent.of("ops.alert.rider_acceptance_rate_low", "rider", Ids.newId(), payload));
  }

  private static void requireOpsOrFinance(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
