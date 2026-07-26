package com.nammamedmate.customer.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.WalletStore.WalletRecord;
import com.nammamedmate.customer.application.port.out.WalletStore.WalletTxRecord;
import com.nammamedmate.customer.domain.WalletTxType;
import com.nammamedmate.kernel.id.Ids;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcWalletStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-26T02:00:00Z");

  @Test
  void findByCustomerId_maps() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    WalletRecord record = sampleWallet();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<WalletRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockWalletRs(record), 0));
            });
    assertThat(store.findByCustomerId(record.customerId())).contains(record);
  }

  @Test
  void findById_and_locks() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    WalletRecord record = sampleWallet();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.id())))
        .thenAnswer(
            inv -> {
              RowMapper<WalletRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockWalletRs(record), 0));
            });
    assertThat(store.findById(record.id())).contains(record);
    assertThat(store.lockById(record.id())).contains(record);
  }

  @Test
  void lockByCustomerId_empty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class))).thenReturn(List.of());
    assertThat(store.lockByCustomerId(Ids.newId())).isEmpty();
  }

  @Test
  void insertAndUpdateWallet() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    WalletRecord record = sampleWallet();
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    assertThat(store.insertWallet(record)).isEqualTo(record);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    WalletRecord updated =
        new WalletRecord(record.id(), record.customerId(), 100, 100, 0, 1, record.createdAt(), NOW);
    assertThat(store.updateWallet(updated, 0)).isEqualTo(updated);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
    assertThatThrownBy(() -> store.updateWallet(updated, 0))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void syncAndInsertTransaction() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    UUID customerId = Ids.newId();
    store.syncCustomerBalancePaise(customerId, 500);
    verify(jdbc).update(anyString(), eq(500L), eq(customerId));

    WalletTxRecord tx = sampleTx(Ids.newId(), true);
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
    assertThat(store.insertTransaction(tx)).isEqualTo(tx);
    WalletTxRecord debit = sampleTx(Ids.newId(), false);
    assertThat(store.insertTransaction(debit)).isEqualTo(debit);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    assertThat(store.updateCreditRemaining(tx.id(), 100L, 10L)).isTrue();
    verify(jdbc).update(anyString(), eq(10L), eq(tx.id()), eq(100L));

    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
    assertThat(store.updateCreditRemaining(tx.id(), 100L, 10L)).isFalse();
  }

  @Test
  void findByIdempotencyKey_maps() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    WalletTxRecord tx = sampleTx(Ids.newId(), true);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("idem-1")))
        .thenAnswer(
            inv -> {
              RowMapper<WalletTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.findByIdempotencyKey("idem-1")).contains(tx);
  }

  @Test
  void listAndCountTransactions() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    UUID walletId = Ids.newId();
    WalletTxRecord tx = sampleTx(walletId, false);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(walletId), eq(20), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<WalletTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.listTransactions(walletId, null, "created_at", "desc", 20, 0))
        .containsExactly(tx);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(walletId), eq("CREDIT"), eq(20), eq(0)))
        .thenReturn(List.of(tx));
    assertThat(store.listTransactions(walletId, WalletTxType.CREDIT, "created_at", "asc", 20, 0))
        .hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(walletId))).thenReturn(null);
    assertThat(store.countTransactions(walletId, null)).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(walletId), eq("DEBIT")))
        .thenReturn(2L);
    assertThat(store.countTransactions(walletId, WalletTxType.DEBIT)).isEqualTo(2);
  }

  @Test
  void expiryQueries() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    UUID walletId = Ids.newId();
    WalletTxRecord tx = sampleTx(walletId, true);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class), eq(10)))
        .thenAnswer(
            inv -> {
              RowMapper<WalletTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.findExpiredOpenCredits(NOW, 10)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(walletId))).thenReturn(List.of(tx));
    assertThat(store.findOpenCreditsFifo(walletId)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(walletId), any(Timestamp.class)))
        .thenReturn(null);
    assertThat(store.sumRemainingExpiringBefore(walletId, NOW)).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(walletId), any(Timestamp.class)))
        .thenReturn(50L);
    assertThat(store.sumRemainingExpiringBefore(walletId, NOW)).isEqualTo(50);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(walletId), any(Timestamp.class)))
        .thenReturn(List.of());
    assertThat(store.earliestExpiryBefore(walletId, NOW)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(walletId), any(Timestamp.class)))
        .thenReturn(java.util.Collections.singletonList(null));
    assertThat(store.earliestExpiryBefore(walletId, NOW)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(walletId), any(Timestamp.class)))
        .thenAnswer(
            inv -> {
              RowMapper<Timestamp> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.earliestExpiryBefore(walletId, NOW)).contains(NOW);
  }

  @Test
  void mapTx_nullRemaining() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcWalletStore store = new JdbcWalletStore(jdbc);
    UUID walletId = Ids.newId();
    WalletTxRecord tx =
        new WalletTxRecord(
            Ids.newId(),
            walletId,
            WalletTxType.DEBIT,
            50,
            50,
            "ORDER_PAYMENT",
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(walletId), eq(20), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<WalletTxRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockTxRs(tx), 0));
            });
    assertThat(store.listTransactions(walletId, null, "created_at", "desc", 20, 0))
        .containsExactly(tx);
  }

  private static WalletRecord sampleWallet() {
    return new WalletRecord(Ids.newId(), Ids.newId(), 0, 0, 0, 0, NOW, NOW);
  }

  private static WalletTxRecord sampleTx(UUID walletId, boolean withExpiry) {
    return new WalletTxRecord(
        Ids.newId(),
        walletId,
        WalletTxType.CREDIT,
        100,
        100,
        "GOODWILL",
        "note",
        "ref",
        "idem-key",
        Ids.newId(),
        withExpiry ? NOW.plusSeconds(86400) : null,
        100L,
        NOW);
  }

  private static ResultSet mockWalletRs(WalletRecord r) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(r.id());
    when(rs.getObject("customer_id")).thenReturn(r.customerId());
    when(rs.getLong("balance_paise")).thenReturn(r.balancePaise());
    when(rs.getLong("lifetime_credited_paise")).thenReturn(r.lifetimeCreditedPaise());
    when(rs.getLong("lifetime_debited_paise")).thenReturn(r.lifetimeDebitedPaise());
    when(rs.getLong("version")).thenReturn(r.version());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(r.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(r.updatedAt()));
    return rs;
  }

  private static ResultSet mockTxRs(WalletTxRecord t) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(t.id());
    when(rs.getObject("wallet_id")).thenReturn(t.walletId());
    when(rs.getString("type")).thenReturn(t.type().name());
    when(rs.getLong("amount_paise")).thenReturn(t.amountPaise());
    when(rs.getLong("balance_after_paise")).thenReturn(t.balanceAfterPaise());
    when(rs.getString("reason")).thenReturn(t.reason());
    when(rs.getString("description")).thenReturn(t.description());
    when(rs.getString("reference_id")).thenReturn(t.referenceId());
    when(rs.getString("idempotency_key")).thenReturn(t.idempotencyKey());
    when(rs.getObject("credited_by")).thenReturn(t.creditedBy());
    when(rs.getTimestamp("expires_at"))
        .thenReturn(t.expiresAt() == null ? null : Timestamp.from(t.expiresAt()));
    when(rs.getLong("remaining_paise"))
        .thenReturn(t.remainingPaise() == null ? 0 : t.remainingPaise());
    when(rs.wasNull()).thenReturn(t.remainingPaise() == null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t.createdAt()));
    return rs;
  }
}
