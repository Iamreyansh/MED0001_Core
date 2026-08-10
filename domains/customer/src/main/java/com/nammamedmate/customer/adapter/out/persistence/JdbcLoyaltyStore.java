package com.nammamedmate.customer.adapter.out.persistence;

import com.nammamedmate.customer.application.port.out.LoyaltyStore;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcLoyaltyStore implements LoyaltyStore {

  private static final RowMapper<LoyaltyRecord> LOYALTY_ROW = JdbcLoyaltyStore::mapLoyalty;
  private static final RowMapper<LoyaltyTxRecord> TX_ROW = JdbcLoyaltyStore::mapTx;
  private static final RowMapper<ProgramSettingsRecord> SETTINGS_ROW =
      JdbcLoyaltyStore::mapSettings;

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
          id, customer_id, type, points, points_balance_after, description, reference_id,
          created_at, expires_at, remaining_points, adjusted_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tx.id(),
        tx.customerId(),
        tx.type().name(),
        tx.points(),
        tx.pointsBalanceAfter(),
        tx.description(),
        tx.referenceId(),
        Timestamp.from(tx.createdAt()),
        tx.expiresAt() == null ? null : Timestamp.from(tx.expiresAt()),
        tx.remainingPoints(),
        tx.adjustedBy());
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

  @Override
  public ProgramSettingsRecord getProgramSettings() {
    List<ProgramSettingsRecord> rows =
        jdbc.query(
            "SELECT * FROM loyalty_program_settings WHERE id = ?",
            SETTINGS_ROW,
            PROGRAM_SETTINGS_ID);
    return rows.stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("loyalty_program_settings missing"));
  }

  @Override
  public ProgramSettingsRecord updateProgramSettings(ProgramSettingsRecord settings) {
    jdbc.update(
        """
        UPDATE loyalty_program_settings SET
          earn_rate_rs_per_point = ?,
          redemption_rate_rs_per_point = ?,
          tier_silver_pts = ?,
          tier_gold_pts = ?,
          tier_platinum_pts = ?,
          max_redemption_pct_per_order = ?,
          min_points_per_redemption = ?,
          points_expiry_days = ?,
          updated_by = ?,
          updated_at = ?
        WHERE id = ?
        """,
        settings.earnRateRsPerPoint(),
        settings.redemptionRateRsPerPoint(),
        settings.tierSilverPts(),
        settings.tierGoldPts(),
        settings.tierPlatinumPts(),
        settings.maxRedemptionPctPerOrder(),
        settings.minPointsPerRedemption(),
        settings.pointsExpiryDays(),
        settings.updatedBy(),
        Timestamp.from(settings.updatedAt()),
        settings.id());
    return settings;
  }

  @Override
  public List<LoyaltyTxRecord> findOpenEarnBatchesFifo(UUID customerId) {
    return jdbc.query(
        """
        SELECT * FROM loyalty_transactions
        WHERE customer_id = ? AND type = 'EARN'
          AND remaining_points IS NOT NULL AND remaining_points > 0
        ORDER BY created_at ASC, id ASC
        """,
        TX_ROW,
        customerId);
  }

  @Override
  public void updateEarnRemaining(UUID txId, int remainingPoints) {
    jdbc.update(
        "UPDATE loyalty_transactions SET remaining_points = ? WHERE id = ? AND type = 'EARN'",
        remainingPoints,
        txId);
  }

  @Override
  public List<LoyaltyTxRecord> findExpiredEarnBatches(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM loyalty_transactions
        WHERE type = 'EARN'
          AND remaining_points IS NOT NULL AND remaining_points > 0
          AND expires_at IS NOT NULL AND expires_at <= ?
        ORDER BY expires_at ASC, created_at ASC
        LIMIT ?
        """,
        TX_ROW,
        Timestamp.from(now),
        limit);
  }

  @Override
  public OverviewStats overviewStats(Instant since30d) {
    Long outstanding =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(points_balance), 0) FROM customer_loyalty", Long.class);
    Long customers = jdbc.queryForObject("SELECT COUNT(*) FROM customer_loyalty", Long.class);
    long out = outstanding == null ? 0L : outstanding;
    long cust = customers == null ? 0L : customers;
    BigDecimal avg =
        cust == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(out)
                .divide(BigDecimal.valueOf(cust), 0, java.math.RoundingMode.HALF_UP);

    Map<String, Long> tiers = new LinkedHashMap<>();
    tiers.put("NONE", 0L);
    tiers.put("SILVER", 0L);
    tiers.put("GOLD", 0L);
    tiers.put("PLATINUM", 0L);
    jdbc.query(
        "SELECT tier, COUNT(*) AS c FROM customer_loyalty GROUP BY tier",
        rs -> {
          tiers.put(rs.getString("tier"), rs.getLong("c"));
        });

    Timestamp since = Timestamp.from(since30d);
    long earned =
        sumPoints(
            "SELECT COALESCE(SUM(points), 0) FROM loyalty_transactions WHERE type = 'EARN' AND created_at >= ?",
            since);
    long redeemed =
        Math.abs(
            sumPoints(
                "SELECT COALESCE(SUM(points), 0) FROM loyalty_transactions WHERE type = 'REDEEM' AND created_at >= ?",
                since));
    long expired =
        Math.abs(
            sumPoints(
                "SELECT COALESCE(SUM(points), 0) FROM loyalty_transactions WHERE type = 'EXPIRE' AND created_at >= ?",
                since));
    return new OverviewStats(out, avg, Map.copyOf(tiers), earned, redeemed, expired);
  }

  private long sumPoints(String sql, Timestamp since) {
    Long v = jdbc.queryForObject(sql, Long.class, since);
    return v == null ? 0L : v;
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
    Timestamp expires = rs.getTimestamp("expires_at");
    int rem = rs.getInt("remaining_points");
    Integer remaining = rs.wasNull() ? null : rem;
    return new LoyaltyTxRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        LoyaltyTxType.valueOf(rs.getString("type")),
        rs.getInt("points"),
        rs.getInt("points_balance_after"),
        rs.getString("description"),
        (UUID) rs.getObject("reference_id"),
        rs.getTimestamp("created_at").toInstant(),
        expires == null ? null : expires.toInstant(),
        remaining,
        (UUID) rs.getObject("adjusted_by"));
  }

  private static ProgramSettingsRecord mapSettings(ResultSet rs, int rowNum) throws SQLException {
    Timestamp updated = rs.getTimestamp("updated_at");
    return new ProgramSettingsRecord(
        (UUID) rs.getObject("id"),
        rs.getInt("earn_rate_rs_per_point"),
        rs.getBigDecimal("redemption_rate_rs_per_point"),
        rs.getInt("tier_silver_pts"),
        rs.getInt("tier_gold_pts"),
        rs.getInt("tier_platinum_pts"),
        rs.getInt("max_redemption_pct_per_order"),
        rs.getInt("min_points_per_redemption"),
        rs.getInt("points_expiry_days"),
        (UUID) rs.getObject("updated_by"),
        updated == null ? Instant.EPOCH : updated.toInstant());
  }
}
