package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.order.adapter.out.persistence.StubDeliveryFeeAdapter;
import com.nammamedmate.order.application.port.out.CartStore;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.MedicineDetails;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.PlatformCouponPort;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.WalletBalancePort;
import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartPricing;
import com.nammamedmate.order.domain.CartStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
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
class CartServiceGapsTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID MED = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID MED2 = UUID.fromString("22222222-2222-4222-8222-222222222223");
  private static final UUID ADDR = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Mock private CartStore carts;
  @Mock private SmartPharmacySelectionService smartSelect;
  @Mock private InventoryAvailabilityPort inventory;
  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private CustomerAddressPort addresses;
  @Mock private WalletBalancePort wallet;
  @Mock private PrescriptionPort prescriptions;
  @Mock private ZoneMembershipPort zones;
  @Mock private RateLimiter rateLimiter;

  private CartService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(wallet.balancePaise(CUST)).thenReturn(0L);
    when(zones.isInPharmacyZone(any(), anyDouble(), anyDouble())).thenReturn(true);
    service =
        new CartService(
            carts,
            smartSelect,
            inventory,
            pharmacies,
            addresses,
            wallet,
            prescriptions,
            zones,
            new StubDeliveryFeeAdapter(),
            (code, total) -> {
              var applied = CartPricing.applyCoupon(code, total);
              return new PlatformCouponPort.Quote(
                  applied.code(),
                  applied.type(),
                  applied.discountPaise(),
                  applied.type() == CartPricing.CouponType.FREE_DELIVERY,
                  applied.message());
            },
            rateLimiter,
            clock);
  }

  @Test
  void coversValidationMismatchStockEtaPharmacyStubAndCoordsFromCartAddress() {
    Instant now = clock.instant();
    Cart cart = Cart.empty(CUST, now);
    cart.setPharmacyId(PH1);
    cart.setDeliveryAddressId(ADDR);
    CartItem item =
        new CartItem(UUID.randomUUID(), MED, 1, 8500, true, "M", "B", "10", "http://img");
    cart.addOrMerge(item);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(cart));
    when(carts.update(any())).thenAnswer(inv -> inv.getArgument(0));
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "full", 12.9, 77.6)));
    when(inventory.findMedicine(MED2))
        .thenReturn(Optional.of(new MedicineDetails(MED2, "P", "G", "1", false, null, false)));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED2))))
        .thenReturn(List.of(new StockLine(MED2, "P", 0, 0, 0, false, "NOT_FOUND")));
    assertThatThrownBy(() -> service.addItem(customer, MED2, 1, false, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED2))))
        .thenReturn(List.of(new StockLine(MED2, "P", 0, 0, 0, false, "BANNED")));
    assertThatThrownBy(() -> service.addItem(customer, MED2, 1, false, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 1, 8500, 9000, true, null)));
    when(inventory.findMedicine(MED))
        .thenReturn(
            Optional.of(new MedicineDetails(MED, "M", "B", "10", true, "http://img", false)));
    assertThatThrownBy(() -> service.addItem(customer, MED, 5, false, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OUT_OF_STOCK");

    // successful add same product (merge) with enough stock
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 50, 8500, 9000, true, null)));
    Map<String, Object> view = service.addItem(customer, MED, 1, false, null, null);
    assertThat(view.get("pharmacy")).isInstanceOf(Map.class);

    assertThatThrownBy(() -> service.addItem(customer, MED, null, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.addItem(customer, MED, 0, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.updateItemQuantity(customer, null, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.updateItemQuantity(customer, item.itemId(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.updateItemQuantity(customer, UUID.randomUUID(), 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 0, 8500, 9000, false, "OUT_OF_STOCK")));
    assertThatThrownBy(() -> service.updateItemQuantity(customer, item.itemId(), 3))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OUT_OF_STOCK");

    // remove non-last item path
    CartItem other = new CartItem(UUID.randomUUID(), MED2, 1, 1000, false, "P", "G", "1", null);
    cart.addOrMerge(other);
    when(inventory.findMedicine(MED2))
        .thenReturn(Optional.of(new MedicineDetails(MED2, "P", "G", "1", false, null, false)));
    service.removeItem(customer, other.itemId());
    assertThatThrownBy(() -> service.removeItem(customer, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.attachPrescription(customer, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(prescriptions.findVerified(any(), eq(CUST))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.attachPrescription(customer, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.setAddress(customer, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.switchPharmacy(customer, null, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.switchPharmacy(customer, PH1, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // smart-select returns string id; coords from cart delivery address
    Cart empty = Cart.empty(CUST, now);
    empty.setDeliveryAddressId(ADDR);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(empty));
    Map<String, Object> selected = new LinkedHashMap<>();
    selected.put("id", PH1.toString());
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble()))
        .thenReturn(Map.of("available", true, "selected_pharmacy", selected));
    when(inventory.findMedicine(MED))
        .thenReturn(Optional.of(new MedicineDetails(MED, "M", "B", "10", true, null, false)));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 10, 8500, 9000, true, null)));
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "Kora", "a", null, null, 12.935, 77.613, true, false, "ACTIVE", 4.6,
                    1, 90, 10.0)));
    when(prescriptions.findVerified(any(), eq(CUST)))
        .thenReturn(
            Optional.of(new PrescriptionPort.PrescriptionRef(UUID.randomUUID(), "VERIFIED")));
    empty.setPrescriptionId(UUID.randomUUID());
    Map<String, Object> added = service.addItem(customer, MED, 1, false, null, null);
    assertThat(added.get("pharmacy")).isNotNull();

    // first-add stock miss after smart-select
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED)))).thenReturn(List.of());
    Cart empty2 = Cart.empty(CUST, now);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(empty2));
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OUT_OF_STOCK");

    // abandoned cart cannot update
    Cart abandoned = Cart.empty(CUST, now);
    abandoned.setStatus(CartStatus.ABANDONED);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(abandoned));
    assertThatThrownBy(() -> service.clearCart(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(any(), anyInt(), anyInt())).thenReturn(12);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(cart));
    assertThatThrownBy(() -> service.getCart(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    // empty cart with null pharmacy → first-add path; switch without existing pharmacy
    Cart bare = Cart.empty(CUST, now);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(bare));
    Map<String, Object> sel = new LinkedHashMap<>();
    sel.put("id", PH1);
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble()))
        .thenReturn(Map.of("available", true, "selected_pharmacy", sel));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 10, 8500, 9000, true, null)));
    service.addItem(customer, MED, 1, true, 12.9, 77.6);

    // quantity < 0; inStock true but qty short on update; set address with null pharmacy
    Cart noPh = Cart.empty(CUST, now);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(noPh));
    assertThatThrownBy(() -> service.updateItemQuantity(customer, UUID.randomUUID(), -1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "full", 12.9, 77.6)));
    service.setAddress(customer, ADDR);

    // re-apply same coupon; eta via default address; pharmacy geo null
    Cart priced = Cart.empty(CUST, now);
    priced.setPharmacyId(PH1);
    priced.addOrMerge(new CartItem(UUID.randomUUID(), MED, 1, 40_000, false, "M", "B", "1", null));
    priced.setCoupon("FLAT50", 5000);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(priced));
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "Kora", "a", null, null, null, null, true, false, "ACTIVE", 4.6, 1,
                    90, null)));
    when(addresses.findDefault(CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "full", 12.9, 77.6)));
    service.applyCoupon(customer, "FLAT50");
    assertThat(service.getCart(customer).get("pharmacy")).isNotNull();

    // delivery address id set but missing row → fall through to default for eta
    priced.setDeliveryAddressId(UUID.randomUUID());
    when(addresses.findForCustomer(any(), eq(CUST))).thenReturn(Optional.empty());
    service.getCart(customer);

    // available=true but selected_pharmacy null; one of lat/lng null → requireCoords
    Cart empty3 = Cart.empty(CUST, now);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(empty3));
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("available", true);
    bad.put("selected_pharmacy", null);
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble())).thenReturn(bad);
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_PHARMACY_AVAILABLE");

    assertThatThrownBy(() -> CartService.requireCustomer(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    // !inStock without OUT_OF_STOCK reason string
    Cart locked = Cart.empty(CUST, now);
    locked.setPharmacyId(PH1);
    locked.addOrMerge(new CartItem(UUID.randomUUID(), MED, 1, 100, false, "M", "B", "1", null));
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(locked));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED2))))
        .thenReturn(List.of(new StockLine(MED2, "P", 0, 0, 0, false, null)));
    when(inventory.findMedicine(MED2))
        .thenReturn(Optional.of(new MedicineDetails(MED2, "P", "G", "1", false, null, false)));
    assertThatThrownBy(() -> service.addItem(customer, MED2, 1, false, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OUT_OF_STOCK");

    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 2, 100, 100, true, null)));
    assertThatThrownBy(
            () -> service.updateItemQuantity(customer, locked.items().getFirst().itemId(), 5))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OUT_OF_STOCK");

    // coords: cart address id set but lookup empty → default
    Cart empty4 = Cart.empty(CUST, now);
    empty4.setDeliveryAddressId(ADDR);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(empty4));
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    when(addresses.findDefault(CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "full", 12.9345, 77.6125)));
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble()))
        .thenReturn(Map.of("available", true, "selected_pharmacy", sel));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 10, 8500, 9000, true, null)));
    when(inventory.findMedicine(MED))
        .thenReturn(Optional.of(new MedicineDetails(MED, "M", "B", "10", true, null, false)));
    service.addItem(customer, MED, 1, false, null, null);

    // non-empty cart but pharmacy_id null → first-add path via pharmacyId==null branch
    Cart orphan = Cart.empty(CUST, now);
    orphan.addOrMerge(new CartItem(UUID.randomUUID(), MED2, 1, 100, false, "P", "G", "1", null));
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(orphan));
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble()))
        .thenReturn(Map.of("available", true, "selected_pharmacy", sel));
    when(inventory.findMedicine(MED))
        .thenReturn(Optional.of(new MedicineDetails(MED, "M", "B", "10", true, null, false)));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 10, 8500, 9000, true, null)));
    service.addItem(customer, MED, 1, false, 12.9, 77.6);

    // apply coupon when none applied yet (existing == null branch of COUPON_ALREADY check)
    Cart bareCoupon = Cart.empty(CUST, now);
    bareCoupon.setPharmacyId(PH1);
    bareCoupon.addOrMerge(
        new CartItem(UUID.randomUUID(), MED, 1, 40_000, false, "M", "B", "1", null));
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(bareCoupon));
    service.applyCoupon(customer, "NAMMA25");
    // next == null while existing set → skip already-applied guard, then INVALID_COUPON
    assertThatThrownBy(() -> service.applyCoupon(customer, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COUPON");
    assertThatThrownBy(() -> service.applyCoupon(customer, "   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COUPON");

    // lat without lng → skip coords pair; no address → VALIDATION_ERROR
    Cart empty5 = Cart.empty(CUST, now);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(empty5));
    when(addresses.findDefault(CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, 12.9, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // addStockedItem: in stock but quantity short after smart-select
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble()))
        .thenReturn(Map.of("available", true, "selected_pharmacy", sel));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 1, 8500, 9000, true, null)));
    assertThatThrownBy(() -> service.addItem(customer, MED, 5, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OUT_OF_STOCK");

    // eta: lat present, pharmacy longitude null
    Cart etaCart = Cart.empty(CUST, now);
    etaCart.setPharmacyId(PH1);
    etaCart.setDeliveryAddressId(ADDR);
    etaCart.addOrMerge(new CartItem(UUID.randomUUID(), MED, 1, 100, false, "M", "B", "1", null));
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(etaCart));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "full", 12.9, 77.6)));
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "Kora", "a", null, null, 12.935, null, true, false, "ACTIVE", 4.6,
                    1, 90, 10.0)));
    assertThat(service.getCart(customer).get("pharmacy")).isNotNull();
  }

  @Test
  void createActiveFromQuoteAbandonsPriorCart() {
    Instant now = clock.instant();
    Cart prior = Cart.empty(CUST, now);
    prior.setPharmacyId(PH1);
    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.of(prior));
    when(carts.update(any())).thenAnswer(inv -> inv.getArgument(0));
    when(carts.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    UUID rxId = UUID.randomUUID();
    List<CartItem> items =
        List.of(new CartItem(UUID.randomUUID(), MED, 2, 8500, true, "M", null, null, null));
    Cart created = service.createActiveFromQuote(CUST, PH1, ADDR, rxId, items);
    assertThat(created.status()).isEqualTo(CartStatus.ACTIVE);
    assertThat(created.pharmacyId()).isEqualTo(PH1);
    assertThat(created.prescriptionId()).isEqualTo(rxId);
    assertThat(created.deliveryAddressId()).isEqualTo(ADDR);
    assertThat(created.items()).hasSize(1);
    assertThat(created.items().getFirst().unitPricePaise()).isEqualTo(8500);
    assertThat(created.items().getFirst().productId()).isEqualTo(MED);
    assertThat(created.items().getFirst().quantity()).isEqualTo(2);
    assertThat(prior.status()).isEqualTo(CartStatus.ABANDONED);

    when(carts.findActiveByCustomer(CUST)).thenReturn(Optional.empty());
    Cart emptyItems = service.createActiveFromQuote(CUST, PH1, ADDR, null, null);
    assertThat(emptyItems.items()).isEmpty();
    assertThat(emptyItems.prescriptionId()).isNull();
  }
}
