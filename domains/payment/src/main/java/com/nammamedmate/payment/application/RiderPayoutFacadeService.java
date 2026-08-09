package com.nammamedmate.payment.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort.PayoutRequest;
import com.nammamedmate.payment.application.port.out.RazorpayXPayoutPort.PayoutResult;
import com.nammamedmate.payment.application.port.out.RiderPayoutNotificationPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.EarningsEntry;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.ListFilter;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.ListResult;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.PayoutRecord;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.RiderSnapshot;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort.SummarySnapshot;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.payment.domain.RiderPayoutStatuses;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

/** EPIC-012 STORY-004 finance façade over rider payouts + ledger + Razorpay Route/X. */
@Service
public class RiderPayoutFacadeService {

  static final Duration RETRY_AFTER = Duration.ofHours(24);
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final RiderPayoutPort payouts;
  private final RazorpayXPayoutPort razorpayx;
  private final FinancialLedgerWriterPort ledger;
  private final RiderPayoutNotificationPort notifications;
  private final Clock clock;
  private final TransactionTemplate tx;

  public RiderPayoutFacadeService(
      RiderPayoutPort payouts,
      RazorpayXPayoutPort razorpayx,
      FinancialLedgerWriterPort ledger,
      RiderPayoutNotificationPort notifications,
      Clock clock) {
    this(payouts, razorpayx, ledger, notifications, clock, null);
  }

