package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.order.application.ReorderService.HistoryResult;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.MedicineDetails;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.ReorderAttemptLogStore;
import com.nammamedmate.order.application.port.out.RiderLookupPort;
import com.nammamedmate.order.application.port.out.RiderLookupPort.RiderInfo;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartStatus;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReorderServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000002");
  private static final UUID MED1 = UUID.fromString("22222222-2222-4222-8222-222222222221");
  private static final UUID MED2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID MED3 = UUID.fromString("22222222-2222-4222-8222-222222222223");
  private static final UUID ADDR = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ORDER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CART_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final Instant T0 = Instant.parse("2026-08-08T12:00:00Z");

  @Mock private OrderStore orders;
  @Mock private CartService cartService;
  @Mock private InventoryAvailabilityPort inventory;
  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private CustomerAddressPort addresses;
  @Mock private RiderLookupPort riders;
  @Mock private ReorderAttemptLogStore reorderLogs;
  @Mock private RateLimiter rateLimiter;

  private ReorderService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "Koramangala", 12.935, 77.613)));
    service =
        new ReorderService(
            orders,
            cartService,
            inventory,
            pharmacies,
            addresses,
            riders,
            reorderLogs,
            rateLimiter,
            clock);
  }

  @Test
  void ac1_samePharmacyAllInStock() {
    Order order = deliveredOrder(List.of(item(MED1, "Metformin 500mg", 3, true)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy(PH1, "Sai Medicals")));
    stubStock(PH1, MED1, 10, 8500);
    stubMedicine(MED1, "Metformin 500mg", true);
    Cart cart = emptyCart(PH1);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH1), eq(ADDR), anyList()))
        .thenReturn(cart);

    Map<String, Object> res = service.reorder(customer, ORDER_ID, false);
    assertThat(res.get("cart_id")).isEqualTo(CART_ID);
    assertThat(res.get("prescription_required")).isEqualTo(true);
    assertThat(res.get("prescription_attached")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> added = (List<Map<String, Object>>) res.get("items_added");
    assertThat(added).hasSize(1);
    assertThat(added.getFirst().get("quantity")).isEqualTo(3);
    verify(cartService).createActiveForReorder(eq(CUST), eq(PH1), eq(ADDR), anyList());
    verify(reorderLogs).insert(any());
  }

  @Test
  void ac2_pharmacyChangeRequiresConfirm() {
    Order order = deliveredOrder(List.of(item(MED1, "Metformin 500mg", 3, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    PharmacyRow closed = closedPharmacy(PH1, "Sai Medicals");
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(closed));
    PharmacyRow fallback = openPharmacy(PH2, "Apollo Pharmacy");
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(fallback));
    stubStock(PH2, MED1, 10, 9000);
    stubMedicine(MED1, "Metformin 500mg", false);

    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, false))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("PHARMACY_CHANGE_REQUIRED");
              assertThat(ae.httpStatus()).isEqualTo(409);
              assertThat(ae.details()).containsKey("suggested_pharmacy");
            });
    verify(cartService, never()).createActiveForReorder(any(), any(), any(), any());
  }

  @Test
  void ac3_confirmFallbackCreatesCartWithNote() {
    Order order = deliveredOrder(List.of(item(MED1, "Metformin 500mg", 3, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(closedPharmacy(PH1, "Sai Medicals")));
    PharmacyRow fallback = openPharmacy(PH2, "Apollo Pharmacy");
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(fallback));
    stubStock(PH2, MED1, 10, 9000);
    stubMedicine(MED1, "Metformin 500mg", false);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(emptyCart(PH2));

    Map<String, Object> res = service.reorder(customer, ORDER_ID, true);
    @SuppressWarnings("unchecked")
    Map<String, Object> ph = (Map<String, Object>) res.get("pharmacy");
    assertThat(ph.get("id")).isEqualTo(PH2);
    assertThat(ph.get("note").toString()).contains("closed");
  }

  @Test
  void ac4_rxItemsNotReattached() {
    Order order = deliveredOrder(List.of(item(MED1, "Metformin 500mg", 1, true)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy(PH1, "Sai")));
    stubStock(PH1, MED1, 5, 1000);
    stubMedicine(MED1, "Metformin 500mg", true);
    ArgumentCaptor<List<CartItem>> itemsCap = ArgumentCaptor.forClass(List.class);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH1), eq(ADDR), itemsCap.capture()))
        .thenReturn(emptyCart(PH1));

    Map<String, Object> res = service.reorder(customer, ORDER_ID, null);
    assertThat(res.get("prescription_required")).isEqualTo(true);
    assertThat(res.get("prescription_attached")).isEqualTo(false);
    verify(cartService).createActiveForReorder(eq(CUST), eq(PH1), eq(ADDR), anyList());
  }

  @Test
  void ac5_noItemsAvailable() {
    Order order = deliveredOrder(List.of(item(MED1, "Metformin 500mg", 3, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(closedPharmacy(PH1, "Sai")));
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());

    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, true))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_ITEMS_AVAILABLE");
  }

  @Test
  void ac6_partialExcludeOneItem() {
    Order order =
        deliveredOrder(
            List.of(
                item(MED1, "A", 1, false),
                item(MED2, "B", 1, false),
                item(MED3, "Glipizide 5mg", 30, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(closedPharmacy(PH1, "Sai")));
    PharmacyRow fallback = openPharmacy(PH2, "Apollo");
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(fallback));
    stubStock(PH2, MED1, 10, 1000);
    stubStock(PH2, MED2, 10, 1000);
    stubStock(PH2, MED3, 0, 1000);
    stubMedicine(MED1, "A", false);
    stubMedicine(MED2, "B", false);
    stubMedicine(MED3, "Glipizide 5mg", false);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(emptyCart(PH2));

    Map<String, Object> res = service.reorder(customer, ORDER_ID, true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> added = (List<Map<String, Object>>) res.get("items_added");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> excluded = (List<Map<String, Object>>) res.get("excluded_items");
    assertThat(added).hasSize(2);
    assertThat(excluded).hasSize(1);
    assertThat(excluded.getFirst().get("reason")).isEqualTo("OUT_OF_STOCK");
  }

  @Test
  void ac7_historyOnlyTerminal() {
    Order delivered = deliveredOrder(List.of(item(MED1, "A", 1, false)));
    when(orders.countCustomerHistory(CUST, "ALL")).thenReturn(1L);
    when(orders.listCustomerHistory(CUST, "ALL", 0, 20)).thenReturn(List.of(delivered));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy(PH1, "Sai Medicals")));

    HistoryResult result = service.history(customer, 1, 20, "ALL");
    assertThat(result.data()).hasSize(1);
    assertThat(result.data().getFirst().get("status")).isEqualTo("DELIVERED");
    assertThat(result.meta().total()).isEqualTo(1);
  }

  @Test
  void ac8_activeReturnsBoth() {
    Order a = activeOrder(OrderStatus.OUT_FOR_DELIVERY, UUID.randomUUID());
    Order b = activeOrder(OrderStatus.PACKING, null);
    when(orders.listCustomerActive(CUST)).thenReturn(List.of(a, b));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy(PH1, "Sai")));
    when(riders.findById(any()))
        .thenReturn(Optional.of(new RiderInfo(UUID.randomUUID(), "Suresh", "+91-9", "KA01", null)));

    List<Map<String, Object>> rows = service.active(customer);
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().get("rider")).isNotNull();
    assertThat(rows.get(1).get("rider")).isNull();
  }

  @Test
  void orderNotFoundAndValidationPaths() {
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_NOT_FOUND");
    assertThatThrownBy(() -> service.reorder(customer, null, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_NOT_FOUND");

    MedmatePrincipal admin =
        new MedmatePrincipal(CUST, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.reorder(admin, ORDER_ID, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.history(customer, 1, 20, "ALL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void historyStatusFilterAndInvalid() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(orders.countCustomerHistory(CUST, "CANCELLED")).thenReturn(0L);
    when(orders.listCustomerHistory(CUST, "CANCELLED", 0, 20)).thenReturn(List.of());
    assertThat(service.history(customer, null, null, "cancelled").data()).isEmpty();

    assertThatThrownBy(() -> service.history(customer, 1, 20, "PACKING"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void samePharmacyPartialUsesFallbackWithoutChangeWhenSameIdWins() {
    Order order = deliveredOrder(List.of(item(MED1, "A", 1, false), item(MED2, "B", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    PharmacyRow original = openPharmacy(PH1, "Sai");
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(original));
    stubStock(PH1, MED1, 10, 1000);
    stubStock(PH1, MED2, 0, 1000);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(original));
    stubMedicine(MED1, "A", false);
    stubMedicine(MED2, "B", false);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH1), eq(ADDR), anyList()))
        .thenReturn(emptyCart(PH1));

    Map<String, Object> res = service.reorder(customer, ORDER_ID, false);
    @SuppressWarnings("unchecked")
    Map<String, Object> ph = (Map<String, Object>) res.get("pharmacy");
    assertThat(ph.get("note")).isNull();
    @SuppressWarnings("unchecked")
    List<?> excluded = (List<?>) res.get("excluded_items");
    assertThat(excluded).hasSize(1);
  }

  @Test
  void emptyOrderItemsAndMissingAddress() {
    Order empty = deliveredOrder(List.of());
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(empty));
    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_ITEMS_AVAILABLE");

    Order order = deliveredOrder(List.of(item(MED1, "A", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    when(addresses.findDefault(CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void defaultAddressFallbackAndBannedExcluded() {
    Order order = deliveredOrder(List.of(item(MED1, "A", 1, false), item(MED2, "B", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    when(addresses.findDefault(CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.935, 77.613)));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(openPharmacy(PH1, "Sai")));
    stubStock(PH1, MED1, 10, 1000);
    stubStock(PH1, MED2, 10, 1000);
    when(inventory.findMedicine(MED1))
        .thenReturn(Optional.of(new MedicineDetails(MED1, "A", null, null, false, null, false)));
    when(inventory.findMedicine(MED2))
        .thenReturn(Optional.of(new MedicineDetails(MED2, "B", null, null, false, null, true)));
    when(cartService.createActiveForReorder(eq(CUST), eq(PH1), eq(ADDR), anyList()))
        .thenReturn(emptyCart(PH1));

    // not all in stock path because banned fails allItemsInStock? banned still has stock line
    // inStock — allItemsInStock only checks stock qty, not banned. Then buildCartItems excludes
    // banned when samePharmacyOk. Force same-pharmacy by making both in stock first:
    Map<String, Object> res = service.reorder(customer, ORDER_ID, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> excluded = (List<Map<String, Object>>) res.get("excluded_items");
    // If same pharmacy OK (both stocked), banned excluded in buildCartItems
    assertThat(excluded).isNotEmpty();
  }

  @Test
  void nullProductIdAndPharmacyMissingLatSkipped() {
    Order order =
        deliveredOrder(
            List.of(
                new OrderItemSnapshot(null, "Ghost", 1, 1000, 1000, false),
                item(MED1, "A", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(closedPharmacy(PH1, "Sai")));
    PharmacyRow noCoords =
        new PharmacyRow(
            PH2, "Apollo", "BTM", "a", null, null, null, null, true, false, "ACTIVE", 4.0, 1, 80.0,
            10.0);
    PharmacyRow ok = openPharmacy(PH2, "Apollo");
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(noCoords, ok));
    stubStock(PH2, MED1, 5, 1000);
    stubMedicine(MED1, "A", false);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(emptyCart(PH2));

    Map<String, Object> res = service.reorder(customer, ORDER_ID, true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> excluded = (List<Map<String, Object>>) res.get("excluded_items");
    assertThat(excluded.stream().anyMatch(e -> "Ghost".equals(e.get("name")))).isTrue();
  }

  @Test
  void openPharmaciesButZeroFillUsesOutOfStock() {
    Order order = deliveredOrder(List.of(item(MED1, "A", 5, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(closedPharmacy(PH1, "Sai")));
    PharmacyRow open = openPharmacy(PH2, "Apollo");
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(open));
    stubStock(PH2, MED1, 0, 1000);
    stubMedicine(MED1, "A", false);

    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_ITEMS_AVAILABLE");
  }

  private void stubStock(UUID pharmacyId, UUID medId, int qty, long price) {
    when(inventory.checkAvailability(eq(pharmacyId), eq(List.of(medId))))
        .thenReturn(
            List.of(
                new StockLine(
                    medId, "n", qty, price, price, qty > 0, qty > 0 ? null : "OUT_OF_STOCK")));
  }

  private void stubMedicine(UUID medId, String name, boolean rx) {
    when(inventory.findMedicine(medId))
        .thenReturn(Optional.of(new MedicineDetails(medId, name, null, null, rx, null, false)));
  }

  private Cart emptyCart(UUID pharmacyId) {
    return new Cart(
        CART_ID, CUST, pharmacyId, List.of(), null, 0L, null, ADDR, CartStatus.ACTIVE, T0, T0);
  }

  private Order deliveredOrder(List<OrderItemSnapshot> items) {
    long total = items.stream().mapToLong(OrderItemSnapshot::lineTotalPaise).sum();
    return new Order(
        ORDER_ID,
        "ORD-20260808-00001",
        CUST,
        PH1,
        CART_ID,
        items,
        total,
        null,
        0,
        2500,
        500,
        0,
        total + 3000,
        PaymentMethod.UPI,
        PaymentStatus.PAID,
        null,
        null,
        null,
        ADDR,
        null,
        OrderStatus.DELIVERED,
        null,
        null,
        null,
        T0.minusSeconds(3600),
        T0.minusSeconds(1800),
        T0.minusSeconds(3600),
        T0.minusSeconds(1800),
        T0.minusSeconds(3000),
        T0.minusSeconds(1800),
        T0.minusSeconds(1800),
        false,
        null,
        null,
        null,
        null,
        null);
  }

  private Order activeOrder(OrderStatus status, UUID riderId) {
    return new Order(
        UUID.randomUUID(),
        "ORD-ACTIVE",
        CUST,
        PH1,
        CART_ID,
        List.of(item(MED1, "A", 1, false)),
        1000,
        null,
        0,
        2500,
        500,
        0,
        4000,
        PaymentMethod.COD,
        PaymentStatus.PENDING_COLLECTION,
        null,
        null,
        null,
        ADDR,
        null,
        status,
        riderId,
        null,
        null,
        T0.minusSeconds(600),
        T0.plusSeconds(600),
        T0.minusSeconds(600),
        T0,
        T0.minusSeconds(500),
        null,
        T0.plusSeconds(1200),
        false,
        riderId == null ? null : T0.minusSeconds(100),
        null,
        null,
        null,
        null);
  }

  private static OrderItemSnapshot item(UUID id, String name, int qty, boolean rx) {
    return new OrderItemSnapshot(id, name, qty, 1000, 1000L * qty, rx);
  }

  private static PharmacyRow openPharmacy(UUID id, String name) {
    return new PharmacyRow(
        id,
        name,
        "Koramangala",
        "addr",
        "https://cdn/logo.png",
        null,
        12.935,
        77.613,
        true,
        false,
        "ACTIVE",
        4.5,
        10,
        90.0,
        10.0);
  }

  private static PharmacyRow closedPharmacy(UUID id, String name) {
    return new PharmacyRow(
        id,
        name,
        "Koramangala",
        "addr",
        null,
        null,
        12.935,
        77.613,
        false,
        false,
        "ACTIVE",
        4.5,
        10,
        90.0,
        10.0);
  }
}
