package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
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
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.WalletBalancePort;
import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000002");
  private static final UUID MED = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID MED2 = UUID.fromString("22222222-2222-4222-8222-222222222223");
  private static final UUID ADDR = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Mock private SmartPharmacySelectionService smartSelect;
  @Mock private InventoryAvailabilityPort inventory;
  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private CustomerAddressPort addresses;
  @Mock private WalletBalancePort wallet;
  @Mock private PrescriptionPort prescriptions;
  @Mock private ZoneMembershipPort zones;
  @Mock private RateLimiter rateLimiter;

  private InMemoryCartStore cartStore;
  private CartService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    cartStore = new InMemoryCartStore();
    when(rateLimiter.tryAcquire(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    service =
        new CartService(
            cartStore,
            smartSelect,
            inventory,
            pharmacies,
            addresses,
            wallet,
            prescriptions,
            zones,
            new StubDeliveryFeeAdapter(),
            rateLimiter,
            clock);
    when(wallet.balancePaise(CUST)).thenReturn(0L);
    when(zones.isInPharmacyZone(any(), anyDouble(), anyDouble())).thenReturn(true);
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(pharmacy(PH1, "Sai Medicals")));
    when(pharmacies.findById(PH2)).thenReturn(Optional.of(pharmacy(PH2, "Apollo")));
    when(inventory.findMedicine(MED))
        .thenReturn(
            Optional.of(
                new MedicineDetails(MED, "Metformin", "USV", "10 tablets", true, null, false)));
    when(inventory.findMedicine(MED2))
        .thenReturn(
            Optional.of(
                new MedicineDetails(MED2, "Paracetamol", "GSK", "15 tab", false, null, false)));
  }

  @Test
  void ac1_firstAddRunsSmartSelectAndLocksPharmacy() {
    stubSmartSelect(PH1);
    stubStock(PH1, MED, 8500, 100);
    Map<String, Object> view = service.addItem(customer, MED, 3, false, 12.9345, 77.6125);
    assertThat(view.get("pharmacy")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> ph = (Map<String, Object>) view.get("pharmacy");
    assertThat(ph.get("id")).isEqualTo(PH1);
    verify(smartSelect).smartSelect(customer, MED, 12.9345, 77.6125);
  }

  @Test
  void ac2_pharmacyMismatchWithoutSwitch() {
    Cart cart = seededCartWithItem(PH1, MED, 1, 8500);
    cartStore.insert(cart);
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED2))))
        .thenReturn(List.of(new StockLine(MED2, "P", 0, 0, 0, false, "NOT_MAPPED")));
    assertThatThrownBy(() -> service.addItem(customer, MED2, 1, false, 12.9, 77.6))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_MISMATCH");
  }

  @Test
  void ac3_flat50MinNotMet() {
    Cart cart = seededCartWithItem(PH1, MED, 1, 35_000);
    cartStore.insert(cart);
    assertThatThrownBy(() -> service.applyCoupon(customer, "FLAT50"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COUPON_MIN_NOT_MET");
  }

  @Test
  void ac4_namma25Bill() {
    Cart cart = seededCartWithItem(PH1, MED, 3, 8500);
    cartStore.insert(cart);
    service.applyCoupon(customer, "NAMMA25");
    Map<String, Object> view = service.getCart(customer);
    assertThat((BigDecimal) view.get("coupon_discount"))
        .isEqualByComparingTo(new BigDecimal("63.75"));
    @SuppressWarnings("unchecked")
    Map<String, Object> bill = (Map<String, Object>) view.get("bill");
    // pre-coupon 255 >= 199 → free delivery (BR-3); discounted subtotal is irrelevant
    assertThat((BigDecimal) bill.get("delivery_fee")).isEqualByComparingTo(new BigDecimal("0.00"));
    assertThat((BigDecimal) bill.get("coupon_discount"))
        .isEqualByComparingTo(new BigDecimal("63.75"));
  }

  @Test
  void ac5_lastItemClearsPharmacyCouponPrescription() {
    Cart cart = seededCartWithItem(PH1, MED, 1, 8500);
    cart.setCoupon("NAMMA25", 100);
    cart.setPrescriptionId(UUID.randomUUID());
    cartStore.insert(cart);
    UUID itemId = cart.items().getFirst().itemId();
    Map<String, Object> view = service.updateItemQuantity(customer, itemId, 0);
    assertThat(view.get("pharmacy")).isNull();
    assertThat(view.get("coupon_applied")).isNull();
    assertThat(view.get("prescription_id")).isNull();
  }

  @Test
  void ac6_checkoutBlockedWithoutPrescription() {
    Cart cart = seededCartWithItem(PH1, MED, 1, 8500);
    assertThatThrownBy(() -> service.assertCheckoutReady(cart))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_REQUIRED");
    cart.setPrescriptionId(UUID.randomUUID());
    service.assertCheckoutReady(cart);
  }

  @Test
  void ac7_abandonStalePreventsUpdate() {
    Cart cart = seededCartWithItem(PH1, MED, 1, 8500);
    cartStore.insert(cart);
    cartStore.forceUpdatedAt(cart.id(), Instant.parse("2026-08-06T12:00:00Z"));
    assertThat(service.abandonStaleCarts()).isEqualTo(1);
    assertThat(cartStore.findById(cart.id()).orElseThrow().status())
        .isEqualTo(CartStatus.ABANDONED);
    // new active cart created on get
    Map<String, Object> view = service.getCart(customer);
    assertThat(view.get("status")).isEqualTo("ACTIVE");
    assertThat(view.get("cart_id")).isNotEqualTo(cart.id());
  }

  @Test
  void ac8_freedelDeliveryZero() {
    Cart cart = seededCartWithItem(PH1, MED, 1, 15_000);
    cartStore.insert(cart);
    service.applyCoupon(customer, "FREEDEL");
    @SuppressWarnings("unchecked")
    Map<String, Object> bill = (Map<String, Object>) service.getCart(customer).get("bill");
    assertThat((BigDecimal) bill.get("delivery_fee")).isEqualByComparingTo(new BigDecimal("0.00"));
  }

  @Test
  void switchPharmacyFlagAndClearAndCouponAndAddressAndRx() {
    stubSmartSelect(PH2);
    stubStock(PH2, MED2, 1200, 50);
    Cart cart = seededCartWithItem(PH1, MED, 1, 8500);
    cartStore.insert(cart);
    when(inventory.findMedicine(MED2))
        .thenReturn(
            Optional.of(
                new MedicineDetails(MED2, "Paracetamol", "GSK", "15 tab", false, null, false)));
    Map<String, Object> switched = service.addItem(customer, MED2, 1, true, 12.9, 77.6);
    @SuppressWarnings("unchecked")
    Map<String, Object> ph = (Map<String, Object>) switched.get("pharmacy");
    assertThat(ph.get("id")).isEqualTo(PH2);

    service.clearCart(customer);
    assertThat(service.getCart(customer).get("pharmacy")).isNull();

    // re-seed for coupon/address
    cartStore.clear();
    Cart again = seededCartWithItem(PH1, MED, 1, 40_000);
    cartStore.insert(again);
    Map<String, Object> coupon = service.applyCoupon(customer, "FLAT50");
    assertThat(coupon.get("discount_type")).isEqualTo("FLAT");
    assertThatThrownBy(() -> service.applyCoupon(customer, "NAMMA25"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COUPON_ALREADY_APPLIED");
    service.removeCoupon(customer);

    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(
            Optional.of(new AddressRow(ADDR, CUST, "Home", "42 Koramangala", 12.9345, 77.6125)));
    Map<String, Object> withAddr = service.setAddress(customer, ADDR);
    assertThat(withAddr.get("delivery_address")).isNotNull();

    UUID rx = UUID.randomUUID();
    when(prescriptions.findVerified(rx, CUST))
        .thenReturn(Optional.of(new PrescriptionPort.PrescriptionRef(rx, "VERIFIED")));
    assertThat(service.attachPrescription(customer, rx).get("prescription_id")).isEqualTo(rx);
    assertThat(service.removePrescription(customer).get("prescription_id")).isNull();

    Map<String, Object> sw = service.switchPharmacy(customer, PH2, true);
    assertThat(sw.get("message").toString()).contains("Apollo");
  }

  @Test
  void errorsAndHelpers() {
    assertThatThrownBy(() -> service.addItem(customer, null, 1, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(inventory.findMedicine(MED)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(inventory.findMedicine(MED))
        .thenReturn(Optional.of(new MedicineDetails(MED, "X", "Y", "1", false, null, true)));
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(inventory.findMedicine(MED))
        .thenReturn(
            Optional.of(
                new MedicineDetails(MED, "Metformin", "USV", "10 tablets", true, null, false)));
    Map<String, Object> unavailable = new LinkedHashMap<>();
    unavailable.put("available", false);
    unavailable.put("selected_pharmacy", null);
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble())).thenReturn(unavailable);
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_PHARMACY_AVAILABLE");

    cartStore.clear();
    Cart cart = seededCartWithItem(PH1, MED, 1, 8500);
    cartStore.insert(cart);
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED))))
        .thenReturn(List.of(new StockLine(MED, "M", 0, 8500, 9000, false, "OUT_OF_STOCK")));
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, 12.9, 77.6))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("OUT_OF_STOCK");

    assertThatThrownBy(() -> service.setAddress(customer, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADDRESS_NOT_FOUND");
    when(zones.isInPharmacyZone(eq(PH1), anyDouble(), anyDouble())).thenReturn(false);
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.9, 77.6)));
    assertThatThrownBy(() -> service.setAddress(customer, ADDR))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ADDRESS_NOT_SERVICEABLE");

    assertThatThrownBy(() -> service.switchPharmacy(customer, PH1, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Cart empty = Cart.empty(CUST, clock.instant());
    assertThatThrownBy(() -> service.assertCheckoutReady(empty))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CART_EMPTY");
    empty.abandon(clock.instant());
    assertThatThrownBy(() -> service.assertCheckoutReady(empty))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Cart helper = seededCartWithItem(PH1, MED, 1, 100);
    service.clear(helper);
    assertThat(helper.pharmacyId()).isNull();
    service.abandon(helper);
    assertThat(helper.status()).isEqualTo(CartStatus.ABANDONED);

    cartStore.clear();
    Cart prior = seededCartWithItem(PH1, MED, 1, 100);
    cartStore.insert(prior);
    Cart reordered =
        service.createActiveForReorder(
            CUST,
            PH2,
            ADDR,
            List.of(new CartItem(UUID.randomUUID(), MED2, 2, 9000, false, "B", null, null, null)));
    assertThat(reordered.pharmacyId()).isEqualTo(PH2);
    assertThat(reordered.prescriptionId()).isNull();
    assertThat(reordered.couponCode()).isNull();
    assertThat(cartStore.findActiveByCustomer(CUST).orElseThrow().id()).isEqualTo(reordered.id());
    assertThat(cartStore.byId.get(prior.id()).status()).isEqualTo(CartStatus.ABANDONED);

    MedmatePrincipal admin =
        new MedmatePrincipal(CUST, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.getCart(admin))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    // lat/lng from default address
    cartStore.clear();
    when(addresses.findDefault(CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.9345, 77.6125)));
    stubSmartSelect(PH1);
    stubStock(PH1, MED, 8500, 10);
    when(inventory.findMedicine(MED))
        .thenReturn(
            Optional.of(
                new MedicineDetails(MED, "Metformin", "USV", "10 tablets", true, null, false)));
    service.addItem(customer, MED, 1, false, null, null);
    verify(smartSelect).smartSelect(customer, MED, 12.9345, 77.6125);

    cartStore.clear();
    when(addresses.findDefault(CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addItem(customer, MED, 1, false, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // remove item endpoint path
    cartStore.clear();
    Cart c2 = seededCartWithItem(PH1, MED, 2, 8500);
    cartStore.insert(c2);
    stubStock(PH1, MED, 8500, 100);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> lines =
        (List<Map<String, Object>>) service.getCart(customer).get("items");
    UUID itemId = (UUID) lines.getFirst().get("item_id");
    service.updateItemQuantity(customer, itemId, 2);
    service.removeItem(customer, itemId);
    assertThat(service.getCart(customer).get("pharmacy")).isNull();
  }

  @Test
  void applyInvalidCouponExplicit() {
    Cart cart = seededCartWithItem(PH1, MED, 1, 8500);
    cartStore.insert(cart);
    assertThatThrownBy(() -> service.applyCoupon(customer, "INVALID"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COUPON");
    verify(smartSelect, never()).smartSelect(any(), any(), anyDouble(), anyDouble());
  }

  @Test
  void rateLimitedOnGet() {
    CartService limited =
        new CartService(
            cartStore,
            smartSelect,
            inventory,
            pharmacies,
            addresses,
            wallet,
            prescriptions,
            zones,
            new StubDeliveryFeeAdapter(),
            new InMemoryRateLimiter(clock),
            clock);
    limited.getCart(customer);
    for (int i = 0; i < 70; i++) {
      try {
        limited.getCart(customer);
      } catch (AppException ex) {
        assertThat(ex.code()).isEqualTo("RATE_LIMITED");
        return;
      }
    }
    throw new AssertionError("expected RATE_LIMITED");
  }

  private void stubSmartSelect(UUID pharmacyId) {
    Map<String, Object> selected = new LinkedHashMap<>();
    selected.put("id", pharmacyId);
    selected.put("name", "P");
    when(smartSelect.smartSelect(any(), any(), anyDouble(), anyDouble()))
        .thenReturn(
            Map.of("available", true, "selected_pharmacy", selected, "alternatives", List.of()));
  }

  private void stubStock(UUID pharmacyId, UUID medicineId, long pricePaise, int qty) {
    when(inventory.checkAvailability(eq(pharmacyId), eq(List.of(medicineId))))
        .thenReturn(
            List.of(new StockLine(medicineId, "M", qty, pricePaise, pricePaise, qty > 0, null)));
  }

  private Cart seededCartWithItem(UUID pharmacyId, UUID medicineId, int qty, long unitPaise) {
    Instant now = clock.instant();
    Cart cart = Cart.empty(CUST, now);
    cart.setPharmacyId(pharmacyId);
    cart.addOrMerge(
        new CartItem(
            UUID.randomUUID(),
            medicineId,
            qty,
            unitPaise,
            true,
            "Metformin",
            "USV",
            "10 tablets",
            null));
    cart.touch(now);
    return cart;
  }

  private static PharmacyRow pharmacy(UUID id, String name) {
    return new PharmacyRow(
        id,
        name,
        "Koramangala",
        "addr",
        null,
        null,
        12.935,
        77.613,
        true,
        false,
        "ACTIVE",
        4.6,
        10,
        95,
        10.0);
  }

  private static final class InMemoryCartStore implements CartStore {
    private final Map<UUID, Cart> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<Cart> findActiveByCustomer(UUID customerId) {
      return byId.values().stream()
          .filter(c -> c.customerId().equals(customerId) && c.status() == CartStatus.ACTIVE)
          .findFirst();
    }

    @Override
    public Optional<Cart> findById(UUID cartId) {
      return Optional.ofNullable(byId.get(cartId));
    }

    @Override
    public Cart insert(Cart cart) {
      byId.put(cart.id(), copy(cart));
      return cart;
    }

    @Override
    public Cart update(Cart cart) {
      byId.put(cart.id(), copy(cart));
      return cart;
    }

    @Override
    public int abandonStale(Instant cutoff) {
      int n = 0;
      for (Cart c : new ArrayList<>(byId.values())) {
        if (c.status() == CartStatus.ACTIVE && c.updatedAt().isBefore(cutoff)) {
          c.abandon(Instant.parse("2026-08-08T12:00:00Z"));
          byId.put(c.id(), copy(c));
          n++;
        }
      }
      return n;
    }

    void forceUpdatedAt(UUID id, Instant updatedAt) {
      Cart c = byId.get(id);
      Cart forced =
          new Cart(
              c.id(),
              c.customerId(),
              c.pharmacyId(),
              c.items(),
              c.couponCode(),
              c.couponDiscountPaise(),
              c.prescriptionId(),
              c.deliveryAddressId(),
              c.status(),
              c.createdAt(),
              updatedAt);
      byId.put(id, forced);
    }

    void clear() {
      byId.clear();
    }

    private static Cart copy(Cart c) {
      return new Cart(
          c.id(),
          c.customerId(),
          c.pharmacyId(),
          c.items(),
          c.couponCode(),
          c.couponDiscountPaise(),
          c.prescriptionId(),
          c.deliveryAddressId(),
          c.status(),
          c.createdAt(),
          c.updatedAt());
    }
  }
}
