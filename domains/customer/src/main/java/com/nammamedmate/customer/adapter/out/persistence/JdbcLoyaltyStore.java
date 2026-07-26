package com.nammamedmate.customer.adapter.out.persistence;

import com.nammamedmate.customer.application.port.out.LoyaltyStore;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcLoyaltyStore implements LoyaltyStore {

  private static final RowMapper<LoyaltyRecord> LOYALTY_ROW = JdbcLoyaltyStore::mapLoyalty;
  private static final RowMapper<LoyaltyTxRecord> TX_ROW = JdbcLoyaltyStore::mapTx;

  private final JdbcTemplate jdbc;

  public JdbcLoyaltyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<LoyaltyRecord> findByCustomerId(UUID customerId) {
    List<LoyaltyRecord> rows =
        jdbc.query("SELECT * FROM customer_loyalty WHERE customer_id = ?", LOYALTY_ROW, customerId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<LoyaltyRecord> lockByCustomerId(UUID customerId) {
    List<LoyaltyRecord> rows =
        jdbc.query(
            "SELECT * FROM customer_loyalty WHERE customer_id = ? FOR UPDATE",
            LOYALTY_ROW,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public LoyaltyRecord insert(LoyaltyRecord record) {
    jdbc.update(
        """
        INSERT INTO customer_loyalty (
          id, customer_id, tier, points_balance, points_earned_lifetime, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.customerId(),
        record.tier(),
        record.pointsBalance(),
        record.pointsEarnedLifetime(),
        Timestamp.from(record.updatedAt()));
    return record;
  }

  @Override
  public LoyaltyRecord update(LoyaltyRecord record) {
    jdbc.update(
        """
        UPDATE customer_loyalty SET
          tier = ?,
          points_balance = ?,
          points_earned_lifetime = ?,
          updated_at = ?
        WHERE id = ?
        """,
        record.tier(),
        record.pointsBalance(),
        record.pointsEarnedLifetime(),
        Timestamp.from(record.updatedAt()),
        record.id());
    return record;
  }

  @Override
  public void syncCustomerLoyaltyPoints(UUID customerId, int pointsBalance) {
    jdbc.update(
        "UPDATE customers SET loyalty_points = ?, updated_at = NOW() WHERE id = ?",
        pointsBalance,
        customerId);
  }

  @Override
  public LoyaltyTxRecord insertTransaction(LoyaltyTxRecord tx) {
    jdbc.update(
        """
        INSERT INTO loyalty_transactions (
          id, customer_id, type, points, points_balance_after, description, reference_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tx.id(),
        tx.customerId(),
        tx.type().name(),
        tx.points(),
        tx.pointsBalanceAfter(),
        tx.description(),
        tx.referenceId(),
        Timestamp.from(tx.createdAt()));
    return tx;
  }

  @Override
  public Optional<LoyaltyTxRecord> findByReferenceAndType(UUID referenceId, LoyaltyTxType type) {
    List<LoyaltyTxRecord> rows =
        jdbc.query(
            """
            SELECT * FROM loyalty_transactions
            WHERE reference_id = ? AND type = ?
            """,
            TX_ROW,
            referenceId,
            type.name());
    return rows.stream().findFirst();
  }

  @Override
  public List<LoyaltyTxRecord> listTransactions(
      UUID customerId, LoyaltyTxType type, String order, int limit, int offset) {
    boolean asc = "asc".equalsIgnoreCase(order);
    String direction = asc ? "ASC" : "DESC";
    if (type == null) {
      return jdbc.query(
          "SELECT * FROM loyalty_transactions WHERE customer_id = ? ORDER BY created_at "
              + direction
              + " LIMIT ? OFFSET ?",
          TX_ROW,
          customerId,
          limit,
          offset);
    }
    return jdbc.query(
        "SELECT * FROM loyalty_transactions WHERE customer_id = ? AND type = ? ORDER BY created_at "
            + direction
            + " LIMIT ? OFFSET ?",
        TX_ROW,
        customerId,
        type.name(),
        limit,
        offset);
  }

  @Override
  public long countTransactions(UUID customerId, LoyaltyTxType type) {
    if (type == null) {
      Long count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM loyalty_transactions WHERE customer_id = ?",
              Long.class,
              customerId);
      return count == null ? 0L : count;
    }
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM loyalty_transactions WHERE customer_id = ? AND type = ?",
            Long.class,
            customerId,
            type.name());
    return count == null ? 0L : count;
  }

  private static LoyaltyRecord mapLoyalty(ResultSet rs, int rowNum) throws SQLException {
    return new LoyaltyRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getString("tier"),
        rs.getInt("points_balance"),
        rs.getInt("points_earned_lifetime"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static LoyaltyTxRecord mapTx(ResultSet rs, int rowNum) throws SQLException {
    return new LoyaltyTxRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        LoyaltyTxType.valueOf(rs.getString("type")),
        rs.getInt("points"),
        rs.getInt("points_balance_after"),
        rs.getString("description"),
        (UUID) rs.getObject("reference_id"),
        rs.getTimestamp("created_at").toInstant());
  }
}
