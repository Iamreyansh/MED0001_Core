package com.nammamedmate.api.config;

import com.nammamedmate.payment.application.port.out.PharmacySettlementPort;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort;
import com.nammamedmate.payment.application.port.out.SettlementNotificationPort;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridge: payment finance façades ↔ V019 settlement + pharmacy bank + orders. Also
 * re-exports payment RazorpayX as pharmacy's payout port so legacy admin pharmacy settlement
 * endpoints share the live|stub client.
 */
@Configuration
public class PaymentSettlementBridgeConfig {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  @Bean
  @Primary
  PharmacySettlementPort jdbcPharmacySettlementPort(JdbcTemplate jdbc) {
    return new JdbcPharmacySettlementBridge(jdbc);
  }

  @Bean
  @Primary
  SettlementNotificationPort settlementNotificationBridge(NotificationDispatchPort notifications) {
    return new SettlementNotificationPort() {
      @Override
      public void settlementReleased(UUID pharmacyId, UUID settlementId, long netPaise) {
        notifications.dispatchSettlementReleased(pharmacyId, settlementId, netPaise);
      }

      @Override
      public void settlementHeld(UUID pharmacyId, UUID settlementId, String reason) {
        notifications.dispatchSettlementHeld(pharmacyId, settlementId, reason);
      }
    };
  }

  @Bean
  @Primary
  com.nammamedmate.pharmacy.application.port.out.RazorpayXPayoutPort pharmacyRazorpayXBridge(
      RazorpayXPayoutPort paymentPayout) {
    return request -> {
      RazorpayXPayoutPort.PayoutResult result =
          paymentPayout.initiatePayout(
              new RazorpayXPayoutPort.PayoutRequest(
                  request.pharmacyId(),
                  request.settlementId(),
                  request.amountPaise(),
                  request.accountLast4(),
                  request.ifsc()));
      return new com.nammamedmate.pharmacy.application.port.out.RazorpayXPayoutPort.PayoutResult(
          result.razorpayxPayoutId(), result.estimatedCreditHours());
    };
  }

  static final class JdbcPharmacySettlementBridge implements PharmacySettlementPort {

    private final JdbcTemplate jdbc;