  @Autowired
  public RiderPayoutFacadeService(
      RiderPayoutPort payouts,
      RazorpayXPayoutPort razorpayx,
      FinancialLedgerWriterPort ledger,
      RiderPayoutNotificationPort notifications,
      Clock clock,
      @Nullable PlatformTransactionManager transactionManager) {
    this.payouts = payouts;
    this.razorpayx = razorpayx;
    this.ledger = ledger;
    this.notifications = notifications;
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
      LocalDate cycleFrom,
      String status,
      UUID zoneId,
      Integer page,
      Integer limit) {
    requireFinanceRole(principal);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    String storageStatus = parseStatusFilter(status);
    ListFilter filter =
        new ListFilter(storageStatus, cycleFrom, zoneId, pageLimit, (pageNum - 1) * pageLimit);
    ListResult result = payouts.list(filter);
    SummarySnapshot summary = payouts.summary(cycleFrom, zoneId);

    List<Map<String, Object>> items = new ArrayList<>();
    for (PayoutRecord row : result.payouts()) {
      items.add(toListItem(row));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "summary",
        Map.of(
            "total_pending", summary.totalPending(),
            "total_pending_amount", MoneyFormats.paiseToRupees(summary.totalPendingAmountPaise()),
            "total_held", summary.totalHeld(),
            "total_held_amount", MoneyFormats.paiseToRupees(summary.totalHeldAmountPaise()),
            "total_released_this_cycle", summary.totalReleasedThisCycle(),
            "total_released_amount",
                MoneyFormats.paiseToRupees(summary.totalReleasedAmountPaise())));
    data.put("payouts", items);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  @Transactional(readOnly = true)
  public PagedResult ledger(
      MedmatePrincipal principal,
      UUID riderId,
      LocalDate from,
      LocalDate to,
      Integer page,
      Integer limit) {
    requireFinanceRole(principal);
    RiderSnapshot rider =
        payouts
            .findRider(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? 50 : Math.min(limit, MAX_LIMIT);
    long total = payouts.countEarnings(riderId, from, to);
    List<EarningsEntry> entries =
        payouts.listEarnings(riderId, from, to, pageLimit, (pageNum - 1) * pageLimit);

    List<Map<String, Object>> entryMaps = new ArrayList<>();
    for (EarningsEntry e : entries) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("date", e.date() == null ? null : e.date().toString());
      m.put("order_id", e.orderId() == null ? null : e.orderId().toString());
      m.put("order_number", e.orderNumber());
      m.put("base_pay", MoneyFormats.paiseToRupees(e.basePayPaise()));
      m.put("tip", MoneyFormats.paiseToRupees(e.tipPaise()));
      m.put("incentive_bonus", MoneyFormats.paiseToRupees(e.incentiveBonusPaise()));
      m.put("total", MoneyFormats.paiseToRupees(e.totalPaise()));
      m.put("on_time", e.onTime());
      m.put(
          "distance_km",
          e.distanceKm() == null
              ? 0.0
              : e.distanceKm().setScale(1, java.math.RoundingMode.HALF_UP).doubleValue());
      m.put("completed_at", e.completedAt() == null ? null : e.completedAt().toString());
      entryMaps.add(m);
    }

    Map<String, Object> cycleSummary = cycleSummary(riderId, from, to);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", rider.id().toString());
    data.put("rider_name", rider.name());
    data.put("entries", entryMaps);
    data.put("cycle_summary", cycleSummary);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, total));
  }

  /**
   * Claim → Razorpay (outside TX) → finalize+ledger. Avoids rolling back a committed claim after a
   * successful provider payout.
   */
  public Map<String, Object> release(
      MedmatePrincipal principal,
      UUID riderId,
      UUID payoutId,
      String notes,
      String idempotencyKey) {
    requireFinanceRole(principal);
    String key = requireIdempotencyKey(idempotencyKey);

    var existing = payouts.findByIdempotencyKey(key);
    if (existing.isPresent()) {
      PayoutRecord row = existing.get();
      if (!row.id().equals(payoutId) || !row.riderId().equals(riderId)) {
        throw new AppException(
            "IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key already used for another payout", 409);
      }
      return releaseResponse(row);
    }

    if (payoutId == null) {
      throw new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404);
    }
    PayoutRecord payout =
        payouts
            .findById(payoutId)
            .orElseThrow(
                () -> new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404));
    if (!payout.riderId().equals(riderId)) {
      throw new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404);
    }

    rejectInvalidReleaseState(payout);

    RiderSnapshot rider =
        payouts
            .findRider(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));

    long floatLimit = payouts.codFloatLimitPaise();
    if (rider.codInHandPaise() > floatLimit) {
      throw new AppException("COD_UNRESOLVED", "Rider cod_in_hand still > cod_float_limit", 422);
    }

    if (payout.netPayoutPaise() < RiderPayoutStatuses.MIN_RELEASE_PAISE) {
      inTx(
          () ->
              payouts.markBelowThreshold(
                  payoutId,
                  notes == null ? "Below Rs 100 threshold" : notes.trim(),
                  clock.instant()));
      throw new AppException("PAYOUT_BELOW_THRESHOLD", "net_payout < Rs 100", 422);
    }

    payouts
        .findPaymentInstrument(riderId)
        .orElseThrow(
            () ->
                new AppException(
                    "RIDER_NO_PAYMENT_DETAILS", "Rider has no registered UPI/bank account", 422));

    Instant now = clock.instant();
    Boolean claimed = inTx(() -> payouts.claimForRelease(payoutId, riderId, key, now));
    if (!Boolean.TRUE.equals(claimed)) {
      var replay = payouts.findByIdempotencyKey(key);
      if (replay.isPresent()) {
        return releaseResponse(replay.get());
      }
      PayoutRecord current =
          payouts
              .findById(payoutId)
              .orElseThrow(
                  () -> new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404));
      rejectInvalidReleaseState(current);
      throw new AppException("PAYOUT_CONFLICT", "Could not claim payout for release", 409);
    }

    PayoutResult result;
    try {
      result =
          razorpayx.initiatePayout(
              new PayoutRequest(riderId, payoutId, payout.netPayoutPaise(), "0000", "XXXX0000"));
    } catch (RuntimeException ex) {
      // Provider never accepted — safe to retry once after 24h
      inTx(
          () -> {
            if (payout.retryCount() >= 1) {
              payouts.markFailed(payoutId, key, messageOf(ex), now);
              notifications.payoutFailed(riderId, payoutId, messageOf(ex));
            } else {
              payouts.scheduleRetry(payoutId, key, messageOf(ex), now.plus(RETRY_AFTER), now);
            }
          });
      if (ex instanceof AppException app) {
        if ("RAZORPAY_PAYOUT_FAILED".equals(app.code()) || "RAZORPAY_ERROR".equals(app.code())) {
          throw new AppException("RAZORPAY_PAYOUT_FAILED", app.getMessage(), 502);
        }
        throw app;
      }
      throw new AppException("RAZORPAY_PAYOUT_FAILED", "Failed to initiate payout", 502);
    }

    try {
      inTx(
          () -> {
            if (!payouts.finalizeRelease(
                payoutId, principal.subject(), now, result.razorpayxPayoutId(), notes, key, now)) {
              throw new AppException("PAYOUT_CONFLICT", "Could not finalize payout release", 409);
            }
            payouts.adjustEarningsWallet(riderId, -payout.netPayoutPaise(), now);
            writeLedger(payout);
            notifications.payoutReleased(
                riderId, payoutId, payout.netPayoutPaise(), result.razorpayxPayoutId());
          });
    } catch (RuntimeException ex) {
      // Provider already accepted — NEVER scheduleRetry (would double-disburse). Mark FAILED for
      // ops reconcile using stored payout id in the error trail.
      String err =
          "Provider accepted payout "
              + result.razorpayxPayoutId()
              + " but finalize failed: "
              + messageOf(ex);
      inTx(
          () -> {
            payouts.markFailed(payoutId, key, err, now);
            notifications.payoutFailed(riderId, payoutId, err);
          });
      if (ex instanceof AppException app) {
        throw app;
      }
      throw new AppException("PAYOUT_CONFLICT", err, 409);
    }

    PayoutRecord updated =
        payouts
            .findById(payoutId)
            .orElseThrow(
                () -> new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404));
    return releaseResponse(updated);
  }

  /** Not @Transactional — each nested release commits claim/finalize independently. */
  public Map<String, Object> releaseAll(
      MedmatePrincipal principal,
      Object threshold,
      LocalDate cycleFrom,
      String notes,
      String idempotencyKey) {
    requireFinanceRole(principal);
    requireIdempotencyKey(idempotencyKey);
    long maxPaise = RiderPayoutStatuses.DEFAULT_BULK_MAX_PAISE;
    if (threshold != null) {
      maxPaise = MoneyFormats.parsePositiveRupeesToPaise(threshold);
    }

    List<PayoutRecord> candidates =
        payouts.listPendingForBulk(RiderPayoutStatuses.MIN_RELEASE_PAISE, maxPaise, cycleFrom, 200);
    int attempted = candidates.size();
    int released = 0;
    long totalReleased = 0L;
    List<Map<String, Object>> failures = new ArrayList<>();

    for (PayoutRecord row : candidates) {
      String perKey = idempotencyKey.trim() + ":" + row.id();
      try {
        release(principal, row.riderId(), row.id(), notes, perKey);
        released++;
        totalReleased += row.netPayoutPaise();
      } catch (AppException ex) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("payout_id", row.id().toString());
        fail.put("rider_name", row.riderName() == null ? "" : row.riderName());
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
  public PagedResult history(MedmatePrincipal principal, Integer page, Integer limit) {
    UUID riderId = requireRider(principal);
    int pageNum = page == null || page < 1 ? 1 : page;
    int pageLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    ListResult result = payouts.listForRider(riderId, pageLimit, (pageNum - 1) * pageLimit);
    List<Map<String, Object>> items = new ArrayList<>();
    for (PayoutRecord row : result.payouts()) {
      items.add(toHistoryItem(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payouts", items);
    return new PagedResult(data, PaginationMeta.of(pageNum, pageLimit, result.total()));
  }

  private Map<String, Object> cycleSummary(UUID riderId, LocalDate from, LocalDate to) {
    Map<String, Object> summary = new LinkedHashMap<>();
    if (from != null && to != null) {
      var payout = payouts.findByRiderAndCycle(riderId, from, to);
      if (payout.isPresent()) {
        PayoutRecord p = payout.get();
        summary.put("base_earnings", MoneyFormats.paiseToRupees(p.baseEarningsPaise()));
        summary.put("incentives", MoneyFormats.paiseToRupees(p.incentivesPaise()));
        summary.put("tips", MoneyFormats.paiseToRupees(p.tipsPaise()));
        summary.put("streak_bonus", MoneyFormats.paiseToRupees(p.streakBonusPaise()));
        summary.put("cod_deducted", MoneyFormats.paiseToRupees(p.codDeductedPaise()));
        summary.put("net_payout", MoneyFormats.paiseToRupees(p.netPayoutPaise()));
        return summary;
      }
    }
    summary.put("base_earnings", MoneyFormats.paiseToRupees(0));
    summary.put("incentives", MoneyFormats.paiseToRupees(0));
    summary.put("tips", MoneyFormats.paiseToRupees(0));
    summary.put("streak_bonus", MoneyFormats.paiseToRupees(0));
    summary.put("cod_deducted", MoneyFormats.paiseToRupees(0));
    summary.put("net_payout", MoneyFormats.paiseToRupees(0));
    return summary;
  }

  private void writeLedger(PayoutRecord payout) {
    ledger.append(
        "PAYOUT_RIDER",
        payout.id(),
        "RIDER_PAYOUT",
        0L,
        payout.netPayoutPaise(),
        "Rider weekly payout",
        Map.of(
            "rider_id",
            payout.riderId().toString(),
            "cycle_from",
            payout.cycleFrom().toString(),
            "cycle_to",
            payout.cycleTo().toString()));
  }

  private static Map<String, Object> releaseResponse(PayoutRecord p) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payout_id", p.id().toString());
    data.put("rider_id", p.riderId().toString());
    data.put("net_payout", MoneyFormats.paiseToRupees(p.netPayoutPaise()));
    data.put("status", RiderPayoutStatuses.toApiStatus(p.status()));
    data.put("razorpay_payout_id", p.razorpayPayoutId());
    data.put("released_by", p.releasedBy() == null ? null : p.releasedBy().toString());
    data.put("released_at", p.releasedAt() == null ? null : p.releasedAt().toString());
    return data;
  }

  private static Map<String, Object> toListItem(PayoutRecord row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("payout_id", row.id().toString());
    m.put("rider_id", row.riderId().toString());
    m.put("rider_name", row.riderName());
    m.put("zone_name", row.zoneName());
    m.put("cycle_from", row.cycleFrom().toString());
    m.put("cycle_to", row.cycleTo().toString());
    m.put("base_earnings", MoneyFormats.paiseToRupees(row.baseEarningsPaise()));
    m.put("incentives", MoneyFormats.paiseToRupees(row.incentivesPaise()));
    m.put("tips", MoneyFormats.paiseToRupees(row.tipsPaise()));
    m.put("streak_bonus", MoneyFormats.paiseToRupees(row.streakBonusPaise()));
    m.put("cod_deducted", MoneyFormats.paiseToRupees(row.codDeductedPaise()));
    m.put("net_payout", MoneyFormats.paiseToRupees(row.netPayoutPaise()));
    m.put("status", RiderPayoutStatuses.toApiStatus(row.status()));
    m.put("payout_cycle", RiderPayoutStatuses.isoWeekLabel(row.cycleFrom()));
    return m;
  }

  private static Map<String, Object> toHistoryItem(PayoutRecord row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("payout_id", row.id().toString());
    m.put("cycle_from", row.cycleFrom().toString());
    m.put("cycle_to", row.cycleTo().toString());
    m.put("base_earnings", MoneyFormats.paiseToRupees(row.baseEarningsPaise()));
    m.put("incentives", MoneyFormats.paiseToRupees(row.incentivesPaise()));
    m.put("tips", MoneyFormats.paiseToRupees(row.tipsPaise()));
    m.put("streak_bonus", MoneyFormats.paiseToRupees(row.streakBonusPaise()));
    m.put("cod_deducted", MoneyFormats.paiseToRupees(row.codDeductedPaise()));
    m.put("net_payout", MoneyFormats.paiseToRupees(row.netPayoutPaise()));
    m.put("status", RiderPayoutStatuses.toApiStatus(row.status()));
    m.put("released_at", row.releasedAt() == null ? null : row.releasedAt().toString());
    return m;
  }

  private void rejectInvalidReleaseState(PayoutRecord payout) {
    String api = RiderPayoutStatuses.toApiStatus(payout.status());
    if (RiderPayoutStatuses.API_RELEASED.equals(api)) {
      throw new AppException("PAYOUT_ALREADY_RELEASED", "Payout already released", 409);
    }
    if (RiderPayoutStatuses.API_BELOW.equals(api)) {
      throw new AppException("PAYOUT_BELOW_THRESHOLD", "net_payout < Rs 100", 422);
    }
  }

  private static String parseStatusFilter(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return RiderPayoutStatuses.toStorageFilter(status);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_STATUS", "Invalid payout status filter", 400);
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

  private static UUID requireRider(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.RIDER) {
      throw new AppException("FORBIDDEN", "rider role required", 403);
    }
    return principal.subject();
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

  private static String messageOf(Throwable ex) {
    String msg = ex.getMessage();
    return msg == null || msg.isBlank() ? "razorpay_payout_failed" : msg;
  }
}
