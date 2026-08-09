package com.nammamedmate.payment.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.DayKpis;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.LedgerPage;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.LedgerRow;
import com.nammamedmate.payment.application.port.out.TaxFilingObjectStore;
import com.nammamedmate.payment.domain.LedgerEntryTypes;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** EPIC-012 STORY-008 financial ledger browse + CSV export. */
@Service
public class LedgerFacadeService {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;
  private static final int MAX_EXPORT_DAYS = 90;
  private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

  private final FinancialLedgerQueryPort ledger;
  private final TaxFilingObjectStore objects;
  private final Clock clock;

  public LedgerFacadeService(
      FinancialLedgerQueryPort ledger, TaxFilingObjectStore objects, Clock clock) {
    this.ledger = ledger;
    this.objects = objects;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public PagedResult browse(
      MedmatePrincipal principal,
      String type,
      String from,
      String to,
      Integer page,
      Integer limit,
      String sort,
      String order) {
    requireFinance(principal);
    String[] types = resolveTypes(type);
    DateWindow window = optionalWindow(from, to);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    boolean ascending = "asc".equalsIgnoreCase(order);
    if (sort != null && !sort.isBlank() && !"created_at".equalsIgnoreCase(sort.trim())) {
      throw new AppException("INVALID_SORT", "Only sort=created_at is supported", 422);
    }
    LedgerPage result =
        ledger.list(types, window.fromInclusive(), window.toExclusive(), p, lim, ascending);
    LocalDate today = LocalDate.now(clock.withZone(IST));
    Instant dayStart = today.atStartOfDay(IST).toInstant();
    Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
    DayKpis kpis = ledger.dayKpis(dayStart, dayEnd);
    long netRevenue = Math.max(0L, kpis.commissionTodayPaise() - kpis.gatewayFeeTodayPaise());

    Map<String, Object> kpiChips = new LinkedHashMap<>();
    kpiChips.put("gmv_today", MoneyFormats.paiseToRupees(kpis.gmvTodayPaise()));
    kpiChips.put("commission_today", MoneyFormats.paiseToRupees(kpis.commissionTodayPaise()));
    kpiChips.put("net_revenue_today", MoneyFormats.paiseToRupees(netRevenue));

    List<Map<String, Object>> entries = new ArrayList<>(result.rows().size());
    for (LedgerRow row : result.rows()) {
      entries.add(toEntry(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpi_chips", kpiChips);
    data.put("entries", entries);
    return new PagedResult(data, PaginationMeta.of(p, lim, result.total()));
  }

  /** CSV for Accept: text/csv on browse (same filters; ascending for running balance). */
  public byte[] browseCsv(MedmatePrincipal principal, String type, String from, String to) {
    requireFinance(principal);
    String[] types = resolveTypes(type);
    DateWindow window = optionalWindow(from, to);
    List<LedgerRow> rows =
        ledger.listAllForExport(types, window.fromInclusive(), window.toExclusive());
    return buildCsv(rows).getBytes(StandardCharsets.UTF_8);
  }

  public Map<String, Object> export(
      MedmatePrincipal principal, String from, String to, String type) {
    requireFinance(principal);
    if (isBlank(from) || isBlank(to)) {
      throw new AppException("INVALID_DATE_RANGE", "from and to are required", 422);
    }
    LocalDate fromDate = parseDate(from, "from");
    LocalDate toDate = parseDate(to, "to");
    if (fromDate.isAfter(toDate)) {
      throw new AppException("INVALID_DATE_RANGE", "from_date is after to_date", 422);
    }
    long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    if (days > MAX_EXPORT_DAYS) {
      throw new AppException("DATE_RANGE_TOO_LARGE", "Range exceeds 90 days", 422);
    }
    Instant fromInclusive = fromDate.atStartOfDay(IST).toInstant();
    Instant toExclusive = toDate.plusDays(1).atStartOfDay(IST).toInstant();
    String[] types = resolveTypes(type);
    List<LedgerRow> rows = ledger.listAllForExport(types, fromInclusive, toExclusive);
    Instant generatedAt = clock.instant();
    Instant expiresAt = generatedAt.plus(DOWNLOAD_TTL);
    String key =
        StorageObjectKeys.export(
            "ledger_" + fromDate + "_to_" + toDate + "_" + UUID.randomUUID() + ".csv");
    byte[] csv = buildCsv(rows).getBytes(StandardCharsets.UTF_8);
    objects.put(key, csv, "text/csv");
    String url = objects.createDownloadUrl(key, DOWNLOAD_TTL);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("download_url", url);
    data.put("expires_at", expiresAt.toString());
    data.put("record_count", rows.size());
    data.put("from_date", fromDate.toString());
    data.put("to_date", toDate.toString());
    data.put("generated_at", generatedAt.toString());
    return data;
  }

  private static Map<String, Object> toEntry(LedgerRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ledger_id", row.id().toString());
    m.put("type", LedgerEntryTypes.toApiType(row.entryType()));
    m.put("reference_id", row.referenceId().toString());
    m.put("reference_type", row.referenceType());
    m.put("credit", MoneyFormats.paiseToRupees(row.creditPaise()));
    m.put("debit", MoneyFormats.paiseToRupees(row.debitPaise()));
    m.put("running_balance", MoneyFormats.paiseToRupees(row.runningBalancePaise()));
    m.put("description", row.description() == null ? "" : row.description());
    m.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    return m;
  }

  static String buildCsv(List<LedgerRow> rows) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "ledger_id,type,reference_id,reference_type,credit,debit,running_balance,description,created_at\n");
    for (LedgerRow row : rows) {
      sb.append(csv(row.id().toString())).append(',');
      sb.append(csv(LedgerEntryTypes.toApiType(row.entryType()))).append(',');
      sb.append(csv(row.referenceId().toString())).append(',');
      sb.append(csv(row.referenceType())).append(',');
      sb.append(MoneyFormats.paiseToRupees(row.creditPaise()).toPlainString()).append(',');
      sb.append(MoneyFormats.paiseToRupees(row.debitPaise()).toPlainString()).append(',');
      sb.append(MoneyFormats.paiseToRupees(row.runningBalancePaise()).toPlainString()).append(',');
      sb.append(csv(row.description() == null ? "" : row.description())).append(',');
      sb.append(csv(row.createdAt() == null ? "" : row.createdAt().toString())).append('\n');
    }
    return sb.toString();
  }

  private static String csv(String value) {
    boolean escape =
        value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
    if (escape) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }

  private String[] resolveTypes(String type) {
    if (isBlank(type)) {
      return new String[0];
    }
    String normalized = type.trim().toUpperCase(Locale.ROOT);
    if (!LedgerEntryTypes.isKnown(normalized)) {
      throw new AppException("INVALID_TYPE", "Unknown ledger entry type", 422);
    }
    return LedgerEntryTypes.storageTypesForFilter(normalized);
  }

  private DateWindow optionalWindow(String from, String to) {
    boolean fromMissing = isBlank(from);
    boolean toMissing = isBlank(to);
    if (fromMissing && toMissing) {
      return new DateWindow(null, null);
    }
    if (fromMissing || toMissing) {
      throw new AppException("INVALID_DATE_RANGE", "from and to must both be provided", 422);
    }
    LocalDate fromDate = parseDate(from, "from");
    LocalDate toDate = parseDate(to, "to");
    if (fromDate.isAfter(toDate)) {
      throw new AppException("INVALID_DATE_RANGE", "from_date is after to_date", 422);
    }
    return new DateWindow(
        fromDate.atStartOfDay(IST).toInstant(), toDate.plusDays(1).atStartOfDay(IST).toInstant());
  }

  private static LocalDate parseDate(String raw, String field) {
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new AppException("INVALID_DATE_RANGE", field + " must be YYYY-MM-DD", 422);
    }
  }

  private static int normalizePage(Integer page) {
    if (page == null || page < 1) {
      return 1;
    }
    return page;
  }

  private static int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    if (limit < 1) {
      return 1;
    }
    return Math.min(MAX_LIMIT, limit);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requireFinance(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Finance access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Finance access required", 403);
    }
  }

  private record DateWindow(Instant fromInclusive, Instant toExclusive) {}
}
