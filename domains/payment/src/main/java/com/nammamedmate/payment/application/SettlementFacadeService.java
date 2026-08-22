package com.nammamedmate.payment.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.BankSnapshot;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.LineItem;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.ListFilter;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.ListResult;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.SettlementRecord;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort.Totals;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort.PayoutRequest;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort.PayoutResult;
import com.nammamedmate.payment.application.port.out.SettlementNotificationPort;
import com.nammamedmate.payment.application.port.out.TcsRegisterWriterPort;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.payment.domain.SettlementStatuses;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** EPIC-012 STORY-003 finance façade over pharmacy settlements + ledger + RazorpayX. */
@Service
public class SettlementFacadeService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final BigDecimal GST_ON_COMMISSION = new BigDecimal("0.18");

  private final PharmacySettlementPort settlements;
  private final RazorpayXPayoutPort razorpayx;
  private final FinancialLedgerWriterPort ledger;
  private final SettlementNotificationPort notifications;
  private final TcsRegisterWriterPort tcsRegister;
  private final Clock clock;
  private final TransactionTemplate tx;

  public SettlementFacadeService(
      PharmacySettlementPort settlements,
      RazorpayXPayoutPort razorpayx,
      FinancialLedgerWriterPort ledger,
      SettlementNotificationPort notifications,
      TcsRegisterWriterPort tcsRegister,
      Clock clock) {
    this(settlements, razorpayx, ledger, notifications, tcsRegister, clock, null);
  }

  @Autowired
  public SettlementFacadeService(
      PharmacySettlementPort settlements,
      RazorpayXPayoutPort razorpayx,
      FinancialLedgerWriterPort ledger,
      SettlementNotificationPort notifications,
      TcsRegisterWriterPort tcsRegister,
      Clock clock,
      @Nullable PlatformTransactionManager transactionManager) {
    this.settlements = settlements;
    this.razorpayx = razorpayx;
    this.ledger = ledger;
    this.notifications = notifications;
    this.tcsRegister = tcsRegister;
    this.clock = clock;
    this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
  }

  private <T> T inTx(Supplier<T> action) {
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

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public PagedResult listAdmin(
      MedmatePrincipal principal,
      String status,
      UUID pharmacyId,
      LocalDate cycleFrom,
      Integer page,
      Integer limit) {
    requireFinanceRole(principal);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    String storageStatus = parseStatusFilter(status);
    ListFilter filter =
        new ListFilter(storageStatus, pharmacyId, cycleFrom, pageLimit, (pageNum - 1) * pageLimit);
    ListResult result = settlements.list(filter);
    Totals totals = settlements.totals(filter);
    Instant dayStart = LocalDate.now(clock.withZone(IST)).atStartOfDay(IST).toInstant();
    Instant dayEnd = dayStart.plusSeconds(86400);
    KpiSnapshot kpi = settlements.kpis(dayStart, dayEnd);

    List<Map<String, Object>> items = new ArrayList<>();
    for (SettlementRecord row : result.settlements()) {
      items.add(toListItem(row));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "kpi_chips",
        Map.of(
            "gmv_today", MoneyFormats.paiseToRupees(kpi.gmvTodayPaise()),
            "commission_today", MoneyFormats.paiseToRupees(kpi.commissionTodayPaise()),
            "payout_due_total", MoneyFormats.paiseToRupees(kpi.payoutDueTotalPaise()),
            "payout_released_today", MoneyFormats.paiseToRupees(kpi.payoutReleasedTodayPaise())));
    data.put("settlements", items);
    data.put(
        "totals",
        Map.of(
            "total_gmv", MoneyFormats.paiseToRupees(totals.totalGmvPaise()),
            "total_commission", MoneyFormats.paiseToRupees(totals.totalCommissionPaise()),
            "total_tcs", MoneyFormats.paiseToRupees(totals.totalTcsPaise()),
            "total_net_payable", MoneyFormats.paiseToRupees(totals.totalNetPayablePaise())));
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getAdminDetail(MedmatePrincipal principal, UUID settlementId) {
    requireFinanceRole(principal);
    SettlementRecord row = requireSettlement(settlementId);
    return toDetail(row, true);
  }

  /**
   * Release runs claim → RazorpayX (outside TX) → finalize+ledger. Claim/finalize use short
   * transactions so a provider accept is not rolled back with an uncommitted Idempotency-Key.
   */
  public Map<String, Object> release(
      MedmatePrincipal principal, UUID settlementId, String notes, String idempotencyKey) {
    requireFinanceRole(principal);
    String key = requireIdempotencyKey(idempotencyKey);

    var existing = settlements.findByIdempotencyKey(key);
    if (existing.isPresent()) {
      SettlementRecord row = existing.get();
      if (!row.id().equals(settlementId)) {
        throw new AppException(
            "IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key already used for another settlement", 409);
      }
      return releaseResponse(row, principal.subject(), true);
    }

    SettlementRecord settlement = requireSettlement(settlementId);
    rejectInvalidReleaseState(settlement);

    if (settlement.netPayablePaise() < SettlementStatuses.MIN_RELEASE_PAISE) {
      inTx(
          () ->
              settlements.markBelowThreshold(
                  settlementId,
                  notes == null ? "Below Rs 100 threshold" : notes.trim(),
                  clock.instant()));
      throw new AppException(
          "AMOUNT_BELOW_THRESHOLD",
          "net_payable < Rs 100; amount carried forward to next cycle",
          422);
    }

    BankSnapshot bank =
        settlements
            .findVerifiedBank(settlement.pharmacyId())
            .orElseThrow(
                () ->
                    new AppException(
                        "PHARMACY_NO_BANK_ACCOUNT", "Pharmacy has no verified bank account", 422));

    Instant now = clock.instant();
    Boolean claimed =
        inTx(() -> settlements.claimForRelease(settlementId, settlement.pharmacyId(), key, now));
    if (!Boolean.TRUE.equals(claimed)) {
      var replay = settlements.findByIdempotencyKey(key);
      if (replay.isPresent()) {
        return releaseResponse(replay.get(), principal.subject(), true);
      }
      SettlementRecord current = requireSettlement(settlementId);
      rejectInvalidReleaseState(current);
      throw new AppException("SETTLEMENT_CONFLICT", "Could not claim settlement for release", 409);
    }

    try {
      // Outside DB TX — must not share a rollback boundary with claim/finalize
      PayoutResult payout =
          razorpayx.initiatePayout(
              new PayoutRequest(
                  settlement.pharmacyId(),
                  settlementId,
                  settlement.netPayablePaise(),
                  last4FromMasked(bank.accountNumberMasked()),
                  bank.ifsc()));

      inTx(
          () -> {
            if (!settlements.finalizeRelease(
                settlementId,
                principal.subject(),
                now,
                payout.razorpayxPayoutId(),
                notes,
                key,
                now)) {
              throw new AppException(
                  "SETTLEMENT_CONFLICT", "Could not finalize settlement release", 409);
            }
            writeLedger(settlement);
            tcsRegister.recordReleasedSettlement(
                settlementId,
                settlement.pharmacyId(),
                settlement.cycleFrom().toString().substring(0, 7),
                settlement.gmvPaise(),
                settlement.tcsDeductedPaise(),
                now);
            notifications.settlementReleased(
                settlement.pharmacyId(), settlementId, settlement.netPayablePaise());
          });
    } catch (RuntimeException ex) {
      inTx(() -> settlements.markReleaseFailed(settlementId, key, now));
      if (ex instanceof AppException app) {
        if ("RAZORPAY_PAYOUT_FAILED".equals(app.code()) || "RAZORPAY_ERROR".equals(app.code())) {
          throw new AppException("RAZORPAY_PAYOUT_FAILED", app.getMessage(), 502);
        }
        throw app;
      }
      throw new AppException("RAZORPAY_PAYOUT_FAILED", "Failed to initiate payout", 502);
    }

    SettlementRecord updated = requireSettlement(settlementId);
    return releaseResponse(updated, principal.subject(), true);
  }

  @Transactional
  public Map<String, Object> hold(
      MedmatePrincipal principal, UUID settlementId, String reason, String notes) {
    requireFinanceRole(principal);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    SettlementRecord settlement = requireSettlement(settlementId);
    String apiStatus = SettlementStatuses.toApiStatus(settlement.status());
    if (SettlementStatuses.API_RELEASED.equals(apiStatus)) {
      throw new AppException("ALREADY_RELEASED", "Settlement already released", 409);
    }
    Instant now = clock.instant();
    settlements.markHeld(settlementId, principal.subject(), reason.trim(), notes, now);
    notifications.settlementHeld(settlement.pharmacyId(), settlementId, reason.trim());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("settlement_id", settlementId.toString());
    data.put("status", SettlementStatuses.API_HELD);
    data.put("held_by", principal.subject().toString());
    data.put("held_at", now.toString());
    data.put("reason", reason.trim());
    return data;
  }

  @Transactional
  public Map<String, Object> unhold(MedmatePrincipal principal, UUID settlementId, String notes) {
    requireFinanceRole(principal);
    SettlementRecord settlement = requireSettlement(settlementId);
    String apiStatus = SettlementStatuses.toApiStatus(settlement.status());
    if (!SettlementStatuses.API_HELD.equals(apiStatus)) {
      throw new AppException("SETTLEMENT_NOT_HELD", "Settlement is not on HOLD", 422);
    }
    Instant now = clock.instant();
    settlements.markUnheld(settlementId, principal.subject(), notes, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("settlement_id", settlementId.toString());
    data.put("status", SettlementStatuses.API_PENDING);
    data.put("unheld_by", principal.subject().toString());
    data.put("unheld_at", now.toString());
    return data;
  }

  /** Not @Transactional — each nested release commits claim/finalize independently. */
  public Map<String, Object> releaseAll(
      MedmatePrincipal principal, Object threshold, String notes, String idempotencyKey) {
    requireFinanceRole(principal);
    requireIdempotencyKey(idempotencyKey);
    long maxPaise = SettlementStatuses.DEFAULT_BULK_MAX_PAISE;
    if (threshold != null) {
      maxPaise = MoneyFormats.parsePositiveRupeesToPaise(threshold);
    }

    List<SettlementRecord> candidates = settlements.listPendingForBulk(maxPaise, 200);
    int attempted = candidates.size();
    int released = 0;
    long totalReleased = 0L;
    List<Map<String, Object>> failures = new ArrayList<>();

    for (SettlementRecord row : candidates) {
      String perKey = idempotencyKey.trim() + ":" + row.id();
      try {
        release(principal, row.id(), notes, perKey);
        released++;
        totalReleased += row.netPayablePaise();
      } catch (AppException ex) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("settlement_id", row.id().toString());
        fail.put("pharmacy_name", row.pharmacyName() == null ? "" : row.pharmacyName());
        fail.put("reason", ex.code());
        failures.add(fail);
      }
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("attempted", attempted);
    data.put("released", released);
    data.put("failed", failures.size());
    data.put("total_amount_released", MoneyFormats.paiseToRupees(totalReleased));
    data.put("failures", failures);
    return data;
  }

  @Transactional(readOnly = true)
  public PagedResult listPharmacy(
      MedmatePrincipal principal, String status, Integer page, Integer limit) {
    UUID pharmacyId = requirePharmacyOwner(principal);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    String storageStatus = parseStatusFilter(status);
    ListFilter filter =
        new ListFilter(storageStatus, pharmacyId, null, pageLimit, (pageNum - 1) * pageLimit);
    ListResult result = settlements.list(filter);
    List<Map<String, Object>> items = new ArrayList<>();
    for (SettlementRecord row : result.settlements()) {
      items.add(toPharmacyListItem(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("settlements", items);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getPharmacyDetail(MedmatePrincipal principal, UUID settlementId) {
    UUID pharmacyId = requirePharmacyOwner(principal);
    SettlementRecord row = requireSettlement(settlementId);
    if (!pharmacyId.equals(row.pharmacyId())) {
      throw new AppException("FORBIDDEN", "Settlement does not belong to this pharmacy", 403);
    }
    return toDetail(row, false);
  }

  private void writeLedger(SettlementRecord settlement) {
    ledger.append(
        "PAYOUT_PHARMACY",
        settlement.id(),
        "SETTLEMENT",
        0L,
        settlement.netPayablePaise(),
        "Pharmacy settlement payout",
        Map.of(
            "pharmacy_id",
            settlement.pharmacyId().toString(),
            "cycle_from",
            settlement.cycleFrom().toString(),
            "cycle_to",
            settlement.cycleTo().toString()));
    if (settlement.tcsDeductedPaise() > 0) {
      ledger.append(
          "TCS_COLLECTED",
          settlement.id(),
          "SETTLEMENT",
          settlement.tcsDeductedPaise(),
          0L,
          "TCS on pharmacy settlement GMV",
          Map.of("pharmacy_id", settlement.pharmacyId().toString()));
    }
  }

  private Map<String, Object> releaseResponse(
      SettlementRecord settlement, UUID releasedBy, boolean notificationSent) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("settlement_id", settlement.id().toString());
    data.put("pharmacy_id", settlement.pharmacyId().toString());
    data.put("net_payable", MoneyFormats.paiseToRupees(settlement.netPayablePaise()));
    data.put("status", SettlementStatuses.toApiStatus(settlement.status()));
    data.put("razorpay_payout_id", settlement.razorpayxPayoutId());
    data.put("released_by", releasedBy.toString());
    data.put(
        "released_at", settlement.releasedAt() == null ? null : settlement.releasedAt().toString());
    data.put("notification_sent", notificationSent);
    return data;
  }

  private Map<String, Object> toDetail(SettlementRecord row, boolean includeBank) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("settlement_id", row.id().toString());
    data.put("pharmacy_id", row.pharmacyId().toString());
    data.put("pharmacy_name", row.pharmacyName());
    if (includeBank) {
      BankSnapshot bank = settlements.findVerifiedBank(row.pharmacyId()).orElse(null);
      if (bank != null) {
        data.put(
            "pharmacy_bank",
            Map.of(
                "account_number_masked",
                bank.accountNumberMasked() == null ? "" : bank.accountNumberMasked(),
                "bank_name",
                bank.bankName() == null ? "" : bank.bankName(),
                "ifsc",
                bank.ifsc() == null ? "" : bank.ifsc()));
      } else {
        data.put("pharmacy_bank", null);
      }
    }
    data.put("cycle_from", row.cycleFrom().toString());
    data.put("cycle_to", row.cycleTo().toString());
    data.put("gmv", MoneyFormats.paiseToRupees(row.gmvPaise()));
    data.put("commission_pct", scalePct(row.commissionPct()));
    data.put("commission_earned", MoneyFormats.paiseToRupees(row.commissionEarnedPaise()));
    data.put("tcs_deducted", MoneyFormats.paiseToRupees(row.tcsDeductedPaise()));
    long gst =
        row.gstOnCommissionPaise() > 0
            ? row.gstOnCommissionPaise()
            : BigDecimal.valueOf(row.commissionEarnedPaise())
                .multiply(GST_ON_COMMISSION)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    data.put("gst_on_commission", MoneyFormats.paiseToRupees(gst));
    data.put("net_payable", MoneyFormats.paiseToRupees(row.netPayablePaise()));
    data.put("status", SettlementStatuses.toApiStatus(row.status()));
    List<LineItem> lines =
        settlements.lineItems(
            row.pharmacyId(), row.cycleFrom(), row.cycleTo(), row.commissionPct());
    data.put("orders_count", row.ordersCount() > 0 ? row.ordersCount() : lines.size());
    List<Map<String, Object>> lineMaps = new ArrayList<>();
    for (LineItem li : lines) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("order_id", li.orderId().toString());
      m.put("order_number", li.orderNumber());
      m.put("delivered_at", li.deliveredAt() == null ? null : li.deliveredAt().toString());
      m.put("gmv", MoneyFormats.paiseToRupees(li.gmvPaise()));
      m.put("commission_pct", scalePct(li.commissionPct()));
      m.put("commission", MoneyFormats.paiseToRupees(li.commissionPaise()));
      m.put("tcs", MoneyFormats.paiseToRupees(li.tcsPaise()));
      m.put("net", MoneyFormats.paiseToRupees(li.netPaise()));
      lineMaps.add(m);
    }
    data.put("line_items", lineMaps);
    return data;
  }

  private static Map<String, Object> toListItem(SettlementRecord row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("settlement_id", row.id().toString());
    m.put("pharmacy_id", row.pharmacyId().toString());
    m.put("pharmacy_name", row.pharmacyName());
    m.put("cycle_from", row.cycleFrom().toString());
    m.put("cycle_to", row.cycleTo().toString());
    m.put("gmv", MoneyFormats.paiseToRupees(row.gmvPaise()));
    m.put("commission_pct", scalePct(row.commissionPct()));
    m.put("commission_earned", MoneyFormats.paiseToRupees(row.commissionEarnedPaise()));
    m.put("tcs_deducted", MoneyFormats.paiseToRupees(row.tcsDeductedPaise()));
    m.put("net_payable", MoneyFormats.paiseToRupees(row.netPayablePaise()));
    m.put("status", SettlementStatuses.toApiStatus(row.status()));
    m.put("released_at", row.releasedAt() == null ? null : row.releasedAt().toString());
    return m;
  }

  private static Map<String, Object> toPharmacyListItem(SettlementRecord row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("settlement_id", row.id().toString());
    m.put("cycle_from", row.cycleFrom().toString());
    m.put("cycle_to", row.cycleTo().toString());
    m.put("gmv", MoneyFormats.paiseToRupees(row.gmvPaise()));
    m.put("commission_pct", scalePct(row.commissionPct()));
    m.put("commission_deducted", MoneyFormats.paiseToRupees(row.commissionEarnedPaise()));
    m.put("tcs_deducted", MoneyFormats.paiseToRupees(row.tcsDeductedPaise()));
    m.put("net_payable", MoneyFormats.paiseToRupees(row.netPayablePaise()));
    m.put("status", SettlementStatuses.toApiStatus(row.status()));
    m.put("released_at", row.releasedAt() == null ? null : row.releasedAt().toString());
    return m;
  }

  private SettlementRecord requireSettlement(UUID settlementId) {
    return settlements
        .findById(settlementId)
        .orElseThrow(() -> new AppException("SETTLEMENT_NOT_FOUND", "Settlement not found", 404));
  }

  private void rejectInvalidReleaseState(SettlementRecord settlement) {
    String api = SettlementStatuses.toApiStatus(settlement.status());
    switch (api) {
      case SettlementStatuses.API_RELEASED ->
          throw new AppException("ALREADY_RELEASED", "Settlement already released", 409);
      case SettlementStatuses.API_HELD ->
          throw new AppException(
              "SETTLEMENT_HELD", "Settlement is on HOLD; unhold before releasing", 422);
      case SettlementStatuses.API_BELOW ->
          throw new AppException(
              "AMOUNT_BELOW_THRESHOLD", "Settlement already marked below threshold", 422);
      default -> {
        // PENDING / FAILED / other — release path decides claim eligibility
      }
    }
  }

  private static String parseStatusFilter(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return SettlementStatuses.toStorageFilter(status);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_STATUS", "Invalid settlement status filter", 400);
    }
  }

  static void requireFinanceRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "admin_finance or admin_super required", 403);
    }
  }

  private static UUID requirePharmacyOwner(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER || principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "pharmacy_owner required", 403);
    }
    return principal.pharmacyId();
  }

  static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    String trimmed = key.trim();
    if (trimmed.length() > 128) {
      throw new AppException(
          "VALIDATION_ERROR", "Idempotency-Key must be at most 128 characters", 400);
    }
    return trimmed;
  }

  private static BigDecimal scalePct(BigDecimal pct) {
    if (pct == null) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return pct.setScale(1, RoundingMode.HALF_UP);
  }

  private static String last4FromMasked(String masked) {
    if (masked == null || masked.length() < 4) {
      return "0000";
    }
    return masked.substring(masked.length() - 4);
  }
}
