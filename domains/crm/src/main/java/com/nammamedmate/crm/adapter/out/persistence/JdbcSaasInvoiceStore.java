package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasInvoiceStore;
import com.nammamedmate.crm.domain.SaasInvoice;
import com.nammamedmate.crm.domain.SaasInvoiceLineItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasInvoiceStore implements SaasInvoiceStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasInvoiceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<SaasInvoice> MAPPER = (rs, i) -> mapInvoice(rs);

  private static SaasInvoice mapInvoice(ResultSet rs) throws SQLException {
    return new SaasInvoice(
        (UUID) rs.getObject("id"),
        rs.getString("invoice_number"),
        (UUID) rs.getObject("account_id"),
        (UUID) rs.getObject("subscription_id"),
        rs.getString("plan_name"),
        rs.getDate("billing_period_from").toLocalDate(),
        rs.getDate("billing_period_to").toLocalDate(),
        rs.getLong("subtotal_paise"),
        rs.getBigDecimal("gst_rate_pct"),
        rs.getLong("gst_amount_paise"),
        rs.getLong("total_amount_paise"),
        rs.getString("status"),
        rs.getDate("due_at").toLocalDate(),
        ts(rs, "paid_at"),
        rs.getString("payment_mode"),
        rs.getString("reference_number"),
        (UUID) rs.getObject("marked_paid_by"),
        rs.getInt("dunning_step"),
        rs.getString("waive_reason"),
        rs.getString("pdf_object_key"),
        rs.getString("checkout_url"),
        ts(rs, "checkout_expires_at"),
        rs.getString("mark_paid_idempotency_key"),
        rs.getString("pay_idempotency_key"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"));
  }

  private static final String SELECT =
      """
      SELECT id, invoice_number, account_id, subscription_id, plan_name,
             billing_period_from, billing_period_to, subtotal_paise, gst_rate_pct,
             gst_amount_paise, total_amount_paise, status, due_at, paid_at, payment_mode,
             reference_number, marked_paid_by, dunning_step, waive_reason, pdf_object_key,
             checkout_url, checkout_expires_at, mark_paid_idempotency_key, pay_idempotency_key,
             created_at, updated_at
      FROM saas_invoice
      """;

  @Override
  public void insert(SaasInvoice invoice, List<SaasInvoiceLineItem> lines) {
    jdbc.update(
        """
        INSERT INTO saas_invoice (
          id, invoice_number, account_id, subscription_id, plan_name,
          billing_period_from, billing_period_to, subtotal_paise, gst_rate_pct,
          gst_amount_paise, total_amount_paise, status, due_at, paid_at, payment_mode,
          reference_number, marked_paid_by, dunning_step, waive_reason, pdf_object_key,
          checkout_url, checkout_expires_at, mark_paid_idempotency_key, pay_idempotency_key,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        invoice.id(),
        invoice.invoiceNumber(),
        invoice.accountId(),
        invoice.subscriptionId(),
        invoice.planName(),
        Date.valueOf(invoice.billingPeriodFrom()),
        Date.valueOf(invoice.billingPeriodTo()),
        invoice.subtotalPaise(),
        invoice.gstRatePct(),
        invoice.gstAmountPaise(),
        invoice.totalAmountPaise(),
        invoice.status(),
        Date.valueOf(invoice.dueAt()),
        ts(invoice.paidAt()),
        invoice.paymentMode(),
        invoice.referenceNumber(),
        invoice.markedPaidBy(),
        invoice.dunningStep(),
        invoice.waiveReason(),
        invoice.pdfObjectKey(),
        invoice.checkoutUrl(),
        ts(invoice.checkoutExpiresAt()),
        invoice.markPaidIdempotencyKey(),
        invoice.payIdempotencyKey(),
        ts(invoice.createdAt()),
        ts(invoice.updatedAt()));
    for (SaasInvoiceLineItem line : lines) {
      jdbc.update(
          """
          INSERT INTO saas_invoice_line_item (
            id, invoice_id, description, sac_code, amount_paise, item_type, created_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?)
          """,
          line.id(),
          line.invoiceId(),
          line.description(),
          line.sacCode(),
          line.amountPaise(),
          line.itemType(),
          ts(line.createdAt()));
    }
  }

  @Override
  public void update(SaasInvoice invoice) {
    jdbc.update(
        """
        UPDATE saas_invoice SET
          status = ?, paid_at = ?, payment_mode = ?, reference_number = ?,
          marked_paid_by = ?, dunning_step = ?, waive_reason = ?, pdf_object_key = ?,
          checkout_url = ?, checkout_expires_at = ?,
          mark_paid_idempotency_key = ?, pay_idempotency_key = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        invoice.status(),
        ts(invoice.paidAt()),
        invoice.paymentMode(),
        invoice.referenceNumber(),
        invoice.markedPaidBy(),
        invoice.dunningStep(),
        invoice.waiveReason(),
        invoice.pdfObjectKey(),
        invoice.checkoutUrl(),
        ts(invoice.checkoutExpiresAt()),
        invoice.markPaidIdempotencyKey(),
        invoice.payIdempotencyKey(),
        ts(invoice.updatedAt()),
        invoice.id());
  }

  @Override
  public Optional<SaasInvoice> findById(UUID id) {
    List<SaasInvoice> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", MAPPER, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<SaasInvoice> findByMarkPaidIdempotencyKey(String key) {
    List<SaasInvoice> rows =
        jdbc.query(
            SELECT + " WHERE mark_paid_idempotency_key = ? AND deleted_at IS NULL", MAPPER, key);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<SaasInvoice> findByPayIdempotencyKey(String key) {
    List<SaasInvoice> rows =
        jdbc.query(SELECT + " WHERE pay_idempotency_key = ? AND deleted_at IS NULL", MAPPER, key);
    return rows.stream().findFirst();
  }

  @Override
  public List<SaasInvoiceLineItem> listLineItems(UUID invoiceId) {
    return jdbc.query(
        """
        SELECT id, invoice_id, description, sac_code, amount_paise, item_type, created_at
        FROM saas_invoice_line_item WHERE invoice_id = ? ORDER BY created_at ASC, id ASC
        """,
        (rs, i) ->
            new SaasInvoiceLineItem(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("invoice_id"),
                rs.getString("description"),
                rs.getString("sac_code"),
                rs.getLong("amount_paise"),
                rs.getString("item_type"),
                ts(rs, "created_at")),
        invoiceId);
  }

  @Override
  public List<SaasInvoice> listAdmin(AdminListFilter filter) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, filter);
    sql.append(" ORDER BY created_at DESC OFFSET ? LIMIT ?");
    args.add(filter.offset());
    args.add(filter.limit());
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
  }

  @Override
  public long countAdmin(AdminListFilter filter) {
    StringBuilder sql =
        new StringBuilder("SELECT COUNT(*) FROM saas_invoice WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, filter);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  private static void appendFilters(StringBuilder sql, List<Object> args, AdminListFilter filter) {
    if (filter.status() != null && !filter.status().isBlank()) {
      sql.append(" AND status = ?");
      args.add(filter.status());
    }
    if (filter.plan() != null && !filter.plan().isBlank()) {
      sql.append(" AND plan_name = ?");
      args.add(filter.plan());
    }
    if (filter.accountId() != null) {
      sql.append(" AND account_id = ?");
      args.add(filter.accountId());
    }
    if (filter.from() != null) {
      sql.append(" AND billing_period_from >= ?");
      args.add(Date.valueOf(filter.from()));
    }
    if (filter.to() != null) {
      sql.append(" AND billing_period_to <= ?");
      args.add(Date.valueOf(filter.to()));
    }
  }

  @Override
  public BillingChips chips(LocalDate from, LocalDate to) {
    StringBuilder where = new StringBuilder(" WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    if (from != null) {
      where.append(" AND billing_period_from >= ?");
      args.add(Date.valueOf(from));
    }
    if (to != null) {
      where.append(" AND billing_period_to <= ?");
      args.add(Date.valueOf(to));
    }
    Long collected =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(total_amount_paise),0) FROM saas_invoice"
                + where
                + " AND status = 'PAID'",
            Long.class,
            args.toArray());
    Long due =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(total_amount_paise),0) FROM saas_invoice"
                + where
                + " AND status = 'DUE'",
            Long.class,
            args.toArray());
    Long overdue =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(total_amount_paise),0) FROM saas_invoice"
                + where
                + " AND status IN ('OVERDUE','DUNNING')",
            Long.class,
            args.toArray());
    Long paidCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM saas_invoice" + where + " AND status = 'PAID'",
            Long.class,
            args.toArray());
    Long overdueCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM saas_invoice" + where + " AND status IN ('OVERDUE','DUNNING')",
            Long.class,
            args.toArray());
    Long dunning =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM saas_invoice" + where + " AND status = 'DUNNING'",
            Long.class,
            args.toArray());
    long pc = paidCount == null ? 0L : paidCount;
    long oc = overdueCount == null ? 0L : overdueCount;
    BigDecimal rate;
    if (pc + oc == 0) {
      rate = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    } else {
      rate =
          BigDecimal.valueOf(pc)
              .multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(pc + oc), 1, RoundingMode.HALF_UP);
    }
    BigDecimal dso = computeDso(where.toString(), args);
    return new BillingChips(
        collected == null ? 0L : collected,
        due == null ? 0L : due,
        overdue == null ? 0L : overdue,
        rate,
        dso,
        dunning == null ? 0L : dunning);
  }

  private BigDecimal computeDso(String where, List<Object> args) {
    List<BigDecimal> days =
        jdbc.query(
            "SELECT paid_at, due_at FROM saas_invoice"
                + where
                + " AND status = 'PAID' AND paid_at IS NOT NULL",
            (rs, i) -> {
              Instant paid = rs.getTimestamp("paid_at").toInstant();
              LocalDate due = rs.getDate("due_at").toLocalDate();
              LocalDate paidDay = paid.atZone(java.time.ZoneOffset.UTC).toLocalDate();
              return BigDecimal.valueOf(ChronoUnit.DAYS.between(due, paidDay));
            },
            args.toArray());
    if (days.isEmpty()) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    BigDecimal sum = days.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.divide(BigDecimal.valueOf(days.size()), 1, RoundingMode.HALF_UP);
  }

  @Override
  public List<PlanCollected> collectedByPlan(LocalDate from, LocalDate to) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT plan_name, COALESCE(SUM(total_amount_paise),0) AS collected
            FROM saas_invoice
            WHERE deleted_at IS NULL AND status = 'PAID'
            """);
    List<Object> args = new ArrayList<>();
    if (from != null) {
      sql.append(" AND billing_period_from >= ?");
      args.add(Date.valueOf(from));
    }
    if (to != null) {
      sql.append(" AND billing_period_to <= ?");
      args.add(Date.valueOf(to));
    }
    sql.append(" GROUP BY plan_name ORDER BY plan_name");
    return jdbc.query(
        sql.toString(),
        (rs, i) -> new PlanCollected(rs.getString("plan_name"), rs.getLong("collected")),
        args.toArray());
  }

  @Override
  public List<SaasInvoice> listForAccount(UUID accountId, int offset, int limit) {
    return jdbc.query(
        SELECT
            + " WHERE account_id = ? AND deleted_at IS NULL ORDER BY created_at DESC OFFSET ? LIMIT ?",
        MAPPER,
        accountId,
        offset,
        limit);
  }

  @Override
  public long countForAccount(UUID accountId) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM saas_invoice WHERE account_id = ? AND deleted_at IS NULL",
            Long.class,
            accountId);
    return n == null ? 0L : n;
  }

  @Override
  public List<String> listOpenStatuses(UUID accountId) {
    return jdbc.query(
        """
        SELECT status FROM saas_invoice
        WHERE account_id = ? AND deleted_at IS NULL
          AND status IN ('DUE', 'OVERDUE', 'DUNNING')
        """,
        (rs, i) -> rs.getString("status"),
        accountId);
  }

  @Override
  public PharmacyBillingProfile pharmacyProfile(UUID accountId) {
    List<PharmacyBillingProfile> rows =
        jdbc.query(
            """
            SELECT a.pharmacy_id,
                   COALESCE(p.business_name, p.name, 'Pharmacy') AS pharmacy_name,
                   COALESCE(p.gstin, '') AS gstin,
                   COALESCE(p.address::text, '') AS address_json
            FROM crm_account a
            LEFT JOIN pharmacies p ON p.id = a.pharmacy_id
            WHERE a.id = ? AND a.deleted_at IS NULL
            """,
            (rs, i) ->
                new PharmacyBillingProfile(
                    rs.getString("pharmacy_name"),
                    formatAddress(rs.getString("address_json")),
                    rs.getString("gstin"),
                    (UUID) rs.getObject("pharmacy_id")),
            accountId);
    return rows.isEmpty() ? new PharmacyBillingProfile("Pharmacy", "", "", null) : rows.getFirst();
  }

  @Override
  public int nextInvoiceSeq(String yearMonth) {
    jdbc.update(
        """
        INSERT INTO saas_invoice_number_counter (year_month, last_seq)
        VALUES (?, 1)
        ON CONFLICT (year_month) DO UPDATE SET last_seq = saas_invoice_number_counter.last_seq + 1
        """,
        yearMonth);
    Integer seq =
        jdbc.queryForObject(
            "SELECT last_seq FROM saas_invoice_number_counter WHERE year_month = ?",
            Integer.class,
            yearMonth);
    return seq == null ? 1 : seq;
  }

  @Override
  public List<SaasInvoice> findOpenPastDue(LocalDate asOf) {
    return jdbc.query(
        SELECT + " WHERE deleted_at IS NULL AND status = 'DUE' AND due_at < ? ORDER BY due_at ASC",
        MAPPER,
        Date.valueOf(asOf));
  }

  @Override
  public List<SaasInvoice> findForDunning(LocalDate asOf) {
    return jdbc.query(
        SELECT
            + """
             WHERE deleted_at IS NULL
               AND status IN ('DUE','OVERDUE','DUNNING')
               AND due_at <= ?
             ORDER BY due_at ASC
            """,
        MAPPER,
        Date.valueOf(asOf));
  }

  static String formatAddress(String addressJson) {
    if (addressJson == null || addressJson.isBlank() || "{}".equals(addressJson.trim())) {
      return "";
    }
    String s = addressJson.trim();
    if (s.startsWith("{")) {
      // ponytail: strip JSON noise for display; upgrade: typed address mapper
      return s.replaceAll("[{}\"]", "")
          .replace(",", ", ")
          .replace(":", ": ")
          .replaceAll("\\s+", " ")
          .trim();
    }
    return s;
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
