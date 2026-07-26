package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import com.nammamedmate.kernel.id.Ids;
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

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
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

  private static LoyaltyRecord sample() {
    return new LoyaltyRecord(Ids.newId(), Ids.newId(), "GOLD", 50, 50, NOW);
  }

  private static LoyaltyTxRecord sampleTx() {
    return new LoyaltyTxRecord(
        Ids.newId(), Ids.newId(), LoyaltyTxType.EARN, 3, 3, "pts", Ids.newId(), NOW);
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
    return rs;
  }
}
