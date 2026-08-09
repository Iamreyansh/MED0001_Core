package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.OfferAppliesTo;
import com.nammamedmate.pos.domain.PharmacyOffer;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfferService {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final int WINDOW = 60;
  private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final OfferStore store;
  private final PosPlanPort plan;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public OfferService(OfferStore store, PosPlanPort plan, RateLimiter rateLimiter, Clock clock) {
    this.store = store;
    this.plan = plan;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public record AppliedOffer(
      PharmacyOffer offer, long discountAmountPaise, long eligibleSubtotalPaise) {}

  public ListResult list(MedmatePrincipal principal, String status, Integer page, Integer limit) {
    requireGrowth(principal);
    requireStaff(principal);
    rateLimit("pharmacy:offers:list:" + principal.pharmacyId(), 60);

    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    LocalDate today = today();
    String filter =
        status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ACTIVE", "EXPIRED", "ALL").contains(filter)) {
      throw new AppException("VALIDATION_ERROR", "status must be ACTIVE, EXPIRED, or ALL", 400);
    }

    OfferStore.ListPage pageResult = store.list(principal.pharmacyId(), filter, today, p, lim);
    OfferStore.Kpi kpi = store.kpi(principal.pharmacyId(), today);

    List<Map<String, Object>> offers = new ArrayList<>();
    for (PharmacyOffer o : pageResult.items()) {
      offers.add(toListItem(o, today));
    }
    Map<String, Object> kpiMap = new LinkedHashMap<>();
    kpiMap.put("active_count", kpi.activeCount());
    kpiMap.put("total_redemptions", kpi.totalRedemptions());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpi", kpiMap);
    data.put("offers", offers);
    return new ListResult(data, PaginationMeta.of(p, lim, pageResult.total()));
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, Map<String, Object> body) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:offers:create:" + principal.pharmacyId(), 20);

    Map<String, Object> req = body == null ? Map.of() : body;
    String title = requireTitle(str(req.get("title")));
    DiscountType discountType = requireDiscountType(str(req.get("discount_type")));
    BigDecimal discountValueBd =
        requirePositiveDecimal(req.get("discount_value"), "discount_value");
    long storedValue = toStoredDiscountValue(discountType, discountValueBd);
    OfferAppliesTo appliesTo = requireAppliesTo(str(req.get("applies_to")));
    List<UUID> scopeIds = resolveScopeIds(appliesTo, req);
    boolean online = bool(req.get("is_online"), false);
    boolean counter = bool(req.get("is_counter"), false);
    LocalDate validFrom = requireDate(req.get("valid_from"), "valid_from");
    LocalDate validUntil = requireDate(req.get("valid_until"), "valid_until");
    if (validUntil.isBefore(validFrom)) {
      throw new AppException("INVALID_DATE_RANGE", "valid_until must be >= valid_from", 400);
    }
    int maxRedemptions = intOrDefault(req.get("max_redemptions"), 0);
    if (maxRedemptions < 0) {
      throw new AppException("VALIDATION_ERROR", "max_redemptions must be >= 0", 400);
    }

    String coupon = normalizeCoupon(str(req.get("coupon_code")));
    if (coupon == null) {
      coupon = generateUniqueCoupon(principal.pharmacyId());
    } else if (store.couponExists(principal.pharmacyId(), coupon, null)) {
      throw new AppException(
          "COUPON_CODE_EXISTS", "Coupon code already exists for this pharmacy", 409);
    }

    Instant now = clock.instant();
    PharmacyOffer created =
        store.insert(
            new PharmacyOffer(
                Ids.newId(),
                principal.pharmacyId(),
                title,
                coupon,
                discountType,
                storedValue,
                appliesTo,
                scopeIds,
                online,
                counter,
                true,
                validFrom,
                validUntil,
                maxRedemptions,
                0,
                now,
                now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("offer_id", created.id().toString());
    data.put("title", created.title());
    data.put("coupon_code", created.couponCode());
    data.put("discount_type", created.discountType().name());
    data.put(
        "discount_value",
        MoneyMath.offerDiscountValueForApi(created.discountType(), created.discountValue()));
    data.put("applies_to", created.appliesTo().name());
    data.put("is_online", created.online());
    data.put("is_counter", created.counter());
    data.put("valid_from", created.validFrom().toString());
    data.put("valid_until", created.validUntil().toString());
    data.put("is_active", created.active());
    data.put("created_at", created.createdAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal, UUID offerId, Map<String, Object> body) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:offers:patch:" + principal.pharmacyId(), 20);

    PharmacyOffer cur = requireOffer(principal.pharmacyId(), offerId);
    LocalDate today = today();
    if (cur.isExpired(today)) {
      throw new AppException("OFFER_EXPIRED", "Offer has already expired", 400);
    }

    Map<String, Object> req = body == null ? Map.of() : body;
    String title = req.containsKey("title") ? requireTitle(str(req.get("title"))) : cur.title();
    DiscountType discountType =
        req.containsKey("discount_type")
            ? requireDiscountType(str(req.get("discount_type")))
            : cur.discountType();
    long storedValue = cur.discountValue();
    if (req.containsKey("discount_value") || req.containsKey("discount_type")) {
      BigDecimal bd =
          req.containsKey("discount_value")
              ? requirePositiveDecimal(req.get("discount_value"), "discount_value")
              : MoneyMath.offerDiscountValueForApi(cur.discountType(), cur.discountValue());
      storedValue = toStoredDiscountValue(discountType, bd);
    }
    OfferAppliesTo appliesTo =
        req.containsKey("applies_to")
            ? requireAppliesTo(str(req.get("applies_to")))
            : cur.appliesTo();
    List<UUID> scopeIds = cur.scopeIds();
    if (req.containsKey("applies_to")) {
      Map<String, Object> scopeBody = new LinkedHashMap<>(req);
      if (!scopeBody.containsKey("category_ids") && appliesTo == OfferAppliesTo.CATEGORY) {
        scopeBody.put("category_ids", cur.scopeIds().stream().map(UUID::toString).toList());
      }
      if (!scopeBody.containsKey("product_ids") && appliesTo == OfferAppliesTo.PRODUCT) {
        scopeBody.put("product_ids", cur.scopeIds().stream().map(UUID::toString).toList());
      }
      scopeIds = resolveScopeIds(appliesTo, scopeBody);
    } else if (req.containsKey("category_ids") || req.containsKey("product_ids")) {
      scopeIds = resolveScopeIds(appliesTo, req);
    }
    boolean online =
        req.containsKey("is_online") ? bool(req.get("is_online"), false) : cur.online();
    boolean counter =
        req.containsKey("is_counter") ? bool(req.get("is_counter"), false) : cur.counter();
    LocalDate validFrom =
        req.containsKey("valid_from")
            ? requireDate(req.get("valid_from"), "valid_from")
            : cur.validFrom();
    LocalDate validUntil =
        req.containsKey("valid_until")
            ? requireDate(req.get("valid_until"), "valid_until")
            : cur.validUntil();
    if (validUntil.isBefore(validFrom)) {
      throw new AppException("INVALID_DATE_RANGE", "valid_until must be >= valid_from", 400);
    }
    int maxRedemptions =
        req.containsKey("max_redemptions")
            ? intOrDefault(req.get("max_redemptions"), 0)
            : cur.maxRedemptions();
    if (maxRedemptions < 0) {
      throw new AppException("VALIDATION_ERROR", "max_redemptions must be >= 0", 400);
    }

    String coupon = cur.couponCode();
    if (req.containsKey("coupon_code")) {
      String next = normalizeCoupon(str(req.get("coupon_code")));
      if (next == null) {
        throw new AppException("VALIDATION_ERROR", "coupon_code is required when provided", 400);
      }
      if (!next.equals(cur.couponCode())
          && store.couponExists(principal.pharmacyId(), next, cur.id())) {
        throw new AppException(
            "COUPON_CODE_EXISTS", "Coupon code already exists for this pharmacy", 409);
      }
      coupon = next;
    }

    Instant now = clock.instant();
    PharmacyOffer updated =
        store.update(
            new PharmacyOffer(
                cur.id(),
                cur.pharmacyId(),
                title,
                coupon,
                discountType,
                storedValue,
                appliesTo,
                scopeIds,
                online,
                counter,
                cur.active(),
                validFrom,
                validUntil,
                maxRedemptions,
                cur.totalRedemptions(),
                cur.createdAt(),
                now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("offer_id", updated.id().toString());
    data.put("title", updated.title());
    data.put("updated_at", updated.updatedAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> toggle(MedmatePrincipal principal, UUID offerId) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:offers:toggle:" + principal.pharmacyId(), 30);

    PharmacyOffer cur = requireOffer(principal.pharmacyId(), offerId);
    if (cur.isExpired(today())) {
      throw new AppException("OFFER_EXPIRED", "Offer has already expired", 400);
    }
    Instant now = clock.instant();
    PharmacyOffer updated =
        store.update(
            new PharmacyOffer(
                cur.id(),
                cur.pharmacyId(),
                cur.title(),
                cur.couponCode(),
                cur.discountType(),
                cur.discountValue(),
                cur.appliesTo(),
                cur.scopeIds(),
                cur.online(),
                cur.counter(),
                !cur.active(),
                cur.validFrom(),
                cur.validUntil(),
                cur.maxRedemptions(),
                cur.totalRedemptions(),
                cur.createdAt(),
                now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("offer_id", updated.id().toString());
    data.put("is_active", updated.active());
    data.put("toggled_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID offerId) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:offers:delete:" + principal.pharmacyId(), 10);

    PharmacyOffer cur = requireOffer(principal.pharmacyId(), offerId);
    LocalDate today = today();
    if (cur.isExpired(today)) {
      throw new AppException("OFFER_EXPIRED", "Offer has already expired", 400);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("offer_id", cur.id().toString());
    if (cur.totalRedemptions() > 0) {
      Instant now = clock.instant();
      store.update(
          new PharmacyOffer(
              cur.id(),
              cur.pharmacyId(),
              cur.title(),
              cur.couponCode(),
              cur.discountType(),
              cur.discountValue(),
              cur.appliesTo(),
              cur.scopeIds(),
              cur.online(),
              cur.counter(),
              false,
              cur.validFrom(),
              today,
              cur.maxRedemptions(),
              cur.totalRedemptions(),
              cur.createdAt(),
              now));
      data.put("action", "SET_EXPIRED");
      data.put("message", "Offer had redemptions and has been expired instead of deleted.");
      data.put("valid_until", today.toString());
    } else {
      store.hardDelete(principal.pharmacyId(), offerId);
      data.put("action", "HARD_DELETED");
      data.put("message", "Offer permanently deleted.");
    }
    return data;
  }

  public Map<String, Object> validate(MedmatePrincipal principal, Map<String, Object> body) {
    requireGrowth(principal);
    requireStaff(principal);
    rateLimit("pharmacy:offers:validate:" + principal.pharmacyId(), 60);

    Map<String, Object> req = body == null ? Map.of() : body;
    String coupon = normalizeCoupon(str(req.get("coupon_code")));
    if (coupon == null) {
      throw new AppException("VALIDATION_ERROR", "coupon_code is required", 400);
    }
    BigDecimal cartTotal = requirePositiveDecimal(req.get("cart_total"), "cart_total");
    List<UUID> productIds = parseUuidList(req.get("product_ids"), "product_ids");
    if (productIds.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "product_ids is required", 400);
    }

    Optional<PharmacyOffer> found = store.findByCoupon(principal.pharmacyId(), coupon);
    if (found.isEmpty()) {
      return invalid("COUPON_NOT_FOUND", "Coupon code not found for this pharmacy");
    }
    PharmacyOffer offer = found.get();
    LocalDate today = today();
    if (!offer.active()) {
      return invalid("COUPON_NOT_ACTIVE", "This coupon is not active");
    }
    if (offer.isExpired(today)) {
      return invalid("COUPON_EXPIRED", "This coupon has expired");
    }
    if (today.isBefore(offer.validFrom())) {
      return invalid("COUPON_EXPIRED", "This coupon has expired");
    }
    if (offer.maxRedemptions() > 0 && offer.totalRedemptions() >= offer.maxRedemptions()) {
      return invalid("COUPON_LIMIT_REACHED", "Coupon redemption limit reached");
    }

    long cartTotalPaise = MoneyMath.rupeesToPaise(cartTotal);
    long eligible =
        eligibleSubtotalForProducts(principal.pharmacyId(), offer, productIds, cartTotalPaise);
    if (eligible <= 0) {
      return invalid(
          "COUPON_NOT_APPLICABLE", "This coupon does not apply to any items in the cart.");
    }

    long amount =
        MoneyMath.computeOfferDiscountPaise(offer.discountType(), offer.discountValue(), eligible);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("is_valid", true);
    data.put("offer_id", offer.id().toString());
    data.put("title", offer.title());
    data.put("discount_type", offer.discountType().name());
    data.put(
        "discount_value",
        MoneyMath.offerDiscountValueForApi(offer.discountType(), offer.discountValue()));
    data.put("discount_amount", MoneyMath.paiseToRupees(amount));
    data.put("applies_to_description", appliesToDescription(offer));
    data.put("expires_on", offer.validUntil().toString());
    return data;
  }

  /** Highest counter offer for cart items, or empty when none apply. */
  public Optional<AppliedOffer> bestCounterOffer(
      UUID pharmacyId, List<PosCartItem> items, boolean growthEnabled) {
    if (!growthEnabled || items == null || items.isEmpty()) {
      return Optional.empty();
    }
    LocalDate today = today();
    List<PharmacyOffer> offers = store.listActiveCounterOffers(pharmacyId, today);
    if (offers.isEmpty()) {
      return Optional.empty();
    }
    List<UUID> productIds = items.stream().map(PosCartItem::productId).distinct().toList();
    Map<UUID, UUID> productCats = store.productCategoryIds(pharmacyId, productIds);
    Map<UUID, Long> lineByProduct = new LinkedHashMap<>();
    for (PosCartItem item : items) {
      lineByProduct.merge(item.productId(), item.lineTotalPaise(), Long::sum);
    }

    AppliedOffer best = null;
    for (PharmacyOffer offer : offers) {
      long eligible = eligibleSubtotal(offer, lineByProduct, productCats);
      if (eligible <= 0) {
        continue;
      }
      long amount =
          MoneyMath.computeOfferDiscountPaise(
              offer.discountType(), offer.discountValue(), eligible);
      if (best == null || amount > best.discountAmountPaise()) {
        best = new AppliedOffer(offer, amount, eligible);
      }
    }
    return Optional.ofNullable(best);
  }

  public Optional<PharmacyOffer> findById(UUID pharmacyId, UUID offerId) {
    return store.findById(pharmacyId, offerId);
  }

  private long eligibleSubtotalForProducts(
      UUID pharmacyId, PharmacyOffer offer, List<UUID> productIds, long cartTotalPaise) {
    if (offer.appliesTo() == OfferAppliesTo.ALL) {
      return cartTotalPaise;
    }
    Map<UUID, UUID> productCats = store.productCategoryIds(pharmacyId, productIds);
    if (offer.appliesTo() == OfferAppliesTo.PRODUCT) {
      Set<UUID> scope = new HashSet<>(offer.scopeIds());
      boolean any = productIds.stream().anyMatch(scope::contains);
      return any ? cartTotalPaise : 0L;
    }
    Set<UUID> scope = new HashSet<>(offer.scopeIds());
    boolean any =
        productIds.stream()
            .map(productCats::get)
            .anyMatch(cat -> cat != null && scope.contains(cat));
    return any ? cartTotalPaise : 0L;
  }

  private static long eligibleSubtotal(
      PharmacyOffer offer, Map<UUID, Long> lineByProduct, Map<UUID, UUID> productCats) {
    if (offer.appliesTo() == OfferAppliesTo.ALL) {
      return lineByProduct.values().stream().mapToLong(Long::longValue).sum();
    }
    Set<UUID> scope = new HashSet<>(offer.scopeIds());
    long sum = 0L;
    for (Map.Entry<UUID, Long> e : lineByProduct.entrySet()) {
      boolean match;
      if (offer.appliesTo() == OfferAppliesTo.PRODUCT) {
        match = scope.contains(e.getKey());
      } else {
        UUID cat = productCats.get(e.getKey());
        if (cat == null) {
          match = false;
        } else {
          match = scope.contains(cat);
        }
      }
      if (match) {
        sum += e.getValue();
      }
    }
    return sum;
  }

  private Map<String, Object> toListItem(PharmacyOffer o, LocalDate today) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("offer_id", o.id().toString());
    row.put("title", o.title());
    row.put("coupon_code", o.couponCode());
    row.put("discount_type", o.discountType().name());
    row.put(
        "discount_value", MoneyMath.offerDiscountValueForApi(o.discountType(), o.discountValue()));
    row.put("applies_to", o.appliesTo().name());
    if (o.appliesTo() == OfferAppliesTo.CATEGORY) {
      row.put("category_names", store.categoryNames(o.scopeIds()).values().stream().toList());
    }
    row.put("is_online", o.online());
    row.put("is_counter", o.counter());
    row.put("valid_from", o.validFrom().toString());
    row.put("valid_until", o.validUntil().toString());
    row.put("max_redemptions", o.maxRedemptions());
    row.put("total_redemptions", o.totalRedemptions());
    row.put("is_active", o.active());
    row.put("is_expired", o.isExpired(today));
    return row;
  }

  private String appliesToDescription(PharmacyOffer offer) {
    return switch (offer.appliesTo()) {
      case ALL -> "Applies to: All products";
      case PRODUCT -> "Applies to: Selected products";
      case CATEGORY -> {
        List<String> names = store.categoryNames(offer.scopeIds()).values().stream().toList();
        if (names.isEmpty()) {
          yield "Applies to: Selected categories";
        }
        yield "Applies to: " + String.join(", ", names) + " category";
      }
    };
  }

  private static Map<String, Object> invalid(String code, String message) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("is_valid", false);
    data.put("error_code", code);
    data.put("message", message);
    return data;
  }

  private PharmacyOffer requireOffer(UUID pharmacyId, UUID offerId) {
    return store
        .findById(pharmacyId, offerId)
        .orElseThrow(() -> new AppException("OFFER_NOT_FOUND", "Offer not found", 404));
  }

  private String generateUniqueCoupon(UUID pharmacyId) {
    for (int attempt = 0; attempt < 20; attempt++) {
      StringBuilder sb = new StringBuilder(6);
      for (int i = 0; i < 6; i++) {
        sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
      }
      String code = sb.toString();
      if (!store.couponExists(pharmacyId, code, null)) {
        return code;
      }
    }
    throw new AppException("VALIDATION_ERROR", "Unable to generate unique coupon code", 500);
  }

  private long toStoredDiscountValue(DiscountType type, BigDecimal apiValue) {
    if (type == DiscountType.PERCENTAGE) {
      long pct = apiValue.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
      if (pct <= 0 || pct > MoneyMath.MAX_OFFER_DISCOUNT_PCT.longValue()) {
        throw new AppException(
            "DISCOUNT_EXCEEDS_PLATFORM_LIMIT", "Discount exceeds 50% or ₹1000 cap", 400);
      }
      return pct;
    }
    long paise = MoneyMath.rupeesToPaise(apiValue);
    if (paise > MoneyMath.MAX_OFFER_DISCOUNT_PAISE) {
      throw new AppException(
          "DISCOUNT_EXCEEDS_PLATFORM_LIMIT", "Discount exceeds 50% or ₹1000 cap", 400);
    }
    return paise;
  }

  private List<UUID> resolveScopeIds(OfferAppliesTo appliesTo, Map<String, Object> req) {
    if (appliesTo == OfferAppliesTo.ALL) {
      return List.of();
    }
    if (appliesTo == OfferAppliesTo.CATEGORY) {
      List<UUID> ids = parseUuidList(req.get("category_ids"), "category_ids");
      if (ids.isEmpty()) {
        throw new AppException(
            "MISSING_SCOPE_IDS", "category_ids required for CATEGORY offers", 400);
      }
      return ids;
    }
    List<UUID> ids = parseUuidList(req.get("product_ids"), "product_ids");
    if (ids.isEmpty()) {
      throw new AppException("MISSING_SCOPE_IDS", "product_ids required for PRODUCT offers", 400);
    }
    return ids;
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(INDIA));
  }

  private void requireGrowth(MedmatePrincipal principal) {
    requireStaff(principal);
    if (!plan.growthFeaturesEnabled()) {
      throw new AppException("PLAN_FEATURE_LOCKED", "Offers require Growth plan or higher", 403);
    }
  }

  static void requireOwner(MedmatePrincipal principal) {
    requireStaff(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Pharmacy owner role required", 403);
    }
  }

  static void requireStaff(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.PHARMACY_OWNER && role != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static String requireTitle(String title) {
    if (title == null || title.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "title is required", 400);
    }
    String t = title.trim();
    if (t.length() > 200) {
      throw new AppException("VALIDATION_ERROR", "title max 200 characters", 400);
    }
    return t;
  }

  private static DiscountType requireDiscountType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "discount_type is required", 400);
    }
    try {
      return DiscountType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid discount_type", 400);
    }
  }

  private static OfferAppliesTo requireAppliesTo(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "applies_to is required", 400);
    }
    try {
      return OfferAppliesTo.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid applies_to", 400);
    }
  }

  private static LocalDate requireDate(Object raw, String field) {
    if (raw == null) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    try {
      return LocalDate.parse(raw.toString().trim());
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", field + " must be YYYY-MM-DD", 400);
    }
  }

  private static BigDecimal requirePositiveDecimal(Object raw, String field) {
    if (raw == null) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    final BigDecimal v;
    try {
      v = raw instanceof BigDecimal bd ? bd : new BigDecimal(raw.toString().trim());
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", field + " must be a number", 400);
    }
    if (v.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", field + " must be > 0", 400);
    }
    return v;
  }

  private static String normalizeCoupon(String raw) {
    if (raw == null) {
      return null;
    }
    if (raw.isBlank()) {
      return null;
    }
    String c = raw.trim().toUpperCase(Locale.ROOT);
    if (c.length() > 20 || !c.matches("[A-Z0-9]+")) {
      throw new AppException(
          "VALIDATION_ERROR", "coupon_code must be 1-20 alphanumeric uppercase", 400);
    }
    return c;
  }

  private static List<UUID> parseUuidList(Object raw, String field) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new AppException("VALIDATION_ERROR", field + " must be an array", 400);
    }
    List<UUID> out = new ArrayList<>();
    for (Object o : list) {
      if (o == null) {
        continue;
      }
      try {
        out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
      } catch (Exception ex) {
        throw new AppException("VALIDATION_ERROR", field + " contains invalid UUID", 400);
      }
    }
    return out;
  }

  private static boolean bool(Object raw, boolean defaultValue) {
    if (raw == null) {
      return defaultValue;
    }
    return raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
  }

  private static int intOrDefault(Object raw, int defaultValue) {
    if (raw == null) {
      return defaultValue;
    }
    if (raw instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(raw.toString().trim());
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid integer value", 400);
    }
  }

  private static String str(Object raw) {
    return raw == null ? null : raw.toString();
  }
}
