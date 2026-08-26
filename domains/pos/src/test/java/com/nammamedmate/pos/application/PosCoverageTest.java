package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceChannel;
import com.nammamedmate.pos.domain.InvoiceStatus;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
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
class PosCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

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

  PosCartService cartService;
  PosCheckoutService checkoutService;
  UUID pharmacy = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal principal =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.POS, "j");

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(khata.outstandingPaise(any(), any())).thenReturn(0L);
    when(khata.creditLimitPaise(any(), any())).thenReturn(5_000_000L);
    when(plan.growthFeaturesEnabled()).thenReturn(true);
    when(offerService.bestCounterOffer(any(), any(), any(Boolean.class)))
        .thenReturn(Optional.empty());
    cartService =
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
    checkoutService =
        new PosCheckoutService(
            cartStore, invoiceStore, stock, khata, offerStore, rateLimiter, clock);
  }

  @Test
  void createUpdateRemoveSearchBranches() {
    when(cartStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> created = cartService.createCart(principal, null);
    assertThat(created.get("status")).isEqualTo("ACTIVE");

    UUID cartId = UUID.fromString(created.get("cart_id").toString());
    PosCart cart = activeCart(cartId, null);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(cart));

    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    stubProduct(productId);
    when(fefo.findBatch(pharmacy, productId, batchId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN", LocalDate.of(2027, 1, 1), 20, 1500L)));
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN", LocalDate.of(2027, 1, 1), 20, 1500L)));

    PosCartItem item =
        PosCartItem.compute(
            itemId,
            cartId,
            productId,
            "Para",
            batchId,
            "BN",
            LocalDate.of(2027, 1, 1),
            1,
            false,
            1500L,
            12,
            false,
            15,
            null,
            NOW);
    when(cartStore.insertItem(any())).thenReturn(item);
    when(cartStore.listItems(cartId)).thenReturn(List.of(item));
    when(cartStore.findItem(cartId, itemId)).thenReturn(Optional.of(item));
    when(cartStore.updateItem(any())).thenAnswer(inv -> inv.getArgument(0));

    cartService.addItem(principal, cartId, productId, batchId, 1, true);
    cartService.updateItem(principal, cartId, itemId, 2, null, false);
    cartService.removeItem(principal, cartId, itemId);

    when(productLookup.searchByText(pharmacy, "para", 20)).thenReturn(List.of());
    assertThat(cartService.search(principal, cartId, "para", "TEXT").get("results"))
        .asList()
        .isEmpty();

    when(productLookup.searchByRack(pharmacy, "A1-03", 20)).thenReturn(List.of());
    when(productLookup.searchByText(pharmacy, "A1-03", 20)).thenReturn(List.of());
    cartService.search(principal, cartId, "A1-03", "TEXT");

    assertThatThrownBy(() -> cartService.search(principal, cartId, "", "TEXT"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> cartService.search(principal, cartId, "x", "OTHER"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> cartService.addItem(principal, cartId, null, null, 1, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void checkoutCreditCodCompletedExpired() {
    UUID cartId = UUID.randomUUID();
    UUID cust = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    PosCart cart = activeCart(cartId, cust);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(cart));
    PosCartItem item =
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
            "HSN",
            NOW);
    when(cartStore.listItems(cartId)).thenReturn(List.of(item));
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(7);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> credit =
        checkoutService.checkout(principal, cartId, "CREDIT", BigDecimal.TEN, null, null);
    assertThat(credit.get("payment_method")).isEqualTo("CREDIT");
    verify(khata).postCreditSale(eq(cust), any(), any(Long.class), eq(pharmacy));

    Map<String, Object> cod =
        checkoutService.checkout(principal, cartId, "COD", BigDecimal.TEN, null, null);
    assertThat(cod.get("payment_method")).isEqualTo("COD");

    assertThatThrownBy(
            () -> checkoutService.checkout(principal, cartId, "UPI", BigDecimal.TEN, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(cartStore.findById(pharmacy, cartId))
        .thenReturn(
            Optional.of(
                new PosCart(
                    cartId,
                    pharmacy,
                    staff,
                    cust,
                    "A",
                    "+91",
                    null,
                    null,
                    BigDecimal.ZERO,
                    0,
                    0,
                    0,
                    0,
                    PosCartStatus.COMPLETED,
                    NOW.plusSeconds(100),
                    UUID.randomUUID(),
                    null,
                    NOW,
                    NOW)));
    assertThatThrownBy(
            () -> checkoutService.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_ALREADY_COMPLETED");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> cartService.createCart(principal, staff))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void checkoutIdempotencyReplayAndSave() {
    UUID cartId = UUID.randomUUID();
    UUID cust = UUID.randomUUID();
    UUID invoiceId = UUID.randomUUID();
    Invoice invoice =
        new Invoice(
            invoiceId,
            pharmacy,
            "INV-1",
            cartId,
            InvoiceChannel.COUNTER,
            cust,
            "A",
            "+91",
            null,
            1000,
            0,
            120,
            1120,
            PaymentMethod.CASH,
            PaymentStatus.PAID,
            null,
            1120,
            0,
            0,
            InvoiceStatus.ACTIVE,
            "https://cdn.example/x.pdf",
            NOW);
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice));
    when(invoiceStore.listItems(invoiceId)).thenReturn(List.of());

    when(cartStore.findInvoiceByCheckoutIdempotency(pharmacy, "idem-prior"))
        .thenReturn(Optional.of(invoiceId));
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(activeCart(cartId, cust)));
    Map<String, Object> prior =
        checkoutService.checkout(
            principal, cartId, "CASH", BigDecimal.TEN, null, null, "idem-prior");
    assertThat(prior.get("idempotent_replay")).isEqualTo(true);
    assertThat(prior.get("invoice_id")).isEqualTo(invoiceId.toString());

    PosCart completed =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            cust,
            "A",
            "+91",
            null,
            null,
            BigDecimal.ZERO,
            0,
            0,
            0,
            0,
            PosCartStatus.COMPLETED,
            NOW.plusSeconds(100),
            invoiceId,
            null,
            NOW,
            NOW);
    when(cartStore.findInvoiceByCheckoutIdempotency(pharmacy, "idem-completed"))
        .thenReturn(Optional.empty());
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(completed));
    Map<String, Object> replayed =
        checkoutService.checkout(
            principal, cartId, "CASH", BigDecimal.TEN, null, null, "idem-completed");
    assertThat(replayed.get("idempotent_replay")).isEqualTo(true);

    assertThatThrownBy(
            () ->
                checkoutService.checkout(
                    principal, cartId, "CASH", BigDecimal.TEN, null, null, "  "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_ALREADY_COMPLETED");
    assertThatThrownBy(
            () ->
                checkoutService.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null, ""))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_ALREADY_COMPLETED");

    PosCart completedNoInvoice =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            cust,
            "A",
            "+91",
            null,
            null,
            BigDecimal.ZERO,
            0,
            0,
            0,
            0,
            PosCartStatus.COMPLETED,
            NOW.plusSeconds(100),
            null,
            null,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(completedNoInvoice));
    assertThatThrownBy(
            () ->
                checkoutService.checkout(
                    principal, cartId, "CASH", BigDecimal.TEN, null, null, "idem-no-inv"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_ALREADY_COMPLETED");

    when(cartStore.findInvoiceByCheckoutIdempotency(pharmacy, "idem-missing"))
        .thenReturn(Optional.of(invoiceId));
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.empty());
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(activeCart(cartId, cust)));
    assertThatThrownBy(
            () ->
                checkoutService.checkout(
                    principal, cartId, "CASH", BigDecimal.TEN, null, null, "idem-missing"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_NOT_FOUND");

    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    PosCart cart = activeCart(cartId, cust);
    when(cartStore.findInvoiceByCheckoutIdempotency(pharmacy, "idem-new"))
        .thenReturn(Optional.empty());
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(cart));
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
                    "HSN",
                    NOW)));
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(7);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> blankKey =
        checkoutService.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null, "  ");
    assertThat(blankKey.get("invoice_number")).isNotNull();
    Map<String, Object> created =
        checkoutService.checkout(principal, cartId, "CASH", BigDecimal.TEN, null, null, "idem-new");
    assertThat(created.get("invoice_number")).isNotNull();
    verify(cartStore)
        .saveCheckoutIdempotency(eq(pharmacy), eq("idem-new"), eq(cartId), any(), eq(NOW));
  }

  @Test
  void schedulerAndInsufficientStock() {
    PosCartExpiryScheduler scheduler =
        new PosCartExpiryScheduler(cartStore, Clock.fixed(NOW, ZoneOffset.UTC));
    when(cartStore.abandonExpired(NOW)).thenReturn(3);
    scheduler.abandonExpiredCarts();
    verify(cartStore).abandonExpired(NOW);

    UUID cartId = UUID.randomUUID();
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(activeCart(cartId, null)));
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    UUID.randomUUID(),
                    "X",
                    UUID.randomUUID(),
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    100L,
                    5,
                    false,
                    1,
                    null,
                    NOW)));
    org.mockito.Mockito.doThrow(new AppException("INSUFFICIENT_STOCK", "race", 400))
        .when(stock)
        .deductSale(any(), any(), any(), anyInt(), any(), any());
    assertThatThrownBy(
            () -> checkoutService.checkout(principal, cartId, "CASH", BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_STOCK");
  }

  @Test
  void applyDiscountSuccessAndCompletedCart() {
    UUID cartId = UUID.randomUUID();
    PosCart cart =
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
            10_000,
            0,
            10_000,
            PosCartStatus.ACTIVE,
            NOW.plusSeconds(1000),
            null,
            null,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(cart));
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    UUID.randomUUID(),
                    "X",
                    UUID.randomUUID(),
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    10_000L,
                    12,
                    false,
                    1,
                    null,
                    NOW)));
    Map<String, Object> disc =
        cartService.applyDiscount(principal, cartId, "PERCENTAGE", BigDecimal.valueOf(10));
    assertThat(disc.get("discount_type")).isEqualTo("PERCENTAGE");

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
                    PosCartStatus.COMPLETED,
                    NOW.plusSeconds(1000),
                    UUID.randomUUID(),
                    null,
                    NOW,
                    NOW)));
    assertThatThrownBy(() -> cartService.clearCart(principal, cartId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_COMPLETED");
  }

  private PosCart activeCart(UUID cartId, UUID customerId) {
    return new PosCart(
        cartId,
        pharmacy,
        staff,
        customerId,
        customerId == null ? null : "Priya",
        customerId == null ? null : "+9198",
        null,
        null,
        BigDecimal.ZERO,
        0,
        0,
        0,
        0,
        PosCartStatus.ACTIVE,
        NOW.plusSeconds(7200),
        null,
        null,
        NOW,
        NOW);
  }

  private void stubProduct(UUID productId) {
    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "Para",
                    "Cipla",
                    "TABLET",
                    10,
                    1500L,
                    50,
                    false,
                    true,
                    BigDecimal.valueOf(12),
                    "HSN",
                    List.of())));
  }
}
