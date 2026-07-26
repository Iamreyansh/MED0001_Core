package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.ListFilter;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.PageResult;
import com.nammamedmate.customer.domain.FlagReason;
import com.nammamedmate.customer.domain.LoyaltyTiers;
import com.nammamedmate.customer.domain.NotifyChannel;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCustomerService {

  private static final int LIST_LIMIT = 30;
  private static final int MUTATE_LIMIT = 20;
  private static final int MINUTE = 60;
  private static final int NOTIFY_PER_CUSTOMER = 3;
  private static final Set<String> SORTS =
      Set.of("created_at", "name", "total_orders", "total_ltv");

  private final CustomerProfileStore store;
  private final RateLimiter rateLimiter;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public AdminCustomerService(
      CustomerProfileStore store, RateLimiter rateLimiter, OutboxPublisher outbox, Clock clock) {
    this.store = store;
    this.rateLimiter = rateLimiter;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AdminListResult list(
      MedmatePrincipal principal,
      Integer page,
      Integer limit,
      String sort,
      String order,
      String search,
      String segment,
      Boolean isFlagged,
      String city,
      Boolean export) {
    requirePrincipal(principal);
    rateLimit("admin:customers:list:" + principal.subject(), LIST_LIMIT, MINUTE);

    String sortField = sort == null || sort.isBlank() ? "created_at" : sort.trim();
    if (!SORTS.contains(sortField)) {
      throw new AppException(
          "VALIDATION_ERROR",
          "sort must be one of: created_at, name, total_orders, total_ltv",
          400);
    }
    String orderField = order == null || order.isBlank() ? "desc" : order;
    PageRequest pageReq = PageRequest.normalize(page, limit, sortField, orderField);

    if (Boolean.TRUE.equals(export)) {
      // ponytail: CSV export via presigned S3 lands with media story; stub link for contract
      Map<String, Object> data = new LinkedHashMap<>();
      data.put(
          "export_url",
          "https://cdn.namma-medmate.in/exports/customers-" + principal.subject() + ".csv");
      data.put("expires_at", clock.instant().plus(1, ChronoUnit.HOURS));
      return new AdminListResult(data, null);
    }

    PageResult result =
        store.list(
            new ListFilter(
                pageReq.page(),
                pageReq.limit(),
                pageReq.sort(),
                pageReq.order(),
                blankToNull(search),
                blankToNull(segment),
                isFlagged,
                blankToNull(city)));

    List<Map<String, Object>> items = new ArrayList<>(result.items().size());
    for (CustomerProfileRecord c : result.items()) {
      items.add(toListItem(c));
    }
    return new AdminListResult(
        items, PaginationMeta.of(pageReq.page(), pageReq.limit(), result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requirePrincipal(principal);
    if (principal.role() == AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "admin_compliance cannot view full customer detail", 403);
    }
    rateLimit("admin:customers:get:" + principal.subject(), LIST_LIMIT, MINUTE);
    CustomerProfileRecord c = requireCustomer(id);
    return toDetail(c);
  }

  @Transactional
  public Map<String, Object> flag(MedmatePrincipal principal, UUID id, String reason, String note) {
    requirePrincipal(principal);
    rateLimit("admin:customers:flag:" + principal.subject(), MUTATE_LIMIT, MINUTE);
    CustomerProfileRecord c = requireCustomer(id);
    if (c.isFlagged()) {
      throw new AppException("ALREADY_FLAGGED", "Customer is already flagged", 409);
    }
    FlagReason flagReason;
    try {
      flagReason = FlagReason.parse(reason);
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
    }
    String trimmedNote = note == null ? null : note.trim();
    if (flagReason == FlagReason.OTHER) {
      if (trimmedNote == null || trimmedNote.isEmpty()) {
        throw new AppException("VALIDATION_ERROR", "note is required when reason is OTHER", 400);
      }
    }
    if (trimmedNote != null && trimmedNote.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "note max length is 500", 400);
    }
    Instant now = clock.instant();
    store.flag(id, flagReason.name(), trimmedNote, principal.subject(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("is_flagged", true);
    data.put("flag_reason", flagReason.name());
    data.put("flagged_by", principal.subject().toString());
    data.put("flagged_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> unflag(MedmatePrincipal principal, UUID id) {
    requirePrincipal(principal);
    rateLimit("admin:customers:unflag:" + principal.subject(), MUTATE_LIMIT, MINUTE);
    CustomerProfileRecord c = requireCustomer(id);
    if (!c.isFlagged()) {
      throw new AppException("NOT_FLAGGED", "Customer is not currently flagged", 409);
    }
    store.unflag(id);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("is_flagged", false);
    return data;
  }

  @Transactional
  public Map<String, Object> notify(
      MedmatePrincipal principal,
      UUID id,
      String channelRaw,
      String title,
      String body,
      String deepLink) {
    requirePrincipal(principal);
    rateLimit("admin:customers:notify:" + principal.subject(), MUTATE_LIMIT, MINUTE);
    store.lockCustomer(id);
    CustomerProfileRecord c = requireCustomer(id);

    NotifyChannel channel;
    try {
      channel = NotifyChannel.parse(channelRaw);
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
    }
    if (body == null || body.isBlank() || body.length() > 255) {
      throw new AppException("VALIDATION_ERROR", "body is required (max 255)", 400);
    }
    if (channel == NotifyChannel.PUSH || channel == NotifyChannel.BOTH) {
      if (title == null || title.isBlank() || title.length() > 65) {
        throw new AppException("VALIDATION_ERROR", "title is required for PUSH (max 65)", 400);
      }
    }
    String normalisedDeepLink = null;
    if (deepLink != null && !deepLink.isBlank()) {
      String trimmed = deepLink.trim();
      if (trimmed.length() > 512 || !trimmed.startsWith("medmate://")) {
        throw new AppException(
            "VALIDATION_ERROR", "deep_link must be a medmate:// URL (max 512)", 400);
      }
      normalisedDeepLink = trimmed;
    }

    Instant since = clock.instant().minus(24, ChronoUnit.HOURS);
    if (store.countNotificationsSince(c.id(), since) >= NOTIFY_PER_CUSTOMER) {
      throw new AppException(
          "NOTIFICATION_RATE_LIMITED",
          "Maximum 3 notifications per customer per 24 hours",
          429,
          DAY_RETRY_HINT);
    }

    Instant now = clock.instant();
    UUID notificationId = Ids.newId();
    store.insertNotification(
        notificationId,
        c.id(),
        channel.name(),
        title == null || title.isBlank() ? null : title.trim(),
        body.trim(),
        normalisedDeepLink,
        principal.subject(),
        now);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", notificationId.toString());
    payload.put("customer_id", c.id().toString());
    payload.put("channel", channel.name());
    payload.put("body", body.trim());
    String trimmedTitle = title == null || title.isBlank() ? null : title.trim();
    if (trimmedTitle != null) {
      payload.put("title", trimmedTitle);
    }
    if (normalisedDeepLink != null) {
      payload.put("deep_link", normalisedDeepLink);
    }
    // Worker loads phone at send time (EPIC-017) — keep PII out of outbox/SQS.
    outbox.publish(DomainEvent.of("customer.notification.requested", "customer", c.id(), payload));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("notification_id", notificationId);
    data.put("channel", channel.name());
    // Queued only; FCM/SMS delivery lands with notification consumer (EPIC-017).
    data.put("delivered", false);
    data.put("queued_at", now);
    return data;
  }

  private static final int DAY_RETRY_HINT = 86_400;

  private CustomerProfileRecord requireCustomer(UUID id) {
    return store
        .findById(id)
        .filter(c -> c.deletedAt() == null)
        .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static Map<String, Object> toListItem(CustomerProfileRecord c) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", c.id());
    data.put("phone", c.phone());
    data.put("name", c.name());
    data.put("city", c.city());
    data.put("segment", c.segment());
    data.put("is_flagged", c.isFlagged());
    data.put("total_orders", c.totalOrders());
    data.put("total_ltv", CustomerProfileService.paiseToRupees(c.totalLtvPaise()));
    data.put("cancel_rate", c.cancelRate());
    data.put("created_at", c.createdAt());
    data.put("last_order_at", c.lastOrderAt());
    return data;
  }

  static Map<String, Object> toDetail(CustomerProfileRecord c) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", c.id());
    data.put("phone", c.phone());
    data.put("name", c.name());
    data.put("avatar_url", c.avatarUrl());
    data.put("date_of_birth", c.dateOfBirth());
    data.put("gender", c.gender());
    data.put("preferred_language", c.preferredLanguage());
    data.put("segment", c.segment());
    data.put("is_flagged", c.isFlagged());
    data.put("flag_reason", c.flagReason());
    data.put("created_at", c.createdAt());

    int cancelled =
        c.cancelRate() == null
            ? 0
            : c.cancelRate()
                .multiply(BigDecimal.valueOf(c.totalOrders()))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    cancelled = Math.min(Math.max(cancelled, 0), c.totalOrders());
    int completed = Math.max(c.totalOrders() - cancelled, 0);
    BigDecimal avg =
        c.totalOrders() == 0
            ? BigDecimal.ZERO.setScale(2)
            : CustomerProfileService.paiseToRupees(c.totalLtvPaise())
                .divide(BigDecimal.valueOf(c.totalOrders()), 2, RoundingMode.HALF_UP);

    Map<String, Object> orderStats = new LinkedHashMap<>();
    orderStats.put("total_orders", c.totalOrders());
    orderStats.put("completed_orders", completed);
    orderStats.put("cancelled_orders", cancelled);
    orderStats.put("total_ltv", CustomerProfileService.paiseToRupees(c.totalLtvPaise()));
    orderStats.put("cancel_rate", c.cancelRate());
    orderStats.put("avg_order_value", avg);
    orderStats.put("last_order_at", c.lastOrderAt());
    data.put("order_stats", orderStats);

    Map<String, Object> wallet = new LinkedHashMap<>();
    wallet.put("balance", CustomerProfileService.paiseToRupees(c.walletBalancePaise()));
    // ponytail: lifetime wallet ledgers land in STORY-003
    wallet.put("lifetime_credited", BigDecimal.ZERO.setScale(2));
    wallet.put("lifetime_debited", BigDecimal.ZERO.setScale(2));
    data.put("wallet", wallet);

    Map<String, Object> loyalty = new LinkedHashMap<>();
    loyalty.put("tier", LoyaltyTiers.fromPoints(c.loyaltyPoints()));
    loyalty.put("points_balance", c.loyaltyPoints());
    loyalty.put("points_earned_lifetime", c.loyaltyPoints());
    loyalty.put("dispute_count", c.disputeCount());
    data.put("loyalty", loyalty);
    return data;
  }

  public record AdminListResult(Object data, PaginationMeta meta) {}
}
