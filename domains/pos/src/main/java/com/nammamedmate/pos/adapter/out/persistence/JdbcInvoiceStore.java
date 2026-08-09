package com.nammamedmate.pos.adapter.out.persistence;

import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceChannel;
import com.nammamedmate.pos.domain.InvoiceItem;
import com.nammamedmate.pos.domain.InvoiceStatus;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvoiceStore implements InvoiceStore {

  private static final RowMapper<Invoice> INVOICE_MAPPER = JdbcInvoiceStore::mapInvoice;
  private static final RowMapper<InvoiceItem> ITEM_MAPPER = JdbcInvoiceStore::mapItem;
  private static final RowMapper<InvoiceListRow> LIST_MAPPER = JdbcInvoiceStore::mapListRow;

  private final JdbcTemplate jdbc;

  public JdbcInvoiceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public InvoiceSettingsRow getOrCreateSettings(UUID pharmacyId) {
    List<String> prefixes =
        jdbc.query(
            "SELECT invoice_prefix FROM invoice_settings WHERE pharmacy_id = ?",
            (rs, i) -> rs.getString(1),
            pharmacyId);
    if (!prefixes.isEmpty()) {
      return new InvoiceSettingsRow(prefixes.getFirst());
    }
    Instant now = Instant.now();
    jdbc.update(
        """
        INSERT INTO invoice_settings (pharmacy_id, updated_at)
        VALUES (?, ?)
        ON CONFLICT (pharmacy_id) DO NOTHING
        """,
        pharmacyId,
        Timestamp.from(now));
    return new InvoiceSettingsRow("INV");
  }

  @Override
  public int nextSequence(UUID pharmacyId, int year, int month) {
    int updated =
        jdbc.update(
            """
            UPDATE invoice_number_sequence
            SET last_seq = last_seq + 1
            WHERE pharmacy_id = ? AND year = ? AND month = ?
            """,
            pharmacyId,
            year,
            month);
    if (updated == 0) {
      jdbc.update(
          """
          INSERT INTO invoice_number_sequence (pharmacy_id, year, month, last_seq)
          VALUES (?, ?, ?, 1)
          ON CONFLICT (pharmacy_id, year, month) DO UPDATE
            SET last_seq = invoice_number_sequence.last_seq + 1
          """,
          pharmacyId,
          year,
          month);
    }
    Integer seq =
        jdbc.queryForObject(
            """
            SELECT last_seq FROM invoice_number_sequence
            WHERE pharmacy_id = ? AND year = ? AND month = ?
            """,
            Integer.class,
            pharmacyId,
            year,
            month);
    return seq == null ? 1 : seq;
  }

  @Override
  public Invoice insert(Invoice invoice) {
    jdbc.update(
        """
        INSERT INTO invoice (
          id, pharmacy_id, invoice_number, cart_id, channel, customer_id, customer_name,
          customer_phone, prescribing_doctor, subtotal_paise, discount_amount_paise,
          gst_total_paise, grand_total_paise, payment_method, payment_status,
          payment_reference, amount_paid_paise, change_due_paise, mrp_savings_paise,
          status, invoice_pdf_url, created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        invoice.id(),
        invoice.pharmacyId(),
        invoice.invoiceNumber(),
        invoice.cartId(),
        invoice.channel().name(),
        invoice.customerId(),
        invoice.customerName(),
        invoice.customerPhone(),
        invoice.prescribingDoctor(),
        invoice.subtotalPaise(),
        invoice.discountAmountPaise(),
        invoice.gstTotalPaise(),
        invoice.grandTotalPaise(),
        invoice.paymentMethod().name(),
        invoice.paymentStatus().name(),
        invoice.paymentReference(),
        invoice.amountPaidPaise(),
        invoice.changeDuePaise(),
        invoice.mrpSavingsPaise(),
        invoice.status().name(),
        invoice.invoicePdfUrl(),
        Timestamp.from(invoice.createdAt()));
    return invoice;
  }

  @Override
  public void insertItems(List<InvoiceItem> items) {
    for (InvoiceItem item : items) {
      jdbc.update(
          """
          INSERT INTO invoice_item (
            id, invoice_id, product_id, product_name, hsn_code, batch_id, batch_number,
            expiry_date, pack_size, quantity, is_loose, unit_price_paise, gst_pct,
            line_subtotal_paise, gst_amount_paise, line_total_paise, is_rx_only, created_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          item.id(),
          item.invoiceId(),
          item.productId(),
          item.productName(),
          item.hsnCode(),
          item.batchId(),
          item.batchNumber(),
          item.expiryDate() == null ? null : Date.valueOf(item.expiryDate()),
          item.packSize(),
          item.quantity(),
          item.isLoose(),
          item.unitPricePaise(),
          item.gstPct(),
          item.lineSubtotalPaise(),
          item.gstAmountPaise(),
          item.lineTotalPaise(),
          item.isRxOnly(),
          Timestamp.from(item.createdAt()));
    }
  }

  @Override
  public Optional<Invoice> findById(UUID pharmacyId, UUID invoiceId) {
    List<Invoice> rows =
        jdbc.query(
            "SELECT * FROM invoice WHERE pharmacy_id = ? AND id = ?",
            INVOICE_MAPPER,
            pharmacyId,
            invoiceId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Invoice> findByIdAny(UUID invoiceId) {
    List<Invoice> rows =
        jdbc.query("SELECT * FROM invoice WHERE id = ?", INVOICE_MAPPER, invoiceId);
    return rows.stream().findFirst();
  }

  @Override
  public List<InvoiceItem> listItems(UUID invoiceId) {
    return jdbc.query(
        "SELECT * FROM invoice_item WHERE invoice_id = ? ORDER BY created_at ASC, id ASC",
        ITEM_MAPPER,
        invoiceId);
  }

  @Override
  public List<InvoiceListRow> list(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String channel,
      String q,
      int limit,
      int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT i.*,
                   (SELECT COUNT(*)::int FROM invoice_item ii WHERE ii.invoice_id = i.id) AS items_count
            FROM invoice i
            WHERE i.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(sql, args, fromDate, toDate, paymentMethod, null, channel, q);
    sql.append(" ORDER BY i.created_at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), LIST_MAPPER, args.toArray());
  }

  @Override
  public long count(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String channel,
      String q) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM invoice i WHERE i.pharmacy_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(sql, args, fromDate, toDate, paymentMethod, null, channel, q);
    Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return total == null ? 0L : total;
  }

  @Override
  public List<InvoiceListRow> listSales(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String paymentStatus,
      String channel,
      String q,
      String sort,
      String order,
      int limit,
      int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT i.*,
                   (SELECT COUNT(*)::int FROM invoice_item ii WHERE ii.invoice_id = i.id) AS items_count
            FROM invoice i
            WHERE i.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(sql, args, fromDate, toDate, paymentMethod, paymentStatus, channel, q);
    sql.append(" ORDER BY ").append(salesOrderBy(sort, order));
    sql.append(" LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), LIST_MAPPER, args.toArray());
  }

  @Override
  public long countSales(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String paymentStatus,
      String channel,
      String q) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM invoice i WHERE i.pharmacy_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(sql, args, fromDate, toDate, paymentMethod, paymentStatus, channel, q);
    Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return total == null ? 0L : total;
  }

  @Override
  public PeriodSummary periodSummary(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String paymentStatus,
      String channel,
      String q) {
    StringBuilder cte =
        new StringBuilder(
            """
            WITH filtered AS (
              SELECT i.id, i.grand_total_paise, i.gst_total_paise, i.payment_status, i.amount_paid_paise
              FROM invoice i
              WHERE i.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(cte, args, fromDate, toDate, paymentMethod, paymentStatus, channel, q);
    cte.append(
        """
            )
            SELECT
              (SELECT COUNT(*)::bigint FROM filtered) AS bill_count,
              (SELECT COALESCE(SUM(grand_total_paise), 0)::bigint FROM filtered) AS gross_revenue_paise,
              (SELECT COALESCE(SUM(gst_total_paise), 0)::bigint FROM filtered) AS gst_collected_paise,
              (SELECT COALESCE(SUM(
                 CASE
                   WHEN payment_status IN ('PENDING', 'PARTIAL')
                     THEN GREATEST(grand_total_paise - amount_paid_paise, 0)
                   ELSE 0
                 END
               ), 0)::bigint FROM filtered) AS credit_outstanding_paise,
              (SELECT COALESCE(SUM(ii.quantity), 0)::bigint
               FROM invoice_item ii
               WHERE ii.invoice_id IN (SELECT id FROM filtered)) AS units_sold
            """);
    return jdbc
        .query(
            cte.toString(),
            (rs, rowNum) ->
                new PeriodSummary(
                    rs.getLong("bill_count"),
                    rs.getLong("units_sold"),
                    rs.getLong("gross_revenue_paise"),
                    rs.getLong("gst_collected_paise"),
                    rs.getLong("credit_outstanding_paise")),
            args.toArray())
        .stream()
        .findFirst()
        .orElse(new PeriodSummary(0, 0, 0, 0, 0));
  }

  @Override
  public List<PaymentModeAgg> paymentModeMix(
      UUID pharmacyId, LocalDate fromDate, LocalDate toDate) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT i.payment_method AS payment_method,
                   COUNT(*)::bigint AS cnt,
                   COALESCE(SUM(i.grand_total_paise), 0)::bigint AS amount_paise
            FROM invoice i
            WHERE i.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(sql, args, fromDate, toDate, null, null, null, null);
    sql.append(" GROUP BY i.payment_method ORDER BY amount_paise DESC");
    return jdbc.query(
        sql.toString(),
        (rs, rowNum) ->
            new PaymentModeAgg(
                rs.getString("payment_method"), rs.getLong("cnt"), rs.getLong("amount_paise")),
        args.toArray());
  }

  @Override
  public List<ChannelAgg> channelRevenue(UUID pharmacyId, LocalDate fromDate, LocalDate toDate) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT i.channel AS channel,
                   COALESCE(SUM(i.grand_total_paise), 0)::bigint AS revenue_paise
            FROM invoice i
            WHERE i.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(sql, args, fromDate, toDate, null, null, null, null);
    sql.append(" GROUP BY i.channel");
    return jdbc.query(
        sql.toString(),
        (rs, rowNum) -> new ChannelAgg(rs.getString("channel"), rs.getLong("revenue_paise")),
        args.toArray());
  }

  @Override
  public List<ProductAgg> topProducts(
      UUID pharmacyId, LocalDate fromDate, LocalDate toDate, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT ii.product_name AS product_name,
                   COALESCE(SUM(ii.line_total_paise), 0)::bigint AS revenue_paise,
                   COALESCE(SUM(ii.quantity), 0)::bigint AS units
            FROM invoice_item ii
            INNER JOIN invoice i ON i.id = ii.invoice_id
            WHERE i.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendFilters(sql, args, fromDate, toDate, null, null, null, null);
    sql.append(" GROUP BY ii.product_name ORDER BY revenue_paise DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, rowNum) ->
            new ProductAgg(
                rs.getString("product_name"), rs.getLong("revenue_paise"), rs.getLong("units")),
        args.toArray());
  }

  @Override
  public void markPaid(
      UUID pharmacyId,
      UUID invoiceId,
      PaymentStatus paymentStatus,
      String paymentReference,
      long amountPaidPaise,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE invoice
        SET payment_status = ?,
            payment_reference = ?,
            amount_paid_paise = ?
        WHERE pharmacy_id = ? AND id = ?
        """,
        paymentStatus.name(),
        paymentReference,
        amountPaidPaise,
        pharmacyId,
        invoiceId);
  }

  private static String salesOrderBy(String sort, String order) {
    String dir = order != null && "asc".equalsIgnoreCase(order.trim()) ? "ASC" : "DESC";
    String col =
        switch (sort == null || sort.isBlank() ? "date" : sort.trim().toLowerCase()) {
          case "amount" -> "i.grand_total_paise";
          case "invoice_number" -> "i.invoice_number";
          default -> "i.created_at";
        };
    return col + " " + dir;
  }

  private static void appendFilters(
      StringBuilder sql,
      List<Object> args,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String paymentStatus,
      String channel,
      String q) {
    if (fromDate != null) {
      sql.append(" AND i.created_at >= ?::timestamptz");
      args.add(fromDate.toString() + "T00:00:00Z");
    }
    if (toDate != null) {
      sql.append(" AND i.created_at < (?::date + INTERVAL '1 day')");
      args.add(toDate.toString());
    }
    if (paymentMethod != null && !paymentMethod.isBlank()) {
      sql.append(" AND i.payment_method = ?");
      args.add(paymentMethod);
    }
    if (paymentStatus != null && !paymentStatus.isBlank()) {
      sql.append(" AND i.payment_status = ?");
      args.add(paymentStatus);
    }
    if (channel != null && !channel.isBlank()) {
      sql.append(" AND i.channel = ?");
      args.add(channel);
    }
    if (q != null && !q.isBlank()) {
      String like = "%" + q.trim() + "%";
      sql.append(
          """
           AND (
            i.invoice_number ILIKE ?
            OR COALESCE(i.customer_name, '') ILIKE ?
            OR COALESCE(i.customer_phone, '') ILIKE ?
          )
          """);
      args.add(like);
      args.add(like);
      args.add(like);
    }
  }

  private static InvoiceListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
    return new InvoiceListRow(mapInvoice(rs, rowNum), rs.getInt("items_count"));
  }

  private static Invoice mapInvoice(ResultSet rs, int rowNum) throws SQLException {
    return new Invoice(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("invoice_number"),
        (UUID) rs.getObject("cart_id"),
        InvoiceChannel.valueOf(rs.getString("channel")),
        (UUID) rs.getObject("customer_id"),
        rs.getString("customer_name"),
        rs.getString("customer_phone"),
        rs.getString("prescribing_doctor"),
        rs.getLong("subtotal_paise"),
        rs.getLong("discount_amount_paise"),
        rs.getLong("gst_total_paise"),
        rs.getLong("grand_total_paise"),
        PaymentMethod.valueOf(rs.getString("payment_method")),
        PaymentStatus.valueOf(rs.getString("payment_status")),
        rs.getString("payment_reference"),
        rs.getLong("amount_paid_paise"),
        rs.getLong("change_due_paise"),
        rs.getLong("mrp_savings_paise"),
        InvoiceStatus.valueOf(rs.getString("status")),
        rs.getString("invoice_pdf_url"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static InvoiceItem mapItem(ResultSet rs, int rowNum) throws SQLException {
    Date expiry = rs.getDate("expiry_date");
    Integer packSize = (Integer) rs.getObject("pack_size");
    return new InvoiceItem(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("invoice_id"),
        (UUID) rs.getObject("product_id"),
        rs.getString("product_name"),
        rs.getString("hsn_code"),
        (UUID) rs.getObject("batch_id"),
        rs.getString("batch_number"),
        expiry == null ? null : expiry.toLocalDate(),
        packSize,
        rs.getInt("quantity"),
        rs.getBoolean("is_loose"),
        rs.getLong("unit_price_paise"),
        rs.getInt("gst_pct"),
        rs.getLong("line_subtotal_paise"),
        rs.getLong("gst_amount_paise"),
        rs.getLong("line_total_paise"),
        rs.getBoolean("is_rx_only"),
        rs.getTimestamp("created_at").toInstant());
  }
}
