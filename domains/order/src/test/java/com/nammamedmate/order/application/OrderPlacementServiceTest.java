package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.adapter.out.client.StubCashfreePaymentPort;
import com.nammamedmate.order.adapter.out.persistence.StubDeliveryFeeAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubPriceCeilingAdapter;
import com.nammamedmate.order.application.port.out.CartStore;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.WalletBalancePort;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartStatus;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPlacementServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID MED = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID MED_RX = UUID.fromString("22222222-2222-4222-8222-222222222299");
  private static final UUID ADDR = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID RX = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @Mock private CartService cartService;
  @Mock private InventoryAvailabilityPort inventory;
  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private CustomerAddressPort addresses;
  @Mock private WalletBalancePort walletBalance;
  @Mock private WalletPort wallet;
  @Mock private PrescriptionPort prescriptions;
  @Mock private ZoneMembershipPort zones;
  @Mock private RateLimiter rateLimiter;

  private InMemoryCartStore carts;
  private InMemoryOrderStore orders;
  private StubCashfreePaymentPort cashfree;
  private InMemoryOutboxStore outboxStore;
  private OrderPlacementService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal rider =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.RIDER, null, TokenScope.FULL, "j");
  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    carts = new InMemoryCartStore();
    orders = new InMemoryOrderStore();
    cashfree = new StubCashfreePaymentPort();
    outboxStore = new InMemoryOutboxStore();
    when(rateLimiter.tryAcquire(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    when(walletBalance.balancePaise(CUST)).thenReturn(0L);
    when(wallet.debitForOrder(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenAnswer(inv -> inv.getArgument(2));
    when(zones.isInPharmacyZone(any(), anyDouble(), anyDouble())).thenReturn(true);
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(
            Optional.of(new AddressRow(ADDR, CUST, "Home", "42 Koramangala", 12.9345, 77.6125)));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy(PH1)));
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenAnswer(
            inv -> {
              List<UUID> ids = inv.getArgument(1);
              List<StockLine> lines = new ArrayList<>();
              for (UUID id : ids) {
                lines.add(new StockLine(id, "Metformin 500mg", 100, 8500, 9000, true, null));
              }
              return lines;
            });
    when(prescriptions.findVerified(RX, CUST))
        .thenReturn(Optional.of(new PrescriptionPort.PrescriptionRef(RX, "VERIFIED")));

    service =
        new OrderPlacementService(
            cartService,
            carts,
            orders,
            new InMemoryOrderStatusEventStore(),
            inventory,
            pharmacies,
            addresses,
            walletBalance,
            wallet,
            prescriptions,
            zones,
            new StubDeliveryFeeAdapter(),
            new StubPriceCeilingAdapter(),
            cashfree,
            org.mockito.Mockito.mock(RefundService.class),
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            new ObjectMapper(),
            rateLimiter,
            clock);
    try {
      var field = OrderPlacementService.class.getDeclaredField("platformCoupons");
      field.setAccessible(true);
      var port =
          (com.nammamedmate.order.application.port.out.PlatformCouponPort) field.get(service);
      port.apply("NAMMA25", 10_000);
      port.apply("FREEDEL", 10_000);
      port.record("NAMMA25", UUID.randomUUID(), CUST, 100, 10_000);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    service.setPlatformCoupons(null);
    service.setPlatformCoupons(
        (code, total) ->
            new com.nammamedmate.order.application.port.out.PlatformCouponPort.Quote(
                code, com.nammamedmate.order.domain.CartPricing.CouponType.PERCENT, 0, false, ""));
  }

  @Test
  void ac1_rxWithoutPrescription_returnsPrescriptionRequired() {
    Cart cart = cartWithItem(MED_RX, true, 1, 8500);
    cart.setDeliveryAddressId(ADDR);
    carts.insert(cart);
    org.mockito.Mockito.doThrow(
            new AppException("PRESCRIPTION_REQUIRED", "Prescription required", 422))
        .when(cartService)
        .assertCheckoutReady(any());

    assertThatThrownBy(
            () -> service.placeOrder(customer, cart.id(), "COD", null, null, "idem-rx-1"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_REQUIRED");
    assertThat(orders.byId).isEmpty();
  }

  @Test
  void ac2_liveStockDrop_returnsItemsOutOfStock() {
    Cart cart = readyCart(false);
    carts.insert(cart);
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, "Metformin 500mg", 0, 8500, 9000, false, "OOS")));

    assertThatThrownBy(() -> service.placeOrder(customer, cart.id(), "COD", null, null, "idem-oos"))
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("ITEMS_OUT_OF_STOCK");
              assertThat(ae.details()).containsKey("out_of_stock_items");
            });
  }

  @Test
  void ac3_codPlacesPendingAcceptance() {
    Cart cart = readyCart(true);
    cart.setCoupon("NAMMA25", 100);
    carts.insert(cart);

    Map<String, Object> data =
        service.placeOrder(customer, cart.id(), "COD", null, "Leave at door", "idem-cod");
    verify(prescriptions).enqueueForPharmacy(eq(RX), eq(PH1), any());

    Cart returningCoupon = readyCart(false);
    returningCoupon.setCoupon("NAMMA25", 100);
    carts.insert(returningCoupon);
    assertThat(
            service.placeOrder(
                customer, returningCoupon.id(), "COD", null, null, "idem-coupon-return"))
        .containsKey("status");

    Cart blankCoupon = readyCart(false);
    blankCoupon.setCoupon("   ", 0);
    carts.insert(blankCoupon);
    assertThat(
            service.placeOrder(customer, blankCoupon.id(), "COD", null, null, "idem-blank-coupon"))
        .containsKey("status");

    assertThat(data.get("status")).isEqualTo("PENDING_ACCEPTANCE");
    @SuppressWarnings("unchecked")
    Map<String, Object> payment = (Map<String, Object>) data.get("payment");
    assertThat(payment.get("status")).isEqualTo("PENDING_COLLECTION");
    assertThat(payment.get("method")).isEqualTo("COD");
    assertThat(carts.findById(cart.id()).orElseThrow().status()).isEqualTo(CartStatus.CHECKED_OUT);
    assertThat(outboxStore.all()).isNotEmpty();
  }

  @Test
  void ac4_invalidPaymentSignature() {
    Cart cart = readyCart(false);
    carts.insert(cart);
    Map<String, Object> placed =
        service.placeOrder(customer, cart.id(), "UPI", null, null, "idem-upi");
    UUID orderId = (UUID) placed.get("order_id");

    assertThatThrownBy(
            () ->
                service.confirmPayment(
                    customer, orderId, "pay_x", "bad-signature", "idem-confirm-bad"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_SIGNATURE_INVALID");
  }

  @Test
  void ac5_confirmPaymentIdempotent() {
    Cart cart = readyCart(false);
    carts.insert(cart);
    Map<String, Object> placed =
        service.placeOrder(customer, cart.id(), "UPI", null, null, "idem-upi-2");
    UUID orderId = (UUID) placed.get("order_id");
    Order order = orders.findById(orderId).orElseThrow();
    String paymentId = "pay_Cashfree98765";
    String sig = cashfree.signPayment(order.gatewayOrderId(), paymentId);

    Map<String, Object> first =
        service.confirmPayment(customer, orderId, paymentId, sig, "idem-c1");
    Map<String, Object> second =
        service.confirmPayment(customer, orderId, paymentId, sig, "idem-c2");

    assertThat(first.get("status")).isEqualTo("PENDING_ACCEPTANCE");
    assertThat(second.get("status")).isEqualTo("PENDING_ACCEPTANCE");
    assertThat(second.get("order_id")).isEqualTo(first.get("order_id"));
  }

  @Test
  void ac6_confirmNotifiesPharmacyViaOutbox() {
    Cart cart = readyCart(false);
    carts.insert(cart);
    Map<String, Object> placed =
        service.placeOrder(customer, cart.id(), "UPI", null, null, "idem-notify");
    int before = outboxStore.all().size();
    UUID orderId = (UUID) placed.get("order_id");
    Order order = orders.findById(orderId).orElseThrow();
    String paymentId = "pay_notify";
    String sig = cashfree.signPayment(order.gatewayOrderId(), paymentId);

    Map<String, Object> confirmed =
        service.confirmPayment(customer, orderId, paymentId, sig, "idem-n1");

    assertThat(confirmed.get("pharmacy_notified")).isEqualTo(true);
    assertThat(outboxStore.all().size()).isGreaterThan(before);
    assertThat(
            outboxStore.all().stream()
                .anyMatch(e -> "order.placed.pharmacy_notified".equals(e.type())))
        .isTrue();
  }

  @Test
  void ac7_walletAppliedFirst() {
    when(walletBalance.balancePaise(CUST)).thenReturn(5_000L); // Rs 50
    // item total 170 (<199) → delivery 25 + handling 5 = 200; wallet 50 → payable 150
    Cart cart = cartWithItem(MED, false, 2, 8500);
    cart.setDeliveryAddressId(ADDR);
    carts.insert(cart);

    Map<String, Object> data =
        service.placeOrder(customer, cart.id(), "COD", null, null, "idem-wallet");

    @SuppressWarnings("unchecked")
    Map<String, Object> bill = (Map<String, Object>) data.get("bill");
    assertThat(bill.get("wallet_applied")).isEqualTo(new BigDecimal("50.00"));
    assertThat(bill.get("total_payable")).isEqualTo(new BigDecimal("150.00"));
  }

  @Test
  void ac8_cartCheckedOutCannotModifyViaStatus() {
    Cart cart = readyCart(false);
    carts.insert(cart);
    service.placeOrder(customer, cart.id(), "COD", null, null, "idem-co");
    assertThat(carts.findById(cart.id()).orElseThrow().status()).isEqualTo(CartStatus.CHECKED_OUT);
  }

  @Test
  void getOrderAndCodCollectAndWebhook() {
    Cart cart = readyCart(false);
    carts.insert(cart);
    Map<String, Object> placed =
        service.placeOrder(customer, cart.id(), "COD", null, null, "idem-get");
    UUID orderId = (UUID) placed.get("order_id");

    Map<String, Object> detail = service.getOrder(customer, orderId);
    assertThat(detail.get("order_number")).isEqualTo(placed.get("order_number"));

    Order codOrder = orders.findById(orderId).orElseThrow();
    codOrder.assignRider(rider.subject(), clock.instant());
    codOrder.advanceTo(OrderStatus.OUT_FOR_DELIVERY, clock.instant());
    orders.update(codOrder);

    Map<String, Object> collected =
        service.collectCod(
            rider,
            orderId,
            detail.get("bill") == null
                ? 285.00
                : ((Map<?, ?>) detail.get("bill")).get("total_payable"));
    assertThat(collected.get("payment_status")).isEqualTo("COLLECTED");

    Cart cart2 = readyCart(false);
    carts.insert(cart2);
    Map<String, Object> upi =
        service.placeOrder(customer, cart2.id(), "UPI", null, null, "idem-wh");
    Order o = orders.findById((UUID) upi.get("order_id")).orElseThrow();
    String paymentId = "pay_wh";
    String body =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","order_id":"%s"}}}}
        """
            .formatted(paymentId, o.gatewayOrderId());
    String sig =
        StubCashfreePaymentPort.hmacHex(StubCashfreePaymentPort.DEFAULT_WEBHOOK_SECRET, body);
    Map<String, Object> wh =
        service.handleCashfreeWebhook(sig, body.getBytes(StandardCharsets.UTF_8), "idem-wh-1");
    assertThat(wh.get("status")).isEqualTo("PENDING_ACCEPTANCE");
  }

  @Test
  void placeIdempotentReplayAndValidations() {
    Cart cart = readyCart(false);
    carts.insert(cart);
    Map<String, Object> first =
        service.placeOrder(customer, cart.id(), "COD", null, null, "same-key");
    Map<String, Object> second =
        service.placeOrder(customer, cart.id(), "COD", null, null, "same-key");
    assertThat(second.get("order_id")).isEqualTo(first.get("order_id"));

    assertThatThrownBy(() -> service.placeOrder(customer, null, "COD", null, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.placeOrder(customer, cart.id(), "COD", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> OrderPlacementService.parsePaymentMethod("CASH"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> OrderPlacementService.requireRider(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThat(OrderPlacementService.utf8("x")).hasSize(1);
  }

  @Test
  void pharmacyOfflineAndAddressErrors() {
    Cart cart = readyCart(false);
    cart.setDeliveryAddressId(null);
    carts.insert(cart);
    assertThatThrownBy(
            () -> service.placeOrder(customer, cart.id(), "COD", null, null, "idem-addr"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDRESS_NOT_SET");

    Cart cart2 = readyCart(false);
    carts.insert(cart2);
    when(zones.isInPharmacyZone(any(), anyDouble(), anyDouble())).thenReturn(false);
    assertThatThrownBy(
            () -> service.placeOrder(customer, cart2.id(), "COD", null, null, "idem-zone"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDRESS_NOT_SERVICEABLE");

    when(zones.isInPharmacyZone(any(), anyDouble(), anyDouble())).thenReturn(true);
    when(zones.minOrderValuePaise(any(), anyDouble(), anyDouble()))
        .thenReturn(java.util.OptionalLong.of(999_999_999L));
    Cart belowMin = readyCart(false);
    carts.insert(belowMin);
    assertThatThrownBy(
            () -> service.placeOrder(customer, belowMin.id(), "COD", null, null, "idem-min"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_BELOW_MINIMUM");
    when(zones.minOrderValuePaise(any(), anyDouble(), anyDouble()))
        .thenReturn(java.util.OptionalLong.of(1L));

    Cart cart3 = readyCart(false);
    carts.insert(cart3);
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1,
                    "Sai",
                    "Koramangala",
                    "addr",
                    null,
                    null,
                    12.9,
                    77.6,
                    false,
                    false,
                    "ACTIVE",
                    4.5,
                    10,
                    90,
                    10.0)));
    assertThatThrownBy(
            () -> service.placeOrder(customer, cart3.id(), "COD", null, null, "idem-off"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_OFFLINE");
  }

  private Cart readyCart(boolean rx) {
    Cart cart = cartWithItem(rx ? MED_RX : MED, rx, 3, 8500);
    cart.setDeliveryAddressId(ADDR);
    if (rx) {
      cart.setPrescriptionId(RX);
    }
    return cart;
  }

  private Cart cartWithItem(UUID med, boolean rx, int qty, long price) {
    Cart cart = Cart.empty(CUST, T0);
    cart.setPharmacyId(PH1);
    cart.addOrMerge(
        new CartItem(
            UUID.randomUUID(), med, qty, price, rx, "Metformin 500mg", "Glycomet", "10", null));
    return cart;
  }

  private static PharmacyRow openPharmacy(UUID id) {
    return new PharmacyRow(
        id,
        "Sai Medicals",
        "Koramangala",
        "42 Main",
        null,
        null,
        12.9350,
        77.6130,
        true,
        false,
        "ACTIVE",
        4.6,
        100,
        95.0,
        10.0);
  }

  static final class InMemoryCartStore implements CartStore {
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
      byId.put(cart.id(), cart);
      return cart;
    }

    @Override
    public Cart update(Cart cart) {
      byId.put(cart.id(), cart);
      return cart;
    }

    @Override
    public int abandonStale(Instant cutoff) {
      return 0;
    }
  }

  static final class InMemoryOrderStore implements OrderStore {
    final Map<UUID, Order> byId = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    @Override
    public Order insert(Order order) {
      byId.put(order.id(), order);
      return order;
    }

    @Override
    public Order update(Order order) {
      byId.put(order.id(), order);
      return order;
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }

    @Override
    public Optional<Order> findByCustomerAndId(UUID customerId, UUID orderId) {
      return findById(orderId).filter(o -> o.customerId().equals(customerId));
    }

    @Override
    public Optional<Order> findByPharmacyAndId(UUID pharmacyId, UUID orderId) {
      return findById(orderId).filter(o -> o.pharmacyId().equals(pharmacyId));
    }

    @Override
    public Optional<Order> findByPlacementIdempotencyKey(String idempotencyKey) {
      return byId.values().stream()
          .filter(o -> idempotencyKey.equals(o.placementIdempotencyKey()))
          .findFirst();
    }

    @Override
    public Optional<Order> findByGatewayOrderId(String gatewayOrderId) {
      return byId.values().stream()
          .filter(o -> gatewayOrderId.equals(o.gatewayOrderId()))
          .findFirst();
    }

    @Override
    public int nextSequence(LocalDate dateIst) {
      return seq.incrementAndGet();
    }

    @Override
    public boolean hasActiveOrders(UUID customerId) {
      return byId.values().stream()
          .anyMatch(o -> o.customerId().equals(customerId) && !o.status().isTerminal());
    }

    @Override
    public boolean hasPlacedAnyOrder(UUID customerId) {
      return byId.values().stream().anyMatch(o -> o.customerId().equals(customerId));
    }

    @Override
    public boolean isAddressInActiveOrder(UUID addressId) {
      return byId.values().stream()
          .anyMatch(o -> addressId.equals(o.deliveryAddressId()) && !o.status().isTerminal());
    }

    @Override
    public Optional<String> findPharmacyPhone(UUID pharmacyId) {
      return Optional.of("+91-8022334455");
    }

    @Override
    public List<Order> findPendingAcceptanceTimedOut(Instant deadlineBefore, int limit) {
      return byId.values().stream()
          .filter(o -> o.status() == OrderStatus.PENDING_ACCEPTANCE)
          .filter(o -> o.confirmedAt() != null && !o.confirmedAt().isAfter(deadlineBefore))
          .limit(limit)
          .toList();
    }

    @Override
    public List<Order> findReadyWithoutRiderEscalation(Instant readyBefore, int limit) {
      return byId.values().stream()
          .filter(o -> o.status() == OrderStatus.READY_FOR_PICKUP)
          .filter(o -> o.riderId() == null && o.riderEscalationAt() == null)
          .filter(o -> o.readyForPickupAt() != null && !o.readyForPickupAt().isAfter(readyBefore))
          .limit(limit)
          .toList();
    }

    @Override
    public List<Order> findOpenPastSlaDeadline(Instant now, int limit) {
      return byId.values().stream()
          .filter(o -> !o.slaBreached() && !o.status().isTerminal())
          .filter(o -> o.slaDeadline() != null && o.slaDeadline().isBefore(now))
          .limit(limit)
          .toList();
    }

    @Override
    public List<Order> listCustomerHistory(
        UUID customerId, String statusFilter, int offset, int limit) {
      return byId.values().stream()
          .filter(o -> o.customerId().equals(customerId) && o.status().isTerminal())
          .filter(
              o ->
                  statusFilter == null
                      || "ALL".equals(statusFilter)
                      || o.status().name().equals(statusFilter))
          .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
          .skip(offset)
          .limit(limit)
          .toList();
    }

    @Override
    public long countCustomerHistory(UUID customerId, String statusFilter) {
      return byId.values().stream()
          .filter(o -> o.customerId().equals(customerId) && o.status().isTerminal())
          .filter(
              o ->
                  statusFilter == null
                      || "ALL".equals(statusFilter)
                      || o.status().name().equals(statusFilter))
          .count();
    }

    @Override
    public List<Order> listCustomerActive(UUID customerId) {
      return byId.values().stream()
          .filter(o -> o.customerId().equals(customerId) && !o.status().isTerminal())
          .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
          .toList();
    }

    @Override
    public List<Order> listByPharmacy(UUID pharmacyId, String statusFilter, int offset, int limit) {
      return byId.values().stream()
          .filter(o -> o.pharmacyId().equals(pharmacyId))
          .filter(
              o ->
                  statusFilter == null
                      || "ALL".equalsIgnoreCase(statusFilter)
                      || o.status().name().equalsIgnoreCase(statusFilter))
          .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
          .skip(offset)
          .limit(limit)
          .toList();
    }

    @Override
    public long countByPharmacy(UUID pharmacyId, String statusFilter) {
      return byId.values().stream()
          .filter(o -> o.pharmacyId().equals(pharmacyId))
          .filter(
              o ->
                  statusFilter == null
                      || "ALL".equalsIgnoreCase(statusFilter)
                      || o.status().name().equalsIgnoreCase(statusFilter))
          .count();
    }
  }

  static final class InMemoryOrderStatusEventStore implements OrderStatusEventStore {
    final List<OrderStatusEvent> events = new ArrayList<>();

    @Override
    public void append(OrderStatusEvent event) {
      events.add(event);
    }

    @Override
    public List<OrderStatusEvent> listByOrderId(UUID orderId) {
      return events.stream().filter(e -> e.orderId().equals(orderId)).toList();
    }
  }
}
