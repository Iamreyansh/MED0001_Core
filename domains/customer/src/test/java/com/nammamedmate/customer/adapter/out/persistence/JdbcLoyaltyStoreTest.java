package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.OverviewStats;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.ProgramSettingsRecord;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcLoyaltyStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");

  @Test
  void findLockInsertUpdateAndSync() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcLoyaltyStore store = new JdbcLoyaltyStore(jdbc);
    LoyaltyRecord record = sample();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<LoyaltyRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockLoyaltyRs(record), 0));
            });
    assertThat(store.findByCustomerId(record.customerId())).contains(record);
    assertThat(store.lockByCustomerId(record.customerId())).contains(record);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.insert(record)).isEqualTo(record);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.update(record)).isEqualTo(record);

    store.syncCustomerLoyaltyPoints(record.customerId(), 12);
    verify(jdbc).update(anyString(), eq(12), eq(record.customerId()));
  }

  @Test
  void transactions_listCountAndFind() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcLoyaltyStore store = new JdbcLoyaltyStore(jdbc);
    LoyaltyTxRecord tx = sampleTx();

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
            any()))
        .thenReturn(1);
    assertThat(store.insertTransaction(tx)).isEqualTo(tx);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(tx.referenceId()), eq("EARN")))
        .thenAnswer(
            inv -> {
              RowMapper<LoyaltyTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.findByReferenceAndType(tx.referenceId(), LoyaltyTxType.EARN)).contains(tx);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(tx.customerId()), eq(10), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<LoyaltyTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.listTransactions(tx.customerId(), null, "desc", 10, 0)).hasSize(1);
    assertThat(store.listTransactions(tx.customerId(), null, "asc", 10, 0)).hasSize(1);

    when(jdbc.query(
            anyString(), any(RowMapper.class), eq(tx.customerId()), eq("EARN"), eq(10), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<LoyaltyTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.listTransactions(tx.customerId(), LoyaltyTxType.EARN, "asc", 10, 0))
        .hasSize(1);
    assertThat(store.listTransactions(tx.customerId(), LoyaltyTxType.EARN, "DESC", 10, 0))
        .hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(tx.customerId()))).thenReturn(2L);
    assertThat(store.countTransactions(tx.customerId(), null)).isEqualTo(2L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(tx.customerId()))).thenReturn(null);
    assertThat(store.countTransactions(tx.customerId(), null)).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(tx.customerId()), eq("REVERSE")))
        .thenReturn(null);
    assertThat(store.countTransactions(tx.customerId(), LoyaltyTxType.REVERSE)).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(tx.customerId()), eq("EARN")))
        .thenReturn(1L);
    assertThat(store.countTransactions(tx.customerId(), LoyaltyTxType.EARN)).isEqualTo(1L);
  }

  @Test
  void settingsFifoExpiryAndOverview() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcLoyaltyStore store = new JdbcLoyaltyStore(jdbc);
    ProgramSettingsRecord settings =
        new ProgramSettingsRecord(
            com.nammamedmate.customer.application.port.out.LoyaltyStore.PROGRAM_SETTINGS_ID,
            100,
            BigDecimal.ONE,
            12,
            50,
            120,
            20,
            10,
            365,
            null,
            NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<ProgramSettingsRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockSettingsRs(settings), 0));
            });
    assertThat(store.getProgramSettings()).isEqualTo(settings);

    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThatThrownBy(store::getProgramSettings).isInstanceOf(IllegalStateException.class);

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
            any()))
        .thenReturn(1);
    assertThat(store.updateProgramSettings(settings)).isEqualTo(settings);

    LoyaltyTxRecord tx = sampleTx();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(tx.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<LoyaltyTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.findOpenEarnBatchesFifo(tx.customerId())).hasSize(1);

    store.updateEarnRemaining(tx.id(), 1);
    verify(jdbc).update(anyString(), eq(1), eq(tx.id()));

    when(jdbc.query(anyString(), any(RowMapper.class), any(), eq(10)))
        .thenAnswer(
            inv -> {
              RowMapper<LoyaltyTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.findExpiredEarnBatches(NOW, 10)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null, null);
    doAnswer(
            inv -> {
              org.springframework.jdbc.core.RowCallbackHandler handler = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("tier")).thenReturn("NONE");
              when(rs.getLong("c")).thenReturn(0L);
              handler.processRow(rs);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    OverviewStats empty = store.overviewStats(NOW);
    assertThat(empty.totalPointsOutstanding()).isZero();
    assertThat(empty.avgPointsPerCustomer()).isEqualByComparingTo(BigDecimal.ZERO);

    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(100L, 2L);
    doAnswer(
            inv -> {
              org.springframework.jdbc.core.RowCallbackHandler handler = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("tier")).thenReturn("GOLD");
              when(rs.getLong("c")).thenReturn(2L);
              handler.processRow(rs);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(10L, -5L, -2L);
    OverviewStats stats = store.overviewStats(NOW);
    assertThat(stats.totalPointsOutstanding()).isEqualTo(100L);

    LoyaltyTxRecord bare =
        new LoyaltyTxRecord(Ids.newId(), Ids.newId(), LoyaltyTxType.REDEEM, -1, 0, "r", null, NOW);
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
            any()))
        .thenReturn(1);
    assertThat(store.insertTransaction(bare)).isEqualTo(bare);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(bare.customerId()), eq(5), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<LoyaltyTxRecord> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(bare.id());
              when(rs.getObject("customer_id")).thenReturn(bare.customerId());
              when(rs.getString("type")).thenReturn("REDEEM");
              when(rs.getInt("points")).thenReturn(-1);
              when(rs.getInt("points_balance_after")).thenReturn(0);
              when(rs.getString("description")).thenReturn("r");
              when(rs.getObject("reference_id")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("expires_at")).thenReturn(null);
              when(rs.getInt("remaining_points")).thenReturn(0);
              when(rs.wasNull()).thenReturn(true);
              when(rs.getObject("adjusted_by")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listTransactions(bare.customerId(), null, "desc", 5, 0)).hasSize(1);

    ProgramSettingsRecord noUpdated =
        new ProgramSettingsRecord(
            com.nammamedmate.customer.application.port.out.LoyaltyStore.PROGRAM_SETTINGS_ID,
            100,
            BigDecimal.ONE,
            12,
            50,
            120,
            20,
            10,
            365,
            null,
            Instant.EPOCH);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<ProgramSettingsRecord> mapper = inv.getArgument(1);
              ResultSet rs = mockSettingsRs(noUpdated);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.getProgramSettings().updatedAt()).isEqualTo(Instant.EPOCH);
  }

  private static LoyaltyRecord sample() {
    return new LoyaltyRecord(Ids.newId(), Ids.newId(), "GOLD", 50, 50, NOW);
  }

  private static LoyaltyTxRecord sampleTx() {
    return new LoyaltyTxRecord(
        Ids.newId(),
        Ids.newId(),
        LoyaltyTxType.EARN,
        3,
        3,
        "pts",
        Ids.newId(),
        NOW,
        NOW.plusSeconds(3600),
        3,
        null);
  }

  private static ResultSet mockLoyaltyRs(LoyaltyRecord r) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(r.id());
    when(rs.getObject("customer_id")).thenReturn(r.customerId());
    when(rs.getString("tier")).thenReturn(r.tier());
    when(rs.getInt("points_balance")).thenReturn(r.pointsBalance());
    when(rs.getInt("points_earned_lifetime")).thenReturn(r.pointsEarnedLifetime());
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(r.updatedAt()));
    return rs;
  }

  private static ResultSet mockTxRs(LoyaltyTxRecord t) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(t.id());
    when(rs.getObject("customer_id")).thenReturn(t.customerId());
    when(rs.getString("type")).thenReturn(t.type().name());
    when(rs.getInt("points")).thenReturn(t.points());
    when(rs.getInt("points_balance_after")).thenReturn(t.pointsBalanceAfter());
    when(rs.getString("description")).thenReturn(t.description());
    when(rs.getObject("reference_id")).thenReturn(t.referenceId());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t.createdAt()));
    when(rs.getTimestamp("expires_at"))
        .thenReturn(t.expiresAt() == null ? null : Timestamp.from(t.expiresAt()));
    when(rs.getInt("remaining_points"))
        .thenReturn(t.remainingPoints() == null ? 0 : t.remainingPoints());
    when(rs.wasNull()).thenReturn(t.remainingPoints() == null);
    when(rs.getObject("adjusted_by")).thenReturn(t.adjustedBy());
    return rs;
  }

  private static ResultSet mockSettingsRs(ProgramSettingsRecord s) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(s.id());
    when(rs.getInt("earn_rate_rs_per_point")).thenReturn(s.earnRateRsPerPoint());
    when(rs.getBigDecimal("redemption_rate_rs_per_point")).thenReturn(s.redemptionRateRsPerPoint());
    when(rs.getInt("tier_silver_pts")).thenReturn(s.tierSilverPts());
    when(rs.getInt("tier_gold_pts")).thenReturn(s.tierGoldPts());
    when(rs.getInt("tier_platinum_pts")).thenReturn(s.tierPlatinumPts());
    when(rs.getInt("max_redemption_pct_per_order")).thenReturn(s.maxRedemptionPctPerOrder());
    when(rs.getInt("min_points_per_redemption")).thenReturn(s.minPointsPerRedemption());
    when(rs.getInt("points_expiry_days")).thenReturn(s.pointsExpiryDays());
    when(rs.getObject("updated_by")).thenReturn(s.updatedBy());
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(s.updatedAt()));
    return rs;
  }
}
