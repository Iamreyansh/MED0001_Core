package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.LoyaltyStore;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyTxRecord;
import com.nammamedmate.customer.domain.LoyaltyTiers;
import com.nammamedmate.customer.domain.LoyaltyTxType;
import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoyaltyService {

  private static final int STATUS_LIMIT = 30;
  private static final int TX_LIST_LIMIT = 20;
  private static final int MINUTE = 60;

  private final LoyaltyStore loyalty;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final OutboxPublisher outbox;

  public LoyaltyService(
      LoyaltyStore loyalty, RateLimiter rateLimiter, Clock clock, OutboxPublisher outbox) {
    this.loyalty = loyalty;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.outbox = outbox;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMyStatus(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:loyalty:get:" + customerId, STATUS_LIMIT, MINUTE);
    LoyaltyRecord record = requireLoyalty(customerId);
    return toStatusView(record);
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
   * Award points after order DELIVERED. Idempotent on order id. Invoked by order domain / messaging
   * when EPIC-010 emits delivery events.
   */
  @Transactional
  public Optional<LoyaltyTxRecord> awardForDeliveredOrder(
      UUID customerId, UUID orderId, String orderDisplayId, long orderTotalPaise) {
    if (customerId == null || orderId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id and order_id are required", 400);
    }
    int points = LoyaltyTiers.pointsForOrderPaise(orderTotalPaise);
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
    String newTier = LoyaltyTiers.fromLifetimePoints(newLifetime);

    String display =
        orderDisplayId == null || orderDisplayId.isBlank() ? orderId.toString() : orderDisplayId;
    long rupees = orderTotalPaise / 100;
    String description =
        truncate("Points for order #" + display + " (Rs " + rupees + " spent)", 255);

    LoyaltyTxRecord tx =
        new LoyaltyTxRecord(
            Ids.newId(),
            customerId,
            LoyaltyTxType.EARN,
            points,
            newBalance,
            description,
            orderId,
            now);
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
   * unchanged (tier ratchet). Idempotent on order id. An empty result means there is nothing to
   * reverse — either a sub-Rs 100 order that never earned points, or a reversal already applied —
   * both terminal, so a consumer may safely ack. Correctness under a refund event that races ahead
   * of its award relies on per-order FIFO ordering from the EPIC-010 order event stream (an order
   * must be DELIVERED, hence awarded, before it can be refunded).
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
    int reversePoints = earn.get().points();
    int newBalance = Math.max(0, locked.pointsBalance() - reversePoints);
    // Lifetime unchanged — tier stays based on lifetime.
    String tier = LoyaltyTiers.fromLifetimePoints(locked.pointsEarnedLifetime());

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

    LoyaltyRecord updated =
        new LoyaltyRecord(
            locked.id(), customerId, tier, newBalance, locked.pointsEarnedLifetime(), now);
    loyalty.update(updated);
    loyalty.syncCustomerLoyaltyPoints(customerId, newBalance);
    return Optional.of(tx);
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

  private static Map<String, Object> toStatusView(LoyaltyRecord record) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tier", record.tier());
    data.put("points_balance", record.pointsBalance());
    data.put("points_earned_lifetime", record.pointsEarnedLifetime());
    data.put("tier_progress", LoyaltyTiers.progress(record.pointsEarnedLifetime()));
    data.put("tier_thresholds", LoyaltyTiers.thresholds());
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

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  public record TxPage(List<Map<String, Object>> data, PaginationMeta meta) {
    public TxPage {
      data = List.copyOf(data);
    }
  }
}
