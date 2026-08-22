package com.nammamedmate.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.order.application.AdminOrderService;
import com.nammamedmate.order.application.AdminOrderService.ListResult;
import com.nammamedmate.order.application.CartService;
import com.nammamedmate.order.application.OrderCancellationService;
import com.nammamedmate.order.application.OrderLifecycleService;
import com.nammamedmate.order.application.OrderPlacementService;
import com.nammamedmate.order.application.PharmacyDiscoveryService;
import com.nammamedmate.order.application.PharmacyDiscoveryService.NearbyResult;
import com.nammamedmate.order.application.PharmacyDiscoveryService.ProductsResult;
import com.nammamedmate.order.application.ReorderService;
import com.nammamedmate.order.application.ReorderService.HistoryResult;
import com.nammamedmate.order.application.RxQuoteBroadcastService;
import com.nammamedmate.order.application.SmartPharmacySelectionService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderControllersTest {

  @Mock private SmartPharmacySelectionService smartSelect;
  @Mock private PharmacyDiscoveryService discovery;
  @Mock private CartService cartService;
  @Mock private RxQuoteBroadcastService rxQuote;
  @Mock private OrderPlacementService orderPlacement;
  @Mock private OrderLifecycleService orderLifecycle;
  @Mock private OrderCancellationService orderCancellation;
  @Mock private ReorderService reorderService;
  @Mock private AdminOrderService adminOrderService;
  @Mock private com.nammamedmate.order.application.port.out.RazorpayPaymentPort razorpay;

  private CartSmartSelectController cartSmartSelectController;
  private CartController cartController;
  private CustomerPharmacyController pharmacyController;
  private RxQuoteCustomerController rxCustomerController;
  private RxQuotePharmacyController rxPharmacyController;
  private OrderController orderController;
  private OrderPaymentController orderPaymentController;
  private RazorpayOrderPaymentWebhookController webhookController;
  private PharmacyOrderLifecycleController pharmacyOrderLifecycleController;
  private AdminOrderLifecycleController adminOrderLifecycleController;
  private AdminOrderCancelRefundController adminCancelRefundController;
  private AdminOrderOversightController adminOversightController;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal pharmacyPrincipal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
  private final MedmatePrincipal rider =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.RIDER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    cartSmartSelectController = new CartSmartSelectController(smartSelect);
    cartController = new CartController(cartService);
    pharmacyController = new CustomerPharmacyController(discovery);
    rxCustomerController = new RxQuoteCustomerController(rxQuote);
    rxPharmacyController = new RxQuotePharmacyController(rxQuote);
    orderController =
        new OrderController(orderPlacement, orderLifecycle, orderCancellation, reorderService);
    orderPaymentController = new OrderPaymentController(orderPlacement);
    webhookController = new RazorpayOrderPaymentWebhookController(razorpay);
    pharmacyOrderLifecycleController = new PharmacyOrderLifecycleController(orderLifecycle);
    adminOrderLifecycleController = new AdminOrderLifecycleController(orderLifecycle);
    adminCancelRefundController = new AdminOrderCancelRefundController(orderCancellation);
    adminOversightController = new AdminOrderOversightController(adminOrderService);
  }

  @Test
  void smartSelectDelegates() {
    UUID med = UUID.randomUUID();
    when(smartSelect.smartSelect(customer, med, 12.9, 77.6)).thenReturn(Map.of("available", true));
    ApiResponse<Map<String, Object>> res =
        cartSmartSelectController.smartSelect(
            customer, new CartSmartSelectController.SmartSelectRequest(med, 12.9, 77.6));
    assertThat(res.success()).isTrue();
    assertThat(res.data()).containsEntry("available", true);

    cartSmartSelectController.smartSelect(customer, null);
    verify(smartSelect).smartSelect(customer, null, null, null);
  }

  @Test
  void cartLifecycleDelegates() {
    UUID med = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    UUID rx = UUID.randomUUID();
    UUID addr = UUID.randomUUID();
    UUID ph = UUID.randomUUID();
    when(cartService.getCart(customer)).thenReturn(Map.of("status", "ACTIVE"));
    assertThat(cartController.get(customer).data()).containsEntry("status", "ACTIVE");

    when(cartService.addItem(customer, med, 1, false, 12.9, 77.6))
        .thenReturn(Map.of("items", List.of()));
    assertThat(
            cartController
                .addItem(customer, new CartController.AddItemRequest(med, 1, false, 12.9, 77.6))
                .success())
        .isTrue();
    cartController.addItem(customer, null);
    verify(cartService).addItem(customer, null, null, null, null, null);

    when(cartService.updateItemQuantity(customer, item, 2)).thenReturn(Map.of("ok", true));
    cartController.updateItem(customer, item, new CartController.UpdateQtyRequest(2));
    cartController.updateItem(customer, item, null);
    verify(cartService).updateItemQuantity(customer, item, null);

    when(cartService.removeItem(customer, item)).thenReturn(Map.of("ok", true));
    cartController.removeItem(customer, item);

    when(cartService.clearCart(customer)).thenReturn(Map.of("message", "Cart cleared"));
    assertThat(cartController.clear(customer).data()).containsEntry("message", "Cart cleared");

    when(cartService.applyCoupon(customer, "FLAT50")).thenReturn(Map.of("coupon_code", "FLAT50"));
    cartController.applyCoupon(customer, new CartController.CouponRequest("FLAT50"));
    cartController.applyCoupon(customer, null);
    verify(cartService).applyCoupon(customer, null);

    when(cartService.removeCoupon(customer)).thenReturn(new java.util.LinkedHashMap<>());
    cartController.removeCoupon(customer);

    when(cartService.attachPrescription(customer, rx)).thenReturn(Map.of("prescription_id", rx));
    cartController.attachPrescription(customer, new CartController.PrescriptionRequest(rx));
    cartController.attachPrescription(customer, null);
    verify(cartService).attachPrescription(customer, null);

    when(cartService.removePrescription(customer)).thenReturn(new java.util.LinkedHashMap<>());
    cartController.removePrescription(customer);

    when(cartService.setAddress(customer, addr)).thenReturn(Map.of("ok", true));
    cartController.setAddress(customer, new CartController.AddressRequest(addr));
    cartController.setAddress(customer, null);
    verify(cartService).setAddress(customer, null);

    when(cartService.switchPharmacy(customer, ph, true)).thenReturn(Map.of("items", List.of()));
    cartController.switchPharmacy(customer, new CartController.SwitchPharmacyRequest(ph, true));
    cartController.switchPharmacy(customer, null);
    verify(cartService).switchPharmacy(customer, null, null);
  }

  @Test
  void nearbyAndStorefrontAndProductsAndAvailability() {
    UUID id = UUID.randomUUID();
    when(discovery.nearby(customer, 12.9, 77.6, 3.0, 10))
        .thenReturn(
            new NearbyResult(List.of(Map.of("id", id)), Map.of("total", 1, "radius_km", 3.0)));
    Map<String, Object> nearby = pharmacyController.nearby(customer, 12.9, 77.6, 3.0, 10);
    assertThat(nearby).containsEntry("success", true);

    when(discovery.storefront(customer, id, 12.9, 77.6)).thenReturn(Map.of("id", id));
    assertThat(pharmacyController.storefront(customer, id, 12.9, 77.6).data())
        .containsEntry("id", id);

    when(discovery.products(eq(customer), eq(id), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(new ProductsResult(List.of(), Map.of("page", 1, "limit", 20, "total", 0)));
    Map<String, Object> products =
        pharmacyController.products(customer, id, null, null, null, null);
    assertThat(products).containsEntry("success", true);

    when(discovery.availabilityCheck(eq(customer), eq(id), any()))
        .thenReturn(Map.of("pharmacy_id", id));
    assertThat(
            pharmacyController
                .availabilityCheck(
                    customer,
                    new CustomerPharmacyController.AvailabilityCheckRequest(
                        id, List.of(UUID.randomUUID())))
                .data())
        .containsEntry("pharmacy_id", id);

    pharmacyController.availabilityCheck(customer, null);
    verify(discovery).availabilityCheck(customer, null, null);
  }

  @Test
  void rxQuoteControllersDelegate() {
    UUID bc = UUID.randomUUID();
    UUID rx = UUID.randomUUID();
    UUID addr = UUID.randomUUID();
    UUID ph = UUID.randomUUID();
    when(rxQuote.broadcast(customer, rx, addr, "Ravi", null))
        .thenReturn(Map.of("broadcast_id", bc));
    assertThat(
            rxCustomerController
                .broadcast(
                    customer,
                    new RxQuoteCustomerController.BroadcastRequest(rx, addr, "Ravi", null))
                .data())
        .containsEntry("broadcast_id", bc);
    rxCustomerController.broadcast(customer, null);
    verify(rxQuote).broadcast(customer, null, null, null, null);

    when(rxQuote.getBroadcast(customer, bc)).thenReturn(Map.of("status", "ACTIVE"));
    assertThat(rxCustomerController.get(customer, bc).data()).containsEntry("status", "ACTIVE");

    when(rxQuote.listQuotes(customer, bc)).thenReturn(List.of(Map.of("pharmacy_id", ph)));
    assertThat(rxCustomerController.quotes(customer, bc).data()).hasSize(1);

    when(rxQuote.selectQuote(customer, bc, ph)).thenReturn(Map.of("status", "SELECTED"));
    rxCustomerController.select(customer, bc, new RxQuoteCustomerController.SelectRequest(ph));
    rxCustomerController.select(customer, bc, null);
    verify(rxQuote).selectQuote(customer, bc, null);

    when(rxQuote.listIncoming(pharmacyPrincipal))
        .thenReturn(List.of(Map.of("status", "PENDING_RESPONSE")));
    assertThat(rxPharmacyController.list(pharmacyPrincipal).data()).hasSize(1);

    when(rxQuote.submitQuote(eq(pharmacyPrincipal), eq(bc), any(), eq(22)))
        .thenReturn(Map.of("status", "QUOTED"));
    rxPharmacyController.quote(
        pharmacyPrincipal,
        bc,
        new RxQuotePharmacyController.QuoteRequest(
            List.of(Map.of("name", "A", "qty", 1, "price", 10)), 22));
    rxPharmacyController.quote(pharmacyPrincipal, bc, null);
    verify(rxQuote).submitQuote(pharmacyPrincipal, bc, null, null);

    when(rxQuote.decline(pharmacyPrincipal, bc, "OUT_OF_STOCK"))
        .thenReturn(Map.of("status", "OUT_OF_STOCK"));
    rxPharmacyController.decline(
        pharmacyPrincipal, bc, new RxQuotePharmacyController.DeclineRequest("OUT_OF_STOCK"));
    rxPharmacyController.decline(pharmacyPrincipal, bc, null);
    verify(rxQuote).decline(pharmacyPrincipal, bc, null);
  }

  @Test
  void reorderHistoryActiveDelegate() {
    UUID past = UUID.randomUUID();
    when(reorderService.reorder(customer, past, true)).thenReturn(Map.of("cart_id", past));
    assertThat(
            orderController
                .reorder(customer, past, new OrderController.ReorderRequest(true))
                .data())
        .containsEntry("cart_id", past);
    orderController.reorder(customer, past, null);
    verify(reorderService).reorder(customer, past, null);

    when(reorderService.history(customer, 1, 20, "ALL"))
        .thenReturn(
            new HistoryResult(List.of(Map.of("status", "DELIVERED")), PaginationMeta.of(1, 20, 1)));
    assertThat(orderController.history(customer, 1, 20, "ALL").data()).hasSize(1);
    assertThat(orderController.history(customer, 1, 20, "ALL").meta()).isNotNull();

    when(reorderService.active(customer))
        .thenReturn(List.of(Map.of("status", "OUT_FOR_DELIVERY"), Map.of("status", "PACKING")));
    assertThat(orderController.active(customer).data()).hasSize(2);
  }

  @Test
  void orderPlacementControllersDelegate() {
    UUID cartId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(orderPlacement.placeOrder(customer, cartId, "COD", null, "note", "idem-1"))
        .thenReturn(Map.of("order_id", orderId, "status", "PENDING_ACCEPTANCE"));
    assertThat(
            orderController
                .place(
                    customer,
                    "idem-1",
                    new OrderController.PlaceOrderRequest(cartId, "COD", null, "note"))
                .data())
        .containsEntry("status", "PENDING_ACCEPTANCE");
    orderController.place(customer, "idem-2", null);
    verify(orderPlacement).placeOrder(customer, null, null, null, null, "idem-2");

    when(orderPlacement.getOrder(customer, orderId)).thenReturn(Map.of("id", orderId));
    assertThat(orderController.get(customer, orderId).data()).containsEntry("id", orderId);

    when(orderPlacement.confirmPayment(customer, orderId, "pay_1", "sig", "idem-c"))
        .thenReturn(Map.of("status", "PENDING_ACCEPTANCE"));
    orderPaymentController.confirm(
        customer,
        orderId,
        "idem-c",
        new OrderPaymentController.ConfirmPaymentRequest("pay_1", "sig"));
    orderPaymentController.confirm(customer, orderId, "idem-c2", null);
    verify(orderPlacement).confirmPayment(customer, orderId, null, null, "idem-c2");

    when(orderPlacement.collectCod(rider, orderId, 221.25))
        .thenReturn(Map.of("payment_status", "COLLECTED"));
    assertThat(
            orderPaymentController
                .codCollect(rider, orderId, new OrderPaymentController.CodCollectRequest(221.25))
                .data())
        .containsEntry("payment_status", "COLLECTED");
    orderPaymentController.codCollect(rider, orderId, null);
    verify(orderPlacement).collectCod(rider, orderId, null);

    when(razorpay.handleWebhook(eq("sig"), any())).thenReturn(Map.of("processed", true));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        com.nammamedmate.kernel.webhook.WebhookRawBodyFilter.CACHED_BODY_ATTR, "{}".getBytes());
    assertThat(webhookController.orderPayment("sig", request).success()).isTrue();

    when(orderLifecycle.tracking(customer, orderId))
        .thenReturn(Map.of("status", "OUT_FOR_DELIVERY"));
    assertThat(orderController.tracking(customer, orderId).data())
        .containsEntry("status", "OUT_FOR_DELIVERY");
    when(orderLifecycle.timeline(customer, orderId)).thenReturn(Map.of("events", List.of()));
    assertThat(orderController.timeline(customer, orderId).data()).containsKey("events");

    when(orderLifecycle.accept(pharmacyPrincipal, orderId))
        .thenReturn(Map.of("status", "ACCEPTED"));
    assertThat(pharmacyOrderLifecycleController.accept(pharmacyPrincipal, orderId).data())
        .containsEntry("status", "ACCEPTED");
    when(orderLifecycle.advancePharmacyStatus(
            eq(pharmacyPrincipal), eq(orderId), eq("PACKING"), any()))
        .thenReturn(Map.of("status", "PACKING"));
    pharmacyOrderLifecycleController.advance(
        pharmacyPrincipal,
        orderId,
        new PharmacyOrderLifecycleController.StatusRequest("PACKING", "n"));
    pharmacyOrderLifecycleController.advance(pharmacyPrincipal, orderId, null);
    verify(orderLifecycle).advancePharmacyStatus(pharmacyPrincipal, orderId, null, null);

    when(orderLifecycle.reject(eq(pharmacyPrincipal), eq(orderId), eq("OUT_OF_STOCK"), any()))
        .thenReturn(Map.of("status", "CANCELLED"));
    pharmacyOrderLifecycleController.reject(
        pharmacyPrincipal,
        orderId,
        new PharmacyOrderLifecycleController.RejectRequest("OUT_OF_STOCK", "msg"));
    pharmacyOrderLifecycleController.reject(pharmacyPrincipal, orderId, null);
    verify(orderLifecycle).reject(pharmacyPrincipal, orderId, null, null);

    UUID riderId = UUID.randomUUID();
    when(orderLifecycle.assignRider(pharmacyPrincipal, orderId, riderId))
        .thenReturn(Map.of("rider_id", riderId.toString()));
    pharmacyOrderLifecycleController.assignRider(
        pharmacyPrincipal,
        orderId,
        new PharmacyOrderLifecycleController.AssignRiderRequest(riderId));
    pharmacyOrderLifecycleController.assignRider(pharmacyPrincipal, orderId, null);
    verify(orderLifecycle).assignRider(pharmacyPrincipal, orderId, null);

    when(orderLifecycle.adminForceStatus(
            eq(admin), eq(orderId), eq("OUT_FOR_DELIVERY"), any(), any()))
        .thenReturn(Map.of("status", "OUT_FOR_DELIVERY"));
    adminOrderLifecycleController.forceStatus(
        admin,
        orderId,
        new AdminOrderLifecycleController.ForceStatusRequest("OUT_FOR_DELIVERY", "r", "n"));
    adminOrderLifecycleController.forceStatus(admin, orderId, null);
    verify(orderLifecycle).adminForceStatus(admin, orderId, null, null, null);

    when(orderCancellation.customerCancel(customer, orderId, "CHANGED_MIND"))
        .thenReturn(Map.of("status", "CANCELLED"));
    assertThat(
            orderController
                .cancel(customer, orderId, new OrderController.CancelOrderRequest("CHANGED_MIND"))
                .data())
        .containsEntry("status", "CANCELLED");
    orderController.cancel(customer, orderId, null);
    verify(orderCancellation).customerCancel(customer, orderId, null);

    when(orderCancellation.adminCancel(eq(admin), eq(orderId), eq("ops"), any(), eq("SOURCE")))
        .thenReturn(Map.of("status", "CANCELLED"));
    adminCancelRefundController.cancel(
        admin,
        orderId,
        new AdminOrderCancelRefundController.AdminCancelRequest("ops", 100, "SOURCE"));
    adminCancelRefundController.cancel(admin, orderId, null);
    verify(orderCancellation).adminCancel(admin, orderId, null, null, null);

    when(orderCancellation.adminRefund(
            eq(admin), eq(orderId), any(), eq("WALLET"), eq("partial"), any(), eq("idem-r")))
        .thenReturn(Map.of("status", "PROCESSED"));
    adminCancelRefundController.refund(
        admin,
        orderId,
        "idem-r",
        new AdminOrderCancelRefundController.AdminRefundRequest(50, "WALLET", "partial", "n"));
    adminCancelRefundController.refund(admin, orderId, "idem-r2", null);
    verify(orderCancellation).adminRefund(admin, orderId, null, null, null, null, "idem-r2");

    when(orderCancellation.refundEligibility(admin, orderId)).thenReturn(Map.of("eligible", true));
    assertThat(adminCancelRefundController.eligibility(admin, orderId).data())
        .containsEntry("eligible", true);

    when(adminOrderService.list(
            eq(admin), eq("ALL"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(new ListResult(Map.of("orders", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(
            adminOversightController
                .list(
                    admin, "ALL", null, null, null, null, null, null, null, null, null, null, null)
                .success())
        .isTrue();
    when(adminOrderService.list(
            eq(admin), eq("ALL"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), isNull(), isNull(), eq(true)))
        .thenReturn(new ListResult(Map.of("status", "READY"), null));
    assertThat(
            adminOversightController
                .list(
                    admin, "ALL", null, null, null, null, null, null, null, null, null, null, true)
                .meta())
        .isNull();

    when(adminOrderService.liveFeed(admin)).thenReturn(Map.of("total_live", 1));
    assertThat(adminOversightController.liveFeed(admin).data()).containsEntry("total_live", 1);
    when(adminOrderService.detail(admin, orderId))
        .thenReturn(Map.of("order_id", orderId.toString()));
    assertThat(adminOversightController.detail(admin, orderId).data())
        .containsEntry("order_id", orderId.toString());

    UUID riderId2 = UUID.randomUUID();
    when(adminOrderService.reassignRider(admin, orderId, riderId2, "r"))
        .thenReturn(Map.of("new_rider_id", riderId2.toString()));
    adminOversightController.reassignRider(
        admin, orderId, new AdminOrderOversightController.ReassignRiderRequest(riderId2, "r"));
    adminOversightController.reassignRider(admin, orderId, null);
    verify(adminOrderService).reassignRider(admin, orderId, null, null);

    when(adminOrderService.flagDispute(admin, orderId, "reason", "RIDER"))
        .thenReturn(Map.of("is_disputed", true));
    adminOversightController.flagDispute(
        admin, orderId, new AdminOrderOversightController.DisputeRequest("reason", "RIDER"));
    adminOversightController.flagDispute(admin, orderId, null);
    verify(adminOrderService).flagDispute(admin, orderId, null, null);

    when(adminOrderService.addNote(admin, orderId, "n", true)).thenReturn(Map.of("note_id", "x"));
    adminOversightController.addNote(
        admin, orderId, new AdminOrderOversightController.NoteRequest("n", true));
    adminOversightController.addNote(admin, orderId, null);
    verify(adminOrderService).addNote(admin, orderId, null, null);

    UUID noteId = UUID.randomUUID();
    assertThat(adminOversightController.deleteNote(admin, orderId, noteId).getStatusCode().value())
        .isEqualTo(405);
    verify(adminOrderService).requireNoteDeleteDenied(admin);
  }
}
