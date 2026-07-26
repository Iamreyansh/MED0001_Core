package com.nammamedmate.customer.adapter.out.persistence;

import com.nammamedmate.customer.application.port.out.WalletStore;
import com.nammamedmate.customer.domain.WalletTxType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcWalletStore implements WalletStore {

  private static final RowMapper<WalletRecord> WALLET_ROW = JdbcWalletStore::mapWallet;
  private static final RowMapper<WalletTxRecord> TX_ROW = JdbcWalletStore::mapTx;

  private final JdbcTemplate jdbc;

  public JdbcWalletStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<WalletRecord> findByCustomerId(UUID customerId) {
    List<WalletRecord> rows =
        jdbc.query("SELECT * FROM wallets WHERE customer_id = ?", WALLET_ROW, customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<WalletRecord> findById(UUID walletId) {
    List<WalletRecord> rows =
        jdbc.query("SELECT * FROM wallets WHERE id = ?", WALLET_ROW, walletId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<WalletRecord> lockByCustomerId(UUID customerId) {
    List<WalletRecord> rows =
        jdbc.query(
            "SELECT * FROM wallets WHERE customer_id = ? FOR UPDATE", WALLET_ROW, customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<WalletRecord> lockById(UUID walletId) {
    List<WalletRecord> rows =
        jdbc.query("SELECT * FROM wallets WHERE id = ? FOR UPDATE", WALLET_ROW, walletId);
    return rows.stream().findFirst();
  }

  @Override
  public WalletRecord insertWallet(WalletRecord wallet) {
    jdbc.update(
        """
        INSERT INTO wallets (
          id, customer_id, balance_paise, lifetime_credited_paise, lifetime_debited_paise,
          version, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        wallet.id(),
        wallet.customerId(),
        wallet.balancePaise(),
        wallet.lifetimeCreditedPaise(),
        wallet.lifetimeDebitedPaise(),
        wallet.version(),
        Timestamp.from(wallet.createdAt()),
        Timestamp.from(wallet.updatedAt()));
    return wallet;
  }

  @Override
  public WalletRecord updateWallet(WalletRecord wallet, long expectedVersion) {
    int updated =
        jdbc.update(
            """
            UPDATE wallets SET
              balance_paise = ?,
              lifetime_credited_paise = ?,
              lifetime_debited_paise = ?,
              version = ?,
              updated_at = ?
            WHERE id = ? AND version = ?
            """,
            wallet.balancePaise(),
            wallet.lifetimeCreditedPaise(),
            wallet.lifetimeDebitedPaise(),
            wallet.version(),
            Timestamp.from(wallet.updatedAt()),
            wallet.id(),
            expectedVersion);
    if (updated != 1) {
      throw new IllegalStateException("Wallet optimistic lock failed for " + wallet.id());
    }
    return wallet;
  }

  @Override
  public void syncCustomerBalancePaise(UUID customerId, long balancePaise) {
    jdbc.update(
        "UPDATE customers SET wallet_balance_paise = ?, updated_at = NOW() WHERE id = ?",
        balancePaise,
        customerId);
  }

  @Override
  public WalletTxRecord insertTransaction(WalletTxRecord tx) {
    jdbc.update(
        """
        INSERT INTO wallet_transactions (
          id, wallet_id, type, amount_paise, balance_after_paise, reason, description,
          reference_id, idempotency_key, credited_by, expires_at, remaining_paise, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tx.id(),
        tx.walletId(),
        tx.type().name(),
        tx.amountPaise(),
        tx.balanceAfterPaise(),
        tx.reason(),
        tx.description(),
        tx.referenceId(),
        tx.idempotencyKey(),
        tx.creditedBy(),
        tx.expiresAt() == null ? null : Timestamp.from(tx.expiresAt()),
        tx.remainingPaise(),
        Timestamp.from(tx.createdAt()));
    return tx;
  }

  @Override
  public boolean updateCreditRemaining(
      UUID creditTxId, long expectedRemaining, long remainingPaise) {
    int updated =
        jdbc.update(
            """
            UPDATE wallet_transactions
            SET remaining_paise = ?
            WHERE id = ? AND type = 'CREDIT' AND remaining_paise = ?
            """,
            remainingPaise,
            creditTxId,
            expectedRemaining);
    return updated == 1;
  }

  @Override
  public Optional<WalletTxRecord> findByIdempotencyKey(String idempotencyKey) {
    List<WalletTxRecord> rows =
        jdbc.query(
            "SELECT * FROM wallet_transactions WHERE idempotency_key = ?", TX_ROW, idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public List<WalletTxRecord> listTransactions(
      UUID walletId, WalletTxType type, String sort, String order, int limit, int offset) {
    String dir = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
    if (type == null) {
      return jdbc.query(
          "SELECT * FROM wallet_transactions WHERE wallet_id = ? ORDER BY created_at "
              + dir
              + " LIMIT ? OFFSET ?",
          TX_ROW,
          walletId,
          limit,
          offset);
    }
    return jdbc.query(
        "SELECT * FROM wallet_transactions WHERE wallet_id = ? AND type = ? ORDER BY created_at "
            + dir
            + " LIMIT ? OFFSET ?",
        TX_ROW,
        walletId,
        type.name(),
        limit,
        offset);
  }

  @Override
  public long countTransactions(UUID walletId, WalletTxType type) {
    Long count;
    if (type == null) {
      count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM wallet_transactions WHERE wallet_id = ?", Long.class, walletId);
    } else {
      count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM wallet_transactions WHERE wallet_id = ? AND type = ?",
              Long.class,
              walletId,
              type.name());
    }
    return count == null ? 0L : count;
  }

  @Override
  public List<WalletTxRecord> findExpiredOpenCredits(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM wallet_transactions
        WHERE type = 'CREDIT'
          AND remaining_paise > 0
          AND expires_at IS NOT NULL
          AND expires_at <= ?
        ORDER BY expires_at ASC, created_at ASC
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """,
        TX_ROW,
        Timestamp.from(now),
        limit);
  }

  @Override
  public List<WalletTxRecord> findOpenCreditsFifo(UUID walletId) {
    return jdbc.query(
        """
        SELECT * FROM wallet_transactions
        WHERE wallet_id = ?
          AND type = 'CREDIT'
          AND remaining_paise > 0
        ORDER BY expires_at ASC NULLS LAST, created_at ASC
        """,
        TX_ROW,
        walletId);
  }

  @Override
  public long sumRemainingExpiringBefore(UUID walletId, Instant before) {
    Long sum =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(remaining_paise), 0) FROM wallet_transactions
            WHERE wallet_id = ?
              AND type = 'CREDIT'
              AND remaining_paise > 0
              AND expires_at IS NOT NULL
              AND expires_at < ?
            """,
            Long.class,
            walletId,
            Timestamp.from(before));
    return sum == null ? 0L : sum;
  }

  @Override
  public Optional<Instant> earliestExpiryBefore(UUID walletId, Instant before) {
    List<Timestamp> rows =
        jdbc.query(
            """
            SELECT MIN(expires_at) AS expires_at FROM wallet_transactions
            WHERE wallet_id = ?
              AND type = 'CREDIT'
              AND remaining_paise > 0
              AND expires_at IS NOT NULL
              AND expires_at < ?
            """,
            (rs, i) -> rs.getTimestamp("expires_at"),
            walletId,
            Timestamp.from(before));
    if (rows.isEmpty() || rows.getFirst() == null) {
      return Optional.empty();
    }
    return Optional.of(rows.getFirst().toInstant());
  }

  private static WalletRecord mapWallet(ResultSet rs, int rowNum) throws SQLException {
    return new WalletRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getLong("balance_paise"),
        rs.getLong("lifetime_credited_paise"),
        rs.getLong("lifetime_debited_paise"),
        rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static WalletTxRecord mapTx(ResultSet rs, int rowNum) throws SQLException {
    Timestamp expires = rs.getTimestamp("expires_at");
    long remaining = rs.getLong("remaining_paise");
    Long remainingBox = rs.wasNull() ? null : remaining;
    UUID creditedBy = (UUID) rs.getObject("credited_by");
    return new WalletTxRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("wallet_id"),
        WalletTxType.valueOf(rs.getString("type")),
        rs.getLong("amount_paise"),
        rs.getLong("balance_after_paise"),
        rs.getString("reason"),
        rs.getString("description"),
        rs.getString("reference_id"),
        rs.getString("idempotency_key"),
        creditedBy,
        expires == null ? null : expires.toInstant(),
        remainingBox,
        rs.getTimestamp("created_at").toInstant());
  }
}
