package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.KhataStore;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.ReminderTemplate;
import com.nammamedmate.pos.domain.ShareChannel;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KhataService {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final int WINDOW = 60;

  private final KhataStore store;
  private final PosPlanPort plan;
  private final PosNotificationPort notifications;
  private final SimpleXlsxExporter xlsxExporter;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public KhataService(
      KhataStore store,
      PosPlanPort plan,
      PosNotificationPort notifications,
      SimpleXlsxExporter xlsxExporter,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.plan = plan;
    this.notifications = notifications;
    this.xlsxExporter = xlsxExporter;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public Map<String, Object> data() {
      return data == null ? Map.of() : data;
    }
  }

  public record FileExport(String filename, String contentType, byte[] bytes) {
    public FileExport {
      bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  @Transactional(readOnly = true)
  public ListResult list(
      MedmatePrincipal principal,
      Boolean overdueOnly,
      String sort,
      String q,
      Integer page,
      Integer limit,
      UUID pharmacyIdParam) {
    requireStarterPlan();
    UUID pharmacyId = resolvePharmacyIdForRead(principal, pharmacyIdParam);
    rateLimit("pos:khata:list:" + pharmacyId, 30);

    int p = 1;
    if (page != null) {
      if (page >= 1) {
        p = page;
      }
    }
    int lim = 20;
    if (limit != null) {
      if (limit >= 1) {
        lim = Math.min(limit, 100);
      }
    }
    int offset = (p - 1) * lim;
    boolean overdue = Boolean.TRUE.equals(overdueOnly);

    LocalDate today = LocalDate.now(clock.withZone(INDIA));
    LocalDate monthStart = today.withDayOfMonth(1);
    KhataStore.KpiSnapshot kpi = store.kpi(pharmacyId, monthStart, monthStart.plusMonths(1));
    KhataStore.AgingBuckets aging = store.aging(pharmacyId, today);
    List<KhataStore.CustomerOutstandingRow> rows =
        store.listOutstanding(pharmacyId, overdue, sort, q, lim, offset);
    long total = store.countOutstanding(pharmacyId, overdue, q);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpi", toKpi(kpi));
    data.put("aging_chart", toAging(aging));
    List<Map<String, Object>> customers = new ArrayList<>();
    for (KhataStore.CustomerOutstandingRow row : rows) {
      customers.add(toCustomerRow(row));
    }
    data.put("customers", customers);
    return new ListResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(
      MedmatePrincipal principal, UUID customerId, UUID pharmacyIdParam) {
    requireStarterPlan();
    UUID pharmacyId = resolvePharmacyIdForRead(principal, pharmacyIdParam);
    rateLimit("pos:khata:detail:" + pharmacyId, 60);

    KhataStore.CustomerInfo customer = requireKnownCustomer(pharmacyId, customerId);

    LocalDate today = LocalDate.now(clock.withZone(INDIA));
    long outstanding = store.outstandingPaise(pharmacyId, customerId);
    long limitPaise = store.creditLimitPaise(pharmacyId, customerId);
    List<KhataStore.UnpaidBillRow> unpaid = store.unpaidBills(pharmacyId, customerId, today);
    long overdueAmount =
        unpaid.stream()
            .filter(b -> b.daysSince() > 30)
            .mapToLong(KhataStore.UnpaidBillRow::amountPaise)
            .sum();
    int oldestUnpaidDays =
        unpaid.stream().mapToInt(KhataStore.UnpaidBillRow::daysSince).max().orElse(0);
    BigDecimal utilisation =
        limitPaise <= 0
            ? BigDecimal.ZERO.setScale(1)
            : BigDecimal.valueOf(outstanding)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(limitPaise), 1, RoundingMode.HALF_UP);

    Map<String, Object> customerMap = new LinkedHashMap<>();
    customerMap.put("customer_id", customer.customerId().toString());
    customerMap.put("name", customer.name());
    customerMap.put("phone", customer.phone());
    customerMap.put("credit_limit", MoneyMath.paiseToRupees(limitPaise));

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total_outstanding", MoneyMath.paiseToRupees(outstanding));
    summary.put("overdue_amount", MoneyMath.paiseToRupees(overdueAmount));
    summary.put("oldest_unpaid_days", oldestUnpaidDays);
    summary.put("credit_utilisation_pct", utilisation);

    List<Map<String, Object>> unpaidMaps = new ArrayList<>();
    for (KhataStore.UnpaidBillRow b : unpaid) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("invoice_id", b.invoiceId() == null ? null : b.invoiceId().toString());
      m.put("invoice_number", b.invoiceNumber());
      m.put("invoice_date", b.invoiceDate().toString());
      m.put("amount", MoneyMath.paiseToRupees(b.amountPaise()));
      m.put("days_since", b.daysSince());
      unpaidMaps.add(m);
    }

    List<Map<String, Object>> ledger = new ArrayList<>();
    for (KhataStore.LedgerRow row : store.ledgerDesc(pharmacyId, customerId)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("entry_id", row.entryId().toString());
      m.put("type", row.type());
      m.put("date", row.date().toString());
      m.put("reference", row.reference());
      m.put("amount", MoneyMath.paiseToRupees(row.amountPaise()));
      m.put("running_balance", MoneyMath.paiseToRupees(row.runningBalancePaise()));
      ledger.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("customer", customerMap);
    data.put("summary", summary);
    data.put("unpaid_bills", unpaidMaps);
    data.put("ledger", ledger);
    data.put("total_outstanding", MoneyMath.paiseToRupees(outstanding));
    return data;
  }

  @Transactional
  public Map<String, Object> repay(
      MedmatePrincipal principal, UUID customerId, Map<String, Object> body) {
    requireStarterPlan();
    PosCartService.requireStaff(principal);
    UUID pharmacyId = principal.pharmacyId();
    rateLimit("pos:khata:repay:" + pharmacyId, 30);

    requireKnownCustomer(pharmacyId, customerId);

    Map<String, Object> req = body == null ? Map.of() : body;
    BigDecimal amount = toAmount(req.get("amount"));
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be > 0", 400);
    }
    long amountPaise = MoneyMath.rupeesToPaise(amount);
    String paymentMode = strOrNull(req.get("payment_mode"));
    if (paymentMode == null) {
      throw new AppException("VALIDATION_ERROR", "payment_mode is required", 400);
    }
    paymentMode = paymentMode.trim().toUpperCase(Locale.ROOT);
    if (!List.of("CASH", "UPI", "CARD").contains(paymentMode)) {
      throw new AppException("VALIDATION_ERROR", "payment_mode must be CASH, UPI, or CARD", 400);
    }
    String note = strOrNull(req.get("note"));
    if (note != null) {
      if (note.length() > 300) {
        throw new AppException("VALIDATION_ERROR", "note max 300 chars", 400);
      }
    }
    String referenceNumber = strOrNull(req.get("reference_number"));
    if (referenceNumber != null) {
      if (referenceNumber.length() > 50) {
        throw new AppException("VALIDATION_ERROR", "reference_number max 50 chars", 400);
      }
    }

    long outstanding = store.outstandingPaise(pharmacyId, customerId);
    if (amountPaise > outstanding) {
      throw new AppException(
          "REPAYMENT_EXCEEDS_OUTSTANDING", "amount exceeds outstanding balance", 400);
    }

    Instant now = clock.instant();
    KhataStore.RepaymentResult result =
        store.recordRepayment(
            pharmacyId,
            customerId,
            amountPaise,
            paymentMode,
            referenceNumber,
            note,
            principal.subject(),
            now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("receipt_id", result.receiptId().toString());
    data.put("receipt_number", result.receiptNumber());
    data.put("customer_name", result.customerName());
    data.put("amount", MoneyMath.paiseToRupees(result.amountPaise()));
    data.put("payment_mode", result.paymentMode());
    data.put("previous_outstanding", MoneyMath.paiseToRupees(result.previousOutstandingPaise()));
    data.put("new_outstanding", MoneyMath.paiseToRupees(result.newOutstandingPaise()));
    data.put("receipt_pdf_url", result.receiptPdfUrl());
    data.put("created_at", result.createdAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> remind(
      MedmatePrincipal principal, UUID customerId, Map<String, Object> body) {
    requireStarterPlan();
    PosCartService.requireStaff(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException(
          "STAFF_CANNOT_REMIND", "Only pharmacy_owner may send payment reminders", 403);
    }
    UUID pharmacyId = principal.pharmacyId();
    rateLimit("pos:khata:remind:" + pharmacyId + ":" + customerId, 10);

    KhataStore.CustomerInfo customer = requireKnownCustomer(pharmacyId, customerId);

    long outstanding = store.outstandingPaise(pharmacyId, customerId);
    if (outstanding <= 0) {
      throw new AppException("NO_OUTSTANDING_BALANCE", "Customer has no outstanding balance", 400);
    }

    Instant now = clock.instant();
    Instant last = store.lastReminderAt(pharmacyId, customerId).orElse(null);
    if (last != null) {
      if (ChronoUnit.HOURS.between(last, now) < 24) {
        throw new AppException(
            "REMINDER_RATE_LIMITED", "Reminder already sent in the last 24 hours", 429);
      }
    }

    Map<String, Object> req = body == null ? Map.of() : body;
    String channelRaw = strOrNull(req.get("channel"));
    String templateRaw = strOrNull(req.get("message_template"));
    if (channelRaw == null) {
      throw new AppException("VALIDATION_ERROR", "channel and message_template are required", 400);
    }
    if (templateRaw == null) {
      throw new AppException("VALIDATION_ERROR", "channel and message_template are required", 400);
    }
    ShareChannel channel;
    ReminderTemplate template;
    try {
      channel = ShareChannel.valueOf(channelRaw.trim().toUpperCase(Locale.ROOT));
      template = ReminderTemplate.valueOf(templateRaw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid channel or message_template", 400);
    }
    if (channel != ShareChannel.WHATSAPP) {
      if (channel != ShareChannel.SMS) {
        throw new AppException("VALIDATION_ERROR", "channel must be WHATSAPP or SMS", 400);
      }
    }

    String phone = customer.phone();
    if (phone == null) {
      throw new AppException("VALIDATION_ERROR", "Customer phone required for reminder", 400);
    }
    if (phone.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Customer phone required for reminder", 400);
    }

    PosNotificationPort.ShareResult sent;
    try {
      sent =
          notifications.sendKhataReminder(
              pharmacyId, customerId, channel, template.name(), phone, outstanding);
    } catch (AppException ex) {
      if ("CHANNEL_UNAVAILABLE".equals(ex.code())) {
        if (channel == ShareChannel.WHATSAPP) {
          sent =
              notifications.sendKhataReminder(
                  pharmacyId, customerId, ShareChannel.SMS, template.name(), phone, outstanding);
          channel = ShareChannel.SMS;
        } else {
          throw ex;
        }
      } else {
        throw ex;
      }
    }

    store.insertReminderLog(
        Ids.newId(),
        pharmacyId,
        customerId,
        channel.name(),
        template.name(),
        sent.messageId(),
        sent.sentAt());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("channel", channel.name());
    data.put("template", template.name());
    data.put("sent_to", phone);
    data.put("outstanding_amount", MoneyMath.paiseToRupees(outstanding));
    data.put("message_id", sent.messageId());
    data.put("sent_at", sent.sentAt().toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Object paymentHistory(
      MedmatePrincipal principal,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMode,
      String q,
      Integer page,
      Integer limit,
      String export,
      UUID pharmacyIdParam) {
    requireStarterPlan();
    UUID pharmacyId = resolvePharmacyIdForRead(principal, pharmacyIdParam);
    rateLimit("pos:khata:history:" + pharmacyId, 30);

    if (export != null && !export.isBlank()) {
      if (!"EXCEL".equalsIgnoreCase(export.trim())) {
        throw new AppException("VALIDATION_ERROR", "export must be EXCEL", 400);
      }
      List<KhataStore.PaymentHistoryRow> rows =
          store.paymentHistory(pharmacyId, fromDate, toDate, paymentMode, q, 10_000, 0);
      List<Map<String, Object>> exportRows = new ArrayList<>();
      for (KhataStore.PaymentHistoryRow row : rows) {
        exportRows.add(toPaymentRow(row));
      }
      byte[] bytes =
          xlsxExporter.exportSheet(
              "PaymentHistory",
              new String[] {
                "receipt_number",
                "date",
                "customer_name",
                "customer_phone",
                "mode",
                "amount",
                "note",
                "running_outstanding_after"
              },
              exportRows);
      return new FileExport(
          "khata-payment-history.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          bytes);
    }

    int p = 1;
    if (page != null) {
      if (page >= 1) {
        p = page;
      }
    }
    int lim = 20;
    if (limit != null) {
      if (limit >= 1) {
        lim = Math.min(limit, 100);
      }
    }
    int offset = (p - 1) * lim;
    List<KhataStore.PaymentHistoryRow> rows =
        store.paymentHistory(pharmacyId, fromDate, toDate, paymentMode, q, lim, offset);
    long total = store.countPaymentHistory(pharmacyId, fromDate, toDate, paymentMode, q);
    long periodTotal = store.paymentHistoryTotalPaise(pharmacyId, fromDate, toDate, paymentMode, q);

    List<Map<String, Object>> repayments = new ArrayList<>();
    for (KhataStore.PaymentHistoryRow row : rows) {
      repayments.add(toPaymentRow(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("repayments", repayments);
    data.put("period_total_collected", MoneyMath.paiseToRupees(periodTotal));
    return new ListResult(data, PaginationMeta.of(p, lim, total));
  }

  private void requireStarterPlan() {
    if (!plan.starterFeaturesEnabled()) {
      throw new AppException("PLAN_FEATURE_LOCKED", "Khata requires Starter plan or higher", 403);
    }
  }

  /**
   * Pharmacy link check first (no PII), then load customer. Cross-pharmacy UUID →
   * CUSTOMER_NOT_FOUND.
   */
  private KhataStore.CustomerInfo requireKnownCustomer(UUID pharmacyId, UUID customerId) {
    if (!store.customerKnownToPharmacy(pharmacyId, customerId)) {
      throw new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404);
    }
    return store
        .findCustomer(customerId)
        .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
  }

  private UUID resolvePharmacyIdForRead(MedmatePrincipal principal, UUID pharmacyIdParam) {
    requireStaffOrAdminRead(principal);
    if (isAdminReader(principal)) {
      if (pharmacyIdParam == null) {
        throw new AppException(
            "VALIDATION_ERROR", "pharmacy_id is required for admin khata list", 400);
      }
      return pharmacyIdParam;
    }
    return requirePharmacyContext(principal);
  }

  private static UUID requirePharmacyContext(MedmatePrincipal principal) {
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
    if (role == AuthRole.PHARMACY_OWNER) {
      return;
    }
    if (role == AuthRole.PHARMACY_STAFF) {
      return;
    }
    if (role == AuthRole.ADMIN_FINANCE) {
      return;
    }
    if (role == AuthRole.ADMIN_SUPPORT) {
      return;
    }
    throw new AppException("FORBIDDEN", "Khata read access denied", 403);
  }

  private static boolean isAdminReader(MedmatePrincipal principal) {
    AuthRole role = principal.role();
    if (role == AuthRole.ADMIN_FINANCE) {
      return true;
    }
    return role == AuthRole.ADMIN_SUPPORT;
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static Map<String, Object> toKpi(KhataStore.KpiSnapshot kpi) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("total_outstanding", MoneyMath.paiseToRupees(kpi.totalOutstandingPaise()));
    m.put("overdue_30d", MoneyMath.paiseToRupees(kpi.overdue30dPaise()));
    m.put("collected_this_month", MoneyMath.paiseToRupees(kpi.collectedThisMonthPaise()));
    BigDecimal rate = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    if (kpi.creditGivenThisMonthPaise() > 0) {
      rate =
          BigDecimal.valueOf(kpi.collectedThisMonthPaise())
              .multiply(BigDecimal.valueOf(100))
              .divide(BigDecimal.valueOf(kpi.creditGivenThisMonthPaise()), 1, RoundingMode.HALF_UP);
    }
    m.put("collection_rate_pct", rate);
    m.put("all_time_credit_given", MoneyMath.paiseToRupees(kpi.allTimeCreditGivenPaise()));
    return m;
  }

  private static Map<String, Object> toAging(KhataStore.AgingBuckets aging) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("current_0_30d", MoneyMath.paiseToRupees(aging.current0To30Paise()));
    m.put("overdue_31_60d", MoneyMath.paiseToRupees(aging.overdue31To60Paise()));
    m.put("overdue_60d_plus", MoneyMath.paiseToRupees(aging.overdue60PlusPaise()));
    return m;
  }

  private static Map<String, Object> toCustomerRow(KhataStore.CustomerOutstandingRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("customer_id", row.customerId().toString());
    m.put("name", row.name());
    m.put("phone", row.phone());
    m.put("outstanding", MoneyMath.paiseToRupees(row.outstandingPaise()));
    m.put(
        "oldest_unpaid_date",
        row.oldestUnpaidDate() == null ? null : row.oldestUnpaidDate().toString());
    m.put("days_overdue", row.daysOverdue());
    m.put("is_overdue", row.overdue());
    return m;
  }

  private static Map<String, Object> toPaymentRow(KhataStore.PaymentHistoryRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("receipt_id", row.receiptId().toString());
    m.put("receipt_number", row.receiptNumber());
    m.put("date", row.date().toString());
    m.put("customer_name", row.customerName());
    m.put("customer_phone", row.customerPhone());
    m.put("mode", row.mode());
    m.put("amount", MoneyMath.paiseToRupees(row.amountPaise()));
    m.put("note", row.note());
    m.put("running_outstanding_after", MoneyMath.paiseToRupees(row.runningOutstandingAfterPaise()));
    return m;
  }

  private static BigDecimal toAmount(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof BigDecimal bd) {
      return bd;
    }
    if (raw instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    try {
      return new BigDecimal(raw.toString().trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String strOrNull(Object raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.toString().trim();
    return s.isEmpty() ? null : s;
  }
}
