package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.OfferAppliesTo;
import com.nammamedmate.pos.domain.PharmacyOffer;
import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.pos.domain.PosCartStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfferGapsTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock OfferStore store;
  @Mock PosPlanPort plan;
  @Mock RateLimiter rateLimiter;
  @Mock PosCartStore cartStore;
  @Mock ProductLookupPort productLookup;
  @Mock PosFefoPort fefo;
  @Mock PosCustomerPort customers;
  @Mock PosKhataPort khata;

  OfferService offers;
  PosCartService carts;
  UUID pharmacy = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(plan.growthFeaturesEnabled()).thenReturn(true);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));
    offers = new OfferService(store, plan, rateLimiter, clock);
    carts =
        new PosCartService(
            cartStore, productLookup, fefo, customers, khata, offers, plan, rateLimiter, clock);
  }

  @Test
  void remainingBranches() {
    assertThat(new OfferStore.ListPage(null, 0).items()).isEmpty();
    assertThat(new OfferService.ListResult(null, null).data()).isEmpty();
    assertThat(new OfferService.ListResult(Map.of("k", 1), null).data()).containsEntry("k", 1);
    assertThat(
            new PharmacyOffer(
                    UUID.randomUUID(),
                    pharmacy,
                    "T",
                    "C",
                    DiscountType.PERCENTAGE,
                    1,
                    OfferAppliesTo.ALL,
                    null,
                    false,
                    false,
                    true,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    0,
                    0,
                    NOW,
                    NOW)
                .scopeIds())
        .isEmpty();

    // create null body / negative max / title too long / PRODUCT missing / pct 0 after scale
    assertThatThrownBy(() -> offers.create(owner, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x".repeat(201),
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31",
                        "max_redemptions",
                        -1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        0.4,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISCOUNT_EXCEEDS_PLATFORM_LIMIT");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "PRODUCT",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MISSING_SCOPE_IDS");

    when(store.couponExists(eq(pharmacy), any(), isNull())).thenReturn(true);
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID offerId = UUID.randomUUID();
    UUID cat = UUID.randomUUID();
    PharmacyOffer cur =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "Old",
            "OLD",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.CATEGORY,
            List.of(cat),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 12, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    when(store.couponExists(pharmacy, "TAKEN", offerId)).thenReturn(true);

    assertThat(offers.update(owner, offerId, null).get("offer_id")).isEqualTo(offerId.toString());
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThat(offers.update(owner, offerId, Map.of("discount_type", "FLAT_RS")).get("offer_id"))
        .isEqualTo(offerId.toString());
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThatThrownBy(
            () ->
                offers.update(
                    owner,
                    offerId,
                    Map.of("valid_from", "2026-08-01", "valid_until", "2026-07-01")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE_RANGE");
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThatThrownBy(() -> offers.update(owner, offerId, Map.of("max_redemptions", -3)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThatThrownBy(() -> offers.update(owner, offerId, Map.of("coupon_code", "")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThatThrownBy(() -> offers.update(owner, offerId, Map.of("coupon_code", "TAKEN")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COUPON_CODE_EXISTS");
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    PharmacyOffer productCur =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "Old",
            "OLD",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.PRODUCT,
            List.of(UUID.randomUUID()),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 12, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThat(offers.update(owner, offerId, Map.of("applies_to", "CATEGORY")).get("offer_id"))
        .isEqualTo(offerId.toString());
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(productCur));
    assertThat(offers.update(owner, offerId, Map.of("applies_to", "PRODUCT")).get("offer_id"))
        .isEqualTo(offerId.toString());

    Map<String, Object> missingRequired = new java.util.LinkedHashMap<>();
    missingRequired.put("title", "x");
    missingRequired.put("discount_type", "PERCENTAGE");
    missingRequired.put("discount_value", null);
    missingRequired.put("applies_to", "ALL");
    missingRequired.put("valid_from", "2026-07-01");
    missingRequired.put("valid_until", "2026-07-31");
    assertThatThrownBy(() -> offers.create(owner, missingRequired))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    missingRequired.put("discount_value", 10);
    missingRequired.put("valid_from", null);
    assertThatThrownBy(() -> offers.create(owner, missingRequired))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    missingRequired.put("valid_from", "2026-07-01");
    missingRequired.put("applies_to", null);
    assertThatThrownBy(() -> offers.create(owner, missingRequired))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    missingRequired.put("applies_to", "ALL");
    missingRequired.put("title", null);
    assertThatThrownBy(() -> offers.create(owner, missingRequired))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    missingRequired.put("title", "x");
    missingRequired.put("discount_type", null);
    assertThatThrownBy(() -> offers.create(owner, missingRequired))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> validateNullProducts = new java.util.LinkedHashMap<>();
    validateNullProducts.put("coupon_code", "X");
    validateNullProducts.put("cart_total", 10);
    validateNullProducts.put("product_ids", null);
    assertThatThrownBy(() -> offers.validate(owner, validateNullProducts))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findByCoupon(pharmacy, "X")).thenReturn(Optional.empty());
    assertThat(
            offers
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "X",
                        "cart_total",
                        10,
                        "product_ids",
                        java.util.Arrays.asList(null, UUID.randomUUID())))
                .get("error_code"))
        .isEqualTo("COUPON_NOT_FOUND");

    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> offers.validate(owner, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "X",
                        "cart_total",
                        "nope",
                        "product_ids",
                        List.of(UUID.randomUUID().toString()))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.validate(
                    owner, Map.of("coupon_code", "X", "cart_total", 10, "product_ids", "not-list")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.validate(
                    owner,
                    Map.of(
                        "coupon_code", "X", "cart_total", 10, "product_ids", List.of("bad-uuid"))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "not-a-date",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        BigDecimal.TEN,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31",
                        "max_redemptions",
                        "nope",
                        "is_online",
                        "true")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // CATEGORY description empty names + counter offer skip ineligible
    PharmacyOffer catOffer =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "C",
            "CAT1",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.CATEGORY,
            List.of(cat),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findByCoupon(pharmacy, "CAT1")).thenReturn(Optional.of(catOffer));
    when(store.productCategoryIds(eq(pharmacy), any())).thenReturn(Map.of());
    when(store.categoryNames(List.of(cat))).thenReturn(Map.of());
    UUID pid = UUID.randomUUID();
    assertThat(
            offers
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "CAT1",
                        "cart_total",
                        50,
                        "product_ids",
                        List.of(pid.toString())))
                .get("error_code"))
        .isEqualTo("COUPON_NOT_APPLICABLE");

    when(store.productCategoryIds(pharmacy, List.of(pid))).thenReturn(Map.of(pid, cat));
    when(store.categoryNames(List.of(cat))).thenReturn(Map.of());
    assertThat(
            offers
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "CAT1",
                        "cart_total",
                        BigDecimal.valueOf(50),
                        "product_ids",
                        List.of(pid)))
                .get("is_valid"))
        .isEqualTo(true);

    when(store.listActiveCounterOffers(eq(pharmacy), any()))
        .thenReturn(
            List.of(
                catOffer,
                new PharmacyOffer(
                    UUID.randomUUID(),
                    pharmacy,
                    "P",
                    "PZERO",
                    DiscountType.PERCENTAGE,
                    10,
                    OfferAppliesTo.PRODUCT,
                    List.of(UUID.randomUUID()),
                    false,
                    true,
                    true,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    0,
                    0,
                    NOW,
                    NOW)));
    PosCartItem item =
        PosCartItem.compute(
            UUID.randomUUID(),
            UUID.randomUUID(),
            pid,
            "P",
            UUID.randomUUID(),
            "BN",
            LocalDate.of(2027, 1, 1),
            1,
            false,
            1000L,
            12,
            false,
            1,
            null,
            NOW);
    when(store.productCategoryIds(eq(pharmacy), any())).thenReturn(Map.of(pid, cat));
    assertThat(offers.bestCounterOffer(pharmacy, List.of(item), true)).isPresent();

    when(store.list(eq(pharmacy), any(), any(), anyInt(), anyInt()))
        .thenReturn(new OfferStore.ListPage(List.of(), 0));
    when(store.kpi(eq(pharmacy), any())).thenReturn(new OfferStore.Kpi(0, 0));
    offers.list(owner, "  ", 0, 0);
    offers.list(owner, "ACTIVE", 2, 200);
    offers.list(owner, null, null, null);

    // not-yet-valid coupon
    PharmacyOffer future =
        new PharmacyOffer(
            UUID.randomUUID(),
            pharmacy,
            "F",
            "FUT1",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findByCoupon(pharmacy, "FUT1")).thenReturn(Optional.of(future));
    assertThat(
            offers
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "FUT1",
                        "cart_total",
                        10,
                        "product_ids",
                        List.of(UUID.randomUUID().toString())))
                .get("error_code"))
        .isEqualTo("COUPON_EXPIRED");

    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThat(offers.update(owner, offerId, Map.of("coupon_code", "OLD")).get("offer_id"))
        .isEqualTo(offerId.toString());
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    assertThat(
            offers
                .update(owner, offerId, Map.of("category_ids", List.of(cat.toString())))
                .get("offer_id"))
        .isEqualTo(offerId.toString());

    assertThat(offers.bestCounterOffer(pharmacy, null, true)).isEmpty();

    // two counter offers — second lower amount keeps first
    PharmacyOffer high =
        new PharmacyOffer(
            UUID.randomUUID(),
            pharmacy,
            "H",
            "HI",
            DiscountType.PERCENTAGE,
            20,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            0,
            0,
            NOW,
            NOW);
    PharmacyOffer low =
        new PharmacyOffer(
            UUID.randomUUID(),
            pharmacy,
            "L",
            "LO",
            DiscountType.PERCENTAGE,
            5,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.listActiveCounterOffers(eq(pharmacy), any())).thenReturn(List.of(high, low));
    assertThat(offers.bestCounterOffer(pharmacy, List.of(item), true).get().offer().id())
        .isEqualTo(high.id());
    // PRODUCT-scoped counter offer that matches cart line
    PharmacyOffer prodMatch =
        new PharmacyOffer(
            UUID.randomUUID(),
            pharmacy,
            "PM",
            "PM1",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.PRODUCT,
            List.of(pid),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            0,
            0,
            NOW,
            NOW);
    // CATEGORY counter with product missing category → match=false
    when(store.listActiveCounterOffers(eq(pharmacy), any())).thenReturn(List.of(catOffer));
    when(store.productCategoryIds(eq(pharmacy), any())).thenReturn(Map.of());
    assertThat(offers.bestCounterOffer(pharmacy, List.of(item), true)).isEmpty();
    when(store.listActiveCounterOffers(eq(pharmacy), any()))
        .thenReturn(List.of(prodMatch, catOffer));
    when(store.productCategoryIds(eq(pharmacy), any())).thenReturn(Map.of(pid, cat));
    assertThat(offers.bestCounterOffer(pharmacy, List.of(item), true)).isPresent();

    assertThatThrownBy(
            () ->
                offers.toggle(
                    new MedmatePrincipal(
                        staff, AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j"),
                    offerId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                offers.validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "   ",
                        "cart_total",
                        1,
                        "product_ids",
                        List.of(pid.toString()))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.couponExists(eq(pharmacy), any(), isNull())).thenReturn(false);
    Map<String, Object> okCreate = new java.util.LinkedHashMap<>();
    okCreate.put("title", "Ok");
    okCreate.put("discount_type", "PERCENTAGE");
    okCreate.put("discount_value", "12");
    okCreate.put("applies_to", "ALL");
    okCreate.put("valid_from", "2026-07-01");
    okCreate.put("valid_until", "2026-07-31");
    okCreate.put("max_redemptions", "5");
    okCreate.put("is_online", "true");
    okCreate.put("is_counter", "false");
    assertThat(offers.create(owner, okCreate).get("coupon_code")).isNotNull();
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        "0",
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    UUID cartId = UUID.randomUUID();
    when(cartStore.findById(pharmacy, cartId))
        .thenReturn(
            Optional.of(
                new PosCart(
                    cartId,
                    pharmacy,
                    staff,
                    null,
                    null,
                    null,
                    null,
                    null,
                    BigDecimal.ZERO,
                    0,
                    0,
                    0,
                    0,
                    PosCartStatus.ACTIVE,
                    NOW.plusSeconds(100),
                    null,
                    null,
                    NOW,
                    NOW)))
        .thenReturn(Optional.empty());
    when(cartStore.listItems(cartId)).thenReturn(List.of());
    when(store.listActiveCounterOffers(eq(pharmacy), any())).thenReturn(List.of());
    assertThatThrownBy(() -> carts.getCart(owner, cartId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_NOT_FOUND");
  }

  @Test
  void lastJacocoBranches() {
    UUID offerId = UUID.randomUUID();
    UUID cat = UUID.randomUUID();
    UUID pid = UUID.randomUUID();

    PharmacyOffer catCur =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "Old",
            "OLD",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.CATEGORY,
            List.of(cat),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 12, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(catCur));
    // applies_to CATEGORY with category_ids already present (skip fill-from-cur)
    assertThat(
            offers
                .update(
                    owner,
                    offerId,
                    Map.of("applies_to", "CATEGORY", "category_ids", List.of(cat.toString())))
                .get("offer_id"))
        .isEqualTo(offerId.toString());

    PharmacyOffer prodCur =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "Old",
            "OLD",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.PRODUCT,
            List.of(pid),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 12, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(prodCur));
    assertThat(
            offers
                .update(
                    owner,
                    offerId,
                    Map.of("applies_to", "PRODUCT", "product_ids", List.of(pid.toString())))
                .get("offer_id"))
        .isEqualTo(offerId.toString());
    // product_ids without applies_to
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(prodCur));
    assertThat(
            offers
                .update(owner, offerId, Map.of("product_ids", List.of(pid.toString())))
                .get("offer_id"))
        .isEqualTo(offerId.toString());

    // blank title
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "   ",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // coupon too long / bad chars
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "coupon_code",
                        "ABCDEFGHIJKLMNOPQRSTU",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                offers.create(
                    owner,
                    Map.of(
                        "title",
                        "x",
                        "coupon_code",
                        "BAD-CODE",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // list CATEGORY (category_names) + ALL (skip category_names)
    PharmacyOffer allOffer =
        new PharmacyOffer(
            UUID.randomUUID(),
            pharmacy,
            "All",
            "ALL1",
            DiscountType.PERCENTAGE,
            5,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.list(eq(pharmacy), any(), any(), anyInt(), anyInt()))
        .thenReturn(new OfferStore.ListPage(List.of(catCur, allOffer), 2));
    when(store.kpi(eq(pharmacy), any())).thenReturn(new OfferStore.Kpi(2, 0));
    when(store.categoryNames(List.of(cat))).thenReturn(Map.of(cat, "Antibiotics"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> listed =
        (List<Map<String, Object>>) offers.list(owner, "ALL", 1, 20).data().get("offers");
    assertThat(listed).hasSize(2);
    assertThat(listed.get(0)).containsKey("category_names");
    assertThat(listed.get(1)).doesNotContainKey("category_names");

    // max_redemptions > 0 but not exhausted
    PharmacyOffer limitedOk =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "L",
            "LIMOK",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            5,
            2,
            NOW,
            NOW);
    when(store.findByCoupon(pharmacy, "LIMOK")).thenReturn(Optional.of(limitedOk));
    assertThat(
            offers
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "LIMOK",
                        "cart_total",
                        100,
                        "product_ids",
                        List.of(pid.toString())))
                .get("is_valid"))
        .isEqualTo(true);

    // recalculate with appliedOfferId set → skips manual discount
    UUID cartId = UUID.randomUUID();
    PosCart withOffer =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            null,
            null,
            null,
            null,
            DiscountType.PERCENTAGE,
            BigDecimal.TEN,
            0,
            0,
            0,
            0,
            PosCartStatus.ACTIVE,
            NOW.plusSeconds(100),
            null,
            offerId,
            NOW,
            NOW);
    when(cartStore.listItems(cartId)).thenReturn(List.of());
    when(store.listActiveCounterOffers(eq(pharmacy), any())).thenReturn(List.of());
    carts.recalculate(withOffer, NOW);
  }
}
