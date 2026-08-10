package com.nammamedmate.order.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.order.application.port.out.CartStore;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.DeliveryFeePort;
import com.nammamedmate.order.application.port.out.DeliveryFeePort.FeeQuote;
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
import com.nammamedmate.order.domain.CartPricing.Bill;
import com.nammamedmate.order.domain.CartStatus;
import com.nammamedmate.order.domain.Haversine;
import com.nammamedmate.order.domain.PharmacyScorer;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

  private final CartStore carts;
  private final SmartPharmacySelectionService smartSelect;
  private final InventoryAvailabilityPort inventory;
  private final PharmacyCandidatePort pharmacies;
  private final CustomerAddressPort addresses;
  private final WalletBalancePort wallet;
  private final PrescriptionPort prescriptions;
  private final ZoneMembershipPort zones;
  private final DeliveryFeePort deliveryFees;
  private final PlatformCouponPort platformCoupons;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public CartService(
      CartStore carts,
      SmartPharmacySelectionService smartSelect,
      InventoryAvailabilityPort inventory,
      PharmacyCandidatePort pharmacies,
      CustomerAddressPort addresses,
      WalletBalancePort wallet,
      PrescriptionPort prescriptions,
      ZoneMembershipPort zones,
      DeliveryFeePort deliveryFees,
      PlatformCouponPort platformCoupons,
      RateLimiter rateLimiter,
      Clock clock) {
    this.carts = carts;
    this.smartSelect = smartSelect;
    this.inventory = inventory;
    this.pharmacies = pharmacies;
    this.addresses = addresses;
    this.wallet = wallet;
    this.prescriptions = prescriptions;
    this.zones = zones;
    this.deliveryFees = deliveryFees;
    this.platformCoupons = platformCoupons;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> getCart(MedmatePrincipal principal) {
    requireCustomer(principal);
    rateLimit("order:cart-get:" + principal.subject(), 60, 60);
    Cart cart = getOrCreateActive(principal.subject());
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> addItem(
      MedmatePrincipal principal,
      UUID medicineId,
      Integer quantity,
      Boolean switchPharmacy,
      Double lat,
      Double lng) {
    requireCustomer(principal);
    rateLimit("order:cart-add:" + principal.subject(), 30, 60);
    if (medicineId == null) {
      throw new AppException("VALIDATION_ERROR", "medicine_id is required", 400);
    }
    if (quantity == null || quantity <= 0) {
      throw new AppException("VALIDATION_ERROR", "quantity must be > 0", 400);
    }
    Cart cart = getOrCreateActive(principal.subject());
    requireActive(cart);

    MedicineDetails medicine =
        inventory
            .findMedicine(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (medicine.banned()) {
      throw new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404);
    }

    boolean switchFlag = Boolean.TRUE.equals(switchPharmacy);
    if (cart.isEmpty() || cart.pharmacyId() == null || switchFlag) {
      UUID pharmacyId = selectPharmacyForFirstAdd(principal, medicineId, lat, lng, cart);
      if (switchFlag && cart.pharmacyId() != null) {
        cart.clearContents(now());
      }
      cart.setPharmacyId(pharmacyId);
      addStockedItem(cart, medicine, pharmacyId, quantity);
    } else {
      StockLine stock = stockAt(cart.pharmacyId(), medicineId);
      String reason = stock.unavailableReason();
      if ("NOT_FOUND".equals(reason) || "BANNED".equals(reason)) {
        throw new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404);
      }
      if ("NOT_MAPPED".equals(reason)) {
        throw new AppException(
            "PHARMACY_MISMATCH",
            "Item is from a different pharmacy; set switch_pharmacy=true",
            409);
      }
      if ("OUT_OF_STOCK".equals(reason) || !stock.inStock()) {
        throw new AppException("OUT_OF_STOCK", "Medicine out of stock at selected pharmacy", 422);
      }
      if (stock.quantityAvailable() < quantityNeeded(cart, medicineId, quantity)) {
        throw new AppException("OUT_OF_STOCK", "Medicine out of stock at selected pharmacy", 422);
      }
      addStockedItem(cart, medicine, cart.pharmacyId(), quantity);
    }

    cart.recomputeCouponDiscount();
    cart.touch(now());
    carts.update(cart);
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> updateItemQuantity(
      MedmatePrincipal principal, UUID itemId, Integer quantity) {
    requireCustomer(principal);
    rateLimit("order:cart-patch:" + principal.subject(), 30, 60);
    if (itemId == null) {
      throw new AppException("VALIDATION_ERROR", "item_id is required", 400);
    }
    if (quantity == null || quantity < 0) {
      throw new AppException("VALIDATION_ERROR", "quantity must be >= 0", 400);
    }
    Cart cart = requireActiveCart(principal.subject());
    CartItem existing =
        cart.findItem(itemId)
            .orElseThrow(() -> new AppException("VALIDATION_ERROR", "Cart item not found", 404));
    if (quantity > 0) {
      StockLine stock = stockAt(cart.pharmacyId(), existing.productId());
      if (!stock.inStock() || stock.quantityAvailable() < quantity) {
        throw new AppException("OUT_OF_STOCK", "Medicine out of stock at selected pharmacy", 422);
      }
    }
    cart.updateQuantity(itemId, quantity);
    if (cart.isEmpty()) {
      cart.clearContents(now());
    } else {
      cart.recomputeCouponDiscount();
      cart.touch(now());
    }
    carts.update(cart);
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> removeItem(MedmatePrincipal principal, UUID itemId) {
    requireCustomer(principal);
    rateLimit("order:cart-del-item:" + principal.subject(), 30, 60);
    Cart cart = requireActiveCart(principal.subject());
    if (cart.findItem(itemId).isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "Cart item not found", 404);
    }
    cart.removeItem(itemId);
    if (cart.isEmpty()) {
      cart.clearContents(now());
    } else {
      cart.recomputeCouponDiscount();
      cart.touch(now());
    }
    carts.update(cart);
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> clearCart(MedmatePrincipal principal) {
    requireCustomer(principal);
    rateLimit("order:cart-clear:" + principal.subject(), 10, 60);
    Cart cart = getOrCreateActive(principal.subject());
    requireActive(cart);
    clear(cart);
    carts.update(cart);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", "Cart cleared");
    data.put("cart_id", cart.id());
    data.put("status", cart.status().name());
    return data;
  }

  @Transactional
  public Map<String, Object> applyCoupon(MedmatePrincipal principal, String couponCode) {
    requireCustomer(principal);
    rateLimit("order:cart-coupon:" + principal.subject(), 10, 60);
    Cart cart = requireActiveCart(principal.subject());
    String existing = CartPricing.normalize(cart.couponCode());
    String next = CartPricing.normalize(couponCode);
    if (existing != null && next != null && !existing.equals(next)) {
      throw new AppException(
          "COUPON_ALREADY_APPLIED", "A different coupon is already applied", 409);
    }
    PlatformCouponPort.Quote applied = platformCoupons.apply(couponCode, cart.itemTotalPaise());
    cart.setCoupon(applied.code(), applied.discountPaise());
    cart.touch(now());
    carts.update(cart);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("coupon_code", applied.code());
    data.put("discount_type", applied.type().name());
    data.put("discount_amount", CartPricing.paiseToRupees(applied.discountPaise()));
    data.put("message", applied.message());
    return data;
  }

  @Transactional
  public Map<String, Object> removeCoupon(MedmatePrincipal principal) {
    requireCustomer(principal);
    rateLimit("order:cart-coupon-del:" + principal.subject(), 10, 60);
    Cart cart = requireActiveCart(principal.subject());
    cart.clearCoupon();
    cart.touch(now());
    carts.update(cart);
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> attachPrescription(MedmatePrincipal principal, UUID prescriptionId) {
    requireCustomer(principal);
    rateLimit("order:cart-rx:" + principal.subject(), 10, 60);
    if (prescriptionId == null) {
      throw new AppException("VALIDATION_ERROR", "prescription_id is required", 400);
    }
    Cart cart = requireActiveCart(principal.subject());
    prescriptions
        .findVerified(prescriptionId, principal.subject())
        .orElseThrow(() -> new AppException("VALIDATION_ERROR", "Prescription not found", 404));
    cart.setPrescriptionId(prescriptionId);
    cart.touch(now());
    carts.update(cart);
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> removePrescription(MedmatePrincipal principal) {
    requireCustomer(principal);
    rateLimit("order:cart-rx-del:" + principal.subject(), 10, 60);
    Cart cart = requireActiveCart(principal.subject());
    cart.setPrescriptionId(null);
    cart.touch(now());
    carts.update(cart);
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> setAddress(MedmatePrincipal principal, UUID addressId) {
    requireCustomer(principal);
    rateLimit("order:cart-addr:" + principal.subject(), 10, 60);
    if (addressId == null) {
      throw new AppException("VALIDATION_ERROR", "address_id is required", 400);
    }
    Cart cart = requireActiveCart(principal.subject());
    AddressRow address =
        addresses
            .findForCustomer(addressId, principal.subject())
            .orElseThrow(() -> new AppException("ADDRESS_NOT_FOUND", "Address not found", 404));
    if (cart.pharmacyId() != null
        && !zones.isInPharmacyZone(cart.pharmacyId(), address.lat(), address.lng())) {
      throw new AppException(
          "ADDRESS_NOT_SERVICEABLE", "Delivery address outside pharmacy serviceable zone", 422);
    }
    cart.setDeliveryAddressId(address.id());
    cart.touch(now());
    carts.update(cart);
    return toCartView(cart);
  }

  @Transactional
  public Map<String, Object> switchPharmacy(
      MedmatePrincipal principal, UUID pharmacyId, Boolean confirm) {
    requireCustomer(principal);
    rateLimit("order:cart-switch:" + principal.subject(), 5, 60);
    if (!Boolean.TRUE.equals(confirm)) {
      throw new AppException("VALIDATION_ERROR", "confirm must be true", 400);
    }
    if (pharmacyId == null) {
      throw new AppException("VALIDATION_ERROR", "pharmacy_id is required", 400);
    }
    PharmacyRow pharmacy =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("VALIDATION_ERROR", "Pharmacy not found", 404));
    Cart cart = getOrCreateActive(principal.subject());
    requireActive(cart);
    cart.clearContents(now());
    cart.setPharmacyId(pharmacy.id());
    cart.touch(now());
    carts.update(cart);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cart_id", cart.id());
    Map<String, Object> ph = new LinkedHashMap<>();
    ph.put("id", pharmacy.id());
    ph.put("name", pharmacy.name());
    ph.put("area", pharmacy.area());
    data.put("pharmacy", ph);
    data.put("items", List.of());
    data.put("message", "Cart cleared and switched to " + pharmacy.name());
    return data;
  }

  /** Clears items/pharmacy/coupon/prescription; keeps ACTIVE. Usable by quote/reorder later. */
  public void clear(Cart cart) {
    cart.clearContents(now());
  }

  /** Abandon ACTIVE cart (quote accept / reorder). */
  public void abandon(Cart cart) {
    cart.abandon(now());
  }

  /**
   * Quote select / reorder: abandon any ACTIVE cart, then create a new ACTIVE cart locked to
   * pharmacy with quoted items + Rx + address.
   */
  @Transactional
  public Cart createActiveFromQuote(
      UUID customerId,
      UUID pharmacyId,
      UUID deliveryAddressId,
      UUID prescriptionId,
      List<CartItem> items) {
    Instant ts = now();
    carts
        .findActiveByCustomer(customerId)
        .ifPresent(
            existing -> {
              abandon(existing);
              carts.update(existing);
            });
    Cart cart =
        new Cart(
            UUID.randomUUID(),
            customerId,
            pharmacyId,
            items == null ? List.of() : items,
            null,
            0L,
            prescriptionId,
            deliveryAddressId,
            CartStatus.ACTIVE,
            ts,
            ts);
    return carts.insert(cart);
  }

  /**
   * Reorder: abandon prior ACTIVE cart, then create a fresh cart with items + address. Never
   * attaches prescription or coupon (STORY-007).
   */
  @Transactional
  public Cart createActiveForReorder(
      UUID customerId, UUID pharmacyId, UUID deliveryAddressId, List<CartItem> items) {
    return createActiveFromQuote(customerId, pharmacyId, deliveryAddressId, null, items);
  }

  /** Checkout guard for STORY-004. Throws PRESCRIPTION_REQUIRED when Rx items lack attachment. */
  public void assertCheckoutReady(Cart cart) {
    if (cart.hasRxItem() && cart.prescriptionId() == null) {
      throw new AppException(
          "PRESCRIPTION_REQUIRED", "Prescription required for Rx items in cart", 422);
    }
    if (cart.status() != CartStatus.ACTIVE) {
      throw new AppException("VALIDATION_ERROR", "Cart is not active", 422);
    }
    if (cart.isEmpty()) {
      throw new AppException("CART_EMPTY", "Cart has no items", 422);
    }
  }

  @Transactional
  public int abandonStaleCarts() {
    Instant cutoff = now().minus(Duration.ofHours(24));
    return carts.abandonStale(cutoff);
  }

  private UUID selectPharmacyForFirstAdd(
      MedmatePrincipal principal, UUID medicineId, Double lat, Double lng, Cart cart) {
    double[] coords = resolveCoords(principal.subject(), lat, lng, cart);
    Map<String, Object> result =
        smartSelect.smartSelect(principal, medicineId, coords[0], coords[1]);
    if (!Boolean.TRUE.equals(result.get("available")) || result.get("selected_pharmacy") == null) {
      throw new AppException(
          "NO_PHARMACY_AVAILABLE", "No open pharmacy stocks this medicine within 5km", 422);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> selected = (Map<String, Object>) result.get("selected_pharmacy");
    Object id = selected.get("id");
    if (id instanceof UUID u) {
      return u;
    }
    return UUID.fromString(String.valueOf(id));
  }

  private double[] resolveCoords(UUID customerId, Double lat, Double lng, Cart cart) {
    if (lat != null && lng != null) {
      SmartPharmacySelectionService.requireCoords(lat, lng);
      return new double[] {lat, lng};
    }
    if (cart.deliveryAddressId() != null) {
      Optional<AddressRow> addr = addresses.findForCustomer(cart.deliveryAddressId(), customerId);
      if (addr.isPresent()) {
        return new double[] {addr.get().lat(), addr.get().lng()};
      }
    }
    Optional<AddressRow> def = addresses.findDefault(customerId);
    if (def.isPresent()) {
      return new double[] {def.get().lat(), def.get().lng()};
    }
    throw new AppException(
        "VALIDATION_ERROR", "lat and lng are required when no delivery address is set", 400);
  }

  private void addStockedItem(Cart cart, MedicineDetails medicine, UUID pharmacyId, int quantity) {
    StockLine stock = stockAt(pharmacyId, medicine.id());
    int needed = quantityNeeded(cart, medicine.id(), quantity);
    if (!stock.inStock() || stock.quantityAvailable() < needed) {
      throw new AppException("OUT_OF_STOCK", "Medicine out of stock at selected pharmacy", 422);
    }
    CartItem line =
        new CartItem(
            UUID.randomUUID(),
            medicine.id(),
            quantity,
            stock.pricePaise(),
            medicine.rxRequired(),
            medicine.name(),
            medicine.brand(),
            medicine.packSize(),
            medicine.imageUrl());
    cart.addOrMerge(line);
  }

  private static int quantityNeeded(Cart cart, UUID medicineId, int addQty) {
    return cart.findByProduct(medicineId).map(i -> i.quantity() + addQty).orElse(addQty);
  }

  private StockLine stockAt(UUID pharmacyId, UUID medicineId) {
    List<StockLine> lines = inventory.checkAvailability(pharmacyId, List.of(medicineId));
    if (lines.isEmpty()) {
      return new StockLine(medicineId, "Unknown", 0, 0, 0, false, "NOT_FOUND");
    }
    return lines.getFirst();
  }

  private Cart getOrCreateActive(UUID customerId) {
    return carts
        .findActiveByCustomer(customerId)
        .orElseGet(
            () -> {
              Cart created = Cart.empty(customerId, now());
              return carts.insert(created);
            });
  }

  private Cart requireActiveCart(UUID customerId) {
    Cart cart = getOrCreateActive(customerId);
    requireActive(cart);
    return cart;
  }

  private static void requireActive(Cart cart) {
    if (cart.status() != CartStatus.ACTIVE) {
      throw new AppException("VALIDATION_ERROR", "Cart is abandoned; start a new cart", 422);
    }
  }

  private Map<String, Object> toCartView(Cart cart) {
    long walletBal = wallet.balancePaise(cart.customerId());
    Bill bill = computeBill(cart, walletBal);
    cart.setCoupon(cart.couponCode(), bill.couponDiscountPaise());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cart_id", cart.id());
    data.put("status", cart.status().name());
    data.put("pharmacy", pharmacyView(cart));
    data.put("items", itemsView(cart));
    data.put("coupon_applied", cart.couponCode());
    data.put(
        "coupon_discount",
        cart.couponCode() == null
            ? BigDecimal.ZERO.setScale(2)
            : CartPricing.paiseToRupees(bill.couponDiscountPaise()));
    data.put("prescription_id", cart.prescriptionId());
    data.put(
        "prescription_status",
        cart.prescriptionId() == null
            ? null
            : prescriptions
                .findVerified(cart.prescriptionId(), cart.customerId())
                .map(PrescriptionPort.PrescriptionRef::status)
                .orElse(null));
    data.put("delivery_address", addressView(cart));
    data.put("bill", billView(bill));
    data.put("created_at", cart.createdAt().toString());
    data.put("updated_at", cart.updatedAt().toString());
    return data;
  }

  private Map<String, Object> pharmacyView(Cart cart) {
    if (cart.pharmacyId() == null) {
      return null;
    }
    Optional<PharmacyRow> row = pharmacies.findById(cart.pharmacyId());
    if (row.isEmpty()) {
      Map<String, Object> stub = new LinkedHashMap<>();
      stub.put("id", cart.pharmacyId());
      stub.put("name", null);
      stub.put("area", null);
      stub.put("eta_minutes", null);
      stub.put("is_open", false);
      stub.put("rating", null);
      return stub;
    }
    PharmacyRow p = row.get();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id());
    m.put("name", p.name());
    m.put("area", p.area());
    int eta = etaMinutes(cart, p);
    m.put("eta_minutes", eta);
    m.put("is_open", p.isOpen());
    m.put("rating", SmartPharmacySelectionService.round(p.rating(), 1));
    return m;
  }

  private int etaMinutes(Cart cart, PharmacyRow p) {
    Double lat = null;
    Double lng = null;
    if (cart.deliveryAddressId() != null) {
      Optional<AddressRow> addr =
          addresses.findForCustomer(cart.deliveryAddressId(), cart.customerId());
      if (addr.isPresent()) {
        lat = addr.get().lat();
        lng = addr.get().lng();
      }
    }
    if (lat == null) {
      Optional<AddressRow> def = addresses.findDefault(cart.customerId());
      if (def.isPresent()) {
        lat = def.get().lat();
        lng = def.get().lng();
      }
    }
    if (lat == null || p.latitude() == null || p.longitude() == null) {
      return PharmacyScorer.deliveryEtaMinutes(0, p.avgPrepMinutes());
    }
    double dist = Haversine.distanceKm(lat, lng, p.latitude(), p.longitude());
    return PharmacyScorer.deliveryEtaMinutes(dist, p.avgPrepMinutes());
  }

  private List<Map<String, Object>> itemsView(Cart cart) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (CartItem item : cart.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("item_id", item.itemId());
      Map<String, Object> product = new LinkedHashMap<>();
      product.put("id", item.productId());
      product.put("name", item.name());
      product.put("brand", item.brand());
      product.put("pack_size", item.packSize());
      product.put("is_rx_required", item.rxRequired());
      product.put("image_url", item.imageUrl());
      row.put("product", product);
      row.put("quantity", item.quantity());
      row.put("unit_price", CartPricing.paiseToRupees(item.unitPricePaise()));
      row.put("line_total", CartPricing.paiseToRupees(item.lineTotalPaise()));
      out.add(row);
    }
    return out;
  }

  private Map<String, Object> addressView(Cart cart) {
    if (cart.deliveryAddressId() == null) {
      return null;
    }
    return addresses
        .findForCustomer(cart.deliveryAddressId(), cart.customerId())
        .map(
            a -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", a.id());
              m.put("label", a.label());
              m.put("full_address", a.fullAddress());
              m.put("lat", a.lat());
              m.put("lng", a.lng());
              return m;
            })
        .orElse(null);
  }

  private static Map<String, Object> billView(Bill bill) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("item_total", CartPricing.paiseToRupees(bill.itemTotalPaise()));
    m.put("coupon_discount", CartPricing.paiseToRupees(bill.couponDiscountPaise()));
    m.put("subtotal_after_discount", CartPricing.paiseToRupees(bill.subtotalAfterDiscountPaise()));
    m.put("delivery_fee", CartPricing.paiseToRupees(bill.deliveryFeePaise()));
    m.put("handling_fee", CartPricing.paiseToRupees(bill.handlingFeePaise()));
    m.put("wallet_applied", CartPricing.paiseToRupees(bill.walletAppliedPaise()));
    m.put("total_payable", CartPricing.paiseToRupees(bill.totalPayablePaise()));
    return m;
  }

  Bill computeBill(Cart cart, long walletBal) {
    Double lat = null;
    Double lng = null;
    if (cart.deliveryAddressId() != null) {
      Optional<AddressRow> addr =
          addresses.findForCustomer(cart.deliveryAddressId(), cart.customerId());
      if (addr.isPresent()) {
        lat = addr.get().lat();
        lng = addr.get().lng();
      }
    }
    boolean freeDel = "FREEDEL".equals(CartPricing.normalize(cart.couponCode()));
    Optional<FeeQuote> quote =
        deliveryFees.quote(cart.pharmacyId(), lat, lng, cart.itemTotalPaise(), freeDel);
    if (quote.isPresent()) {
      FeeQuote q = quote.get();
      return CartPricing.compute(
          cart.itemTotalPaise(),
          cart.couponCode(),
          walletBal,
          q.deliveryFeePaise(),
          q.handlingFeePaise());
    }
    return CartPricing.compute(cart.itemTotalPaise(), cart.couponCode(), walletBal);
  }

  private Instant now() {
    return clock.instant();
  }

  static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }
}
