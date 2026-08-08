package com.nammamedmate.rider.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.adapter.in.web.AdminRiderPayoutController;
import com.nammamedmate.rider.adapter.in.web.RiderEarningsController;
import com.nammamedmate.rider.adapter.out.client.StubRazorpayRouteAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderAssignmentStatsAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderBadgeStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderPayoutStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderTripEarningsStore;
import com.nammamedmate.rider.application.RiderEarningsService;
import com.nammamedmate.rider.application.RiderPayoutService;
import com.nammamedmate.rider.application.RiderPerformanceService;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore.PayoutRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.EarningsRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class IncentivesAdapterCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void tripEarningsStoreQueries() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRiderTripEarningsStore store = new JdbcRiderTripEarningsStore(jdbc);
    UUID riderId = Ids.newId();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    store.insert(
        new EarningsRecord(
            Ids.newId(),
            riderId,
            Ids.newId(),
            Ids.newId(),
            LocalDate.of(2026, 7, 24),
            2000,
            0,
            0,
            2000,
            true,
            5,
            BigDecimal.valueOf(2.4),
            14,
            now));
    verify(jdbc)
        .update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong(anyString())).thenReturn(2000L);
              when(rs.getInt(anyString())).thenReturn(1);
              return ex.extractData(rs);
            });
    assertThat(
            store.sumForRider(riderId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)).trips())
        .isEqualTo(1);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("total_sum")).thenReturn(2000L);
              when(rs.getInt("trips")).thenReturn(1);
              return ex.extractData(rs);
            });
    assertThat(store.lifetime(riderId).totalTrips()).isEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("order_id")).thenReturn(Ids.newId());
              when(rs.getString("order_number")).thenReturn("MED-1");
              when(rs.getString("pickup_pharmacy")).thenReturn("Apollo");
              when(rs.getString("delivery_area")).thenReturn("HSR");
              when(rs.getBigDecimal("distance_km")).thenReturn(BigDecimal.valueOf(2.4));
              when(rs.getInt("duration_minutes")).thenReturn(14);
              when(rs.getLong(anyString())).thenReturn(2000L);
              when(rs.getBoolean("on_time")).thenReturn(true);
              when(rs.getObject("customer_rating")).thenReturn(5);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listTrips(riderId, null, null, 0, 20)).hasSize(1);
    assertThat(store.listTrips(riderId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 0, 20))
        .hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThat(store.countTrips(riderId, null, null)).isEqualTo(1L);
    assertThat(store.countTrips(riderId, LocalDate.of(2026, 7, 1), null)).isEqualTo(1L);
    assertThat(store.countTrips(riderId, null, LocalDate.of(2026, 7, 31))).isEqualTo(1L);

    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any()))
        .thenReturn(BigDecimal.valueOf(4.5));
    assertThat(store.avgRating(riderId)).contains(BigDecimal.valueOf(4.5));
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any()))
        .thenReturn(BigDecimal.valueOf(10));
    assertThat(store.totalDistanceKm(riderId)).isEqualByComparingTo("10.0");
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(3);
    assertThat(store.countOnTime(riderId)).isEqualTo(3);
    assertThat(store.countRated(riderId)).isEqualTo(3);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("rider_id")).thenReturn(riderId);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store.distinctRidersWithEarnings(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .contains(riderId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void payoutAndBadgeAndStatsAndStub() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRiderPayoutStore payouts = new JdbcRiderPayoutStore(jdbc);
    UUID riderId = Ids.newId();
    UUID id = Ids.newId();
    Instant now = Instant.parse("2026-07-27T01:00:00Z");
    PayoutRecord row =
        new PayoutRecord(
            id,
            riderId,
            LocalDate.of(2026, 7, 13),
            LocalDate.of(2026, 7, 19),
            10000,
            0,
            0,
            0,
            0,
            0,
            10000,
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            now.plusSeconds(3600),
            now,
            now,
            now);
    payouts.insert(row);
    payouts.update(row);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(row, now);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(payouts.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(row, now), 0));
            });
    assertThat(payouts.findByRiderAndCycle(riderId, row.cycleFrom(), row.cycleTo())).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(row, now), 0));
            });
    assertThat(payouts.listForRider(riderId, null, null, 0, 20)).hasSize(1);
    assertThat(payouts.listForRider(riderId, row.cycleFrom(), row.cycleTo(), 0, 20)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThat(payouts.countForRider(riderId, null, null)).isEqualTo(1L);
    assertThat(payouts.countForRider(riderId, row.cycleFrom(), null)).isEqualTo(1L);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), anyInt()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(row, now), 0));
            });
    assertThat(payouts.findDueForRetry(now.plusSeconds(7200), 10)).hasSize(1);

    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    assertThat(payouts.claimForRelease(id, riderId, "idem-jdbc", now)).isTrue();
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    assertThat(payouts.claimForRelease(id, riderId, "idem-jdbc-2", now)).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("idem-jdbc")))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(row, now), 0));
            });
    assertThat(payouts.findByIdempotencyKey("idem-jdbc")).isPresent();
    assertThat(payouts.findByIdempotencyKey(" ")).isEmpty();
    assertThat(payouts.findByIdempotencyKey(null)).isEmpty();

    JdbcRiderBadgeStore badges = new JdbcRiderBadgeStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("badge")).thenReturn("SPEED_STAR");
              when(rs.getDate("earned_at")).thenReturn(Date.valueOf("2026-07-01"));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(badges.listForRider(riderId)).hasSize(1);
    badges.upsert(Ids.newId(), riderId, "SPEED_STAR", LocalDate.of(2026, 7, 1));

    JdbcRiderAssignmentStatsAdapter stats = new JdbcRiderAssignmentStatsAdapter(jdbc);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("assigned")).thenReturn(10L);
              when(rs.getLong("accepted")).thenReturn(9L);
              when(rs.getLong("cancelled")).thenReturn(1L);
              when(rs.getLong("delivered")).thenReturn(8L);
              when(rs.getObject("avg_pickup")).thenReturn(6.4);
              when(rs.getObject("avg_delivery")).thenReturn(17.8);
              return ex.extractData(rs);
            });
    assertThat(stats.statsForRider(riderId).accepted()).isEqualTo(9L);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong(anyString())).thenReturn(0L);
              when(rs.getObject("avg_pickup")).thenReturn(null);
              when(rs.getObject("avg_delivery")).thenReturn(null);
              return ex.extractData(rs);
            });
    assertThat(stats.statsForRider(riderId).avgPickupMinutes()).isNull();

    StubRazorpayRouteAdapter razorpay = new StubRazorpayRouteAdapter();
    assertThat(razorpay.disburse(riderId, 10000, id).success()).isTrue();
    razorpay.failNext(true);
    assertThat(razorpay.disburse(riderId, 10000, id).success()).isFalse();

    JdbcRiderStore riders = new JdbcRiderStore(jdbc);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5000L);
    assertThat(riders.adjustEarningsWallet(riderId, 100L, now)).isEqualTo(5000L);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(riders.adjustEarningsWallet(riderId, 100L, now)).isZero();
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> riders.adjustEarningsWallet(riderId, 100L, now))
        .isInstanceOf(IllegalStateException.class);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    riders.updateStreak(riderId, 3, LocalDate.of(2026, 7, 24), true, now);
    riders.updateStreak(riderId, 0, null, false, now);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(2500L);
    assertThat(riders.payoutCarryForwardPaise(riderId)).isEqualTo(2500L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(riders.payoutCarryForwardPaise(riderId)).isZero();
    riders.setPayoutCarryForward(riderId, 1000L, now);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getDate("last_delivery_date")).thenReturn(Date.valueOf("2026-07-24"));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(riders.lastDeliveryDate(riderId)).contains(LocalDate.of(2026, 7, 24));
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(riders.lastDeliveryDate(riderId)).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(true);
    assertThat(riders.streakBonusPending(riderId)).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(null);
    assertThat(riders.streakBonusPending(riderId)).isFalse();
    riders.clearStreakBonusPending(riderId, now);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(riderId);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(riders.listIdsForPayoutCompute()).contains(riderId);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getDate("last_delivery_date")).thenReturn(null);
              return java.util.Arrays.asList(mapper.mapRow(rs, 0));
            });
    assertThat(riders.lastDeliveryDate(riderId)).isEmpty();

    RiderEarningsService earningsSvc = mock(RiderEarningsService.class);
    RiderPerformanceService perfSvc = mock(RiderPerformanceService.class);
    RiderPayoutService payoutSvc = mock(RiderPayoutService.class);
    when(earningsSvc.dashboard(any())).thenReturn(Map.of("ok", true));
    when(perfSvc.riderPerformance(any())).thenReturn(Map.of("ok", true));
    when(earningsSvc.trips(any(), any(), any(), any(), any()))
        .thenReturn(
            new RiderEarningsService.TripsResult(
                Map.of("trips", List.of()),
                com.nammamedmate.kernel.api.PaginationMeta.of(1, 20, 0)));
    when(earningsSvc.adminLedger(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new RiderEarningsService.LedgerResult(
                Map.of("ledger", List.of()),
                com.nammamedmate.kernel.api.PaginationMeta.of(1, 20, 0)));
    when(perfSvc.adminPerformance(any(), any())).thenReturn(Map.of("ok", true));
    when(payoutSvc.release(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("payout_status", "RELEASED"));
    RiderEarningsController riderCtrl = new RiderEarningsController(earningsSvc, perfSvc);
    AdminRiderPayoutController adminCtrl =
        new AdminRiderPayoutController(earningsSvc, perfSvc, payoutSvc);
    MedmatePrincipal principal =
        new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
    assertThat(riderCtrl.earnings(principal).success()).isTrue();
    assertThat(riderCtrl.performance(principal).success()).isTrue();
    assertThat(riderCtrl.trips(principal, 1, 20, null, null).success()).isTrue();
    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThat(adminCtrl.ledger(finance, riderId, 1, 20, null, null).success()).isTrue();
    assertThat(adminCtrl.performance(finance, riderId).success()).isTrue();
    assertThat(
            adminCtrl
                .release(
                    finance,
                    riderId,
                    "idem-ctrl-1",
                    new AdminRiderPayoutController.ReleaseRequest(id, "n"))
                .success())
        .isTrue();
    assertThat(adminCtrl.release(finance, riderId, "idem-ctrl-2", null).success()).isTrue();
  }

  private static ResultSet mockRs(PayoutRecord row, Instant now) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(row.id());
    when(rs.getObject("rider_id")).thenReturn(row.riderId());
    when(rs.getDate("cycle_from")).thenReturn(Date.valueOf(row.cycleFrom()));
    when(rs.getDate("cycle_to")).thenReturn(Date.valueOf(row.cycleTo()));
    when(rs.getLong(anyString())).thenReturn(10000L);
    when(rs.getString("status")).thenReturn(row.status());
    when(rs.getString("hold_reason")).thenReturn(null);
    when(rs.getString("razorpay_payout_id")).thenReturn(null);
    when(rs.getString("payout_reference")).thenReturn(null);
    when(rs.getString("release_notes")).thenReturn(null);
    when(rs.getObject("released_by")).thenReturn(null);
    when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("released_at")).thenReturn(null);
    when(rs.getTimestamp("next_retry_at")).thenReturn(null);
    when(rs.getTimestamp("last_attempt_at")).thenReturn(null);
    when(rs.getInt("retry_count")).thenReturn(0);
    return rs;
  }
}
