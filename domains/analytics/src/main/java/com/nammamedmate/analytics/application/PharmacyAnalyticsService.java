package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.AnalyticsPlanPort;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.AccountsData;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.ChannelTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.FinancialTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.GstSlab;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.PaymentMixRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.ProductRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.SaleRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.SaleTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.TopItem;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.analytics.domain.PeriodResolver;
import com.nammamedmate.analytics.domain.PeriodResolver.DateWindow;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EPIC-016 STORY-004 pharmacy analytics. */
@Service
public class PharmacyAnalyticsService {

  public static final String UPGRADE_URL = "/pharmacy/subscription/plans";
  static final int ASYNC_EXPORT_THRESHOLD = 500;
  static final int DEFAULT_PAGE = 1;
  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;
  static final int TOP_ITEMS = 10;

  public static final List<CatalogueEntry> CATALOGUE =
      List.of(
          new CatalogueEntry(
              "GSTR-1-DRAFT",
              "GSTR-1 Draft",
              "GST",
              List.of(
                  "invoice_number",
                  "customer_gstin",
                  "taxable_value",
                  "cgst",
                  "sgst",
                  "igst",
                  "total")),
          new CatalogueEntry(
              "GSTR-3B-DRAFT",
              "GSTR-3B Summary",
              "GST",
              List.of("slab_pct", "taxable_value", "output_gst", "input_itc", "net")),
          new CatalogueEntry(
              "SALES-REGISTER",
              "Sales Register",
              "TRANSACTION",
              List.of("invoice_number", "sale_date", "channel", "total_paise", "payment_method")),
          new CatalogueEntry(
              "PURCHASE-REG",
              "Purchase Register",
              "TRANSACTION",
              List.of("invoice_number", "date", "distributor", "total_paise", "gst_paise")),
          new CatalogueEntry(
              "STOCK-SUMMARY",
              "Stock Summary",
              "ITEM",
              List.of("product_id", "name", "stock_remaining", "cost_value_paise")),
          new CatalogueEntry(
              "DEAD-STOCK",
              "Dead Stock Report",
              "ITEM",
              List.of("product_id", "name", "stock_remaining", "dead_stock_flag")),
          new CatalogueEntry(
              "PARTY-LEDGER",
              "Party Ledger",
              "PARTY",
              List.of("party", "debit_paise", "credit_paise", "balance_paise")),
          new CatalogueEntry(
              "DAYBOOK",
              "Day Book",
              "SUMMARY",
              List.of("date", "type", "reference", "debit_paise", "credit_paise", "balance_paise")),
          new CatalogueEntry(
              "PL-STATEMENT",
              "Profit & Loss Statement",
              "SUMMARY",
              List.of("line", "amount_paise")));

  private static final Set<String> REPORT_IDS =
      CATALOGUE.stream().map(CatalogueEntry::reportId).collect(java.util.stream.Collectors.toSet());

  private final PharmacyAnalyticsStore store;
  private final AnalyticsPlanPort planPort;
  private final AnalyticsExportPort exportPort;
  private final Clock clock;

  public PharmacyAnalyticsService(
      PharmacyAnalyticsStore store,
      AnalyticsPlanPort planPort,
      AnalyticsExportPort exportPort,
      Clock clock) {
    this.store = store;
    this.planPort = planPort;
    this.exportPort = exportPort;
    this.clock = clock;
  }

  public record CatalogueEntry(String reportId, String name, String group, List<String> columns) {
    public CatalogueEntry {
      columns = List.copyOf(columns);
    }
  }

  public record PageResult(Map<String, Object> data, PaginationMeta meta) {
    public PageResult {
      data = Map.copyOf(data);
    }
  }

  public Map<String, Object> overview(
      MedmatePrincipal principal,
      UUID pharmacyIdParam,
      String period,
      String dateFrom,
      String dateTo) {
    UUID pharmacyId = resolvePharmacyId(principal, pharmacyIdParam, false);
    requirePlan(pharmacyId);
    DateWindow window = PeriodResolver.resolvePharmacy(period, dateFrom, dateTo, clock);

    FinancialTotals fin =
        store.financials(pharmacyId, window.fromInclusive(), window.toExclusive());
    List<TopItem> tops =
        store.topItems(pharmacyId, window.fromInclusive(), window.toExclusive(), TOP_ITEMS);
    ChannelTotals channel =
        store.channelTotals(pharmacyId, window.fromInclusive(), window.toExclusive());
    List<PaymentMixRow> payments =
        store.paymentMix(pharmacyId, window.fromInclusive(), window.toExclusive());

    long channelTotal = channel.onlineRevenuePaise() + channel.counterRevenuePaise();
    Map<String, Object> channelMix = new LinkedHashMap<>();
    channelMix.put(
        "online_pct", AnalyticsMath.ratioPct(channel.onlineRevenuePaise(), channelTotal));
    channelMix.put(
        "counter_pct", AnalyticsMath.ratioPct(channel.counterRevenuePaise(), channelTotal));

    long payTotal = payments.stream().mapToLong(PaymentMixRow::revenuePaise).sum();
    List<Map<String, Object>> paymentMix = new ArrayList<>();
    for (PaymentMixRow p : payments) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("method", normalizePaymentLabel(p.method()));
      row.put("pct", AnalyticsMath.ratioPct(p.revenuePaise(), payTotal));
      paymentMix.add(row);
    }

