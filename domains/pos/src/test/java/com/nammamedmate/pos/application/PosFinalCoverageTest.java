package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.nammamedmate.pos.domain.MoneyMath;
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
class PosFinalCoverageTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock PosCartStore cartStore;
  @Mock ProductLookupPort productLookup;
  @Mock PosFefoPort fefo;
  @Mock PosCustomerPort customers;
  @Mock PosKhataPort khata;
  @Mock InvoiceStore invoiceStore;
  @Mock StockDeductionPort stock;
  @Mock OfferService offerService;
  @Mock OfferStore offerStore;
  @Mock PosPlanPort plan;
  @Mock RateLimiter rateLimiter;

  PosCartService carts;
  PosCheckoutService checkout;
  UUID pharmacy = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal principal =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(khata.outstandingPaise(any(), any())).thenReturn(0L);
    when(khata.creditLimitPaise(any(), any())).thenReturn(5_000_000L);
    when(plan.growthFeaturesEnabled()).thenReturn(true);
    when(offerService.bestCounterOffer(any(), any(), any(Boolean.class)))
        .thenReturn(Optional.empty());
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    carts =
        new PosCartService(
            cartStore,
            productLookup,
            fefo,
            customers,
            khata,
            offerService,
            plan,
            rateLimiter,
            clock);
    checkout =
        new PosCheckoutService(
            cartStore, invoiceStore, stock, khata, offerStore, rateLimiter, clock);
  }

  @Test
  void cartErrorAndHappyBranches() {
    UUID cartId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID cust = UUID.randomUUID();

    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.getCart(principal, cartId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_NOT_FOUND");

    PosCart abandoned = cart(cartId, PosCartStatus.ABANDONED, NOW.plusSeconds(10), cust);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(abandoned));
    assertThatThrownBy(() -> carts.clearCart(principal, cartId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_EXPIRED");

    PosCart active = cart(cartId, PosCartStatus.ACTIVE, NOW.plusSeconds(1000), cust);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(active));
    PosCartItem item =
        PosCartItem.compute(
            itemId,
            cartId,
            productId,
            "Para",
            batchId,
            "BN",
            LocalDate.of(2027, 1, 1),
            2,
            false,
            2250L,
            12,
            true,
            15,
            "HSN",
            NOW);
    when(cartStore.listItems(cartId)).thenReturn(List.of(item));
    Map<String, Object> view = carts.getCart(principal, cartId);
    assertThat(view.get("rx_items_present")).isEqualTo(true);
    assertThat(view.get("customer")).isInstanceOf(Map.class);

    when(productLookup.findById(pharmacy, productId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.addItem(principal, cartId, productId, null, 1, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_NOT_FOUND");

    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "Para",
                    "C",
                    "TABLET",
                    10,
                    1000L,
                    5,
                    false,
                    false,
                    BigDecimal.valueOf(12),
                    null,
                    null)));
    assertThatThrownBy(() -> carts.addItem(principal, cartId, productId, null, 1, true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "Para",
                    "C",
                    "TABLET",
                    10,
                    1000L,
                    5,
                    false,
                    true,
                    BigDecimal.valueOf(5),
                    "H",
                    List.of())));
    when(fefo.selectFefoBatch(pharmacy, productId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.addItem(principal, cartId, productId, null, 1, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_EXPIRED");

    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN", LocalDate.of(2027, 1, 1), 1, 1000L)));
    assertThatThrownBy(() -> carts.addItem(principal, cartId, productId, null, 5, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_STOCK");

    when(fefo.findBatch(pharmacy, productId, batchId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.addItem(principal, cartId, productId, batchId, 1, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_EXPIRED");

    when(fefo.findBatch(pharmacy, productId, batchId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN", LocalDate.of(2027, 1, 1), 10, 1000L)));
    when(cartStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));
    when(cartStore.listItems(cartId)).thenReturn(List.of());
    carts.addItem(principal, cartId, productId, batchId, 1, true);

    when(cartStore.findItem(cartId, itemId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.updateItem(principal, cartId, itemId, 1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(cartStore.findItem(cartId, itemId)).thenReturn(Optional.of(item));
    assertThatThrownBy(() -> carts.updateItem(principal, cartId, itemId, 0, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(productLookup.findById(pharmacy, productId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.updateItem(principal, cartId, itemId, 1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_NOT_FOUND");

    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "Para",
                    "C",
                    "TABLET",
                    10,
                    1000L,
                    5,
                    false,
                    true,
                    BigDecimal.valueOf(12),
                    "H",
                    List.of())));
    when(fefo.findBatch(pharmacy, productId, batchId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.updateItem(principal, cartId, itemId, 1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_EXPIRED");

    when(fefo.findBatch(pharmacy, productId, batchId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN", LocalDate.of(2027, 1, 1), 1, 1000L)));
    assertThatThrownBy(() -> carts.updateItem(principal, cartId, itemId, 9, null, true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_STOCK");

    when(cartStore.findItem(cartId, itemId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> carts.removeItem(principal, cartId, itemId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(productLookup.findByBarcode(pharmacy, "x")).thenReturn(Optional.empty());
    assertThat(carts.search(principal, cartId, "x", "BARCODE").get("results")).asList().isEmpty();

    assertThatThrownBy(() -> carts.search(principal, cartId, "q", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> carts.attachCustomer(principal, cartId, " ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(cartStore.listItems(cartId)).thenReturn(List.of(item));
    assertThatThrownBy(
            () -> carts.applyDiscount(principal, cartId, "FLAT_RS", BigDecimal.valueOf(-1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> carts.applyDiscount(principal, cartId, "BOGUS", BigDecimal.TEN))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    carts.applyDiscount(principal, cartId, "FLAT_RS", BigDecimal.valueOf(5));

    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> carts.createCart(noPharmacy, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    assertThat(MoneyMath.computeDiscountAmountPaise("PERCENTAGE", BigDecimal.TEN, 0)).isZero();
  }

  @Test
  void checkoutRemainingBranches() {
    UUID cartId = UUID.randomUUID();
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "CASH", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_NOT_FOUND");

    when(cartStore.findById(pharmacy, cartId))
        .thenReturn(Optional.of(cart(cartId, PosCartStatus.ACTIVE, NOW.minusSeconds(1), null)));
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "CASH", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_EXPIRED");

    when(cartStore.findById(pharmacy, cartId))
        .thenReturn(Optional.of(cart(cartId, PosCartStatus.ABANDONED, NOW.plusSeconds(10), null)));
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "CASH", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_EXPIRED");

    when(cartStore.findById(pharmacy, cartId))
        .thenReturn(Optional.of(cart(cartId, PosCartStatus.ACTIVE, NOW.plusSeconds(100), null)));
    when(cartStore.listItems(cartId)).thenReturn(List.of());
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "CASH", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EMPTY_CART");

    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    PosCartItem rx =
        PosCartItem.compute(
            UUID.randomUUID(),
            cartId,
            productId,
            "Rx",
            batchId,
            "BN",
            LocalDate.of(2027, 1, 1),
            1,
            false,
            1000L,
            18,
            true,
            1,
            "H",
            NOW);
    when(cartStore.listItems(cartId)).thenReturn(List.of(rx));

    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "NOPE", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> checkout.checkout(principal, cartId, null, BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(9);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> upi =
        checkout.checkout(principal, cartId, "UPI", BigDecimal.valueOf(20), "UPI123", "Dr. X");
    assertThat(upi.get("payment_method")).isEqualTo("UPI");
    assertThat(upi.get("gst_breakdown")).asList().isNotEmpty();

    PosCart withDiscount =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            null,
            null,
            null,
            null,
            DiscountType.PERCENTAGE,
            BigDecimal.valueOf(50),
            0,
            1000,
            0,
            1000,
            PosCartStatus.ACTIVE,
            NOW.plusSeconds(100),
            null,
            null,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(withDiscount));
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    productId,
                    "X",
                    batchId,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    100_000L,
                    12,
                    false,
                    1,
                    null,
                    NOW)));
    Map<String, Object> capped = checkout.checkout(principal, cartId, "CARD", null, null, null);
    assertThat(capped.get("payment_method")).isEqualTo("CARD");

    // blank payment + UPI blank ref + doctor from cart + non-rx checkout
    assertThatThrownBy(() -> checkout.checkout(principal, cartId, "  ", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "UPI", BigDecimal.ONE, "  ", "Dr"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

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
                    "Dr Cart",
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
                    NOW)));
    when(cartStore.listItems(cartId)).thenReturn(List.of(rx));
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(3);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> fromCartDoctor =
        checkout.checkout(principal, cartId, "INSURANCE_TPA", BigDecimal.TEN, null, "  ");
    assertThat(fromCartDoctor.get("payment_method")).isEqualTo("INSURANCE_TPA");

    // blank doctor via empty cart prescribing_doctor
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
                    "",
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    PosCartStatus.ACTIVE,
                    NOW.plusSeconds(100),
                    null,
                    null,
                    NOW,
                    NOW)));
    when(cartStore.listItems(cartId)).thenReturn(List.of(rx));
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RX_PRESCRIBER_REQUIRED");

    // checkout with null discountType/value + rx doctor blank
    when(cartStore.findById(pharmacy, cartId))
        .thenReturn(
            Optional.of(
                new PosCart(
                    cartId,
                    pharmacy,
                    staff,
                    UUID.randomUUID(),
                    "P",
                    "+91",
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    PosCartStatus.ACTIVE,
                    NOW.plusSeconds(100),
                    null,
                    null,
                    NOW,
                    NOW)));
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    productId,
                    "X",
                    batchId,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    1000L,
                    12,
                    true,
                    1,
                    null,
                    NOW)));
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, "   "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RX_PRESCRIBER_REQUIRED");

    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    productId,
                    "X",
                    batchId,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    1000L,
                    12,
                    false,
                    1,
                    null,
                    NOW)));
    Map<String, Object> nullDiscountCheckout =
        checkout.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null);
    assertThat(nullDiscountCheckout.get("payment_method")).isEqualTo("CASH");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(
            () -> checkout.checkout(principal, cartId, "CASH", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void discountedGstUsesProRatedTaxableAndMrpSavings() {
    UUID cartId = UUID.randomUUID();
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(9);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

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
                    DiscountType.PERCENTAGE,
                    BigDecimal.TEN,
                    0,
                    20_000,
                    0,
                    20_000,
                    PosCartStatus.ACTIVE,
                    NOW.plusSeconds(100),
                    null,
                    null,
                    NOW,
                    NOW)));
    when(cartStore.listItems(cartId))
        .thenReturn(List.of(lineItem(cartId, 10_000L, 12), lineItem(cartId, 10_000L, 5)));
    Map<String, Object> discounted =
        checkout.checkout(principal, cartId, "CASH", BigDecimal.valueOf(200), null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> slabs = (List<Map<String, Object>>) discounted.get("gst_breakdown");
    assertThat(slabs).hasSize(2);
    assertThat((BigDecimal) discounted.get("grand_total"))
        .isEqualByComparingTo(new BigDecimal("180.00"));

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
                    null,
                    0,
                    0,
                    0,
                    0,
                    PosCartStatus.ACTIVE,
                    NOW.plusSeconds(100),
                    null,
                    null,
                    NOW,
                    NOW)));
    when(cartStore.listItems(cartId))
        .thenReturn(List.of(lineItem(cartId, 1_000L, 12), lineItem(cartId, 1_000L, 12)));
    Map<String, Object> noDiscount =
        checkout.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null);
    assertThat((BigDecimal) noDiscount.get("grand_total"))
        .isEqualByComparingTo(new BigDecimal("20.00"));

    // four equal lines + 2 paise discount over-allocates HALF_UP remainder to 0
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
                    DiscountType.FLAT_RS,
                    new BigDecimal("0.02"),
                    0,
                    4_000,
                    0,
                    4_000,
                    PosCartStatus.ACTIVE,
                    NOW.plusSeconds(100),
                    null,
                    null,
                    NOW,
                    NOW)));
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                lineItem(cartId, 1_000L, 12),
                lineItem(cartId, 1_000L, 12),
                lineItem(cartId, 1_000L, 12),
                lineItem(cartId, 1_000L, 12)));
    Map<String, Object> remainder =
        checkout.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null);
    assertThat((BigDecimal) remainder.get("grand_total"))
        .isEqualByComparingTo(new BigDecimal("39.98"));
  }

  private static PosCartItem lineItem(UUID cartId, long unitPaise, int gstPct) {
    return PosCartItem.compute(
        UUID.randomUUID(),
        cartId,
        UUID.randomUUID(),
        "X",
        UUID.randomUUID(),
        "BN",
        LocalDate.of(2027, 1, 1),
        1,
        false,
        unitPaise,
        gstPct,
        false,
        1,
        "3004",
        NOW);
  }

  private PosCart cart(UUID id, PosCartStatus status, Instant expires, UUID customerId) {
    return new PosCart(
        id,
        pharmacy,
        staff,
        customerId,
        customerId == null ? null : "Priya",
        customerId == null ? null : "+91",
        null,
        DiscountType.PERCENTAGE,
        BigDecimal.TEN,
        100,
        1000,
        100,
        900,
        status,
        expires,
        null,
        null,
        NOW,
        NOW);
  }
}
