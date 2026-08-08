package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort.ActiveOrder;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetFilter;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetPage;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetRiderRow;
import com.nammamedmate.rider.application.port.out.RiderLiveStatusCachePort;
import com.nammamedmate.rider.application.port.out.RiderShiftStore;
import com.nammamedmate.rider.application.port.out.RiderShiftStore.ShiftRecord;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore.AuditRecord;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.rider.domain.CodFloatLimits;
import com.nammamedmate.rider.domain.RiderAvailability;
import com.nammamedmate.rider.domain.ZoneCoverage;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminFleetService {

  private static final Set<AuthRole> OPS = Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Set<String> STATUS_FILTERS = Set.of("ONLINE", "OFFLINE", "ON_TRIP");
  private static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final RiderStore riders;
  private final RiderFleetStore fleet;
  private final RiderShiftStore shifts;
  private final RiderStatusAuditStore audits;
  private final ZoneLookupPort zones;
  private final ActiveDeliveryPort deliveries;
  private final RiderLiveStatusCachePort liveCache;
  private final PlatformPricingConfigStore pricingConfig;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public AdminFleetService(
      RiderStore riders,
      RiderFleetStore fleet,
      RiderShiftStore shifts,
      RiderStatusAuditStore audits,
      ZoneLookupPort zones,
      ActiveDeliveryPort deliveries,
      RiderLiveStatusCachePort liveCache,
      OutboxPublisher outbox,
      Clock clock) {
    this(riders, fleet, shifts, audits, zones, deliveries, liveCache, null, outbox, clock);
  }

  @Autowired
  public AdminFleetService(
      RiderStore riders,
      RiderFleetStore fleet,
      RiderShiftStore shifts,
      RiderStatusAuditStore audits,
      ZoneLookupPort zones,
      ActiveDeliveryPort deliveries,
      RiderLiveStatusCachePort liveCache,
      PlatformPricingConfigStore pricingConfig,
      OutboxPublisher outbox,
      Clock clock) {
    this.riders = riders;
    this.fleet = fleet;
    this.shifts = shifts;
    this.audits = audits;
    this.zones = zones;
    this.deliveries = deliveries;
    this.liveCache = liveCache;
    this.pricingConfig = pricingConfig;
    this.outbox = outbox;
    this.clock = clock;
  }

  public record FleetResult(Map<String, Object> data, PaginationMeta meta) {
    public FleetResult {
      data = Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public FleetResult fleetOverview(
      MedmatePrincipal principal, UUID zoneId, String status, Integer page, Integer limit) {
    requireOps(principal);
    String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
    if (statusFilter != null && !STATUS_FILTERS.contains(statusFilter)) {
      throw new AppException("INVALID_STATUS", "status value not in ONLINE, OFFLINE, ON_TRIP", 422);
    }
    int p = page == null || page < 1 ? 1 : page;
    int lim = 20;
    if (limit != null && limit >= 1) {
      lim = Math.min(limit, 100);
    }

    // Load a wide page then filter by display status (active delivery drives ON_TRIP)
    FleetPage raw = fleet.listFleet(new FleetFilter(zoneId, statusFilter, 1, 5000));
    Instant now = clock.instant();
    LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    List<Map<String, Object>> all = new ArrayList<>();
    int online = 0;
    int onTrip = 0;
    int offline = 0;
    int stale = 0;
    for (FleetRiderRow row : raw.rows()) {
      Optional<ActiveOrder> active = deliveries.findActiveByRider(row.riderId());
      String display = RiderAvailability.displayStatus(row.accountStatus(), active.isPresent());
      if (statusFilter != null && !statusFilter.equals(display)) {
        continue;
      }
      boolean isStale = RiderAvailability.isStaleGps(row.lastLocationAt(), now);
      if (isStale) {
        if (RiderAvailability.isOnlineForCoverage(display)) {
          stale++;
        }
      }
      switch (display) {
        case "ONLINE" -> online++;
        case "ON_TRIP" -> onTrip++;
        default -> offline++;
      }
      all.add(toFleetMap(row, display, active, isStale, dayStart, dayEnd));
    }

    int from = (p - 1) * lim;
    List<Map<String, Object>> pageRows =
        from >= all.size() ? List.of() : all.subList(from, Math.min(from + lim, all.size()));

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total_riders", all.size());
    summary.put("online", online);
    summary.put("on_trip", onTrip);
    summary.put("offline", offline);
    summary.put("stale_gps_count", stale);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", summary);
    data.put("riders", pageRows);
    return new FleetResult(data, PaginationMeta.of(p, lim, all.size()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> zoneRiders(MedmatePrincipal principal, UUID zoneId) {
    requireOps(principal);
    ZoneLookupPort.ZoneInfo zone =
        zones
            .findById(zoneId)
            .orElseThrow(() -> new AppException("INVALID_ZONE", "zone_id does not exist", 422));

    List<FleetRiderRow> rows = fleet.listByZone(zoneId);
    int onlineCount = 0;
    int onTripCount = 0;
    int offlineCount = 0;
    BigDecimal ratingSum = BigDecimal.ZERO;
    int ratingN = 0;
    List<Map<String, Object>> riderMaps = new ArrayList<>();
    for (FleetRiderRow row : rows) {
      Optional<ActiveOrder> active = deliveries.findActiveByRider(row.riderId());
      String display = RiderAvailability.displayStatus(row.accountStatus(), active.isPresent());
      if ("ONLINE".equals(display)) {
        onlineCount++;
      } else if ("ON_TRIP".equals(display)) {
        onTripCount++;
      } else {
        offlineCount++;
      }
      if (row.avgRating() != null) {
        ratingSum = ratingSum.add(row.avgRating());
        ratingN++;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rider_id", row.riderId().toString());
      m.put("name", row.name());
      m.put("status", display);
      m.put("active_order_id", active.map(a -> a.orderId().toString()).orElse(null));
      m.put("avg_rating", row.avgRating());
      m.put("on_time_pct", row.onTimePct());
      riderMaps.add(m);
    }

    int onlineForCoverage = onlineCount + onTripCount;
    int liveOrders = deliveries.countLiveOrdersInZone(zoneId);
    double ratio = ZoneCoverage.ratio(onlineForCoverage, liveOrders);
    String coverage = ZoneCoverage.status(onlineForCoverage, liveOrders);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", zoneId.toString());
    data.put("zone_name", zone.name());
    data.put("coverage_status", coverage);
    data.put("online_count", onlineCount);
    data.put("on_trip_count", onTripCount);
    data.put("offline_count", offlineCount);
    data.put("live_orders", liveOrders);
    data.put(
        "coverage_ratio",
        BigDecimal.valueOf(ratio).setScale(1, RoundingMode.HALF_UP).doubleValue());
    data.put(
        "avg_rating",
        ratingN == 0
            ? null
            : ratingSum.divide(BigDecimal.valueOf(ratingN), 1, RoundingMode.HALF_UP));
    data.put("riders", riderMaps);
    return data;
  }

  @Transactional
  public Map<String, Object> forceStatus(
      MedmatePrincipal principal, UUID riderId, String statusRaw, String reason) {
    requireOps(principal);
    if (reason == null) {
      throw new AppException("REASON_REQUIRED", "reason is missing for admin action", 422);
    }
    if (reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is missing for admin action", 422);
    }
    if (statusRaw == null
        || !Set.of("ONLINE", "OFFLINE").contains(statusRaw.trim().toUpperCase())) {
      throw new AppException("INVALID_STATUS", "status value not in ONLINE, OFFLINE", 422);
    }
    String to = statusRaw.trim().toUpperCase();
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));

    Instant now = clock.instant();
    String from = rider.status();
    UUID zoneId = rider.primaryZoneId();
    Optional<ShiftRecord> open = shifts.findOpenByRider(riderId);

    if ("ONLINE".equals(to)) {
      if (!RiderAvailability.canGoOnline(rider.status(), rider.kycStatus())) {
        throw new AppException("RIDER_NOT_ELIGIBLE", "Rider not eligible to go ONLINE", 403);
      }
      if (zoneId == null) {
        throw new AppException("INVALID_ZONE", "zone_id does not exist", 422);
      }
      if (open.isEmpty()) {
        ShiftRecord shift =
            new ShiftRecord(Ids.newId(), riderId, zoneId, now, null, null, 0, 0L, null, now);
        shifts.insert(shift);
      }
      riders.updateAvailability(riderId, "ONLINE", zoneId, now);
    } else {
      if (open.isPresent()) {
        shifts.close(
            open.get().id(),
            now,
            (int) Duration.between(open.get().shiftStart(), now).toMinutes(),
            principal.subject());
      }
      riders.updateAvailability(riderId, "OFFLINE", zoneId, now);
    }

    String role = principal.role() == AuthRole.ADMIN_SUPER ? "admin_super" : "admin_operations";
    audits.insert(
        new AuditRecord(
            Ids.newId(), riderId, principal.subject(), role, from, to, reason.trim(), now));
    liveCache.put(riderId, to, CACHE_TTL);

    outbox.publish(
        DomainEvent.of(
            "rider.notification.force_status",
            "rider",
            riderId,
            Map.of(
                "rider_id",
                riderId.toString(),
                "status",
                to,
                "reason",
                reason.trim(),
                "template",
                "RIDER_FORCE_STATUS",
                "channels",
                List.of("PUSH"))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("status", to);
    data.put("force_changed_by", principal.subject().toString());
    data.put("force_changed_at", now.toString());
    data.put("reason", reason.trim());
    return data;
  }

  @Transactional
  public Map<String, Object> reassignZone(
      MedmatePrincipal principal, UUID riderId, UUID zoneId, Boolean notifyRider) {
    requireOps(principal);
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    ZoneLookupPort.ZoneInfo zone =
        zones
            .findById(zoneId)
            .filter(ZoneLookupPort.ZoneInfo::active)
            .orElseThrow(() -> new AppException("INVALID_ZONE", "zone_id does not exist", 422));

    Instant now = clock.instant();
    riders.updatePrimaryZone(riderId, zoneId, now);
    if ("ONLINE".equals(rider.status()) || "ON_TRIP".equals(rider.status())) {
      riders.updateAvailability(riderId, rider.status(), zoneId, now);
    }

    boolean notify = notifyRider == null || notifyRider;
    if (notify) {
      outbox.publish(
          DomainEvent.of(
              "rider.notification.zone_reassigned",
              "rider",
              riderId,
              Map.of(
                  "rider_id",
                  riderId.toString(),
                  "zone_id",
                  zoneId.toString(),
                  "zone_name",
                  zone.name(),
                  "template",
                  "RIDER_ZONE_REASSIGNED",
                  "channels",
                  List.of("PUSH"))));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("new_zone_id", zoneId.toString());
    data.put("new_zone_name", zone.name());
    data.put("reassigned_by", principal.subject().toString());
    data.put("reassigned_at", now.toString());
    data.put("rider_notified", notify);
    return data;
  }

  private Map<String, Object> toFleetMap(
      FleetRiderRow row,
      String display,
      Optional<ActiveOrder> active,
      boolean isStale,
      Instant dayStart,
      Instant dayEnd) {
    long earnings = fleet.sumShiftEarningsTodayPaise(row.riderId(), dayStart, dayEnd);
    int trips = fleet.countTripsToday(row.riderId(), dayStart, dayEnd);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rider_id", row.riderId().toString());
    m.put("name", row.name());
    m.put("phone", maskPhone(row.phone()));
    UUID zid = row.currentZoneId() != null ? row.currentZoneId() : row.zoneId();
    m.put("zone_id", zid == null ? null : zid.toString());
    m.put("zone_name", row.zoneName());
    m.put("vehicle_type", row.vehicleType());
    m.put("status", display);
    m.put("active_order_id", active.map(a -> a.orderId().toString()).orElse(null));
    m.put("avg_rating", row.avgRating());
    m.put("on_time_pct", row.onTimePct());
    m.put("trips_today", trips);
    m.put("earnings_today", paiseToRupees(earnings));
    m.put(
        "last_location_at", row.lastLocationAt() == null ? null : row.lastLocationAt().toString());
    m.put("is_stale_gps", isStale);
    long inHand = riders.findById(row.riderId()).map(RiderRecord::codInHandPaise).orElse(0L);
    long floatLimit = CodFloatLimits.resolvePaise(pricingConfig);
    m.put("cod_in_hand", paiseToRupees(inHand));
    m.put("risk_status", CodFloatLimits.riskStatus(inHand, floatLimit));
    return m;
  }

  private static String maskPhone(String phone) {
    if (phone == null) {
      return null;
    }
    // story sample shows 10-digit national form
    if (phone.startsWith("+91")) {
      return phone.substring(3);
    }
    return phone;
  }

  private static void requireOps(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    if (!OPS.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }
}
