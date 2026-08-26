package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.CustomerOrderHistoryPort;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore;
import com.nammamedmate.customer.application.port.out.ReferralStore.AdminOverviewChips;
import com.nammamedmate.customer.application.port.out.ReferralStore.AdminReferralRow;
import com.nammamedmate.customer.application.port.out.ReferralStore.ProgramSettingsRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralEventRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.TopReferrerRow;
import com.nammamedmate.customer.domain.ReferralCodes;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Referral programme (EPIC-002 STORY-005 + EPIC-013 STORY-005).
 *
 * <p>Intentional EPIC-002 contract keep (plan 2C): MED**** 7-char codes (not story 8-char
 * alphanumeric); apply path {@code POST /customers/me/referral/apply} (not {@code
 * /referral/apply}); error codes {@code REFERRAL_ALREADY_USED} / {@code SELF_REFERRAL_NOT_ALLOWED}
 * (not story aliases); event statuses PENDING/REWARDED/CANCELLED (admin API maps
 * REWARDED→CONVERTED).
 */
@Service
public class ReferralService {

  private static final int INFO_LIMIT = 30;
  private static final int APPLY_LIMIT = 5;
  private static final int APPLY_WINDOW_SECONDS = 3600;
  private static final int INVITE_LIMIT = 30;
  private static final int MINUTE = 60;
  private static final long DEFAULT_REWARD_PAISE = 10_000L;
  private static final int DEFAULT_EXPIRY_DAYS = 365;
  private static final Set<String> INVITE_CHANNELS =
      Set.of("WHATSAPP", "SMS", "EMAIL", "COPY_LINK", "OTHER");
  private static final Set<AuthRole> ADMIN_READ =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);

  private final ReferralStore referrals;
  private final CustomerProfileStore profiles;
  private final CustomerOrderHistoryPort orderHistory;
  private final WalletService wallets;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final String joinBaseUrl;

  public ReferralService(
      ReferralStore referrals,
      CustomerProfileStore profiles,
      CustomerOrderHistoryPort orderHistory,
      WalletService wallets,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.referral.join-base-url:https://nammamedmate.com/join}")
          String joinBaseUrl) {
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
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyReferral(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:referral:get:" + customerId, INFO_LIMIT, MINUTE);
    ReferralRecord record = requireReferral(customerId);
    ProgramSettingsRecord settings = safeSettings();
    long pending =
        referrals.countEventsByReferrerAndStatus(customerId, ReferralEventStatus.PENDING);
    String link = joinBaseUrl + "?ref=" + record.referralCode();
    long pendingPaise = pending * settings.rewardForReferrerPaise();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("referral_code", record.referralCode());
    data.put("referral_link", link);
    data.put("total_referrals", record.totalReferrals());
    data.put("converted_referrals", record.convertedReferrals());
    data.put("pending_referrals", (int) pending);
    data.put("total_earned", WalletService.paiseToRupees(record.totalEarnedPaise()));
    data.put("pending_rewards", WalletService.paiseToRupees(pendingPaise));
    data.put("total_earned_rs", WalletService.paiseToRupees(record.totalEarnedPaise()));
    data.put("pending_rewards_rs", WalletService.paiseToRupees(pendingPaise));
    Map<String, Object> earnings = new LinkedHashMap<>();
    earnings.put("friends_joined", record.totalReferrals());
    earnings.put("total_earned_rs", WalletService.paiseToRupees(record.totalEarnedPaise()));
    earnings.put("pending_rs", WalletService.paiseToRupees(pendingPaise));
    data.put("earnings_stats", earnings);
    data.put(
        "share_message",
        "Download Namma MedMate and get Rs 100 wallet credit on your first order! Use my referral code "
            + record.referralCode()
            + ". Link: "
            + link);
    return data;
  }

  @Transactional
  public Map<String, Object> invite(MedmatePrincipal principal, InviteCommand cmd) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:referral:invite:" + customerId, INVITE_LIMIT, MINUTE);
    String channel = normalizeChannel(cmd == null ? null : cmd.channel());
    ReferralRecord record = requireReferral(customerId);
    Instant now = clock.instant();
    referrals.insertShareEvent(Ids.newId(), customerId, channel, now);
    String link = joinBaseUrl + "?ref=" + record.referralCode();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("referral_code", record.referralCode());
    data.put("referral_link", link);
    data.put(
        "share_text",
        "Use my code "
            + record.referralCode()
            + " to get Rs 100 off your first order on Namma MedMate!");
    data.put("channel", channel);
    data.put("share_logged_at", now.toString());
    data.put("share_count", referrals.countShareEvents(customerId));
    return data;
  }

  @Transactional
  public Map<String, Object> applyCode(MedmatePrincipal principal, ApplyCommand cmd) {
    UUID refereeId = requireCustomerId(principal);
    rateLimit("customer:referral:apply:" + refereeId, APPLY_LIMIT, APPLY_WINDOW_SECONDS);

    ProgramSettingsRecord settings = safeSettings();
    if (!settings.active()) {
      throw new AppException(
          "REFERRAL_PROGRAM_PAUSED", "Referral program is currently paused", 403);
    }

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
    CustomerProfileRecord profile =
        profiles
            .findById(refereeId)
            .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
    Instant signupCutoff = clock.instant().minusSeconds(30L * 60);
    if (profile.createdAt() == null || profile.createdAt().isBefore(signupCutoff)) {
      throw new AppException(
          "REFERRAL_SIGNUP_ONLY", "Referral codes can only be applied during signup", 422);
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
    long referrerReward = settings.rewardForReferrerPaise();
    long refereeReward = settings.rewardForRefereePaise();
    ReferralEventRecord event =
        new ReferralEventRecord(
            Ids.newId(),
            refereeId,
            referrer.customerId(),
            code,
            ReferralEventStatus.PENDING,
            null,
            referrerReward,
            refereeReward,
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
            .filter(Objects::nonNull)
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
            + " will each receive Rs "
            + WalletService.paiseToRupees(refereeReward).toPlainString()
            + " wallet credit after your first order is delivered.");
    data.put("reward_amount", WalletService.paiseToRupees(refereeReward));
    return data;
  }

  /**
   * Disburse wallet credit to both parties when referee's first order is DELIVERED. Uses reward
   * amounts snapshotted on the event at apply time (from program settings). Idempotent on event
   * status. Wallet credits expire via WalletService CREDIT_TTL (365d; aligns with default
   * reward_expiry_days).
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
        locked.refereeRewardAmountPaise(),
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
            locked.refereeRewardAmountPaise(),
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
            locked.refereeRewardAmountPaise(),
            null,
            null,
            locked.createdAt(),
            now);
    referrals.updateEvent(cancelled);
    return Optional.of(cancelled);
  }

  public record AdminOverviewResult(Map<String, Object> data, PaginationMeta meta) {}

  @Transactional(readOnly = true)
  public AdminOverviewResult adminOverview(
      MedmatePrincipal principal, String status, Integer page, Integer limit) {
    requireAdminRead(principal);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    ReferralEventStatus filter = parseAdminStatusFilter(status);
    AdminOverviewChips chips = referrals.chips();
    long converted = chips.convertedReferrals();
    long cacPaise =
        converted == 0 ? 0L : Math.round((double) chips.totalRewardsPaidPaise() / converted);

    Map<String, Object> chipView = new LinkedHashMap<>();
    chipView.put("total_referrals", chips.totalReferrals());
    chipView.put("converted_referrals", converted);
    chipView.put("pending_rewards_rs", WalletService.paiseToRupees(chips.pendingRewardsPaise()));
    chipView.put("referral_cac_rs", WalletService.paiseToRupees(cacPaise).longValue());
    chipView.put("referral_mrr_rs", 0);

    List<Map<String, Object>> top = new ArrayList<>();
    for (TopReferrerRow row : referrals.topReferrers(10)) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("customer_id", row.customerId());
      item.put("name", row.name() == null || row.name().isBlank() ? "Customer" : row.name());
      item.put("total_referrals", row.totalReferrals());
      item.put("converted", row.converted());
      item.put("total_earned_rs", WalletService.paiseToRupees(row.totalEarnedPaise()));
      top.add(item);
    }

    List<Map<String, Object>> list = new ArrayList<>();
    for (AdminReferralRow row : referrals.listAdminReferrals(filter, lim, (p - 1) * lim)) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", row.id());
      item.put("referrer_name", blankToDash(row.referrerName()));
      item.put("referee_name", blankToDash(row.refereeName()));
      item.put("referee_phone", maskPhone(row.refereePhone()));
      item.put("status", toAdminStatus(row.status()));
      item.put(
          "reward_credited_at",
          row.rewardCreditedAt() == null ? null : row.rewardCreditedAt().toString());
      list.add(item);
    }

    long total = referrals.countAdminReferrals(filter);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("chips", chipView);
    data.put("top_referrers", top);
    data.put("referrals", list);
    return new AdminOverviewResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getProgram(MedmatePrincipal principal) {
    requireAdminRead(principal);
    return toProgramView(safeSettings());
  }

  @Transactional
  public Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd) {
    requireAdminSuper(principal);
    ProgramSettingsRecord current = safeSettings();
    long referrerPaise =
        cmd != null && cmd.rewardForReferrerRs() != null
            ? rupeesToPaise(cmd.rewardForReferrerRs())
            : current.rewardForReferrerPaise();
    long refereePaise =
        cmd != null && cmd.rewardForRefereeRs() != null
            ? rupeesToPaise(cmd.rewardForRefereeRs())
            : current.rewardForRefereePaise();
    boolean active = cmd != null && cmd.isActive() != null ? cmd.isActive() : current.active();
    int expiry =
        cmd != null && cmd.rewardExpiryDays() != null
            ? cmd.rewardExpiryDays()
            : current.rewardExpiryDays();
    String conditions =
        cmd != null && cmd.conditions() != null ? cmd.conditions() : current.conditions();
    if (referrerPaise <= 0 || refereePaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "reward amounts must be positive", 400);
    }
    if (expiry <= 0) {
      throw new AppException("VALIDATION_ERROR", "reward_expiry_days must be positive", 400);
    }
    Instant now = clock.instant();
    ProgramSettingsRecord updated =
        new ProgramSettingsRecord(
            current.id(),
            referrerPaise,
            refereePaise,
            active,
            expiry,
            conditions,
            principal.subject(),
            now);
    referrals.updateProgramSettings(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("updated_at", now.toString());
    data.put("updated_by", principal.subject());
    return data;
  }

  private ProgramSettingsRecord safeSettings() {
    try {
      return referrals.getProgramSettings();
    } catch (RuntimeException ex) {
      Instant now = clock.instant();
      return new ProgramSettingsRecord(
          ReferralStore.PROGRAM_SETTINGS_ID,
          DEFAULT_REWARD_PAISE,
          DEFAULT_REWARD_PAISE,
          true,
          DEFAULT_EXPIRY_DAYS,
          "Reward credited after referee's first DELIVERED order. One code per customer.",
          null,
          now);
    }
  }

  private Map<String, Object> toProgramView(ProgramSettingsRecord s) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reward_for_referrer_rs", WalletService.paiseToRupees(s.rewardForReferrerPaise()));
    data.put("reward_for_referee_rs", WalletService.paiseToRupees(s.rewardForRefereePaise()));
    data.put("is_active", s.active());
    data.put("reward_expiry_days", s.rewardExpiryDays());
    data.put("conditions", s.conditions());
    return data;
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

  private static void requireAdminRead(MedmatePrincipal principal) {
    if (principal == null || !ADMIN_READ.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireAdminSuper(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may update program settings", 403);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429);
    }
  }

  private static String normalizeChannel(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "channel is required", 400);
    }
    String channel = raw.trim().toUpperCase(Locale.ROOT);
    if (!INVITE_CHANNELS.contains(channel)) {
      throw new AppException(
          "VALIDATION_ERROR", "channel must be one of WHATSAPP, SMS, EMAIL, COPY_LINK, OTHER", 400);
    }
    return channel;
  }

  private static ReferralEventStatus parseAdminStatusFilter(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String s = status.trim().toUpperCase(Locale.ROOT);
    return switch (s) {
      case "PENDING" -> ReferralEventStatus.PENDING;
      case "CONVERTED", "REWARDED" -> ReferralEventStatus.REWARDED;
      case "EXPIRED", "CANCELLED" -> ReferralEventStatus.CANCELLED;
      default ->
          throw new AppException(
              "VALIDATION_ERROR", "status must be PENDING, CONVERTED, or EXPIRED", 400);
    };
  }

  private static String toAdminStatus(ReferralEventStatus status) {
    return switch (status) {
      case PENDING -> "PENDING";
      case REWARDED -> "CONVERTED";
      case CANCELLED -> "EXPIRED";
    };
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.length() < 6) {
      return phone;
    }
    if (phone.startsWith("+")) {
      return phone.substring(0, 4) + "xxxxxxxxx";
    }
    return phone.substring(0, 2) + "xxxxxxxxx";
  }

  private static String blankToDash(String name) {
    return name == null || name.isBlank() ? "—" : name;
  }

  private static long rupeesToPaise(Number rupees) {
    return BigDecimal.valueOf(rupees.doubleValue())
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValue();
  }

  public record ApplyCommand(String referrerCode) {}

  public record InviteCommand(String channel) {}

  public record PatchProgramCommand(
      Number rewardForReferrerRs,
      Number rewardForRefereeRs,
      Boolean isActive,
      Integer rewardExpiryDays,
      String conditions) {}
}
