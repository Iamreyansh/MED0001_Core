package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.InvoiceSettingsStore;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPharmacyPort;
import com.nammamedmate.pos.domain.GstBreakdown;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceItem;
import com.nammamedmate.pos.domain.InvoiceSettings;
import com.nammamedmate.pos.domain.InvoiceTemplate;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.ShareChannel;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

  private static final int WINDOW = 60;
  private static final Pattern PREFIX = Pattern.compile("^[A-Z0-9]{1,6}$");
  private static final Pattern ACCENT = Pattern.compile("^#[0-9A-Fa-f]{6}$");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{8,15}$");
  private static final String[] EXPORT_HEADERS = {
    "invoice_id",
    "invoice_number",
    "date",
    "customer_name",
    "customer_phone",
    "channel",
    "payment_method",
    "items_count",
    "grand_total",
    "gst_total",
    "payment_status"
  };

  private final InvoiceStore invoiceStore;
  private final InvoiceSettingsStore settingsStore;
  private final PosPharmacyPort pharmacyPort;
  private final PosNotificationPort notificationPort;
  private final SimpleXlsxExporter xlsxExporter;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public InvoiceService(
      InvoiceStore invoiceStore,
      InvoiceSettingsStore settingsStore,
      PosPharmacyPort pharmacyPort,
      PosNotificationPort notificationPort,
      SimpleXlsxExporter xlsxExporter,
      RateLimiter rateLimiter,
      Clock clock) {
    this.invoiceStore = invoiceStore;
    this.settingsStore = settingsStore;
    this.pharmacyPort = pharmacyPort;
    this.notificationPort = notificationPort;
    this.xlsxExporter = xlsxExporter;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public record FileExport(byte[] bytes, String filename, String contentType) {
    public FileExport {
      bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  @Transactional(readOnly = true)
  public Object list(
      MedmatePrincipal principal,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMethod,
      String channel,
      String q,
      Integer page,
      Integer limit,
      String export,
      UUID pharmacyIdParam) {
    UUID pharmacyId = resolvePharmacyIdForRead(principal, pharmacyIdParam);
    rateLimit("pos:invoice:list:" + pharmacyId, 60);

    String pay = normalizeEnum(paymentMethod, "payment_method");
    String ch = normalizeEnum(channel, "channel");
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);

    if (export != null && !export.isBlank()) {
      String kind = export.trim().toUpperCase(Locale.ROOT);
      List<InvoiceStore.InvoiceListRow> rows =
          invoiceStore.list(pharmacyId, fromDate, toDate, pay, ch, q, 10_000, 0);
      if ("EXCEL".equals(kind)) {
        return new FileExport(
            xlsxExporter.exportSheet("Invoices", EXPORT_HEADERS, toExportRows(rows)),
            "invoices.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      }
      if ("PDF".equals(kind)) {
        List<String> lines = new ArrayList<>();
        lines.add("Invoice Export");
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
        return new FileExport(
            SimplePdfExporter.export("Invoices", lines, false), "invoices.pdf", "application/pdf");
      }
      throw new AppException("VALIDATION_ERROR", "export must be EXCEL or PDF", 400);
    }

    long total = invoiceStore.count(pharmacyId, fromDate, toDate, pay, ch, q);
    List<InvoiceStore.InvoiceListRow> rows =
        invoiceStore.list(pharmacyId, fromDate, toDate, pay, ch, q, lim, (p - 1) * lim);
    List<Map<String, Object>> invoices = new ArrayList<>();
    for (InvoiceStore.InvoiceListRow row : rows) {
      invoices.add(toListItem(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoices", invoices);
    return new ListResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getDetail(MedmatePrincipal principal, UUID invoiceId) {
    Invoice invoice = requireInvoiceForRead(principal, invoiceId);
    UUID pharmacyId = invoice.pharmacyId();
    rateLimit("pos:invoice:detail:" + pharmacyId, 120);
    InvoiceSettings settings = settingsStore.getOrCreate(pharmacyId);
    List<InvoiceItem> items = invoiceStore.listItems(invoiceId);
    PosPharmacyPort.PharmacyInfo pharmacy =
        pharmacyPort
            .findById(pharmacyId)
            .orElse(new PosPharmacyPort.PharmacyInfo("Pharmacy", null, null, null, null));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoice_id", invoice.id().toString());
    data.put("invoice_number", invoice.invoiceNumber());
    data.put("date", invoice.createdAt().toString());
    data.put("channel", invoice.channel().name());
    Map<String, Object> pharmacyMap = new LinkedHashMap<>();
    pharmacyMap.put("name", pharmacy.name());
    pharmacyMap.put("address", pharmacy.address());
    pharmacyMap.put("phone", pharmacy.phone());
    pharmacyMap.put("gstin", pharmacy.gstin());
    pharmacyMap.put("drug_licence", pharmacy.drugLicence());
    data.put("pharmacy", pharmacyMap);
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("name", invoice.customerName());
    customer.put("phone", invoice.customerPhone());
    data.put("customer", customer);
    if (settings.showDoctor()) {
      data.put("prescribing_doctor", invoice.prescribingDoctor());
    }
    data.put("line_items", toLineItems(items, settings.showHsn()));
    data.put("subtotal", MoneyMath.paiseToRupees(invoice.subtotalPaise()));
    data.put("discount_amount", MoneyMath.paiseToRupees(invoice.discountAmountPaise()));
    data.put("gst_breakdown", GstBreakdown.fromItems(items));
    data.put("grand_total", MoneyMath.paiseToRupees(invoice.grandTotalPaise()));
    data.put("payment_method", invoice.paymentMethod().name());
    data.put("payment_status", invoice.paymentStatus().name());
    if (settings.showMrpSavings()) {
      data.put("mrp_savings", MoneyMath.paiseToRupees(invoice.mrpSavingsPaise()));
    }
    data.put("payment_reference", invoice.paymentReference());
    return data;
  }

  @Transactional(readOnly = true)
  public FileExport pdf(MedmatePrincipal principal, UUID invoiceId, String template) {
    Invoice invoice = requireInvoiceForRead(principal, invoiceId);
    UUID pharmacyId = invoice.pharmacyId();
    rateLimit("pos:invoice:pdf:" + pharmacyId, 30);
    InvoiceSettings settings = settingsStore.getOrCreate(pharmacyId);
    InvoiceTemplate tpl = resolveTemplate(template, settings.template());
    byte[] pdf = renderPdf(invoice, settings, tpl);
    String filename = invoice.invoiceNumber().replace('/', '-') + ".pdf";
    return new FileExport(pdf, filename, "application/pdf");
  }

  @Transactional
  public Map<String, Object> share(
      MedmatePrincipal principal, UUID invoiceId, String channel, String recipient) {
    PosCartService.requireStaff(principal);
    rateLimit("pos:invoice:share:" + principal.pharmacyId(), 20);
    Invoice invoice = requireInvoice(principal.pharmacyId(), invoiceId);
    ShareChannel shareChannel = parseShareChannel(channel);
    String dest = validateRecipient(shareChannel, recipient);
    InvoiceSettings settings = settingsStore.getOrCreate(principal.pharmacyId());
    byte[] pdf = renderPdf(invoice, settings, settings.template());
    String pdfUrl = "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf);
    PosNotificationPort.ShareResult sent =
        notificationPort.shareInvoice(
            principal.pharmacyId(),
            invoice.id(),
            invoice.invoiceNumber(),
            shareChannel,
            dest,
            pdfUrl);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("channel", shareChannel.name());
    data.put("recipient", dest);
    data.put("message_id", sent.messageId());
    data.put("sent_at", sent.sentAt().toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getSettings(MedmatePrincipal principal) {
    PosCartService.requireStaff(principal);
    rateLimit("pos:invoice:settings:get:" + principal.pharmacyId(), 30);
    return toSettingsMap(settingsStore.getOrCreate(principal.pharmacyId()));
  }

  @Transactional
  public Map<String, Object> patchSettings(MedmatePrincipal principal, Map<String, Object> body) {
    PosCartService.requireStaff(principal);
    requireOwner(principal);
    rateLimit("pos:invoice:settings:patch:" + principal.pharmacyId(), 10);
    InvoiceSettings current = settingsStore.getOrCreate(principal.pharmacyId());
    Map<String, Object> patch = body == null ? Map.of() : body;
    Instant now = clock.instant();

    InvoiceTemplate template = current.template();
    if (patch.containsKey("template") && patch.get("template") != null) {
      template = parseTemplate(String.valueOf(patch.get("template")));
    }
    String accent = current.accentColor();
    if (patch.containsKey("accent_color") && patch.get("accent_color") != null) {
      accent = String.valueOf(patch.get("accent_color")).trim();
      if (!ACCENT.matcher(accent).matches()) {
        throw new AppException("INVALID_ACCENT_COLOR", "accent_color must be #RRGGBB", 400);
      }
    }
    String logoUrl = current.logoUrl();
    if (patch.containsKey("logo_url")) {
      logoUrl = strOrNull(patch.get("logo_url"));
    }
    String signatureUrl = current.signatureUrl();
    if (patch.containsKey("signature_url")) {
      signatureUrl = strOrNull(patch.get("signature_url"));
    }
    String documentTitle = current.documentTitle();
    if (patch.containsKey("document_title") && patch.get("document_title") != null) {
      documentTitle = String.valueOf(patch.get("document_title")).trim();
      if (documentTitle.length() > 50) {
        throw new AppException("VALIDATION_ERROR", "document_title max 50 chars", 400);
      }
    }
    String prefix = current.invoicePrefix();
    if (patch.containsKey("invoice_prefix") && patch.get("invoice_prefix") != null) {
      prefix = String.valueOf(patch.get("invoice_prefix")).trim().toUpperCase(Locale.ROOT);
      if (!PREFIX.matcher(prefix).matches()) {
        throw new AppException(
            "INVALID_PREFIX_FORMAT", "invoice_prefix must be 1-6 alphanumeric uppercase", 400);
      }
    }
    String signatory = current.signatoryLabel();
    if (patch.containsKey("signatory_label") && patch.get("signatory_label") != null) {
      signatory = String.valueOf(patch.get("signatory_label")).trim();
      if (signatory.length() > 100) {
        throw new AppException("VALIDATION_ERROR", "signatory_label max 100 chars", 400);
      }
    }
    Map<String, Object> bank = current.bankDetails();
    if (patch.containsKey("bank_details") && patch.get("bank_details") != null) {
      bank = normalizeBank(patch.get("bank_details"));
    }
    String terms = current.termsAndConditions();
    if (patch.containsKey("terms_and_conditions")) {
      terms = strOrNull(patch.get("terms_and_conditions"));
      if (terms != null && terms.length() > 1000) {
        throw new AppException("VALIDATION_ERROR", "terms_and_conditions max 1000 chars", 400);
      }
    }
    String footer = current.footerNote();
    if (patch.containsKey("footer_note")) {
      footer = strOrNull(patch.get("footer_note"));
      if (footer != null && footer.length() > 500) {
        throw new AppException("VALIDATION_ERROR", "footer_note max 500 chars", 400);
      }
    }
    boolean showMrp = boolOr(patch, "show_mrp_savings", current.showMrpSavings());
    boolean showDoctor = boolOr(patch, "show_doctor", current.showDoctor());
    boolean showHsn = boolOr(patch, "show_hsn", current.showHsn());
    boolean printBank = boolOr(patch, "print_bank_details", current.printBankDetails());

    InvoiceSettings updated =
        settingsStore.upsert(
            new InvoiceSettings(
                principal.pharmacyId(),
                template,
                accent,
                logoUrl,
                signatureUrl,
                documentTitle,
                prefix,
                signatory,
                bank,
                terms,
                footer,
                showMrp,
                showDoctor,
                showHsn,
                printBank,
                now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoice_prefix", updated.invoicePrefix());
    data.put("template", updated.template().name());
    data.put("updated_at", updated.updatedAt().toString());
    return data;
  }

  private byte[] renderPdf(Invoice invoice, InvoiceSettings settings, InvoiceTemplate template) {
    List<InvoiceItem> items = invoiceStore.listItems(invoice.id());
    PosPharmacyPort.PharmacyInfo pharmacy =
        pharmacyPort
            .findById(invoice.pharmacyId())
            .orElse(new PosPharmacyPort.PharmacyInfo("Pharmacy", null, null, null, null));
    boolean thermal = template == InvoiceTemplate.THERMAL;
    List<String> lines = new ArrayList<>();
    lines.add(settings.documentTitle() + " [" + template.name() + "]");
    lines.add(nullToEmpty(pharmacy.name()));
    if (!thermal && pharmacy.address() != null) {
      lines.add(pharmacy.address());
    }
    if (pharmacy.gstin() != null) {
      lines.add("GSTIN: " + pharmacy.gstin());
    }
    lines.add(invoice.invoiceNumber());
    lines.add(invoice.createdAt().toString());
    if (settings.showDoctor() && invoice.prescribingDoctor() != null) {
      lines.add("Dr: " + invoice.prescribingDoctor());
    }
    for (InvoiceItem item : items) {
      StringBuilder line = new StringBuilder(item.productName());
      line.append(" x").append(item.quantity());
      line.append(" ").append(MoneyMath.paiseToRupees(item.lineTotalPaise()));
      if (settings.showHsn() && item.hsnCode() != null) {
        line.append(" HSN:").append(item.hsnCode());
      }
      lines.add(line.toString());
    }
    lines.add("Grand Total: " + MoneyMath.paiseToRupees(invoice.grandTotalPaise()));
    if (settings.showMrpSavings()) {
      lines.add(
          "You saved Rs "
              + MoneyMath.paiseToRupees(invoice.mrpSavingsPaise())
              + " by shopping at "
              + nullToEmpty(pharmacy.name()));
    }
    if (settings.footerNote() != null) {
      lines.add(settings.footerNote());
    }
    return SimplePdfExporter.export(null, lines, thermal);
  }

  private Invoice requireInvoice(UUID pharmacyId, UUID invoiceId) {
    if (invoiceId == null) {
      throw new AppException("INVOICE_NOT_FOUND", "Invoice not found", 404);
    }
    return invoiceStore
        .findById(pharmacyId, invoiceId)
        .orElseThrow(() -> new AppException("INVOICE_NOT_FOUND", "Invoice not found", 404));
  }

  private Invoice requireInvoiceForRead(MedmatePrincipal principal, UUID invoiceId) {
    requireStaffOrAdminRead(principal);
    if (invoiceId == null) {
      throw new AppException("INVOICE_NOT_FOUND", "Invoice not found", 404);
    }
    if (isAdminReader(principal)) {
      return invoiceStore
          .findByIdAny(invoiceId)
          .orElseThrow(() -> new AppException("INVOICE_NOT_FOUND", "Invoice not found", 404));
    }
    return requireInvoice(principal.pharmacyId(), invoiceId);
  }

  private UUID resolvePharmacyIdForRead(MedmatePrincipal principal, UUID pharmacyIdParam) {
    requireStaffOrAdminRead(principal);
    if (isAdminReader(principal)) {
      if (pharmacyIdParam == null) {
        throw new AppException(
            "VALIDATION_ERROR", "pharmacy_id is required for admin invoice list", 400);
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
        || role == AuthRole.ADMIN_SUPPORT
        || role == AuthRole.ADMIN_COMPLIANCE) {
      return;
    }
    throw new AppException("FORBIDDEN", "Invoice read access denied", 403);
  }

  private static boolean isAdminReader(MedmatePrincipal principal) {
    AuthRole role = principal.role();
    return role == AuthRole.ADMIN_FINANCE
        || role == AuthRole.ADMIN_SUPPORT
        || role == AuthRole.ADMIN_COMPLIANCE;
  }

  private static void requireOwner(MedmatePrincipal principal) {
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Only pharmacy_owner may update invoice settings", 403);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static Map<String, Object> toListItem(InvoiceStore.InvoiceListRow row) {
    Invoice inv = row.invoice();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("invoice_id", inv.id().toString());
    m.put("invoice_number", inv.invoiceNumber());
    m.put("date", inv.createdAt().atZone(ZoneOffset.UTC).toLocalDate().toString());
    m.put("customer_name", inv.customerName());
    m.put("customer_phone", inv.customerPhone());
    m.put("channel", inv.channel().name());
    m.put("payment_method", inv.paymentMethod().name());
    m.put("items_count", row.itemsCount());
    m.put("grand_total", MoneyMath.paiseToRupees(inv.grandTotalPaise()));
    m.put("gst_total", MoneyMath.paiseToRupees(inv.gstTotalPaise()));
    m.put("payment_status", inv.paymentStatus().name());
    return m;
  }

  private static List<Map<String, Object>> toExportRows(List<InvoiceStore.InvoiceListRow> rows) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (InvoiceStore.InvoiceListRow row : rows) {
      out.add(toListItem(row));
    }
    return out;
  }

  private static List<Map<String, Object>> toLineItems(List<InvoiceItem> items, boolean showHsn) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (InvoiceItem item : items) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("product_name", item.productName());
      if (showHsn) {
        m.put("hsn_code", item.hsnCode());
      }
      m.put("batch_number", item.batchNumber());
      m.put("expiry_date", item.expiryDate() == null ? null : item.expiryDate().toString());
      m.put("pack_size", item.packSize());
      m.put("quantity", item.quantity());
      m.put("unit_price", MoneyMath.paiseToRupees(item.unitPricePaise()));
      m.put("line_subtotal", MoneyMath.paiseToRupees(item.lineSubtotalPaise()));
      m.put("gst_pct", item.gstPct());
      m.put("gst_amount", MoneyMath.paiseToRupees(item.gstAmountPaise()));
      m.put("line_total", MoneyMath.paiseToRupees(item.lineTotalPaise()));
      out.add(m);
    }
    return out;
  }

  private static Map<String, Object> toSettingsMap(InvoiceSettings s) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("template", s.template().name());
    data.put("accent_color", s.accentColor());
    data.put("logo_url", s.logoUrl());
    data.put("signature_url", s.signatureUrl());
    data.put("document_title", s.documentTitle());
    data.put("invoice_prefix", s.invoicePrefix());
    data.put("signatory_label", s.signatoryLabel());
    data.put("bank_details", s.bankDetails());
    data.put("terms_and_conditions", s.termsAndConditions());
    data.put("footer_note", s.footerNote());
    data.put("show_mrp_savings", s.showMrpSavings());
    data.put("show_doctor", s.showDoctor());
    data.put("show_hsn", s.showHsn());
    data.put("print_bank_details", s.printBankDetails());
    return data;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizeBank(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new AppException("VALIDATION_ERROR", "bank_details must be an object", 400);
    }
    Map<String, Object> bank = new LinkedHashMap<>();
    Object ifsc = map.get("ifsc_code");
    if (ifsc != null) {
      String code = String.valueOf(ifsc).trim().toUpperCase(Locale.ROOT);
      if (code.length() != 11) {
        throw new AppException("INVALID_IFSC_CODE", "ifsc_code must be exactly 11 characters", 400);
      }
      bank.put("ifsc_code", code);
    }
    putIfPresent(bank, map, "bank_name");
    putIfPresent(bank, map, "account_number");
    putIfPresent(bank, map, "upi_id");
    return bank;
  }

  private static void putIfPresent(Map<String, Object> dest, Map<?, ?> src, String key) {
    if (src.containsKey(key) && src.get(key) != null) {
      dest.put(key, String.valueOf(src.get(key)).trim());
    }
  }

  private static InvoiceTemplate resolveTemplate(String override, InvoiceTemplate fallback) {
    if (override == null || override.isBlank()) {
      return fallback;
    }
    return parseTemplate(override);
  }

  private static InvoiceTemplate parseTemplate(String raw) {
    try {
      return InvoiceTemplate.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid template", 400);
    }
  }

  private static ShareChannel parseShareChannel(String channel) {
    if (channel == null || channel.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "channel is required", 400);
    }
    try {
      return ShareChannel.valueOf(channel.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid channel", 400);
    }
  }

  private static String validateRecipient(ShareChannel channel, String recipient) {
    if (recipient == null || recipient.isBlank()) {
      throw new AppException("INVALID_RECIPIENT", "recipient_phone_or_email is required", 400);
    }
    String value = recipient.trim();
    if (channel == ShareChannel.EMAIL) {
      if (!EMAIL.matcher(value).matches()) {
        throw new AppException("INVALID_RECIPIENT", "Invalid email recipient", 400);
      }
      return value;
    }
    String phone = value.replaceAll("[\\s-]", "");
    if (!PHONE.matcher(phone).matches()) {
      throw new AppException("INVALID_RECIPIENT", "Invalid phone recipient", 400);
    }
    return phone;
  }

  private static String normalizeEnum(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean boolOr(Map<String, Object> patch, String key, boolean fallback) {
    if (!patch.containsKey(key) || patch.get(key) == null) {
      return fallback;
    }
    Object v = patch.get(key);
    if (v instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(v));
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

  /** Expose for tests that need a synthetic message id style. */
  static String newMessageId(String channel) {
    return channel.toLowerCase(Locale.ROOT) + "_msg_" + Ids.newId();
  }
}
