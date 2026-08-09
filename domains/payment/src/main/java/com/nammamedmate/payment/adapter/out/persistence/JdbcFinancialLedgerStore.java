package com.nammamedmate.payment.adapter.out.persistence;

import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinancialLedgerStore implements FinancialLedgerQueryPort {

  private final JdbcTemplate jdbc;

  public JdbcFinancialLedgerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public LedgerPage list(
      String[] entryTypes,
      Instant fromInclusive,
      Instant toExclusive,
      int page,
      int limit,
      boolean ascending) {
    FilterSql filter = buildFilter(entryTypes, fromInclusive, toExclusive);
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(1) FROM financial_ledger fl WHERE " + filter.whereSql(),
            Long.class,
            filter.args.toArray());
    long totalCount = total == null ? 0L : total;
    String order = ascending ? "ASC" : "DESC";
    int offset = Math.max(0, (page - 1) * limit);
    List<Object> args = new ArrayList<>(filter.args);
    args.add(limit);
    args.add(offset);
    String sql =
        """
        WITH ranked AS (
          SELECT
            fl.id,
            fl.entry_type,
            fl.reference_id,
            fl.reference_type,
            fl.credit_paise,
            fl.debit_paise,
            fl.description,
            fl.created_at,
            SUM(fl.credit_paise - fl.debit_paise) OVER (
              ORDER BY fl.created_at ASC, fl.id ASC
              ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
            ) AS running_balance_paise
          FROM financial_ledger fl
        )
        SELECT * FROM ranked
        WHERE __WHERE__
        ORDER BY created_at __ORDER__, id __ORDER__
        LIMIT ? OFFSET ?
        """
            .replace("__WHERE__", filter.whereSql().replace("fl.", ""))
            .replace("__ORDER__", order);
    List<LedgerRow> rows = jdbc.query(sql, (rs, i) -> mapRow(rs), args.toArray());
    return new LedgerPage(rows, totalCount);
  }

  @Override
  public List<LedgerRow> listAllForExport(
      String[] entryTypes, Instant fromInclusive, Instant toExclusive) {
    FilterSql filter = buildFilter(entryTypes, fromInclusive, toExclusive);
    String sql =
        """
        WITH ranked AS (
          SELECT
            fl.id,
            fl.entry_type,
            fl.reference_id,
            fl.reference_type,
            fl.credit_paise,
            fl.debit_paise,
            fl.description,
            fl.created_at,
            SUM(fl.credit_paise - fl.debit_paise) OVER (
              ORDER BY fl.created_at ASC, fl.id ASC
              ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
            ) AS running_balance_paise
          FROM financial_ledger fl
        )
        SELECT * FROM ranked
        WHERE __WHERE__
        ORDER BY created_at ASC, id ASC
        """
            .replace("__WHERE__", filter.whereSql().replace("fl.", ""));
    return jdbc.query(sql, (rs, i) -> mapRow(rs), filter.args.toArray());
  }

  @Override
  public DayKpis dayKpis(Instant dayStartInclusive, Instant dayEndExclusive) {
    List<DayKpis> rows =
        jdbc.query(
            """
            SELECT
              COALESCE(SUM(CASE WHEN entry_type = 'ORDER_GMV' THEN credit_paise ELSE 0 END), 0) AS gmv,
              COALESCE(SUM(CASE WHEN entry_type = 'COMMISSION' THEN credit_paise ELSE 0 END), 0)
                AS commission,
              COALESCE(SUM(CASE WHEN entry_type = 'GATEWAY_FEE' THEN debit_paise ELSE 0 END), 0)
                AS gateway_fee
            FROM financial_ledger
            WHERE created_at >= ? AND created_at < ?
            """,
            (rs, i) ->
                new DayKpis(rs.getLong("gmv"), rs.getLong("commission"), rs.getLong("gateway_fee")),
            Timestamp.from(dayStartInclusive),
            Timestamp.from(dayEndExclusive));
    return rows.isEmpty() ? new DayKpis(0, 0, 0) : rows.getFirst();
  }

  private static LedgerRow mapRow(ResultSet rs) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    return new LedgerRow(
        (UUID) rs.getObject("id"),
        rs.getString("entry_type"),
        (UUID) rs.getObject("reference_id"),
        rs.getString("reference_type"),
        rs.getLong("credit_paise"),
        rs.getLong("debit_paise"),
        rs.getLong("running_balance_paise"),
        rs.getString("description"),
        created == null ? null : created.toInstant());
  }

  private static FilterSql buildFilter(
      String[] entryTypes, Instant fromInclusive, Instant toExclusive) {
    StringBuilder where = new StringBuilder("1=1");
    List<Object> args = new ArrayList<>();
    if (entryTypes != null && entryTypes.length > 0) {
      where.append(" AND fl.entry_type IN (");
      for (int i = 0; i < entryTypes.length; i++) {
        if (i > 0) {
          where.append(',');
        }
        where.append('?');
        args.add(entryTypes[i]);
      }
      where.append(')');
    }
    if (fromInclusive != null) {
      where.append(" AND fl.created_at >= ?");
      args.add(Timestamp.from(fromInclusive));
    }
    if (toExclusive != null) {
      where.append(" AND fl.created_at < ?");
      args.add(Timestamp.from(toExclusive));
    }
    return new FilterSql(where.toString(), args);
  }

  private record FilterSql(String whereSql, List<Object> args) {}
}
