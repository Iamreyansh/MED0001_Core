package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PosCartServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock private PosCartStore cartStore;
  @Mock private ProductLookupPort productLookup;
  @Mock private PosFefoPort fefo;
  @Mock private PosCustomerPort customers;
  @Mock private PosKhataPort khata;
  @Mock private InvoiceStore invoiceStore;
  @Mock private StockDeductionPort stockDeduction;
  @Mock private OfferService offerService;
  @Mock private OfferStore offerStore;
  @Mock private PosPlanPort plan;

  private PosCartService cartService;
  private PosCheckoutService checkoutService;
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID staff = UUID.randomUUID();
  private final MedmatePrincipal principal =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j");

  private final List<PosCartItem> items = new ArrayList<>();
  private final AtomicReference<PosCart> cartRef = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    InMemoryRateLimiter rl = new InMemoryRateLimiter(clock);
    when(plan.growthFeaturesEnabled()).thenReturn(true);
    when(offerService.bestCounterOffer(any(), any(), any(Boolean.class)))
        .thenReturn(Optional.empty());
    cartService =
        new PosCartService(
            cartStore, productLookup, fefo, customers, khata, offerService, plan, rl, clock);
    checkoutService =
        new PosCheckoutService(
            cartStore, invoiceStore, stockDeduction, khata, offerStore, rl, clock);

    when(cartStore.insert(any()))
        .thenAnswer(
            inv -> {
              PosCart c = inv.getArgument(0);
              cartRef.set(c);
              return c;
            });
    when(cartStore.findById(eq(pharmacy), any()))
        .thenAnswer(inv -> Optional.ofNullable(cartRef.get()));
    when(cartStore.listItems(any())).thenAnswer(inv -> List.copyOf(items));
    when(cartStore.insertItem(any()))
        .thenAnswer(
            inv -> {
              PosCartItem item = inv.getArgument(0);
              items.add(item);
              return item;
            });
    when(cartStore.findItem(any(), any()))
        .thenAnswer(
            inv -> items.stream().filter(i -> i.id().equals(inv.getArgument(1))).findFirst());
    doAnswer(
            inv -> {
              items.removeIf(i -> i.id().equals(inv.getArgument(1)));
              return null;
            })
        .when(cartStore)
        .deleteItem(any(), any());
    when(cartStore.deleteAllItems(any()))
        .thenAnswer(
            inv -> {
              int n = items.size();
              items.clear();
              return n;
            });
    when(khata.outstandingPaise(any(), any())).thenReturn(50_000L);
    when(khata.creditLimitPaise(any(), any())).thenReturn(Long.MAX_VALUE / 2);
  }

  @Test
  void ac01_fefoBatchAutoSelectedWhenBatchIdOmitted() {
    UUID productId = UUID.randomUUID();
    UUID earlyBatch = UUID.randomUUID();
    UUID lateBatch = UUID.randomUUID();
    seedActiveCart();
    stubProduct(productId, false);
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    earlyBatch, productId, "BN-EARLY", LocalDate.of(2027, 1, 1), 100, 2250L)));
    when(fefo.findBatch(any(), any(), eq(lateBatch)))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    lateBatch, productId, "BN-LATE", LocalDate.of(2028, 1, 1), 100, 2250L)));

    Map<String, Object> added =
        cartService.addItem(principal, cartRef.get().id(), productId, null, 2, false);

    assertThat(added.get("batch_number")).isEqualTo("BN-EARLY");
    verify(fefo).selectFefoBatch(pharmacy, productId);
    verify(fefo, never()).findBatch(any(), any(), eq(lateBatch));
  }

  @Test
  void ac02_rxRequiresPrescriberOnCheckout() {
    UUID productId = UUID.randomUUID();
    seedActiveCart();
    stubProduct(productId, true);
    stubFefo(productId);
    cartService.addItem(principal, cartRef.get().id(), productId, null, 1, false);

    assertThatThrownBy(
            () ->
                checkoutService.checkout(
                    principal, cartRef.get().id(), "CASH", BigDecimal.valueOf(100), null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_PRESCRIBER_REQUIRED");
  }

  @Test
  void ac03_creditRequiresNamedCustomer() {
    UUID productId = UUID.randomUUID();
    seedActiveCart();
    stubProduct(productId, false);
    stubFefo(productId);
    cartService.addItem(principal, cartRef.get().id(), productId, null, 1, false);

    assertThatThrownBy(
            () ->
                checkoutService.checkout(
                    principal, cartRef.get().id(), "CREDIT", BigDecimal.valueOf(100), null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CREDIT_REQUIRES_NAMED_CUSTOMER");
  }

  @Test
  void ac04_discountExceedsThirtyPercent() {
    UUID productId = UUID.randomUUID();
    seedActiveCart();
    stubProduct(productId, false);
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    UUID.randomUUID(),
                    productId,
                    "BN",
                    LocalDate.of(2027, 6, 30),
                    1000,
                    100_000L))); // ₹1000 unit → subtotal 1000 for qty 1

    cartService.addItem(principal, cartRef.get().id(), productId, null, 1, false);

    assertThatThrownBy(
            () ->
                cartService.applyDiscount(
                    principal, cartRef.get().id(), "PERCENTAGE", BigDecimal.valueOf(35)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DISCOUNT_EXCEEDS_LIMIT");
  }

  @Test
  void ac05_checkoutDeductsStockAtomically() {
    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    seedActiveCart();
    stubProduct(productId, false);
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN", LocalDate.of(2027, 6, 30), 50, 4500L)));
    cartService.addItem(principal, cartRef.get().id(), productId, null, 2, false);

    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(eq(pharmacy), anyInt(), anyInt())).thenReturn(42);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> result =
        checkoutService.checkout(
            principal, cartRef.get().id(), "CASH", BigDecimal.valueOf(100), null, null);

    verify(stockDeduction)
        .deductSale(eq(pharmacy), eq(productId), eq(batchId), eq(2), eq(staff), eq(NOW));
    verify(cartStore).markCompleted(eq(cartRef.get().id()), any(), eq(NOW));
    assertThat(result.get("invoice_number")).isEqualTo("INV-2026-07-000042");
  }

  @Test
  void ac06_expiredCartReturns410() {
    PosCart expired =
        new PosCart(
            UUID.randomUUID(),
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
            NOW.minusSeconds(10),
            null,
            null,
            NOW.minusSeconds(7200),
            NOW.minusSeconds(7200));
    cartRef.set(expired);

    assertThatThrownBy(() -> cartService.getCart(principal, expired.id()))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("CART_EXPIRED");
              assertThat(ae.httpStatus()).isEqualTo(410);
            });
    ArgumentCaptor<PosCart> cap = ArgumentCaptor.forClass(PosCart.class);
    verify(cartStore).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(PosCartStatus.ABANDONED);
  }

  @Test
  void ac07_rackCodeSearch() {
    seedActiveCart();
    UUID productId = UUID.randomUUID();
    var product =
        new ProductLookupPort.ProductSnapshot(
            productId,
            "Para",
            "Cipla",
            "TABLET",
            15,
            2250L,
            100,
            false,
            false,
            BigDecimal.valueOf(12),
            "30049099",
            List.of("A1-03"));
    when(productLookup.searchByRack(pharmacy, "A1-03", 20))
        .thenReturn(
            List.of(
                new ProductLookupPort.SearchHit(
                    product,
                    List.of(
                        new ProductLookupPort.BatchOption(
                            UUID.randomUUID(), "BN", LocalDate.of(2027, 1, 1), 10, true)),
                    false)));

    Map<String, Object> data = cartService.search(principal, cartRef.get().id(), "A1-03", "TEXT");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().get("name")).isEqualTo("Para");
    verify(productLookup).searchByRack(pharmacy, "A1-03", 20);
  }

  @Test
  void ac08_cashChangeDue() {
    UUID productId = UUID.randomUUID();
    seedActiveCart();
    stubProduct(productId, false);
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    UUID.randomUUID(),
                    productId,
                    "BN",
                    LocalDate.of(2027, 6, 30),
                    50,
                    45_000L))); // ₹450
    cartService.addItem(principal, cartRef.get().id(), productId, null, 1, false);

    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoiceStore.nextSequence(any(), anyInt(), anyInt())).thenReturn(1);
    when(invoiceStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> result =
        checkoutService.checkout(
            principal, cartRef.get().id(), "CASH", BigDecimal.valueOf(500), null, null);

    assertThat((BigDecimal) result.get("grand_total"))
        .isEqualByComparingTo(BigDecimal.valueOf(450).setScale(2));
    assertThat((BigDecimal) result.get("change_due"))
        .isEqualByComparingTo(BigDecimal.valueOf(50).setScale(2));
  }

  @Test
  void barcodeSearchAutoAddAndAuthBranches() {
    seedActiveCart();
    UUID productId = UUID.randomUUID();
    var product =
        new ProductLookupPort.ProductSnapshot(
            productId,
            "Para",
            "Cipla",
            "TABLET",
            15,
            2250L,
            10,
            false,
            false,
            BigDecimal.valueOf(12),
            null,
            List.of());
    when(productLookup.findByBarcode(pharmacy, "890123")).thenReturn(Optional.of(product));
    when(fefo.listEligibleBatches(pharmacy, productId))
        .thenReturn(
            List.of(
                new PosFefoPort.BatchSnapshot(
                    UUID.randomUUID(), productId, "BN", LocalDate.of(2027, 1, 1), 5, 2250L)));

    Map<String, Object> data =
        cartService.search(principal, cartRef.get().id(), "890123", "BARCODE");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
    assertThat(results.getFirst().get("auto_add")).isEqualTo(true);

    assertThatThrownBy(() -> cartService.createCart(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "c");
    assertThatThrownBy(() -> cartService.createCart(customer, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void attachCustomerUpdateClearDiscountEmpty() {
    seedActiveCart();
    UUID cust = UUID.randomUUID();
    when(customers.findOrCreate("+919876000001", "Priya"))
        .thenReturn(new PosCustomerPort.CustomerRef(cust, "Priya", "+919876000001", false));

    Map<String, Object> attached =
        cartService.attachCustomer(principal, cartRef.get().id(), "+919876000001", "Priya");
    assertThat(attached.get("is_new_customer")).isEqualTo(false);
    assertThat((BigDecimal) attached.get("outstanding_khata"))
        .isEqualByComparingTo(BigDecimal.valueOf(500).setScale(2));
    verify(khata).ensureCustomerKnown(pharmacy, cust);

    assertThatThrownBy(
            () ->
                cartService.applyDiscount(principal, cartRef.get().id(), "FLAT_RS", BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_CART");

    Map<String, Object> cleared = cartService.clearCart(principal, cartRef.get().id());
    assertThat(cleared.get("items_removed")).isEqualTo(0);
  }

  private void seedActiveCart() {
    items.clear();
    PosCart cart =
        new PosCart(
            UUID.randomUUID(),
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
            NOW.plusSeconds(7200),
            null,
            null,
            NOW,
            NOW);
    cartRef.set(cart);
    when(cartStore.update(any()))
        .thenAnswer(
            inv -> {
              cartRef.set(inv.getArgument(0));
              return inv.getArgument(0);
            });
  }

  private void stubProduct(UUID productId, boolean rx) {
    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "Paracetamol 500mg Tab",
                    "Cipla",
                    "TABLET",
                    15,
                    2250L,
                    450,
                    rx,
                    true,
                    BigDecimal.valueOf(12),
                    "30049099",
                    List.of("A1-03"))));
  }

  private void stubFefo(UUID productId) {
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    UUID.randomUUID(),
                    productId,
                    "BN25100",
                    LocalDate.of(2027, 6, 30),
                    300,
                    2250L)));
  }
}
