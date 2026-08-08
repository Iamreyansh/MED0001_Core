package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.RiderEarningsService.LedgerResult;
import com.nammamedmate.rider.application.RiderEarningsService.TripsResult;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.ZoneRow;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderAssignmentStatsPort;
import com.nammamedmate.rider.application.port.out.RiderAssignmentStatsPort.Stats;
import com.nammamedmate.rider.application.port.out.RiderBadgeStore;
import com.nammamedmate.rider.application.port.out.RiderBadgeStore.BadgeRow;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore.PayoutRecord;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.EarningsRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.LifetimeTotals;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.PeriodTotals;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.TripView;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiderEarningsPerformanceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:30:00Z");

  private FakeRiders riders;
  private FakeEarnings earnings;
  private FakePayouts payouts;
  private FakeStats stats;
  private FakeBadges badges;
  private InMemoryOutboxStore outbox;
  private RiderEarningsService earningsService;
  private RiderPerformanceService performanceService;
  private UUID riderId;
  private UUID zoneId;

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    earnings = new FakeEarnings();
    payouts = new FakePayouts();
    stats = new FakeStats();
    badges = new FakeBadges();
    outbox = new InMemoryOutboxStore();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    PlatformPricingConfigStore cfg = cfg();
    earningsService = new RiderEarningsService(riders, earnings, payouts, cfg, clock);
    DeliveryZoneStore zones = mock(DeliveryZoneStore.class);
    performanceService =
        new RiderPerformanceService(
            riders,
            earnings,
            stats,
            badges,
            zones,
            cfg,
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock);
    riderId = Ids.newId();
    zoneId = Ids.newId();
    when(zones.findById(any()))
        .thenReturn(
            Optional.of(
                new ZoneRow(
                    zoneId,
                    "Koramangala",
                    "Bengaluru",
                    "KA",
                    "{}",
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    30,
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(500),
                    BigDecimal.ONE,
                    false,
                    true,
                    null,
                    true,
                    null,
                    NOW,
                    NOW)));
    riders.insert(rider(riderId, zoneId, 5));
    earnings.insert(
        new EarningsRecord(
            Ids.newId(),
            riderId,
            Ids.newId(),
            Ids.newId(),
            LocalDate.of(2026, 7, 24),
            2000,
            500,
            0,
            2500,
            true,
            5,
            BigDecimal.valueOf(2.4),
            14,
            NOW));
    stats.stats = new Stats(100, 94, 2, 90, 6.4, 17.8);
    badges.rows.add(new BadgeRow("SPEED_STAR", LocalDate.of(2026, 7, 1)));
  }

  @Test
  void earningsDashboard() {
    Map<String, Object> data = earningsService.dashboard(rider());
    assertThat(data.get("rider_id")).isEqualTo(riderId.toString());
    assertThat(((Map<?, ?>) data.get("today")).get("trips")).isEqualTo(1);
    assertThat(((Map<?, ?>) data.get("streak")).get("current_days")).isEqualTo(5);
    assertThat(((Map<?, ?>) data.get("next_payout")).get("date")).isNotNull();
  }

  @Test
  void tripsAndLedger() {
    TripsResult trips = earningsService.trips(rider(), 1, 20, "2026-07-01", "2026-07-31");
    assertThat(((List<?>) trips.data().get("trips"))).hasSize(1);
    UUID payoutId = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            payoutId,
            riderId,
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 20),
            168000,
            15000,
            7000,
            10000,
            0,
            50000,
            150000,
            "RELEASED",
            null,
            "pout_x",
            "RPX-1",
            null,
            Ids.newId(),
            NOW,
            0,
            null,
            null,
            NOW,
            NOW));
    LedgerResult ledger =
        earningsService.adminLedger(finance(), riderId, 1, 20, "2026-07-01", "2026-07-31");
    assertThat(((List<?>) ledger.data().get("ledger"))).hasSize(1);
  }

  @Test
  void ac008_acceptanceRatePct() {
    Map<String, Object> data = performanceService.riderPerformance(rider());
    assertThat(data.get("acceptance_rate_pct")).isEqualTo(94.0);
    assertThat(data.get("badges")).isInstanceOf(List.class);
  }

  @Test
  void adminPerformanceAlertsLowAcceptance() {
    stats.stats = new Stats(100, 60, 5, 50, 8.0, 20.0);
    Map<String, Object> data = performanceService.adminPerformance(ops(), riderId);
    assertThat(data.get("name")).isEqualTo("Ravi");
    assertThat(data.get("acceptance_rate_pct")).isEqualTo(60.0);
    assertThat(((List<?>) data.get("alerts"))).isNotEmpty();
    assertThat(outbox.all()).anyMatch(m -> m.type().contains("acceptance_rate_low"));
  }

  @Test
  void forbiddenRoles() {
    assertThatThrownBy(() -> earningsService.dashboard(ops()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> performanceService.riderPerformance(ops()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void streakResetsAfterMissedDay() {
    Instant t = NOW;
    riders.lastDelivery.put(riderId, LocalDate.of(2026, 7, 22));
    riders.byId.put(riderId, rider(riderId, zoneId, 4));
    // deliver on 24th after missing 23rd → streak resets to 1
    var orderSvc =
        new Object() {
          void apply() {
            LocalDate deliveryDate = LocalDate.of(2026, 7, 24);
            LocalDate last = riders.lastDeliveryDate(riderId).orElse(null);
            int streak;
            if (last == null) {
              streak = 1;
            } else if (last.equals(deliveryDate)) {
              streak = riders.findById(riderId).orElseThrow().dailyStreakDays();
            } else if (last.plusDays(1).equals(deliveryDate)) {
              streak = riders.findById(riderId).orElseThrow().dailyStreakDays() + 1;
            } else {
              streak = 1;
            }
            riders.updateStreak(riderId, streak, deliveryDate, streak >= 7, t);
          }
        };
    orderSvc.apply();
    assertThat(riders.findById(riderId).orElseThrow().dailyStreakDays()).isEqualTo(1);
  }

  private MedmatePrincipal rider() {
    return new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal finance() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal ops() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private static RiderRecord rider(UUID id, UUID zoneId, int streak) {
    return new RiderRecord(
        id,
        "Ravi",
        "9000000001",
        null,
        "BIKE",
        "KA01AB1234",
        zoneId,
        "ACTIVE",
        "APPROVED",
        null,
        null,
        null,
        null,
        null,
        true,
        BigDecimal.valueOf(4.72),
        1924,
        BigDecimal.valueOf(91.2),
        216000,
        0,
        streak,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private static PlatformPricingConfigStore cfg() {
    return new PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String key) {
        return Optional.empty();
      }

      @Override
      public BigDecimal handlingFeeRupees() {
        return BigDecimal.ZERO;
      }

      @Override
      public void upsert(
          String key, String value, String description, UUID updatedBy, Instant now) {}
    };
  }

  static final class FakeRiders implements RiderStore {
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();
    final Map<UUID, LocalDate> lastDelivery = new ConcurrentHashMap<>();
    final Map<UUID, Integer> streak = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
      streak.put(rider.id(), rider.dailyStreakDays());
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
      return new PageResult(List.of(), 0);
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}

    @Override
    public void updateStreak(
        UUID id,
        int dailyStreakDays,
        LocalDate lastDeliveryDate,
        boolean streakBonusPending,
        Instant updatedAt) {
      streak.put(id, dailyStreakDays);
      lastDelivery.put(id, lastDeliveryDate);
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
              dailyStreakDays,
              r.blockedReason(),
              r.blockedBy(),
              r.blockedAt(),
              r.createdAt(),
              updatedAt));
    }

    @Override
    public Optional<LocalDate> lastDeliveryDate(UUID id) {
      return Optional.ofNullable(lastDelivery.get(id));
    }
  }

  static final class FakeEarnings implements RiderTripEarningsStore {
    final List<EarningsRecord> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(EarningsRecord row) {
      rows.add(row);
    }

    @Override
    public PeriodTotals sumForRider(UUID riderId, LocalDate from, LocalDate to) {
      long base = 0, tip = 0, inc = 0, total = 0;
      int trips = 0;
      for (EarningsRecord r : rows) {
        if (r.riderId().equals(riderId)
            && !r.deliveryDate().isBefore(from)
            && !r.deliveryDate().isAfter(to)) {
          base += r.basePayPaise();
          tip += r.tipPaise();
          inc += r.incentiveBonusPaise();
          total += r.totalPaise();
          trips++;
        }
      }
      return new PeriodTotals(base, inc, tip, total, trips);
    }

    @Override
    public LifetimeTotals lifetime(UUID riderId) {
      long total =
          rows.stream()
              .filter(r -> r.riderId().equals(riderId))
              .mapToLong(EarningsRecord::totalPaise)
              .sum();
      int trips = (int) rows.stream().filter(r -> r.riderId().equals(riderId)).count();
      return new LifetimeTotals(total, trips);
    }

    @Override
    public List<TripView> listTrips(
        UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
      return rows.stream()
          .filter(r -> r.riderId().equals(riderId))
          .map(
              r ->
                  new TripView(
                      r.orderId(),
                      "MED-1",
                      "Apollo",
                      "HSR",
                      r.distanceKm(),
                      r.durationMinutes() == null ? 0 : r.durationMinutes(),
                      r.basePayPaise(),
                      r.tipPaise(),
                      r.incentiveBonusPaise(),
                      r.totalPaise(),
                      r.onTime(),
                      r.customerRating(),
                      r.createdAt()))
          .toList();
    }

    @Override
    public long countTrips(UUID riderId, LocalDate from, LocalDate to) {
      return listTrips(riderId, from, to, 0, 1000).size();
    }

    @Override
    public Optional<BigDecimal> avgRating(UUID riderId) {
      return Optional.of(BigDecimal.valueOf(4.72));
    }

    @Override
    public BigDecimal totalDistanceKm(UUID riderId) {
      return BigDecimal.valueOf(4821.3);
    }

    @Override
    public int countOnTime(UUID riderId) {
      return (int) rows.stream().filter(r -> r.riderId().equals(riderId) && r.onTime()).count();
    }

    @Override
    public int countRated(UUID riderId) {
      return 10;
    }
  }

  static final class FakePayouts implements RiderPayoutStore {
    final List<PayoutRecord> rows = new ArrayList<>();

    @Override
    public void insert(PayoutRecord row) {
      rows.add(row);
    }

    @Override
    public void update(PayoutRecord row) {}

    @Override
    public Optional<PayoutRecord> findById(UUID id) {
      return rows.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    @Override
    public Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey) {
      return Optional.empty();
    }

    @Override
    public boolean claimForRelease(
        UUID payoutId, UUID riderId, String idempotencyKey, Instant updatedAt) {
      return false;
    }

    @Override
    public Optional<PayoutRecord> findByRiderAndCycle(
        UUID riderId, LocalDate cycleFrom, LocalDate cycleTo) {
      return Optional.empty();
    }

    @Override
    public List<PayoutRecord> listForRider(
        UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
      return rows.stream().filter(p -> p.riderId().equals(riderId)).toList();
    }

    @Override
    public long countForRider(UUID riderId, LocalDate from, LocalDate to) {
      return listForRider(riderId, from, to, 0, 100).size();
    }

    @Override
    public List<PayoutRecord> findDueForRetry(Instant now, int limit) {
      return List.of();
    }
  }

  static final class FakeStats implements RiderAssignmentStatsPort {
    Stats stats = new Stats(0, 0, 0, 0, null, null);

    @Override
    public Stats statsForRider(UUID riderId) {
      return stats;
    }
  }

  static final class FakeBadges implements RiderBadgeStore {
    final List<BadgeRow> rows = new ArrayList<>();

    @Override
    public List<BadgeRow> listForRider(UUID riderId) {
      return rows;
    }
  }
}
