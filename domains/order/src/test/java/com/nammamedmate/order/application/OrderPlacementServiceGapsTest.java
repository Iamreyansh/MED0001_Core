package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.adapter.out.client.StubRazorpayPaymentPort;
import com.nammamedmate.order.adapter.out.persistence.StubDeliveryFeeAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubPriceCeilingAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubWalletPort;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryCartStore;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStore;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.RazorpayPaymentPort;
import com.nammamedmate.order.application.port.out.WalletBalancePort;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
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
class OrderPlacementServiceGapsTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OTHER = UUID.fromString("99999999-9999-4999-8999-999999999999");
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
  private StubRazorpayPaymentPort razorpay;
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
    razorpay = new StubRazorpayPaymentPort();
    when(rateLimiter.tryAcquire(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    when(walletBalance.balancePaise(CUST)).thenReturn(0L);
    when(wallet.debitForOrder(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(0L);
    when(zones.isInPharmacyZone(any(), anyDouble(), anyDouble())).thenReturn(true);
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy()));
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED, "M", 100, 8500, 9000, true, null)));
    service = build(razorpay, wallet);
    StubWalletPort stubWallet = new StubWalletPort();
    assertThat(stubWallet.debitForOrder(CUST, UUID.randomUUID(), 100, "x")).isZero();
    assertThat(stubWallet.creditForRefund(CUST, UUID.randomUUID(), 100, "x", "k")).isNull();
  }

  @Test
  void coversPlaceGetConfirmCollectWebhookEdges() {
    assertThatThrownBy(
            () -> service.placeOrder(customer, UUID.randomUUID(), "COD", null, null, "k1"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_NOT_FOUND");

    Cart foreign = readyCart();
    Cart hacked =
        new Cart(
            foreign.id(),
            OTHER,
            PH1,
            foreign.items(),
            null,
            0,
            null,
            ADDR,
            foreign.status(),
            T0,
            T0);
    carts.insert(hacked);
    assertThatThrownBy(() -> service.placeOrder(customer, hacked.id(), "COD", null, null, "k2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_NOT_FOUND");

    Cart empty = Cart.empty(CUST, T0);
    empty.setPharmacyId(PH1);
    empty.setDeliveryAddressId(ADDR);
    carts.insert(empty);
    assertThatThrownBy(() -> service.placeOrder(customer, empty.id(), "COD", null, null, "k3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CART_EMPTY");

    Cart noAddrLookup = readyCart();
    carts.insert(noAddrLookup);
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.placeOrder(customer, noAddrLookup.id(), "COD", null, null, "k4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDRESS_NOT_SET");
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));

    Cart noPh = readyCart();
    noPh.setPharmacyId(null);
    carts.insert(noPh);
    assertThatThrownBy(() -> service.placeOrder(customer, noPh.id(), "COD", null, null, "k5"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_OFFLINE");

    Cart missingPh = readyCart();
    carts.insert(missingPh);
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.placeOrder(customer, missingPh.id(), "COD", null, null, "k6"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PHARMACY_OFFLINE");
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy()));

    assertThatThrownBy(
            () ->
                service.placeOrder(
                    customer, readyInserted().id(), "COD", "x".repeat(513), null, "k7"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // stock null line + prescription branches
    Cart rxCart = readyCart();
    rxCart.replaceItems(
        List.of(new CartItem(UUID.randomUUID(), MED_RX, 1, 8500, true, "RxMed", null, "10", null)));
    rxCart.setPrescriptionId(RX);
    carts.insert(rxCart);
    when(inventory.checkAvailability(eq(PH1), anyList())).thenReturn(List.of());
    assertThatThrownBy(() -> service.placeOrder(customer, rxCart.id(), "COD", null, null, "k8"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ITEMS_OUT_OF_STOCK");
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED_RX, null, 0, 8500, 9000, false, "OOS")));
    assertThatThrownBy(() -> service.placeOrder(customer, rxCart.id(), "COD", null, null, "k9"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ITEMS_OUT_OF_STOCK");
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(List.of(new StockLine(MED_RX, "RxMed", 100, 8500, 9000, true, null)));
    when(prescriptions.findVerified(RX, CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.placeOrder(customer, rxCart.id(), "COD", null, null, "k10"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_REQUIRED");
    when(prescriptions.findVerified(RX, CUST))
        .thenReturn(Optional.of(new PrescriptionPort.PrescriptionRef(RX, null)));
    assertThatThrownBy(() -> service.placeOrder(customer, rxCart.id(), "COD", null, null, "k11"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_REQUIRED");
    when(prescriptions.findVerified(RX, CUST))
        .thenReturn(Optional.of(new PrescriptionPort.PrescriptionRef(RX, "REJECTED")));
    assertThatThrownBy(() -> service.placeOrder(customer, rxCart.id(), "COD", null, null, "k12"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_REQUIRED");
    when(prescriptions.findVerified(RX, CUST))
        .thenReturn(Optional.of(new PrescriptionPort.PrescriptionRef(RX, "UPLOADED")));
    Map<String, Object> rxOk = service.placeOrder(customer, rxCart.id(), "COD", null, null, "k13");
    assertThat(rxOk.get("status")).isEqualTo("PENDING_ACCEPTANCE");

    // razorpay AppException non-initiation + RuntimeException
    RazorpayPaymentPort badApp = mock(RazorpayPaymentPort.class);
    when(badApp.createOrder(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenThrow(new AppException("OTHER", "x", 500));
    OrderPlacementService s1 = build(badApp, wallet);
    assertThatThrownBy(
            () -> s1.placeOrder(customer, readyInserted().id(), "UPI", null, null, "k14"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_INITIATION_FAILED");
    RazorpayPaymentPort badRt = mock(RazorpayPaymentPort.class);
    when(badRt.createOrder(any(), org.mockito.ArgumentMatchers.anyLong()))
        .thenThrow(new RuntimeException("boom"));
    OrderPlacementService s2 = build(badRt, wallet);
    assertThatThrownBy(
            () -> s2.placeOrder(customer, readyInserted().id(), "CARD", null, null, "k15"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_INITIATION_FAILED");

    assertThatThrownBy(() -> service.getOrder(customer, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.getOrder(customer, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    Cart upiCart = readyInserted();
    Map<String, Object> upi = service.placeOrder(customer, upiCart.id(), "UPI", "tok", null, "k16");
    UUID upiId = (UUID) upi.get("order_id");
    assertThatThrownBy(() -> service.confirmPayment(customer, null, "p", "s", "c1"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.confirmPayment(customer, upiId, " ", "s", "c2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.confirmPayment(customer, upiId, "p", " ", "c3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.confirmPayment(customer, UUID.randomUUID(), "p", "s", "c4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    // confirm COD order → not payment pending
    Cart codCart = readyInserted();
    Map<String, Object> cod = service.placeOrder(customer, codCart.id(), "COD", null, null, "k17");
    UUID codId = (UUID) cod.get("order_id");
    assertThatThrownBy(() -> service.confirmPayment(customer, codId, "pay", "sig", "c5"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_IN_PAYMENT_PENDING");

    Order upiOrder = orders.findById(upiId).orElseThrow();
    String pay = "pay_gap";
    String sig = razorpay.signPayment(upiOrder.razorpayOrderId(), pay);
    service.confirmPayment(customer, upiId, pay, sig, "c6");
    // null razorpay order id branch via fake order — already confirmed path covered
    assertThat(service.confirmPayment(customer, upiId, pay, sig, "c7").get("status"))
        .isEqualTo("PENDING_ACCEPTANCE");

    assertThatThrownBy(() -> service.collectCod(rider, null, 1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.collectCod(rider, UUID.randomUUID(), 1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
    assertThatThrownBy(() -> service.collectCod(rider, upiId, 1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.collectCod(rider, codId, 1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    Object payable =
        ((Map<?, ?>) service.getOrder(customer, codId).get("bill")).get("total_payable");
    Order codForCollect = orders.findById(codId).orElseThrow();
    codForCollect.assignRider(UUID.randomUUID(), clock.instant());
    orders.update(codForCollect);
    assertThatThrownBy(() -> service.collectCod(rider, codId, payable))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    codForCollect.assignRider(rider.subject(), clock.instant());
    orders.update(codForCollect);
    assertThatThrownBy(() -> service.collectCod(rider, codId, payable))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    codForCollect.advanceTo(OrderStatus.READY_FOR_PICKUP, clock.instant());
    orders.update(codForCollect);
    Map<String, Object> collected = service.collectCod(rider, codId, payable);
    assertThat(collected.get("payment_status")).isEqualTo("COLLECTED");
    assertThat(
            service
                .collectCod(rider, codId, collected.get("amount_collected"))
                .get("payment_status"))
        .isEqualTo("COLLECTED");

    // force non-pending-collection COD by mutating after place — create UPI then change? skip
    // webhook edges
    String missingIds =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{}}}}";
    String whSig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, missingIds);
    assertThat(
            service.handleRazorpayWebhook(whSig, missingIds.getBytes(StandardCharsets.UTF_8), "w1"))
        .containsEntry("ignored", true);

    Cart upi2 = readyInserted();
    Map<String, Object> upiPlaced =
        service.placeOrder(customer, upi2.id(), "UPI", null, null, "k18");
    Order o2 = orders.findById((UUID) upiPlaced.get("order_id")).orElseThrow();
    String body =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_w\",\"order_id\":\"%s\"}}}}"
            .formatted(o2.razorpayOrderId());
    String bodySig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, body);
    assertThat(service.handleRazorpayWebhook(bodySig, body.getBytes(StandardCharsets.UTF_8), "w2"))
        .containsEntry("status", "PENDING_ACCEPTANCE");
    assertThat(service.handleRazorpayWebhook(bodySig, body.getBytes(StandardCharsets.UTF_8), "w3"))
        .containsEntry("payment_status", "PAID");

    // webhook unknown order
    String unknown =
        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_x\",\"order_id\":\"order_missing\"}}}}";
    String unknownSig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, unknown);
    assertThatThrownBy(
            () ->
                service.handleRazorpayWebhook(
                    unknownSig, unknown.getBytes(StandardCharsets.UTF_8), "w4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    // invalid json
    String badJson = "{";
    String badJsonSig =
        StubRazorpayPaymentPort.hmacHex(StubRazorpayPaymentPort.DEFAULT_WEBHOOK_SECRET, badJson);
    assertThatThrownBy(
            () ->
                service.handleRazorpayWebhook(
                    badJsonSig, badJson.getBytes(StandardCharsets.UTF_8), "w5"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // webhook on already non-pending: place COD then try webhook with forged rz id — mutate order
    Order codOrder = orders.findById(codId).orElseThrow();
    // ignored path when not PAYMENT_PENDING: craft webhook for confirmed UPI already done above

    // confirm with pharmacy/address missing for ETA null geo
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy()));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));
    when(walletBalance.balancePaise(CUST)).thenReturn(0L);
    Cart upi3 = readyInserted();
    Map<String, Object> upi3p = service.placeOrder(customer, upi3.id(), "UPI", null, null, "k19");
    assertThat(upi3p.get("status")).isEqualTo("PAYMENT_PENDING");
    UUID upi3id = UUID.fromString(String.valueOf(upi3p.get("order_id")));
    Order o3 = orders.findById(upi3id).orElseThrow();
    assertThat(o3.razorpayOrderId()).isNotBlank();
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    String p3 = "pay_eta";
    String s3 = razorpay.signPayment(o3.razorpayOrderId(), p3);
    Map<String, Object> confirmedEta = service.confirmPayment(customer, upi3id, p3, s3, "c8");
    assertThat(confirmedEta).containsEntry("pharmacy_notified", true);

    // helpers
    assertThatThrownBy(() -> OrderPlacementService.parsePaymentMethod(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(OrderPlacementService.normalizeInstructions(null)).isNull();
    assertThat(OrderPlacementService.parseAmountPaise("12.34")).isEqualTo(1234L);
    assertThatThrownBy(() -> OrderPlacementService.parseAmountPaise(new BigDecimal("1.001")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // amount mismatch on fresh COD
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy()));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "42", 12.9345, 77.6125)));
    Cart cod2 = readyInserted();
    UUID cod2id =
        (UUID) service.placeOrder(customer, cod2.id(), "COD", null, null, "k20").get("order_id");
    Order cod2Order = orders.findById(cod2id).orElseThrow();
    cod2Order.assignRider(rider.subject(), clock.instant());
    cod2Order.advanceTo(OrderStatus.OUT_FOR_DELIVERY, clock.instant());
    orders.update(cod2Order);
    assertThatThrownBy(() -> service.collectCod(rider, cod2id, 1.00))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // wallet full pay + debit path
    when(walletBalance.balancePaise(CUST)).thenReturn(1_000_000L);
    Cart wCart = readyInserted();
    assertThat(service.placeOrder(customer, wCart.id(), "WALLET", null, null, "k21").get("status"))
        .isEqualTo("PENDING_ACCEPTANCE");

    // CARD with null pharmacy lat for ETA during place confirm path
    when(pharmacies.findById(PH1))
        .thenReturn(
            Optional.of(
                new PharmacyRow(
                    PH1, "Sai", "Kora", "a", null, null, null, null, true, false, "ACTIVE", 4.5, 1,
                    90, null)));
    Cart cardCart = readyInserted();
    when(walletBalance.balancePaise(CUST)).thenReturn(0L);
    Map<String, Object> card =
        service.placeOrder(customer, cardCart.id(), "CARD", null, null, "k22");
    assertThat(card.get("status")).isEqualTo("PAYMENT_PENDING");

    // COD not pending collection: mark collected already then force status via second order UPI
    // confirmed used as non-COD
    assertThat(PaymentMethod.CARD.isOnline()).isTrue();
    assertThat(codOrder.paymentMethod()).isEqualTo(PaymentMethod.COD);
  }

  private Cart readyInserted() {
    Cart cart = readyCart();
    carts.insert(cart);
    when(inventory.checkAvailability(eq(PH1), anyList()))
        .thenReturn(
            List.of(
                new StockLine(MED, "M", 100, 8500, 9000, true, null),
                new StockLine(MED_RX, "RxMed", 100, 8500, 9000, true, null)));
    return cart;
  }

  private Cart readyCart() {
    Cart cart = Cart.empty(CUST, T0);
    cart.setPharmacyId(PH1);
    cart.setDeliveryAddressId(ADDR);
    cart.addOrMerge(new CartItem(UUID.randomUUID(), MED, 1, 8500, false, "M", null, "10", null));
    return cart;
  }

  private PharmacyRow openPharmacy() {
    return new PharmacyRow(
        PH1,
        "Sai",
        "Koramangala",
        "a",
        null,
        null,
        12.9,
        77.6,
        true,
        false,
        "ACTIVE",
        4.5,
        10,
        90,
        10.0);
  }

  private OrderPlacementService build(RazorpayPaymentPort rz, WalletPort w) {
    return new OrderPlacementService(
        cartService,
        carts,
        orders,
        new OrderPlacementServiceTest.InMemoryOrderStatusEventStore(),
        inventory,
        pharmacies,
        addresses,
        walletBalance,
        w,
        prescriptions,
        zones,
        new StubDeliveryFeeAdapter(),
        new StubPriceCeilingAdapter(),
        rz,
        org.mockito.Mockito.mock(RefundService.class),
        new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
        new ObjectMapper(),
        rateLimiter,
        clock);
  }
}
