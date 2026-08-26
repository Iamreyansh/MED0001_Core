package com.nammamedmate.api.config;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutNotificationPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort;
import com.nammamedmate.payment.domain.RiderPayoutStatuses;
import com.nammamedmate.rider.application.port.out.CashfreeRoutePort;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridge: payment finance façades ↔ V042 rider_payouts + riders/zones/earnings.
 * Also re-exports payment CashfreePayout as rider {@link CashfreePayoutPort} so legacy admin rider
 * payout endpoints share the live|stub client.
 */
@Configuration
public class PaymentRiderPayoutBridgeConfig {

  @Bean
  @Primary
  RiderPayoutPort jdbcRiderPayoutPort(JdbcTemplate jdbc) {
    return new JdbcRiderPayoutBridge(jdbc);
  }

  @Bean
  @Primary
  RiderPayoutNotificationPort riderPayoutNotificationBridge(OutboxPublisher outbox) {
    return new RiderPayoutNotificationPort() {
      @Override
      public void payoutReleased(
          UUID riderId, UUID payoutId, long netPaise, String cashfreeTransferId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rider_id", riderId.toString());
        payload.put("payout_id", payoutId.toString());
        payload.put("net_payout_paise", netPaise);
        payload.put("cashfree_transfer_id", cashfreeTransferId);
        payload.put("channel", "SMS");
        payload.put("template", "rider_payout_success");
        outbox.publish(
            DomainEvent.of("rider.notification.payout_released", "rider", riderId, payload));
      }

      @Override
      public void payoutFailed(UUID riderId, UUID payoutId, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rider_id", riderId.toString());
        payload.put("payout_id", payoutId.toString());
        payload.put("error", error);
        outbox.publish(DomainEvent.of("finance.alert.payout_failed", "rider", payoutId, payload));
      }
    };
  }

  @Bean
  @Primary
  CashfreeRoutePort riderCashfreePayoutBridge(
      com.nammamedmate.payment.application.port.out.CashfreePayoutPort paymentPayout) {
    return (riderId, netPayoutPaise, payoutId) -> {
      try {
        CashfreePayoutPort.PayoutResult result =
            paymentPayout.initiatePayout(
                new CashfreePayoutPort.PayoutRequest(
                    riderId, payoutId, netPayoutPaise, "0000", "XXXX0000"));
        String ref = "RPX-" + payoutId.toString().substring(0, 8).toUpperCase();
        return CashfreeRoutePort.PayoutResult.ok(result.cashfreeTransferId(), ref);
      } catch (RuntimeException ex) {
        String msg = ex.getMessage();
        return CashfreeRoutePort.PayoutResult.fail(
            msg == null || msg.isBlank() ? "cashfree_route_failed" : msg);
      }
    };
  }

  static final class JdbcRiderPayoutBridge implements RiderPayoutPort {

    private final JdbcTemplate jdbc;

