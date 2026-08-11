package com.nammamedmate.analytics.adapter.out.persistence;

import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPharmacyAnalyticsStore implements PharmacyAnalyticsStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyAnalyticsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public FinancialTotals financials(UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
            SELECT
              COALESCE(SUM(i.grand_total_paise), 0) AS revenue,
              COALESCE(SUM(line.cogs), 0) AS cogs,
              COALESCE(SUM(line.units), 0) AS units,
              COALESCE(SUM(i.gst_total_paise), 0) AS output_gst,
              COALESCE(SUM(line.missing_cogs), 0) AS missing_cogs
            FROM invoice i
            LEFT JOIN LATERAL (
              SELECT
                COALESCE(SUM(
                  CASE WHEN ii.batch_id IS NOT NULL AND pb.purchase_price_paise IS NOT NULL
                    THEN pb.purchase_price_paise * ii.quantity ELSE 0 END), 0) AS cogs,
                COALESCE(SUM(ii.quantity), 0) AS units,
                COALESCE(SUM(
                  CASE WHEN ii.batch_id IS NULL OR pb.id IS NULL THEN 1 ELSE 0 END), 0) AS missing_cogs
              FROM invoice_item ii
              LEFT JOIN product_batch pb ON pb.id = ii.batch_id
              WHERE ii.invoice_id = i.id
            ) line ON TRUE
            WHERE i.pharmacy_id = ?
              AND i.created_at >= ? AND i.created_at < ?
              AND i.status = 'ACTIVE'
            """,
        rs -> {
          if (!rs.next()) {
            return new FinancialTotals(0, 0, 0, 0, 0, false);
          }
          long revenue = rs.getLong("revenue");
          long cogs = rs.getLong("cogs");
          long units = rs.getLong("units");
          long gst = rs.getLong("output_gst");
          boolean incomplete = rs.getLong("missing_cogs") > 0;
          return new FinancialTotals(revenue, cogs, revenue - cogs, units, gst, incomplete);
        },
        pharmacyId,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<TopItem> topItems(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive, int limit) {
    return jdbc.query(
        """
        SELECT ii.product_id, MAX(ii.product_name) AS name,
               SUM(ii.quantity) AS units, SUM(ii.line_total_paise) AS revenue
        FROM invoice_item ii
        JOIN invoice i ON i.id = ii.invoice_id
        WHERE i.pharmacy_id = ?
          AND i.created_at >= ? AND i.created_at < ?
          AND i.status = 'ACTIVE'
        GROUP BY ii.product_id
        ORDER BY revenue DESC
        LIMIT ?
        """,
        (rs, n) ->
            new TopItem(
                (UUID) rs.getObject("product_id"),
                rs.getString("name"),
                rs.getLong("units"),
                rs.getLong("revenue")),
        pharmacyId,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive),
        limit);
  }

  @Override
  public ChannelTotals channelTotals(UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN channel = 'ONLINE' THEN grand_total_paise ELSE 0 END), 0) AS online,
          COALESCE(SUM(CASE WHEN channel = 'COUNTER' THEN grand_total_paise ELSE 0 END), 0) AS counter
        FROM invoice
        WHERE pharmacy_id = ?
          AND created_at >= ? AND created_at < ?
          AND status = 'ACTIVE'
        """,
        rs -> {
          rs.next();
          return new ChannelTotals(rs.getLong("online"), rs.getLong("counter"));
        },
        pharmacyId,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<PaymentMixRow> paymentMix(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT payment_method AS method, COALESCE(SUM(grand_total_paise), 0) AS revenue
        FROM invoice
        WHERE pharmacy_id = ?
          AND created_at >= ? AND created_at < ?
          AND status = 'ACTIVE'
        GROUP BY payment_method
        ORDER BY revenue DESC
        """,
        (rs, n) -> new PaymentMixRow(rs.getString("method"), rs.getLong("revenue")),
        pharmacyId,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public SaleTotals saleTotals(
      UUID pharmacyId,
      Instant fromInclusive,
      Instant toExclusive,
      String channel,
      String paymentMethod) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COUNT(1) AS cnt,
                   COALESCE(SUM(grand_total_paise), 0) AS revenue,
                   COALESCE(SUM(gst_total_paise), 0) AS gst
            FROM invoice
            WHERE pharmacy_id = ?
              AND created_at >= ? AND created_at < ?
              AND status = 'ACTIVE'
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    args.add(Timestamp.from(fromInclusive));
    args.add(Timestamp.from(toExclusive));
    appendSaleFilters(sql, args, channel, paymentMethod);
    return jdbc.query(
        sql.toString(),
        rs -> {
          rs.next();
          return new SaleTotals(rs.getLong("cnt"), rs.getLong("revenue"), rs.getLong("gst"));
        },
        args.toArray());
  }

  @Override
  public List<SaleRow> sales(
      UUID pharmacyId,
      Instant fromInclusive,
      Instant toExclusive,
      String channel,
      String paymentMethod,
      int offset,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT i.id, i.invoice_number, i.created_at, i.channel, i.customer_name,
                   (SELECT COUNT(1) FROM invoice_item ii WHERE ii.invoice_id = i.id) AS items_count,
                   i.subtotal_paise, i.gst_total_paise, i.grand_total_paise,
                   i.payment_method, i.payment_status
            FROM invoice i
            WHERE i.pharmacy_id = ?
              AND i.created_at >= ? AND i.created_at < ?
              AND i.status = 'ACTIVE'
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    args.add(Timestamp.from(fromInclusive));
    args.add(Timestamp.from(toExclusive));
    appendSaleFilters(sql, args, channel, paymentMethod);
    sql.append(" ORDER BY i.created_at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, n) ->
            new SaleRow(
                (UUID) rs.getObject("id"),
                rs.getString("invoice_number"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("channel"),
                rs.getString("customer_name"),
                rs.getInt("items_count"),
                rs.getLong("subtotal_paise"),
                rs.getLong("gst_total_paise"),
                rs.getLong("grand_total_paise"),
                rs.getString("payment_method"),
                mapSaleStatus(rs.getString("payment_status"))),
        args.toArray());
  }

  @Override
  public long countProducts(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive, boolean deadStockOnly) {
    String dead = deadStockOnly ? " AND COALESCE(pp.dead_stock_flag, FALSE) = TRUE" : "";
    Long count =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM pharmacy_product pp
            WHERE pp.pharmacy_id = ? AND pp.deleted_at IS NULL
            """
                + dead,
            Long.class,
            pharmacyId);
    return count == null ? 0L : count;
  }

  @Override
  public List<ProductRow> products(
      UUID pharmacyId,
      Instant fromInclusive,
      Instant toExclusive,
      String sort,
      String order,
      boolean deadStockOnly,
      int offset,
      int limit) {
    String sortExpr =
        switch (sort) {
          case "units_sold" -> "COALESCE(s.units_sold, 0)";
          case "margin_pct" ->
              """
              CASE WHEN COALESCE(s.revenue_paise, 0) = 0 OR s.cogs_paise IS NULL THEN NULL
                   ELSE (COALESCE(s.revenue_paise, 0) - s.cogs_paise)::float
                        / NULLIF(s.revenue_paise, 0) END
              """;
          case "profit" -> "COALESCE(s.revenue_paise, 0) - COALESCE(s.cogs_paise, 0)";
          default -> "COALESCE(s.revenue_paise, 0)";
        };
    String ord = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
    String dead = deadStockOnly ? " AND COALESCE(pp.dead_stock_flag, FALSE) = TRUE" : "";
    String sql =
        """
        SELECT pp.id AS product_id, pp.name, pp.schedule AS category,
               COALESCE(s.units_sold, 0) AS units_sold,
               COALESCE(s.revenue_paise, 0) AS revenue_paise,
               s.cogs_paise,
               s.missing_cogs,
               pp.total_stock_units AS stock_remaining,
               COALESCE(pp.dead_stock_flag, FALSE) AS dead_stock_flag
        FROM pharmacy_product pp
        LEFT JOIN LATERAL (
          SELECT
            SUM(ii.quantity) AS units_sold,
            SUM(ii.line_total_paise) AS revenue_paise,
            SUM(CASE WHEN ii.batch_id IS NOT NULL AND pb.purchase_price_paise IS NOT NULL
                THEN pb.purchase_price_paise * ii.quantity ELSE NULL END) AS cogs_paise,
            SUM(CASE WHEN ii.batch_id IS NULL OR pb.id IS NULL THEN 1 ELSE 0 END) AS missing_cogs
          FROM invoice_item ii
          JOIN invoice i ON i.id = ii.invoice_id
          LEFT JOIN product_batch pb ON pb.id = ii.batch_id
          WHERE i.pharmacy_id = pp.pharmacy_id
            AND ii.product_id = pp.id
            AND i.created_at >= ? AND i.created_at < ?
            AND i.status = 'ACTIVE'
        ) s ON TRUE
        WHERE pp.pharmacy_id = ? AND pp.deleted_at IS NULL
        """
            + dead
            + " ORDER BY "
            + sortExpr
            + " "
            + ord
            + " NULLS LAST LIMIT ? OFFSET ?";
    return jdbc.query(
        sql,
        (rs, n) -> {
          long revenue = rs.getLong("revenue_paise");
          Long cogsObj = (Long) rs.getObject("cogs_paise");
          long missing = rs.getLong("missing_cogs");
          boolean cogsMissing = cogsObj == null || missing > 0;
          long cogs = cogsObj == null ? 0L : cogsObj.longValue();
          long profit = 0L;
          BigDecimal margin = null;
          if (!cogsMissing) {
            profit = revenue - cogs;
            margin = AnalyticsMath.netMarginPct(revenue, cogs);
          }
          return new ProductRow(
              (UUID) rs.getObject("product_id"),
              rs.getString("name"),
              mapCategory(rs.getString("category")),
              rs.getLong("units_sold"),
              revenue,
              cogs,
              profit,
              margin,
              rs.getInt("stock_remaining"),
              rs.getBoolean("dead_stock_flag"),
              cogsMissing && revenue > 0);
        },
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive),
        pharmacyId,
        limit,
        offset);
  }

  @Override
  public AccountsData accounts(UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    FinancialTotals fin = financials(pharmacyId, fromInclusive, toExclusive);
    long cash =
        nullableLong(
            jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(grand_total_paise), 0) FROM invoice
                WHERE pharmacy_id = ? AND created_at >= ? AND created_at < ?
                  AND status = 'ACTIVE' AND payment_method = 'CASH'
                """,
                Long.class,
                pharmacyId,
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive)));
    long digital =
        nullableLong(
            jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(grand_total_paise), 0) FROM invoice
                WHERE pharmacy_id = ? AND created_at >= ? AND created_at < ?
                  AND status = 'ACTIVE' AND payment_method <> 'CASH'
                """,
                Long.class,
                pharmacyId,
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive)));

    long purchases =
        nullableLong(
            jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(gi.line_total_paise), 0)
                FROM purchase_grn_item gi
                JOIN purchase_grn g ON g.id = gi.grn_id
                WHERE g.pharmacy_id = ?
                  AND g.status = 'STOCKED'
                  AND g.invoice_date >= ? AND g.invoice_date <= ?
                """,
                Long.class,
                pharmacyId,
                Date.valueOf(fromInclusive.atZone(AnalyticsMathZone()).toLocalDate()),
                Date.valueOf(toExclusive.atZone(AnalyticsMathZone()).toLocalDate().minusDays(1))));
    long purchaseGst =
        nullableLong(
            jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(gi.gst_amount_paise), 0)
                FROM purchase_grn_item gi
                JOIN purchase_grn g ON g.id = gi.grn_id
                WHERE g.pharmacy_id = ?
                  AND g.status = 'STOCKED'
                  AND g.invoice_date >= ? AND g.invoice_date <= ?
                """,
                Long.class,
                pharmacyId,
                Date.valueOf(fromInclusive.atZone(AnalyticsMathZone()).toLocalDate()),
                Date.valueOf(toExclusive.atZone(AnalyticsMathZone()).toLocalDate().minusDays(1))));

    List<GstSlab> slabs = ensureDefaultSlabs(loadGstSlabs(pharmacyId, fromInclusive, toExclusive));
    long outputGst = slabs.stream().mapToLong(GstSlab::outputGstPaise).sum();
    long inputItc = slabs.stream().mapToLong(GstSlab::inputItcPaise).sum();
    // Prefer slab sums; fall back to invoice/purchase totals when slabs are empty zeros.
    outputGst = outputGst == 0L ? fin.netGstPaise() : outputGst;
    inputItc = inputItc == 0L ? purchaseGst : inputItc;

    List<DayBookRow> dayBook = loadDayBook(pharmacyId, fromInclusive, toExclusive);
    return new AccountsData(
        fin.netRevenuePaise(),
        fin.cogsPaise(),
        fin.grossProfitPaise(),
        0L,
        outputGst,
        inputItc,
        cash,
        digital,
        purchases,
        purchaseGst,
        fin.cogsIncomplete(),
        slabs,
        dayBook);
  }

  @Override
  public Set<String> favoriteReportIds(UUID pharmacyId) {
    List<String> ids =
        jdbc.query(
            "SELECT report_id FROM pharmacy_report_favorites WHERE pharmacy_id = ?",
            (rs, n) -> rs.getString("report_id"),
            pharmacyId);
    return new HashSet<>(ids);
  }

  @Override
  @Transactional
  public void setFavorite(UUID pharmacyId, String reportId, boolean favorite) {
    if (favorite) {
      jdbc.update(
          """
          INSERT INTO pharmacy_report_favorites (id, pharmacy_id, report_id, created_at)
          VALUES (?, ?, ?, NOW())
          ON CONFLICT (pharmacy_id, report_id) DO NOTHING
          """,
          Ids.newId(),
          pharmacyId,
          reportId);
    } else {
      jdbc.update(
          "DELETE FROM pharmacy_report_favorites WHERE pharmacy_id = ? AND report_id = ?",
          pharmacyId,
          reportId);
    }
  }

  @Override
  public List<List<Object>> reportRows(
      UUID pharmacyId, String reportId, Instant fromInclusive, Instant toExclusive) {
    return switch (reportId) {
      case "GSTR-1-DRAFT" -> gstr1Rows(pharmacyId, fromInclusive, toExclusive);
      case "SALES-REGISTER" -> salesReportRows(pharmacyId, fromInclusive, toExclusive);
      case "DEAD-STOCK" -> deadStockRows(pharmacyId);
      case "DAYBOOK" -> {
        AccountsData a = accounts(pharmacyId, fromInclusive, toExclusive);
        long bal = 0;
        List<List<Object>> out = new ArrayList<>();
        for (DayBookRow r : a.dayBook()) {
          bal = bal + r.creditPaise() - r.debitPaise();
          out.add(
              List.of(
                  r.date().toString(),
                  r.type(),
                  r.reference(),
                  r.debitPaise(),
                  r.creditPaise(),
                  bal));
        }
        yield out;
      }
      case "PL-STATEMENT" -> {
        AccountsData a = accounts(pharmacyId, fromInclusive, toExclusive);
        yield List.of(
            List.of("revenue", a.revenuePaise()),
            List.of("cogs", a.cogsPaise()),
            List.of("gross_profit", a.grossProfitPaise()),
            List.of("operating_expenses", a.operatingExpensesPaise()),
            List.of("net_gst_payable", a.outputGstPaise() - a.inputItcPaise()),
            List.of(
                "net_profit",
                a.grossProfitPaise()
                    - a.operatingExpensesPaise()
                    - (a.outputGstPaise() - a.inputItcPaise())));
      }
      case "GSTR-3B-DRAFT" -> {
        List<List<Object>> out = new ArrayList<>();
        for (GstSlab s : accounts(pharmacyId, fromInclusive, toExclusive).slabs()) {
          out.add(
              List.of(
                  s.slabPct(),
                  s.taxableValuePaise(),
                  s.outputGstPaise(),
                  s.inputItcPaise(),
                  s.netPaise()));
        }
        yield out;
      }
      case "PURCHASE-REG" -> purchaseRegRows(pharmacyId, fromInclusive, toExclusive);
      case "STOCK-SUMMARY" -> stockSummaryRows(pharmacyId);
      case "PARTY-LEDGER" -> List.of();
      default -> List.of();
    };
  }

  @Override
  @Transactional
  public void refreshDailySnapshots(LocalDate fromInclusive, LocalDate toInclusive) {
    LocalDate d = fromInclusive;
    while (!d.isAfter(toInclusive)) {
      Instant from = d.atStartOfDay(AnalyticsMathZone()).toInstant();
      Instant to = d.plusDays(1).atStartOfDay(AnalyticsMathZone()).toInstant();
      for (String channel : List.of("ONLINE", "COUNTER")) {
        upsertChannelSnapshots(d, channel, from, to);
      }
      d = d.plusDays(1);
    }
  }

  private void upsertChannelSnapshots(LocalDate day, String channel, Instant from, Instant to) {
    jdbc.query(
        """
        SELECT i.pharmacy_id,
          COALESCE(SUM(i.grand_total_paise), 0) AS revenue,
          COALESCE(SUM(line.cogs), 0) AS cogs,
          COALESCE(SUM(line.units), 0) AS units,
          COALESCE(SUM(i.gst_total_paise), 0) AS output_gst,
          COUNT(i.id) AS orders_count
        FROM invoice i
        LEFT JOIN LATERAL (
          SELECT
            COALESCE(SUM(
              CASE WHEN ii.batch_id IS NOT NULL AND pb.purchase_price_paise IS NOT NULL
                THEN pb.purchase_price_paise * ii.quantity ELSE 0 END), 0) AS cogs,
            COALESCE(SUM(ii.quantity), 0) AS units
          FROM invoice_item ii
          LEFT JOIN product_batch pb ON pb.id = ii.batch_id
          WHERE ii.invoice_id = i.id
        ) line ON TRUE
        WHERE i.created_at >= ? AND i.created_at < ?
          AND i.status = 'ACTIVE' AND i.channel = ?
        GROUP BY i.pharmacy_id
        """,
        rs -> {
          while (rs.next()) {
            UUID pharmacyId = (UUID) rs.getObject("pharmacy_id");
            long revenue = rs.getLong("revenue");
            long cogs = rs.getLong("cogs");
            long inputItc =
                nullableLong(
                    jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(gi.gst_amount_paise), 0)
                        FROM purchase_grn_item gi
                        JOIN purchase_grn g ON g.id = gi.grn_id
                        WHERE g.pharmacy_id = ? AND g.status = 'STOCKED' AND g.invoice_date = ?
                        """,
                        Long.class,
                        pharmacyId,
                        Date.valueOf(day)));
            jdbc.update(
                """
                INSERT INTO pharmacy_analytics_daily (
                  id, pharmacy_id, snapshot_date, channel, revenue_paise, cogs_paise,
                  gross_profit_paise, units_sold, output_gst_paise, input_itc_paise, orders_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (pharmacy_id, snapshot_date, channel) DO UPDATE SET
                  revenue_paise = EXCLUDED.revenue_paise,
                  cogs_paise = EXCLUDED.cogs_paise,
                  gross_profit_paise = EXCLUDED.gross_profit_paise,
                  units_sold = EXCLUDED.units_sold,
                  output_gst_paise = EXCLUDED.output_gst_paise,
                  input_itc_paise = EXCLUDED.input_itc_paise,
                  orders_count = EXCLUDED.orders_count
                """,
                Ids.newId(),
                pharmacyId,
                Date.valueOf(day),
                channel,
                revenue,
                cogs,
                revenue - cogs,
                (int) rs.getLong("units"),
                rs.getLong("output_gst"),
                inputItc,
                (int) rs.getLong("orders_count"));
          }
          return null;
        },
        Timestamp.from(from),
        Timestamp.from(to),
        channel);
  }

  @Override
  @Transactional
  public void refreshDeadStockFlags(LocalDate asOfDate) {
    LocalDate since = asOfDate.minusDays(90);
    Instant from = since.atStartOfDay(AnalyticsMathZone()).toInstant();
    Instant to = asOfDate.plusDays(1).atStartOfDay(AnalyticsMathZone()).toInstant();
    jdbc.update("UPDATE pharmacy_product SET dead_stock_flag = FALSE WHERE deleted_at IS NULL");
    jdbc.update(
        """
        UPDATE pharmacy_product pp
        SET dead_stock_flag = TRUE
        WHERE pp.deleted_at IS NULL
          AND pp.total_stock_units > 0
          AND NOT EXISTS (
            SELECT 1 FROM invoice_item ii
            JOIN invoice i ON i.id = ii.invoice_id
            WHERE ii.product_id = pp.id
              AND i.pharmacy_id = pp.pharmacy_id
              AND i.status = 'ACTIVE'
              AND i.created_at >= ? AND i.created_at < ?
          )
        """,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  private List<GstSlab> loadGstSlabs(UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    List<GstSlab> slabs =
        jdbc.query(
            """
            SELECT ii.gst_pct AS slab,
                   COALESCE(SUM(ii.line_subtotal_paise), 0) AS taxable,
                   COALESCE(SUM(ii.gst_amount_paise), 0) AS output_gst
            FROM invoice_item ii
            JOIN invoice i ON i.id = ii.invoice_id
            WHERE i.pharmacy_id = ?
              AND i.created_at >= ? AND i.created_at < ?
              AND i.status = 'ACTIVE'
              AND ii.gst_pct IN (5, 12, 18)
            GROUP BY ii.gst_pct
            ORDER BY ii.gst_pct
            """,
            (rs, n) -> {
              int slab = rs.getInt("slab");
              long taxable = rs.getLong("taxable");
              long output = rs.getLong("output_gst");
              long input =
                  nullableLong(
                      jdbc.queryForObject(
                          """
                          SELECT COALESCE(SUM(gi.gst_amount_paise), 0)
                          FROM purchase_grn_item gi
                          JOIN purchase_grn g ON g.id = gi.grn_id
                          WHERE g.pharmacy_id = ?
                            AND g.status = 'STOCKED'
                            AND g.invoice_date >= ? AND g.invoice_date <= ?
                            AND gi.gst_pct = ?
                          """,
                          Long.class,
                          pharmacyId,
                          Date.valueOf(fromInclusive.atZone(AnalyticsMathZone()).toLocalDate()),
                          Date.valueOf(
                              toExclusive.atZone(AnalyticsMathZone()).toLocalDate().minusDays(1)),
                          slab));
              return new GstSlab(slab, taxable, output, input, output - input);
            },
            pharmacyId,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return slabs;
  }

  private List<GstSlab> ensureDefaultSlabs(List<GstSlab> slabs) {
    int[] wanted = {5, 12, 18};
    List<GstSlab> out = new ArrayList<>();
    for (int s : wanted) {
      GstSlab found = slabs.stream().filter(x -> x.slabPct() == s).findFirst().orElse(null);
      out.add(found != null ? found : new GstSlab(s, 0, 0, 0, 0));
    }
    return out;
  }

  private List<DayBookRow> loadDayBook(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    List<DayBookRow> rows = new ArrayList<>();
    rows.addAll(
        jdbc.query(
            """
            SELECT created_at::date AS d, invoice_number, customer_name, grand_total_paise, channel
            FROM invoice
            WHERE pharmacy_id = ?
              AND created_at >= ? AND created_at < ?
              AND status = 'ACTIVE'
            ORDER BY created_at ASC
            """,
            (rs, n) -> {
              String channel = rs.getString("channel");
              String customer = rs.getString("customer_name");
              String prefix = "ONLINE".equals(channel) ? "Online" : "Counter";
              String desc = customer == null ? prefix : prefix + " — " + customer;
              return new DayBookRow(
                  rs.getDate("d").toLocalDate(),
                  "SALE",
                  rs.getString("invoice_number"),
                  desc,
                  0L,
                  rs.getLong("grand_total_paise"));
            },
            pharmacyId,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive)));
    rows.addAll(
        jdbc.query(
            """
            SELECT g.invoice_date AS d, g.invoice_number,
                   COALESCE(SUM(gi.line_total_paise), 0) AS total
            FROM purchase_grn g
            JOIN purchase_grn_item gi ON gi.grn_id = g.id
            WHERE g.pharmacy_id = ?
              AND g.status = 'STOCKED'
              AND g.invoice_date >= ? AND g.invoice_date <= ?
            GROUP BY g.id, g.invoice_date, g.invoice_number
            ORDER BY g.invoice_date ASC
            """,
            (rs, n) ->
                new DayBookRow(
                    rs.getDate("d").toLocalDate(),
                    "PURCHASE",
                    rs.getString("invoice_number"),
                    "Stock purchase",
                    rs.getLong("total"),
                    0L),
            pharmacyId,
            Date.valueOf(fromInclusive.atZone(AnalyticsMathZone()).toLocalDate()),
            Date.valueOf(toExclusive.atZone(AnalyticsMathZone()).toLocalDate().minusDays(1))));
    rows.sort(
        (a, b) -> {
          int c = a.date().compareTo(b.date());
          if (c != 0) {
            return c;
          }
          return a.type().compareTo(b.type());
        });
    return rows;
  }

  private List<List<Object>> gstr1Rows(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT invoice_number, grand_total_paise - gst_total_paise AS taxable,
               gst_total_paise / 2 AS half_gst, grand_total_paise
        FROM invoice
        WHERE pharmacy_id = ?
          AND created_at >= ? AND created_at < ?
          AND status = 'ACTIVE'
        ORDER BY created_at
        """,
        (rs, n) -> {
          long taxable = rs.getLong("taxable");
          long half = rs.getLong("half_gst");
          long total = rs.getLong("grand_total_paise");
          return List.<Object>of(
              rs.getString("invoice_number"), "", taxable, half, half, 0L, total);
        },
        pharmacyId,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  private List<List<Object>> salesReportRows(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT invoice_number, created_at, channel, grand_total_paise, payment_method
        FROM invoice
        WHERE pharmacy_id = ?
          AND created_at >= ? AND created_at < ?
          AND status = 'ACTIVE'
        ORDER BY created_at
        """,
        (rs, n) ->
            List.of(
                rs.getString("invoice_number"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("channel"),
                rs.getLong("grand_total_paise"),
                rs.getString("payment_method")),
        pharmacyId,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  private List<List<Object>> deadStockRows(UUID pharmacyId) {
    return jdbc.query(
        """
        SELECT id, name, total_stock_units, dead_stock_flag
        FROM pharmacy_product
        WHERE pharmacy_id = ? AND deleted_at IS NULL AND dead_stock_flag = TRUE
        ORDER BY name
        """,
        (rs, n) ->
            List.of(
                rs.getObject("id").toString(),
                rs.getString("name"),
                rs.getInt("total_stock_units"),
                rs.getBoolean("dead_stock_flag")),
        pharmacyId);
  }

  private List<List<Object>> purchaseRegRows(
      UUID pharmacyId, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT g.invoice_number, g.invoice_date,
               COALESCE(SUM(gi.line_total_paise), 0) AS total,
               COALESCE(SUM(gi.gst_amount_paise), 0) AS gst
        FROM purchase_grn g
        JOIN purchase_grn_item gi ON gi.grn_id = g.id
        WHERE g.pharmacy_id = ?
          AND g.status = 'STOCKED'
          AND g.invoice_date >= ? AND g.invoice_date <= ?
        GROUP BY g.id, g.invoice_number, g.invoice_date
        ORDER BY g.invoice_date
        """,
        (rs, n) ->
            List.of(
                rs.getString("invoice_number"),
                rs.getDate("invoice_date").toLocalDate().toString(),
                "",
                rs.getLong("total"),
                rs.getLong("gst")),
        pharmacyId,
        Date.valueOf(fromInclusive.atZone(AnalyticsMathZone()).toLocalDate()),
        Date.valueOf(toExclusive.atZone(AnalyticsMathZone()).toLocalDate().minusDays(1)));
  }

  private List<List<Object>> stockSummaryRows(UUID pharmacyId) {
    return jdbc.query(
        """
        SELECT id, name, total_stock_units, cost_value_paise
        FROM pharmacy_product
        WHERE pharmacy_id = ? AND deleted_at IS NULL
        ORDER BY name
        """,
        (rs, n) ->
            List.of(
                rs.getObject("id").toString(),
                rs.getString("name"),
                rs.getInt("total_stock_units"),
                rs.getLong("cost_value_paise")),
        pharmacyId);
  }

  private static void appendSaleFilters(
      StringBuilder sql, List<Object> args, String channel, String paymentMethod) {
    if (channel != null) {
      sql.append(" AND channel = ?");
      args.add(channel.toUpperCase(Locale.ROOT));
    }
    if (paymentMethod != null) {
      sql.append(" AND payment_method = ?");
      args.add(paymentMethod.toUpperCase(Locale.ROOT));
    }
  }

  private static String mapSaleStatus(String paymentStatus) {
    if ("PAID".equals(paymentStatus)) {
      return "DELIVERED";
    }
    if (paymentStatus == null) {
      return "ACTIVE";
    }
    return paymentStatus;
  }

  private static String mapCategory(String schedule) {
    if (schedule == null) {
      return "OTC";
    }
    if ("OTC".equals(schedule)) {
      return "OTC";
    }
    return "PRESCRIPTION";
  }

  private static long nullableLong(Long v) {
    if (v == null) {
      return 0L;
    }
    return v;
  }

  private static java.time.ZoneId AnalyticsMathZone() {
    return com.nammamedmate.analytics.domain.PeriodResolver.IST;
  }
}
