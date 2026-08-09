package com.nammamedmate.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.payment.application.port.out.CodFloatAlertPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort.DayAggregates;
import com.nammamedmate.payment.application.port.out.CodFloatPort.FloatRiderRow;
import com.nammamedmate.payment.application.port.out.CodFloatPort.FloatSnapshot;
import com.nammamedmate.payment.application.port.out.CodFloatPort.ReportRecord;
import com.nammamedmate.payment.application.port.out.CodFloatPort.RiderDayBreakdown;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** EPIC-012 STORY-006 finance façade over rider COD tables + reconciliation report. */
@Service
public class CodFloatFacadeService {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  static final long VARIANCE_ALERT_THRESHOLD_PAISE = 10_000L; // ₹100
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int ESTIMATED_COMPLETION_SECONDS = 30;

  private final CodFloatPort floats;
  private final CodFloatAlertPort alerts;
  private final FinancialLedgerWriterPort ledger;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final TransactionTemplate tx;

  public CodFloatFacadeService(
      CodFloatPort floats,
      CodFloatAlertPort alerts,
      FinancialLedgerWriterPort ledger,
      ObjectMapper objectMapper,
      Clock clock) {
    this(floats, alerts, ledger, objectMapper, clock, null);
  }

  @Autowired
  public CodFloatFacadeService(
      CodFloatPort floats,
      CodFloatAlertPort alerts,
      FinancialLedgerWriterPort ledger,
      ObjectMapper objectMapper,
      Clock clock,
      @Nullable PlatformTransactionManager transactionManager) {
    this.floats = floats;
    this.alerts = alerts;
    this.ledger = ledger;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public PagedResult floatSummary(
      MedmatePrincipal principal, UUID zoneId, Boolean riskOnly, Integer page, Integer limit) {
    requireFloatRead(principal);
    boolean risk = Boolean.TRUE.equals(riskOnly);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    long floatLimit = floats.floatLimitPaise();
    Instant now = clock.instant();
    DayWindow day = istDay(now);
    FloatSnapshot snap =
        floats.floatBoard(zoneId, risk, day.start(), day.end(), floatLimit, pageNum, pageLimit);

    List<Map<String, Object>> riderMaps = new ArrayList<>();
    for (FloatRiderRow row : snap.riders()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rider_id", row.riderId().toString());
      m.put("rider_name", row.riderName());
      m.put("zone_name", row.zoneName());
      m.put("collected", MoneyFormats.paiseToRupees(row.collectedPaise()));
      m.put("deposited", MoneyFormats.paiseToRupees(row.depositedPaise()));
      m.put("in_hand", MoneyFormats.paiseToRupees(row.inHandPaise()));
      m.put("risk_status", riskStatus(row.inHandPaise(), floatLimit));
      m.put("last_deposit_at", row.lastDepositAt() == null ? null : row.lastDepositAt().toString());
      riderMaps.add(m);
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total_cod_in_transit", MoneyFormats.paiseToRupees(snap.totalInTransitPaise()));
    summary.put("collected_today", MoneyFormats.paiseToRupees(snap.collectedTodayPaise()));
    summary.put("deposited_today", MoneyFormats.paiseToRupees(snap.depositedTodayPaise()));
    summary.put("float_risk_amount", MoneyFormats.paiseToRupees(snap.floatRiskAmountPaise()));
    summary.put("float_risk_riders_count", snap.floatRiskRidersCount());
    summary.put("float_risk_threshold", MoneyFormats.paiseToRupees(floatLimit));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", summary);
    data.put("riders", riderMaps);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, snap.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> reconciliationReport(MedmatePrincipal principal, LocalDate date) {
    requireFinanceWrite(principal);
    LocalDate reportDate = resolveReportDate(date);
    assertNotFuture(reportDate);
    ReportRecord report =
        floats
            .findReport(reportDate)
            .orElseThrow(
                () ->
                    new AppException(
                        "REPORT_NOT_GENERATED",
                        "Reconciliation job has not run for the requested date",
                        404));
    if ("PENDING".equals(report.reconciliationStatus())) {
      throw new AppException(
          "REPORT_NOT_GENERATED",
          "Reconciliation job has not completed for the requested date",
          404);
    }
    return toReportResponse(report);
  }

  @Transactional(readOnly = true)
  public void exportReconciliationCsv(
      MedmatePrincipal principal, LocalDate date, OutputStream out) {
    Map<String, Object> report = reconciliationReport(principal, date);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> breakdown =
        (List<Map<String, Object>>) report.getOrDefault("rider_breakdown", List.of());
    try {
      Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
      w.write("rider_id,rider_name,orders,collected,deposited,variance,status\n");
      for (Map<String, Object> row : breakdown) {
        w.write(csv(row.get("rider_id")));
        w.write(',');
        w.write(csv(row.get("rider_name")));
        w.write(',');
        w.write(csv(row.get("orders")));
        w.write(',');
        w.write(csv(row.get("collected")));
        w.write(',');
        w.write(csv(row.get("deposited")));
        w.write(',');
        w.write(csv(row.get("variance")));
        w.write(',');
        w.write(csv(row.get("status")));
        w.write('\n');
      }
      w.flush();
    } catch (Exception e) {
      throw new AppException("EXPORT_FAILED", "Failed to export reconciliation CSV", 500);
    }
  }

  public Map<String, Object> autoReconcile(MedmatePrincipal principal, LocalDate date) {
    requireFinanceWrite(principal);
    LocalDate reportDate = date == null ? clock.instant().atZone(IST).toLocalDate() : date;
    assertNotFuture(reportDate);
    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    UUID triggeredBy = principal.subject();
    boolean claimed = inTx(() -> floats.tryClaimJob(jobId, reportDate, triggeredBy, now));
    if (!claimed) {
      throw new AppException(
          "JOB_ALREADY_RUNNING", "Reconciliation job for this date is already in progress", 409);
    }
    runReconciliation(jobId, reportDate, triggeredBy, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", jobId.toString());
    data.put("date", reportDate.toString());
    data.put("status", "RUNNING");
    data.put("triggered_by", triggeredBy.toString());
    data.put("triggered_at", now.toString());
    data.put("estimated_completion_seconds", ESTIMATED_COMPLETION_SECONDS);
    data.put(
        "result_url", "/api/v1/admin/finance/cod-float/reconciliation-report?date=" + reportDate);
    return data;
  }

  /** Cron / rider bridge entry — no auth principal (triggered_by null). */
  public void runScheduledReconciliation(LocalDate reportDate) {
    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    boolean claimed = inTx(() -> floats.tryClaimJob(jobId, reportDate, null, now));
    if (!claimed) {
      return;
    }
    runReconciliation(jobId, reportDate, null, now);
  }

  /** Write COD_DEPOSIT ledger on deposit confirm if not already present. */
  public void onDepositConfirmed(UUID depositId, UUID riderId, long amountPaise) {
    if (depositId == null || amountPaise <= 0) {
      return;
    }
    if (floats.hasCodDepositLedgerEntry(depositId)) {
      return;
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    if (riderId != null) {
      meta.put("rider_id", riderId.toString());
    }
    ledger.append(
        "COD_DEPOSIT", depositId, "COD_DEPOSIT", amountPaise, 0L, "COD deposit confirmed", meta);
  }

  private void runReconciliation(
      UUID jobId, LocalDate reportDate, UUID triggeredBy, Instant generatedAt) {
    DayWindow day = dayWindow(reportDate);
    DayAggregates agg = floats.aggregatesForDay(day.start(), day.end());

    long collected = agg.collectedPaise();
    long deposited = agg.depositedPaise();
    long outstanding = Math.max(0L, collected - deposited);
    long variance = Math.max(0L, deposited - collected);

    List<Map<String, Object>> breakdown = new ArrayList<>();
    for (RiderDayBreakdown r : agg.riders()) {
      long riderVar = Math.abs(r.collectedPaise() - r.depositedPaise());
      String status = riderVar == 0L ? "MATCHED" : "DISCREPANCY";
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("rider_id", r.riderId().toString());
      row.put("rider_name", r.riderName() == null ? "" : r.riderName());
      row.put("orders", r.orders());
      row.put("collected", MoneyFormats.paiseToRupees(r.collectedPaise()));
      row.put("deposited", MoneyFormats.paiseToRupees(r.depositedPaise()));
      row.put("variance", MoneyFormats.paiseToRupees(riderVar));
      row.put("status", status);
      breakdown.add(row);
    }

    String status = variance > 0L ? "DISCREPANCY" : "BALANCED";
    boolean alert = variance > VARIANCE_ALERT_THRESHOLD_PAISE;
    String breakdownJson;
    try {
      breakdownJson = objectMapper.writeValueAsString(breakdown);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("json encode failed", e);
    }

    ReportRecord completed =
        new ReportRecord(
            jobId,
            reportDate,
            agg.totalCodOrders(),
            agg.totalCodAmountPaise(),
            collected,
            deposited,
            outstanding,
            variance,
            null,
            status,
            alert,
            generatedAt,
            triggeredBy,
            breakdownJson);
    inTx(() -> floats.completeReport(completed));
    if (alert) {
      alerts.varianceAlert(jobId, reportDate, variance, status);
    }
  }

  private Map<String, Object> toReportResponse(ReportRecord report) {
    List<Map<String, Object>> breakdown = parseBreakdown(report.riderBreakdownJson());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("report_date", report.reportDate().toString());
    data.put("total_cod_orders", report.totalCodOrders());
    data.put("total_cod_amount", MoneyFormats.paiseToRupees(report.totalCodAmountPaise()));
    data.put("collected_by_riders", MoneyFormats.paiseToRupees(report.collectedByRidersPaise()));
    data.put(
        "deposited_to_platform", MoneyFormats.paiseToRupees(report.depositedToPlatformPaise()));
    data.put("outstanding_float", MoneyFormats.paiseToRupees(report.outstandingFloatPaise()));
    data.put("variance", MoneyFormats.paiseToRupees(report.variancePaise()));
    data.put("variance_reason", report.varianceReason());
    data.put("reconciliation_status", report.reconciliationStatus());
    data.put("rider_breakdown", breakdown);
    data.put("generated_at", report.generatedAt().toString());
    return data;
  }

  private List<Map<String, Object>> parseBreakdown(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  private LocalDate resolveReportDate(LocalDate date) {
    if (date != null) {
      return date;
    }
    return clock.instant().atZone(IST).toLocalDate().minusDays(1);
  }

  private void assertNotFuture(LocalDate reportDate) {
    LocalDate today = clock.instant().atZone(IST).toLocalDate();
    if (reportDate.isAfter(today)) {
      throw new AppException("INVALID_DATE", "date is in the future", 422);
    }
  }

  private <T> T inTx(java.util.function.Supplier<T> action) {
    if (tx == null) {
      return action.get();
    }
    return tx.execute(status -> action.get());
  }

  private void inTx(Runnable action) {
    inTx(
        () -> {
          action.run();
          return null;
        });
  }

  static String riskStatus(long inHandPaise, long limitPaise) {
    return inHandPaise > limitPaise ? "FLOAT_RISK" : "SAFE";
  }

  static void requireFloatRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_FINANCE
        && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  static void requireFinanceWrite(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "admin_finance or admin_super required", 403);
    }
  }

  private static DayWindow istDay(Instant now) {
    LocalDate date = now.atZone(IST).toLocalDate();
    return dayWindow(date);
  }

  private static DayWindow dayWindow(LocalDate date) {
    Instant start = date.atStartOfDay(IST).toInstant();
    Instant end = date.plusDays(1).atStartOfDay(IST).toInstant();
    return new DayWindow(date, start, end);
  }

  private record DayWindow(LocalDate date, Instant start, Instant end) {}

  private static String csv(Object value) {
    if (value == null) {
      return "";
    }
    String s = value.toString();
    if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }
}
