package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RxQuoteBroadcastServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ADDR = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID RX = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2");
  private static final Instant T0 = Instant.parse("2026-08-08T10:00:00Z");

  @Mock private RxBroadcastStore store;
  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private CustomerAddressPort addresses;
  @Mock private PrescriptionPort prescriptions;
  @Mock private CartService cartService;
  @Mock private RateLimiter rateLimiter;

  private InMemoryOutboxStore outboxStore;
  private RxQuoteBroadcastService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal pharmacy =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PH1, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    outboxStore = new InMemoryOutboxStore();
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service =
        new RxQuoteBroadcastService(
            store,
            pharmacies,
            addresses,
            prescriptions,
            cartService,
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void broadcastNotifiesNearestTenAndCaps() {
    when(prescriptions.findForBroadcast(RX, CUST))
        .thenReturn(
            Optional.of(
                new PrescriptionDetail(
                    RX, "VERIFIED", false, List.of(new MedicineLine("Metformin 500mg", 60)))));
    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.9345, 77.6125)));
    List<PharmacyRow> near = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      UUID id = UUID.fromString(String.format("aaaaaaaa-aaaa-4aaa-8aaa-%012d", i + 1));
      near.add(pharmacy(id, "P" + i, 12.9345 + (i * 0.001), 77.6125));
    }
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), eq(3.0))).thenReturn(near);
    when(pharmacies.findById(any()))
        .thenAnswer(inv -> Optional.of(pharmacy(inv.getArgument(0), "X", 12.9, 77.6)));

    AtomicReference<List<RxBroadcastPharmacy>> slots = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(
            inv -> {
              slots.set(inv.getArgument(1));
              return null;
            })
        .when(store)
        .insert(any(), anyList());

    Map<String, Object> data = service.broadcast(customer, RX, ADDR, "Ravi", "note");
    assertThat(data.get("pharmacies_notified")).isEqualTo(10);
    assertThat(slots.get()).hasSize(10);
    assertThat(outboxStore.findUnpublished(100)).hasSize(10);
  }

  @Test
  void broadcastErrors() {
    when(prescriptions.findForBroadcast(RX, CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, "Ravi", null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_NOT_FOUND");

    when(prescriptions.findForBroadcast(RX, CUST))
        .thenReturn(Optional.of(new PrescriptionDetail(RX, "EXPIRED", true, List.of())));
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, "Ravi", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRESCRIPTION_EXPIRED");

    when(prescriptions.findForBroadcast(RX, CUST))
        .thenReturn(
            Optional.of(
                new PrescriptionDetail(RX, "VERIFIED", false, List.of(new MedicineLine("A", 1)))));
    when(addresses.findForCustomer(ADDR, CUST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, "Ravi", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDRESS_NOT_FOUND");

    when(addresses.findForCustomer(ADDR, CUST))
        .thenReturn(Optional.of(new AddressRow(ADDR, CUST, "Home", "x", 12.9, 77.6)));
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), eq(3.0))).thenReturn(List.of());
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, "Ravi", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NO_PHARMACIES_NEARBY");
  }

  @Test
  void canViewQuotesAndTagsAndSelectCreatesCart() {
    UUID bc = UUID.randomUUID();
    RxBroadcast broadcast = activeBroadcast(bc);
    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.of(broadcast));

    // A lower price / higher ETA; B higher price / lower ETA
    RxBroadcastPharmacy q1 =
        quoted(bc, PH1, 22, 27_500, List.of(new QuotedMedicine("A", 60, 25_000)));
    RxBroadcastPharmacy q2 =
        quoted(bc, PH2, 18, 37_050, List.of(new QuotedMedicine("A", 60, 34_550)));
    when(store.listPharmacies(bc)).thenReturn(List.of(q1, q2));
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(pharmacy(PH1, "Sai", 12.9, 77.6)));
    when(pharmacies.findById(PH2)).thenReturn(Optional.of(pharmacy(PH2, "Med", 12.9, 77.6)));

    Map<String, Object> status = service.getBroadcast(customer, bc);
    assertThat(status.get("can_view_quotes")).isEqualTo(true);
    assertThat(status.get("quotes_received")).isEqualTo(2);

    List<Map<String, Object>> quotes = service.listQuotes(customer, bc);
    assertThat(quotes).hasSize(2);
    @SuppressWarnings("unchecked")
    List<String> tags1 = (List<String>) quotes.get(0).get("tags");
    @SuppressWarnings("unchecked")
    List<String> tags2 = (List<String>) quotes.get(1).get("tags");
    assertThat(tags1).contains("LOWEST_PRICE");
    assertThat(tags2).contains("FASTEST");

    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.of(q1));
    Cart cart = Cart.empty(CUST, T0);
    when(cartService.createActiveFromQuote(eq(CUST), eq(PH1), eq(ADDR), eq(RX), anyList()))
        .thenReturn(cart);

    Map<String, Object> selected = service.selectQuote(customer, bc, PH1);
    assertThat(selected.get("status")).isEqualTo("SELECTED");
    assertThat(selected.get("cart_id")).isEqualTo(cart.id());
    verify(store).markSelected(bc, PH1, cart.id());
  }

  @Test
  void canViewQuotesFalseUntilThreshold() {
    UUID bc = UUID.randomUUID();
    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.of(activeBroadcast(bc)));
    RxBroadcastPharmacy q1 = quoted(bc, PH1, 22, 37_050, List.of(new QuotedMedicine("A", 1, 1000)));
    when(store.listPharmacies(bc)).thenReturn(List.of(q1));
    when(pharmacies.findById(any())).thenReturn(Optional.of(pharmacy(PH1, "Sai", 12.9, 77.6)));

    // clock fixed at T0 = broadcast_at → 0 minutes elapsed
    Map<String, Object> status = service.getBroadcast(customer, bc);
    assertThat(status.get("can_view_quotes")).isEqualTo(false);
    assertThat(service.listQuotes(customer, bc)).isEmpty();
  }

  @Test
  void pharmacyQuoteDeclineAndExpiryJobs() {
    UUID bc = UUID.randomUUID();
    when(store.findById(bc)).thenReturn(Optional.of(activeBroadcast(bc)));
    RxBroadcastPharmacy slot =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            PH1,
            1.2,
            RxPharmacySlotStatus.NOTIFIED,
            null,
            null,
            null,
            T0,
            T0.plusSeconds(900),
            null,
            null,
            List.of());
    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.of(slot));
    when(store.listPendingForPharmacy(PH1)).thenReturn(List.of(slot));

    Map<String, Object> quoted =
        service.submitQuote(
            pharmacy,
            bc,
            List.of(Map.of("name", "Metformin 500mg", "qty", 60, "price", 255.00)),
            22);
    assertThat(quoted.get("status")).isEqualTo("QUOTED");

    when(store.findPharmacySlot(bc, PH1))
        .thenReturn(
            Optional.of(
                new RxBroadcastPharmacy(
                    slot.id(),
                    bc,
                    PH1,
                    1.2,
                    RxPharmacySlotStatus.NOTIFIED,
                    null,
                    null,
                    null,
                    T0,
                    T0.plusSeconds(900),
                    null,
                    null,
                    List.of())));
    Map<String, Object> declined = service.decline(pharmacy, bc, "OUT_OF_STOCK");
    assertThat(declined.get("status")).isEqualTo("OUT_OF_STOCK");
    verify(store).updatePharmacyStatus(slot.id(), RxPharmacySlotStatus.OUT_OF_STOCK);

    List<Map<String, Object>> incoming = service.listIncoming(pharmacy);
    assertThat(incoming).hasSize(1);
    assertThat(incoming.getFirst().get("status")).isEqualTo("PENDING_RESPONSE");

    when(store.expirePharmacySlots(T0)).thenReturn(3);
    assertThat(service.expirePharmacyResponseWindows()).isEqualTo(3);

    RxBroadcast exp = activeBroadcast(bc);
    when(store.expireBroadcasts(T0)).thenReturn(List.of(exp));
    assertThat(service.expireBroadcastsAndNotify()).isEqualTo(1);
    assertThat(outboxStore.findUnpublished(10)).isNotEmpty();
  }

  @Test
  void selectRejectsExpiredQuoteAndBroadcast() {
    UUID bc = UUID.randomUUID();
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
                    RxBroadcastStatus.EXPIRED,
                    1,
                    T0.minusSeconds(2000),
                    T0.minusSeconds(100),
                    null,
                    null,
                    T0)));
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("BROADCAST_EXPIRED");

    when(store.findByIdForCustomer(bc, CUST)).thenReturn(Optional.of(activeBroadcast(bc)));
    RxBroadcastPharmacy expiredQuote =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            PH1,
            1.0,
            RxPharmacySlotStatus.QUOTED,
            List.of(new QuotedMedicine("A", 1, 100)),
            10,
            3100L,
            T0.minusSeconds(2000),
            T0.minusSeconds(1000),
            T0.minusSeconds(1500),
            T0.minusSeconds(100),
            List.of());
    when(store.findPharmacySlot(bc, PH1)).thenReturn(Optional.of(expiredQuote));
    assertThatThrownBy(() -> service.selectQuote(customer, bc, PH1))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("QUOTE_EXPIRED");
  }

  @Test
  void validationAndAuthBranches() {
    assertThatThrownBy(() -> service.broadcast(null, RX, ADDR, "Ravi", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.listIncoming(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.broadcast(customer, null, ADDR, "Ravi", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.broadcast(customer, RX, null, "Ravi", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, "  ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.broadcast(customer, RX, ADDR, "Ravi", "x".repeat(301)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(any(), anyInt(), anyInt())).thenReturn(12);
    assertThatThrownBy(() -> service.getBroadcast(customer, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  private RxBroadcast activeBroadcast(UUID id) {
    return new RxBroadcast(
        id,
        CUST,
        RX,
        ADDR,
        "Ravi",
        null,
        List.of(new RequestedMedicine("Metformin 500mg", 60)),
        RxBroadcastStatus.ACTIVE,
        2,
        T0,
        T0.plusSeconds(1800),
        null,
        null,
        T0);
  }

  private static RxBroadcastPharmacy quoted(
      UUID bc, UUID ph, int eta, long total, List<QuotedMedicine> meds) {
    return new RxBroadcastPharmacy(
        UUID.randomUUID(),
        bc,
        ph,
        1.2,
        RxPharmacySlotStatus.QUOTED,
        meds,
        eta,
        total,
        T0,
        T0.plusSeconds(900),
        T0,
        T0.plusSeconds(1200),
        List.of());
  }

  private static PharmacyRow pharmacy(UUID id, String name, double lat, double lng) {
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
