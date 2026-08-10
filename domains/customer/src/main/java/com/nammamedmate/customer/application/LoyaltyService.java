package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.LoyaltyCartPort;
import com.nammamedmate.customer.application.port.out.LoyaltyStore;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.OverviewStats;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.ProgramSettingsRecord;
import com.nammamedmate.customer.domain.LoyaltyTiers;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import com.nammamedmate.customer.domain.WalletCreditReason;
import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoyaltyService {

  private static final int STATUS_LIMIT = 30;
  private static final int TX_LIST_LIMIT = 20;
  private static final int REDEEM_LIMIT = 20;
  private static final int ADMIN_LIMIT = 30;
  private static final int MINUTE = 60;
  private static final int EXPIRY_BATCH = 200;
  private static final Set<AuthRole> ADMIN_READ =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_FINANCE);

  private final LoyaltyStore loyalty;
  private final LoyaltyCartPort carts;
  private final WalletService wallets;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final OutboxPublisher outbox;

  public LoyaltyService(
      LoyaltyStore loyalty,
      LoyaltyCartPort carts,
      WalletService wallets,
      RateLimiter rateLimiter,
      Clock clock,
      OutboxPublisher outbox) {
    this.loyalty = loyalty;
    this.carts = carts;
    this.wallets = wallets;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.outbox = outbox;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyStatus(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:loyalty:get:" + customerId, STATUS_LIMIT, MINUTE);
    LoyaltyRecord record = requireLoyalty(customerId);
    return toStatusView(record, safeSettings());
  }

  @Transactional(readOnly = true)
  public TxPage listMyTransactions(
      MedmatePrincipal principal, Integer page, Integer limit, String order, String type) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:loyalty:tx:" + customerId, TX_LIST_LIMIT, MINUTE);
    requireLoyalty(customerId);

    String effectiveOrder = (order == null || order.isBlank()) ? "desc" : order;
    PageRequest pageReq = PageRequest.normalize(page, limit, "created_at", effectiveOrder);
    LoyaltyTxType filter = LoyaltyTxType.parseOptional(type);

    List<Map<String, Object>> items =
        loyalty
            .listTransactions(
                customerId, filter, pageReq.order(), pageReq.limit(), pageReq.offset())
            .stream()
            .map(LoyaltyService::toTxView)
            .toList();
    long total = loyalty.countTransactions(customerId, filter);
    return new TxPage(items, PaginationMeta.of(pageReq.page(), pageReq.limit(), total));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> adminLoyaltySummary(UUID customerId) {
    LoyaltyRecord record = requireLoyalty(customerId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tier", record.tier());
    data.put("points_balance", record.pointsBalance());
    data.put("points_earned_lifetime", record.pointsEarnedLifetime());
    return data;
  }

  /**
   * Award points after order DELIVERED. Idempotent on order id. Uses program earn rate on item
   * total (paise).
   */
  @Transactional
  public Optional<LoyaltyTxRecord> awardForDeliveredOrder(
      UUID customerId, UUID orderId, String orderDisplayId, long itemTotalPaise) {
    if (customerId == null || orderId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id and order_id are required", 400);
    }
    ProgramSettingsRecord settings = safeSettings();
    int points = LoyaltyTiers.pointsForOrderPaise(itemTotalPaise, settings.earnRateRsPerPoint());
    if (points <= 0) {
      return Optional.empty();
    }
    Optional<LoyaltyTxRecord> existing =
        loyalty.findByReferenceAndType(orderId, LoyaltyTxType.EARN);
    if (existing.isPresent()) {
      return existing;
    }

    Instant now = clock.instant();
    LoyaltyRecord locked = lockOrCreate(customerId, now);
    String previousTier = locked.tier();
    int newBalance = locked.pointsBalance() + points;
    int newLifetime = locked.pointsEarnedLifetime() + points;
    String newTier =
        LoyaltyTiers.fromLifetimePoints(
            newLifetime,
            settings.tierSilverPts(),
            settings.tierGoldPts(),
            settings.tierPlatinumPts());

    String display =
        orderDisplayId == null || orderDisplayId.isBlank() ? orderId.toString() : orderDisplayId;
    long rupees = itemTotalPaise / 100;
    String description =
        truncate("Points for order #" + display + " (Rs " + rupees + " spent)", 255);
    Instant expiresAt = now.plus(settings.pointsExpiryDays(), ChronoUnit.DAYS);

    LoyaltyTxRecord tx =
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            points,
            newBalance,
            description,
            orderId,
            now,
            expiresAt,
            points,
            null);
    try {
      loyalty.insertTransaction(tx);
    } catch (DuplicateKeyException ex) {
      return loyalty.findByReferenceAndType(orderId, LoyaltyTxType.EARN);
    }

    LoyaltyRecord updated =
        new LoyaltyRecord(locked.id(), customerId, newTier, newBalance, newLifetime, now);
    loyalty.update(updated);
    loyalty.syncCustomerLoyaltyPoints(customerId, newBalance);
    publishTierChangeIfNeeded(customerId, previousTier, newTier);
    return Optional.of(tx);
  }

  /**
   * Reverse points for a previously awarded delivered order that was refunded. Lifetime points are
   * unchanged (tier ratchet). Idempotent on order id.
   */
  @Transactional
  public Optional<LoyaltyTxRecord> reverseForRefundedOrder(
      UUID customerId, UUID orderId, String orderDisplayId) {
    if (customerId == null || orderId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id and order_id are required", 400);
    }
    Optional<LoyaltyTxRecord> earn = loyalty.findByReferenceAndType(orderId, LoyaltyTxType.EARN);
    if (earn.isEmpty()) {
      return Optional.empty();
    }
    Optional<LoyaltyTxRecord> existingReverse =
        loyalty.findByReferenceAndType(orderId, LoyaltyTxType.REVERSE);
    if (existingReverse.isPresent()) {
      return existingReverse;
    }

    Instant now = clock.instant();
    LoyaltyRecord locked = lockOrCreate(customerId, now);
    LoyaltyTxRecord earnTx = earn.get();
    int reversePoints = earnTx.points();
    int newBalance = Math.max(0, locked.pointsBalance() - reversePoints);
    ProgramSettingsRecord settings = safeSettings();
    String tier =
        LoyaltyTiers.fromLifetimePoints(
            locked.pointsEarnedLifetime(),
            settings.tierSilverPts(),
            settings.tierGoldPts(),
            settings.tierPlatinumPts());

    String display =
        orderDisplayId == null || orderDisplayId.isBlank() ? orderId.toString() : orderDisplayId;
    String description = truncate("Points reversed for refunded order #" + display, 255);

    LoyaltyTxRecord tx =
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.REVERSE,
            -reversePoints,
            newBalance,
            description,
            orderId,
            now);
    try {
      loyalty.insertTransaction(tx);
    } catch (DuplicateKeyException ex) {
      return loyalty.findByReferenceAndType(orderId, LoyaltyTxType.REVERSE);
    }

    if (earnTx.remainingPoints() != null) {
      if (earnTx.remainingPoints() > 0) {
        loyalty.updateEarnRemaining(earnTx.id(), 0);
      }
    }

    LoyaltyRecord updated =
        new LoyaltyRecord(
            locked.id(), customerId, tier, newBalance, locked.pointsEarnedLifetime(), now);
    loyalty.update(updated);
    loyalty.syncCustomerLoyaltyPoints(customerId, newBalance);
    return Optional.of(tx);
  }

  @Transactional
  public Map<String, Object> redeem(MedmatePrincipal principal, int pointsToRedeem, UUID cartId) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:loyalty:redeem:" + customerId, REDEEM_LIMIT, MINUTE);
    if (cartId == null) {
      throw new AppException("VALIDATION_ERROR", "cart_id is required", 400);
    }
    ProgramSettingsRecord settings = safeSettings();
    if (pointsToRedeem < settings.minPointsPerRedemption()) {
      throw new AppException(
          "BELOW_MINIMUM_REDEMPTION",
          "Minimum redemption is " + settings.minPointsPerRedemption() + " points",
          400);
    }
    long cartTotalPaise =
        carts
            .findCartItemTotalPaise(customerId, cartId)
            .orElseThrow(() -> new AppException("CART_NOT_FOUND", "Cart not found", 404));

    long cartRupees = cartTotalPaise / 100;
    int cap = (int) Math.floor(cartRupees * (settings.maxRedemptionPctPerOrder() / 100.0));
    if (pointsToRedeem > cap) {
      throw new AppException(
          "EXCEEDS_REDEMPTION_CAP",
          "Redemption exceeds " + settings.maxRedemptionPctPerOrder() + "% of cart value",
          400);
    }

    Instant now = clock.instant();
    LoyaltyRecord locked = lockOrCreate(customerId, now);
    if (locked.pointsBalance() < pointsToRedeem) {
      throw new AppException("INSUFFICIENT_POINTS", "Insufficient loyalty points", 400);
    }

    int newBalance = locked.pointsBalance() - pointsToRedeem;
    String tier =
        LoyaltyTiers.fromLifetimePoints(
            locked.pointsEarnedLifetime(),
            settings.tierSilverPts(),
            settings.tierGoldPts(),
            settings.tierPlatinumPts());

    consumeEarnBatchesFifo(customerId, pointsToRedeem);

    UUID txId = Ids.newId();
    String description =
        truncate(
            "Redeemed "
                + pointsToRedeem
                + " points (Rs "
                + walletCreditRs(pointsToRedeem, settings)
                + " wallet credit)",
            255);
    LoyaltyTxRecord tx =
        new LoyaltyTxRecord(
            txId,
            customerId,
            LoyaltyTxType.REDEEM,
            -pointsToRedeem,
            newBalance,
            description,
            cartId,
            now);
    loyalty.insertTransaction(tx);

    LoyaltyRecord updated =
        new LoyaltyRecord(
            locked.id(), customerId, tier, newBalance, locked.pointsEarnedLifetime(), now);
    loyalty.update(updated);
    loyalty.syncCustomerLoyaltyPoints(customerId, newBalance);

    long creditPaise = walletCreditPaise(pointsToRedeem, settings);
    wallets.systemCredit(
        customerId,
        creditPaise,
        description,
        cartId.toString(),
        "loyalty-redeem-" + txId,
        WalletCreditReason.PROMOTIONAL.name());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("points_redeemed", pointsToRedeem);
    data.put("wallet_credit_applied_rs", walletCreditRs(pointsToRedeem, settings));
    data.put("points_balance_after", newBalance);
    data.put("redemption_transaction_id", txId);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getProgram(MedmatePrincipal principal) {
    requireAdminRead(principal);
    rateLimit("admin:loyalty:program:get:" + principal.subject(), ADMIN_LIMIT, MINUTE);
    return toProgramView(safeSettings());
  }

  @Transactional
  public Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd) {
    requireAdminSuper(principal);
    rateLimit("admin:loyalty:program:patch:" + principal.subject(), ADMIN_LIMIT, MINUTE);
    ProgramSettingsRecord current = safeSettings();
    Instant now = clock.instant();
    ProgramSettingsRecord updated =
        new ProgramSettingsRecord(
            current.id(),
            cmd.earnRateRsPerPoint() == null
                ? current.earnRateRsPerPoint()
                : requirePositive(cmd.earnRateRsPerPoint(), "earn_rate_rs_per_point"),
            cmd.redemptionRateRsPerPoint() == null
                ? current.redemptionRateRsPerPoint()
                : requirePositiveRate(cmd.redemptionRateRsPerPoint()),
            cmd.tierSilverPts() == null
                ? current.tierSilverPts()
                : requirePositive(cmd.tierSilverPts(), "tier_silver_pts"),
            cmd.tierGoldPts() == null
                ? current.tierGoldPts()
                : requirePositive(cmd.tierGoldPts(), "tier_gold_pts"),
            cmd.tierPlatinumPts() == null
                ? current.tierPlatinumPts()
                : requirePositive(cmd.tierPlatinumPts(), "tier_platinum_pts"),
            cmd.maxRedemptionPctPerOrder() == null
                ? current.maxRedemptionPctPerOrder()
                : requirePct(cmd.maxRedemptionPctPerOrder()),
            cmd.minPointsPerRedemption() == null
                ? current.minPointsPerRedemption()
                : requirePositive(cmd.minPointsPerRedemption(), "min_points_per_redemption"),
            cmd.pointsExpiryDays() == null
                ? current.pointsExpiryDays()
                : requirePositive(cmd.pointsExpiryDays(), "points_expiry_days"),
            principal.subject(),
            now);
    if (updated.tierGoldPts() <= updated.tierSilverPts()) {
      throw new AppException(
          "VALIDATION_ERROR", "tier thresholds must be silver < gold < platinum", 400);
    }
    if (updated.tierPlatinumPts() <= updated.tierGoldPts()) {
      throw new AppException(
          "VALIDATION_ERROR", "tier thresholds must be silver < gold < platinum", 400);
    }
    loyalty.updateProgramSettings(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("updated_at", now);
    data.put("updated_by", principal.subject());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> adminOverview(MedmatePrincipal principal) {
    requireAdminRead(principal);
    rateLimit("admin:loyalty:overview:" + principal.subject(), ADMIN_LIMIT, MINUTE);
    ProgramSettingsRecord settings = safeSettings();
    Instant since30 = clock.instant().minus(30, ChronoUnit.DAYS);
    OverviewStats stats = loyalty.overviewStats(since30);
    BigDecimal liability =
        BigDecimal.valueOf(stats.totalPointsOutstanding())
            .multiply(settings.redemptionRateRsPerPoint())
            .setScale(0, RoundingMode.HALF_UP);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_points_outstanding", stats.totalPointsOutstanding());
    data.put("points_liability_rs", liability.longValue());
    data.put("avg_points_per_customer", stats.avgPointsPerCustomer().intValue());
    data.put("tier_distribution", stats.tierDistribution());
    data.put("points_earned_last_30d", stats.pointsEarnedLast30d());
    data.put("points_redeemed_last_30d", stats.pointsRedeemedLast30d());
    data.put("points_expired_last_30d", stats.pointsExpiredLast30d());
    return data;
  }

  @Transactional
  public Map<String, Object> adminAdjust(
      MedmatePrincipal principal,
      UUID customerId,
      int points,
      String reason,
      UUID referenceOrderId) {
    requireAdminSuper(principal);
    rateLimit("admin:loyalty:adjust:" + principal.subject(), ADMIN_LIMIT, MINUTE);
    if (customerId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id is required", 400);
    }
    if (points == 0) {
      throw new AppException("VALIDATION_ERROR", "points must be non-zero", 400);
    }
    if (reason == null) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    if (reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 400);
    }
    Instant now = clock.instant();
    LoyaltyRecord locked = lockOrCreate(customerId, now);
    if (points < 0) {
      if (locked.pointsBalance() < Math.abs(points)) {
        throw new AppException(
            "ADJUSTMENT_WOULD_EXCEED_BALANCE", "Adjustment exceeds current balance", 400);
      }
    }
    ProgramSettingsRecord settings = safeSettings();
    int newBalance = locked.pointsBalance() + points;
    int newLifetime =
        points > 0 ? locked.pointsEarnedLifetime() + points : locked.pointsEarnedLifetime();
    String previousTier = locked.tier();
    String newTier =
        LoyaltyTiers.fromLifetimePoints(
            newLifetime,
            settings.tierSilverPts(),
            settings.tierGoldPts(),
            settings.tierPlatinumPts());

    UUID txId = Ids.newId();
    LoyaltyTxRecord tx =
        new LoyaltyTxRecord(
            txId,
            customerId,
            LoyaltyTxType.ADJUST,
            points,
            newBalance,
            truncate(reason.trim(), 255),
            referenceOrderId,
            now,
            null,
            null,
            principal.subject());
    loyalty.insertTransaction(tx);

    if (points < 0) {
      consumeEarnBatchesFifo(customerId, Math.abs(points));
    }

    LoyaltyRecord updated =
        new LoyaltyRecord(locked.id(), customerId, newTier, newBalance, newLifetime, now);
    loyalty.update(updated);
    loyalty.syncCustomerLoyaltyPoints(customerId, newBalance);
    publishTierChangeIfNeeded(customerId, previousTier, newTier);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("customer_id", customerId);
    data.put("points_adjusted", points);
    data.put("points_balance_after", newBalance);
    data.put("transaction_id", txId);
    data.put("adjusted_by", principal.subject());
    data.put("adjusted_at", now);
    return data;
  }

  /** Nightly FIFO expiry of EARN batches past expires_at. */
  @Transactional
  public int expirePoints() {
    Instant now = clock.instant();
    int expired = 0;
    while (true) {
      List<LoyaltyTxRecord> batch = loyalty.findExpiredEarnBatches(now, EXPIRY_BATCH);
      if (batch.isEmpty()) {
        break;
      }
      for (LoyaltyTxRecord earn : batch) {
        int remaining;
        if (earn.remainingPoints() == null) {
          remaining = earn.points();
        } else {
          remaining = earn.remainingPoints();
        }
        LoyaltyRecord locked = lockOrCreate(earn.customerId(), now);
        int debit = Math.min(remaining, locked.pointsBalance());
        loyalty.updateEarnRemaining(earn.id(), 0);
        if (debit <= 0) {
          continue;
        }
        int newBalance = locked.pointsBalance() - debit;
        ProgramSettingsRecord settings = safeSettings();
        String tier =
            LoyaltyTiers.fromLifetimePoints(
                locked.pointsEarnedLifetime(),
                settings.tierSilverPts(),
                settings.tierGoldPts(),
                settings.tierPlatinumPts());
        LoyaltyTxRecord tx =
            new LoyaltyTxRecord(
                Ids.newId(),
                earn.customerId(),
                LoyaltyTxType.EXPIRE,
                -debit,
                newBalance,
                truncate("Points expired (" + debit + " pts)", 255),
                earn.id(),
                now);
        loyalty.insertTransaction(tx);
        loyalty.update(
            new LoyaltyRecord(
                locked.id(),
                earn.customerId(),
                tier,
                newBalance,
                locked.pointsEarnedLifetime(),
                now));
        loyalty.syncCustomerLoyaltyPoints(earn.customerId(), newBalance);
        expired += debit;
      }
    }
    return expired;
  }

  private void consumeEarnBatchesFifo(UUID customerId, int points) {
    int left = points;
    for (LoyaltyTxRecord batch : loyalty.findOpenEarnBatchesFifo(customerId)) {
      int rem = batch.remainingPoints() == null ? 0 : batch.remainingPoints();
      int take = Math.min(rem, left);
      if (take > 0) {
        loyalty.updateEarnRemaining(batch.id(), rem - take);
        left -= take;
      }
      if (left <= 0) {
        break;
      }
    }
  }

  private void publishTierChangeIfNeeded(UUID customerId, String previousTier, String newTier) {
    if (previousTier.equals(newTier)) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("customer_id", customerId.toString());
    payload.put("previous_tier", previousTier);
    payload.put("new_tier", newTier);
    outbox.publish(
        DomainEvent.of("customer.loyalty.tier_changed", "customer", customerId, payload));
  }

  private LoyaltyRecord requireLoyalty(UUID customerId) {
    return loyalty.findByCustomerId(customerId).orElseGet(() -> createDefault(customerId));
  }

  private LoyaltyRecord lockOrCreate(UUID customerId, Instant now) {
    return loyalty
        .lockByCustomerId(customerId)
        .orElseGet(
            () -> {
              LoyaltyRecord created = createDefault(customerId, now);
              return loyalty.lockByCustomerId(customerId).orElse(created);
            });
  }

  private LoyaltyRecord createDefault(UUID customerId) {
    return createDefault(customerId, clock.instant());
  }

  private LoyaltyRecord createDefault(UUID customerId, Instant now) {
    LoyaltyRecord created =
        new LoyaltyRecord(Ids.newId(), customerId, LoyaltyTiers.NONE, 0, 0, now);
    try {
      return loyalty.insert(created);
    } catch (DuplicateKeyException | IllegalStateException ex) {
      return loyalty
          .findByCustomerId(customerId)
          .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
    }
  }

  private ProgramSettingsRecord safeSettings() {
    try {
      return loyalty.getProgramSettings();
    } catch (RuntimeException ex) {
      return new ProgramSettingsRecord(
          LoyaltyStore.PROGRAM_SETTINGS_ID,
          100,
          BigDecimal.ONE.setScale(2),
          12,
          50,
          120,
          20,
          10,
          365,
          null,
          clock.instant());
    }
  }

  private Map<String, Object> toStatusView(LoyaltyRecord record, ProgramSettingsRecord settings) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tier", record.tier());
    data.put("points_balance", record.pointsBalance());
    data.put("points_earned_lifetime", record.pointsEarnedLifetime());
    data.put(
        "tier_progress",
        LoyaltyTiers.progress(
            record.pointsEarnedLifetime(),
            settings.tierSilverPts(),
            settings.tierGoldPts(),
            settings.tierPlatinumPts()));
    data.put(
        "tier_thresholds",
        LoyaltyTiers.thresholds(
            settings.tierSilverPts(), settings.tierGoldPts(), settings.tierPlatinumPts()));
    return data;
  }

  private static Map<String, Object> toProgramView(ProgramSettingsRecord s) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("earn_rate_rs_per_point", s.earnRateRsPerPoint());
    data.put("redemption_rate_rs_per_point", s.redemptionRateRsPerPoint());
    Map<String, Object> thresholds = new LinkedHashMap<>();
    thresholds.put("SILVER", s.tierSilverPts());
    thresholds.put("GOLD", s.tierGoldPts());
    thresholds.put("PLATINUM", s.tierPlatinumPts());
    data.put("tier_thresholds", thresholds);
    data.put("max_redemption_pct_per_order", s.maxRedemptionPctPerOrder());
    data.put("min_points_per_redemption", s.minPointsPerRedemption());
    data.put("points_expiry_days", s.pointsExpiryDays());
    return data;
  }

  private static Map<String, Object> toTxView(LoyaltyTxRecord tx) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", tx.id());
    data.put("type", tx.type().name());
    data.put("points", tx.points());
    data.put("points_balance_after", tx.pointsBalanceAfter());
    data.put("description", tx.description());
    data.put("reference_id", tx.referenceId());
    data.put("created_at", tx.createdAt());
    return data;
  }

  private static long walletCreditPaise(int points, ProgramSettingsRecord settings) {
    return BigDecimal.valueOf(points)
        .multiply(settings.redemptionRateRsPerPoint())
        .multiply(BigDecimal.valueOf(100))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private static long walletCreditRs(int points, ProgramSettingsRecord settings) {
    return BigDecimal.valueOf(points)
        .multiply(settings.redemptionRateRsPerPoint())
        .setScale(0, RoundingMode.HALF_UP)
        .longValue();
  }

  private UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
    if (!ADMIN_READ.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireAdminSuper(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Only admin_super may perform this action", 403);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may perform this action", 403);
    }
  }

  private static int requirePositive(int value, String field) {
    if (value <= 0) {
      throw new AppException("VALIDATION_ERROR", field + " must be positive", 400);
    }
    return value;
  }

  private static int requirePct(int value) {
    if (value <= 0) {
      throw new AppException(
          "VALIDATION_ERROR", "max_redemption_pct_per_order must be 1..100", 400);
    }
    if (value > 100) {
      throw new AppException(
          "VALIDATION_ERROR", "max_redemption_pct_per_order must be 1..100", 400);
    }
    return value;
  }

  private static BigDecimal requirePositiveRate(Number raw) {
    BigDecimal v = new BigDecimal(raw.toString());
    if (v.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException(
          "VALIDATION_ERROR", "redemption_rate_rs_per_point must be positive", 400);
    }
    return v;
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429);
    }
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  public record TxPage(List<Map<String, Object>> data, PaginationMeta meta) {
    public TxPage {
      data = List.copyOf(data);
    }
  }

  public record PatchProgramCommand(
      Integer earnRateRsPerPoint,
      Number redemptionRateRsPerPoint,
      Integer tierSilverPts,
      Integer tierGoldPts,
      Integer tierPlatinumPts,
      Integer maxRedemptionPctPerOrder,
      Integer minPointsPerRedemption,
      Integer pointsExpiryDays) {}
}