    JdbcPharmacySettlementBridge(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public Optional<SettlementRecord> findById(UUID settlementId) {
      List<SettlementRecord> rows =
          jdbc.query(
              """
              SELECT s.*, p.business_name AS pharmacy_name
              FROM settlement s
              LEFT JOIN pharmacies p ON p.id = s.pharmacy_id
              WHERE s.id = ? AND s.deleted_at IS NULL
              """,
              this::mapRow,
              settlementId);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Optional<SettlementRecord> findByIdempotencyKey(String idempotencyKey) {
      List<SettlementRecord> rows =
          jdbc.query(
              """
              SELECT s.*, p.business_name AS pharmacy_name
              FROM settlement s
              LEFT JOIN pharmacies p ON p.id = s.pharmacy_id
              WHERE s.release_idempotency_key = ? AND s.deleted_at IS NULL
              """,
              this::mapRow,
              idempotencyKey);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public ListResult list(ListFilter filter) {
      StringBuilder where = new StringBuilder(" WHERE s.deleted_at IS NULL ");
      List<Object> args = new ArrayList<>();
      appendFilters(where, args, filter);

      Long total =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM settlement s" + where, Long.class, args.toArray());
      long count = total == null ? 0L : total;

      List<Object> pageArgs = new ArrayList<>(args);
      pageArgs.add(filter.limit());
      pageArgs.add(filter.offset());
      List<SettlementRecord> rows =
          jdbc.query(
              """
              SELECT s.*, p.business_name AS pharmacy_name
              FROM settlement s
              LEFT JOIN pharmacies p ON p.id = s.pharmacy_id
              """
                  + where
                  + """
               ORDER BY s.period_start DESC
               LIMIT ? OFFSET ?
              """,
              this::mapRow,
              pageArgs.toArray());
      return new ListResult(rows, count);
    }

    @Override
    public Totals totals(ListFilter filter) {
      StringBuilder where = new StringBuilder(" WHERE s.deleted_at IS NULL ");
      List<Object> args = new ArrayList<>();
      appendFilters(where, args, filter);
      return jdbc.query(
          """
              SELECT
                COALESCE(SUM(s.gmv_paise), 0) AS gmv,
                COALESCE(SUM(s.commission_earned_paise), 0) AS commission,
                COALESCE(SUM(s.tcs_deducted_paise), 0) AS tcs,
                COALESCE(SUM(s.net_paid_paise), 0) AS net
              FROM settlement s
              """
              + where,
          rs -> {
            if (!rs.next()) {
              return new Totals(0, 0, 0, 0);
            }
            return new Totals(
                rs.getLong("gmv"), rs.getLong("commission"), rs.getLong("tcs"), rs.getLong("net"));
          },
          args.toArray());
    }

    @Override
    public KpiSnapshot kpis(Instant dayStartIst, Instant dayEndIst) {
      Long gmvToday =
          jdbc.queryForObject(
              """
              SELECT COALESCE(SUM(gmv_paise), 0) FROM settlement
              WHERE deleted_at IS NULL AND created_at >= ? AND created_at < ?
              """,
              Long.class,
              Timestamp.from(dayStartIst),
              Timestamp.from(dayEndIst));
      Long commissionToday =
          jdbc.queryForObject(
              """
              SELECT COALESCE(SUM(commission_earned_paise), 0) FROM settlement
              WHERE deleted_at IS NULL AND created_at >= ? AND created_at < ?
              """,
              Long.class,
              Timestamp.from(dayStartIst),
              Timestamp.from(dayEndIst));
      Long payoutDue =
          jdbc.queryForObject(
              """
              SELECT COALESCE(SUM(net_paid_paise), 0) FROM settlement
              WHERE deleted_at IS NULL AND status = 'PENDING_RELEASE'
              """,
              Long.class);
      Long releasedToday =
          jdbc.queryForObject(
              """
              SELECT COALESCE(SUM(net_paid_paise), 0) FROM settlement
              WHERE deleted_at IS NULL
                AND status IN ('RELEASED', 'PAID')
                AND released_at >= ? AND released_at < ?
              """,
              Long.class,
              Timestamp.from(dayStartIst),
              Timestamp.from(dayEndIst));
      return new KpiSnapshot(
          gmvToday == null ? 0 : gmvToday,
          commissionToday == null ? 0 : commissionToday,
          payoutDue == null ? 0 : payoutDue,
          releasedToday == null ? 0 : releasedToday);
    }

    @Override
    public Optional<BankSnapshot> findVerifiedBank(UUID pharmacyId) {
      List<BankSnapshot> rows =
          jdbc.query(
              """
              SELECT bank_name, account_number_last4, ifsc_code, verification_status
              FROM pharmacy_bank_accounts
              WHERE pharmacy_id = ? AND deleted_at IS NULL
              ORDER BY created_at DESC
              LIMIT 1
              """,
              (rs, i) -> {
                String last4 = rs.getString("account_number_last4");
                String masked =
                    last4 == null || last4.isBlank() ? "XXXXXXXXXXXX" : "XXXXXXXXXXXX" + last4;
                return new BankSnapshot(
                    masked,
                    rs.getString("bank_name"),
                    rs.getString("ifsc_code"),
                    rs.getString("verification_status"));
              },
              pharmacyId);
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      BankSnapshot bank = rows.getFirst();
      if (!"VERIFIED".equals(bank.verificationStatus())) {
        return Optional.empty();
      }
      return Optional.of(bank);
    }

    @Override
    public List<LineItem> lineItems(
        UUID pharmacyId, LocalDate cycleFrom, LocalDate cycleTo, BigDecimal commissionPct) {
      Instant from = cycleFrom.atStartOfDay(IST).toInstant();
      Instant to = cycleTo.plusDays(1).atStartOfDay(IST).toInstant();
      BigDecimal pct = commissionPct == null ? BigDecimal.ZERO : commissionPct;
      return jdbc.query(
          """
          SELECT id, order_number, delivered_at, total_payable_paise
          FROM orders
          WHERE pharmacy_id = ? AND deleted_at IS NULL
            AND status = 'DELIVERED'
            AND payment_status IN ('CAPTURED', 'COLLECTED_COD')
            AND delivered_at >= ? AND delivered_at < ?
          ORDER BY delivered_at ASC
          """,
          (rs, i) -> {
            long gmv = rs.getLong("total_payable_paise");
            long commission =
                BigDecimal.valueOf(gmv)
                    .multiply(pct)
                    .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                    .longValue();
            long tcs =
                BigDecimal.valueOf(gmv)
                    .multiply(BigDecimal.ONE)
                    .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                    .longValue();
            // ponytail: line-item TCS shown at 1%; generation may skip TCS below annual threshold
            Timestamp delivered = rs.getTimestamp("delivered_at");
            return new LineItem(
                (UUID) rs.getObject("id"),
                rs.getString("order_number"),
                delivered == null ? null : delivered.toInstant(),
                gmv,
                pct,
                commission,
                tcs,
                gmv - commission - tcs);
          },
          pharmacyId,
          Timestamp.from(from),
          Timestamp.from(to));
    }

    @Override
    public boolean claimForRelease(
        UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE settlement SET
                release_idempotency_key = ?,
                updated_at = ?
              WHERE id = ? AND pharmacy_id = ? AND status = 'PENDING_RELEASE'
                AND release_idempotency_key IS NULL AND deleted_at IS NULL
              """,
              idempotencyKey,
              Timestamp.from(now),
              settlementId,
              pharmacyId);
      return updated > 0;
    }

    @Override
    public boolean finalizeRelease(
        UUID settlementId,
        UUID releasedBy,
        Instant releasedAt,
        String razorpayxPayoutId,
        String notes,
        String idempotencyKey,
        Instant now) {
      int updated =
          jdbc.update(
              """
              UPDATE settlement SET
                status = 'RELEASED',
                released_by = ?,
                released_at = ?,
                razorpayx_payout_id = ?,
                notes = COALESCE(?, notes),
                updated_at = ?
              WHERE id = ? AND status = 'PENDING_RELEASE'
                AND release_idempotency_key = ? AND deleted_at IS NULL
              """,
              releasedBy,
              Timestamp.from(releasedAt),
              razorpayxPayoutId,
              notes,
              Timestamp.from(now),
              settlementId,
              idempotencyKey);
      return updated > 0;
    }

    @Override
    public void markReleaseFailed(UUID settlementId, String idempotencyKey, Instant now) {
      jdbc.update(
          """
          UPDATE settlement SET
            status = 'FAILED',
            updated_at = ?
          WHERE id = ? AND status = 'PENDING_RELEASE'
            AND release_idempotency_key = ? AND deleted_at IS NULL
          """,
          Timestamp.from(now),
          settlementId,
          idempotencyKey);
    }

    @Override
    public void markHeld(
        UUID settlementId, UUID heldBy, String reason, String notes, Instant heldAt) {
      jdbc.update(
          """
          UPDATE settlement SET
            status = 'HELD',
            hold_reason = ?,
            held_by = ?,
            held_at = ?,
            notes = COALESCE(?, notes),
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          reason,
          heldBy,
          Timestamp.from(heldAt),
          notes,
          Timestamp.from(heldAt),
          settlementId);
    }

    @Override
    public void markBelowThreshold(UUID settlementId, String notes, Instant now) {
      jdbc.update(
          """
          UPDATE settlement SET
            status = 'BELOW_THRESHOLD_CARRIED',
            notes = COALESCE(?, notes),
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          notes,
          Timestamp.from(now),
          settlementId);
    }

    @Override
    public List<SettlementRecord> listPendingForBulk(long maxNetPaiseInclusive, int limit) {
      return jdbc.query(
          """
          SELECT s.*, p.business_name AS pharmacy_name
          FROM settlement s
          LEFT JOIN pharmacies p ON p.id = s.pharmacy_id
          WHERE s.deleted_at IS NULL
            AND s.status = 'PENDING_RELEASE'
            AND s.net_paid_paise <= ?
          ORDER BY s.net_paid_paise ASC, s.period_start ASC
          LIMIT ?
          """,
          this::mapRow,
          maxNetPaiseInclusive,
          limit);
    }

    private static void appendFilters(StringBuilder where, List<Object> args, ListFilter filter) {
      if (filter.storageStatus() != null && !filter.storageStatus().isBlank()) {
        if ("RELEASED".equals(filter.storageStatus())) {
          where.append(" AND s.status IN ('RELEASED', 'PAID') ");
        } else {
          where.append(" AND s.status = ? ");
          args.add(filter.storageStatus());
        }
      }
      if (filter.pharmacyId() != null) {
        where.append(" AND s.pharmacy_id = ? ");
        args.add(filter.pharmacyId());
      }
      if (filter.cycleFrom() != null) {
        where.append(" AND s.period_start >= ? ");
        args.add(filter.cycleFrom());
      }
    }

    private SettlementRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new SettlementRecord(
          (UUID) rs.getObject("id"),
          (UUID) rs.getObject("pharmacy_id"),
          rs.getString("pharmacy_name"),
          rs.getObject("period_start", LocalDate.class),
          rs.getObject("period_end", LocalDate.class),
          rs.getLong("gmv_paise"),
          rs.getBigDecimal("commission_pct"),
          rs.getLong("commission_earned_paise"),
          rs.getLong("tcs_deducted_paise"),
          columnLong(rs, "gst_on_commission_paise"),
          rs.getLong("net_paid_paise"),
          columnInt(rs, "orders_count"),
          rs.getString("status"),
          rs.getString("hold_reason"),
          (UUID) rs.getObject("held_by"),
          tsInstant(rs, "held_at"),
          (UUID) rs.getObject("released_by"),
          tsInstant(rs, "released_at"),
          rs.getString("razorpayx_payout_id"),
          columnString(rs, "notes"),
          rs.getString("release_idempotency_key"));
    }

    private static long columnLong(ResultSet rs, String col) throws SQLException {
      try {
        long v = rs.getLong(col);
        return rs.wasNull() ? 0L : v;
      } catch (SQLException e) {
        return 0L;
      }
    }

    private static int columnInt(ResultSet rs, String col) throws SQLException {
      try {
        int v = rs.getInt(col);
        return rs.wasNull() ? 0 : v;
      } catch (SQLException e) {
        return 0;
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
