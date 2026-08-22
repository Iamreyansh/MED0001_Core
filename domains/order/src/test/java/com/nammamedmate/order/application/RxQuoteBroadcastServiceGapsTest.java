package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.PrescriptionPort.MedicineLine;
import com.nammamedmate.order.application.port.out.PrescriptionPort.PrescriptionDetail;
import com.nammamedmate.order.application.port.out.RxBroadcastStore;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.QuotedMedicine;
import com.nammamedmate.order.domain.RxBroadcast;
import com.nammamedmate.order.domain.RxBroadcast.RequestedMedicine;
import com.nammamedmate.order.domain.RxBroadcastPharmacy;
import com.nammamedmate.order.domain.RxBroadcastStatus;
import com.nammamedmate.order.domain.RxPharmacySlotStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
class RxQuoteBroadcastServiceGapsTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ADDR = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID RX = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
  private static final UUID PROD = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final Instant T0 = Instant.parse("2026-08-08T10:00:00Z");

  @Mock private RxBroadcastStore store;
  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private CustomerAddressPort addresses;
  @Mock private PrescriptionPort prescriptions;
  @Mock private CartService cartService;
  @Mock private RateLimiter rateLimiter;

  private RxQuoteBroadcastService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal pharmacyOwner =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PH1, TokenScope.FULL, "j");
  private final MedmatePrincipal pharmacyStaff =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PH1, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service =
        new RxQuoteBroadcastService(
            store,
            pharmacies,
            addresses,
            prescriptions,
            cartService,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void remainingBranchCoverage() {
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                RxQuoteBroadcastService.requireCustomer(
                    new MedmatePrincipal(CUST, AuthRole.PHARMACY_OWNER, PH1, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> RxQuoteBroadcastService.requirePharmacy(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    UUID bc = UUID.randomUUID();
    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.of(active(bc)));
    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.QUOTED,
                    null,
                    10,
                    1L,
                    T0,
                    T0.plusSeconds(900),
                    T0,
                    T0.plusSeconds(1200),
                    List.of())));
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_NOT_FOUND");

    when(store.findById(bc)).thenReturn(Optional.of(active(bc)));
    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0.minusSeconds(1000),
                    T0.minusSeconds(1),
                    null,
                    null,
                    List.of())));
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_EXPIRED");
    assertThatThrownBy(() -> service.decline(pharmacyOwner, bc, "OUT_OF_STOCK"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_EXPIRED");

    when(store.findById(bc))
        .thenReturn(
            Optional.of(
                new RxBroadcast(
                    bc,
                    CUST,
                    RX,
                    ADDR,
                    "Ravi",
                    null,
                    List.of(),
                    RxBroadcastStatus.ACTIVE,
                    1,
                    T0.minusSeconds(2000),
                    T0.minusSeconds(1),
                    null,
                    null,
                    T0)));
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_EXPIRED");

    when(store.findById(bc)).thenReturn(Optional.of(active(bc)));
    UUID slotId = UUID.randomUUID();
    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    slotId,
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    assertThat(service.decline(pharmacyOwner, bc, "OUT_OF_STOCK"))
        .containsEntry("reason", "OUT_OF_STOCK");
    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    assertThat(service.decline(pharmacyOwner, bc, "   ")).doesNotContainKey("reason");

    Map<String, Object> missingPrice = new HashMap<>();
    missingPrice.put("name", "A");
    missingPrice.put("qty", 1);
    assertThatThrownBy(() -> service.submitQuote(pharmacyOwner, bc, List.of(missingPrice), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> missingName = new HashMap<>();
    missingName.put("qty", 1);
    missingName.put("price", 1);
    assertThatThrownBy(() -> service.submitQuote(pharmacyOwner, bc, List.of(missingName), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> missingQty = new HashMap<>();
    missingQty.put("name", "A");
    missingQty.put("price", 1);
    assertThatThrownBy(() -> service.submitQuote(pharmacyOwner, bc, List.of(missingQty), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // can_view false with a QUOTED slot (1 quote, 0 minutes elapsed)
    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.of(active(bc)));
    when(store.listPharmacies(bc))
        .thenReturn(
            List.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1.0,
                    RxPharmacySlotStatus.QUOTED,
                    List.of(new QuotedMedicine("A", 1, 1000)),
                    10,
                    3500L,
                    T0,
                    T0.plusSeconds(900),
                    T0,
                    T0.plusSeconds(1200),
                    List.of()),
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    UUID.randomUUID(),
                    2.0,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    when(pharmacies.findById(any())).thenReturn(Optional.of(pharmacy(PH1, "Sai", 12.9, 77.6)));
    assertThat(service.getBroadcast(customer, bc).get("can_view_quotes")).isEqualTo(false);
    assertThat(service.listQuotes(customer, bc)).isEmpty();

    PharmacyRow onlyLat = pharmacy(UUID.randomUUID(), "LatOnly", 12.9345, null);
    when(prescriptions.findForBroadcast(RX, CUST))
        .thenReturn(
            Optional.of(
                new PrescriptionDetail(RX, "VERIFIED", false, List.of(new MedicineLine("A", 1)))));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.9345, 77.6125)));
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), eq(3.0))).thenReturn(List.of(onlyLat));
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, "Ravi", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NO_PHARMACIES_NEARBY");
  }

  @Test
  void broadcastSkipsNullCoordsFarPharmaciesAndMissingPharmacyLookup() {
    when(prescriptions.findForBroadcast(RX, CUST))
        .thenReturn(
            Optional.of(
                new PrescriptionDetail(RX, "VERIFIED", false, List.of(new MedicineLine("A", 1)))));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.9345, 77.6125)));
    PharmacyRow near = pharmacy(PH1, "Sai", 12.9350, 77.6130);
    PharmacyRow noCoords = pharmacy(UUID.randomUUID(), "NoGeo", null, null);
    PharmacyRow far = pharmacy(UUID.randomUUID(), "Far", 13.5, 78.5);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), eq(3.0)))
        .thenReturn(List.of(near, noCoords, far));
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());

    Map<String, Object> data = service.broadcast(customer, RX, ADDR, "Ravi", "  ");
    assertThat(data.get("pharmacies_notified")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("pharmacies");
    assertThat(rows.getFirst().get("name")).isNull();
  }

  @Test
  void getBroadcastQuoteSummaryNullBranches() {
    UUID bc = UUID.randomUUID();
    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.of(active(bc)));
    RxBroadcastPharmacy quotedNullMeds =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            PH1,
            1.0,
            RxPharmacySlotStatus.QUOTED,
            null,
            10,
            null,
            T0,
            T0.plusSeconds(900),
            T0,
            null,
            List.of());
    when(store.listPharmacies(bc))
        .thenReturn(
            List.of(
                quotedNullMeds,
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    UUID.randomUUID(),
                    2.0,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    when(pharmacies.findById(any())).thenReturn(Optional.empty());

    // 2 quotes? only 1 QUOTED — still can_view false at T0; force 2 quoted count via duplicate
    RxBroadcastPharmacy q2 =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            UUID.randomUUID(),
            1.5,
            RxPharmacySlotStatus.QUOTED,
            List.of(new QuotedMedicine("A", 1, 1000)),
            null,
            null,
            T0,
            T0.plusSeconds(900),
            null,
            null,
            List.of());
    RxBroadcastPharmacy notified =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            UUID.randomUUID(),
            2.2,
            RxPharmacySlotStatus.NOTIFIED,
            null,
            null,
            null,
            T0,
            T0.plusSeconds(900),
            null,
            null,
            List.of());
    when(store.listPharmacies(bc)).thenReturn(List.of(quotedNullMeds, q2, notified));
    Map<String, Object> status = service.getBroadcast(customer, bc);
    assertThat(status.get("can_view_quotes")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pharmacies = (List<Map<String, Object>>) status.get("pharmacies");
    assertThat(pharmacies).hasSize(3);
    assertThat(pharmacies.get(2).get("quote")).isNull();
    assertThat(service.listQuotes(customer, bc)).hasSize(2);
  }

  @Test
  void selectQuoteErrorPaths() {
    UUID bc = UUID.randomUUID();
    assertThatThrownBy(() -> service.selectQuote(customer, bc, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findByIdForCustomer(bc, CUST))
        .thenReturn(
            Optional.of(
                new RxBroadcast(
                    bc,
                    CUST,
                    RX,
                    ADDR,
                    "Ravi",
                    null,
                    List.of(),
                    RxBroadcastStatus.ACTIVE,
                    1,
                    T0.minusSeconds(2000),
                    T0.minusSeconds(1),
                    null,
                    null,
                    T0)));
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_EXPIRED");

    when(store.findByIdForCustomer(bc, CUST))
        .thenReturn(
            Optional.of(
                new RxBroadcast(
                    bc,
                    CUST,
                    RX,
                    ADDR,
                    "Ravi",
                    null,
                    List.of(),
                    RxBroadcastStatus.SELECTED,
                    1,
                    T0,
                    T0.plusSeconds(1800),
                    PH1,
                    UUID.randomUUID(),
                    T0)));
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_EXPIRED");

    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.of(active(bc)));
    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_NOT_FOUND");

    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_NOT_FOUND");

    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.QUOTED,
                    List.of(new QuotedMedicine("A", 1, 100, PROD)),
                    10,
                    3100L,
                    T0,
                    T0.plusSeconds(900),
                    T0,
                    T0.plusSeconds(1200),
                    List.of())));
    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_NOT_FOUND");

    when(pharmacies.findById(PH1)).thenReturn(Optional.of(pharmacy(PH1, "Sai", 12.9, 77.6)));
    when(cartService.createActiveFromQuote(eq(CUST), eq(PH1), eq(ADDR), eq(RX), anyList()))
        .thenReturn(Cart.empty(CUST, T0));
    assertThat(service.selectQuote(customer, bc, PH1).get("status")).isEqualTo("SELECTED");
  }

  @Test
  void pharmacySubmitDeclineAndAuthGaps() {
    UUID bc = UUID.randomUUID();
    assertThatThrownBy(() -> service.submitQuote(pharmacyOwner, bc, List.of(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.submitQuote(pharmacyOwner, bc, List.of(), 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.submitQuote(pharmacyOwner, bc, null, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.submitQuote(pharmacyOwner, bc, List.of(), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findById(bc)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_NOT_FOUND");

    when(store.findById(bc))
        .thenReturn(
            Optional.of(
                new RxBroadcast(
                    bc,
                    CUST,
                    RX,
                    ADDR,
                    "Ravi",
                    null,
                    List.of(),
                    RxBroadcastStatus.EXPIRED,
                    1,
                    T0,
                    T0.plusSeconds(1800),
                    null,
                    null,
                    T0)));
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_EXPIRED");

    when(store.findById(bc)).thenReturn(Optional.of(active(bc)));
    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_NOT_FOUND");

    RxBroadcastPharmacy expiredSlot =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            PH1,
            1,
            RxPharmacySlotStatus.EXPIRED,
            null,
            null,
            null,
            T0.minusSeconds(1000),
            T0.minusSeconds(1),
            null,
            null,
            List.of());
    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.of(expiredSlot));
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_EXPIRED");

    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.QUOTED,
                    List.of(new QuotedMedicine("A", 1, 1)),
                    10,
                    100L,
                    T0,
                    T0.plusSeconds(900),
                    T0,
                    T0.plusSeconds(1200),
                    List.of())));
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.OUT_OF_STOCK,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyOwner, bc, List.of(Map.of("name", "A", "qty", 1, "price", 1)), 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    // parse branches: null entry, quantity key, missing fields, empty after filter
    java.util.ArrayList<Map<String, Object>> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    withNull.add(Map.of("name", "A"));
    assertThatThrownBy(() -> service.submitQuote(pharmacyStaff, bc, withNull, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> withQty = new HashMap<>();
    withQty.put("name", "A");
    withQty.put("quantity", 2);
    withQty.put("price", 10.0);
    assertThat(service.submitQuote(pharmacyStaff, bc, List.of(withQty), 10).get("status"))
        .isEqualTo("QUOTED");
    UUID prod = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    assertThat(
            service
                .submitQuote(
                    pharmacyStaff,
                    bc,
                    List.of(
                        Map.of(
                            "name", "A", "qty", 1, "price", 10.0, "product_id", prod.toString())),
                    10)
                .get("status"))
        .isEqualTo("QUOTED");
    assertThat(
            service
                .submitQuote(
                    pharmacyStaff,
                    bc,
                    List.of(Map.of("name", "A", "qty", 1, "price", 10.0, "medicine_id", prod)),
                    10)
                .get("status"))
        .isEqualTo("QUOTED");
    Map<String, Object> blankPid = new HashMap<>();
    blankPid.put("name", "A");
    blankPid.put("qty", 1);
    blankPid.put("price", 10.0);
    blankPid.put("product_id", "  ");
    assertThat(service.submitQuote(pharmacyStaff, bc, List.of(blankPid), 10).get("status"))
        .isEqualTo("QUOTED");
    assertThatThrownBy(
            () ->
                service.submitQuote(
                    pharmacyStaff,
                    bc,
                    List.of(Map.of("name", "A", "qty", 1, "price", 10.0, "product_id", "not-uuid")),
                    10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    java.util.ArrayList<Map<String, Object>> onlyNull = new java.util.ArrayList<>();
    onlyNull.add(null);
    assertThatThrownBy(() -> service.submitQuote(pharmacyStaff, bc, onlyNull, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findById(bc)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.decline(pharmacyOwner, bc, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_NOT_FOUND");
    when(store.findById(bc))
        .thenReturn(
            Optional.of(
                new RxBroadcast(
                    bc,
                    CUST,
                    RX,
                    ADDR,
                    "Ravi",
                    null,
                    List.of(),
                    RxBroadcastStatus.SELECTED,
                    1,
                    T0,
                    T0.plusSeconds(1800),
                    PH1,
                    null,
                    T0)));
    assertThatThrownBy(() -> service.decline(pharmacyOwner, bc, "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_EXPIRED");

    when(store.findById(bc)).thenReturn(Optional.of(active(bc)));
    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.decline(pharmacyOwner, bc, " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_NOT_FOUND");
    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.of(expiredSlot));
    assertThatThrownBy(() -> service.decline(pharmacyOwner, bc, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_EXPIRED");
    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.QUOTED,
                    List.of(new QuotedMedicine("A", 1, 1)),
                    10,
                    100L,
                    T0,
                    T0.plusSeconds(900),
                    T0,
                    T0.plusSeconds(1200),
                    List.of())));
    assertThatThrownBy(() -> service.decline(pharmacyOwner, bc, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    assertThat(service.decline(pharmacyOwner, bc, null).get("status")).isEqualTo("OUT_OF_STOCK");

    when(store.listPendingForPharmacy(PH1))
        .thenReturn(
            List.of(
                new RxBroadcastPharmacy(
                    UUID.randomUUID(),
                    bc,
                    PH1,
                    1,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.minusSeconds(10),
                    null,
                    null,
                    List.of())));
    when(store.findById(bc)).thenReturn(Optional.empty());
    assertThat(service.listIncoming(pharmacyOwner)).isEmpty();

    assertThatThrownBy(
            () ->
                service.listIncoming(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.getBroadcast(customer, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_NOT_FOUND");
    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getBroadcast(customer, bc))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_NOT_FOUND");
  }

  private RxBroadcast active(UUID id) {
    return new RxBroadcast(
        id,
        CUST,
        RX,
        ADDR,
        "Ravi",
        null,
        List.of(new RequestedMedicine("A", 1)),
        RxBroadcastStatus.ACTIVE,
        2,
        T0,
        T0.plusSeconds(1800),
        null,
        null,
        T0);
  }

  private static PharmacyRow pharmacy(UUID id, String name, Double lat, Double lng) {
    return new PharmacyRow(
        id,
        name,
        "Koramangala",
        "addr",
        "https://cdn/logo.png",
        null,
        lat,
        lng,
        true,
        false,
        "ACTIVE",
        4.6,
        10,
        90.0,
        8.0);
  }
}
