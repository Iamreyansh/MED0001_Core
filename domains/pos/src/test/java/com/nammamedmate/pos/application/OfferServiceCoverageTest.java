package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.OfferAppliesTo;
import com.nammamedmate.pos.domain.PharmacyOffer;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
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
class OfferServiceCoverageTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock OfferStore store;
  @Mock PosPlanPort plan;
  @Mock RateLimiter rateLimiter;

  OfferService service;
  UUID pharmacy = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
  MedmatePrincipal staffPrincipal =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j");
  MedmatePrincipal customer =
      new MedmatePrincipal(staff, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(plan.growthFeaturesEnabled()).thenReturn(true);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));
    service = new OfferService(store, plan, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void listCreateUpdateValidateDeleteBranches() {
    when(store.list(eq(pharmacy), eq("ACTIVE"), any(), eq(1), eq(20)))
        .thenReturn(new OfferStore.ListPage(List.of(), 0));
    when(store.kpi(eq(pharmacy), any())).thenReturn(new OfferStore.Kpi(0, 0));
    assertThat(service.list(owner, null, null, null).data().get("offers")).isEqualTo(List.of());

    assertThatThrownBy(() -> service.list(owner, "NOPE", 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID cat = UUID.randomUUID();
    when(store.couponExists(eq(pharmacy), any(), isNull())).thenReturn(false);
    Map<String, Object> createBody = new java.util.LinkedHashMap<>();
    createBody.put("title", "Cat offer");
    createBody.put("coupon_code", "cat10");
    createBody.put("discount_type", "FLAT_RS");
    createBody.put("discount_value", 100);
    createBody.put("applies_to", "CATEGORY");
    createBody.put("category_ids", List.of(cat.toString()));
    createBody.put("is_online", true);
    createBody.put("is_counter", true);
    createBody.put("valid_from", "2026-07-01");
    createBody.put("valid_until", "2026-07-31");
    createBody.put("max_redemptions", 10);
    Map<String, Object> created = service.create(owner, createBody);
    assertThat(created.get("coupon_code")).isEqualTo("CAT10");
    assertThat((java.math.BigDecimal) created.get("discount_value")).isEqualByComparingTo("100.00");

    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    Map.of(
                        "title",
                        "X",
                        "discount_type",
                        "FLAT_RS",
                        "discount_value",
                        1001,
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
                service.create(
                    owner,
                    Map.of(
                        "title",
                        "X",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-08-01",
                        "valid_until",
                        "2026-07-01")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE_RANGE");

    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    Map.of(
                        "title",
                        "X",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        10,
                        "applies_to",
                        "CATEGORY",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MISSING_SCOPE_IDS");

    when(store.couponExists(pharmacy, "DUP", null)).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    Map.of(
                        "title",
                        "X",
                        "coupon_code",
                        "DUP",
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
        .isEqualTo("COUPON_CODE_EXISTS");

    UUID offerId = UUID.randomUUID();
    PharmacyOffer cur =
        baseOffer(offerId, "OLD", OfferAppliesTo.ALL, List.of(), true, LocalDate.of(2026, 12, 31));
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    Map<String, Object> patched =
        service.update(owner, offerId, Map.of("title", "New title", "discount_value", 12));
    assertThat(patched.get("title")).isEqualTo("New title");

    PharmacyOffer expired =
        baseOffer(offerId, "OLD", OfferAppliesTo.ALL, List.of(), true, LocalDate.of(2026, 7, 1));
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> service.update(owner, offerId, Map.of("title", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("OFFER_EXPIRED");

    when(store.findById(pharmacy, offerId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(owner, offerId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("OFFER_NOT_FOUND");

    PharmacyOffer zero =
        baseOffer(offerId, "Z", OfferAppliesTo.ALL, List.of(), true, LocalDate.of(2026, 12, 31));
    when(store.findById(pharmacy, offerId)).thenReturn(Optional.of(zero));
    assertThat(service.delete(owner, offerId).get("action")).isEqualTo("HARD_DELETED");
    verify(store).hardDelete(pharmacy, offerId);

    when(store.findByCoupon(pharmacy, "GONE")).thenReturn(Optional.empty());
    assertThat(
            service
                .validate(
                    staffPrincipal,
                    Map.of(
                        "coupon_code",
                        "GONE",
                        "cart_total",
                        10,
                        "product_ids",
                        List.of(UUID.randomUUID().toString())))
                .get("error_code"))
        .isEqualTo("COUPON_NOT_FOUND");

    PharmacyOffer inactive =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "T",
            "OFF",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            false,
            false,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findByCoupon(pharmacy, "OFF")).thenReturn(Optional.of(inactive));
    assertThat(
            service
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "OFF",
                        "cart_total",
                        10,
                        "product_ids",
                        List.of(UUID.randomUUID().toString())))
                .get("error_code"))
        .isEqualTo("COUPON_NOT_ACTIVE");

    PharmacyOffer limited =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "T",
            "LIM",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            1,
            1,
            NOW,
            NOW);
    when(store.findByCoupon(pharmacy, "LIM")).thenReturn(Optional.of(limited));
    assertThat(
            service
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "LIM",
                        "cart_total",
                        10,
                        "product_ids",
                        List.of(UUID.randomUUID().toString())))
                .get("error_code"))
        .isEqualTo("COUPON_LIMIT_REACHED");

    PharmacyOffer past =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "T",
            "EXP",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findByCoupon(pharmacy, "EXP")).thenReturn(Optional.of(past));
    assertThat(
            service
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "EXP",
                        "cart_total",
                        10,
                        "product_ids",
                        List.of(UUID.randomUUID().toString())))
                .get("error_code"))
        .isEqualTo("COUPON_EXPIRED");

    assertThatThrownBy(() -> service.create(staffPrincipal, Map.of("title", "x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(customer, "ALL", 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, "ALL", 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void productValidateAndBestOfferEmpty() {
    UUID productId = UUID.randomUUID();
    UUID offerId = UUID.randomUUID();
    PharmacyOffer productOffer =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "P",
            "PROD",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.PRODUCT,
            List.of(productId),
            false,
            true,
            true,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            0,
            0,
            NOW,
            NOW);
    when(store.findByCoupon(pharmacy, "PROD")).thenReturn(Optional.of(productOffer));
    when(store.productCategoryIds(eq(pharmacy), any())).thenReturn(Map.of());
    Map<String, Object> ok =
        service.validate(
            owner,
            Map.of(
                "coupon_code",
                "PROD",
                "cart_total",
                200,
                "product_ids",
                List.of(productId.toString())));
    assertThat(ok.get("is_valid")).isEqualTo(true);

    assertThat(service.bestCounterOffer(pharmacy, List.of(), true)).isEmpty();
    assertThat(service.bestCounterOffer(pharmacy, List.of(), false)).isEmpty();
    when(store.listActiveCounterOffers(eq(pharmacy), any())).thenReturn(List.of());
    PosCartItem item =
        PosCartItem.compute(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
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
    assertThat(service.bestCounterOffer(pharmacy, List.of(item), true)).isEmpty();
  }

  private PharmacyOffer baseOffer(
      UUID id,
      String code,
      OfferAppliesTo applies,
      List<UUID> scope,
      boolean active,
      LocalDate until) {
    return new PharmacyOffer(
        id,
        pharmacy,
        "Title",
        code,
        DiscountType.PERCENTAGE,
        10,
        applies,
        scope,
        false,
        true,
        active,
        LocalDate.of(2026, 7, 1),
        until,
        0,
        0,
        NOW,
        NOW);
  }
}
