package com.nammamedmate.marketing.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.CouponStore;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Coupon;
import com.nammamedmate.marketing.domain.CouponDiscount;
import com.nammamedmate.marketing.domain.CouponStatus;
import com.nammamedmate.marketing.domain.CouponType;
import com.nammamedmate.marketing.domain.MoneyFormats;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final CouponStore store;
  private final SegmentStore segments;
  private final NotificationDispatchPort notifications;
  private final Clock clock;

  public CouponService(
      CouponStore store,
      SegmentStore segments,
      NotificationDispatchPort notifications,
      Clock clock) {
    this.store = store;
    this.segments = segments;
    this.notifications = notifications;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {}

  public record CreateCommand(
      String code,
      String type,
      Number value,
      Number minOrderValue,
      Number maxDiscountCap,
      Integer maxRedemptionsTotal,
      Integer maxPerUser,
      Number budgetTotal,
      List<UUID> segmentIds,
      Boolean firstOrderOnly,
      Boolean rxOrdersOnly,
      Instant validFrom,
      Instant validUntil,
      String description,
      String terms) {}

  public record PatchCommand(
      Number minOrderValue,
      Number maxDiscountCap,
      Number budgetTotal,
      Integer maxRedemptionsTotal,
      Integer maxPerUser,
      List<UUID> segmentIds,
      Boolean firstOrderOnly,
      Boolean rxOrdersOnly,
      Instant validFrom,
      Instant validUntil,
      String description,
      String terms,
      Boolean immutableCodeOrTypePresent) {}

  public record ValidateCommand(
      String couponCode,
      Number cartTotal,
      UUID customerId,
      Boolean firstOrder,
      Boolean hasRxItems,
      UUID pharmacyId) {}

  /** Order-domain cart apply: throws AppException with cart-compatible codes. */
  public record CartQuote(
      String code, String discountType, long discountPaise, boolean freeDelivery, String message) {}

  @Transactional(readOnly = true)
  public PagedResult list(
      MedmatePrincipal principal,
      String status,
      String type,
      Integer page,
      Integer limit,
      String sort,
      String order) {
    requireAdminRead(principal);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    CouponStatus st = parseStatus(status);
    CouponType ty = parseTypeFilter(type);
    long total = store.count(st, ty);
    List<Coupon> rows = store.list(st, ty, sort, order, (p - 1) * lim, lim);
    CouponStore.Chips chips = store.chips();
    Map<String, Object> chipMap = new LinkedHashMap<>();
    chipMap.put("active_count", chips.activeCount());
    chipMap.put("total_redemptions", chips.totalRedemptions());
    chipMap.put("discount_spend_rs", MoneyFormats.paiseToRupees(chips.discountSpendPaise()));
    chipMap.put("marketing_spend_rs", MoneyFormats.paiseToRupees(chips.marketingSpendPaise()));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (Coupon c : rows) {
      items.add(toListItem(c));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("chips", chipMap);
    data.put("coupons", items);
    return new PagedResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    requireAdminWrite(principal);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "code is required", 422);
    }
    if (cmd.code() == null || cmd.code().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "code is required", 422);
    }
    if (cmd.type() == null || cmd.type().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "type is required", 422);
    }
    CouponType type = parseType(cmd.type());
    String code = normalizeCode(cmd.code());
    if (store.findByCode(code).isPresent()) {
      throw new AppException("COUPON_CODE_EXISTS", "Coupon code already exists", 409);
    }
    Instant from = cmd.validFrom() == null ? clock.instant() : cmd.validFrom();
    Instant until = cmd.validUntil();
    if (until == null) {
      throw new AppException("VALIDATION_ERROR", "valid_until is required", 422);
    }
    if (from.isAfter(until)) {
      throw new AppException("INVALID_DATE_RANGE", "valid_from must be before valid_until", 422);
    }
    Integer percent = null;
    Long valuePaise = null;
    if (type == CouponType.PERCENTAGE) {
      int pct = toIntValue(cmd.value());
      if (pct < 1 || pct > 100) {
        throw new AppException("INVALID_VALUE", "percentage value must be 1-100", 422);
      }
      percent = pct;
    } else if (type == CouponType.FLAT_RS) {
      long paise = toPaise(cmd.value());
      if (paise <= 0) {
        throw new AppException("INVALID_VALUE", "flat value must be > 0", 422);
      }
      valuePaise = paise;
    } else {
      valuePaise = 0L;
    }
    long budget = cmd.budgetTotal() == null ? 0L : toPaise(cmd.budgetTotal());
    if (budget < 0) {
      throw new AppException("INVALID_VALUE", "budget_total must be >= 0", 422);
    }
    Instant now = clock.instant();
    Coupon created =
        store.insert(
            new Coupon(
                Ids.newId(),
                code,
                type,
                percent,
                valuePaise,
                cmd.minOrderValue() == null ? 0L : toPaise(cmd.minOrderValue()),
                cmd.maxDiscountCap() == null ? null : toPaise(cmd.maxDiscountCap()),
                budget,
                0L,
                0,
                cmd.maxRedemptionsTotal(),
                cmd.maxPerUser() == null ? 1 : Math.max(1, cmd.maxPerUser()),
                cmd.segmentIds() == null ? List.of() : cmd.segmentIds(),
                Boolean.TRUE.equals(cmd.firstOrderOnly()),
                Boolean.TRUE.equals(cmd.rxOrdersOnly()),
                from,
                until,
                CouponStatus.ACTIVE,
                cmd.description(),
                cmd.terms(),
                principal.subject(),
                now,
                now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", created.id());
    data.put("code", created.code());
    data.put("type", created.type().name());
    data.put("status", created.status().name());
    data.put("created_at", created.createdAt());
    data.put("created_by", created.createdBy());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(
      MedmatePrincipal principal, String code, Integer page, Integer limit) {
    requireAdminRead(principal);
    Coupon c = requireCoupon(code);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    CouponStore.Economics eco = store.economics(c.id());
    long discountSpend = eco.discountSpendPaise();
    long revenue = eco.revenueAttributedPaise();
    BigDecimal roas =
        discountSpend <= 0
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.valueOf(revenue)
                .divide(BigDecimal.valueOf(discountSpend), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    double pctUsed =
        c.budgetTotalPaise() <= 0 ? 0d : (c.budgetUsedPaise() * 100.0) / c.budgetTotalPaise();
    Map<String, Object> economics = new LinkedHashMap<>();
    economics.put(
        "discount_per_redemption_rs",
        c.redemptionsCount() <= 0
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : MoneyFormats.paiseToRupees(discountSpend / c.redemptionsCount()));
    economics.put("revenue_attributed_rs", MoneyFormats.paiseToRupees(revenue));
    economics.put("roas", roas);

    Map<String, Object> ring = new LinkedHashMap<>();
    ring.put("used", MoneyFormats.paiseToRupees(c.budgetUsedPaise()));
    ring.put("total", MoneyFormats.paiseToRupees(c.budgetTotalPaise()));
    ring.put("pct_used", BigDecimal.valueOf(pctUsed).setScale(1, RoundingMode.HALF_UP));

    List<Map<String, Object>> daily = new ArrayList<>();
    for (CouponStore.DailyRedemption d : store.dailyRedemptions(c.id(), 30)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", d.date().toString());
      row.put("count", d.count());
      daily.add(row);
    }

    long redeemTotal = store.countRedemptions(c.id());
    List<Map<String, Object>> redeemed = new ArrayList<>();
    for (CouponStore.RedemptionRow r : store.listRedemptions(c.id(), (p - 1) * lim, lim)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("customer_id", r.customerId());
      row.put("customer_name", r.customerName());
      row.put("order_id", r.orderId());
      row.put("discount_applied_rs", MoneyFormats.paiseToRupees(r.discountAppliedPaise()));
      row.put("redeemed_at", r.redeemedAt());
      redeemed.add(row);
    }
    Map<String, Object> redeemedBy = new LinkedHashMap<>();
    redeemedBy.put("data", redeemed);
    redeemedBy.put("meta", PaginationMeta.of(p, lim, redeemTotal));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", c.id());
    data.put("code", c.code());
    data.put("type", c.type().name());
    data.put("value", c.apiValue());
    data.put("status", c.status().name());
    data.put("budget_total", MoneyFormats.paiseToRupees(c.budgetTotalPaise()));
    data.put("budget_used", MoneyFormats.paiseToRupees(c.budgetUsedPaise()));
    data.put("redemptions_count", c.redemptionsCount());
    data.put("economics", economics);
    data.put("redemptions_daily", daily);
    data.put("budget_ring", ring);
    data.put("terms", c.terms());
    data.put("description", c.description());
    data.put("min_order_value", MoneyFormats.paiseToRupees(c.minOrderValuePaise()));
    data.put(
        "max_discount_cap",
        c.maxDiscountCapPaise() == null
            ? null
            : MoneyFormats.paiseToRupees(c.maxDiscountCapPaise()));
    data.put("segment_ids", c.segmentIds());
    data.put("is_first_order_only", c.firstOrderOnly());
    data.put("is_rx_orders_only", c.rxOrdersOnly());
    data.put("valid_from", c.validFrom());
    data.put("valid_until", c.validUntil());
    data.put("redeemed_by", redeemedBy);
    return data;
  }

  @Transactional
  public Map<String, Object> patch(MedmatePrincipal principal, String code, PatchCommand cmd) {
    requireAdminWrite(principal);
    if (cmd != null && Boolean.TRUE.equals(cmd.immutableCodeOrTypePresent())) {
      throw new AppException("IMMUTABLE_FIELD", "code and type cannot be changed", 400);
    }
    Coupon existing = requireCoupon(code);
    Instant now = clock.instant();
    Instant from = cmd != null && cmd.validFrom() != null ? cmd.validFrom() : existing.validFrom();
    Instant until =
        cmd != null && cmd.validUntil() != null ? cmd.validUntil() : existing.validUntil();
    if (from.isAfter(until)) {
      throw new AppException("INVALID_DATE_RANGE", "valid_from must be before valid_until", 422);
    }
    long budget =
        cmd != null && cmd.budgetTotal() != null
            ? toPaise(cmd.budgetTotal())
            : existing.budgetTotalPaise();
    Coupon updated =
        new Coupon(
            existing.id(),
            existing.code(),
            existing.type(),
            existing.percentValue(),
            existing.valuePaise(),
            cmd != null && cmd.minOrderValue() != null
                ? toPaise(cmd.minOrderValue())
                : existing.minOrderValuePaise(),
            cmd != null && cmd.maxDiscountCap() != null
                ? Long.valueOf(toPaise(cmd.maxDiscountCap()))
                : existing.maxDiscountCapPaise(),
            budget,
            existing.budgetUsedPaise(),
            existing.redemptionsCount(),
            cmd != null && cmd.maxRedemptionsTotal() != null
                ? cmd.maxRedemptionsTotal()
                : existing.maxRedemptionsTotal(),
            cmd != null && cmd.maxPerUser() != null ? cmd.maxPerUser() : existing.maxPerUser(),
            cmd != null && cmd.segmentIds() != null ? cmd.segmentIds() : existing.segmentIds(),
            cmd != null && cmd.firstOrderOnly() != null
                ? cmd.firstOrderOnly()
                : existing.firstOrderOnly(),
            cmd != null && cmd.rxOrdersOnly() != null
                ? cmd.rxOrdersOnly()
                : existing.rxOrdersOnly(),
            from,
            until,
            existing.status(),
            cmd != null && cmd.description() != null ? cmd.description() : existing.description(),
            cmd != null && cmd.terms() != null ? cmd.terms() : existing.terms(),
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    return Map.of("code", updated.code(), "updated_at", now);
  }

  @Transactional
  public Map<String, Object> toggle(MedmatePrincipal principal, String code) {
    requireAdminWrite(principal);
    Coupon c = requireCoupon(code);
    CouponStatus next =
        switch (c.status()) {
          case ACTIVE -> CouponStatus.PAUSED;
          case PAUSED -> CouponStatus.ACTIVE;
          case EXPIRED ->
              throw new AppException("VALIDATION_ERROR", "Expired coupons cannot be toggled", 422);
        };
    Instant now = clock.instant();
    store.update(withStatus(c, next, now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", c.code());
    data.put("status", next.name());
    data.put("toggled_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, String code) {
    requireAdminSuper(principal);
    Coupon c = requireCoupon(code);
    if (c.redemptionsCount() <= 0) {
      store.hardDelete(c.id());
      return Map.of("code", c.code(), "action", "DELETED", "message", "Coupon deleted.");
    }
    Instant now = clock.instant();
    store.update(withStatus(c, CouponStatus.EXPIRED, now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", c.code());
    data.put("action", "EXPIRED");
    data.put("message", "Coupon has redemptions; status set to EXPIRED instead of deleted.");
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> validate(MedmatePrincipal principal, ValidateCommand cmd) {
    requireCustomer(principal);
    UUID customerId = cmd.customerId() == null ? principal.subject() : cmd.customerId();
    if (!customerId.equals(principal.subject())) {
      throw new AppException("FORBIDDEN", "customer_id must match authenticated customer", 403);
    }
    String code = cmd.couponCode() == null ? null : normalizeCode(cmd.couponCode());
    long cartPaise = cmd.cartTotal() == null ? 0L : toPaise(cmd.cartTotal());
    if (code == null) {
      return invalid(null, null, 0L, "COUPON_NOT_FOUND");
    }
    var found = store.findByCode(code);
    if (found.isEmpty()) {
      return invalid(null, null, 0L, "COUPON_NOT_FOUND");
    }
    Coupon c = found.get();
    Instant now = clock.instant();
    if (c.status() == CouponStatus.PAUSED) {
      return invalid(null, null, 0L, "COUPON_PAUSED");
    }
    if (c.status() == CouponStatus.EXPIRED) {
      return invalid(null, null, 0L, "COUPON_EXPIRED");
    }
    if (now.isAfter(c.validUntil()) || now.isBefore(c.validFrom())) {
      return invalid(null, null, 0L, "COUPON_EXPIRED");
    }
    if (c.budgetTotalPaise() > 0 && c.budgetUsedPaise() >= c.budgetTotalPaise()) {
      return invalid(null, null, 0L, "COUPON_BUDGET_EXHAUSTED");
    }
    if (store.countRedemptionsForCustomer(c.id(), customerId) >= c.maxPerUser()) {
      return invalid(null, null, 0L, "COUPON_PER_USER_LIMIT");
    }
    if (cartPaise < c.minOrderValuePaise()) {
      return invalid(null, null, 0L, "COUPON_MIN_ORDER_NOT_MET");
    }
    if (!c.openToAllSegments()) {
      boolean member = false;
      for (UUID seg : c.segmentIds()) {
        if (segments.isMember(seg, customerId)) {
          member = true;
          break;
        }
      }
      if (!member) {
        return invalid(null, null, 0L, "COUPON_SEGMENT_MISMATCH");
      }
    }
    if (c.firstOrderOnly() && !Boolean.TRUE.equals(cmd.firstOrder())) {
      return invalid(null, null, 0L, "COUPON_FIRST_ORDER_ONLY");
    }
    if (c.rxOrdersOnly() && !Boolean.TRUE.equals(cmd.hasRxItems())) {
      return invalid(null, null, 0L, "COUPON_RX_ONLY");
    }
    long discount = CouponDiscount.discountPaise(c, cartPaise);
    String appliesTo = c.type() == CouponType.FREE_DELIVERY ? "DELIVERY_FEE" : "SUBTOTAL";
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("valid", true);
    data.put("discount_type", c.type().name());
    data.put("discount_amount", MoneyFormats.paiseToRupees(discount));
    data.put("applies_to", appliesTo);
    data.put("error_code", null);
    return data;
  }

  @Transactional(readOnly = true)
  public PagedResult available(MedmatePrincipal principal, Boolean includeApplied) {
    requireCustomer(principal);
    Instant now = clock.instant();
    List<Coupon> active = store.list(CouponStatus.ACTIVE, null, "created_at", "desc", 0, 200);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Coupon c : active) {
      if (now.isAfter(c.validUntil()) || now.isBefore(c.validFrom())) {
        continue;
      }
      if (c.budgetTotalPaise() > 0 && c.budgetUsedPaise() >= c.budgetTotalPaise()) {
        continue;
      }
      if (!c.openToAllSegments()) {
        boolean member = false;
        for (UUID seg : c.segmentIds()) {
          if (segments.isMember(seg, principal.subject())) {
            member = true;
            break;
          }
        }
        if (!member) {
          continue;
        }
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", c.code());
      row.put("type", c.type().name());
      row.put("value", c.apiValue());
      row.put("description", c.description());
      row.put("min_order_value", MoneyFormats.paiseToRupees(c.minOrderValuePaise()));
      row.put("valid_until", c.validUntil());
      out.add(row);
    }
    return new PagedResult(Map.of("coupons", out), PaginationMeta.of(1, out.size(), out.size()));
  }

  /** Cart apply bridge — maps eligibility failures to order cart error codes. */
  @Transactional(readOnly = true)
  public CartQuote applyForCart(String couponCode, long itemTotalPaise) {
    String code = normalizeCode(couponCode);
    if (code == null) {
      throw new AppException("INVALID_COUPON", "Coupon code not found or expired", 422);
    }
    Coupon c =
        store
            .findByCode(code)
            .orElseThrow(
                () -> new AppException("INVALID_COUPON", "Coupon code not found or expired", 422));
    Instant now = clock.instant();
    if (c.status() != CouponStatus.ACTIVE
        || now.isAfter(c.validUntil())
        || now.isBefore(c.validFrom())) {
      throw new AppException("INVALID_COUPON", "Coupon code not found or expired", 422);
    }
    if (itemTotalPaise < c.minOrderValuePaise()) {
      throw new AppException(
          "COUPON_MIN_NOT_MET",
          c.code()
              + " requires minimum cart of Rs "
              + MoneyFormats.paiseToRupees(c.minOrderValuePaise()),
          422);
    }
    long discount = CouponDiscount.discountPaise(c, itemTotalPaise);
    boolean freeDel = c.type() == CouponType.FREE_DELIVERY;
    String type =
        switch (c.type()) {
          case PERCENTAGE -> "PERCENT";
          case FLAT_RS -> "FLAT";
          case FREE_DELIVERY -> "FREE_DELIVERY";
        };
    String message =
        switch (c.type()) {
          case PERCENTAGE ->
              (c.percentValue() == null ? 0 : c.percentValue()) + "% discount applied";
          case FLAT_RS -> "Rs " + MoneyFormats.paiseToRupees(discount) + " discount applied";
          case FREE_DELIVERY -> "Free delivery applied";
        };
    return new CartQuote(c.code(), type, discount, freeDel, message);
  }

  /**
   * Record redemption and auto-pause when budget exhausted (AC-5). Call from order bridge when
   * order places.
   */
  @Transactional
  public void recordRedemption(
      String couponCode,
      UUID orderId,
      UUID customerId,
      long discountAppliedPaise,
      long orderTotalPaise) {
    Coupon c = requireCoupon(couponCode);
    Instant now = clock.instant();
    store.insertRedemption(
        Ids.newId(), c.id(), orderId, customerId, discountAppliedPaise, orderTotalPaise, now);
    long used = c.budgetUsedPaise() + Math.max(discountAppliedPaise, 0L);
    CouponStatus status = c.status();
    boolean paused = false;
    if (c.budgetTotalPaise() > 0 && used >= c.budgetTotalPaise()) {
      status = CouponStatus.PAUSED;
      paused = true;
      used = Math.min(used, c.budgetTotalPaise());
    }
    store.update(
        new Coupon(
            c.id(),
            c.code(),
            c.type(),
            c.percentValue(),
            c.valuePaise(),
            c.minOrderValuePaise(),
            c.maxDiscountCapPaise(),
            c.budgetTotalPaise(),
            used,
            c.redemptionsCount() + 1,
            c.maxRedemptionsTotal(),
            c.maxPerUser(),
            c.segmentIds(),
            c.firstOrderOnly(),
            c.rxOrdersOnly(),
            c.validFrom(),
            c.validUntil(),
            status,
            c.description(),
            c.terms(),
            c.createdBy(),
            c.createdAt(),
            now));
    if (paused) {
      notifications.notifyCouponBudgetExhausted(c.code(), c.id());
    }
  }

  /** Force budget exhaustion path for tests / internal pause. */
  @Transactional
  public void applyBudgetUsage(String code, long additionalPaise) {
    Coupon c = requireCoupon(code);
    Instant now = clock.instant();
    long used = c.budgetUsedPaise() + Math.max(additionalPaise, 0L);
    CouponStatus status = c.status();
    boolean paused = false;
    if (c.budgetTotalPaise() > 0 && used >= c.budgetTotalPaise()) {
      status = CouponStatus.PAUSED;
      paused = true;
    }
    store.update(withBudget(c, used, status, now));
    if (paused) {
      notifications.notifyCouponBudgetExhausted(c.code(), c.id());
    }
  }

  @Transactional
  public void sendDailyBudgetBurnDigest() {
    LocalDate istDay = LocalDate.now(clock.withZone(IST));
    List<CouponStore.BudgetBurnRow> rows = store.highBurnCouponsForDay(istDay);
    if (rows.isEmpty()) {
      return;
    }
    List<NotificationDispatchPort.BudgetBurnItem> items = new ArrayList<>(rows.size());
    for (CouponStore.BudgetBurnRow r : rows) {
      double pct =
          r.budgetTotalPaise() <= 0 ? 0d : (r.budgetUsedPaise() * 100.0) / r.budgetTotalPaise();
      items.add(
          new NotificationDispatchPort.BudgetBurnItem(
              r.code(), r.budgetTotalPaise(), r.budgetUsedPaise(), pct));
    }
    notifications.notifyDailyBudgetBurnDigest(items);
  }

  private static Map<String, Object> invalid(
      String type, String appliesTo, long amountPaise, String errorCode) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("valid", false);
    data.put("discount_type", type);
    data.put("discount_amount", MoneyFormats.paiseToRupees(amountPaise));
    data.put("applies_to", appliesTo);
    data.put("error_code", errorCode);
    return data;
  }

  private Coupon requireCoupon(String code) {
    String normalized = normalizeCode(code);
    if (normalized == null) {
      throw new AppException("COUPON_NOT_FOUND", "Coupon not found", 404);
    }
    return store
        .findByCode(normalized)
        .orElseThrow(() -> new AppException("COUPON_NOT_FOUND", "Coupon not found", 404));
  }

  private static Coupon withStatus(Coupon c, CouponStatus status, Instant now) {
    return new Coupon(
        c.id(),
        c.code(),
        c.type(),
        c.percentValue(),
        c.valuePaise(),
        c.minOrderValuePaise(),
        c.maxDiscountCapPaise(),
        c.budgetTotalPaise(),
        c.budgetUsedPaise(),
        c.redemptionsCount(),
        c.maxRedemptionsTotal(),
        c.maxPerUser(),
        c.segmentIds(),
        c.firstOrderOnly(),
        c.rxOrdersOnly(),
        c.validFrom(),
        c.validUntil(),
        status,
        c.description(),
        c.terms(),
        c.createdBy(),
        c.createdAt(),
        now);
  }

  private static Coupon withBudget(Coupon c, long used, CouponStatus status, Instant now) {
    return new Coupon(
        c.id(),
        c.code(),
        c.type(),
        c.percentValue(),
        c.valuePaise(),
        c.minOrderValuePaise(),
        c.maxDiscountCapPaise(),
        c.budgetTotalPaise(),
        used,
        c.redemptionsCount(),
        c.maxRedemptionsTotal(),
        c.maxPerUser(),
        c.segmentIds(),
        c.firstOrderOnly(),
        c.rxOrdersOnly(),
        c.validFrom(),
        c.validUntil(),
        status,
        c.description(),
        c.terms(),
        c.createdBy(),
        c.createdAt(),
        now);
  }

  private static Map<String, Object> toListItem(Coupon c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", c.code());
    m.put("type", c.type().name());
    m.put("value", c.apiValue());
    m.put("scope", c.openToAllSegments() ? "ALL_ORDERS" : "SEGMENT");
    m.put("min_order", MoneyFormats.paiseToRupees(c.minOrderValuePaise()));
    m.put(
        "max_discount",
        c.maxDiscountCapPaise() == null
            ? null
            : MoneyFormats.paiseToRupees(c.maxDiscountCapPaise()));
    m.put("budget_total", MoneyFormats.paiseToRupees(c.budgetTotalPaise()));
    m.put("budget_used", MoneyFormats.paiseToRupees(c.budgetUsedPaise()));
    m.put("redemptions", c.redemptionsCount());
    m.put("status", c.status().name());
    m.put("is_rx_specific", c.rxOrdersOnly());
    m.put("valid_from", c.validFrom());
    m.put("valid_until", c.validUntil());
    return m;
  }

  private static String normalizeCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    return code.trim().toUpperCase(Locale.ROOT);
  }

  private static long toPaise(Number rupees) {
    return MoneyFormats.rupeesToPaise(BigDecimal.valueOf(rupees.doubleValue()));
  }

  private static int toIntValue(Number value) {
    if (value == null) {
      return 0;
    }
    return value.intValue();
  }

  private static int normalizePage(Integer page) {
    if (page == null || page < 1) {
      return 1;
    }
    return page;
  }

  private static int normalizeLimit(Integer limit) {
    if (limit == null || limit < 1) {
      return 20;
    }
    return Math.min(limit, 100);
  }

  private static CouponStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return CouponStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "invalid status", 422);
    }
  }

  private static CouponType parseType(String type) {
    try {
      return CouponType.valueOf(type.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "invalid type", 422);
    }
  }

  private static CouponType parseTypeFilter(String type) {
    if (type == null || type.isBlank()) {
      return null;
    }
    return parseType(type);
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_FINANCE);
  }

  private static void requireAdminWrite(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  }

  private static void requireAdminSuper(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.ADMIN_SUPER);
  }

  private static void requireCustomer(MedmatePrincipal principal) {
    requireRole(principal, AuthRole.CUSTOMER);
  }

  private static void requireRole(MedmatePrincipal principal, AuthRole... allowed) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    for (AuthRole role : allowed) {
      if (principal.role() == role) {
        return;
      }
    }
    throw new AppException("FORBIDDEN", "Insufficient role", 403);
  }
}