    JdbcRiderPayoutBridge(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public Optional<PayoutRecord> findById(UUID payoutId) {
      List<PayoutRecord> rows =
          jdbc.query(
              """
              SELECT p.*, r.name AS rider_name, r.primary_zone_id AS zone_id, z.name AS zone_name
              FROM rider_payouts p
              LEFT JOIN riders r ON r.id = p.rider_id
              LEFT JOIN zones z ON z.id = r.primary_zone_id
              WHERE p.id = ? AND p.deleted_at IS NULL
              """,
              this::mapRow,
              payoutId);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey) {
      if (idempotencyKey == null || idempotencyKey.isBlank()) {
        return Optional.empty();
      }
      List<PayoutRecord> rows =
          jdbc.query(
              """
              SELECT p.*, r.name AS rider_name, r.primary_zone_id AS zone_id, z.name AS zone_name
              FROM rider_payouts p
              LEFT JOIN riders r ON r.id = p.rider_id
              LEFT JOIN zones z ON z.id = r.primary_zone_id
              WHERE p.release_idempotency_key = ? AND p.deleted_at IS NULL
              """,
              this::mapRow,
              idempotencyKey);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Optional<RiderSnapshot> findRider(UUID riderId) {
      List<RiderSnapshot> rows =
          jdbc.query(
              """
              SELECT id, name, cod_in_hand_paise
              FROM riders
              WHERE id = ? AND deleted_at IS NULL
              """,
              (rs, i) ->
                  new RiderSnapshot(
                      (UUID) rs.getObject("id"),
                      rs.getString("name"),
                      rs.getLong("cod_in_hand_paise")),
              riderId);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Optional<PaymentInstrument> findPaymentInstrument(UUID riderId) {
      // ponytail: no rider UPI/bank table yet — ACTIVE riders are treated as Route-ready.
      Long n =
          jdbc.queryForObject(
              """
              SELECT COUNT(1) FROM riders
              WHERE id = ? AND deleted_at IS NULL
                AND status IN ('ACTIVE', 'OFFLINE', 'ON_TRIP')
              """,
              Long.class,
              riderId);
      if (n == null || n == 0L) {
        return Optional.empty();
      }
      return Optional.of(new PaymentInstrument("ROUTE", "rider:" + riderId));
    }

    @Override
    public ListResult list(ListFilter filter) {
      StringBuilder where = new StringBuilder(" WHERE p.deleted_at IS NULL ");
      List<Object> args = new ArrayList<>();
      appendFilters(where, args, filter);

      Long total =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM rider_payouts p
              LEFT JOIN riders r ON r.id = p.rider_id
              """
                  + where,
              Long.class,
              args.toArray());
      long count = total == null ? 0L : total;

      List<Object> pageArgs = new ArrayList<>(args);
      pageArgs.add(filter.limit());
      pageArgs.add(filter.offset());
      List<PayoutRecord> rows =
          jdbc.query(
              """
              SELECT p.*, r.name AS rider_name, r.primary_zone_id AS zone_id, z.name AS zone_name
              FROM rider_payouts p
              LEFT JOIN riders r ON r.id = p.rider_id
              LEFT JOIN zones z ON z.id = r.primary_zone_id
              """
                  + where
                  + """
               ORDER BY p.cycle_from DESC, p.created_at DESC
               LIMIT ? OFFSET ?
              """,
              this::mapRow,
              pageArgs.toArray());
      return new ListResult(rows, count);
    }

    @Override
    public SummarySnapshot summary(LocalDate cycleFrom, UUID zoneId) {
      StringBuilder where = new StringBuilder(" WHERE p.deleted_at IS NULL ");
      List<Object> args = new ArrayList<>();
      if (cycleFrom != null) {
        where.append(" AND p.cycle_from = ? ");
        args.add(cycleFrom);
      }
      if (zoneId != null) {
        where.append(" AND r.primary_zone_id = ? ");
        args.add(zoneId);
      }
      return jdbc.query(
          """
          SELECT
            COALESCE(SUM(CASE WHEN p.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_n,
            COALESCE(SUM(CASE WHEN p.status = 'PENDING' THEN p.net_payout_paise ELSE 0 END), 0)
              AS pending_amt,
            COALESCE(SUM(CASE WHEN p.status = 'HELD' THEN 1 ELSE 0 END), 0) AS held_n,
            COALESCE(SUM(CASE WHEN p.status = 'HELD' THEN p.net_payout_paise ELSE 0 END), 0)
              AS held_amt,
            COALESCE(SUM(CASE WHEN p.status = 'RELEASED' THEN 1 ELSE 0 END), 0) AS released_n,
            COALESCE(SUM(CASE WHEN p.status = 'RELEASED' THEN p.net_payout_paise ELSE 0 END), 0)
              AS released_amt
          FROM rider_payouts p
          LEFT JOIN riders r ON r.id = p.rider_id
          """
              + where,
          rs -> {
            if (!rs.next()) {
              return new SummarySnapshot(0, 0, 0, 0, 0, 0);
            }
            return new SummarySnapshot(
                rs.getLong("pending_n"),
                rs.getLong("pending_amt"),
                rs.getLong("held_n"),
                rs.getLong("held_amt"),
                rs.getLong("released_n"),
                rs.getLong("released_amt"));
          },
          args.toArray());
    }

    @Override
    public ListResult listForRider(UUID riderId, int limit, int offset) {
      Long total =
          jdbc.queryForObject(
              """
              SELECT COUNT(1) FROM rider_payouts
              WHERE rider_id = ? AND deleted_at IS NULL
              """,
              Long.class,
              riderId);
      List<PayoutRecord> rows =
          jdbc.query(
              """
              SELECT p.*, r.name AS rider_name, r.primary_zone_id AS zone_id, z.name AS zone_name
              FROM rider_payouts p
              LEFT JOIN riders r ON r.id = p.rider_id
              LEFT JOIN zones z ON z.id = r.primary_zone_id
              WHERE p.rider_id = ? AND p.deleted_at IS NULL
              ORDER BY p.cycle_from DESC
              LIMIT ? OFFSET ?
              """,
              this::mapRow,
              riderId,
              limit,
              offset);
      return new ListResult(rows, total == null ? 0L : total);
    }

    @Override
    public List<EarningsEntry> listEarnings(
        UUID riderId, LocalDate from, LocalDate to, int limit, int offset) {
      StringBuilder sql =
          new StringBuilder(
              """
              SELECT e.delivery_date, e.order_id, o.order_number, e.base_pay_paise, e.tip_paise,
                     e.incentive_bonus_paise, e.total_paise, e.on_time, e.distance_km, e.created_at
              FROM rider_trip_earnings e
              LEFT JOIN orders o ON o.id = e.order_id
              WHERE e.rider_id = ?
              """);
      List<Object> args = new ArrayList<>();
      args.add(riderId);
      if (from != null) {
        sql.append(" AND e.delivery_date >= ? ");
        args.add(from);
      }
      if (to != null) {
        sql.append(" AND e.delivery_date <= ? ");
        args.add(to);
      }
      sql.append(" ORDER BY e.delivery_date DESC, e.created_at DESC LIMIT ? OFFSET ? ");
      args.add(limit);
      args.add(offset);
      return jdbc.query(
          sql.toString(),
          (rs, i) ->
              new EarningsEntry(
                  rs.getObject("delivery_date", LocalDate.class),
                  (UUID) rs.getObject("order_id"),
                  rs.getString("order_number"),
                  rs.getLong("base_pay_paise"),
                  rs.getLong("tip_paise"),
                  columnLong(rs, "incentive_bonus_paise"),
                  rs.getLong("total_paise"),
                  rs.getBoolean("on_time"),
                  rs.getBigDecimal("distance_km"),
                  tsInstant(rs, "created_at")),
          args.toArray());
    }

    @Override
    public long countEarnings(UUID riderId, LocalDate from, LocalDate to) {
      StringBuilder sql =
          new StringBuilder("SELECT COUNT(1) FROM rider_trip_earnings WHERE rider_id = ?");
      List<Object> args = new ArrayList<>();
      args.add(riderId);
      if (from != null) {
        sql.append(" AND delivery_date >= ? ");
        args.add(from);
      }
      if (to != null) {
        sql.append(" AND delivery_date <= ? ");
        args.add(to);
      }
      Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
      return n == null ? 0L : n;
    }

    @Override
    public Optional<PayoutRecord> findByRiderAndCycle(
        UUID riderId, LocalDate cycleFrom, LocalDate cycleTo) {
      List<PayoutRecord> rows =
          jdbc.query(
              """
              SELECT p.*, r.name AS rider_name, r.primary_zone_id AS zone_id, z.name AS zone_name
              FROM rider_payouts p
              LEFT JOIN riders r ON r.id = p.rider_id
              LEFT JOIN zones z ON z.id = r.primary_zone_id
              WHERE p.rider_id = ? AND p.cycle_from = ? AND p.cycle_to = ?
                AND p.deleted_at IS NULL
              """,
              this::mapRow,
              riderId,
              Date.valueOf(cycleFrom),
              Date.valueOf(cycleTo));
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public long codFloatLimitPaise() {
      List<String> vals =
          jdbc.query(
              """
              SELECT value FROM platform_pricing_config WHERE key = 'cod_float_limit_default'
              """,
              (rs, i) -> rs.getString("value"));
      if (vals.isEmpty() || vals.getFirst() == null || vals.getFirst().isBlank()) {
        return RiderPayoutStatuses.DEFAULT_COD_FLOAT_LIMIT_PAISE;
      }
      try {
        String t = vals.getFirst().trim();
        if (t.contains(".")) {
          return new BigDecimal(t).movePointRight(2).longValue();
        }
        return Long.parseLong(t);
      } catch (RuntimeException e) {
        return RiderPayoutStatuses.DEFAULT_COD_FLOAT_LIMIT_PAISE;
      }
    }

    @Override
    public boolean claimForRelease(
        UUID payoutId, UUID riderId, String idempotencyKey, Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE rider_payouts SET
                release_idempotency_key = ?,
                updated_at = ?
              WHERE id = ? AND rider_id = ? AND deleted_at IS NULL
                AND release_idempotency_key IS NULL
                AND status IN ('HELD', 'FAILED', 'PENDING')
              """,
              idempotencyKey,
              Timestamp.from(now),
              payoutId,
              riderId);
      return updated == 1;
    }

    @Override
    public boolean finalizeRelease(
        UUID payoutId,
        UUID releasedBy,
        Instant releasedAt,
        String cashfreeTransferId,
        String notes,
        String idempotencyKey,
        Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE rider_payouts SET
                status = 'RELEASED',
                released_by = ?,
                released_at = ?,
                cashfree_transfer_id = ?,
                release_notes = COALESCE(?, release_notes),
                hold_reason = NULL,
                next_retry_at = NULL,
                last_attempt_at = ?,
                updated_at = ?
              WHERE id = ? AND deleted_at IS NULL
                AND release_idempotency_key = ?
                AND status IN ('HELD', 'FAILED', 'PENDING')
              """,
              releasedBy,
              Timestamp.from(releasedAt),
              cashfreeTransferId,
              notes,
              Timestamp.from(now),
              Timestamp.from(now),
              payoutId,
              idempotencyKey);
      return updated == 1;
    }

    @Override
    public void scheduleRetry(
        UUID payoutId, String idempotencyKey, String error, Instant retryAt, Instant now) {
      jdbc.update(
          """
          UPDATE rider_payouts SET
            status = 'PENDING',
            hold_reason = ?,
            retry_count = 0,
            next_retry_at = ?,
            last_attempt_at = ?,
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
            AND release_idempotency_key = ?
          """,
          error,
          Timestamp.from(retryAt),
          Timestamp.from(now),
          Timestamp.from(now),
          payoutId,
          idempotencyKey);
    }

    @Override
    public void markFailed(UUID payoutId, String idempotencyKey, String error, Instant now) {
      jdbc.update(
          """
          UPDATE rider_payouts SET
            status = 'FAILED',
            hold_reason = ?,
            retry_count = 1,
            next_retry_at = NULL,
            last_attempt_at = ?,
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
            AND release_idempotency_key = ?
          """,
          error,
          Timestamp.from(now),
          Timestamp.from(now),
          payoutId,
          idempotencyKey);
    }

    @Override
    public void markBelowThreshold(UUID payoutId, String notes, Instant now) {
      jdbc.update(
          """
          UPDATE rider_payouts SET
            status = 'BELOW_THRESHOLD_CARRIED_FORWARD',
            release_notes = COALESCE(?, release_notes),
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          notes,
          Timestamp.from(now),
          payoutId);
    }

    @Override
    public void adjustEarningsWallet(UUID riderId, long deltaPaise, Instant now) {
      jdbc.update(
          """
          UPDATE riders SET
            earnings_wallet_balance_paise = GREATEST(0, earnings_wallet_balance_paise + ?),
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          deltaPaise,
          Timestamp.from(now),
          riderId);
    }

    @Override
    public List<PayoutRecord> listPendingForBulk(
        long minPaiseInclusive, long maxPaiseInclusive, LocalDate cycleFrom, int limit) {
      StringBuilder sql =
          new StringBuilder(
              """
              SELECT p.*, r.name AS rider_name, r.primary_zone_id AS zone_id, z.name AS zone_name
              FROM rider_payouts p
              LEFT JOIN riders r ON r.id = p.rider_id
              LEFT JOIN zones z ON z.id = r.primary_zone_id
              WHERE p.deleted_at IS NULL
                AND p.status = 'PENDING'
                AND p.net_payout_paise >= ?
                AND p.net_payout_paise <= ?
              """);
      List<Object> args = new ArrayList<>();
      args.add(minPaiseInclusive);
      args.add(maxPaiseInclusive);
      if (cycleFrom != null) {
        sql.append(" AND p.cycle_from = ? ");
        args.add(cycleFrom);
      }
      sql.append(" ORDER BY p.net_payout_paise ASC, p.cycle_from ASC LIMIT ? ");
      args.add(limit);
      return jdbc.query(sql.toString(), this::mapRow, args.toArray());
    }

    private static void appendFilters(StringBuilder where, List<Object> args, ListFilter filter) {
      if (filter.storageStatus() != null && !filter.storageStatus().isBlank()) {
        where.append(" AND p.status = ? ");
        args.add(filter.storageStatus());
      }
      if (filter.cycleFrom() != null) {
        where.append(" AND p.cycle_from = ? ");
        args.add(filter.cycleFrom());
      }
      if (filter.zoneId() != null) {
        where.append(" AND r.primary_zone_id = ? ");
        args.add(filter.zoneId());
      }
    }

    private PayoutRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new PayoutRecord(
          (UUID) rs.getObject("id"),
          (UUID) rs.getObject("rider_id"),
          columnString(rs, "rider_name"),
          (UUID) rs.getObject("zone_id"),
          columnString(rs, "zone_name"),
          rs.getObject("cycle_from", LocalDate.class),
          rs.getObject("cycle_to", LocalDate.class),
          rs.getLong("base_earnings_paise"),
          rs.getLong("incentives_paise"),
          rs.getLong("tips_paise"),
          rs.getLong("streak_bonus_paise"),
          columnLong(rs, "carry_forward_paise"),
          rs.getLong("cod_deducted_paise"),
          rs.getLong("net_payout_paise"),
          rs.getString("status"),
          rs.getString("hold_reason"),
          rs.getString("cashfree_transfer_id"),
          columnString(rs, "payout_reference"),
          columnString(rs, "release_notes"),
          (UUID) rs.getObject("released_by"),
          tsInstant(rs, "released_at"),
          rs.getInt("retry_count"),
          tsInstant(rs, "next_retry_at"),
          columnString(rs, "release_idempotency_key"));
    }

    private static long columnLong(ResultSet rs, String col) throws SQLException {
      try {
        long v = rs.getLong(col);
        return rs.wasNull() ? 0L : v;
      } catch (SQLException e) {
        return 0L;
      }
    }

    private static String columnString(ResultSet rs, String col) throws SQLException {
      try {
        return rs.getString(col);
      } catch (SQLException e) {
        return null;
      }
    }

    private static Instant tsInstant(ResultSet rs, String col) throws SQLException {
      try {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
      } catch (SQLException e) {
        return null;
      }
    }
  }
}
