package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLiveStatusCache;
import com.nammamedmate.rider.application.AdminFleetService.FleetResult;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort.ActiveOrder;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetFilter;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetPage;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetRiderRow;
import com.nammamedmate.rider.application.port.out.RiderShiftStore;
import com.nammamedmate.rider.application.port.out.RiderShiftStore.ShiftRecord;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore;
import com.nammamedmate.rider.application.port.out.RiderStatusAuditStore.AuditRecord;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminFleetServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC);
  private final UUID adminId = Ids.newId();
  private final UUID zoneId = Ids.newId();
  private final UUID riderId = Ids.newId();
  private FakeRiders riders;
  private FakeFleet fleet;
  private FakeShifts shifts;
  private FakeAudits audits;
  private FakeDeliveries deliveries;
  private InMemoryOutboxStore outbox;
  private AdminFleetService service;

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    fleet = new FakeFleet();
    shifts = new FakeShifts();
    audits = new FakeAudits();
    deliveries = new FakeDeliveries();
    outbox = new InMemoryOutboxStore();
    riders.insert(rider("ONLINE", "APPROVED"));
    fleet.rows.add(fleetRow("ONLINE", null));
    service =
        new AdminFleetService(
            riders,
            fleet,
            shifts,
            audits,
            z ->
                z.equals(zoneId)
                    ? Optional.of(new ZoneLookupPort.ZoneInfo(zoneId, "Koramangala", true))
                    : Optional.empty(),
            deliveries,
            new RedisRiderLiveStatusCache(null),
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock);
  }

  @Test
  void ac005_fleetCountsOnlineOnTripOffline() {
    UUID r2 = Ids.newId();
    UUID r3 = Ids.newId();
    fleet.rows.clear();
    fleet.rows.add(fleetRow(riderId, "ONLINE", null));
    fleet.rows.add(fleetRow(r2, "ONLINE", null));
    fleet.rows.add(fleetRow(r3, "OFFLINE", null));
    deliveries.byRider.put(riderId, new ActiveOrder(Ids.newId(), "OUT_FOR_DELIVERY", "HSR", 5));

    FleetResult result = service.fleetOverview(admin(), null, null, 1, 50);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) result.data().get("summary");
    assertThat(summary.get("online")).isEqualTo(1);
    assertThat(summary.get("on_trip")).isEqualTo(1);
    assertThat(summary.get("offline")).isEqualTo(1);
    assertThat(summary.get("total_riders")).isEqualTo(3);
  }

  @Test
  void ac006_zoneCoverageUnderStrain() {
    fleet.rows.clear();
    fleet.rows.add(fleetRow(Ids.newId(), "ONLINE", null));
    fleet.rows.add(fleetRow(Ids.newId(), "ONLINE", null));
    deliveries.liveOrders = 5;
    Map<String, Object> data = service.zoneRiders(admin(), zoneId);
    assertThat(data.get("coverage_status")).isEqualTo("UNDER_STRAIN");
    assertThat(data.get("live_orders")).isEqualTo(5);
    assertThat(((Number) data.get("coverage_ratio")).doubleValue()).isEqualTo(2.5);
  }

  @Test
  void ac007_forceStatusCreatesAuditWithReason() {
    Map<String, Object> data =
        service.forceStatus(admin(), riderId, "OFFLINE", "Rider unresponsive on phone");
    assertThat(data.get("status")).isEqualTo("OFFLINE");
    assertThat(data.get("reason")).isEqualTo("Rider unresponsive on phone");
    assertThat(audits.all).hasSize(1);
    assertThat(audits.all.get(0).reason()).isEqualTo("Rider unresponsive on phone");
    assertThat(audits.all.get(0).changedBy()).isEqualTo(adminId);
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("force_status"));

    riders.updateAvailability(riderId, "OFFLINE", zoneId, clock.instant());
    Map<String, Object> online =
        service.forceStatus(
            new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"),
            riderId,
            "ONLINE",
            "Back on duty");
    assertThat(online.get("status")).isEqualTo("ONLINE");
    assertThat(shifts.findOpenByRider(riderId)).isPresent();
  }

  @Test
  void ac008_staleGpsWhenLastLocationOlderThanTwoMinutes() {
    fleet.rows.clear();
    fleet.rows.add(fleetRow(riderId, "ONLINE", Instant.parse("2026-07-24T09:57:00Z")));
    FleetResult result = service.fleetOverview(admin(), null, null, 1, 50);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) result.data().get("summary");
    assertThat(summary.get("stale_gps_count")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) result.data().get("riders");
    assertThat(list.get(0).get("is_stale_gps")).isEqualTo(true);
  }

  @Test
  void reassignZoneAndErrors() {
    Map<String, Object> data = service.reassignZone(admin(), riderId, zoneId, true);
    assertThat(data.get("new_zone_name")).isEqualTo("Koramangala");
    assertThat(data.get("rider_notified")).isEqualTo(true);
    assertThatThrownBy(() -> service.forceStatus(admin(), riderId, "OFFLINE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> service.forceStatus(admin(), riderId, "OFFLINE", "   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> service.forceStatus(admin(), riderId, " ", "x"))
        .isInstanceOf(AppException.class); // blank status after trim fails INVALID
    assertThatThrownBy(() -> service.fleetOverview(null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.forceStatus(admin(), riderId, null, "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    assertThatThrownBy(() -> service.forceStatus(admin(), riderId, "BUSY", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    assertThatThrownBy(() -> service.forceStatus(admin(), Ids.newId(), "OFFLINE", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> service.reassignZone(admin(), riderId, Ids.newId(), false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");
    assertThatThrownBy(() -> service.fleetOverview(admin(), null, "BUSY", 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    // blank status filter → treat as all
    assertThat(service.fleetOverview(admin(), null, "  ", 0, 0).meta().page()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                service.fleetOverview(
                    new MedmatePrincipal(adminId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    null,
                    null,
                    1,
                    20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.zoneRiders(admin(), Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");
    assertThatThrownBy(() -> service.reassignZone(admin(), Ids.newId(), zoneId, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_FOUND");
  }

  @Test
  void fleetFiltersPaginationZoneCoverageEdges() {
    UUID offlineId = Ids.newId();
    UUID tripId = Ids.newId();
    fleet.rows.clear();
    fleet.rows.add(fleetRow(riderId, "ONLINE", null));
    fleet.rows.add(fleetRow(offlineId, "OFFLINE", null));
    fleet.rows.add(fleetRow(tripId, "ONLINE", null));
    deliveries.byRider.put(tripId, new ActiveOrder(Ids.newId(), "OUT_FOR_DELIVERY", "HSR", 5));

    FleetResult filtered = service.fleetOverview(admin(), null, "ONLINE", 1, 50);
    assertThat(filtered.meta().total()).isEqualTo(1);

    FleetResult pagePast = service.fleetOverview(admin(), null, null, 99, 50);
    assertThat(((java.util.List<?>) pagePast.data().get("riders"))).isEmpty();

    FleetResult capped = service.fleetOverview(admin(), null, null, null, 200);
    assertThat(capped.meta().limit()).isEqualTo(100);
    FleetResult defaults = service.fleetOverview(admin(), null, null, null, null);
    assertThat(defaults.meta().limit()).isEqualTo(20);

    // stale on ON_TRIP; phone without +91; null zone ids
    fleet.rows.clear();
    fleet.rows.add(
        new FleetRiderRow(
            Ids.newId(),
            "X",
            null,
            null,
            null,
            "BIKE",
            "ONLINE",
            null,
            Instant.parse("2026-07-24T09:50:00Z"),
            null,
            null,
            0,
            0L));
    fleet.rows.add(
        new FleetRiderRow(
            Ids.newId(),
            "Y",
            "9876543210",
            null,
            null,
            "BIKE",
            "ONLINE",
            null,
            Instant.parse("2026-07-24T09:50:00Z"),
            null,
            null,
            0,
            0L));
    UUID tripStale = Ids.newId();
    fleet.rows.add(
        new FleetRiderRow(
            tripStale,
            "Z",
            "+919876543210",
            zoneId,
            "K",
            "BIKE",
            "ONLINE",
            zoneId,
            Instant.parse("2026-07-24T09:50:00Z"),
            null,
            null,
            0,
            0L));
    deliveries.byRider.put(tripStale, new ActiveOrder(Ids.newId(), "OUT_FOR_DELIVERY", "HSR", 1));
    fleet.rows.add(
        new FleetRiderRow(
            Ids.newId(),
            "OFF",
            "9000000000",
            zoneId,
            "K",
            "BIKE",
            "OFFLINE",
            zoneId,
            Instant.parse("2026-07-24T09:50:00Z"),
            null,
            null,
            0,
            0L));
    FleetResult staleTrip = service.fleetOverview(admin(), null, null, 1, 50);
    @SuppressWarnings("unchecked")
    Map<String, Object> sum = (Map<String, Object>) staleTrip.data().get("summary");
    assertThat(sum.get("stale_gps_count")).isEqualTo(3);

    // zone riders: online + on_trip + offline + null ratings
    fleet.rows.clear();
    fleet.rows.add(
        new FleetRiderRow(
            riderId,
            "A",
            "9876543210",
            zoneId,
            "Koramangala",
            "BIKE",
            "ONLINE",
            null,
            null,
            null,
            null,
            0,
            0L));
    fleet.rows.add(
        new FleetRiderRow(
            tripId,
            "B",
            "9876543211",
            zoneId,
            "Koramangala",
            "BIKE",
            "ONLINE",
            zoneId,
            null,
            null,
            null,
            0,
            0L));
    fleet.rows.add(
        new FleetRiderRow(
            offlineId,
            "C",
            "9876543212",
            zoneId,
            "Koramangala",
            "BIKE",
            "OFFLINE",
            zoneId,
            null,
            null,
            null,
            0,
            0L));
    deliveries.byRider.put(tripId, new ActiveOrder(Ids.newId(), "OUT_FOR_DELIVERY", "HSR", 5));
    deliveries.liveOrders = 0;
    Map<String, Object> zone = service.zoneRiders(admin(), zoneId);
    assertThat(zone.get("coverage_status")).isEqualTo("COVERED");
    assertThat(zone.get("on_trip_count")).isEqualTo(1);
    assertThat(zone.get("offline_count")).isEqualTo(1);
    assertThat(zone.get("avg_rating")).isNull();

    // force offline closes open shift; force online without zone
    shifts.insert(
        new ShiftRecord(
            Ids.newId(),
            riderId,
            zoneId,
            clock.instant().minusSeconds(120),
            null,
            null,
            0,
            0L,
            null,
            clock.instant()));
    service.forceStatus(admin(), riderId, "OFFLINE", "close shift");
    assertThat(shifts.findOpenByRider(riderId)).isEmpty();

    riders.update(
        new RiderRecord(
            riderId,
            "Ravi",
            "+919876543210",
            null,
            "BIKE",
            "KA01AB1234",
            null,
            "ACTIVE",
            "APPROVED",
            clock.instant(),
            null,
            null,
            null,
            null,
            false,
            null,
            0,
            null,
            0L,
            0L,
            0,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    assertThatThrownBy(() -> service.forceStatus(admin(), riderId, "ONLINE", "no zone"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");

    riders.update(rider("BLOCKED", "APPROVED"));
    assertThatThrownBy(() -> service.forceStatus(admin(), riderId, "ONLINE", "blocked"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_ELIGIBLE");

    riders.update(rider("ONLINE", "APPROVED"));
    service.reassignZone(admin(), riderId, zoneId, false);
    service.reassignZone(admin(), riderId, zoneId, null);
    riders.updateAvailability(riderId, "ON_TRIP", zoneId, clock.instant());
    service.reassignZone(admin(), riderId, zoneId, true);
    riders.updateAvailability(riderId, "OFFLINE", zoneId, clock.instant());
    service.reassignZone(admin(), riderId, zoneId, true);

    // force ONLINE when open shift already exists
    riders.update(rider("OFFLINE", "APPROVED"));
    shifts.insert(
        new ShiftRecord(
            Ids.newId(),
            riderId,
            zoneId,
            clock.instant().minusSeconds(30),
            null,
            null,
            0,
            0L,
            null,
            clock.instant()));
    assertThat(service.forceStatus(admin(), riderId, "ONLINE", "already open").get("status"))
        .isEqualTo("ONLINE");
  }

  private MedmatePrincipal admin() {
    return new MedmatePrincipal(adminId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private RiderRecord rider(String status, String kyc) {
    Instant now = clock.instant();
    return new RiderRecord(
        riderId,
        "Ravi",
        "+919876543210",
        null,
        "BIKE",
        "KA01AB1234",
        zoneId,
        status,
        kyc,
        now,
        null,
        null,
        null,
        null,
        false,
        BigDecimal.valueOf(4.7),
        0,
        BigDecimal.valueOf(91.2),
        0L,
        0L,
        0,
        null,
        null,
        null,
        now,
        now);
  }

  private FleetRiderRow fleetRow(String status, Instant lastLoc) {
    return fleetRow(riderId, status, lastLoc);
  }

  private FleetRiderRow fleetRow(UUID id, String status, Instant lastLoc) {
    return new FleetRiderRow(
        id,
        "Ravi",
        "+919876543210",
        zoneId,
        "Koramangala",
        "BIKE",
        status,
        zoneId,
        lastLoc,
        BigDecimal.valueOf(4.7),
        BigDecimal.valueOf(91.2),
        0,
        0L);
  }

  static final class FakeRiders implements RiderStore {
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return Optional.empty();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return false;
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(new ArrayList<>(byId.values()), byId.size());
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {
      RiderRecord r = byId.get(id);
      byId.put(
          id,
          new RiderRecord(
              r.id(),
              r.name(),
              r.phone(),
              r.email(),
              r.vehicleType(),
              r.vehiclePlateNumber(),
              r.primaryZoneId(),
              status,
              r.kycStatus(),
              r.kycSubmittedAt(),
              r.kycReviewedAt(),
              r.kycReviewedBy(),
              r.kycRejectionReason(),
              r.kycRejectionNotes(),
              r.aadhaarVerified(),
              r.avgRating(),
              r.totalTrips(),
              r.onTimePct(),
              r.earningsWalletBalancePaise(),
              r.codInHandPaise(),
              r.dailyStreakDays(),
              r.blockedReason(),
              r.blockedBy(),
              r.blockedAt(),
              r.createdAt(),
              updatedAt));
    }

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {
      RiderRecord r = byId.get(id);
      byId.put(
          id,
          new RiderRecord(
              r.id(),
              r.name(),
              r.phone(),
              r.email(),
              r.vehicleType(),
              r.vehiclePlateNumber(),
              primaryZoneId,
              r.status(),
              r.kycStatus(),
              r.kycSubmittedAt(),
              r.kycReviewedAt(),
              r.kycReviewedBy(),
              r.kycRejectionReason(),
              r.kycRejectionNotes(),
              r.aadhaarVerified(),
              r.avgRating(),
              r.totalTrips(),
              r.onTimePct(),
              r.earningsWalletBalancePaise(),
              r.codInHandPaise(),
              r.dailyStreakDays(),
              r.blockedReason(),
              r.blockedBy(),
              r.blockedAt(),
              r.createdAt(),
              updatedAt));
    }
  }

  static final class FakeFleet implements RiderFleetStore {
    final List<FleetRiderRow> rows = new CopyOnWriteArrayList<>();

    @Override
    public FleetPage listFleet(FleetFilter filter) {
      return new FleetPage(List.copyOf(rows), rows.size());
    }

    @Override
    public List<FleetRiderRow> listByZone(UUID zoneId) {
      return List.copyOf(rows);
    }

    @Override
    public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
      return rows.stream().filter(r -> r.riderId().equals(riderId)).findFirst();
    }

    @Override
    public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 12;
    }

    @Override
    public long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 44500L;
    }
  }

  static final class FakeShifts implements RiderShiftStore {
    final Map<UUID, ShiftRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(ShiftRecord shift) {
      byId.put(shift.id(), shift);
    }

    @Override
    public void close(UUID shiftId, Instant shiftEnd, int durationMinutes, UUID forceClosedBy) {
      ShiftRecord s = byId.get(shiftId);
      byId.put(
          shiftId,
          new ShiftRecord(
              s.id(),
              s.riderId(),
              s.zoneId(),
              s.shiftStart(),
              shiftEnd,
              durationMinutes,
              s.tripsInShift(),
              s.earningsInShiftPaise(),
              forceClosedBy,
              s.createdAt()));
    }

    @Override
    public Optional<ShiftRecord> findOpenByRider(UUID riderId) {
      return byId.values().stream()
          .filter(s -> s.riderId().equals(riderId) && s.shiftEnd() == null)
          .findFirst();
    }

    @Override
    public int sumDurationMinutesForRiderBetween(
        UUID riderId, Instant fromInclusive, Instant toExclusive) {
      return 0;
    }

    @Override
    public Optional<ShiftRecord> findLatestClosedByRider(UUID riderId) {
      return Optional.empty();
    }
  }

  static final class FakeAudits implements RiderStatusAuditStore {
    final List<AuditRecord> all = new CopyOnWriteArrayList<>();

    @Override
    public void insert(AuditRecord record) {
      all.add(record);
    }

    @Override
    public Optional<AuditRecord> findLatestForceChange(UUID riderId) {
      return all.stream().filter(a -> a.riderId().equals(riderId)).reduce((a, b) -> b);
    }
  }

  static final class FakeDeliveries implements ActiveDeliveryPort {
    final Map<UUID, ActiveOrder> byRider = new ConcurrentHashMap<>();
    final AtomicReference<ActiveOrder> unused = new AtomicReference<>();
    int liveOrders;

    @Override
    public Optional<ActiveOrder> findActiveByRider(UUID riderId) {
      return Optional.ofNullable(byRider.get(riderId));
    }

    @Override
    public int countLiveOrdersInZone(UUID zoneId) {
      return liveOrders;
    }

    @Override
    public void flagForMonitoring(UUID orderId, String reason) {}
  }
}