    Map<String, Object> financials = new LinkedHashMap<>();
    financials.put("net_revenue_paise", fin.netRevenuePaise());
    financials.put("gross_profit_paise", fin.grossProfitPaise());
    financials.put(
        "margin_pct", AnalyticsMath.netMarginPct(fin.netRevenuePaise(), fin.cogsPaise()));
    financials.put("units_sold", fin.unitsSold());
    financials.put("net_gst_paise", fin.netGstPaise());
    if (fin.cogsIncomplete()) {
      financials.put("cogs_data_incomplete", true);
    }

    List<Map<String, Object>> topItems = new ArrayList<>();
    for (TopItem t : tops) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("product_id", t.productId().toString());
      row.put("name", t.name());
      row.put("units_sold", t.unitsSold());
      row.put("revenue_paise", t.revenuePaise());
      topItems.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("period", window.period());
    data.put("date_from", window.fromDate().toString());
    data.put("date_to", window.toDate().toString());
    data.put("financials", financials);
    data.put("top_items", topItems);
    data.put("channel_mix", channelMix);
    data.put("payment_mix", paymentMix);
    return data;
  }

  public PageResult salesRegister(
      MedmatePrincipal principal,
      UUID pharmacyIdParam,
      String period,
      String dateFrom,
      String dateTo,
      String channel,
      String paymentMethod,
      Integer pageRaw,
      Integer limitRaw) {
    UUID pharmacyId = resolvePharmacyId(principal, pharmacyIdParam, false);
    requirePlan(pharmacyId);
    DateWindow window = PeriodResolver.resolvePharmacy(period, dateFrom, dateTo, clock);
    int page = pageRaw == null ? DEFAULT_PAGE : pageRaw;
    int limit = limitRaw == null ? DEFAULT_LIMIT : Math.min(limitRaw, MAX_LIMIT);
    if (page < 1 || limit < 1) {
      throw new AppException("VALIDATION_ERROR", "page and limit must be >= 1", 400);
    }
    String ch = blankToNull(channel);
    String pm = blankToNull(paymentMethod);
    int offset = (page - 1) * limit;

    SaleTotals totals =
        store.saleTotals(pharmacyId, window.fromInclusive(), window.toExclusive(), ch, pm);
    List<SaleRow> sales =
        store.sales(
            pharmacyId, window.fromInclusive(), window.toExclusive(), ch, pm, offset, limit);

    List<Map<String, Object>> rows = new ArrayList<>();
    for (SaleRow s : sales) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sale_id", s.saleId().toString());
      row.put("invoice_number", s.invoiceNumber());
      row.put("sale_date", s.saleDate().toString());
      row.put("channel", s.channel());
      row.put("customer_name", s.customerName());
      row.put("items_count", s.itemsCount());
      row.put("subtotal_paise", s.subtotalPaise());
      row.put("gst_paise", s.gstPaise());
      row.put("total_paise", s.totalPaise());
      row.put("payment_method", s.paymentMethod());
      row.put("status", s.status());
      rows.add(row);
    }

    Map<String, Object> totalsMap = new LinkedHashMap<>();
    totalsMap.put("total_sales", totals.totalSales());
    totalsMap.put("total_revenue_paise", totals.totalRevenuePaise());
    totalsMap.put("total_gst_paise", totals.totalGstPaise());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("sales", rows);
    data.put("totals", totalsMap);
    return new PageResult(data, PaginationMeta.of(page, limit, totals.totalSales()));
  }

  public PageResult products(
      MedmatePrincipal principal,
      UUID pharmacyIdParam,
      String period,
      String dateFrom,
      String dateTo,
      String sortRaw,
      String orderRaw,
      Boolean deadStockOnly,
      Integer pageRaw,
      Integer limitRaw) {
    UUID pharmacyId = resolvePharmacyId(principal, pharmacyIdParam, false);
    requirePlan(pharmacyId);
    DateWindow window = PeriodResolver.resolvePharmacy(period, dateFrom, dateTo, clock);
    String sort = normalizeSort(sortRaw);
    String order = normalizeOrder(orderRaw);
    boolean deadOnly = Boolean.TRUE.equals(deadStockOnly);
    int page = pageRaw == null ? DEFAULT_PAGE : pageRaw;
    int limit = limitRaw == null ? DEFAULT_LIMIT : Math.min(limitRaw, MAX_LIMIT);
    if (page < 1 || limit < 1) {
      throw new AppException("VALIDATION_ERROR", "page and limit must be >= 1", 400);
    }
    int offset = (page - 1) * limit;

    long total =
        store.countProducts(pharmacyId, window.fromInclusive(), window.toExclusive(), deadOnly);
    List<ProductRow> products =
        store.products(
            pharmacyId,
            window.fromInclusive(),
            window.toExclusive(),
            sort,
            order,
            deadOnly,
            offset,
            limit);

    List<Map<String, Object>> rows = new ArrayList<>();
    for (ProductRow p : products) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("product_id", p.productId().toString());
      row.put("name", p.name());
      row.put("category", p.category());
      row.put("units_sold", p.unitsSold());
      row.put("revenue_paise", p.revenuePaise());
      row.put("cogs_paise", p.cogsPaise());
      row.put("profit_paise", p.profitPaise());
      row.put("margin_pct", p.marginPct());
      row.put("stock_remaining", p.stockRemaining());
      row.put("dead_stock_flag", p.deadStockFlag());
      if (p.cogsMissing()) {
        row.put("cogs_missing", true);
      }
      rows.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("products", rows);
    return new PageResult(data, PaginationMeta.of(page, limit, total));
  }

  public Map<String, Object> accountsGst(
      MedmatePrincipal principal,
      UUID pharmacyIdParam,
      String period,
      String dateFrom,
      String dateTo) {
    UUID pharmacyId = resolvePharmacyId(principal, pharmacyIdParam, true);
    requirePlan(pharmacyId);
    DateWindow window = PeriodResolver.resolvePharmacy(period, dateFrom, dateTo, clock);
    AccountsData acc = store.accounts(pharmacyId, window.fromInclusive(), window.toExclusive());

    long netGst = acc.outputGstPaise() - acc.inputItcPaise();
    long netProfit = acc.grossProfitPaise() - acc.operatingExpensesPaise() - netGst;

    Map<String, Object> pl = new LinkedHashMap<>();
    pl.put("revenue_paise", acc.revenuePaise());
    pl.put("cogs_paise", acc.cogsPaise());
    pl.put("gross_profit_paise", acc.grossProfitPaise());
    pl.put("operating_expenses_paise", acc.operatingExpensesPaise());
    pl.put("net_gst_payable_paise", netGst);
    pl.put("net_profit_paise", netProfit);

    List<Map<String, Object>> slabs = new ArrayList<>();
    long slabOutputSum = 0L;
    for (GstSlab s : acc.slabs()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("slab_pct", s.slabPct());
      row.put("taxable_value_paise", s.taxableValuePaise());
      row.put("output_gst_paise", s.outputGstPaise());
      row.put("input_itc_paise", s.inputItcPaise());
      row.put("net_paise", s.netPaise());
      slabs.add(row);
      slabOutputSum += s.outputGstPaise();
    }

    Map<String, Object> gst = new LinkedHashMap<>();
    gst.put("output_gst_paise", acc.outputGstPaise());
    gst.put("input_itc_paise", acc.inputItcPaise());
    gst.put("net_payable_paise", netGst);
    gst.put("slab_breakdown", slabs);

    Map<String, Object> cash = new LinkedHashMap<>();
    cash.put("total_collections_paise", acc.cashCollectedPaise() + acc.digitalCollectedPaise());
    cash.put("cash_collected_paise", acc.cashCollectedPaise());
    cash.put("digital_collected_paise", acc.digitalCollectedPaise());

    Map<String, Object> purchases = new LinkedHashMap<>();
    purchases.put("total_purchases_paise", acc.totalPurchasesPaise());
    purchases.put("gst_on_purchases_paise", acc.gstOnPurchasesPaise());

    long running = 0L;
    List<Map<String, Object>> dayBook = new ArrayList<>();
    for (var r : acc.dayBook()) {
      running = running + r.creditPaise() - r.debitPaise();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", r.date().toString());
      row.put("type", r.type());
      row.put("reference", r.reference());
      row.put("description", r.description());
      row.put("debit_paise", r.debitPaise());
      row.put("credit_paise", r.creditPaise());
      row.put("balance_paise", running);
      dayBook.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pl_card", pl);
    data.put("gst_liability", gst);
    data.put("cash_summary", cash);
    data.put("purchases_summary", purchases);
    data.put("day_book", dayBook);
    if (acc.cogsIncomplete() || slabOutputSum != acc.outputGstPaise()) {
      data.put("data_warning", true);
    }
    return data;
  }

  public Map<String, Object> reportsCatalogue(MedmatePrincipal principal, UUID pharmacyIdParam) {
    UUID pharmacyId = resolvePharmacyId(principal, pharmacyIdParam, false);
    requirePlan(pharmacyId);
    Set<String> favorites = store.favoriteReportIds(pharmacyId);
    List<Map<String, Object>> reports = new ArrayList<>();
    for (CatalogueEntry e : CATALOGUE) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("report_id", e.reportId());
      row.put("name", e.name());
      row.put("group", e.group());
      row.put("is_favorite", favorites.contains(e.reportId()));
      reports.add(row);
    }
    return Map.of("reports", reports);
  }

  @Transactional
  public Map<String, Object> setFavorite(
      MedmatePrincipal principal, UUID pharmacyIdParam, String reportId, Boolean isFavorite) {
    // ownerOnly=false so staff reaches requireOwnerOnly → FORBIDDEN (AC-008)
    UUID pharmacyId = resolvePharmacyId(principal, pharmacyIdParam, false);
    requirePlan(pharmacyId);
    requireOwnerOnly(principal);
    String id = normalizeReportId(reportId);
    if (!REPORT_IDS.contains(id)) {
      throw new AppException("REPORT_NOT_FOUND", "report_id does not exist", 404);
    }
    if (isFavorite == null) {
      throw new AppException("VALIDATION_ERROR", "is_favorite is required", 400);
    }
    store.setFavorite(pharmacyId, id, isFavorite);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("report_id", id);
    data.put("is_favorite", isFavorite);
    return data;
  }

  public Map<String, Object> runReport(
      MedmatePrincipal principal,
      UUID pharmacyIdParam,
      String reportId,
      String period,
      String dateFrom,
      String dateTo,
      String export) {
    UUID pharmacyId = resolvePharmacyId(principal, pharmacyIdParam, false);
    requirePlan(pharmacyId);
    String id = normalizeReportId(reportId);
    CatalogueEntry entry =
        CATALOGUE.stream()
            .filter(e -> e.reportId().equals(id))
            .findFirst()
            .orElseThrow(
                () -> new AppException("REPORT_NOT_FOUND", "report_id does not exist", 404));
    DateWindow window = PeriodResolver.resolvePharmacy(period, dateFrom, dateTo, clock);
    List<List<Object>> rows =
        store.reportRows(pharmacyId, id, window.fromInclusive(), window.toExclusive());

    List<String> columns = entry.columns();
    Map<String, Object> totals = reportTotals(id, rows);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("report_id", id);
    data.put("name", entry.name());
    data.put("period_from", window.fromDate().toString());
    data.put("period_to", window.toDate().toString());
    data.put("columns", columns);
    data.put("totals", totals);

    String exportFmt = export == null ? null : export.trim().toLowerCase(Locale.ROOT);
    if (exportFmt != null && !exportFmt.isBlank()) {
      if (!"excel".equals(exportFmt) && !"pdf".equals(exportFmt)) {
        throw new AppException("VALIDATION_ERROR", "export must be excel or pdf", 400);
      }
      if (rows.size() > ASYNC_EXPORT_THRESHOLD) {
        String objectKey =
            StorageObjectKeys.export(
                "pharmacy-analytics-" + pharmacyId + "-" + id + "." + exportExt(exportFmt));
        byte[] bytes = renderExport(columns, rows, exportFmt);
        exportPort.put(objectKey, bytes, contentType(exportFmt));
        var signed = exportPort.signedGet(objectKey, Duration.ofHours(1));
        data.put("rows", List.of());
        data.put("export_url", signed.url());
        data.put("export_async", true);
        return data;
      }
    }

    data.put("rows", rows);
    data.put("export_url", null);
    return data;
  }

  private void requirePlan(UUID pharmacyId) {
    if (!planPort.allowsPharmacyAnalytics(pharmacyId)) {
      throw new AppException(
          "PLAN_UPGRADE_REQUIRED",
          "Pharmacy analytics requires Growth plan or higher",
          403,
          null,
          Map.of("upgrade_url", UPGRADE_URL));
    }
  }

  private static void requireOwnerOnly(MedmatePrincipal principal) {
    AuthRole role = principal.role();
    if (role == AuthRole.PHARMACY_OWNER
        || role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS) {
      return;
    }
    throw new AppException("FORBIDDEN", "Only pharmacy owner can toggle report favorites", 403);
  }

  private UUID resolvePharmacyId(
      MedmatePrincipal principal, UUID pharmacyIdParam, boolean ownerOnlyEndpoint) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role == AuthRole.ADMIN_SUPER || role == AuthRole.ADMIN_OPERATIONS) {
      if (pharmacyIdParam == null) {
        throw new AppException("VALIDATION_ERROR", "pharmacy_id is required for admin", 400);
      }
      return pharmacyIdParam;
    }
    if (role == AuthRole.PHARMACY_OWNER || role == AuthRole.PHARMACY_STAFF) {
      if (ownerOnlyEndpoint && role == AuthRole.PHARMACY_STAFF) {
        throw new AppException("FORBIDDEN", "Owner access required", 403);
      }
      if (principal.pharmacyId() == null) {
        throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
      }
      if (pharmacyIdParam != null && !pharmacyIdParam.equals(principal.pharmacyId())) {
        throw new AppException("FORBIDDEN", "Accessing another pharmacy's data", 403);
      }
      return principal.pharmacyId();
    }
    throw new AppException("FORBIDDEN", "Pharmacy analytics access denied", 403);
  }

  private static String normalizeSort(String sortRaw) {
    if (sortRaw == null || sortRaw.isBlank()) {
      return "revenue";
    }
    String s = sortRaw.trim().toLowerCase(Locale.ROOT);
    return switch (s) {
      case "units_sold", "revenue", "margin_pct", "profit" -> s;
      default -> throw new AppException("VALIDATION_ERROR", "invalid sort", 400);
    };
  }

  private static String normalizeOrder(String orderRaw) {
    if (orderRaw == null || orderRaw.isBlank()) {
      return "desc";
    }
    String o = orderRaw.trim().toLowerCase(Locale.ROOT);
    if (!"asc".equals(o) && !"desc".equals(o)) {
      throw new AppException("VALIDATION_ERROR", "order must be asc or desc", 400);
    }
    return o;
  }

  private static String normalizeReportId(String reportId) {
    if (reportId == null || reportId.isBlank()) {
      throw new AppException("REPORT_NOT_FOUND", "report_id does not exist", 404);
    }
    return reportId.trim().toUpperCase(Locale.ROOT);
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizePaymentLabel(String method) {
    if (method == null) {
      return "OTHER";
    }
    // Story sample uses WALLET; POS stores CREDIT/COD etc.
    if ("CREDIT".equalsIgnoreCase(method) || "COD".equalsIgnoreCase(method)) {
      return "WALLET";
    }
    return method.toUpperCase(Locale.ROOT);
  }

  private static Map<String, Object> reportTotals(String reportId, List<List<Object>> rows) {
    Map<String, Object> totals = new LinkedHashMap<>();
    if ("GSTR-1-DRAFT".equals(reportId)) {
      long taxable = 0, cgst = 0, sgst = 0, igst = 0, total = 0;
      for (List<Object> r : rows) {
        if (r.size() >= 7) {
          taxable += toLong(r.get(2));
          cgst += toLong(r.get(3));
          sgst += toLong(r.get(4));
          igst += toLong(r.get(5));
          total += toLong(r.get(6));
        }
      }
      totals.put("taxable_value", taxable);
      totals.put("cgst", cgst);
      totals.put("sgst", sgst);
      totals.put("igst", igst);
      totals.put("total", total);
    } else {
      totals.put("row_count", rows.size());
    }
    return totals;
  }

  private static long toLong(Object o) {
    return o instanceof Number n ? n.longValue() : 0L;
  }

  private static String exportExt(String fmt) {
    return "excel".equals(fmt) ? "xlsx" : "pdf";
  }

  private static String contentType(String fmt) {
    return "excel".equals(fmt)
        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        : "application/pdf";
  }

  /** ponytail: CSV bytes labeled as excel/pdf until real workbook/PDF renderer is wired. */
  private static byte[] renderExport(List<String> columns, List<List<Object>> rows, String fmt) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.join(",", columns)).append('\n');
    for (List<Object> row : rows) {
      List<String> cells = new ArrayList<>();
      for (Object c : row) {
        cells.add(c == null ? "" : c.toString().replace(',', ' '));
      }
      sb.append(String.join(",", cells)).append('\n');
    }
    sb.append("#format=").append(fmt).append('\n');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }
}
