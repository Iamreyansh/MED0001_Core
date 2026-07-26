package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.CustomerOrderHistoryPort;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralEventRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralRecord;
import com.nammamedmate.customer.domain.ReferralCodes;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferralService {

  private static final int INFO_LIMIT = 30;
  private static final int APPLY_LIMIT = 5;
  private static final int APPLY_WINDOW_SECONDS = 3600;
  private static final int MINUTE = 60;
  private static final long DEFAULT_REWARD_PAISE = 10_000L;

  private final ReferralStore referrals;
  private final CustomerProfileStore profiles;
  private final CustomerOrderHistoryPort orderHistory;
  private final WalletService wallets;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final String joinBaseUrl;
  private final long rewardPaise;

  public ReferralService(
      ReferralStore referrals,
      CustomerProfileStore profiles,
      CustomerOrderHistoryPort orderHistory,
      WalletService wallets,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.referral.join-base-url:https://nammamedmate.com/join}") String joinBaseUrl,
      @Value("${medmate.referral.reward-paise:10000}") long rewardPaise) {
    this.referrals = referrals;
    this.profiles = profiles;
    this.orderHistory = orderHistory;
    this.wallets = wallets;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.joinBaseUrl =
        joinBaseUrl.endsWith("/")
            ? joinBaseUrl.substring(0, joinBaseUrl.length() - 1)
            : joinBaseUrl;
    this.rewardPaise = rewardPaise > 0 ? rewardPaise : DEFAULT_REWARD_PAISE;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyReferral(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:referral:get:" + customerId, INFO_LIMIT, MINUTE);
    ReferralRecord record = requireReferral(customerId);
    long pending =
        referrals.countEventsByReferrerAndStatus(customerId, ReferralEventStatus.PENDING);
    String link = joinBaseUrl + "?ref=" + record.referralCode();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("referral_code", record.referralCode());
    data.put("referral_link", link);
    data.put("total_referrals", record.totalReferrals());
    data.put("converted_referrals", record.convertedReferrals());
    data.put("pending_referrals", (int) pending);
    data.put("total_earned", WalletService.paiseToRupees(record.totalEarnedPaise()));
    data.put("pending_rewards", WalletService.paiseToRupees(pending * rewardPaise));
    data.put(
        "share_message",
        "Download Namma MedMate and get Rs 100 wallet credit on your first order! Use my referral code "
            + record.referralCode()
            + ". Link: "
            + link);
    return data;
  }

  @Transactional
  public Map<String, Object> applyCode(MedmatePrincipal principal, ApplyCommand cmd) {
    UUID refereeId = requireCustomerId(principal);
    rateLimit("customer:referral:apply:" + refereeId, APPLY_LIMIT, APPLY_WINDOW_SECONDS);

    if (cmd == null || cmd.referrerCode() == null || cmd.referrerCode().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "referrer_code is required", 400);
    }
    String code = ReferralCodes.normalize(cmd.referrerCode());
    if (!ReferralCodes.isValidFormat(code)) {
      throw new AppException(
          "VALIDATION_ERROR", "referrer_code must be a 7-character alphanumeric code", 400);
    }

    if (referrals.findEventByReferee(refereeId).isPresent()) {
      throw new AppException(
          "REFERRAL_ALREADY_USED", "This customer has already applied a referral code", 409);
    }
    if (orderHistory.hasPlacedAnyOrder(refereeId)) {
      throw new AppException(
          "FIRST_ORDER_ALREADY_PLACED", "Customer has already placed their first order", 409);
    }

    ReferralRecord referrer =
        referrals
            .findByCode(code)
            .orElseThrow(
                () ->
                    new AppException(
                        "REFERRAL_CODE_NOT_FOUND", "No customer with this referral code", 404));
    if (referrer.customerId().equals(refereeId)) {
      throw new AppException(
          "SELF_REFERRAL_NOT_ALLOWED", "Cannot apply your own referral code", 422);
    }

    Instant now = clock.instant();
    ReferralEventRecord event =
        new ReferralEventRecord(
            Ids.newId(),
            refereeId,
            referrer.customerId(),
            code,
            ReferralEventStatus.PENDING,
            null,
            rewardPaise,
            null,
            null,
            now,
            now);
    try {
      referrals.insertEvent(event);
    } catch (DuplicateKeyException ex) {
      throw new AppException(
          "REFERRAL_ALREADY_USED", "This customer has already applied a referral code", 409);
    }

    ReferralRecord locked = referrals.lockByCustomerId(referrer.customerId()).orElse(referrer);
    referrals.update(
        new ReferralRecord(
            locked.id(),
            locked.customerId(),
            locked.referralCode(),
            locked.totalReferrals() + 1,
            locked.convertedReferrals(),
            locked.totalEarnedPaise(),
            locked.createdAt()));

    String referrerName =
        profiles
            .findById(referrer.customerId())
            .map(CustomerProfileRecord::name)
            .filter(n -> !n.isBlank())
            .orElse("your referrer");

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("referral_event_id", event.id());
    data.put("referrer_code", code);
    data.put("status", ReferralEventStatus.PENDING.name());
    data.put(
        "message",
        "Referral code applied! You and "
            + referrerName
            + " will each receive Rs 100 wallet credit after your first order is delivered.");
    data.put("reward_amount", WalletService.paiseToRupees(rewardPaise));
    return data;
  }

  /**
   * Disburse Rs 100 wallet credit to both parties when referee's first order is DELIVERED.
   * Idempotent on event status: only a {@code PENDING} event is settled, so replays and a late
   * cancel of the same lifecycle become no-ops. Caller contract (EPIC-010 consumer): invoke only
   * for the referee's <em>first</em> order delivery — the apply gate guarantees the referral was
   * applied before any order existed, so that first delivery is the unambiguous qualifying order.
   */
  @Transactional
  public Optional<ReferralEventRecord> onRefereeOrderDelivered(
      UUID refereeCustomerId, UUID orderId) {
    if (refereeCustomerId == null || orderId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id and order_id are required", 400);
    }
    Optional<ReferralEventRecord> found = referrals.findEventByReferee(refereeCustomerId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    ReferralEventRecord locked = referrals.lockEventById(found.get().id()).orElse(found.get());
    if (locked.status() != ReferralEventStatus.PENDING) {
      return Optional.of(locked);
    }

    Instant now = clock.instant();
    String idemBase = "referral-reward:" + locked.id();
    wallets.systemCredit(
        locked.refereeCustomerId(),
        locked.rewardAmountPaise(),
        "Referral reward for first delivered order",
        locked.id().toString(),
        idemBase + ":referee");
    wallets.systemCredit(
        locked.referrerCustomerId(),
        locked.rewardAmountPaise(),
        "Referral reward for invited customer first order",
        locked.id().toString(),
        idemBase + ":referrer");

    ReferralEventRecord rewarded =
        new ReferralEventRecord(
            locked.id(),
            locked.refereeCustomerId(),
            locked.referrerCustomerId(),
            locked.referralCode(),
            ReferralEventStatus.REWARDED,
            orderId,
            locked.rewardAmountPaise(),
            now,
            now,
            locked.createdAt(),
            now);
    referrals.updateEvent(rewarded);

    ReferralRecord referrer =
        referrals
            .lockByCustomerId(locked.referrerCustomerId())
            .orElseGet(() -> requireReferral(locked.referrerCustomerId()));
    referrals.update(
        new ReferralRecord(
            referrer.id(),
            referrer.customerId(),
            referrer.referralCode(),
            referrer.totalReferrals(),
            referrer.convertedReferrals() + 1,
            referrer.totalEarnedPaise() + locked.rewardAmountPaise(),
            referrer.createdAt()));
    return Optional.of(rewarded);
  }

  /**
   * Cancel pending referral when referee's first order is cancelled before delivery. Only a {@code
   * PENDING} event transitions to {@code CANCELLED}; an already-{@code REWARDED} lifecycle is left
   * untouched, so a delivered-then-cancelled race can never claw back a paid reward. Caller
   * contract (EPIC-010 consumer): invoke only for the referee's first order.
   */
  @Transactional
  public Optional<ReferralEventRecord> onRefereeFirstOrderCancelled(
      UUID refereeCustomerId, UUID orderId) {
    if (refereeCustomerId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id is required", 400);
    }
    Optional<ReferralEventRecord> found = referrals.findEventByReferee(refereeCustomerId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    ReferralEventRecord locked = referrals.lockEventById(found.get().id()).orElse(found.get());
    if (locked.status() != ReferralEventStatus.PENDING) {
      return Optional.of(locked);
    }
    Instant now = clock.instant();
    ReferralEventRecord cancelled =
        new ReferralEventRecord(
            locked.id(),
            locked.refereeCustomerId(),
            locked.referrerCustomerId(),
            locked.referralCode(),
            ReferralEventStatus.CANCELLED,
            orderId,
            locked.rewardAmountPaise(),
            null,
            null,
            locked.createdAt(),
            now);
    referrals.updateEvent(cancelled);
    return Optional.of(cancelled);
  }

  private ReferralRecord requireReferral(UUID customerId) {
    return referrals.findByCustomerId(customerId).orElseGet(() -> createDefault(customerId));
  }

  private ReferralRecord createDefault(UUID customerId) {
    Instant now = clock.instant();
    String code = ReferralCodes.generateUnique(referrals::codeExists);
    ReferralRecord created = new ReferralRecord(Ids.newId(), customerId, code, 0, 0, 0L, now);
    try {
      return referrals.insert(created);
    } catch (DuplicateKeyException | IllegalStateException ex) {
      return referrals
          .findByCustomerId(customerId)
          .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
    }
  }

  private UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429);
    }
  }

  public record ApplyCommand(String referrerCode) {}
}
