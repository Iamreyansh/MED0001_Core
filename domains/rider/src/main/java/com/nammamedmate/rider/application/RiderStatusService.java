package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort.ActiveOrder;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderLiveStatusCachePort;
import com.nammamedmate.rider.application.port.out.RiderShiftStore;
import com.nammamedmate.rider.application.port.out.RiderShiftStore.ShiftRecord;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore.AuditRecord;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.rider.domain.RiderAvailability;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderStatusService {

  private static final Set<String> LIVE_STATUSES = Set.of("ONLINE", "OFFLINE");
  private static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final RiderStore riders;
  private final RiderShiftStore shifts;
  private final RiderStatusAuditStore audits;
  private final ZoneLookupPort zones;
  private final ActiveDeliveryPort deliveries;
  private final RiderLiveStatusCachePort liveCache;
  private final RiderFleetStore fleet;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public RiderStatusService(
      RiderStore riders,
      RiderShiftStore shifts,
      RiderStatusAuditStore audits,
      ZoneLookupPort zones,
      ActiveDeliveryPort deliveries,
      RiderLiveStatusCachePort liveCache,
      RiderFleetStore fleet,
      OutboxPublisher outbox,
      Clock clock) {
    this.riders = riders;
    this.shifts = shifts;
    this.audits = audits;
    this.zones = zones;
    this.deliveries = deliveries;
    this.liveCache = liveCache;
    this.fleet = fleet;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> setStatus(MedmatePrincipal principal, String statusRaw, UUID zoneId) {
    requireRider(principal);
    if (statusRaw == null || !LIVE_STATUSES.contains(statusRaw.trim().toUpperCase())) {
      throw new AppException("INVALID_STATUS", "status not ONLINE or OFFLINE", 422);
    }
    String status = statusRaw.trim().toUpperCase();
    UUID riderId = principal.subject();

    if ("ONLINE".equals(status)) {
      return goOnline(principal, riderId, zoneId);
    }
    return goOffline(principal, riderId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getStatus(MedmatePrincipal principal) {
    requireRider(principal);
    UUID riderId = principal.subject();
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));

    Instant now = clock.instant();
    Optional<ShiftRecord> open = shifts.findOpenByRider(riderId);
    Optional<ActiveOrder> active = deliveries.findActiveByRider(riderId);
    String display = RiderAvailability.displayStatus(rider.status(), active.isPresent());

    UUID zoneId = open.map(ShiftRecord::zoneId).orElse(rider.primaryZoneId());
    String zoneName =
        zoneId == null
            ? null
            : zones.findById(zoneId).map(ZoneLookupPort.ZoneInfo::name).orElse(null);

    LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    int durationMinutes =
        open.map(s -> (int) Duration.between(s.shiftStart(), now).toMinutes())
            .orElseGet(() -> shifts.sumDurationMinutesForRiderBetween(riderId, dayStart, dayEnd));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("status", display);
    data.put("zone_id", zoneId == null ? null : zoneId.toString());
    data.put("zone_name", zoneName);
    data.put("shift_started_at", open.map(s -> s.shiftStart().toString()).orElse(null));
    data.put("shift_duration_minutes", durationMinutes);
    if (active.isPresent()) {
      ActiveOrder o = active.get();
      Map<String, Object> ao = new LinkedHashMap<>();
      ao.put("order_id", o.orderId().toString());
      ao.put("order_status", mapOrderStatus(o.orderStatus()));
      ao.put("customer_address_short", o.customerAddressShort());
      ao.put("eta_minutes", o.etaMinutes());
      data.put("active_order", ao);
    } else {
      data.put("active_order", null);
    }
    long earningsPaise = fleet.sumShiftEarningsTodayPaise(riderId, dayStart, dayEnd);
    Map<String, Object> earnings = new LinkedHashMap<>();
    earnings.put("base", paiseToRupees(earningsPaise));
    earnings.put("incentives", paiseToRupees(0));
    earnings.put("tips", paiseToRupees(0));
    earnings.put("total", paiseToRupees(earningsPaise));
    data.put("earnings_today", earnings);
    data.put("daily_streak_days", rider.dailyStreakDays());
    audits
        .findLatestForceChange(riderId)
        .ifPresent(
            a -> {
              data.put("force_status_reason", a.reason());
              data.put("force_changed_at", a.createdAt().toString());
            });
    return data;
  }

  private Map<String, Object> goOnline(MedmatePrincipal principal, UUID riderId, UUID zoneId) {
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    if (!RiderAvailability.canGoOnline(rider.status(), rider.kycStatus())) {
      throw new AppException(
          "RIDER_NOT_ELIGIBLE", "Rider status is not ACTIVE (KYC not approved or blocked)", 403);
    }
    UUID effectiveZone = zoneId != null ? zoneId : rider.primaryZoneId();
    if (effectiveZone == null) {
      throw new AppException("INVALID_ZONE", "zone_id does not exist", 422);
    }
    zones
        .findById(effectiveZone)
        .filter(ZoneLookupPort.ZoneInfo::active)
        .orElseThrow(() -> new AppException("INVALID_ZONE", "zone_id does not exist", 422));

    Instant now = clock.instant();
    Optional<ShiftRecord> open = shifts.findOpenByRider(riderId);
    if (open.isPresent() && "ONLINE".equals(rider.status())) {
      return onlineResponse(riderId, effectiveZone, open.get(), "Already online.");
    }

    if (open.isPresent()) {
      ShiftRecord prior = open.get();
      shifts.close(
          prior.id(), now, (int) Duration.between(prior.shiftStart(), now).toMinutes(), null);
    }

    UUID shiftId = Ids.newId();
    ShiftRecord shift =
        new ShiftRecord(shiftId, riderId, effectiveZone, now, null, null, 0, 0L, null, now);
    shifts.insert(shift);
    riders.updateAvailability(riderId, "ONLINE", effectiveZone, now);
    audit(riderId, principal, rider.status(), "ONLINE", null, now);
    liveCache.put(riderId, "ONLINE", CACHE_TTL);
    return onlineResponse(
        riderId, effectiveZone, shift, "You are now online and ready to receive orders.");
  }

  private Map<String, Object> goOffline(MedmatePrincipal principal, UUID riderId) {
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    Instant now = clock.instant();
    Optional<ActiveOrder> active = deliveries.findActiveByRider(riderId);
    Optional<ShiftRecord> open = shifts.findOpenByRider(riderId);
    UUID zoneId = open.map(ShiftRecord::zoneId).orElse(rider.primaryZoneId());

    if (open.isPresent()) {
      shifts.close(
          open.get().id(),
          now,
          (int) Duration.between(open.get().shiftStart(), now).toMinutes(),
          null);
    }
    riders.updateAvailability(riderId, "OFFLINE", zoneId, now);
    audit(riderId, principal, rider.status(), "OFFLINE", null, now);
    liveCache.put(riderId, "OFFLINE", CACHE_TTL);

    boolean during = active.isPresent();
    if (during) {
      ActiveOrder o = active.get();
      deliveries.flagForMonitoring(o.orderId(), "OFFLINE_DURING_DELIVERY");
      outbox.publish(
          DomainEvent.of(
              "admin.alert.offline_during_delivery",
              "rider",
              riderId,
              Map.of(
                  "rider_id",
                  riderId.toString(),
                  "order_id",
                  o.orderId().toString(),
                  "alert",
                  "OFFLINE_DURING_DELIVERY",
                  "channels",
                  List.of("PUSH"))));
    }

    // Notes: non-blocking — status set + alert; return warning code (not 409 rollback)
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("status", "OFFLINE");
    data.put("zone_id", zoneId == null ? null : zoneId.toString());
    data.put("shift_id", open.map(s -> s.id().toString()).orElse(null));
    data.put("shift_started_at", open.map(s -> s.shiftStart().toString()).orElse(null));
    data.put(
        "message",
        during
            ? "You are offline; active delivery flagged for monitoring."
            : "You are now offline.");
    if (during) {
      data.put("warning", "OFFLINE_DURING_DELIVERY");
    }
    return data;
  }

  private Map<String, Object> onlineResponse(
      UUID riderId, UUID zoneId, ShiftRecord shift, String message) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("status", "ONLINE");
    data.put("zone_id", zoneId.toString());
    data.put("shift_id", shift.id().toString());
    data.put("shift_started_at", shift.shiftStart().toString());
    data.put("message", message);
    return data;
  }

  private void audit(
      UUID riderId,
      MedmatePrincipal principal,
      String from,
      String to,
      String reason,
      Instant now) {
    audits.insert(
        new AuditRecord(Ids.newId(), riderId, principal.subject(), "rider", from, to, reason, now));
  }

  private static void requireRider(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.RIDER) {
      throw new AppException("FORBIDDEN", "Rider role required", 403);
    }
  }

  private static String mapOrderStatus(String dbStatus) {
    if ("OUT_FOR_DELIVERY".equals(dbStatus)) {
      return "ON_TRIP";
    }
    return dbStatus;
  }

  private static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }
}
