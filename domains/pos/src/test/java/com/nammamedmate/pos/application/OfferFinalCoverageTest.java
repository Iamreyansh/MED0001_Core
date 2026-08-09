package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
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
import java.util.LinkedHashMap;
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
class OfferFinalCoverageTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock OfferStore offerStore;
  @Mock PosPlanPort plan;
  @Mock RateLimiter rateLimiter;
  @Mock PosCartStore cartStore;
  @Mock ProductLookupPort productLookup;
  @Mock PosFefoPort fefo;
  @Mock PosCustomerPort customers;
  @Mock PosKhataPort khata;
  @Mock InvoiceStore invoiceStore;
  @Mock StockDeductionPort stock;

  OfferService offers;
  PosCartService carts;
  PosCheckoutService checkout;
  UUID pharmacy = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(plan.growthFeaturesEnabled()).thenReturn(true);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(offerStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(offerStore.update(any())).thenAnswer(inv -> inv.getArgument(0));
    when(offerStore.couponExists(any(), any(), any())).thenReturn(false);
    offers = new OfferService(offerStore, plan, rateLimiter, clock);
    carts =
        new PosCartService(
            cartStore, productLookup, fefo, customers, khata, offers, plan, rateLimiter, clock);
    checkout =
        new PosCheckoutService(
            cartStore, invoiceStore, stock, khata, offerStore, rateLimiter, clock);
  }

  @Test
  void offerUpdateListValidateAndCartOfferPaths() {
    UUID offerId = UUID.randomUUID();
    UUID cat = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    PharmacyOffer cur =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "Old",
            "OLD1",
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
    when(offerStore.findById(pharmacy, offerId)).thenReturn(Optional.of(cur));
    when(offerStore.categoryNames(List.of(cat))).thenReturn(Map.of(cat, "Antibiotics"));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("coupon_code", "NEW1");
    body.put("applies_to", "PRODUCT");
    body.put("product_ids", List.of(productId.toString()));
    body.put("discount_type", "FLAT_RS");
    body.put("discount_value", 50);
    body.put("is_online", true);
    body.put("is_counter", false);
    body.put("valid_from", "2026-07-02");
    body.put("valid_until", "2026-08-01");
    body.put("max_redemptions", 5);
    assertThat(offers.update(owner, offerId, body).get("offer_id")).isEqualTo(offerId.toString());

    when(offerStore.list(eq(pharmacy), eq("ALL"), any(), eq(1), eq(20)))
        .thenReturn(new OfferStore.ListPage(List.of(cur), 1));
    when(offerStore.kpi(eq(pharmacy), any())).thenReturn(new OfferStore.Kpi(1, 9));
    assertThat(offers.list(owner, "ALL", 1, 20).data().get("offers")).asList().isNotEmpty();

    // expired delete
    PharmacyOffer expired =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "E",
            "E1",
            DiscountType.PERCENTAGE,
            10,
            OfferAppliesTo.ALL,
            List.of(),
            false,
            true,
            true,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 2),
            0,
            0,
            NOW,
            NOW);
    when(offerStore.findById(pharmacy, offerId)).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> offers.delete(owner, offerId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("OFFER_EXPIRED");
    assertThatThrownBy(() -> offers.toggle(owner, offerId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("OFFER_EXPIRED");

    // validate PRODUCT not applicable / CATEGORY applicable
    PharmacyOffer prod =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "P",
            "P1",
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
    when(offerStore.findByCoupon(pharmacy, "P1")).thenReturn(Optional.of(prod));
    when(offerStore.productCategoryIds(eq(pharmacy), any())).thenReturn(Map.of());
    assertThat(
            offers
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "P1",
                        "cart_total",
                        100,
                        "product_ids",
                        List.of(UUID.randomUUID().toString())))
                .get("error_code"))
        .isEqualTo("COUPON_NOT_APPLICABLE");

    PharmacyOffer catOffer =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "C",
            "C1",
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
    when(offerStore.findByCoupon(pharmacy, "C1")).thenReturn(Optional.of(catOffer));
    when(offerStore.productCategoryIds(pharmacy, List.of(productId)))
        .thenReturn(Map.of(productId, cat));
    when(offerStore.categoryNames(List.of(cat))).thenReturn(Map.of(cat, "Antibiotics"));
    assertThat(
            offers
                .validate(
                    owner,
                    Map.of(
                        "coupon_code",
                        "C1",
                        "cart_total",
                        100,
                        "product_ids",
                        List.of(productId.toString())))
                .get("is_valid"))
        .isEqualTo(true);

    // validation errors
    assertThatThrownBy(
            () -> offers.validate(owner, Map.of("cart_total", 1, "product_ids", List.of())))
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
                        "BOGUS",
                        "discount_value",
                        1,
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
                        1,
                        "applies_to",
                        "NOPE",
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
                        "bad!",
                        "discount_type",
                        "PERCENTAGE",
                        "discount_value",
                        1,
                        "applies_to",
                        "ALL",
                        "valid_from",
                        "2026-07-01",
                        "valid_until",
                        "2026-07-31")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> OfferService.requireStaff(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                OfferService.requireStaff(
                    new MedmatePrincipal(
                        staff, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    // cart auto-apply + checkout redemption
    UUID cartId = UUID.randomUUID();
    PharmacyOffer counter =
        new PharmacyOffer(
            offerId,
            pharmacy,
            "Counter",
            "CTR1",
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
    when(offerStore.listActiveCounterOffers(eq(pharmacy), any())).thenReturn(List.of(counter));
    when(offerStore.findById(pharmacy, offerId)).thenReturn(Optional.of(counter));
    when(offerStore.productCategoryIds(eq(pharmacy), any())).thenReturn(Map.of());

    PosCart cart =
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
            NOW.plusSeconds(1000),
            null,
            null,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(cart));
    PosCartItem item =
        PosCartItem.compute(
            UUID.randomUUID(),
            cartId,
            productId,
            "P",
            UUID.randomUUID(),
            "BN",
            LocalDate.of(2027, 1, 1),
            1,
            false,
            10_000L,
            12,
            false,
            1,
            "H",
            NOW);
    when(cartStore.listItems(cartId)).thenReturn(List.of(item));
    when(cartStore.findById(pharmacy, cartId))
        .thenReturn(Optional.of(cart))
        .thenAnswer(
            inv ->
                Optional.of(
                    new PosCart(
                        cartId,
                        pharmacy,
                        staff,
                        null,
                        null,
                        null,
                        null,
                        DiscountType.PERCENTAGE,
                        BigDecimal.valueOf(20),
                        2000,
                        10_000,
                        1071,
                        8000,
                        PosCartStatus.ACTIVE,
                        NOW.plusSeconds(1000),
                        null,
                        offerId,
                        NOW,
                        NOW)));

    Map<String, Object> view = carts.getCart(owner, cartId);
    assertThat(view.get("applied_offers")).asList().isNotEmpty();

    PosCart checkoutCart =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            null,
            null,
            null,
            null,
            DiscountType.PERCENTAGE,
            BigDecimal.valueOf(20),
            2000,
            10_000,
            1071,
            8000,
            PosCartStatus.ACTIVE,
            NOW.plusSeconds(1000),
            null,
            offerId,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(checkoutCart));
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(1);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> checked =
        checkout.checkout(owner, cartId, "CASH", BigDecimal.valueOf(100), null, null);
    assertThat(checked.get("invoice_id")).isNotNull();
    // checkout with applied offer but zero discount skips redemption insert body
    PosCart zeroDisc =
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
            NOW.plusSeconds(1000),
            null,
            offerId,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(zeroDisc));
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    productId,
                    "P",
                    UUID.randomUUID(),
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    100L,
                    12,
                    false,
                    1,
                    "H",
                    NOW)));
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(2);
    checkout.checkout(owner, cartId, "CASH", BigDecimal.ONE, null, null);
  }
}
