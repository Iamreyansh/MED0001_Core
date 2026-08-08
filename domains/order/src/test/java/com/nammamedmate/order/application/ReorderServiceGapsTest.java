package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
import com.nammamedmate.order.domain.Cart;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReorderServiceGapsTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111199");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000091");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000092");
  private static final UUID MED1 = UUID.fromString("22222222-2222-4222-8222-222222222291");
  private static final UUID MED2 = UUID.fromString("22222222-2222-4222-8222-222222222292");
  private static final UUID ADDR = UUID.fromString("33333333-3333-4333-8333-333333333399");
  private static final UUID ORDER_ID = UUID.fromString("44444444-4444-4444-8444-444444444499");
  private static final UUID CART_ID = UUID.fromString("55555555-5555-4555-8555-555555555599");
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

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.935, 77.613)));
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
            Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void originalMissingConfirmAndAllBannedEmptyCart() {
    Order order = order(List.of(item(MED1, "A", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    PharmacyRow fallback =
        new PharmacyRow(
            PH2, "Apollo", null, "a", null, null, 12.936, 77.614, true, false, "ACTIVE", 4.0, 1,
            80.0, 10.0);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(fallback));
    stubStock(PH2, MED1, 5, 1000);
    stubMedicine(MED1, "A", false);

    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_CHANGE_REQUIRED");

    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(cart(PH2));
    Map<String, Object> ok = service.reorder(customer, ORDER_ID, true);
    @SuppressWarnings("unchecked")
    Map<String, Object> ph = (Map<String, Object>) ok.get("pharmacy");
    assertThat(ph.get("note").toString()).contains("Original pharmacy");

    // all banned → same pharmacy ok by stock, then toAdd empty
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(open(PH1, "Sai")));
    stubStock(PH1, MED1, 5, 1000);
    when(inventory.findMedicine(MED1))
        .thenReturn(Optional.of(new MedicineDetails(MED1, "A", null, null, false, null, true)));
    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_ITEMS_AVAILABLE");
  }

  @Test
  void openButPartialFulfillNoteAndScoredEmptyAndClosedCandidate() {
    Order order = order(List.of(item(MED1, "A", 1, false), item(MED2, "B", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    PharmacyRow original = open(PH1, "Sai");
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(original));
    stubStock(PH1, MED1, 5, 1000);
    stubStock(PH1, MED2, 0, 1000);
    PharmacyRow closedNear = closed(PH2, "Closed");
    PharmacyRow noGeo =
        new PharmacyRow(
            UUID.randomUUID(),
            "NoGeo",
            "x",
            "a",
            null,
            null,
            null,
            null,
            true,
            false,
            "ACTIVE",
            4.0,
            1,
            80.0,
            10.0);
    PharmacyRow lngMissing =
        new PharmacyRow(
            UUID.randomUUID(),
            "LngMissing",
            "x",
            "a",
            null,
            null,
            12.935,
            null,
            true,
            false,
            "ACTIVE",
            4.0,
            1,
            80.0,
            10.0);
    PharmacyRow fallback = open(PH2, "Apollo");
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(closedNear, noGeo, lngMissing, fallback));
    stubStock(PH2, MED1, 5, 1000);
    stubStock(PH2, MED2, 5, 1000);
    stubMedicine(MED1, "A", false);
    stubMedicine(MED2, "B", false);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(cart(PH2));

    Map<String, Object> res = service.reorder(customer, ORDER_ID, true);
    @SuppressWarnings("unchecked")
    Map<String, Object> ph = (Map<String, Object>) res.get("pharmacy");
    assertThat(ph.get("note").toString()).contains("could not fulfill");
    assertThat(res.get("message").toString()).contains("items");

    // only no-geo candidates → scored empty → PHARMACY_UNAVAILABLE → NO_ITEMS
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(noGeo));
    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_ITEMS_AVAILABLE");
  }

  @Test
  void historyActiveEdgeBranches() {
    Order cancelled =
        new Order(
            ORDER_ID,
            "ORD-C",
            CUST,
            PH1,
            CART_ID,
            List.of(
                item(MED1, "A", 2, false), item(MED2, "B", 1, false), item(MED1, "C", 1, false)),
            3000,
            null,
            0,
            0,
            0,
            0,
            3000,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
            null,
            null,
            null,
            ADDR,
            null,
            OrderStatus.CANCELLED,
            null,
            null,
            null,
            T0,
            null,
            T0,
            T0,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null);
    when(orders.countCustomerHistory(eq(CUST), any())).thenReturn(1L);
    when(orders.listCustomerHistory(eq(CUST), any(), anyInt(), anyInt()))
        .thenReturn(List.of(cancelled));
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    assertThat(service.history(customer, 0, 100, " ").data().getFirst().get("delivered_at"))
        .isNull();

    UUID riderId = UUID.randomUUID();
    Order active =
        new Order(
            UUID.randomUUID(),
            "ORD-A",
            CUST,
            PH1,
            CART_ID,
            List.of(item(MED1, "A", 1, false)),
            1000,
            null,
            0,
            0,
            0,
            0,
            1000,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            null,
            null,
            null,
            ADDR,
            null,
            OrderStatus.OUT_FOR_DELIVERY,
            riderId,
            null,
            null,
            T0,
            null,
            T0,
            T0);
    when(orders.listCustomerActive(CUST)).thenReturn(List.of(active));
    when(riders.findById(riderId)).thenReturn(Optional.empty());
    assertThat(service.active(customer).getFirst().get("rider")).isNull();

    assertThatThrownBy(() -> service.reorder(null, ORDER_ID, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThat(service.history(customer, 1, 20, null).data()).hasSize(1);
    assertThat(service.history(customer, 1, 20, "DELIVERED").data()).hasSize(1);

    // insufficient qty + OOS on original; fallback adds 1 excludes 2 → "s were"
    UUID med3 = UUID.fromString("22222222-2222-4222-8222-222222222293");
    Order multi =
        order(
            List.of(
                item(MED1, "A", 5, false),
                item(MED2, "B", 1, false),
                new OrderItemSnapshot(med3, "C", 1, 1000, 1000, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(multi));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(open(PH1, "Sai")));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED1))))
        .thenReturn(List.of(new StockLine(MED1, "A", 2, 1000, 1000, true, null)));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED2))))
        .thenReturn(List.of(new StockLine(MED2, "B", 0, 1000, 1000, false, "OUT_OF_STOCK")));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(med3))))
        .thenReturn(List.of(new StockLine(med3, "C", 5, 1000, 1000, true, null)));
    PharmacyRow onlyOne = open(PH2, "Apollo");
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(onlyOne));
    when(inventory.checkAvailability(eq(PH2), eq(List.of(MED1))))
        .thenReturn(List.of(new StockLine(MED1, "A", 5, 1000, 1000, true, null)));
    when(inventory.checkAvailability(eq(PH2), eq(List.of(MED2))))
        .thenReturn(List.of(new StockLine(MED2, "B", 0, 1000, 1000, false, "OUT_OF_STOCK")));
    when(inventory.checkAvailability(eq(PH2), eq(List.of(med3))))
        .thenReturn(List.of(new StockLine(med3, "C", 0, 1000, 1000, true, null)));
    stubMedicine(MED1, "A", false);
    stubMedicine(MED2, "B", false);
    when(inventory.findMedicine(med3))
        .thenReturn(Optional.of(new MedicineDetails(med3, "C", null, null, false, null, false)));
    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(cart(PH2));
    Map<String, Object> partial = service.reorder(customer, ORDER_ID, true);
    assertThat(partial.get("message").toString()).contains("were unavailable");

    // null delivery address → default address
    Order noAddr =
        new Order(
            ORDER_ID,
            "ORD-NA",
            CUST,
            PH1,
            CART_ID,
            List.of(item(MED1, "A", 1, false)),
            1000,
            null,
            0,
            0,
            0,
            0,
            1000,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            null,
            null,
            null,
            null,
            null,
            OrderStatus.DELIVERED,
            null,
            null,
            null,
            T0,
            T0,
            T0,
            T0);
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(noAddr));
    when(addresses.findDefault(CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.935, 77.613)));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(open(PH1, "Sai")));
    stubStock(PH1, MED1, 5, 1000);
    stubMedicine(MED1, "A", false);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH1), isNull(), anyList()))
        .thenReturn(cart(PH1));
    assertThat(service.reorder(customer, ORDER_ID, false).get("cart_id")).isEqualTo(CART_ID);
  }

  @Test
  void emptyStockLinesAndNullProductIdOnSamePharmacyCheck() {
    Order order = order(List.of(item(MED1, "A", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(open(PH1, "Sai")));
    when(inventory.checkAvailability(eq(PH1), eq(List.of(MED1)))).thenReturn(List.of());
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(open(PH2, "Apollo")));
    when(inventory.checkAvailability(eq(PH2), eq(List.of(MED1))))
        .thenReturn(List.of(new StockLine(MED1, "A", 5, 1000, 1000, true, null)));
    stubMedicine(MED1, "A", false);
    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(cart(PH2));

    Map<String, Object> res = service.reorder(customer, ORDER_ID, true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> added = (List<Map<String, Object>>) res.get("items_added");
    assertThat(added).hasSize(1);

    // null productId forces allItemsInStock false while original is open
    Order ghost = order(List.of(new OrderItemSnapshot(null, "Ghost", 1, 1000, 1000, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(ghost));
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_ITEMS_AVAILABLE");
  }

  @Test
  void openOriginalCannotFulfillNeedsConfirmAndScoreTieBreak() {
    Order order = order(List.of(item(MED1, "A", 1, false)));
    when(orders.findByCustomerAndId(CUST, ORDER_ID)).thenReturn(Optional.of(order));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(open(PH1, "Sai")));
    stubStock(PH1, MED1, 0, 1000);

    PharmacyRow lowScore =
        new PharmacyRow(
            UUID.randomUUID(),
            "Low",
            "A",
            "a",
            null,
            null,
            12.940,
            77.620,
            true,
            false,
            "ACTIVE",
            1.0,
            1,
            10.0,
            30.0);
    PharmacyRow highScore =
        new PharmacyRow(
            PH2, "High", "B", "a", null, null, 12.9351, 77.6131, true, false, "ACTIVE", 5.0, 1,
            99.0, 5.0);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(lowScore, highScore));
    stubStock(lowScore.id(), MED1, 5, 1000);
    stubStock(PH2, MED1, 5, 1000);
    stubMedicine(MED1, "A", false);

    assertThatThrownBy(() -> service.reorder(customer, ORDER_ID, false))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("PHARMACY_CHANGE_REQUIRED");
              assertThat(ae.getMessage()).contains("cannot fulfill");
            });

    when(cartService.createActiveForReorder(eq(CUST), eq(PH2), eq(ADDR), anyList()))
        .thenReturn(cart(PH2));
    Map<String, Object> res = service.reorder(customer, ORDER_ID, true);
    @SuppressWarnings("unchecked")
    Map<String, Object> ph = (Map<String, Object>) res.get("pharmacy");
    assertThat(ph.get("id")).isEqualTo(PH2);
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

  private Cart cart(UUID pharmacyId) {
    return new Cart(
        CART_ID, CUST, pharmacyId, List.of(), null, 0L, null, ADDR, CartStatus.ACTIVE, T0, T0);
  }

  private Order order(List<OrderItemSnapshot> items) {
    return new Order(
        ORDER_ID,
        "ORD-G",
        CUST,
        PH1,
        CART_ID,
        items,
        1000,
        null,
        0,
        0,
        0,
        0,
        1000,
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
        T0,
        T0,
        T0,
        T0,
        null,
        T0,
        null,
        false,
        null,
        null,
        null,
        null,
        null);
  }

  private static OrderItemSnapshot item(UUID id, String name, int qty, boolean rx) {
    return new OrderItemSnapshot(id, name, qty, 1000, 1000L * qty, rx);
  }

  private static PharmacyRow open(UUID id, String name) {
    return new PharmacyRow(
        id,
        name,
        "Koramangala",
        "a",
        null,
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

  private static PharmacyRow closed(UUID id, String name) {
    return new PharmacyRow(
        id,
        name,
        "Koramangala",
        "a",
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
