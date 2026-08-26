package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.messaging.ProviderOperationStore;
import com.nammamedmate.rider.application.port.out.CashfreeRoutePort;
import com.nammamedmate.rider.application.port.out.CashfreeRoutePort.PayoutResult;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore;
import com.nammamedmate.rider.application.port.out.RiderPayoutStore.PayoutRecord;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.PeriodTotals;
import com.nammamedmate.rider.domain.CodFloatLimits;
import com.nammamedmate.rider.domain.IncentiveRules;
import com.nammamedmate.rider.domain.PayoutCycle;
import com.nammamedmate.rider.domain.PayoutCycle.Window;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderPayoutService {

  static final Duration RETRY_AFTER = Duration.ofHours(24);

  private final RiderStore riders;
  private final RiderTripEarningsStore earnings;
  private final RiderPayoutStore payouts;
  private final CashfreeRoutePort cashfree;
  private final PlatformPricingConfigStore config;
  private final OutboxPublisher outbox;
  private final Clock clock;
  private final ProviderOperationStore providerOps;

  public RiderPayoutService(
      RiderStore riders,
      RiderTripEarningsStore earnings,
      RiderPayoutStore payouts,
      CashfreeRoutePort cashfree,
      PlatformPricingConfigStore config,
      OutboxPublisher outbox,
      Clock clock) {
    this(riders, earnings, payouts, cashfree, config, outbox, clock, null);
  }

  @Autowired
  public RiderPayoutService(
      RiderStore riders,
      RiderTripEarningsStore earnings,
      RiderPayoutStore payouts,
      CashfreeRoutePort cashfree,
      PlatformPricingConfigStore config,
      OutboxPublisher outbox,
      Clock clock,
      @Nullable ProviderOperationStore providerOps) {
    this.riders = riders;
    this.earnings = earnings;
    this.payouts = payouts;
    this.cashfree = cashfree;
    this.config = config;
    this.outbox = outbox;
    this.clock = clock;
    this.providerOps = providerOps;
  }

  /** AC-003: compute previous Mon–Sun cycle for all eligible riders. */
  @Transactional
  public int computeWeeklyPayouts() {
    Instant now = clock.instant();
    Window cycle = PayoutCycle.previous(now);
    Set<UUID> riderIds = new HashSet<>(riders.listIdsForPayoutCompute());
    riderIds.addAll(earnings.distinctRidersWithEarnings(cycle.from(), cycle.to()));
    int created = 0;
    for (UUID riderId : riderIds) {
      if (computeForRider(riderId, cycle, now)) {
        created++;
      }
    }
    return created;
  }

  @Transactional
  public boolean computeForRider(UUID riderId, Window cycle, Instant now) {
    if (payouts.findByRiderAndCycle(riderId, cycle.from(), cycle.to()).isPresent()) {
      return false;
    }
    RiderRecord rider = riders.findById(riderId).orElse(null);
    if (rider == null) {
      return false;
    }
    PeriodTotals totals = earnings.sumForRider(riderId, cycle.from(), cycle.to());
    long carry = riders.payoutCarryForwardPaise(riderId);
    long streakBonus = 0L;
    if (riders.streakBonusPending(riderId)
        || rider.dailyStreakDays() >= IncentiveRules.streakDaysRequired(config)) {
      streakBonus = IncentiveRules.streakBonusPaise(config);
    }
    long codDeducted = Math.max(0L, rider.codInHandPaise());
    long gross =
        totals.basePaise() + totals.incentivesPaise() + totals.tipsPaise() + streakBonus + carry;
    long net = gross - codDeducted;
    if (net < 0) {
      net = 0;
    }
    if (totals.trips() == 0 && carry == 0 && streakBonus == 0) {
      return false;
    }

    long floatLimit = CodFloatLimits.resolvePaise(config);
    long minPayout = IncentiveRules.minPayoutPaise(config);
    String status;
    String holdReason = null;
    if (CodFloatLimits.isFloatRisk(rider.codInHandPaise(), floatLimit)) {
      status = "HELD";
      holdReason = "COD float unresolved above limit";
    } else if (net < minPayout) {
      status = "BELOW_THRESHOLD_CARRIED_FORWARD";
      riders.setPayoutCarryForward(riderId, net, now);
      if (streakBonus > 0) {
        riders.clearStreakBonusPending(riderId, now);
      }
    } else {
      status = "PENDING";
      riders.setPayoutCarryForward(riderId, 0L, now);
      if (streakBonus > 0) {
        riders.clearStreakBonusPending(riderId, now);
      }
    }

    UUID id = Ids.newId();
    PayoutRecord row =
        new PayoutRecord(
            id,
            riderId,
            cycle.from(),
            cycle.to(),
            totals.basePaise(),
            totals.incentivesPaise(),
            totals.tipsPaise(),
            streakBonus,
            carry,
            codDeducted,
            net,
            status,
            holdReason,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            now,
            now);
    payouts.insert(row);

    if ("PENDING".equals(status)) {
      attemptDisburse(row, null, false);
    }
    return true;
  }

  @Transactional
  public Map<String, Object> release(
      MedmatePrincipal principal,
      UUID riderId,
      UUID payoutId,
      String notes,
      String idempotencyKey) {
    requireFinance(principal);
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
    PayoutRecord p =
        payouts
            .findById(payoutId)
            .orElseThrow(
                () -> new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404));
    if (!p.riderId().equals(riderId)) {
      throw new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404);
    }
    if ("RELEASED".equals(p.status())) {
      throw new AppException("PAYOUT_ALREADY_RELEASED", "Payout already in RELEASED state", 409);
    }
    if ("BELOW_THRESHOLD_CARRIED_FORWARD".equals(p.status())) {
      throw new AppException("PAYOUT_BELOW_THRESHOLD", "Net payout < Rs 100 minimum", 422);
    }
    RiderRecord rider =
        riders
            .findById(riderId)
            .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
    long floatLimit = CodFloatLimits.resolvePaise(config);
    // Prior checks leave only HELD/FAILED/PENDING here.
    if (CodFloatLimits.isFloatRisk(rider.codInHandPaise(), floatLimit)) {
      throw new AppException("COD_UNRESOLVED", "Rider has unresolved COD float > limit", 422);
    }
    long minPayout = IncentiveRules.minPayoutPaise(config);
    if (p.netPayoutPaise() < minPayout) {
      throw new AppException("PAYOUT_BELOW_THRESHOLD", "Net payout < Rs 100 minimum", 422);
    }

    Instant now = clock.instant();
    if (!payouts.claimForRelease(payoutId, riderId, key, now)) {
      var replay = payouts.findByIdempotencyKey(key);
      if (replay.isPresent()) {
        return releaseResponse(replay.get());
      }
      throw new AppException("PAYOUT_CONFLICT", "Could not claim payout for release", 409);
    }

    PayoutRecord claimed =
        payouts
            .findById(payoutId)
            .orElseThrow(
                () -> new AppException("PAYOUT_NOT_FOUND", "payout_id does not exist", 404));
    PayoutRecord withNotes =
        copy(
            claimed,
            claimed.status(),
            claimed.holdReason(),
            claimed.cashfreeTransferId(),
            claimed.payoutReference(),
            notes,
            principal.subject(),
            claimed.releasedAt(),
            claimed.retryCount(),
            claimed.nextRetryAt(),
            claimed.lastAttemptAt(),
            now);
    payouts.update(withNotes);
    return attemptDisburse(withNotes, principal.subject(), true);
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

  /** AC-007: retry PENDING payouts once after 24h. */
  @Transactional
  public int retryDuePayouts() {
    Instant now = clock.instant();
    List<PayoutRecord> due = payouts.findDueForRetry(now, 100);
    int n = 0;
    for (PayoutRecord p : due) {
      attemptDisburse(p, null, false);
      n++;
    }
    return n;
  }

  private Map<String, Object> attemptDisburse(PayoutRecord p, UUID releasedBy, boolean manual) {
    Instant now = clock.instant();
    String opKey = "rider-payout:" + p.id();
    PayoutResult result = replayRiderPayout(opKey);
    if (result == null) {
      if (providerOps != null) {
        providerOps.ensurePending("PAYOUT", opKey, "cashfree");
      }
      result = cashfree.disburse(p.riderId(), p.netPayoutPaise(), p.id());
      if (providerOps != null && result.success()) {
        providerOps.markSent("PAYOUT", opKey, result.cashfreeTransferId());
      }
    }
    if (result.success()) {
      PayoutRecord released =
          copy(
              p,
              "RELEASED",
              null,
              result.cashfreeTransferId(),
              result.reference(),
              p.releaseNotes(),
              releasedBy != null ? releasedBy : p.releasedBy(),
              now,
              p.retryCount(),
              null,
              now,
              now);
      payouts.update(released);
      riders.adjustEarningsWallet(p.riderId(), -p.netPayoutPaise(), now);
      publishSms(p.riderId(), released);
      return releaseResponse(released);
    }

    // Failure path
    if (manual) {
      // Manual release: schedule one auto-retry if not yet used, else FAILED.
      if (p.retryCount() >= 1) {
        PayoutRecord failed =
            copy(
                p,
                "FAILED",
                result.error(),
                null,
                null,
                p.releaseNotes(),
                releasedBy,
                null,
                p.retryCount(),
                null,
                now,
                now);
        payouts.update(failed);
        alertFinance(failed, result.error());
        return releaseResponse(failed);
      }
      PayoutRecord pending =
          copy(
              p,
              "PENDING",
              result.error(),
              null,
              null,
              p.releaseNotes(),
              releasedBy,
              null,
              0,
              now.plus(RETRY_AFTER),
              now,
              now);
      payouts.update(pending);
      return releaseResponse(pending);
    }

    // Automatic (compute or scheduled retry). First failure sets next_retry_at; retryDue loads
    // those.
    if (p.nextRetryAt() != null) {
      PayoutRecord failed =
          copy(
              p,
              "FAILED",
              result.error(),
              null,
              null,
              p.releaseNotes(),
              p.releasedBy(),
              null,
              1,
              null,
              now,
              now);
      payouts.update(failed);
      alertFinance(failed, result.error());
      return releaseResponse(failed);
    }
    PayoutRecord scheduled =
        copy(
            p,
            "PENDING",
            result.error(),
            null,
            null,
            p.releaseNotes(),
            p.releasedBy(),
            null,
            0,
            now.plus(RETRY_AFTER),
            now,
            now);
    payouts.update(scheduled);
    return releaseResponse(scheduled);
  }

  private void publishSms(UUID riderId, PayoutRecord released) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rider_id", riderId.toString());
    payload.put("payout_id", released.id().toString());
    payload.put("net_payout_paise", released.netPayoutPaise());
    payload.put("cashfree_transfer_id", released.cashfreeTransferId());
    payload.put("channel", "SMS");
    payload.put("template", "rider_payout_success");
    outbox.publish(DomainEvent.of("rider.notification.payout_released", "rider", riderId, payload));
  }

  private void alertFinance(PayoutRecord failed, String error) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rider_id", failed.riderId().toString());
    payload.put("payout_id", failed.id().toString());
    payload.put("error", error);
    outbox.publish(DomainEvent.of("finance.alert.payout_failed", "rider", failed.id(), payload));
  }

  private static Map<String, Object> releaseResponse(PayoutRecord p) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("payout_id", p.id().toString());
    data.put("rider_id", p.riderId().toString());
    data.put("net_payout", CodFloatLimits.paiseToRupees(p.netPayoutPaise()));
    data.put("payout_status", p.status());
    data.put("cashfree_transfer_id", p.cashfreeTransferId());
    data.put("released_by", p.releasedBy() == null ? null : p.releasedBy().toString());
    data.put("released_at", p.releasedAt() == null ? null : p.releasedAt().toString());
    return data;
  }

  private PayoutResult replayRiderPayout(String opKey) {
    if (providerOps == null) {
      return null;
    }
    return providerOps
        .find("PAYOUT", opKey)
        .filter(ProviderOperationStore.Operation::hasProviderRef)
        .map(op -> new PayoutResult(true, op.providerRef(), op.providerRef(), null))
        .orElse(null);
  }

  private static PayoutRecord copy(
      PayoutRecord p,
      String status,
      String holdReason,
      String cashfreeId,
      String reference,
      String notes,
      UUID releasedBy,
      Instant releasedAt,
      int retryCount,
      Instant nextRetryAt,
      Instant lastAttemptAt,
      Instant updatedAt) {
    return new PayoutRecord(
        p.id(),
        p.riderId(),
        p.cycleFrom(),
        p.cycleTo(),
        p.baseEarningsPaise(),
        p.incentivesPaise(),
        p.tipsPaise(),
        p.streakBonusPaise(),
        p.carryForwardPaise(),
        p.codDeductedPaise(),
        p.netPayoutPaise(),
        status,
        holdReason,
        cashfreeId,
        reference,
        notes,
        releasedBy,
        releasedAt,
        retryCount,
        nextRetryAt,
        lastAttemptAt,
        p.createdAt(),
        updatedAt);
  }

  private static void requireFinance(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
