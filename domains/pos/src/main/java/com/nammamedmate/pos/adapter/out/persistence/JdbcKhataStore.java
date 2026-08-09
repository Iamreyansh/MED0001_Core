package com.nammamedmate.pos.adapter.out.persistence;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pos.application.port.out.KhataStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKhataStore implements KhataStore {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private final JdbcTemplate jdbc;

  public JdbcKhataStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long outstandingPaise(UUID pharmacyId, UUID customerId) {
    Long v =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount_paise ELSE -amount_paise END), 0)
            FROM khata_entry
            WHERE pharmacy_id = ? AND customer_id = ?
            """,
            Long.class,
            pharmacyId,
            customerId);
    return v == null ? 0L : Math.max(0L, v);
  }

  @Override
  public long creditLimitPaise(UUID pharmacyId, UUID customerId) {
    List<Long> rows =
        jdbc.query(
            """
            SELECT credit_limit_paise FROM khata_customer_limit
            WHERE pharmacy_id = ? AND customer_id = ?
            """,
            (rs, i) -> rs.getLong(1),
            pharmacyId,
            customerId);
    return rows.isEmpty() ? DEFAULT_CREDIT_LIMIT_PAISE : rows.getFirst();
  }

  @Override
  public void postCreditSale(UUID customerId, UUID invoiceId, long amountPaise, UUID pharmacyId) {
    ensureCustomerKnown(pharmacyId, customerId);
    Instant now = Instant.now();
    String invoiceNumber =
        jdbc
            .query(
                """
                SELECT invoice_number FROM invoice WHERE id = ? AND pharmacy_id = ?
                """,
                (rs, i) -> rs.getString(1),
                invoiceId,
                pharmacyId)
            .stream()
            .findFirst()
            .orElse("INV-" + invoiceId);
    long previous = outstandingPaise(pharmacyId, customerId);
    long running = previous + amountPaise;
    jdbc.update(
        """
        INSERT INTO khata_entry (
          id, pharmacy_id, customer_id, type, amount_paise, invoice_id, repayment_id,
          reference_number, notes, running_balance_paise, created_at)
        VALUES (?, ?, ?, 'DEBIT', ?, ?, NULL, ?, NULL, ?, ?)
        """,
        Ids.newId(),
        pharmacyId,
        customerId,
        amountPaise,
        invoiceId,
        invoiceNumber,
        running,
        Timestamp.from(now));
  }

  @Override
  public String recordCreditRepayment(
      UUID customerId,
      UUID invoiceId,
      long amountPaise,
      UUID pharmacyId,
      String paymentMode,
      String referenceNumber,
      String note,
      UUID collectedBy) {
    Instant now = Instant.now();
    RepaymentResult result =
        recordRepayment(
            pharmacyId,
            customerId,
            amountPaise,
            paymentMode,
            referenceNumber,
            note,
            collectedBy != null ? collectedBy : pharmacyId,
            now);
    // Link CREDIT entry to invoice when settling a specific sale (mark-paid).
    if (invoiceId != null) {
      jdbc.update(
          """
          UPDATE khata_entry SET invoice_id = ?
          WHERE repayment_id = ? AND pharmacy_id = ? AND type = 'CREDIT'
          """,
          invoiceId,
          result.receiptId(),
          pharmacyId);
    }
    return result.receiptNumber();
  }

  @Override
  public Optional<CustomerInfo> findCustomer(UUID customerId) {
    List<CustomerInfo> rows =
        jdbc.query(
            """
            SELECT id, name, phone FROM customers
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) ->
                new CustomerInfo(
                    (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("phone")),
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean customerKnownToPharmacy(UUID pharmacyId, UUID customerId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT CASE WHEN EXISTS (
              SELECT 1 FROM khata_customer_limit WHERE pharmacy_id = ? AND customer_id = ?
            ) OR EXISTS (
              SELECT 1 FROM khata_entry WHERE pharmacy_id = ? AND customer_id = ?
            ) OR EXISTS (
              SELECT 1 FROM invoice WHERE pharmacy_id = ? AND customer_id = ?
            ) THEN 1 ELSE 0 END
            """,
            Long.class,
            pharmacyId,
            customerId,
            pharmacyId,
            customerId,
            pharmacyId,
            customerId);
    return n != null && n > 0;
  }

  @Override
  public void ensureCustomerKnown(UUID pharmacyId, UUID customerId) {
    jdbc.update(
        """
        INSERT INTO khata_customer_limit (pharmacy_id, customer_id, credit_limit_paise, updated_at)
        VALUES (?, ?, ?, NOW())
        ON CONFLICT (pharmacy_id, customer_id) DO NOTHING
        """,
        pharmacyId,
        customerId,
        DEFAULT_CREDIT_LIMIT_PAISE);
  }

  @Override
  public List<CustomerOutstandingRow> listOutstanding(
      UUID pharmacyId, boolean overdueOnly, String sort, String q, int limit, int offset) {
    LocalDate today = LocalDate.now(INDIA);
    List<CustomerOutstandingRow> all = computeCustomerRows(pharmacyId, today, q);
    if (overdueOnly) {
      all = all.stream().filter(CustomerOutstandingRow::overdue).toList();
    }
    Comparator<CustomerOutstandingRow> cmp = outstandingComparator(sort);
    return all.stream().sorted(cmp).skip(offset).limit(limit).toList();
  }

  @Override
  public long countOutstanding(UUID pharmacyId, boolean overdueOnly, String q) {
    LocalDate today = LocalDate.now(INDIA);
    List<CustomerOutstandingRow> all = computeCustomerRows(pharmacyId, today, q);
    if (overdueOnly) {
      return all.stream().filter(CustomerOutstandingRow::overdue).count();
    }
    return all.size();
  }

  @Override
  public KpiSnapshot kpi(UUID pharmacyId, LocalDate monthStart, LocalDate monthEndExclusive) {
    Instant from = monthStart.atStartOfDay(INDIA).toInstant();
    Instant to = monthEndExclusive.atStartOfDay(INDIA).toInstant();
    LocalDate today = LocalDate.now(INDIA);

    Long collected =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0) FROM khata_entry
            WHERE pharmacy_id = ? AND type = 'CREDIT'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            pharmacyId,
            Timestamp.from(from),
            Timestamp.from(to));
    Long creditGiven =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0) FROM khata_entry
            WHERE pharmacy_id = ? AND type = 'DEBIT'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            pharmacyId,
            Timestamp.from(from),
            Timestamp.from(to));
    Long allTime =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0) FROM khata_entry
            WHERE pharmacy_id = ? AND type = 'DEBIT'
            """,
            Long.class,
            pharmacyId);

    long collectedPaise = collected == null ? 0L : collected;
    long creditGivenPaise = creditGiven == null ? 0L : creditGiven;
    long allTimePaise = allTime == null ? 0L : allTime;

    AgingBuckets buckets = aging(pharmacyId, today);
    long totalOut =
        buckets.current0To30Paise() + buckets.overdue31To60Paise() + buckets.overdue60PlusPaise();
    long overdue30 = buckets.overdue31To60Paise() + buckets.overdue60PlusPaise();
    return new KpiSnapshot(totalOut, overdue30, collectedPaise, creditGivenPaise, allTimePaise);
  }

  @Override
  public AgingBuckets aging(UUID pharmacyId, LocalDate today) {
    long c0 = 0;
    long c31 = 0;
    long c60 = 0;
    List<UUID> customers =
        jdbc.query(
            """
            SELECT DISTINCT customer_id FROM khata_entry WHERE pharmacy_id = ?
            """,
            (rs, i) -> (UUID) rs.getObject(1),
            pharmacyId);
    for (UUID customerId : customers) {
      for (UnpaidBillRow bill : unpaidBills(pharmacyId, customerId, today)) {
        int days = bill.daysSince();
        if (days <= 30) {
          c0 += bill.amountPaise();
        } else if (days <= 60) {
          c31 += bill.amountPaise();
        } else {
          c60 += bill.amountPaise();
        }
      }
    }
    return new AgingBuckets(c0, c31, c60);
  }

  @Override
  public List<UnpaidBillRow> unpaidBills(UUID pharmacyId, UUID customerId, LocalDate today) {
    List<DebitRow> debits =
        jdbc.query(
            """
            SELECT e.invoice_id, e.reference_number, e.amount_paise, e.created_at,
                   COALESCE(i.created_at, e.created_at) AS invoice_at
            FROM khata_entry e
            LEFT JOIN invoice i ON i.id = e.invoice_id
            WHERE e.pharmacy_id = ? AND e.customer_id = ? AND e.type = 'DEBIT'
            ORDER BY e.created_at ASC
            """,
            (rs, i) ->
                new DebitRow(
                    (UUID) rs.getObject("invoice_id"),
                    rs.getString("reference_number"),
                    rs.getLong("amount_paise"),
                    rs.getTimestamp("invoice_at").toInstant()),
            pharmacyId,
            customerId);
    Long credits =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0) FROM khata_entry
            WHERE pharmacy_id = ? AND customer_id = ? AND type = 'CREDIT'
            """,
            Long.class,
            pharmacyId,
            customerId);
    long remainingCredit = credits == null ? 0L : credits;
    List<UnpaidBillRow> unpaid = new ArrayList<>();
    for (DebitRow d : debits) {
      long apply = Math.min(remainingCredit, d.amountPaise());
      remainingCredit -= apply;
      long left = d.amountPaise() - apply;
      if (left <= 0) {
        continue;
      }
      LocalDate invoiceDate = d.invoiceAt().atZone(INDIA).toLocalDate();
      int days = (int) ChronoUnit.DAYS.between(invoiceDate, today);
      unpaid.add(
          new UnpaidBillRow(
              d.invoiceId(), d.referenceNumber(), invoiceDate, left, Math.max(0, days)));
    }
    return unpaid;
  }

  @Override
  public List<LedgerRow> ledgerDesc(UUID pharmacyId, UUID customerId) {
    return jdbc.query(
        """
        SELECT id, type, amount_paise, reference_number, running_balance_paise, created_at
        FROM khata_entry
        WHERE pharmacy_id = ? AND customer_id = ?
        ORDER BY created_at DESC, id DESC
        """,
        (rs, i) -> mapLedger(rs),
        pharmacyId,
        customerId);
  }

  @Override
  public RepaymentResult recordRepayment(
      UUID pharmacyId,
      UUID customerId,
      long amountPaise,
      String paymentMode,
      String referenceNumber,
      String note,
      UUID collectedBy,
      Instant now) {
    long previous = outstandingPaise(pharmacyId, customerId);
    LocalDate ist = now.atZone(INDIA).toLocalDate();
    int seq = nextReceiptSequence(pharmacyId, ist.getYear(), ist.getMonthValue());
    String receiptNumber =
        String.format(Locale.ROOT, "RCPT-%04d-%02d-%06d", ist.getYear(), ist.getMonthValue(), seq);
    UUID receiptId = Ids.newId();
    long newOutstanding = previous - amountPaise;
    jdbc.update(
        """
        INSERT INTO khata_repayment (
          id, pharmacy_id, customer_id, receipt_number, amount_paise, payment_mode,
          reference_number, notes, collected_by, outstanding_after_paise, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        receiptId,
        pharmacyId,
        customerId,
        receiptNumber,
        amountPaise,
        paymentMode,
        referenceNumber,
        note,
        collectedBy,
        newOutstanding,
        Timestamp.from(now));
    jdbc.update(
        """
        INSERT INTO khata_entry (
          id, pharmacy_id, customer_id, type, amount_paise, invoice_id, repayment_id,
          reference_number, notes, running_balance_paise, created_at)
        VALUES (?, ?, ?, 'CREDIT', ?, NULL, ?, ?, ?, ?, ?)
        """,
        Ids.newId(),
        pharmacyId,
        customerId,
        amountPaise,
        receiptId,
        receiptNumber,
        note,
        newOutstanding,
        Timestamp.from(now));
    String name =
        findCustomer(customerId)
            .map(c -> c.name() != null ? c.name() : c.phone())
            .orElse("Customer");
    String pdfUrl = "https://cdn.medmate.in/pharmacy/" + pharmacyId + "/" + receiptNumber + ".pdf";
    return new RepaymentResult(
        receiptId,
        receiptNumber,
        name,
        amountPaise,
        paymentMode,
        previous,
        newOutstanding,
        pdfUrl,
        now);
  }

  @Override
  public Optional<Instant> lastReminderAt(UUID pharmacyId, UUID customerId) {
    List<Instant> rows =
        jdbc.query(
            """
            SELECT sent_at FROM khata_reminder_log
            WHERE pharmacy_id = ? AND customer_id = ?
            ORDER BY sent_at DESC LIMIT 1
            """,
            (rs, i) -> rs.getTimestamp(1).toInstant(),
            pharmacyId,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public void insertReminderLog(
      UUID id,
      UUID pharmacyId,
      UUID customerId,
      String channel,
      String template,
      String messageId,
      Instant sentAt) {
    jdbc.update(
        """
        INSERT INTO khata_reminder_log (id, pharmacy_id, customer_id, channel, template, message_id, sent_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        pharmacyId,
        customerId,
        channel,
        template,
        messageId,
        Timestamp.from(sentAt));
  }

  @Override
  public List<PaymentHistoryRow> paymentHistory(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMode,
      String q,
      int limit,
      int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT r.id, r.receipt_number, r.created_at, r.payment_mode, r.amount_paise, r.notes,
                   r.outstanding_after_paise, c.name, c.phone
            FROM khata_repayment r
            JOIN customers c ON c.id = r.customer_id
            WHERE r.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendPaymentFilters(sql, args, fromDate, toDate, paymentMode, q);
    sql.append(" ORDER BY r.created_at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), (rs, i) -> mapPayment(rs), args.toArray());
  }

  @Override
  public long countPaymentHistory(
      UUID pharmacyId, LocalDate fromDate, LocalDate toDate, String paymentMode, String q) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COUNT(*) FROM khata_repayment r
            JOIN customers c ON c.id = r.customer_id
            WHERE r.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendPaymentFilters(sql, args, fromDate, toDate, paymentMode, q);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public long paymentHistoryTotalPaise(
      UUID pharmacyId, LocalDate fromDate, LocalDate toDate, String paymentMode, String q) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COALESCE(SUM(r.amount_paise), 0) FROM khata_repayment r
            JOIN customers c ON c.id = r.customer_id
            WHERE r.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    appendPaymentFilters(sql, args, fromDate, toDate, paymentMode, q);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  private int nextReceiptSequence(UUID pharmacyId, int year, int month) {
    int updated =
        jdbc.update(
            """
            UPDATE receipt_number_sequence
            SET last_seq = last_seq + 1
            WHERE pharmacy_id = ? AND year = ? AND month = ?
            """,
            pharmacyId,
            year,
            month);
    if (updated == 0) {
      jdbc.update(
          """
          INSERT INTO receipt_number_sequence (pharmacy_id, year, month, last_seq)
          VALUES (?, ?, ?, 1)
          ON CONFLICT (pharmacy_id, year, month) DO UPDATE
            SET last_seq = receipt_number_sequence.last_seq + 1
          """,
          pharmacyId,
          year,
          month);
    }
    Integer seq =
        jdbc.queryForObject(
            """
            SELECT last_seq FROM receipt_number_sequence
            WHERE pharmacy_id = ? AND year = ? AND month = ?
            """,
            Integer.class,
            pharmacyId,
            year,
            month);
    return seq == null ? 1 : seq;
  }

  private List<CustomerOutstandingRow> computeCustomerRows(
      UUID pharmacyId, LocalDate today, String q) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT e.customer_id, c.name, c.phone,
                   COALESCE(SUM(CASE WHEN e.type = 'DEBIT' THEN e.amount_paise ELSE -e.amount_paise END), 0)
                     AS outstanding
            FROM khata_entry e
            JOIN customers c ON c.id = e.customer_id AND c.deleted_at IS NULL
            WHERE e.pharmacy_id = ?
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (q != null && !q.isBlank()) {
      sql.append(" AND (LOWER(COALESCE(c.name,'')) LIKE ? OR c.phone LIKE ?)");
      String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
      args.add(like);
      args.add("%" + q.trim() + "%");
    }
    sql.append(
        " GROUP BY e.customer_id, c.name, c.phone HAVING COALESCE(SUM(CASE WHEN e.type = 'DEBIT' THEN e.amount_paise ELSE -e.amount_paise END), 0) > 0");

    List<Map<String, Object>> base =
        jdbc.query(
            sql.toString(),
            (rs, i) -> {
              Map<String, Object> m = new HashMap<>();
              m.put("customer_id", (UUID) rs.getObject("customer_id"));
              m.put("name", rs.getString("name"));
              m.put("phone", rs.getString("phone"));
              m.put("outstanding", rs.getLong("outstanding"));
              return m;
            },
            args.toArray());

    List<CustomerOutstandingRow> rows = new ArrayList<>();
    for (Map<String, Object> m : base) {
      UUID customerId = (UUID) m.get("customer_id");
      List<UnpaidBillRow> unpaid = unpaidBills(pharmacyId, customerId, today);
      LocalDate oldest = null;
      int maxDays = 0;
      boolean overdue = false;
      for (UnpaidBillRow b : unpaid) {
        if (oldest == null) {
          oldest = b.invoiceDate();
        } else {
          if (b.invoiceDate().isBefore(oldest)) {
            oldest = b.invoiceDate();
          }
        }
        if (b.daysSince() > maxDays) {
          maxDays = b.daysSince();
        }
        if (b.daysSince() > 30) {
          overdue = true;
        }
      }
      int daysOverdue = overdue ? Math.max(0, maxDays - 30) : 0;
      rows.add(
          new CustomerOutstandingRow(
              customerId,
              (String) m.get("name"),
              (String) m.get("phone"),
              (Long) m.get("outstanding"),
              oldest,
              daysOverdue,
              overdue));
    }
    return rows;
  }

  private static Comparator<CustomerOutstandingRow> outstandingComparator(String sort) {
    String s = sort == null ? "outstanding_desc" : sort.trim().toLowerCase(Locale.ROOT);
    return switch (s) {
      case "outstanding_asc" ->
          Comparator.comparingLong(CustomerOutstandingRow::outstandingPaise)
              .thenComparing(r -> r.customerId().toString());
      case "oldest_bill" ->
          Comparator.comparing(
                  CustomerOutstandingRow::oldestUnpaidDate,
                  Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(r -> r.customerId().toString());
      default ->
          Comparator.comparingLong(CustomerOutstandingRow::outstandingPaise)
              .reversed()
              .thenComparing(r -> r.customerId().toString());
    };
  }

  private static void appendPaymentFilters(
      StringBuilder sql,
      List<Object> args,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMode,
      String q) {
    if (fromDate != null) {
      sql.append(" AND r.created_at >= ?");
      args.add(Timestamp.from(fromDate.atStartOfDay(INDIA).toInstant()));
    }
    if (toDate != null) {
      sql.append(" AND r.created_at < ?");
      args.add(Timestamp.from(toDate.plusDays(1).atStartOfDay(INDIA).toInstant()));
    }
    if (paymentMode != null) {
      if (!paymentMode.isBlank()) {
        sql.append(" AND r.payment_mode = ?");
        args.add(paymentMode.trim().toUpperCase(Locale.ROOT));
      }
    }
    if (q != null) {
      if (!q.isBlank()) {
        sql.append(
            " AND (LOWER(COALESCE(c.name,'')) LIKE ? OR LOWER(r.receipt_number) LIKE ? OR c.phone LIKE ?)");
        String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        args.add(like);
        args.add(like);
        args.add("%" + q.trim() + "%");
      }
    }
  }

  private static LedgerRow mapLedger(ResultSet rs) throws SQLException {
    Instant created = rs.getTimestamp("created_at").toInstant();
    return new LedgerRow(
        (UUID) rs.getObject("id"),
        rs.getString("type"),
        created.atZone(ZoneOffset.UTC).toLocalDate(),
        rs.getString("reference_number"),
        rs.getLong("amount_paise"),
        rs.getLong("running_balance_paise"),
        created);
  }

  private static PaymentHistoryRow mapPayment(ResultSet rs) throws SQLException {
    Instant created = rs.getTimestamp("created_at").toInstant();
    return new PaymentHistoryRow(
        (UUID) rs.getObject("id"),
        rs.getString("receipt_number"),
        created.atZone(INDIA).toLocalDate(),
        rs.getString("name"),
        rs.getString("phone"),
        rs.getString("payment_mode"),
        rs.getLong("amount_paise"),
        rs.getString("notes"),
        rs.getLong("outstanding_after_paise"));
  }

  private record DebitRow(
      UUID invoiceId, String referenceNumber, long amountPaise, Instant invoiceAt) {}
}
