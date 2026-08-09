package com.nammamedmate.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcCommissionHistoryStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcSettlementStore;
import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore.CommissionHistoryRow;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.SettlementRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyCommissionJdbcCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
  private static final UUID PID = Ids.newId();

  @Test
  void jdbcCommissionHistoryStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCommissionHistoryStore store = new JdbcCommissionHistoryStore(jdbc);

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID)))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(commissionRs(), 0));
            });

    assertThat(store.findPendingChange(PID)).isEmpty();
    assertThat(store.findPendingChange(PID)).isPresent();
    store.insert(
        new CommissionHistoryRow(
            Ids.newId(),
            PID,
            new BigDecimal("8.00"),
            new BigDecimal("7.00"),
            LocalDate.parse("2026-07-28"),
            "reason",
            null,
            Ids.newId(),
            NOW,
            null));
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(LocalDate.parse("2026-07-28"))))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(commissionRs(), 0));
            });
    assertThat(store.findDueForApply(LocalDate.parse("2026-07-28"))).hasSize(1);
    store.markApplied(Ids.newId(), NOW);
    store.insert(
        new CommissionHistoryRow(
            Ids.newId(),
            PID,
            new BigDecimal("8.00"),
            new BigDecimal("7.00"),
            LocalDate.parse("2026-07-28"),
            "reason",
            null,
            Ids.newId(),
            NOW,
            NOW));
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(commissionRsWithApplied(), 0));
            });
    assertThat(store.findPendingChange(PID)).isPresent();
  }

  @Test
  void jdbcSettlementStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcSettlementStore store = new JdbcSettlementStore(jdbc);
    UUID sid = Ids.newId();

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(sid)))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(settlementRs(), 0));
            });

    assertThat(store.findById(sid)).isEmpty();
    assertThat(store.findById(sid)).isPresent();

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(sid), eq(PID)))
        .thenReturn(List.of(settlementRow()));
    assertThat(store.findByIdForPharmacy(PID, sid)).isPresent();

    when(jdbc.query(any(String.class), any(RowMapper.class), eq("idem")))
        .thenReturn(List.of(settlementRow()));
    assertThat(store.findByIdempotencyKey("idem")).isPresent();

    when(jdbc.query(any(String.class), any(RowMapper.class), eq("pout_1")))
        .thenReturn(List.of(settlementRow()));
    assertThat(store.findByRazorpayxPayoutId("pout_1")).isPresent();

    when(jdbc.query(
            any(String.class),
            any(RowMapper.class),
            eq(PID),
            eq(LocalDate.parse("2026-07-14")),
            eq(LocalDate.parse("2026-07-20"))))
        .thenReturn(List.of(settlementRow()));
    assertThat(
            store.findForPeriod(PID, LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-20")))
        .isPresent();

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID)))
        .thenReturn(List.of(settlementRow()));
    assertThat(store.findLatestPaid(PID)).isPresent();

    store.insert(sampleSettlement(sid));
    store.updateReleased(sid, "RELEASED", Ids.newId(), NOW, "pout_1", "idem", NOW);
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0, 1, 1, 0, 1, 0);
    assertThat(store.claimForRelease(sid, PID, "idem-claim", NOW)).isFalse();
    assertThat(store.claimForRelease(sid, PID, "idem-claim", NOW)).isTrue();
    assertThat(store.finalizeRelease(sid, Ids.newId(), NOW, "pout_2", "idem-claim", NOW)).isTrue();
    assertThat(store.finalizeRelease(sid, Ids.newId(), NOW, "pout_3", "idem-miss", NOW)).isFalse();
    assertThat(store.markReleaseFailed(sid, "idem-claim", NOW)).isTrue();
    assertThat(store.markReleaseFailed(sid, "idem-claim", NOW)).isFalse();
    store.updateHeld(sid, "hold", NOW);
    store.updatePaid(sid, "UTR", "https://cdn/r.pdf", NOW, NOW);

    when(jdbc.queryForObject(any(String.class), eq(Long.class), any(Object[].class)))
        .thenReturn(1L)
        .thenReturn(null);
    when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(settlementRow()))
        .thenReturn(List.of(settlementRowWithTimestamps()));
    store.list(
        PID,
        new com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListFilter(
            "PAID", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), 20, 0));
    store.list(
        PID,
        new com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListFilter(
            "ALL", null, null, 20, 0));
    store.list(
        PID,
        new com.nammamedmate.pharmacy.application.port.out.SettlementStore.ListFilter(
            null, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), 20, 0));

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(sid)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(settlementRsWithTimestamps(), 0));
            });
    assertThat(store.findById(sid)).isPresent();

    store.insert(
        new SettlementRow(
            sid,
            PID,
            LocalDate.parse("2026-07-14"),
            LocalDate.parse("2026-07-20"),
            1L,
            new BigDecimal("8.00"),
            1L,
            new BigDecimal("1.00"),
            0L,
            1L,
            "RELEASED",
            null,
            Ids.newId(),
            NOW,
            NOW,
            "pout_1",
            "UTR",
            "url",
            "idem",
            NOW,
            NOW));

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID))).thenReturn(List.of());
    assertThat(store.findLatestPaid(PID)).isEmpty();

    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(PID), any(), any()))
        .thenReturn(1)
        .thenReturn(0)
        .thenReturn(null);
    assertThat(
            store.existsForPeriod(
                PID, LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-20")))
        .isTrue();
    assertThat(
            store.existsForPeriod(
                PID, LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-20")))
        .isFalse();
    assertThat(
            store.existsForPeriod(
                PID, LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-20")))
        .isFalse();

    when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(PID)))
        .thenReturn(1234L)
        .thenReturn(null);
    assertThat(store.sumUnconsumedCarryForwardPaise(PID)).isEqualTo(1234L);
    assertThat(store.sumUnconsumedCarryForwardPaise(PID)).isZero();
    store.markCarryForwardConsumed(PID, NOW);
    verify(jdbc, atLeastOnce()).update(any(String.class), any(), any(), eq(PID));
  }

  @Test
  void jdbcAdminStore_updateCommissionPct() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAdminPharmacyStore store =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAdminPharmacyStore(
            jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    store.updateCommissionPct(PID, new BigDecimal("7.00"), NOW);
  }

  private SettlementRow sampleSettlement(UUID sid) {
    return new SettlementRow(
        sid,
        PID,
        LocalDate.parse("2026-07-14"),
        LocalDate.parse("2026-07-20"),
        1L,
        new BigDecimal("8.00"),
        1L,
        new BigDecimal("1.00"),
        0L,
        1L,
        "PENDING_RELEASE",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  private SettlementRow settlementRow() {
    return sampleSettlement(Ids.newId());
  }

  private static ResultSet commissionRs() throws Exception {
    return commissionRsAppliedAt(null);
  }

  private static ResultSet commissionRsWithApplied() throws Exception {
    return commissionRsAppliedAt(Timestamp.from(NOW));
  }

  private static ResultSet commissionRsAppliedAt(Timestamp appliedAt) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getBigDecimal("previous_commission_pct")).thenReturn(new BigDecimal("8.00"));
    when(rs.getBigDecimal("new_commission_pct")).thenReturn(new BigDecimal("7.00"));
    when(rs.getObject("effective_from", LocalDate.class)).thenReturn(LocalDate.parse("2026-07-28"));
    when(rs.getString("reason")).thenReturn("reason");
    when(rs.getString("notes")).thenReturn(null);
    when(rs.getObject("changed_by")).thenReturn(Ids.newId());
    when(rs.getTimestamp("changed_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("applied_at")).thenReturn(appliedAt);
    return rs;
  }

  private static ResultSet settlementRs() throws Exception {
    return settlementRsTimestamps(null, null);
  }

  private static ResultSet settlementRsWithTimestamps() throws Exception {
    return settlementRsTimestamps(Timestamp.from(NOW), Timestamp.from(NOW));
  }

  private static ResultSet settlementRsTimestamps(Timestamp releasedAt, Timestamp paidAt)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(Ids.newId());
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getObject("period_start", LocalDate.class)).thenReturn(LocalDate.parse("2026-07-14"));
    when(rs.getObject("period_end", LocalDate.class)).thenReturn(LocalDate.parse("2026-07-20"));
    when(rs.getLong("gmv_paise")).thenReturn(1L);
    when(rs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("8.00"));
    when(rs.getLong("commission_earned_paise")).thenReturn(1L);
    when(rs.getBigDecimal("tcs_rate_pct")).thenReturn(new BigDecimal("1.00"));
    when(rs.getLong("tcs_deducted_paise")).thenReturn(0L);
    when(rs.getLong("net_paid_paise")).thenReturn(1L);
    when(rs.getString("status")).thenReturn("PENDING_RELEASE");
    when(rs.getString("hold_reason")).thenReturn(null);
    when(rs.getObject("released_by")).thenReturn(null);
    when(rs.getTimestamp("released_at")).thenReturn(releasedAt);
    when(rs.getTimestamp("paid_at")).thenReturn(paidAt);
    when(rs.getString("razorpayx_payout_id")).thenReturn(null);
    when(rs.getString("utr_number")).thenReturn(null);
    when(rs.getString("receipt_url")).thenReturn(null);
    when(rs.getString("release_idempotency_key")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private SettlementRow settlementRowWithTimestamps() {
    return new SettlementRow(
        Ids.newId(),
        PID,
        LocalDate.parse("2026-07-14"),
        LocalDate.parse("2026-07-20"),
        1L,
        new BigDecimal("8.00"),
        1L,
        new BigDecimal("1.00"),
        0L,
        1L,
        "PAID",
        null,
        Ids.newId(),
        NOW,
        NOW,
        "pout_1",
        "UTR",
        "url",
        "idem",
        NOW,
        NOW);
  }
}
