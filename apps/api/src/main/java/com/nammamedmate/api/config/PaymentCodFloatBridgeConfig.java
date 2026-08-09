package com.nammamedmate.api.config;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.payment.application.CodFloatFacadeService;
import com.nammamedmate.payment.application.port.out.CodFloatAlertPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort.DayAggregates;
import com.nammamedmate.payment.application.port.out.CodFloatPort.FloatRiderRow;
import com.nammamedmate.payment.application.port.out.CodFloatPort.FloatSnapshot;
import com.nammamedmate.payment.application.port.out.CodFloatPort.ReportRecord;
import com.nammamedmate.payment.application.port.out.CodFloatPort.RiderDayBreakdown;
import com.nammamedmate.rider.application.port.out.CodDepositConfirmedPort;
import com.nammamedmate.rider.application.port.out.FinanceCodDailyReconciliationPort;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridge: payment COD float façade ↔ V041 COD tables + V062 reconciliation report
 * + ledger COD_DEPOSIT on deposit confirm + 23:00 IST rider scheduler.
 */
@Configuration
public class PaymentCodFloatBridgeConfig {

  @Bean
  @Primary
  CodFloatPort jdbcCodFloatPort(JdbcTemplate jdbc) {
    return new JdbcCodFloatBridge(jdbc);
  }

  @Bean
  @Primary
  CodFloatAlertPort codFloatAlertBridge(OutboxPublisher outbox) {
    return (reportId, reportDate, variancePaise, reconciliationStatus) -> {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("report_id", reportId.toString());
      payload.put("report_date", reportDate.toString());
      payload.put("variance_paise", variancePaise);
      payload.put("reconciliation_status", reconciliationStatus);
      payload.put("audience", "admin_finance");
      payload.put("channels", List.of("EMAIL", "PUSH"));
      payload.put("template", "COD_FLOAT_VARIANCE_ALERT");
      outbox.publish(DomainEvent.of("finance.cod.variance_alert", "finance", reportId, payload));
    };
  }

  @Bean
  @Primary
  CodDepositConfirmedPort codDepositLedgerBridge(CodFloatFacadeService facade) {
    return facade::onDepositConfirmed;
  }

  @Bean
  @Primary
  FinanceCodDailyReconciliationPort financeCodDailyBridge(CodFloatFacadeService facade) {
    return facade::runScheduledReconciliation;
  }

  static final class JdbcCodFloatBridge implements CodFloatPort {

    private final JdbcTemplate jdbc;

