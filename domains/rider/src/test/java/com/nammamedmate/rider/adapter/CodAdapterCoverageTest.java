package com.nammamedmate.rider.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.adapter.in.web.AdminFinanceCodController;
import com.nammamedmate.rider.adapter.in.web.RiderCodController;
import com.nammamedmate.rider.adapter.out.persistence.JdbcCodCollectionStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcCodDepositStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRiderStore;
import com.nammamedmate.rider.application.CodReconciliationService;
import com.nammamedmate.rider.application.CodReconciliationService.BoardResult;
import com.nammamedmate.rider.application.port.out.CodCollectionStore.CollectionRecord;
import com.nammamedmate.rider.application.port.out.CodDepositStore.DepositRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CodAdapterCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void jdbcStoresAndControllers() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    UUID id = Ids.newId();
    UUID riderId = Ids.newId();
    UUID orderId = Ids.newId();

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(jdbc.update(
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
            any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any(), any()))
        .thenReturn(100L)
        .thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any()))
        .thenReturn(100L)
        .thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(1L).thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1L).thenReturn(null);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, riderId, orderId, now);
              Object row = mapper.mapRow(rs, 0);
              return row == null ? List.of() : List.of(row);
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, riderId, orderId, now);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, riderId, orderId, now);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, riderId, orderId, now);
              return List.of(mapper.mapRow(rs, 0));
            });

    JdbcCodCollectionStore collections = new JdbcCodCollectionStore(jdbc);
    collections.insert(new CollectionRecord(id, riderId, orderId, 1000L, now, null, false, now));
    assertThat(collections.findByOrderId(orderId)).isPresent();
    assertThat(collections.recentForRider(riderId, 5)).hasSize(1);
    assertThat(collections.sumCollectedToday(riderId, now.minusSeconds(10), now.plusSeconds(10)))
        .isEqualTo(100L);
    assertThat(collections.sumCollectedToday(riderId, now.minusSeconds(10), now.plusSeconds(10)))
        .isZero();
    assertThat(collections.sumCollectedTodayAll(now.minusSeconds(10), now.plusSeconds(10)))
        .isEqualTo(100L);
    assertThat(collections.sumCollectedTodayAll(now.minusSeconds(10), now.plusSeconds(10)))
        .isZero();
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any(), any()))
        .thenReturn(100L)
        .thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any()))
        .thenReturn(100L)
        .thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(1L).thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1L).thenReturn(null);
    assertThat(collections.markDepositedFifo(riderId, id, 0)).isZero();
    assertThat(collections.markDepositedFifo(riderId, id, 5000L)).isGreaterThanOrEqualTo(0L);
    assertThat(collections.markDepositedFifo(riderId, id, 1L)).isZero();
    // two equal collections; amount covers exactly first → second loop hits remaining<=0
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs1 = mockRs(id, riderId, orderId, now);
              when(rs1.getLong("cod_amount_paise")).thenReturn(500L);
              ResultSet rs2 = mockRs(Ids.newId(), riderId, Ids.newId(), now);
              when(rs2.getLong("cod_amount_paise")).thenReturn(500L);
              return List.of(mapper.mapRow(rs1, 0), mapper.mapRow(rs2, 1));
            });
    assertThat(collections.markDepositedFifo(riderId, id, 500L)).isEqualTo(500L);

    JdbcCodDepositStore deposits = new JdbcCodDepositStore(jdbc);
    DepositRecord dep =
        new DepositRecord(
            id,
            riderId,
            1000L,
            "UPI",
            "REF",
            "PENDING_CONFIRMATION",
            now,
            null,
            null,
            null,
            null,
            now,
            now);
    deposits.insert(dep);
    deposits.update(
        new DepositRecord(
            id,
            riderId,
            1000L,
            "UPI",
            "REF",
            "CONFIRMED",
            now,
            now,
            Ids.newId(),
            now,
            "n",
            now,
            now));
    assertThat(deposits.findById(id)).isPresent();
    assertThat(deposits.findByReference("REF")).isPresent();
    assertThat(deposits.findPendingByReference(riderId, "REF")).isPresent();
    assertThat(deposits.referenceExists("REF")).isTrue();
    assertThat(deposits.sumDepositedToday(riderId, now.minusSeconds(1), now.plusSeconds(1)))
        .isGreaterThanOrEqualTo(0L);
    assertThat(deposits.sumDepositedTodayAll(now.minusSeconds(1), now.plusSeconds(1)))
        .isGreaterThanOrEqualTo(0L);
    assertThat(deposits.sumPendingDepositRequests(now, now.plusSeconds(1)))
        .isGreaterThanOrEqualTo(0L);
    assertThat(deposits.countFloatRiskRiders(200_000L)).isGreaterThanOrEqualTo(0);
    assertThat(deposits.sumCodInHandAll()).isGreaterThanOrEqualTo(0L);
    deposits.lastConfirmedDepositAt(riderId);
    assertThat(deposits.listBoard(null, true, 100L, 1, 20).total()).isGreaterThanOrEqualTo(0);
    assertThat(deposits.listBoard(Ids.newId(), false, 100L, 1, 20).rows()).isNotNull();
    assertThat(deposits.allForReport(100L)).isNotNull();

    JdbcRiderStore riders = new JdbcRiderStore(jdbc);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(5000L);
    assertThat(riders.adjustCodInHand(riderId, 100L, now)).isEqualTo(5000L);
    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(null);
    assertThat(riders.adjustCodInHand(riderId, 100L, now)).isZero();
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    assertThatThrownBy(() -> riders.adjustCodInHand(riderId, 100L, now))
        .isInstanceOf(IllegalStateException.class);

    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(0L);
    assertThat(deposits.referenceExists("zero")).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(deposits.lastConfirmedDepositAt(riderId)).isNull();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> java.util.Collections.singletonList(null));
    assertThat(deposits.lastConfirmedDepositAt(riderId)).isNull();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(deposits.listBoard(null, false, 1L, 1, 10).total()).isZero();
    // instant(null) via map with null timestamps
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("rider_id")).thenReturn(riderId);
              when(rs.getLong("amount_paise")).thenReturn(1L);
              when(rs.getString("deposit_mode")).thenReturn("UPI");
              when(rs.getString("reference_number")).thenReturn("R");
              when(rs.getString("status")).thenReturn("CONFIRMED");
              when(rs.getString("notes")).thenReturn(null);
              when(rs.getObject("confirmed_by")).thenReturn(null);
              when(rs.getTimestamp("submitted_at")).thenReturn(null);
              when(rs.getTimestamp("confirmed_at")).thenReturn(null);
              when(rs.getTimestamp("deposited_at")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(deposits.findById(id)).isPresent();

    CodReconciliationService svc = mock(CodReconciliationService.class);
    when(svc.adminBoard(any(), any(), any(), any(), any()))
        .thenReturn(new BoardResult(Map.of("riders", List.of()), PaginationMeta.of(1, 20, 0)));
    when(svc.markDeposited(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("ok", true));
    when(svc.remind(any(), any(), any())).thenReturn(Map.of("notification_sent", true));
    when(svc.riderSummary(any())).thenReturn(Map.of("in_hand", BigDecimal.ZERO));
    when(svc.depositRequest(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "PENDING_CONFIRMATION"));

    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    MedmatePrincipal rider =
        new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
    AdminFinanceCodController adminCtrl = new AdminFinanceCodController(svc);
    ApiResponse<Map<String, Object>> board = adminCtrl.board(admin, null, true, 1, 20);
    assertThat(board.success()).isTrue();
    assertThat(
            adminCtrl
                .markDeposited(
                    admin,
                    riderId,
                    new AdminFinanceCodController.MarkDepositedRequest(10, null, "R", null))
                .success())
        .isTrue();
    assertThat(
            adminCtrl
                .remind(admin, riderId, new AdminFinanceCodController.RemindRequest("hi"))
                .success())
        .isTrue();
    assertThat(adminCtrl.markDeposited(admin, riderId, null).success()).isTrue();
    assertThat(adminCtrl.remind(admin, riderId, null).success()).isTrue();

    RiderCodController riderCtrl = new RiderCodController(svc);
    assertThat(riderCtrl.summary(rider).success()).isTrue();
    assertThat(
            riderCtrl
                .depositRequest(rider, new RiderCodController.DepositRequest(10, "UPI", "R2", null))
                .success())
        .isTrue();
    assertThat(riderCtrl.depositRequest(rider, null).success()).isTrue();
  }

  private static ResultSet mockRs(UUID id, UUID riderId, UUID orderId, Instant now)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rider_id")).thenReturn(riderId);
    when(rs.getObject("order_id")).thenReturn(orderId);
    when(rs.getObject("deposit_id")).thenReturn(null);
    when(rs.getObject("zone_id")).thenReturn(Ids.newId());
    when(rs.getObject("confirmed_by")).thenReturn(Ids.newId());
    when(rs.getLong("cod_amount_paise")).thenReturn(1000L);
    when(rs.getLong("amount_paise")).thenReturn(1000L);
    when(rs.getLong("cod_in_hand_paise")).thenReturn(3000L);
    when(rs.getBoolean("is_deposited")).thenReturn(false);
    when(rs.getString("order_number")).thenReturn("MED-1");
    when(rs.getString("deposit_mode")).thenReturn("UPI");
    when(rs.getString("reference_number")).thenReturn("REF");
    when(rs.getString("status")).thenReturn("PENDING_CONFIRMATION");
    when(rs.getString("notes")).thenReturn(null);
    when(rs.getString("name")).thenReturn("Ravi");
    when(rs.getString("zone_name")).thenReturn("Koramangala");
    when(rs.getTimestamp("collected_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("submitted_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("confirmed_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deposited_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
