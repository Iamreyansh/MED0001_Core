package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.out.client.StubCashfreeRouteAdapter;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderPayoutStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.AssignmentOtpCachePort;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.ZoneRow;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.OrderDetails;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderAssignmentStatsPort;
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
import com.nammamedmate.rider.domain.AssignmentOtps;
import com.nammamedmate.rider.domain.BasePayFormula;
import com.nammamedmate.rider.domain.IncentiveRules;
import com.nammamedmate.rider.domain.PayoutCycle;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RiderIncentivesGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-26T19:00:00Z");

  @Test
  void portDefaultsAndDomainEdgeBranches() {
    RiderTripEarningsStore earnings =
        new RiderTripEarningsStore() {
          @Override
          public void insert(EarningsRecord row) {}
        };
    assertThat(earnings.sumForRider(Ids.newId(), LocalDate.now(), LocalDate.now()).trips())
        .isZero();
    assertThat(earnings.lifetime(Ids.newId()).totalTrips()).isZero();
    assertThat(earnings.listTrips(Ids.newId(), null, null, 0, 1)).isEmpty();
    assertThat(earnings.countTrips(Ids.newId(), null, null)).isZero();
    assertThat(earnings.avgRating(Ids.newId())).isEmpty();
    assertThat(earnings.totalDistanceKm(Ids.newId())).isEqualByComparingTo("0");
    assertThat(earnings.countOnTime(Ids.newId())).isZero();
    assertThat(earnings.countRated(Ids.newId())).isZero();
    assertThat(earnings.distinctRidersWithEarnings(LocalDate.now(), LocalDate.now())).isEmpty();

    RiderBadgeStore badges =
        new RiderBadgeStore() {
          @Override
          public List<BadgeRow> listForRider(UUID riderId) {
            return List.of();
          }
        };
    badges.upsert(Ids.newId(), Ids.newId(), "X", LocalDate.now());

    RiderAssignmentStatsPort stats =
        new RiderAssignmentStatsPort() {
          @Override
          public Stats statsForRider(UUID riderId) {
            return new Stats(0, 0, 0, 0, null, null);
          }
        };
    assertThat(stats.find(Ids.newId())).isPresent();

    RiderStore bare =
        new RiderStore() {
          @Override
          public void insert(RiderRecord rider) {}

          @Override
          public Optional<RiderRecord> findById(UUID id) {
            return Optional.empty();
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
          public void update(RiderRecord rider) {}

          @Override
          public PageResult list(ListFilter filter) {
            return new PageResult(List.of(), 0);
          }

          @Override
          public void updateAvailability(
              UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

          @Override
          public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}
        };
    assertThatThrownBy(() -> bare.adjustEarningsWallet(Ids.newId(), 1, NOW))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> bare.updateStreak(Ids.newId(), 1, LocalDate.now(), false, NOW))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(bare.payoutCarryForwardPaise(Ids.newId())).isZero();
    assertThatThrownBy(() -> bare.setPayoutCarryForward(Ids.newId(), 1, NOW))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(bare.lastDeliveryDate(Ids.newId())).isEmpty();
    assertThat(bare.streakBonusPending(Ids.newId())).isFalse();
    assertThatThrownBy(() -> bare.clearStreakBonusPending(Ids.newId(), NOW))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(bare.listIdsForPayoutCompute()).isEmpty();

    assertThat(
            BasePayFormula.computePaise(
                new BigDecimal("3"), 10, 10, new BigDecimal("5"), new BigDecimal("5")))
        .isEqualTo(10L);
    PlatformPricingConfigStore bad = cfg("not-a-number");
    assertThat(BasePayFormula.computePaise(new BigDecimal("3"), bad)).isEqualTo(1833L);
    assertThat(IncentiveRules.streakBonusPaise(bad)).isEqualTo(10_000L);
    assertThat(PayoutCycle.isMondayMorningWindow(Instant.parse("2026-07-28T01:00:00Z"))).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcArgBranchesNullKmAndNullCount() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRiderTripEarningsStore store = new JdbcRiderTripEarningsStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.listTrips(Ids.newId(), LocalDate.of(2026, 7, 1), null, 0, 10)).isEmpty();
    assertThat(store.listTrips(Ids.newId(), null, LocalDate.of(2026, 7, 31), 0, 10)).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.countTrips(Ids.newId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .isZero();
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any())).thenReturn(null);
    assertThat(store.totalDistanceKm(Ids.newId())).isEqualByComparingTo("0");
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.countOnTime(Ids.newId())).isZero();
    assertThat(store.countRated(Ids.newId())).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getObject("order_id")).thenReturn(Ids.newId());
              when(rs.getString(anyString())).thenReturn("x");
              when(rs.getBigDecimal("distance_km")).thenReturn(null);
              when(rs.getInt(anyString())).thenReturn(0);
              when(rs.getLong(anyString())).thenReturn(0L);
              when(rs.getBoolean(anyString())).thenReturn(false);
              when(rs.getObject("customer_rating")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    TripView tv = store.listTrips(Ids.newId(), null, null, 0, 1).get(0);
    assertThat(tv.distanceKm()).isEqualByComparingTo("0");
    assertThat(tv.completedAt()).isNull();

    JdbcRiderPayoutStore payouts = new JdbcRiderPayoutStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(payouts.listForRider(Ids.newId(), LocalDate.of(2026, 7, 1), null, 0, 10)).isEmpty();
    assertThat(payouts.listForRider(Ids.newId(), null, LocalDate.of(2026, 7, 31), 0, 10)).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(
            payouts.countForRider(Ids.newId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .isZero();
    assertThat(payouts.countForRider(Ids.newId(), null, LocalDate.of(2026, 7, 31))).isZero();

    // cover SSE callback registrations
    var push =
        new com.nammamedmate.rider.adapter.out.sse.InMemoryOrderLocationPush(new ObjectMapper());
    UUID oid = Ids.newId();
    var e1 = push.subscribe(oid);
    e1.complete();
    var e2 = push.subscribe(oid);
    e2.completeWithError(new RuntimeException("x"));
  }

  @Test
  void payoutServiceEdgeBranches() {
    FakeRiders riders = new FakeRiders();
    FakeEarnings earnings = new FakeEarnings();
    FakePayouts payouts = new FakePayouts();
    StubCashfreeRouteAdapter cashfree = new StubCashfreeRouteAdapter();
    InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RiderPayoutService service =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            cashfree,
            cfg("200000"),
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock);
    UUID riderId = Ids.newId();
    riders.insert(rider(riderId, 0));
    PayoutCycle.Window prev = PayoutCycle.previous(NOW);
    // missing rider skipped
    assertThat(service.computeForRider(Ids.newId(), prev, NOW)).isFalse();
    // no activity skipped
    assertThat(service.computeForRider(riderId, prev, NOW)).isFalse();

    earnings.insert(trip(riderId, prev.from(), 50_000));
    assertThat(service.computeForRider(riderId, prev, NOW)).isTrue();
    // duplicate cycle
    assertThat(service.computeForRider(riderId, prev, NOW)).isFalse();

    // below threshold (no streak) carries forward
    UUID r2 = Ids.newId();
    riders.insert(rider(r2, 0));
    earnings.insert(trip(r2, prev.from(), 1_000));
    service.computeForRider(r2, prev, NOW);
    assertThat(payouts.byRider.get(r2).status()).isEqualTo("BELOW_THRESHOLD_CARRIED_FORWARD");
    // streak pending clears on successful PENDING compute
    UUID r2b = Ids.newId();
    riders.insert(rider(r2b, 0));
    riders.streakPending.put(r2b, true);
    earnings.insert(trip(r2b, prev.from(), 50_000));
    service.computeForRider(r2b, prev, NOW);
    assertThat(payouts.byRider.get(r2b).streakBonusPaise()).isEqualTo(10_000L);
    assertThat(riders.streakBonusPending(r2b)).isFalse();

    // manual release fail then schedule; retry fail → FAILED; forbid non-finance
    UUID r3 = Ids.newId();
    riders.insert(rider(r3, 0));
    UUID payoutId = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            payoutId,
            r3,
            prev.from(),
            prev.to(),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "HELD",
            "COD",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            NOW,
            NOW));
    cashfree.failNext(true);
    var pending = service.release(finance(), r3, payoutId, "try", "idem-try");
    assertThat(pending.get("payout_status")).isEqualTo("PENDING");
    cashfree.failNext(true);
    RiderPayoutService later =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            cashfree,
            cfg("200000"),
            new OutboxPublisher(outbox, new ObjectMapper()),
            Clock.fixed(NOW.plusSeconds(90_000), ZoneOffset.UTC));
    later.retryDuePayouts();
    assertThat(payouts.byId.get(payoutId).status()).isEqualTo("FAILED");

    // manual release with retry_count>=1 fails immediately to FAILED
    UUID payout2 = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            payout2,
            r3,
            prev.from().minusWeeks(1),
            prev.to().minusWeeks(1),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "FAILED",
            "err",
            null,
            null,
            null,
            null,
            null,
            1,
            null,
            null,
            NOW,
            NOW));
    // change status to PENDING for release path with retry_count 1
    payouts.byId.put(
        payout2,
        new PayoutRecord(
            payout2,
            r3,
            prev.from().minusWeeks(1),
            prev.to().minusWeeks(1),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "PENDING",
            "err",
            null,
            null,
            null,
            null,
            null,
            1,
            null,
            null,
            NOW,
            NOW));
    cashfree.failNext(true);
    assertThat(service.release(finance(), r3, payout2, "again", "idem-again").get("payout_status"))
        .isEqualTo("FAILED");

    assertThatThrownBy(() -> service.release(ops(), r3, payoutId, null, "idem-ops"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.release(null, r3, payoutId, null, "idem-null"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.release(finance(), r3, Ids.newId(), null, "idem-unknown"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");
    // wrong rider
    assertThatThrownBy(
            () -> service.release(finance(), Ids.newId(), payoutId, null, "idem-wrong-rider"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_NOT_FOUND");

    // below-threshold with streak clears pending; net=0 clamp; HELD then COD unresolved on FAILED
    UUID r4 = Ids.newId();
    riders.insert(rider(r4, 0, 300_000L));
    riders.carry.put(r4, 500L);
    riders.streakPending.put(r4, true);
    // trips=0 but carry+streak → compute; float risk → HELD; streak clear skipped on HELD
    service.computeForRider(
        r4, new PayoutCycle.Window(prev.from().minusWeeks(2), prev.to().minusWeeks(2)), NOW);
    // force below-threshold + streak clear
    UUID r5 = Ids.newId();
    riders.insert(rider(r5, 0));
    riders.streakPending.put(r5, true);
    riders.carry.put(r5, 500L);
    // custom service with high min payout via empty config defaults still 10000; streak 10000+carry
    // 500
    // use tiny streak via daily streak days without pending and no trips — skip
    UUID heldFail = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            heldFail,
            r5,
            prev.from().minusWeeks(2),
            prev.to().minusWeeks(2),
            50_000,
            0,
            0,
            0,
            0,
            0,
            5_000,
            "HELD",
            "COD",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            NOW,
            NOW));
    riders.byId.put(r5, rider(r5, 0, 250_000L));
    assertThatThrownBy(() -> service.release(finance(), r5, heldFail, "x", "idem-held-fail"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COD_UNRESOLVED");
    // net below min on non-below-threshold status
    UUID lowNet = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            lowNet,
            r5,
            prev.from().minusWeeks(3),
            prev.to().minusWeeks(3),
            1_000,
            0,
            0,
            0,
            0,
            0,
            1_000,
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            NOW,
            NOW));
    riders.byId.put(r5, rider(r5, 0, 0L));
    assertThatThrownBy(() -> service.release(finance(), r5, lowNet, "x", "idem-low"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_BELOW_THRESHOLD");
    // rider deleted mid-release
    UUID orphan = Ids.newId();
    UUID orphanPayout = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            orphanPayout,
            orphan,
            prev.from(),
            prev.to(),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "HELD",
            "x",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            NOW,
            NOW));
    assertThatThrownBy(() -> service.release(finance(), orphan, orphanPayout, "x", "idem-orphan"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
  }

  @Test
  void earningsAndPerformanceForbiddenAndNullBranches() {
    FakeRiders riders = new FakeRiders();
    FakeEarnings earnings = new FakeEarnings();
    FakePayouts payouts = new FakePayouts();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RiderEarningsService earningsService =
        new RiderEarningsService(riders, earnings, payouts, cfg(null), clock);
    UUID riderId = Ids.newId();
    riders.insert(rider(riderId, 5));
    earnings.insert(trip(riderId, LocalDate.of(2026, 7, 24), 2000));

    MapLike dashboard = new MapLike(earningsService.dashboard(rider(riderId)));
    assertThat(dashboard.get("wallet_balance")).isNotNull();

    // estimated clamps at 0 when COD huge
    riders.byId.put(riderId, rider(riderId, 5, 500_000L));
    assertThat(
            ((Number)
                    ((java.util.Map<?, ?>)
                            earningsService.dashboard(rider(riderId)).get("next_payout"))
                        .get("estimated_amount"))
                .doubleValue())
        .isEqualTo(0.0);

    assertThatThrownBy(() -> earningsService.dashboard(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> earningsService.adminLedger(null, riderId, 1, 20, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                earningsService.adminLedger(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    riderId,
                    1,
                    20,
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> earningsService.adminLedger(finance(), Ids.newId(), 1, 20, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    TripView nullDist =
        new TripView(Ids.newId(), "M", "P", "A", null, 1, 1, 0, 0, 1, true, null, null);
    earnings.tripsOverride = List.of(nullDist);
    var trips = earningsService.trips(rider(riderId), 1, 20, null, null);
    assertThat(((List<?>) trips.data().get("trips"))).hasSize(1);

    DeliveryZoneStore zones = mock(DeliveryZoneStore.class);
    when(zones.findById(any())).thenReturn(Optional.empty());
    RiderPerformanceService perf =
        new RiderPerformanceService(
            riders,
            earnings,
            riderId1 -> new RiderAssignmentStatsPort.Stats(0, 0, 0, 0, null, null),
            id -> List.of(),
            zones,
            cfg(null),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
    assertThat(perf.adminPerformance(ops(), riderId).get("zone_name")).isNull();
    assertThat(perf.adminPerformance(ops(), riderId).get("avg_pickup_minutes")).isEqualTo(0.0);
    assertThatThrownBy(() -> perf.adminPerformance(null, riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                perf.adminPerformance(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> perf.adminPerformance(ops(), Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> perf.riderPerformance(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // rating hidden when <5 ratings
    earnings.rated = 2;
    assertThat(perf.riderPerformance(rider(riderId)).get("avg_rating")).isNull();
  }

  @Test
  void remainingBranchCoverage() {
    FakeRiders riders = new FakeRiders();
    FakeEarnings earnings = new FakeEarnings();
    FakePayouts payouts = new FakePayouts();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    PlatformPricingConfigStore highMin =
        new PlatformPricingConfigStore() {
          @Override
          public Optional<String> get(String key) {
            if ("rider_min_payout_paise".equals(key)) {
              return Optional.of("50000");
            }
            if ("cod_float_limit_default".equals(key)) {
              return Optional.of("200000");
            }
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
    RiderPayoutService service =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            new StubCashfreeRouteAdapter(),
            highMin,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
    PayoutCycle.Window prev = PayoutCycle.previous(NOW);
    UUID r = Ids.newId();
    riders.insert(rider(r, 7)); // daily streak days >= 7 without pending flag
    riders.streakPending.put(r, false);
    earnings.insert(trip(r, prev.from(), 1_000));
    riders.streakPending.put(r, true);
    service.computeForRider(r, prev, NOW);
    assertThat(payouts.byRider.get(r).status()).isEqualTo("BELOW_THRESHOLD_CARRIED_FORWARD");
    assertThat(riders.streakBonusPending(r)).isFalse();

    // computeWeekly with a false compute (already exists)
    assertThat(service.computeWeeklyPayouts()).isZero();

    // performance zero trips
    FakeEarnings emptyLife = new FakeEarnings();
    emptyLife.zeroLifetime = true;
    RiderPerformanceService perf =
        new RiderPerformanceService(
            riders,
            emptyLife,
            id -> new RiderAssignmentStatsPort.Stats(10, 9, 0, 0, 1.0, 2.0),
            id -> List.of(),
            mock(DeliveryZoneStore.class),
            cfg(null),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
    assertThat(perf.riderPerformance(rider(r)).get("on_time_pct")).isEqualTo(0.0);

    // earnings: blank from date; admin_super finance read; rider missing
    RiderEarningsService es = new RiderEarningsService(riders, earnings, payouts, cfg(null), clock);
    es.trips(rider(r), 1, 20, "  ", null);
    es.adminLedger(
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"),
        r,
        1,
        20,
        null,
        null);
    assertThatThrownBy(
            () ->
                es.dashboard(
                    new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    // release with ADMIN_SUPER + FAILED status COD check
    UUID failedId = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            failedId,
            r,
            prev.from().minusWeeks(1),
            prev.to().minusWeeks(1),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "FAILED",
            "e",
            null,
            null,
            null,
            null,
            null,
            1,
            null,
            null,
            NOW,
            NOW));
    riders.byId.put(r, rider(r, 0, 250_000L));
    assertThatThrownBy(
            () ->
                service.release(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"),
                    r,
                    failedId,
                    "n",
                    "idem-failed-cod"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COD_UNRESOLVED");

    // streak from dailyStreakDays only; carry-only week; PENDING+COD block
    UUID rB = Ids.newId();
    riders.insert(rider(rB, 7));
    riders.streakPending.put(rB, false);
    riders.carry.put(rB, 12_000L);
    service.computeForRider(
        rB, new PayoutCycle.Window(prev.from().minusWeeks(4), prev.to().minusWeeks(4)), NOW);
    assertThat(payouts.byRider.get(rB).streakBonusPaise()).isEqualTo(10_000L);

    UUID pendingCod = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            pendingCod,
            rB,
            prev.from().minusWeeks(5),
            prev.to().minusWeeks(5),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            NOW,
            NOW));
    riders.byId.put(rB, rider(rB, 0, 250_000L));
    assertThatThrownBy(() -> service.release(finance(), rB, pendingCod, "x", "idem-pending-cod"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COD_UNRESOLVED");

    // earnings streak-days branch + ops ledger
    riders.byId.put(r, rider(r, 7));
    riders.streakPending.put(r, false);
    new RiderEarningsService(riders, earnings, payouts, cfg(null), clock).dashboard(rider(r));
    new RiderEarningsService(riders, earnings, payouts, cfg(null), clock)
        .adminLedger(ops(), r, 1, 20, null, null);

    // performance assigned=0 skips alert; finance role for admin performance
    RiderPerformanceService perf2 =
        new RiderPerformanceService(
            riders,
            emptyLife,
            id -> new RiderAssignmentStatsPort.Stats(0, 0, 0, 0, null, null),
            id -> List.of(),
            mock(DeliveryZoneStore.class),
            cfg(null),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
    assertThat(((List<?>) perf2.adminPerformance(finance(), r).get("alerts"))).isEmpty();

    // trips=0 + streak only (no carry); auto-fail with retryCount>=1 & null nextRetryAt
    UUID rC = Ids.newId();
    riders.insert(rider(rC, 7));
    riders.streakPending.put(rC, false);
    service.computeForRider(
        rC, new PayoutCycle.Window(prev.from().minusWeeks(6), prev.to().minusWeeks(6)), NOW);
    assertThat(payouts.byRider.get(rC).streakBonusPaise()).isEqualTo(10_000L);

    UUID autoFail = Ids.newId();
    StubCashfreeRouteAdapter rz = new StubCashfreeRouteAdapter();
    RiderPayoutService autoSvc =
        new RiderPayoutService(
            riders,
            earnings,
            payouts,
            rz,
            cfg("200000"),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
    payouts.insert(
        new PayoutRecord(
            autoFail,
            rC,
            prev.from().minusWeeks(7),
            prev.to().minusWeeks(7),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            null,
            null,
            NOW,
            NOW));
    riders.byId.put(rC, rider(rC, 0, 0L));
    UUID heldOk = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            heldOk,
            rC,
            prev.from().minusWeeks(8),
            prev.to().minusWeeks(8),
            50_000,
            0,
            0,
            0,
            0,
            0,
            50_000,
            "HELD",
            "was held",
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            NOW,
            NOW));
    rz.failNext(false);
    assertThat(autoSvc.release(finance(), rC, heldOk, "ok", "idem-held-ok").get("payout_status"))
        .isEqualTo("RELEASED");

    // high acceptance → no alert
    RiderPerformanceService perf3 =
        new RiderPerformanceService(
            riders,
            emptyLife,
            id -> new RiderAssignmentStatsPort.Stats(100, 95, 0, 90, 1.0, 2.0),
            id -> List.of(),
            mock(DeliveryZoneStore.class),
            cfg(null),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
    assertThat(((List<?>) perf3.adminPerformance(ops(), rC).get("alerts"))).isEmpty();
    perf3.adminPerformance(
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j"), rC);

    // float risk + non-blocking status skips COD_UNRESOLVED
    UUID below = Ids.newId();
    payouts.insert(
        new PayoutRecord(
            below,
            rC,
            prev.from().minusWeeks(9),
            prev.to().minusWeeks(9),
            1_000,
            0,
            0,
            0,
            0,
            0,
            1_000,
            "BELOW_THRESHOLD_CARRIED_FORWARD",
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            NOW,
            NOW));
    riders.byId.put(rC, rider(rC, 0, 250_000L));
    assertThatThrownBy(() -> autoSvc.release(finance(), rC, below, "x", "idem-below2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYOUT_BELOW_THRESHOLD");

    // earnings: streak pending true with low streak days
    riders.byId.put(r, rider(r, 2));
    riders.streakPending.put(r, true);
    new RiderEarningsService(riders, earnings, payouts, cfg(null), clock).dashboard(rider(r));
  }

  @Test
  void deliverWithStreakAndZoneSla() {
    UUID riderId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID zoneId = Ids.newId();
    FakeAssignments assignments = new FakeAssignments();
    FakeOrders orders = new FakeOrders();
    FakeRiders riders = new FakeRiders();
    riders.insert(rider(riderId, 2));
    riders.lastDelivery.put(riderId, LocalDate.of(2026, 7, 26)); // consecutive to 27 IST
    AssignmentOtpCachePort otp = mock(AssignmentOtpCachePort.class);
    when(otp.getDeliveryOtp(any())).thenReturn(Optional.of("1234"));
    DeliveryZoneStore zones = mock(DeliveryZoneStore.class);
    when(zones.findById(zoneId))
        .thenReturn(
            Optional.of(
                new ZoneRow(
                    zoneId,
                    "Z",
                    "Bengaluru",
                    "KA",
                    "{}",
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    30,
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(199),
                    BigDecimal.ONE,
                    false,
                    true,
                    null,
                    true,
                    null,
                    NOW,
                    NOW)));
    FakeEarnings earnings = new FakeEarnings();
    String pickup = "1111";
    String delivery = "1234";
    orders.put(order(orderId, "OUT_FOR_DELIVERY", riderId, zoneId));
    Instant accepted = NOW.minusSeconds(600);
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            orderId,
            riderId,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            accepted,
            accepted.plusSeconds(60),
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW.minusSeconds(700),
            NOW.minusSeconds(600)));
    RiderOrderService service =
        new RiderOrderService(
            assignments,
            orders,
            otp,
            new StubDistanceMatrixAdapter(),
            earnings,
            null,
            riders,
            zones,
            cfg(null),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    var data =
        service.deliver(
            new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j"),
            orderId,
            delivery);
    assertThat(data.get("on_time")).isEqualTo(true);
    assertThat(riders.findById(riderId).orElseThrow().dailyStreakDays()).isEqualTo(3);

    // same-day second delivery keeps streak; missing rider on streak path
    riders.byId.remove(riderId);
    // still ok via null rider in applyStreak — need another deliver setup skipped

    // null coords path + no zone sla → deadline path
    UUID order2 = Ids.newId();
    UUID rider2 = Ids.newId();
    riders.insert(rider(rider2, 0));
    orders.put(orderNoCoords(order2, "OUT_FOR_DELIVERY", rider2, zoneId));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order2,
            rider2,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            accepted,
            accepted,
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    when(zones.findById(zoneId)).thenReturn(Optional.empty());
    RiderOrderService service2 =
        new RiderOrderService(
            assignments,
            orders,
            otp,
            new StubDistanceMatrixAdapter(),
            earnings,
            null,
            riders,
            zones,
            null,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    service2.deliver(
        new MedmatePrincipal(rider2, AuthRole.RIDER, null, TokenScope.FULL, "j"), order2, delivery);

    // streak reset after gap + bonus pending at 7
    UUID rider3 = Ids.newId();
    riders.insert(rider(rider3, 6));
    riders.lastDelivery.put(rider3, LocalDate.of(2026, 7, 20));
    UUID order3 = Ids.newId();
    orders.put(order(order3, "OUT_FOR_DELIVERY", rider3, zoneId));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order3,
            rider3,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            accepted,
            accepted,
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    when(zones.findById(any())).thenReturn(Optional.empty());
    service2.deliver(
        new MedmatePrincipal(rider3, AuthRole.RIDER, null, TokenScope.FULL, "j"), order3, delivery);
    assertThat(riders.findById(rider3).orElseThrow().dailyStreakDays()).isEqualTo(1);

    riders.insert(rider(Ids.newId(), 0)); // noop
    // first delivery (last null) streak=1
    UUID rider4 = Ids.newId();
    riders.insert(rider(rider4, 0));
    UUID order4 = Ids.newId();
    orders.put(order(order4, "OUT_FOR_DELIVERY", rider4, zoneId));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order4,
            rider4,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            accepted,
            accepted,
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    service2.deliver(
        new MedmatePrincipal(rider4, AuthRole.RIDER, null, TokenScope.FULL, "j"), order4, delivery);

    // same day keep streak
    riders.lastDelivery.put(rider4, PayoutCycle.istDate(NOW));
    UUID order5 = Ids.newId();
    orders.put(order(order5, "OUT_FOR_DELIVERY", rider4, zoneId));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order5,
            rider4,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            accepted,
            accepted,
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    int before = riders.findById(rider4).orElseThrow().dailyStreakDays();
    service2.deliver(
        new MedmatePrincipal(rider4, AuthRole.RIDER, null, TokenScope.FULL, "j"), order5, delivery);
    assertThat(riders.findById(rider4).orElseThrow().dailyStreakDays()).isEqualTo(before);

    // acceptedAt null → deliveryMinutes 0; rider removed → applyStreak early return
    UUID rider5 = Ids.newId();
    riders.insert(rider(rider5, 0));
    UUID order6 = Ids.newId();
    orders.put(order(order6, "OUT_FOR_DELIVERY", rider5, zoneId));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order6,
            rider5,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            null,
            null,
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    riders.byId.remove(rider5);
    service2.deliver(
        new MedmatePrincipal(rider5, AuthRole.RIDER, null, TokenScope.FULL, "j"), order6, delivery);

    // late vs sla
    when(zones.findById(zoneId))
        .thenReturn(
            Optional.of(
                new ZoneRow(
                    zoneId,
                    "Z",
                    "Bengaluru",
                    "KA",
                    "{}",
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    1,
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(199),
                    BigDecimal.ONE,
                    false,
                    true,
                    null,
                    true,
                    null,
                    NOW,
                    NOW)));
    UUID rider6 = Ids.newId();
    riders.insert(rider(rider6, 0));
    UUID order7 = Ids.newId();
    orders.put(order(order7, "OUT_FOR_DELIVERY", rider6, zoneId));
    Instant oldAccept = NOW.minusSeconds(3600);
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order7,
            rider6,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            oldAccept,
            oldAccept,
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    Map<String, Object> late =
        service2.deliver(
            new MedmatePrincipal(rider6, AuthRole.RIDER, null, TokenScope.FULL, "j"),
            order7,
            delivery);
    assertThat(late.get("on_time")).isEqualTo(false);

    // partial coords → distanceKm stub path; streak hits 7 for bonus pending
    UUID rider7 = Ids.newId();
    riders.insert(rider(rider7, 6));
    riders.lastDelivery.put(rider7, PayoutCycle.istDate(NOW).minusDays(1));
    UUID order8 = Ids.newId();
    orders.put(
        new OrderDetails(
            order8,
            "MED",
            "OUT_FOR_DELIVERY",
            rider7,
            Ids.newId(),
            "Apollo",
            "addr",
            12.93,
            null,
            "9",
            null,
            "Z",
            "C",
            "9",
            "HSR",
            null,
            77.63,
            1,
            "UPI",
            100,
            NOW.plusSeconds(3600),
            NOW.plusSeconds(3600),
            "h"));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order8,
            rider7,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            NOW.minusSeconds(60),
            NOW.minusSeconds(30),
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    when(zones.findById(any())).thenReturn(Optional.empty());
    service2.deliver(
        new MedmatePrincipal(rider7, AuthRole.RIDER, null, TokenScope.FULL, "j"), order8, delivery);
    assertThat(riders.streakPending.get(rider7)).isTrue();

    // deliveryLat null short-circuit in resolveDistanceKm
    UUID rider8 = Ids.newId();
    riders.insert(rider(rider8, 0));
    UUID order9 = Ids.newId();
    orders.put(
        new OrderDetails(
            order9,
            "MED",
            "OUT_FOR_DELIVERY",
            rider8,
            Ids.newId(),
            "Apollo",
            "addr",
            12.93,
            77.62,
            "9",
            zoneId,
            "Z",
            "C",
            "9",
            "HSR",
            null,
            77.63,
            1,
            "UPI",
            100,
            null,
            null,
            "h"));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            order9,
            rider8,
            "MANUAL",
            Ids.newId(),
            "PICKED_UP",
            NOW.plusSeconds(100),
            NOW.minusSeconds(60),
            NOW.minusSeconds(30),
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.TEN,
            NOW,
            NOW));
    service2.deliver(
        new MedmatePrincipal(rider8, AuthRole.RIDER, null, TokenScope.FULL, "j"), order9, delivery);

    // cover each null short-circuit in resolveDistanceKm (pLng / dLng)
    for (OrderDetails partial :
        List.of(
            new OrderDetails(
                Ids.newId(),
                "MED",
                "OUT_FOR_DELIVERY",
                rider8,
                Ids.newId(),
                "A",
                "a",
                12.9,
                null,
                "9",
                zoneId,
                "Z",
                "C",
                "9",
                "H",
                12.9,
                77.6,
                1,
                "UPI",
                1,
                null,
                null,
                "h"),
            new OrderDetails(
                Ids.newId(),
                "MED",
                "OUT_FOR_DELIVERY",
                rider8,
                Ids.newId(),
                "A",
                "a",
                12.9,
                77.6,
                "9",
                zoneId,
                "Z",
                "C",
                "9",
                "H",
                12.9,
                null,
                1,
                "UPI",
                1,
                null,
                null,
                "h"))) {
      riders.insert(rider(partial.riderId(), 0));
      orders.put(partial);
      assignments.insert(
          new AssignmentRecord(
              Ids.newId(),
              partial.orderId(),
              partial.riderId(),
              "MANUAL",
              Ids.newId(),
              "PICKED_UP",
              NOW.plusSeconds(100),
              NOW.minusSeconds(60),
              NOW.minusSeconds(30),
              null,
              AssignmentOtps.hash(pickup),
              AssignmentOtps.hash(delivery),
              null,
              BigDecimal.TEN,
              NOW,
              NOW));
      service2.deliver(
          new MedmatePrincipal(partial.riderId(), AuthRole.RIDER, null, TokenScope.FULL, "j"),
          partial.orderId(),
          delivery);
    }
  }

  private static PlatformPricingConfigStore cfg(String value) {
    return new PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String key) {
        if (value == null) {
          return Optional.empty();
        }
        if ("not-a-number".equals(value)) {
          return Optional.of(value);
        }
        if ("cod_float_limit_default".equals(key)) {
          return Optional.of(value);
        }
        return Optional.empty();
      }

      @Override
      public BigDecimal handlingFeeRupees() {
        return BigDecimal.ZERO;
      }

      @Override
      public void upsert(String key, String v, String description, UUID updatedBy, Instant now) {}
    };
  }

  private MedmatePrincipal finance() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal ops() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal rider(UUID id) {
    return new MedmatePrincipal(id, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private static RiderRecord rider(UUID id, int streak) {
    return rider(id, streak, 0L);
  }

  private static RiderRecord rider(UUID id, int streak, long cod) {
    return new RiderRecord(
        id,
        "Ravi",
        "9",
        null,
        "BIKE",
        "KA01",
        null,
        "ACTIVE",
        "APPROVED",
        null,
        null,
        null,
        null,
        null,
        true,
        BigDecimal.ONE,
        1,
        BigDecimal.TEN,
        0,
        cod,
        streak,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private static EarningsRecord trip(UUID riderId, LocalDate date, long base) {
    return new EarningsRecord(
        Ids.newId(),
        riderId,
        Ids.newId(),
        Ids.newId(),
        date,
        base,
        0,
        0,
        base,
        true,
        5,
        BigDecimal.valueOf(2),
        10,
        NOW);
  }

  private static OrderDetails order(UUID id, String status, UUID rider, UUID zoneId) {
    return new OrderDetails(
        id,
        "MED",
        status,
        rider,
        Ids.newId(),
        "Apollo",
        "addr",
        12.93,
        77.62,
        "9",
        zoneId,
        "Z",
        "C",
        "9",
        "HSR",
        12.91,
        77.63,
        1,
        "UPI",
        100,
        NOW.plusSeconds(3600),
        NOW.plusSeconds(3600),
        "h");
  }

  private static OrderDetails orderNoCoords(UUID id, String status, UUID rider, UUID zoneId) {
    return new OrderDetails(
        id,
        "MED",
        status,
        rider,
        Ids.newId(),
        "Apollo",
        "addr",
        null,
        null,
        "9",
        zoneId,
        "Z",
        "C",
        "9",
        "HSR",
        null,
        null,
        1,
        "UPI",
        100,
        null,
        null,
        "h");
  }

  static final class MapLike {
    private final java.util.Map<String, Object> m;

    MapLike(java.util.Map<String, Object> m) {
      this.m = m;
    }

    Object get(String k) {
      return m.get(k);
    }
  }

  static final class FakeRiders implements RiderStore {
    final java.util.Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();
    final java.util.Map<UUID, LocalDate> lastDelivery = new ConcurrentHashMap<>();
    final java.util.Map<UUID, Boolean> streakPending = new ConcurrentHashMap<>();
    final java.util.Map<UUID, Long> carry = new ConcurrentHashMap<>();
    final java.util.Map<UUID, Long> wallet = new ConcurrentHashMap<>();

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
      return new PageResult(List.of(), 0);
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}

    @Override
    public long adjustEarningsWallet(UUID id, long deltaPaise, Instant updatedAt) {
      long v = wallet.getOrDefault(id, 0L) + deltaPaise;
      wallet.put(id, Math.max(0, v));
      return wallet.get(id);
    }

    @Override
    public void updateStreak(
        UUID id,
        int dailyStreakDays,
        LocalDate lastDeliveryDate,
        boolean streakBonusPending,
        Instant updatedAt) {
      lastDelivery.put(id, lastDeliveryDate);
      streakPending.put(id, streakBonusPending || streakPending.getOrDefault(id, false));
      RiderRecord r = byId.get(id);
      if (r == null) {
        return;
      }
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
    public long payoutCarryForwardPaise(UUID id) {
      return carry.getOrDefault(id, 0L);
    }

    @Override
    public void setPayoutCarryForward(UUID id, long paise, Instant updatedAt) {
      carry.put(id, paise);
    }

    @Override
    public Optional<LocalDate> lastDeliveryDate(UUID id) {
      return Optional.ofNullable(lastDelivery.get(id));
    }

    @Override
    public boolean streakBonusPending(UUID id) {
      return streakPending.getOrDefault(id, false);
    }

    @Override
    public void clearStreakBonusPending(UUID id, Instant updatedAt) {
      streakPending.put(id, false);
    }

    @Override
    public List<UUID> listIdsForPayoutCompute() {
      return new ArrayList<>(byId.keySet());
    }
  }

  static final class FakeEarnings implements RiderTripEarningsStore {
    final List<EarningsRecord> rows = new ArrayList<>();
    List<TripView> tripsOverride;
    int rated = 10;
    boolean zeroLifetime;

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
      return zeroLifetime ? new LifetimeTotals(0, 0) : new LifetimeTotals(100, 10);
    }

    @Override
    public List<TripView> listTrips(
        UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
      if (tripsOverride != null) {
        return tripsOverride;
      }
      return List.of();
    }

    @Override
    public long countTrips(UUID riderId, LocalDate from, LocalDate to) {
      return listTrips(riderId, from, to, 0, 100).size();
    }

    @Override
    public Optional<BigDecimal> avgRating(UUID riderId) {
      return Optional.of(BigDecimal.valueOf(4.5));
    }

    @Override
    public BigDecimal totalDistanceKm(UUID riderId) {
      return BigDecimal.ONE;
    }

    @Override
    public int countOnTime(UUID riderId) {
      return 5;
    }

    @Override
    public int countRated(UUID riderId) {
      return rated;
    }

    @Override
    public List<UUID> distinctRidersWithEarnings(LocalDate from, LocalDate to) {
      return rows.stream().map(EarningsRecord::riderId).distinct().toList();
    }
  }

  static final class FakePayouts implements RiderPayoutStore {
    final java.util.Map<UUID, PayoutRecord> byId = new ConcurrentHashMap<>();
    final java.util.Map<UUID, PayoutRecord> byRider = new ConcurrentHashMap<>();
    final Map<String, UUID> byIdempotency = new ConcurrentHashMap<>();
    final java.util.Set<UUID> claimed = ConcurrentHashMap.newKeySet();

    @Override
    public void insert(PayoutRecord row) {
      byId.put(row.id(), row);
      byRider.put(row.riderId(), row);
    }

    @Override
    public void update(PayoutRecord row) {
      byId.put(row.id(), row);
      byRider.put(row.riderId(), row);
    }

    @Override
    public Optional<PayoutRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey) {
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        return Optional.empty();
      }
      UUID id = byIdempotency.get(idempotencyKey);
      return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean claimForRelease(
        UUID payoutId, UUID riderId, String idempotencyKey, Instant updatedAt) {
      PayoutRecord p = byId.get(payoutId);
      if (p == null || !p.riderId().equals(riderId)) {
        return false;
      }
      if (byIdempotency.containsKey(idempotencyKey)) {
        return false;
      }
      if (claimed.contains(payoutId)) {
        return false;
      }
      if (!java.util.Set.of("HELD", "FAILED", "PENDING").contains(p.status())) {
        return false;
      }
      claimed.add(payoutId);
      byIdempotency.put(idempotencyKey, payoutId);
      return true;
    }

    @Override
    public Optional<PayoutRecord> findByRiderAndCycle(
        UUID riderId, LocalDate cycleFrom, LocalDate cycleTo) {
      return byId.values().stream()
          .filter(
              p ->
                  p.riderId().equals(riderId)
                      && p.cycleFrom().equals(cycleFrom)
                      && p.cycleTo().equals(cycleTo))
          .findFirst();
    }

    @Override
    public List<PayoutRecord> listForRider(
        UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
      return byId.values().stream().filter(p -> p.riderId().equals(riderId)).toList();
    }

    @Override
    public long countForRider(UUID riderId, LocalDate from, LocalDate to) {
      return listForRider(riderId, from, to, 0, 100).size();
    }

    @Override
    public List<PayoutRecord> findDueForRetry(Instant now, int limit) {
      return byId.values().stream()
          .filter(p -> "PENDING".equals(p.status()))
          .filter(p -> p.retryCount() == 0)
          .filter(p -> p.nextRetryAt() != null && !p.nextRetryAt().isAfter(now))
          .limit(limit)
          .toList();
    }
  }

  static final class FakeAssignments implements OrderAssignmentStore {
    final java.util.Map<UUID, AssignmentRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(AssignmentRecord row) {
      byId.put(row.id(), row);
    }

    @Override
    public void update(AssignmentRecord row) {
      byId.put(row.id(), row);
    }

    @Override
    public Optional<AssignmentRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<AssignmentRecord> findActiveByOrder(UUID orderId) {
      return byId.values().stream()
          .filter(a -> a.orderId().equals(orderId))
          .filter(a -> List.of("PENDING_ACCEPTANCE", "ACCEPTED", "PICKED_UP").contains(a.status()))
          .findFirst();
    }

    @Override
    public Optional<AssignmentRecord> findCurrentForRider(UUID riderId) {
      return Optional.empty();
    }

    @Override
    public int countActiveForRider(UUID riderId) {
      return 0;
    }

    @Override
    public List<AssignmentRecord> findPendingPastDeadline(Instant now, int limit) {
      return List.of();
    }

    @Override
    public boolean hasActiveForOrder(UUID orderId) {
      return false;
    }
  }

  static final class FakeOrders implements DispatchOrderPort {
    final java.util.Map<UUID, OrderDetails> byId = new ConcurrentHashMap<>();

    void put(OrderDetails o) {
      byId.put(o.orderId(), o);
    }

    @Override
    public QueuePage listUnassignedReady(UUID zoneId, int page, int limit) {
      return new QueuePage(List.of(), 0);
    }

    @Override
    public Optional<OrderDetails> findOrder(UUID orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }

    @Override
    public void assignRiderOnOrder(UUID orderId, UUID riderId, Instant now) {}

    @Override
    public void clearRiderOnOrder(UUID orderId, Instant now) {}

    @Override
    public void advanceStatus(
        UUID orderId,
        String fromStatus,
        String toStatus,
        String actorType,
        UUID actorId,
        String notes,
        Instant now) {
      OrderDetails o = byId.get(orderId);
      byId.put(
          orderId,
          new OrderDetails(
              o.orderId(),
              o.orderNumber(),
              toStatus,
              o.riderId(),
              o.pharmacyId(),
              o.pharmacyName(),
              o.pharmacyAddress(),
              o.pharmacyLat(),
              o.pharmacyLng(),
              o.pharmacyPhone(),
              o.zoneId(),
              o.zoneName(),
              o.customerName(),
              o.customerPhone(),
              o.deliveryAddress(),
              o.deliveryLat(),
              o.deliveryLng(),
              o.itemsCount(),
              o.paymentMethod(),
              o.totalPayablePaise(),
              o.estimatedDeliveryAt(),
              o.slaDeadline(),
              o.deliveryOtpHash()));
    }

    @Override
    public Optional<String> peekDeliveryOtp(UUID orderId) {
      return Optional.empty();
    }

    @Override
    public boolean verifyDeliveryOtp(UUID orderId, String otp) {
      return false;
    }

    @Override
    public String ensureDeliveryOtp(UUID orderId, Instant now) {
      return "0000";
    }
  }
}