    JdbcCodFloatBridge(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public long floatLimitPaise() {
      List<String> rows =
          jdbc.query(
              "SELECT value FROM platform_pricing_config WHERE key = 'cod_float_limit_default'",
              (rs, i) -> rs.getString(1));
      if (rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank()) {
        return 200_000L;
      }
      try {
        String raw = rows.getFirst().trim();
        if (raw.contains(".")) {
          return Math.round(Double.parseDouble(raw) * 100);
        }
        return Long.parseLong(raw);
      } catch (NumberFormatException e) {
        return 200_000L;
      }
    }

    @Override
    public FloatSnapshot floatBoard(
        UUID zoneId,
        boolean riskOnly,
        Instant dayStart,
        Instant dayEnd,
        long limitPaise,
        int page,
        int limit) {
      StringBuilder where =
          new StringBuilder(
              """
              WHERE r.deleted_at IS NULL
                AND r.status NOT IN ('PENDING_KYC')
                AND (r.cod_in_hand_paise > 0 OR EXISTS (
                  SELECT 1 FROM cod_collections c WHERE c.rider_id = r.id
                ) OR EXISTS (
                  SELECT 1 FROM cod_deposits d
                  WHERE d.rider_id = r.id AND d.deleted_at IS NULL
                ))
              """);
      List<Object> args = new ArrayList<>();
      if (zoneId != null) {
        where.append(" AND COALESCE(r.current_zone_id, r.primary_zone_id) = ? ");
        args.add(zoneId);
      }
      if (riskOnly) {
        where.append(" AND r.cod_in_hand_paise > ? ");
        args.add(limitPaise);
      }

      long total =
          Objects.requireNonNullElse(
              jdbc.queryForObject(
                  "SELECT COUNT(1) FROM riders r " + where, Long.class, args.toArray()),
              0L);

      int offset = (page - 1) * limit;
      List<Object> pageArgs = new ArrayList<>(args);
      pageArgs.add(Timestamp.from(dayStart));
      pageArgs.add(Timestamp.from(dayEnd));
      pageArgs.add(Timestamp.from(dayStart));
      pageArgs.add(Timestamp.from(dayEnd));
      pageArgs.add(limit);
      pageArgs.add(offset);

      List<FloatRiderRow> rows =
          jdbc.query(
              """
              SELECT r.id, r.name, z.name AS zone_name, r.cod_in_hand_paise,
                (SELECT COALESCE(SUM(c.cod_amount_paise), 0) FROM cod_collections c
                 WHERE c.rider_id = r.id AND c.collected_at >= ? AND c.collected_at < ?) AS collected,
                (SELECT COALESCE(SUM(d.amount_paise), 0) FROM cod_deposits d
                 WHERE d.rider_id = r.id AND d.status = 'CONFIRMED' AND d.deleted_at IS NULL
                   AND COALESCE(d.confirmed_at, d.deposited_at, d.submitted_at) >= ?
                   AND COALESCE(d.confirmed_at, d.deposited_at, d.submitted_at) < ?) AS deposited,
                (SELECT MAX(d2.confirmed_at) FROM cod_deposits d2
                 WHERE d2.rider_id = r.id AND d2.status = 'CONFIRMED' AND d2.deleted_at IS NULL
                   AND d2.confirmed_at IS NOT NULL) AS last_deposit_at
              FROM riders r
              LEFT JOIN zones z ON z.id = COALESCE(r.current_zone_id, r.primary_zone_id)
              """
                  + where
                  + " ORDER BY r.cod_in_hand_paise DESC, r.name ASC LIMIT ? OFFSET ?",
              this::mapFloatRider,
              pageArgs.toArray());

      long totalInTransit =
          Objects.requireNonNullElse(
              jdbc.queryForObject(
                  "SELECT COALESCE(SUM(cod_in_hand_paise), 0) FROM riders WHERE deleted_at IS NULL",
                  Long.class),
              0L);
      long collectedToday =
          Objects.requireNonNullElse(
              jdbc.queryForObject(
                  """
                  SELECT COALESCE(SUM(cod_amount_paise), 0) FROM cod_collections
                  WHERE collected_at >= ? AND collected_at < ?
                  """,
                  Long.class,
                  Timestamp.from(dayStart),
                  Timestamp.from(dayEnd)),
              0L);
      long depositedToday =
          Objects.requireNonNullElse(
              jdbc.queryForObject(
                  """
                  SELECT COALESCE(SUM(amount_paise), 0) FROM cod_deposits
                  WHERE status = 'CONFIRMED' AND deleted_at IS NULL
                    AND COALESCE(confirmed_at, deposited_at, submitted_at) >= ?
                    AND COALESCE(confirmed_at, deposited_at, submitted_at) < ?
                  """,
                  Long.class,
                  Timestamp.from(dayStart),
                  Timestamp.from(dayEnd)),
              0L);
      long floatRiskAmount =
          Objects.requireNonNullElse(
              jdbc.queryForObject(
                  """
                  SELECT COALESCE(SUM(cod_in_hand_paise), 0) FROM riders
                  WHERE deleted_at IS NULL AND cod_in_hand_paise > ?
                  """,
                  Long.class,
                  limitPaise),
              0L);
      int floatRiskCount =
          Objects.requireNonNullElse(
                  jdbc.queryForObject(
                      """
                      SELECT COUNT(1) FROM riders
                      WHERE deleted_at IS NULL AND cod_in_hand_paise > ?
                      """,
                      Long.class,
                      limitPaise),
                  0L)
              .intValue();

      return new FloatSnapshot(
          rows,
          total,
          totalInTransit,
          collectedToday,
          depositedToday,
          floatRiskAmount,
          floatRiskCount);
    }

    private FloatRiderRow mapFloatRider(ResultSet rs, int rowNum) throws SQLException {
      Timestamp last = rs.getTimestamp("last_deposit_at");
      return new FloatRiderRow(
          (UUID) rs.getObject("id"),
          rs.getString("name"),
          rs.getString("zone_name"),
          rs.getLong("collected"),
          rs.getLong("deposited"),
          rs.getLong("cod_in_hand_paise"),
          last == null ? null : last.toInstant());
    }

    @Override
    public DayAggregates aggregatesForDay(Instant dayStart, Instant dayEnd) {
      Timestamp start = Timestamp.from(dayStart);
      Timestamp end = Timestamp.from(dayEnd);

      Integer orderCount =
          jdbc.queryForObject(
              """
              SELECT COUNT(1) FROM cod_collections
              WHERE collected_at >= ? AND collected_at < ?
              """,
              Integer.class,
              start,
              end);
      Long collected =
          jdbc.queryForObject(
              """
              SELECT COALESCE(SUM(cod_amount_paise), 0) FROM cod_collections
              WHERE collected_at >= ? AND collected_at < ?
              """,
              Long.class,
              start,
              end);
      Long deposited =
          jdbc.queryForObject(
              """
              SELECT COALESCE(SUM(amount_paise), 0) FROM cod_deposits
              WHERE status = 'CONFIRMED' AND deleted_at IS NULL
                AND COALESCE(confirmed_at, deposited_at, submitted_at) >= ?
                AND COALESCE(confirmed_at, deposited_at, submitted_at) < ?
              """,
              Long.class,
              start,
              end);

      List<RiderDayBreakdown> riders =
          jdbc.query(
              """
              SELECT r.id, r.name,
                COALESCE(c.orders, 0) AS orders,
                COALESCE(c.collected, 0) AS collected,
                COALESCE(d.deposited, 0) AS deposited
              FROM riders r
              LEFT JOIN (
                SELECT rider_id, COUNT(1) AS orders, SUM(cod_amount_paise) AS collected
                FROM cod_collections
                WHERE collected_at >= ? AND collected_at < ?
                GROUP BY rider_id
              ) c ON c.rider_id = r.id
              LEFT JOIN (
                SELECT rider_id, SUM(amount_paise) AS deposited
                FROM cod_deposits
                WHERE status = 'CONFIRMED' AND deleted_at IS NULL
                  AND COALESCE(confirmed_at, deposited_at, submitted_at) >= ?
                  AND COALESCE(confirmed_at, deposited_at, submitted_at) < ?
                GROUP BY rider_id
              ) d ON d.rider_id = r.id
              WHERE r.deleted_at IS NULL
                AND (c.rider_id IS NOT NULL OR d.rider_id IS NOT NULL)
              ORDER BY r.name ASC
              """,
              (rs, i) ->
                  new RiderDayBreakdown(
                      (UUID) rs.getObject("id"),
                      rs.getString("name"),
                      rs.getInt("orders"),
                      rs.getLong("collected"),
                      rs.getLong("deposited")),
              start,
              end,
              start,
              end);

      long collectedPaise = collected == null ? 0L : collected;
      return new DayAggregates(
          orderCount == null ? 0 : orderCount,
          collectedPaise,
          collectedPaise,
          deposited == null ? 0L : deposited,
          riders);
    }

    @Override
    public Optional<ReportRecord> findReport(LocalDate reportDate) {
      List<ReportRecord> rows =
          jdbc.query(
              """
              SELECT * FROM cod_reconciliation_report
              WHERE report_date = ? AND deleted_at IS NULL
              """,
              this::mapReport,
              Date.valueOf(reportDate));
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public boolean tryClaimJob(UUID jobId, LocalDate reportDate, UUID triggeredBy, Instant now) {
      Optional<ReportRecord> existing = findReport(reportDate);
      if (existing.isPresent()) {
        if ("PENDING".equals(existing.get().reconciliationStatus())) {
          return false;
        }
        jdbc.update(
            """
            UPDATE cod_reconciliation_report SET
              id = ?,
              total_cod_orders = 0,
              total_cod_amount_paise = 0,
              collected_by_riders_paise = 0,
              deposited_to_platform_paise = 0,
              outstanding_float_paise = 0,
              variance_paise = 0,
              variance_reason = NULL,
              reconciliation_status = 'PENDING',
              alert_sent = FALSE,
              generated_at = ?,
              triggered_by = ?,
              rider_breakdown_json = '[]'::jsonb,
              updated_at = ?
            WHERE report_date = ? AND deleted_at IS NULL
              AND reconciliation_status <> 'PENDING'
            """,
            jobId,
            Timestamp.from(now),
            triggeredBy,
            Timestamp.from(now),
            Date.valueOf(reportDate));
        Optional<ReportRecord> after = findReport(reportDate);
        return after.isPresent()
            && "PENDING".equals(after.get().reconciliationStatus())
            && jobId.equals(after.get().id());
      }
      try {
        jdbc.update(
            """
            INSERT INTO cod_reconciliation_report (
              id, report_date, total_cod_orders, total_cod_amount_paise,
              collected_by_riders_paise, deposited_to_platform_paise, outstanding_float_paise,
              variance_paise, variance_reason, reconciliation_status, alert_sent,
              generated_at, triggered_by, rider_breakdown_json, created_at, updated_at)
            VALUES (?,?,0,0,0,0,0,0,NULL,'PENDING',FALSE,?,?,'[]'::jsonb,?,?)
            """,
            jobId,
            Date.valueOf(reportDate),
            Timestamp.from(now),
            triggeredBy,
            Timestamp.from(now),
            Timestamp.from(now));
        return true;
      } catch (DuplicateKeyException e) {
        return false;
      }
    }

    @Override
    public void completeReport(ReportRecord report) {
      jdbc.update(
          """
          UPDATE cod_reconciliation_report SET
            total_cod_orders = ?,
            total_cod_amount_paise = ?,
            collected_by_riders_paise = ?,
            deposited_to_platform_paise = ?,
            outstanding_float_paise = ?,
            variance_paise = ?,
            variance_reason = ?,
            reconciliation_status = ?,
            alert_sent = ?,
            generated_at = ?,
            triggered_by = ?,
            rider_breakdown_json = ?::jsonb,
            updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          report.totalCodOrders(),
          report.totalCodAmountPaise(),
          report.collectedByRidersPaise(),
          report.depositedToPlatformPaise(),
          report.outstandingFloatPaise(),
          report.variancePaise(),
          report.varianceReason(),
          report.reconciliationStatus(),
          report.alertSent(),
          Timestamp.from(report.generatedAt()),
          report.triggeredBy(),
          report.riderBreakdownJson() == null ? "[]" : report.riderBreakdownJson(),
          Timestamp.from(report.generatedAt()),
          report.id());
    }

    @Override
    public boolean hasCodDepositLedgerEntry(UUID depositId) {
      Long n =
          jdbc.queryForObject(
              """
              SELECT COUNT(1) FROM financial_ledger
              WHERE entry_type = 'COD_DEPOSIT'
                AND reference_type = 'COD_DEPOSIT'
                AND reference_id = ?
              """,
              Long.class,
              depositId);
      return Objects.requireNonNullElse(n, 0L) > 0;
    }

    private ReportRecord mapReport(ResultSet rs, int rowNum) throws SQLException {
      Timestamp generated = rs.getTimestamp("generated_at");
      Object breakdown = rs.getObject("rider_breakdown_json");
      String breakdownJson = breakdown == null ? "[]" : breakdown.toString();
      return new ReportRecord(
          (UUID) rs.getObject("id"),
          rs.getObject("report_date", LocalDate.class),
          rs.getInt("total_cod_orders"),
          rs.getLong("total_cod_amount_paise"),
          rs.getLong("collected_by_riders_paise"),
          rs.getLong("deposited_to_platform_paise"),
          rs.getLong("outstanding_float_paise"),
          rs.getLong("variance_paise"),
          rs.getString("variance_reason"),
          rs.getString("reconciliation_status"),
          rs.getBoolean("alert_sent"),
          generated == null ? Instant.EPOCH : generated.toInstant(),
          (UUID) rs.getObject("triggered_by"),
          breakdownJson);
    }
  }
}
