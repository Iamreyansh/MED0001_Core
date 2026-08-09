package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesLedgerService {

  private static final int WINDOW = 60;
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final Pattern FY = Pattern.compile("^(\\d{4})-(\\d{2})$");
  private static final String[] EXPORT_HEADERS = {
    "sale_id",
    "invoice_number",
    "date",
    "channel",
    "customer_name",
    "customer_phone",
    "items_count",
    "grand_total",
    "gst_total",
    "payment_method",
    "payment_status"
  };

  private final InvoiceStore invoiceStore;
  private final InvoiceService invoiceService;
  private final PosKhataPort khata;
  private final SimpleXlsxExporter xlsxExporter;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public SalesLedgerService(
      InvoiceStore invoiceStore,
      InvoiceService invoiceService,
      PosKhataPort khata,
      SimpleXlsxExporter xlsxExporter,
      RateLimiter rateLimiter,
      Clock clock) {
    this.invoiceStore = invoiceStore;
    this.invoiceService = invoiceService;
    this.khata = khata;
    this.xlsxExporter = xlsxExporter;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public Object list(
      MedmatePrincipal principal,
      LocalDate fromDate,
      LocalDate toDate,
      String channel,
      String paymentMethod,
      String paymentStatus,
      String q,
      String sort,
      String order,
      Integer page,
      Integer limit,
      String export,
      String financialYear,
      UUID pharmacyIdParam) {
    UUID pharmacyId = resolvePharmacyIdForRead(principal, pharmacyIdParam);
    rateLimit("pos:sales:list:" + pharmacyId, 60);

    LocalDate[] range = resolveDateRange(fromDate, toDate, financialYear);
    LocalDate from = range[0];
    LocalDate to = range[1];

    String pay = normalizeEnum(paymentMethod);
    String status = normalizeEnum(paymentStatus);
    String ch = normalizeEnum(channel);
    String sortKey = sort == null || sort.isBlank() ? "date" : sort.trim().toLowerCase(Locale.ROOT);
    if (!List.of("date", "amount", "invoice_number").contains(sortKey)) {
      throw new AppException(
          "VALIDATION_ERROR", "sort must be date, amount, or invoice_number", 400);
    }
    String ord = order == null || order.isBlank() ? "desc" : order.trim().toLowerCase(Locale.ROOT);
    if (!List.of("asc", "desc").contains(ord)) {
      throw new AppException("VALIDATION_ERROR", "order must be asc or desc", 400);
    }
    int p = 1;
    if (page != null) {
      if (page > 0) {
        p = page;
      }
    }
    int lim = 20;
    if (limit != null) {
      if (limit > 0) {
        lim = Math.min(limit, 100);
      }
    }

    if (export != null && !export.isBlank()) {
      if (!to.isBefore(from.plusMonths(12))) {
        throw new AppException(
            "EXPORT_RANGE_TOO_LARGE", "Export date range cannot exceed 12 months", 400);
      }
      String kind = export.trim().toUpperCase(Locale.ROOT);
      List<InvoiceStore.InvoiceListRow> rows =
          invoiceStore.listSales(pharmacyId, from, to, pay, status, ch, q, sortKey, ord, 10_000, 0);
      if ("EXCEL".equals(kind)) {
        return new InvoiceService.FileExport(
            xlsxExporter.exportSheet("Sales", EXPORT_HEADERS, toExportRows(rows)),
            "sales.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      }
      if ("PDF".equals(kind)) {
        List<String> lines = new ArrayList<>();
        lines.add("Sales Ledger " + from + " to " + to);
        for (InvoiceStore.InvoiceListRow row : rows) {
          Invoice inv = row.invoice();
          lines.add(
              inv.invoiceNumber()
                  + " | "
                  + inv.createdAt().atZone(ZoneOffset.UTC).toLocalDate()
                  + " | "
                  + nullToEmpty(inv.customerName())
                  + " | "
                  + MoneyMath.paiseToRupees(inv.grandTotalPaise()));
        }
        return new InvoiceService.FileExport(
            SimplePdfExporter.export("Sales", lines, false), "sales.pdf", "application/pdf");
      }
      throw new AppException("VALIDATION_ERROR", "export must be EXCEL or PDF", 400);
    }

    InvoiceStore.PeriodSummary summary =
        invoiceStore.periodSummary(pharmacyId, from, to, pay, status, ch, q);
    long total = invoiceStore.countSales(pharmacyId, from, to, pay, status, ch, q);
    List<InvoiceStore.InvoiceListRow> rows =
        invoiceStore.listSales(
            pharmacyId, from, to, pay, status, ch, q, sortKey, ord, lim, (p - 1) * lim);

    List<Map<String, Object>> sales = new ArrayList<>();
    for (InvoiceStore.InvoiceListRow row : rows) {
      sales.add(toSaleRow(row));
    }

    Map<String, Object> period = new LinkedHashMap<>();
    period.put("from_date", from.toString());
    period.put("to_date", to.toString());
    period.put("bill_count", summary.billCount());
    period.put("units_sold", summary.unitsSold());
    period.put("gross_revenue", MoneyMath.paiseToRupees(summary.grossRevenuePaise()));
    period.put("gst_collected", MoneyMath.paiseToRupees(summary.gstCollectedPaise()));
    period.put(
        "net_collected",
        MoneyMath.paiseToRupees(summary.grossRevenuePaise() - summary.gstCollectedPaise()));
    period.put("credit_outstanding", MoneyMath.paiseToRupees(summary.creditOutstandingPaise()));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period_summary", period);
    data.put("sales", sales);
    return new ListResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(
      MedmatePrincipal principal, LocalDate fromDate, LocalDate toDate, UUID pharmacyIdParam) {
    UUID pharmacyId = resolvePharmacyIdForRead(principal, pharmacyIdParam);
    rateLimit("pos:sales:summary:" + pharmacyId, 30);
    LocalDate[] range = resolveDateRange(fromDate, toDate, null);
    LocalDate from = range[0];
    LocalDate to = range[1];

    InvoiceStore.PeriodSummary period =
        invoiceStore.periodSummary(pharmacyId, from, to, null, null, null, null);
    long totalRevenue = period.grossRevenuePaise();
    BigDecimal avg =
        period.billCount() == 0
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : MoneyMath.paiseToRupees(totalRevenue)
                .divide(BigDecimal.valueOf(period.billCount()), 2, RoundingMode.HALF_UP);

    long online = 0L;
    long counter = 0L;
    for (InvoiceStore.ChannelAgg c : invoiceStore.channelRevenue(pharmacyId, from, to)) {
      if ("ONLINE".equals(c.channel())) {
        online = c.revenuePaise();
      } else if ("COUNTER".equals(c.channel())) {
        counter = c.revenuePaise();
      }
    }
    long channelTotal = online + counter;
    BigDecimal onlinePct;
    BigDecimal counterPct;
    if (channelTotal == 0) {
      onlinePct = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
      counterPct = BigDecimal.valueOf(100).setScale(1, RoundingMode.HALF_UP);
    } else {
      onlinePct =
          BigDecimal.valueOf(online)
              .multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(channelTotal), 1, RoundingMode.HALF_UP);
      counterPct = BigDecimal.valueOf(100).setScale(1, RoundingMode.HALF_UP).subtract(onlinePct);
    }

    Map<String, Object> onlineVsCounter = new LinkedHashMap<>();
    onlineVsCounter.put("online_revenue", MoneyMath.paiseToRupees(online));
    onlineVsCounter.put("online_pct", onlinePct);
    onlineVsCounter.put("counter_revenue", MoneyMath.paiseToRupees(counter));
    onlineVsCounter.put("counter_pct", counterPct);

    Map<String, Object> paymentMix = new LinkedHashMap<>();
    for (InvoiceStore.PaymentModeAgg m : invoiceStore.paymentModeMix(pharmacyId, from, to)) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("count", m.count());
      entry.put("amount", MoneyMath.paiseToRupees(m.amountPaise()));
      paymentMix.put(m.paymentMethod(), entry);
    }

    List<Map<String, Object>> topProducts = new ArrayList<>();
    for (InvoiceStore.ProductAgg p : invoiceStore.topProducts(pharmacyId, from, to, 10)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("product_name", p.productName());
      row.put("revenue", MoneyMath.paiseToRupees(p.revenuePaise()));
      row.put("units", p.units());
      topProducts.add(row);
    }

    Map<String, Object> periodMap = new LinkedHashMap<>();
    periodMap.put("from", from.toString());
    periodMap.put("to", to.toString());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", periodMap);
    data.put("total_bills", period.billCount());
    data.put("total_revenue", MoneyMath.paiseToRupees(totalRevenue));
    data.put("avg_bill_value", avg);
    data.put("online_vs_counter", onlineVsCounter);
    data.put("payment_mode_mix", paymentMix);
    data.put("top_selling_categories", List.of());
    data.put("top_selling_products", topProducts);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getDetail(MedmatePrincipal principal, UUID saleId) {
    return invoiceService.getDetail(principal, saleId);
  }

  @Transactional
  public Map<String, Object> markPaid(
      MedmatePrincipal principal, UUID saleId, Map<String, Object> body) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException(
          "STAFF_CANNOT_MARK_PAID", "Only pharmacy_owner may mark sales as paid", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
    rateLimit("pos:sales:mark-paid:" + principal.pharmacyId(), 20);

    if (saleId == null) {
      throw new AppException("SALE_NOT_FOUND", "Sale not found", 404);
    }
    Invoice invoice =
        invoiceStore
            .findById(principal.pharmacyId(), saleId)
            .orElseThrow(() -> new AppException("SALE_NOT_FOUND", "Sale not found", 404));

    if (invoice.paymentStatus() == PaymentStatus.PAID) {
      throw new AppException("SALE_ALREADY_PAID", "Sale is already paid", 400);
    }

    Map<String, Object> req = body == null ? Map.of() : body;
    String paymentMode = strOrNull(req.get("payment_mode"));
    if (paymentMode == null) {
      throw new AppException("VALIDATION_ERROR", "payment_mode is required", 400);
    }
    paymentMode = paymentMode.trim().toUpperCase(Locale.ROOT);
    if (!List.of("CASH", "UPI", "CARD").contains(paymentMode)) {
      throw new AppException("VALIDATION_ERROR", "payment_mode must be CASH, UPI, or CARD", 400);
    }
    BigDecimal amount = toAmount(req.get("amount"));
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be > 0", 400);
    }
    long amountPaise = MoneyMath.rupeesToPaise(amount);
    long outstanding = invoice.grandTotalPaise() - invoice.amountPaidPaise();
    if (amountPaise != outstanding) {
      throw new AppException("AMOUNT_MISMATCH", "amount must equal outstanding balance", 400);
    }

    String referenceNumber = strOrNull(req.get("reference_number"));
    if (referenceNumber != null && referenceNumber.length() > 50) {
      throw new AppException("VALIDATION_ERROR", "reference_number max 50 chars", 400);
    }
    String note = strOrNull(req.get("note"));
    if (note != null && note.length() > 300) {
      throw new AppException("VALIDATION_ERROR", "note max 300 chars", 400);
    }

    Instant settledAt = clock.instant();
    LocalDate ist = settledAt.atZone(INDIA).toLocalDate();
    String receiptNumber;
    if (invoice.paymentMethod() == PaymentMethod.CREDIT) {
      receiptNumber =
          khata.recordCreditRepayment(
              invoice.customerId(),
              invoice.id(),
              amountPaise,
              principal.pharmacyId(),
              paymentMode,
              referenceNumber,
              note,
              principal.subject());
      if (receiptNumber == null || receiptNumber.isBlank()) {
        int seq =
            invoiceStore.nextSequence(principal.pharmacyId(), ist.getYear(), ist.getMonthValue());
        receiptNumber =
            String.format(
                Locale.ROOT, "RCPT-%04d-%02d-%06d", ist.getYear(), ist.getMonthValue(), seq);
      }
    } else {
      int seq =
          invoiceStore.nextSequence(principal.pharmacyId(), ist.getYear(), ist.getMonthValue());
      receiptNumber =
          String.format(
              Locale.ROOT, "RCPT-%04d-%02d-%06d", ist.getYear(), ist.getMonthValue(), seq);
    }

    String paymentRef = referenceNumber != null ? referenceNumber : receiptNumber;
    PaymentStatus previous = invoice.paymentStatus();
    invoiceStore.markPaid(
        principal.pharmacyId(),
        invoice.id(),
        PaymentStatus.PAID,
        paymentRef,
        invoice.grandTotalPaise(),
        settledAt);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("sale_id", invoice.id().toString());
    data.put("invoice_number", invoice.invoiceNumber());
    data.put("previous_payment_status", previous.name());
    data.put("new_payment_status", PaymentStatus.PAID.name());
    data.put("amount_settled", MoneyMath.paiseToRupees(amountPaise));
    data.put("settled_at", settledAt.toString());
    data.put("receipt_number", receiptNumber);
    return data;
  }

  private LocalDate[] resolveDateRange(LocalDate fromDate, LocalDate toDate, String financialYear) {
    LocalDate today = LocalDate.now(clock.withZone(INDIA));
    LocalDate from = fromDate;
    LocalDate to = toDate;
    if (financialYear != null && !financialYear.isBlank()) {
      var m = FY.matcher(financialYear.trim());
      if (!m.matches()) {
        throw new AppException("VALIDATION_ERROR", "financial_year must be like 2025-26", 400);
      }
      int startYear = Integer.parseInt(m.group(1));
      int endSuffix = Integer.parseInt(m.group(2));
      if (endSuffix != (startYear + 1) % 100) {
        throw new AppException(
            "VALIDATION_ERROR", "financial_year end must be startYear+1 (e.g. 2025-26)", 400);
      }
      from = LocalDate.of(startYear, 4, 1);
      to = LocalDate.of(startYear + 1, 3, 31);
    } else {
      if (from == null) {
        from = today.withDayOfMonth(1);
      }
      if (to == null) {
        to = today;
      }
    }
    if (to.isBefore(from)) {
      throw new AppException("VALIDATION_ERROR", "to_date must be on or after from_date", 400);
    }
    return new LocalDate[] {from, to};
  }

  private UUID resolvePharmacyIdForRead(MedmatePrincipal principal, UUID pharmacyIdParam) {
    requireStaffOrAdminRead(principal);
    if (isAdminReader(principal)) {
      if (pharmacyIdParam == null) {
        throw new AppException(
            "VALIDATION_ERROR", "pharmacy_id is required for admin sales list", 400);
      }
      return pharmacyIdParam;
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
    return principal.pharmacyId();
  }

  private static void requireStaffOrAdminRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role == AuthRole.PHARMACY_OWNER
        || role == AuthRole.PHARMACY_STAFF
        || role == AuthRole.ADMIN_FINANCE
        || role == AuthRole.ADMIN_COMPLIANCE) {
      return;
    }
    throw new AppException("FORBIDDEN", "Sales ledger read access denied", 403);
  }

  private static boolean isAdminReader(MedmatePrincipal principal) {
    AuthRole role = principal.role();
    return role == AuthRole.ADMIN_FINANCE || role == AuthRole.ADMIN_COMPLIANCE;
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static Map<String, Object> toSaleRow(InvoiceStore.InvoiceListRow row) {
    Invoice inv = row.invoice();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sale_id", inv.id().toString());
    m.put("invoice_number", inv.invoiceNumber());
    m.put("date", inv.createdAt().toString());
    m.put("channel", inv.channel().name());
    m.put("customer_name", inv.customerName());
    m.put("customer_phone", inv.customerPhone());
    m.put("items_count", row.itemsCount());
    m.put("grand_total", MoneyMath.paiseToRupees(inv.grandTotalPaise()));
    m.put("gst_total", MoneyMath.paiseToRupees(inv.gstTotalPaise()));
    m.put("payment_method", inv.paymentMethod().name());
    m.put("payment_status", inv.paymentStatus().name());
    return m;
  }

  private static List<Map<String, Object>> toExportRows(List<InvoiceStore.InvoiceListRow> rows) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (InvoiceStore.InvoiceListRow row : rows) {
      out.add(toSaleRow(row));
    }
    return out;
  }

  private static String normalizeEnum(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static BigDecimal toAmount(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof BigDecimal bd) {
      return bd;
    }
    if (raw instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
    }
    try {
      return new BigDecimal(String.valueOf(raw).trim());
    } catch (NumberFormatException ex) {
      throw new AppException("VALIDATION_ERROR", "amount must be a number", 400);
    }
  }

  private static String strOrNull(Object value) {
    if (value == null) {
      return null;
    }
    String s = String.valueOf(value).trim();
    return s.isEmpty() ? null : s;
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
