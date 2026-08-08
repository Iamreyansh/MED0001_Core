package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.adapter.out.client.StubRazorpayPaymentPort;
import com.nammamedmate.order.adapter.out.persistence.StubPriceCeilingAdapter;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryCartStore;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStore;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.WalletBalancePort;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
class OrderPlacementCoverageTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID MED = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID MED_RX = UUID.fromString("22222222-2222-4222-8222-222222222299");
  private static final UUID ADDR = UUID.fromString("33333333-3333-4333-8333-333333333333");
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
  private StubRazorpayPaymentPort razorpay;
  private RefundService refundService;
  private OrderPlacementService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal rider =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.RIDER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    carts = new InMemoryCartStore();
    orders = new InMemoryOrderStore();
    razorpay = new StubRazorpayPaymentPort();
    when(rateLimiter.tryAcquire(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    when(rateLimiter.secondsUntilAvailable(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(0);
    when(walletBalance.balancePaise(CUST)).thenReturn(0L);
    when(wallet.debitForOrder(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(0L);
    when(zones.isInPharmacyZone(any(), anyDouble(), anyDouble())).thenReturn(true);
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "K", "a", null, null, 12.9, 77.6, true, false, "ACTIVE", 4.5, 1, 90,
                    10.0)));
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, "M", 100, 8500, 9000, true, null)));
    refundService = org.mockito.Mockito.mock(RefundService.class);
    service =
        new OrderPlacementService(
            cartService,
            carts,
            orders,
            new OrderPlacementServiceTest.InMemoryOrderStatusEventStore(),
            inventory,
            pharmacies,
            addresses,
            walletBalance,
            wallet,
            prescriptions,
            zones,
            new StubPriceCeilingAdapter(),
            razorpay,
            refundService,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void remainingBranches() throws Exception {
    assertThat(OrderStatus.ACCEPTED.isTerminal()).isFalse();
    assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(
            OrderItemSnapshot.fromCartItem(
                    new CartItem(UUID.randomUUID(), MED, 1, 1, false, "N", "  ", null, null))
                .name())
        .contains("N");

    // wallet insufficient
    Cart w = cart();
    carts.insert(w);
    assertThatThrownBy(() -> service.placeOrder(customer, w.id(), "WALLET", null, null, "c1"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // PAYMENT_INITIATION_FAILED rethrow
    OrderPlacementService failing =
        new OrderPlacementService(
            cartService,
            carts,
            orders,
            new OrderPlacementServiceTest.InMemoryOrderStatusEventStore(),
            inventory,
            pharmacies,
            addresses,
            walletBalance,
            wallet,
            prescriptions,
            zones,
            new StubPriceCeilingAdapter(),
            new StubRazorpayPaymentPort("k", "w", true),
            org.mockito.Mockito.mock(RefundService.class),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new ObjectMapper(),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC));
    Cart f = cart();
    carts.insert(f);
    assertThatThrownBy(() -> failing.placeOrder(customer, f.id(), "UPI", null, null, "c2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_INITIATION_FAILED");

    // blank secret defaults + hmac exception path via verify nulls
    StubRazorpayPaymentPort blankSecrets = new StubRazorpayPaymentPort(" ", " ");
    var created = blankSecrets.createOrder(UUID.randomUUID(), 100);
    assertThat(blankSecrets.verifyPaymentSignature(created.razorpayOrderId(), null, "x")).isFalse();
    assertThat(blankSecrets.verifyWebhookSignature("x", null)).isFalse();
    assertThatThrownBy(() -> StubRazorpayPaymentPort.hmacHex(null, "x"))
        .isInstanceOf(IllegalStateException.class);

    // rx without prescription id (checkout stubbed)
    Cart rx = cart();
    rx.replaceItems(
        List.of(new CartItem(UUID.randomUUID(), MED_RX, 1, 8500, true, "Rx", null, null, null)));
    carts.insert(rx);
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED_RX, "Rx", 10, 8500, 9000, true, null)));
    assertThatThrownBy(() -> service.placeOrder(customer, rx.id(), "COD", null, null, "c3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_REQUIRED");

    // UPI place + confirm null payment fields + wrong payment id when paid
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, "M", 100, 8500, 9000, true, null)));
    Cart upi = cart();
    carts.insert(upi);
    Map<String, Object> placed = service.placeOrder(customer, upi.id(), "UPI", null, null, "c4");
    UUID oid = UUID.fromString(String.valueOf(placed.get("order_id")));
    assertThatThrownBy(() -> service.confirmPayment(customer, oid, null, "s", "c5"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.confirmPayment(customer, oid, "p", null, "c6"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Order order = orders.findById(oid).orElseThrow();
    // null razorpay order id
    Order noRz =
        new Order(
            order.id(),
            order.orderNumber(),
            order.customerId(),
            order.pharmacyId(),
            order.cartId(),
            order.items(),
            order.itemTotalPaise(),
            null,
            0,
            order.deliveryFeePaise(),
            order.handlingFeePaise(),
            0,
            order.totalPayablePaise(),
            PaymentMethod.UPI,
            PaymentStatus.AWAITING_PAYMENT,
            null,
            null,
            null,
            order.deliveryAddressId(),
            null,
            OrderStatus.PAYMENT_PENDING,
            null,
            null,
            order.placementIdempotencyKey(),
            null,
            null,
            T0,
            T0);
    orders.update(noRz);
    // update won't clear razorpay in our in-memory if we replace - InMemory update replaces
    orders.byId.put(noRz.id(), noRz);
    assertThatThrownBy(() -> service.confirmPayment(customer, oid, "p", "s", "c7"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_SIGNATURE_INVALID");

    // fresh UPI confirm then wrong payment id on second call when already PAID
    Cart upi2 = cart();
    carts.insert(upi2);
    Map<String, Object> p2 = service.placeOrder(customer, upi2.id(), "UPI", null, null, "c8");
    UUID oid2 = UUID.fromString(String.valueOf(p2.get("order_id")));
    Order o2 = orders.findById(oid2).orElseThrow();
    String pay = "pay_ok";
    String sig = razorpay.signPayment(o2.razorpayOrderId(), pay);
    service.confirmPayment(customer, oid2, pay, sig, "c9");
    assertThatThrownBy(() -> service.confirmPayment(customer, oid2, "pay_other", sig, "c10"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_IN_PAYMENT_PENDING");

    // COD with wrong payment status
    Order oddCod =
        new Order(
            UUID.randomUUID(),
            "ORD-20260808-00999",
            CUST,
            PH1,
            upi2.id(),
            List.of(new OrderItemSnapshot(MED, "M", 1, 8500, 8500, false)),
            8500,
            null,
            0,
            2500,
            500,
            0,
            11500,
            PaymentMethod.COD,
            PaymentStatus.AWAITING_PAYMENT,
            null,
            null,
            null,
            ADDR,
            null,
            OrderStatus.PENDING_ACCEPTANCE,
            null,
            null,
            "odd",
            T0,
            T0,
            T0,
            T0);
    oddCod.assignRider(rider.subject(), T0);
    oddCod.advanceTo(OrderStatus.OUT_FOR_DELIVERY, T0);
    orders.insert(oddCod);
    assertThatThrownBy(() -> service.collectCod(rider, oddCod.id(), 115.00))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // webhook null body + ignored not pending + missing payment id
    String emptySig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, "");
    assertThatThrownBy(() -> service.handleRazorpayWebhook(emptySig, null, "w0"))
        .extracting(e -> ((AppException) e).code())
        .isIn("PAYMENT_SIGNATURE_INVALID", "VALIDATION_ERROR");

    String onlyOrder =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"order_x\"}}}}";
    String onlyOrderSig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, onlyOrder);
    assertThat(
            service.handleRazorpayWebhook(
                onlyOrderSig, onlyOrder.getBytes(StandardCharsets.UTF_8), "w1"))
        .containsEntry("ignored", true);

    // confirmed order webhook ignored path
    String body =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_new\",\"order_id\":\"%s\"}}}}"
            .formatted(o2.razorpayOrderId());
    String bodySig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, body);
    assertThat(service.handleRazorpayWebhook(bodySig, body.getBytes(StandardCharsets.UTF_8), "w2"))
        .containsEntry("ignored", true);

    String refundBody =
        "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\"}}}}";
    String refundSig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, refundBody);
    when(refundService.handleRefundProcessed(any())).thenReturn(Map.of("status", "PROCESSED"));
    assertThat(
            service.handleRazorpayWebhook(
                refundSig, refundBody.getBytes(StandardCharsets.UTF_8), "w-refund"))
        .containsEntry("status", "PROCESSED");

    // get detail with null confirmed/eta
    Map<String, Object> detail = service.getOrder(customer, oddCod.id());
    assertThat(detail.get("confirmed_at")).isNotNull();

    Order pendingView =
        new Order(
            UUID.randomUUID(),
            "ORD-20260808-00998",
            CUST,
            PH1,
            upi2.id(),
            List.of(),
            100,
            null,
            0,
            0,
            0,
            0,
            100,
            PaymentMethod.UPI,
            PaymentStatus.AWAITING_PAYMENT,
            "order_stub_view",
            null,
            null,
            ADDR,
            null,
            OrderStatus.PAYMENT_PENDING,
            null,
            null,
            "pv",
            null,
            null,
            T0,
            T0);
    orders.insert(pendingView);
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    assertThat(service.getOrder(customer, pendingView.id()).get("status"))
        .isEqualTo("PAYMENT_PENDING");

    // rate limit retry zero
    when(rateLimiter.tryAcquire(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(false);
    assertThatThrownBy(() -> service.getOrder(customer, oddCod.id()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");

    assertThatThrownBy(() -> OrderPlacementService.parsePaymentMethod("   "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> OrderPlacementService.requireIdempotencyKey("   "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> OrderPlacementService.requireIdempotencyKey("x".repeat(129)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(OrderPlacementService.normalizeInstructions("   ")).isNull();
    assertThatThrownBy(() -> OrderPlacementService.normalizeInstructions("x".repeat(201)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> OrderPlacementService.parseAmountPaise(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(blankSecrets.verifyPaymentSignature(created.razorpayOrderId(), "pay", "   "))
        .isFalse();
    assertThatThrownBy(
            () ->
                OrderPlacementService.requireCustomer(
                    new MedmatePrincipal(CUST, AuthRole.RIDER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> OrderPlacementService.requireRider(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThat(OrderPlacementService.parseAmountPaise(12)).isEqualTo(1200L);

    // webhook signature invalid on null body
    when(rateLimiter.tryAcquire(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    assertThatThrownBy(() -> service.handleRazorpayWebhook("nope", null, "w-null"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_SIGNATURE_INVALID");

    String onlyPay =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_only\"}}}}";
    String onlyPaySig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, onlyPay);
    assertThat(
            service.handleRazorpayWebhook(
                onlyPaySig, onlyPay.getBytes(StandardCharsets.UTF_8), "w-pay"))
        .containsEntry("ignored", true);

    String blankId =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"  \",\"order_id\":\"order_x\"}}}}";
    String blankIdSig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, blankId);
    assertThat(
            service.handleRazorpayWebhook(
                blankIdSig, blankId.getBytes(StandardCharsets.UTF_8), "w-blank"))
        .containsEntry("ignored", true);

    // full wallet + UPI → confirmed immediately (no online pay)
    when(walletBalance.balancePaise(CUST)).thenReturn(1_000_000L);
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "K", "a", null, null, 12.9, 77.6, true, false, "ACTIVE", 4.5, 1, 90,
                    10.0)));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));
    Cart full = cart();
    carts.insert(full);
    assertThat(service.placeOrder(customer, full.id(), "UPI", null, null, "full-upi").get("status"))
        .isEqualTo("PENDING_ACCEPTANCE");

    // UPLOADED rx
    when(walletBalance.balancePaise(CUST)).thenReturn(0L);
    UUID rxId = UUID.randomUUID();
    when(prescriptions.findVerified(rxId, CUST))
        .thenReturn(Optional.of(new PrescriptionPort.PrescriptionRef(rxId, "uploaded")));
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED_RX, "Rx", 5, 8500, 9000, true, null)));
    Cart rxOk = cart();
    rxOk.replaceItems(
        List.of(new CartItem(UUID.randomUUID(), MED_RX, 1, 8500, true, "Rx", null, null, null)));
    rxOk.setPrescriptionId(rxId);
    carts.insert(rxOk);
    assertThat(service.placeOrder(customer, rxOk.id(), "COD", null, null, "rx-up").get("status"))
        .isEqualTo("PENDING_ACCEPTANCE");

    new StubRazorpayPaymentPort(null, null).createOrder(UUID.randomUUID(), 50);

    String other = "{\"event\":\"order.paid\"}";
    String otherSig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, other);
    assertThat(
            service.handleRazorpayWebhook(
                otherSig, other.getBytes(StandardCharsets.UTF_8), "w-other"))
        .containsEntry("ignored", true);

    // COD place with missing pharmacy/address on response mapping
    when(walletBalance.balancePaise(CUST)).thenReturn(0L);
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, "M", 100, 8500, 9000, true, null)));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "K", "a", null, null, 12.9, 77.6, true, false, "ACTIVE", 4.5, 1, 90,
                    10.0)));
    Cart codView = cart();
    carts.insert(codView);
    Map<String, Object> codPlaced =
        service.placeOrder(customer, codView.id(), "COD", null, null, "cod-view");
    assertThat(codPlaced.get("status")).isEqualTo("PENDING_ACCEPTANCE");
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    // idempotent replay hits confirmedPlaceView with null pharmacy/address
    assertThat(service.placeOrder(customer, codView.id(), "COD", null, null, "cod-view"))
        .containsKey("order_id");

    // stock / geo / auth / amount edge branches
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "K", "a", null, null, null, 77.6, true, false, "ACTIVE", 4.5, 1, 90,
                    10.0)));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));
    when(inventory.checkAvailability(eq(PH1), anyList())).thenReturn(List.of());
    Cart missStock = cart();
    carts.insert(missStock);
    assertThatThrownBy(
            () -> service.placeOrder(customer, missStock.id(), "COD", null, null, "oos1"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ITEMS_OUT_OF_STOCK");

    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, null, 0, 8500, 9000, false, null)));
    Cart oosFlag = cart();
    carts.insert(oosFlag);
    assertThatThrownBy(() -> service.placeOrder(customer, oosFlag.id(), "COD", null, null, "oos2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ITEMS_OUT_OF_STOCK");

    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, "M", 0, 8500, 9000, true, null)));
    Cart lowQty = cart();
    carts.insert(lowQty);
    assertThatThrownBy(() -> service.placeOrder(customer, lowQty.id(), "COD", null, null, "oos3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ITEMS_OUT_OF_STOCK");

    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "K", "a", null, null, 12.9, null, true, false, "ACTIVE", 4.5, 1, 90,
                    10.0)));
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, "M", 100, 8500, 9000, true, null)));
    Cart geo = cart();
    carts.insert(geo);
    assertThat(service.placeOrder(customer, geo.id(), "COD", null, null, "geo-lng").get("status"))
        .isEqualTo("PENDING_ACCEPTANCE");

    when(prescriptions.findVerified(any(), eq(CUST)))
        .thenReturn(
            Optional.of(new PrescriptionPort.PrescriptionRef(UUID.randomUUID(), "REJECTED")));
    Cart badRx = cart();
    badRx.replaceItems(
        List.of(new CartItem(UUID.randomUUID(), MED_RX, 1, 8500, true, "Rx", null, null, null)));
    badRx.setPrescriptionId(UUID.randomUUID());
    carts.insert(badRx);
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED_RX, "Rx", 5, 8500, 9000, true, null)));
    assertThatThrownBy(() -> service.placeOrder(customer, badRx.id(), "COD", null, null, "rx-bad"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_REQUIRED");

    assertThatThrownBy(() -> OrderPlacementService.requireCustomer(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                OrderPlacementService.requireRider(
                    new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThat(OrderPlacementService.parseAmountPaise(new java.math.BigDecimal("11.50")))
        .isEqualTo(1150L);
    assertThat(blankSecrets.verifyPaymentSignature(null, null, null)).isFalse();
    assertThat(blankSecrets.verifyPaymentSignature("order_x", null, "sig")).isFalse();
    assertThat(blankSecrets.verifyPaymentSignature("order_x", "pay", null)).isFalse();

    AddressRow addr = new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125);
    PharmacyRow fullGeo =
        new PharmacyRow(
            PH1, "Sai", "K", "a", null, null, 12.9, 77.6, true, false, "ACTIVE", 4.5, 1, 90, 10.0);
    assertThat(OrderPlacementService.distanceKm(null, addr)).isZero();
    assertThat(OrderPlacementService.distanceKm(fullGeo, null)).isZero();
    assertThat(
            OrderPlacementService.distanceKm(
                new PharmacyRow(
                    PH1, "Sai", "K", "a", null, null, null, 77.6, true, false, "ACTIVE", 4.5, 1, 90,
                    10.0),
                addr))
        .isZero();
    assertThat(
            OrderPlacementService.distanceKm(
                new PharmacyRow(
                    PH1, "Sai", "K", "a", null, null, 12.9, null, true, false, "ACTIVE", 4.5, 1, 90,
                    10.0),
                addr))
        .isZero();
    assertThat(OrderPlacementService.distanceKm(fullGeo, addr)).isPositive();

    ObjectMapper om = new ObjectMapper();
    assertThat(OrderPlacementService.text(null, "event")).isNull();
    assertThat(OrderPlacementService.text(om.createObjectNode(), "event")).isNull();
    assertThat(OrderPlacementService.text(om.readTree("{\"event\":null}"), "event")).isNull();
    assertThat(OrderPlacementService.text(om.readTree("{\"event\":\"  \"}"), "event")).isNull();
    assertThat(OrderPlacementService.text(om.readTree("{\"event\":\"payment.captured\"}"), "event"))
        .isEqualTo("payment.captured");
  }

  private Cart cart() {
    Cart cart = Cart.empty(CUST, T0);
    cart.setPharmacyId(PH1);
    cart.setDeliveryAddressId(ADDR);
    cart.addOrMerge(new CartItem(UUID.randomUUID(), MED, 1, 8500, false, "M", null, "10", null));
    return cart;
  }
}
