package com.nammamedmate.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.payment.application.port.out.TaxFilingObjectStore;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort.PharmacyTaxProfile;
import com.nammamedmate.payment.application.port.out.TaxStorePort;
import com.nammamedmate.payment.application.port.out.TaxStorePort.PharmacyCommissionRow;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TaxFilingRecord;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsMonthTotals;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsPage;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsRegisterRecord;
import com.nammamedmate.payment.application.port.out.TcsRegisterWriterPort;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.payment.domain.TaxFilingStatuses;
import com.nammamedmate.payment.domain.TaxFilingTypes;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EPIC-012 STORY-007 tax liability panel, filings tracker, TCS register, GSTR-8 export. */
@Service
public class TaxFacadeService implements TcsRegisterWriterPort {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  static final long TDS_THRESHOLD_PAISE = 50_000_000L; // ₹5 lakh
  static final BigDecimal TDS_RATE_WITH_PAN = new BigDecimal("0.0075");
  static final BigDecimal TDS_RATE_WITHOUT_PAN = new BigDecimal("0.01");
  static final BigDecimal OUTPUT_GST_RATE = new BigDecimal("0.18");
  static final String SAC_CODE = "9983";
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;
  private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

  private final TaxStorePort store;
  private final TaxPharmacyProfilePort pharmacies;
  private final TaxFilingObjectStore objects;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public TaxFacadeService(
      TaxStorePort store,
      TaxPharmacyProfilePort pharmacies,
      TaxFilingObjectStore objects,
      ObjectMapper objectMapper,
      Clock clock) {
    this.store = store;
    this.pharmacies = pharmacies;
    this.objects = objects;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Override
  @Transactional
  public void recordReleasedSettlement(
      UUID settlementId, UUID pharmacyId, String month, long gmvPaise, long tcsPaise, Instant now) {
    PharmacyTaxProfile profile =
        pharmacies.find(pharmacyId).orElse(new PharmacyTaxProfile(pharmacyId, "", "", ""));
    store.upsertTcsOnRelease(
        pharmacyId,
        month,
        profile.businessName(),
        profile.gstin(),
        profile.pan(),
        settlementId,
        gmvPaise,
        tcsPaise,
        now == null ? clock.instant() : now);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> taxPanel(MedmatePrincipal principal, String monthParam) {
    requireTaxRole(principal);
    YearMonth month = parseMonth(monthParam, clock);
    String monthKey = month.toString();
    LocalDate today = LocalDate.now(clock.withZone(IST));

    TcsMonthTotals tcs = store.tcsTotals(monthKey);
    long half = tcs.totalTcsPaise() / 2;
    long sgst = tcs.totalTcsPaise() - half;

    LocalDate monthStart = month.atDay(1);
    LocalDate monthEnd = month.atEndOfMonth();
    long commissionMonth = store.totalCommissionPaise(monthStart, monthEnd);
    long outputGst =
        BigDecimal.valueOf(commissionMonth)
            .multiply(OUTPUT_GST_RATE)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();

    Instant from = monthStart.atStartOfDay(IST).toInstant();
    Instant to = month.plusMonths(1).atDay(1).atStartOfDay(IST).toInstant();
    long gatewayFees = store.gatewayFeesPaise(from, to);
    long gstOnGateway =
        BigDecimal.valueOf(gatewayFees)
            .multiply(OUTPUT_GST_RATE)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
    long otherInput = 0L;
    long totalInput = gstOnGateway + otherInput;
    long netGst = outputGst - totalInput;

    TdsSnapshot tds = computeTds194o(month);

    TaxFilingRecord gstr8 =
        store.findFilingByTypeAndPeriod(TaxFilingTypes.GSTR_8, monthKey).orElse(null);
    LocalDate due = gstr8DueDate(month);
    String gstr8Status =
        gstr8 == null
            ? TaxFilingStatuses.PENDING
            : TaxFilingStatuses.displayStatus(gstr8.status(), gstr8.dueDate(), today);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("month", monthKey);
    data.put(
        "tcs_collected",
        Map.of(
            "total_gmv",
            MoneyFormats.paiseToRupees(tcs.totalGmvPaise()),
            "tcs_amount",
            MoneyFormats.paiseToRupees(tcs.totalTcsPaise()),
            "cgst_component",
            MoneyFormats.paiseToRupees(half),
            "sgst_component",
            MoneyFormats.paiseToRupees(sgst),
            "pharmacies_count",
            tcs.pharmaciesCount()));
    data.put(
        "tds_194o",
        Map.of(
            "eligible_pharmacies_count",
            tds.eligibleCount(),
            "total_commission_eligible",
            MoneyFormats.paiseToRupees(tds.eligibleCommissionPaise()),
            "tds_amount",
            MoneyFormats.paiseToRupees(tds.tdsPaise()),
            "note",
            "Only pharmacies exceeding Rs 5L annual commission threshold"));
    data.put(
        "output_gst_on_commission",
        Map.of(
            "total_commission",
            MoneyFormats.paiseToRupees(commissionMonth),
            "gst_rate_pct",
            18.0,
            "output_gst",
            MoneyFormats.paiseToRupees(outputGst),
            "sac_code",
            SAC_CODE));
    data.put(
        "input_gst_claimable",
        Map.of(
            "gateway_fees_with_gst",
            MoneyFormats.paiseToRupees(gatewayFees + gstOnGateway),
            "gst_on_gateway_fees",
            MoneyFormats.paiseToRupees(gstOnGateway),
            "other_input_gst",
            MoneyFormats.paiseToRupees(otherInput),
            "total_input_gst",
            MoneyFormats.paiseToRupees(totalInput)));
    data.put("net_gst_payable", MoneyFormats.paiseToRupees(netGst));
    data.put("gstr8_due_date", due.toString());
    data.put("gstr8_status", gstr8Status);
    return data;
  }

  @Transactional
  public Map<String, Object> listFilings(MedmatePrincipal principal, Integer year, String status) {
    requireTaxRole(principal);
    LocalDate today = LocalDate.now(clock.withZone(IST));
    ensureRecentFilings(today);

    String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
    if (statusFilter != null
        && !List.of(TaxFilingStatuses.PENDING, TaxFilingStatuses.FILED, TaxFilingStatuses.OVERDUE)
            .contains(statusFilter)) {
      throw new AppException("INVALID_STATUS", "status must be PENDING|FILED|OVERDUE", 400);
    }

    List<TaxFilingRecord> rows = store.listFilings(year, statusFilter);
    List<Map<String, Object>> filings = new ArrayList<>();
    for (TaxFilingRecord row : rows) {
      String display = TaxFilingStatuses.displayStatus(row.status(), row.dueDate(), today);
      if (statusFilter != null && !statusFilter.equals(display)) {
        continue;
      }
      filings.add(toFilingMap(row, display));
    }
    return Map.of("filings", filings);
  }

  @Transactional
  public Map<String, Object> generate(MedmatePrincipal principal, UUID filingId, String format) {
    requireTaxRole(principal);
    String fmt = format == null ? "JSON" : format.trim().toUpperCase();
    if (!"JSON".equals(fmt) && !"CSV".equals(fmt)) {
      throw new AppException("INVALID_FORMAT", "format must be JSON or CSV", 422);
    }
    TaxFilingRecord filing =
        store
            .findFiling(filingId)
            .orElseThrow(() -> new AppException("FILING_NOT_FOUND", "Filing not found", 404));
    if (TaxFilingStatuses.FILED.equals(filing.status())) {
      throw new AppException(
          "FILING_ALREADY_FILED", "Filing is in FILED state; re-generation blocked", 409);
    }
    if (!TaxFilingTypes.GSTR_8.equals(filing.filingType())
        && !TaxFilingTypes.TDS_194O.equals(filing.filingType())) {
      throw new AppException(
          "DATA_NOT_AVAILABLE", "Export not supported for this filing type", 422);
    }
    YearMonth current = YearMonth.now(clock.withZone(IST));
    if (TaxFilingTypes.GSTR_8.equals(filing.filingType())) {
      YearMonth periodMonth = YearMonth.parse(filing.period());
      if (!periodMonth.isBefore(current)) {
        throw new AppException(
            "DATA_NOT_AVAILABLE", "Period data not yet available (current month not closed)", 422);
      }
    }

    Instant now = clock.instant();
    long totalTcs;
    int recordCount;
    byte[] body;
    String contentType;
    String ext;

    if (TaxFilingTypes.GSTR_8.equals(filing.filingType())) {
      List<TcsRegisterRecord> entries = store.listTcsAll(filing.period());
      totalTcs = entries.stream().mapToLong(TcsRegisterRecord::tcsCollectedPaise).sum();
      recordCount = entries.size();
      if ("CSV".equals(fmt)) {
        body = toGstr8Csv(entries).getBytes(StandardCharsets.UTF_8);
        contentType = "text/csv";
        ext = "csv";
      } else {
        body = toGstr8Json(filing.period(), entries).getBytes(StandardCharsets.UTF_8);
        contentType = "application/json";
        ext = "json";
      }
      store.linkTcsToFiling(filing.period(), filingId, now);
    } else {
      YearMonth anchor = quarterEndMonth(filing.period());
      TdsSnapshot tds = computeTds194o(anchor);
      totalTcs = tds.tdsPaise();
      recordCount = tds.eligibleCount();
      if ("CSV".equals(fmt)) {
        body = ("pharmacy_id,commission_paise,tds_paise\n").getBytes(StandardCharsets.UTF_8);
        contentType = "text/csv";
        ext = "csv";
      } else {
        try {
          body =
              objectMapper.writeValueAsBytes(
                  Map.of(
                      "filing_type",
                      TaxFilingTypes.TDS_194O,
                      "period",
                      filing.period(),
                      "eligible_pharmacies_count",
                      tds.eligibleCount(),
                      "tds_amount_paise",
                      tds.tdsPaise()));
        } catch (JsonProcessingException e) {
          throw new IllegalStateException(e);
        }
        contentType = "application/json";
        ext = "json";
      }
    }

    String objectName =
        "tax/"
            + filing.filingType().toLowerCase().replace('-', '_')
            + "_"
            + filing.period().replace('-', '_')
            + "_"
            + filingId
            + "."
            + ext;
    String key = StorageObjectKeys.export(objectName);
    objects.put(key, body, contentType);
    String url = objects.createDownloadUrl(key, DOWNLOAD_TTL);
    Instant expiresAt = now.plus(DOWNLOAD_TTL);

    try {
      String meta =
          objectMapper.writeValueAsString(
              Map.of(
                  "url", url,
                  "key", key,
                  "format", fmt,
                  "generated_at", now.toString()));
      store.appendGeneratedFile(filingId, meta, now);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("filing_id", filingId.toString());
    data.put("filing_type", filing.filingType());
    data.put("period", filing.period());
    data.put("format", fmt);
    data.put("download_url", url);
    data.put("expires_at", expiresAt.toString());
    data.put("record_count", recordCount);
    data.put("total_tcs_in_file", MoneyFormats.paiseToRupees(totalTcs));
    data.put("generated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> markFiled(
      MedmatePrincipal principal,
      UUID filingId,
      Instant filedAt,
      String referenceNumber,
      String notes) {
    requireTaxRole(principal);
    if (referenceNumber == null || referenceNumber.isBlank()) {
      throw new AppException("REFERENCE_REQUIRED", "reference_number is mandatory", 422);
    }
    TaxFilingRecord filing =
        store
            .findFiling(filingId)
            .orElseThrow(() -> new AppException("FILING_NOT_FOUND", "Filing not found", 404));
    if (TaxFilingStatuses.FILED.equals(filing.status())) {
      throw new AppException("ALREADY_FILED", "Filing already marked as FILED", 409);
    }
    Instant when = filedAt == null ? clock.instant() : filedAt;
    Instant now = clock.instant();
    store.markFiled(filingId, when, referenceNumber.trim(), notes, principal.subject(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("filing_id", filingId.toString());
    data.put("filing_type", filing.filingType());
    data.put("period", filing.period());
    data.put("status", TaxFilingStatuses.FILED);
    data.put("filed_at", when.toString());
    data.put("reference_number", referenceNumber.trim());
    data.put("marked_by", principal.subject().toString());
    return data;
  }

  @Transactional(readOnly = true)
  public PagedResult tcsRegister(
      MedmatePrincipal principal, String monthParam, UUID pharmacyId, Integer page, Integer limit) {
    requireTaxRole(principal);
    YearMonth month = parseMonth(monthParam, clock);
    String monthKey = month.toString();
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    TcsPage result = store.listTcs(monthKey, pharmacyId, pageLimit, (pageNum - 1) * pageLimit);
    TcsMonthTotals totals = store.tcsTotals(monthKey);

    List<Map<String, Object>> entries = new ArrayList<>();
    for (TcsRegisterRecord row : result.entries()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("pharmacy_id", row.pharmacyId().toString());
      m.put("pharmacy_name", row.pharmacyName());
      m.put("gstin", row.gstin());
      m.put("pan", row.pan());
      m.put("gmv", MoneyFormats.paiseToRupees(row.gmvPaise()));
      m.put("tcs_collected", MoneyFormats.paiseToRupees(row.tcsCollectedPaise()));
      m.put("cgst_tcs", MoneyFormats.paiseToRupees(row.cgstTcsPaise()));
      m.put("sgst_tcs", MoneyFormats.paiseToRupees(row.sgstTcsPaise()));
      m.put("settlement_ids", row.settlementIds().stream().map(UUID::toString).toList());
      m.put("month", row.month());
      entries.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("month", monthKey);
    data.put("total_tcs", MoneyFormats.paiseToRupees(totals.totalTcsPaise()));
    data.put("total_gmv", MoneyFormats.paiseToRupees(totals.totalGmvPaise()));
    data.put("entries", entries);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  /** Daily / monthly maintenance: create missing GSTR-8 + TDS filings; persist OVERDUE. */
  @Transactional
  public void runScheduledMaintenance() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    Instant now = clock.instant();
    ensureRecentFilings(today);
    store.markOverduePending(today, now);
  }

  private void ensureRecentFilings(LocalDate today) {
    YearMonth current = YearMonth.from(today);
    ensureGstr8Filing(current.minusMonths(1).toString(), today);
    ensureTdsQuarterFiling(today);
  }

  private void ensureGstr8Filing(String monthKey, LocalDate today) {
    if (store.findFilingByTypeAndPeriod(TaxFilingTypes.GSTR_8, monthKey).isPresent()) {
      return;
    }
    YearMonth ym = YearMonth.parse(monthKey);
    Instant now = clock.instant();
    LocalDate due = gstr8DueDate(ym);
    String status = today.isAfter(due) ? TaxFilingStatuses.OVERDUE : TaxFilingStatuses.PENDING;
    store.insertFiling(
        new TaxFilingRecord(
            Ids.newId(),
            TaxFilingTypes.GSTR_8,
            monthKey,
            due,
            status,
            null,
            null,
            "TCS collected from pharmacy settlements in " + ym.getMonth() + " " + ym.getYear(),
            null,
            null,
            now,
            now));
  }

  private void ensureTdsQuarterFiling(LocalDate today) {
    String period = currentFyQuarterPeriod(today);
    if (store.findFilingByTypeAndPeriod(TaxFilingTypes.TDS_194O, period).isPresent()) {
      return;
    }
    LocalDate due = tdsDueDate(period);
    Instant now = clock.instant();
    store.insertFiling(
        new TaxFilingRecord(
            Ids.newId(),
            TaxFilingTypes.TDS_194O,
            period,
            due,
            TaxFilingStatuses.PENDING,
            null,
            null,
            "TDS on pharmacy commissions for " + period,
            null,
            null,
            now,
            now));
  }

  private Map<String, Object> toFilingMap(TaxFilingRecord row, String displayStatus) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("filing_id", row.id().toString());
    m.put("filing_type", row.filingType());
    m.put("period", row.period());
    m.put("due_date", row.dueDate().toString());
    m.put("status", displayStatus);
    m.put(
        "description",
        row.notes() == null || row.notes().isBlank()
            ? row.filingType() + " for " + row.period()
            : row.notes());
    if (TaxFilingTypes.GSTR_8.equals(row.filingType())) {
      long tcs = store.tcsTotals(row.period()).totalTcsPaise();
      m.put("tcs_amount", MoneyFormats.paiseToRupees(tcs));
    } else if (TaxFilingTypes.TDS_194O.equals(row.filingType())) {
      TdsSnapshot tds = computeTds194o(quarterEndMonth(row.period()));
      m.put("tds_amount", MoneyFormats.paiseToRupees(tds.tdsPaise()));
    }
    m.put("filed_at", row.filedAt() == null ? null : row.filedAt().toString());
    m.put("reference_number", row.referenceNumber());
    return m;
  }

  private TdsSnapshot computeTds194o(YearMonth asOfMonth) {
    LocalDate end = asOfMonth.atEndOfMonth();
    LocalDate start = end.minusMonths(11).withDayOfMonth(1);
    List<PharmacyCommissionRow> rows = store.commissionByPharmacy(start, end);
    int eligible = 0;
    long eligibleCommission = 0L;
    long tds = 0L;
    for (PharmacyCommissionRow row : rows) {
      if (row.commissionPaise() <= TDS_THRESHOLD_PAISE) {
        continue;
      }
      eligible++;
      eligibleCommission += row.commissionPaise();
      BigDecimal rate =
          row.pan() != null && !row.pan().isBlank() ? TDS_RATE_WITH_PAN : TDS_RATE_WITHOUT_PAN;
      tds +=
          BigDecimal.valueOf(row.commissionPaise())
              .multiply(rate)
              .setScale(0, RoundingMode.HALF_UP)
              .longValueExact();
    }
    return new TdsSnapshot(eligible, eligibleCommission, tds);
  }

  private String toGstr8Json(String period, List<TcsRegisterRecord> entries) {
    YearMonth ym = YearMonth.parse(period);
    String fp = String.format("%02d%04d", ym.getMonthValue(), ym.getYear());
    List<Map<String, Object>> supplies = new ArrayList<>();
    for (TcsRegisterRecord e : entries) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("gstin_of_supplier", e.gstin());
      row.put("gross_value", MoneyFormats.paiseToRupees(e.gmvPaise()));
      row.put("tcs_cgst", MoneyFormats.paiseToRupees(e.cgstTcsPaise()));
      row.put("tcs_sgst", MoneyFormats.paiseToRupees(e.sgstTcsPaise()));
      row.put("tcs_igst", MoneyFormats.paiseToRupees(0));
      supplies.add(row);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("gstin", "");
    root.put("fp", fp);
    root.put("table3", supplies);
    try {
      return objectMapper.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String toGstr8Csv(List<TcsRegisterRecord> entries) {
    StringBuilder sb =
        new StringBuilder("gstin,pan,gmv,tcs_collected,cgst_tcs,sgst_tcs,pharmacy_id\n");
    for (TcsRegisterRecord e : entries) {
      sb.append(csv(e.gstin()))
          .append(',')
          .append(csv(e.pan()))
          .append(',')
          .append(MoneyFormats.paiseToRupees(e.gmvPaise()))
          .append(',')
          .append(MoneyFormats.paiseToRupees(e.tcsCollectedPaise()))
          .append(',')
          .append(MoneyFormats.paiseToRupees(e.cgstTcsPaise()))
          .append(',')
          .append(MoneyFormats.paiseToRupees(e.sgstTcsPaise()))
          .append(',')
          .append(e.pharmacyId())
          .append('\n');
    }
    return sb.toString();
  }

  private static String csv(String v) {
    if (v == null) {
      return "";
    }
    if (v.contains(",") || v.contains("\"")) {
      return "\"" + v.replace("\"", "\"\"") + "\"";
    }
    return v;
  }

  static LocalDate gstr8DueDate(YearMonth month) {
    return month.plusMonths(1).atDay(10);
  }

  static YearMonth parseMonth(String monthParam, Clock clock) {
    if (monthParam == null || monthParam.isBlank()) {
      return YearMonth.now(clock.withZone(IST));
    }
    try {
      return YearMonth.parse(monthParam.trim());
    } catch (DateTimeParseException e) {
      throw new AppException("INVALID_MONTH", "month must be YYYY-MM", 400);
    }
  }

  /** Indian FY quarter label e.g. Q2-2026 for Jul–Sep 2026. */
  static String currentFyQuarterPeriod(LocalDate today) {
    int month = today.getMonthValue();
    int year = today.getYear();
    if (month <= 3) {
      return "Q4-" + year;
    }
    if (month <= 6) {
      return "Q1-" + year;
    }
    if (month <= 9) {
      return "Q2-" + year;
    }
    return "Q3-" + year;
  }

  static LocalDate tdsDueDate(String period) {
    // Q1 → Jul 31, Q2 → Oct 31, Q3 → Jan 31 next, Q4 → May 31
    String[] parts = period.split("-");
    int q = Integer.parseInt(parts[0].substring(1));
    int year = Integer.parseInt(parts[1]);
    return switch (q) {
      case 1 -> LocalDate.of(year, 7, 31);
      case 2 -> LocalDate.of(year, 10, 31);
      case 3 -> LocalDate.of(year + 1, 1, 31);
      case 4 -> LocalDate.of(year, 5, 31);
      default -> throw new IllegalArgumentException("bad quarter");
    };
  }

  static YearMonth quarterEndMonth(String period) {
    String[] parts = period.split("-");
    int q = Integer.parseInt(parts[0].substring(1));
    int year = Integer.parseInt(parts[1]);
    return switch (q) {
      case 1 -> YearMonth.of(year, 6);
      case 2 -> YearMonth.of(year, 9);
      case 3 -> YearMonth.of(year, 12);
      case 4 -> YearMonth.of(year, 3);
      default -> throw new IllegalArgumentException("bad quarter");
    };
  }

  static void requireTaxRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_FINANCE
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException(
          "FORBIDDEN", "admin_finance, admin_compliance, or admin_super required", 403);
    }
  }

  private record TdsSnapshot(int eligibleCount, long eligibleCommissionPaise, long tdsPaise) {}
}
