package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLiveStatusCache;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiderStatusServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:00:00Z"), ZoneOffset.UTC);
  private final UUID riderId = Ids.newId();
  private final UUID zoneId = Ids.newId();
  private FakeRiders riders;
  private FakeShifts shifts;
  private FakeAudits audits;
  private FakeDeliveries deliveries;
  private FakeFleet fleet;
  private InMemoryOutboxStore outbox;
  private RiderStatusService service;

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    shifts = new FakeShifts();
    audits = new FakeAudits();
    deliveries = new FakeDeliveries();
    fleet = new FakeFleet();
    outbox = new InMemoryOutboxStore();
    riders.insert(rider("ACTIVE", "APPROVED"));
    service =
        new RiderStatusService(
            riders,
            shifts,
            audits,
            z ->
                z.equals(zoneId)
                    ? Optional.of(new ZoneLookupPort.ZoneInfo(zoneId, "Koramangala", true))
                    : Optional.empty(),
            deliveries,
            new RedisRiderLiveStatusCache(null),
            fleet,
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock);
  }

  @Test
  void ac001_activeRiderGoesOnlineCreatesShift() {
    Map<String, Object> data = service.setStatus(rider(), "ONLINE", zoneId);
    assertThat(data.get("status")).isEqualTo("ONLINE");
    assertThat(data.get("shift_id")).isNotNull();
    assertThat(riders.findById(riderId).orElseThrow().status()).isEqualTo("ONLINE");
    assertThat(shifts.findOpenByRider(riderId)).isPresent();
  }

  @Test
  void ac002_notEligibleWhenKycOrBlocked() {
    riders.update(rider("ACTIVE", "SUBMITTED"));
    assertThatThrownBy(() -> service.setStatus(rider(), "ONLINE", zoneId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_ELIGIBLE");
    riders.update(rider("BLOCKED", "APPROVED"));
    assertThatThrownBy(() -> service.setStatus(rider(), "ONLINE", zoneId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RIDER_NOT_ELIGIBLE");
  }

  @Test
  void ac003_offlineClosesShiftWithDuration() {
    service.setStatus(rider(), "ONLINE", zoneId);
    ShiftRecord open = shifts.findOpenByRider(riderId).orElseThrow();
    Map<String, Object> data = service.setStatus(rider(), "OFFLINE", null);
    assertThat(data.get("status")).isEqualTo("OFFLINE");
    assertThat(shifts.findOpenByRider(riderId)).isEmpty();
    assertThat(shifts.byId.get(open.id()).durationMinutes()).isNotNull();
  }

  @Test
  void ac004_offlineDuringDeliveryFlagsAndAlerts() {
    service.setStatus(rider(), "ONLINE", zoneId);
    UUID orderId = Ids.newId();
    deliveries.active.set(new ActiveOrder(orderId, "OUT_FOR_DELIVERY", "HSR Layout", 8));
    Map<String, Object> data = service.setStatus(rider(), "OFFLINE", null);
    assertThat(data.get("warning")).isEqualTo("OFFLINE_DURING_DELIVERY");
    assertThat(data.get("status")).isEqualTo("OFFLINE");
    assertThat(riders.findById(riderId).orElseThrow().status()).isEqualTo("OFFLINE");
    assertThat(deliveries.flagged).contains(orderId);
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("offline_during_delivery"));
  }

  @Test
  void getStatusAndValidationErrors() {
    service.setStatus(rider(), "ONLINE", zoneId);
    Map<String, Object> status = service.getStatus(rider());
    assertThat(status.get("status")).isEqualTo("ONLINE");
    assertThat(status.get("zone_name")).isEqualTo("Koramangala");
    assertThatThrownBy(() -> service.setStatus(rider(), "BUSY", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    assertThatThrownBy(() -> service.setStatus(rider(), "ONLINE", Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ZONE");
    assertThatThrownBy(
            () ->
                service.setStatus(
                    new MedmatePrincipal(riderId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    "ONLINE",
                    zoneId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void onlineUsesPrimaryZoneWhenOmitted() {
    Map<String, Object> data = service.setStatus(rider(), "ONLINE", null);
    assertThat(data.get("zone_id")).isEqualTo(zoneId.toString());
  }

  @Test
  void getStatusShowsActiveOrderAsOnTrip() {
    service.setStatus(rider(), "ONLINE", zoneId);
    deliveries.active.set(new ActiveOrder(Ids.newId(), "OUT_FOR_DELIVERY", "HSR Layout", 8));
    Map<String, Object> status = service.getStatus(rider());
    assertThat(status.get("status")).isEqualTo("ON_TRIP");
    @SuppressWarnings("unchecked")
    Map<String, Object> ao = (Map<String, Object>) status.get("active_order");
    assertThat(ao.get("order_status")).isEqualTo("ON_TRIP");
  }

  private MedmatePrincipal rider() {
    return new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
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
        null,
        0,
        null,
        0L,
        0L,
        5,
        null,
        null,
        null,
        now,
        now);
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
      return new PageResult(List.copyOf(byId.values()), byId.size());
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
      return byId.values().stream()
          .filter(s -> s.riderId().equals(riderId))
          .mapToInt(s -> s.durationMinutes() == null ? 0 : s.durationMinutes())
          .sum();
    }

    @Override
    public Optional<ShiftRecord> findLatestClosedByRider(UUID riderId) {
      return byId.values().stream()
          .filter(s -> s.riderId().equals(riderId) && s.shiftEnd() != null)
          .findFirst();
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
      return all.stream()
          .filter(a -> a.riderId().equals(riderId) && a.reason() != null)
          .reduce((a, b) -> b);
    }
  }

  static final class FakeDeliveries implements ActiveDeliveryPort {
    final AtomicReference<ActiveOrder> active = new AtomicReference<>();
    final List<UUID> flagged = new CopyOnWriteArrayList<>();
    int liveOrders;

    @Override
    public Optional<ActiveOrder> findActiveByRider(UUID riderId) {
      return Optional.ofNullable(active.get());
    }

    @Override
    public int countLiveOrdersInZone(UUID zoneId) {
      return liveOrders;
    }

    @Override
    public void flagForMonitoring(UUID orderId, String reason) {
      flagged.add(orderId);
    }
  }

  static final class FakeFleet implements RiderFleetStore {
    @Override
    public FleetPage listFleet(FleetFilter filter) {
      return new FleetPage(List.of(), 0);
    }

    @Override
    public List<FleetRiderRow> listByZone(UUID zoneId) {
      return List.of();
    }

    @Override
    public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
      return Optional.empty();
    }

    @Override
    public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 0;
    }

    @Override
    public long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 0L;
    }
  }
}
