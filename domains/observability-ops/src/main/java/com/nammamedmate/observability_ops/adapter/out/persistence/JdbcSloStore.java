package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.SloStore;
import com.nammamedmate.observability_ops.domain.SloComplianceRecord;
import com.nammamedmate.observability_ops.domain.SloDefinition;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSloStore implements SloStore {

  private static final RowMapper<SloDefinition> ROW =
      (rs, i) ->
          new SloDefinition(
              rs.getString("slo_name"),
              rs.getString("description"),
              rs.getBigDecimal("target_pct"),
              rs.getString("metric_name"),
              rs.getInt("measurement_window_days"));

  private static final RowMapper<SloComplianceRecord> HISTORY =
      (rs, i) ->
          new SloComplianceRecord(
              (UUID) rs.getObject("id"),
              rs.getString("slo_name"),
              rs.getDate("period_from").toLocalDate(),
              rs.getDate("period_to").toLocalDate(),
              rs.getBigDecimal("target_pct"),
              rs.getBigDecimal("actual_pct"),
              rs.getBoolean("compliant"),
              rs.getBigDecimal("error_budget_consumed_pct"),
              rs.getInt("incident_count"),
              rs.getTimestamp("recorded_at").toInstant());

  private final JdbcTemplate jdbc;

  public JdbcSloStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<SloDefinition> allDefinitions() {
    return jdbc.query("SELECT * FROM slo_definitions ORDER BY slo_name", ROW);
  }

  @Override
  public Optional<SloDefinition> byMetricName(String metricName) {
    List<SloDefinition> rows =
        jdbc.query("SELECT * FROM slo_definitions WHERE metric_name = ? LIMIT 1", ROW, metricName);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<BigDecimal> previousActualPct(String sloName) {
    List<BigDecimal> rows =
        jdbc.query(
            """
            SELECT actual_pct FROM slo_compliance_history
            WHERE slo_name = ?
            ORDER BY period_to DESC
            LIMIT 1
            """,
            (rs, i) -> rs.getBigDecimal("actual_pct"),
            sloName);
    return rows.stream().findFirst();
  }

  @Override
  public void insertHistory(SloComplianceRecord record) {
    jdbc.update(
        """
        INSERT INTO slo_compliance_history (
          id, slo_name, period_from, period_to, target_pct, actual_pct,
          compliant, error_budget_consumed_pct, recorded_at, incident_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.sloName(),
        Date.valueOf(record.periodFrom()),
        Date.valueOf(record.periodTo()),
        record.targetPct(),
        record.actualPct(),
        record.compliant(),
        record.errorBudgetConsumedPct(),
        Timestamp.from(record.recordedAt()),
        record.incidentCount());
  }

  @Override
  public List<SloComplianceRecord> listHistory(
      String sloName, LocalDate periodFrom, LocalDate periodTo) {
    StringBuilder sql = new StringBuilder("SELECT * FROM slo_compliance_history WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (sloName != null && !sloName.isBlank()) {
      sql.append(" AND slo_name = ?");
      args.add(sloName);
    }
    if (periodFrom != null) {
      sql.append(" AND period_from >= ?");
      args.add(Date.valueOf(periodFrom));
    }
    if (periodTo != null) {
      sql.append(" AND period_to <= ?");
      args.add(Date.valueOf(periodTo));
    }
    sql.append(" ORDER BY period_from DESC, slo_name");
    return jdbc.query(sql.toString(), HISTORY, args.toArray());
  }
}
