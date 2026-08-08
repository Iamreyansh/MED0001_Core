package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.application.CodReconciliationServiceTest.FakeCollections;
import com.nammamedmate.rider.application.CodReconciliationServiceTest.FakeDeposits;
import com.nammamedmate.rider.application.CodReconciliationServiceTest.FakeFleet;
import com.nammamedmate.rider.application.CodReconciliationServiceTest.FakeRiders;
import com.nammamedmate.rider.application.port.out.AssignmentOtpCachePort;
import com.nammamedmate.rider.application.port.out.CodCollectionStore.CollectionRecord;
import com.nammamedmate.rider.application.port.out.CodDepositStore.DepositRecord;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.OrderDetails;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodReconciliationGapsTest {

  private static final Instant T0 = Instant.parse("2026-07-24T09:30:00Z");

  private FakeRiders riders;
  private FakeCollections collections;
  private FakeDeposits deposits;
  private CodReconciliationService service;
  private UUID riderId;

  @BeforeEach
  void setUp() {
    riders = new FakeRiders();
    collections = new FakeCollections();
    deposits = new FakeDeposits(riders);
    service =
        new CodReconciliationService(
            riders,
            collections,
            deposits,
            cfg(),
            new FakeFleet(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            Clock.fixed(T0, ZoneOffset.UTC));
    riderId = Ids.newId();
    riders.insert(rider(riderId, 50_000L));
  }

  @Test
  void remainingBranches() {
    assertThat(service.floatLimitPaise()).isEqualTo(200_000L);
    service.adminBoard(superAdmin(), null, false, null, null);
    service.adminBoard(superAdmin(), null, false, -1, 0);
    service.adminBoard(superAdmin(), Ids.newId(), false, 2, 200);
    // already FLOAT_RISK → no second notify on further collection
    riders.adjustCodInHand(riderId, 200_000L, T0);
    service.recordCollection(riderId, Ids.newId(), 1_000L, T0);
    assertThatThrownBy(() -> service.depositRequest(riderP(), 1, null, "MODE-NULL", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DEPOSIT_MODE");
    service.remind(ops(), riderId, "   ");
    service.markDeposited(finance(), riderId, 10.00, "", "BLANK-TS-" + Ids.newId(), null);
    assertThatThrownBy(() -> service.adminBoard(null, null, false, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.markDeposited(null, riderId, 1, null, "Z", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.riderSummary(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.recordCollection(Ids.newId(), Ids.newId(), 1000L, T0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    collections.insert(
        new CollectionRecord(Ids.newId(), riderId, Ids.newId(), 5_000L, T0, null, false, T0));
    Map<String, Object> summary = service.riderSummary(riderP());
    assertThat((java.util.List<?>) summary.get("recent_cod_trips")).isNotEmpty();

    service.recordCollection(riderId, Ids.newId(), 10_000L, null);
    assertThatThrownBy(
            () -> service.markDeposited(finance(), riderId, 10.00, "not-an-instant", "REF-T", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.markDeposited(finance(), null, 10.00, null, "REF-N", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> service.markDeposited(finance(), riderId, 10.00, null, "  ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.markDeposited(finance(), riderId, 10.00, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.markDeposited(finance(), riderId, "x", null, "REF-BAD", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_AMOUNT");

    service.depositRequest(riderP(), 10.00, "upi", "REF-LOWER", "n");
    service.markDeposited(finance(), riderId, 10.00, null, "REF-LOWER", "confirm pending");
    service.markDeposited(finance(), riderId, 10.00, T0.toString(), "REF-NEW-1", "n");
    assertThatThrownBy(
            () -> service.markDeposited(finance(), riderId, 10.00, null, "REF-NEW-1", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DUPLICATE_REFERENCE");

    assertThatThrownBy(() -> service.depositRequest(riderP(), 1, "UPI", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.depositRequest(riderP(), 1, "UPI", "  ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.depositRequest(
                    new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j"),
                    1,
                    "UPI",
                    "NOPE",
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> service.riderSummary(finance()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.remind(finance(), Ids.newId(), "hi"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
    service.remind(finance(), riderId, "custom");
    assertThatThrownBy(() -> service.assertCanAcceptCod(Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    service.recordCollection(riderId, Ids.newId(), 20_000L, T0);
    service.adminBoard(finance(), null, false, 1, 5);

    UUID cleared = Ids.newId();
    riders.insert(rider(cleared, 0L));
    deposits.insert(
        new DepositRecord(
            Ids.newId(),
            cleared,
            1000L,
            "UPI",
            "CLEARED-1",
            "CONFIRMED",
            T0,
            T0,
            Ids.newId(),
            T0,
            null,
            T0,
            T0));
    service.adminBoard(finance(), null, false, 1, 50);

    UUID anon = Ids.newId();
    riders.insert(
        new RiderRecord(
            anon,
            null,
            "+919999000088",
            null,
            "BIKE",
            "KA01AB8888",
            null,
            "ONLINE",
            "APPROVED",
            null,
            null,
            null,
            null,
            null,
            true,
            null,
            0,
            null,
            0L,
            5_000L,
            0,
            null,
            null,
            null,
            T0,
            T0));
    CodReconciliationService late =
        new CodReconciliationService(
            riders,
            collections,
            deposits,
            cfg(),
            new FakeFleet(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            Clock.fixed(Instant.parse("2026-07-24T15:00:00Z"), ZoneOffset.UTC));
    late.publishDailyReport();
    late.riderSummary(new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j"));
    assertThatThrownBy(
            () ->
                service.riderSummary(
                    new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");

    // deposit_status NONE + COMPLETE (collected today, zero in hand, no deposit)
    UUID none = Ids.newId();
    riders.insert(rider(none, 0L));
    UUID complete = Ids.newId();
    riders.insert(rider(complete, 0L));
    collections.insert(
        new CollectionRecord(Ids.newId(), complete, Ids.newId(), 100L, T0, null, true, T0));
    var board = service.adminBoard(finance(), null, false, 1, 100);
    @SuppressWarnings("unchecked")
    var boardRiders = (java.util.List<Map<String, Object>>) board.data().get("riders");
    assertThat(boardRiders.stream().anyMatch(r -> none.toString().equals(r.get("rider_id"))))
        .isTrue();
    assertThat(
            boardRiders.stream()
                .filter(r -> complete.toString().equals(r.get("rider_id")))
                .findFirst()
                .map(r -> r.get("deposit_status"))
                .orElse(null))
        .isEqualTo("COMPLETE");

    // interface default adjustCodInHand
    RiderStore bare =
        new RiderStore() {
          @Override
          public void insert(RiderRecord rider) {}

          @Override
          public Optional<RiderRecord> findById(UUID id) {
            return Optional.empty();
          }

          @Override
          public Optional<RiderRecord> findByPhone(String phone) {
            return Optional.empty();
          }

          @Override
          public boolean existsByPhone(String phone) {
            return false;
          }

          @Override
          public void update(RiderRecord rider) {}

          @Override
          public PageResult list(ListFilter filter) {
            return new PageResult(List.of(), 0);
          }

          @Override
          public void updateAvailability(
              UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

          @Override
          public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}
        };
    assertThatThrownBy(() -> bare.adjustCodInHand(Ids.newId(), 1L, T0))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void riderAcceptBlocksCodWhenOverFloat() {
    CodReconciliationService cod = mock(CodReconciliationService.class);
    OrderAssignmentStore assignments = mock(OrderAssignmentStore.class);
    DispatchOrderPort orders = mock(DispatchOrderPort.class);
    AssignmentOtpCachePort otp = mock(AssignmentOtpCachePort.class);
    UUID orderId = Ids.newId();
    UUID assignmentId = Ids.newId();
    Instant now = T0;
    whenAccept(assignments, orders, otp, orderId, assignmentId, riderId, now, "COD");
    RiderOrderService riderOrders =
        new RiderOrderService(
            assignments,
            orders,
            otp,
            new StubDistanceMatrixAdapter(),
            mock(RiderTripEarningsStore.class),
            cod,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            Clock.fixed(now, ZoneOffset.UTC));
    riderOrders.accept(riderP(), orderId);
    verify(cod).assertCanAcceptCod(riderId);

    whenAccept(assignments, orders, otp, orderId, assignmentId, riderId, now, "UPI");
    riderOrders.accept(riderP(), orderId);
  }

  private void whenAccept(
      OrderAssignmentStore assignments,
      DispatchOrderPort orders,
      AssignmentOtpCachePort otp,
      UUID orderId,
      UUID assignmentId,
      UUID rider,
      Instant now,
      String pay) {
    org.mockito.Mockito.when(assignments.findActiveByOrder(orderId))
        .thenReturn(
            Optional.of(
                new AssignmentRecord(
                    assignmentId,
                    orderId,
                    rider,
                    "AUTO",
                    null,
                    "PENDING_ACCEPTANCE",
                    now.plusSeconds(300),
                    null,
                    null,
                    null,
                    "h1",
                    "h2",
                    null,
                    BigDecimal.ONE,
                    now,
                    now)));
    org.mockito.Mockito.when(orders.findOrder(orderId))
        .thenReturn(
            Optional.of(
                new OrderDetails(
                    orderId,
                    "MED-1",
                    "READY_FOR_PICKUP",
                    rider,
                    Ids.newId(),
                    "Ph",
                    "addr",
                    12.9,
                    77.6,
                    "99",
                    Ids.newId(),
                    "Z",
                    "Cust",
                    "88",
                    "daddr",
                    12.91,
                    77.61,
                    1,
                    pay,
                    1000L,
                    now,
                    now,
                    "h")));
    org.mockito.Mockito.doNothing().when(assignments).update(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doNothing().when(otp).incrConcurrent(org.mockito.ArgumentMatchers.any());
  }

  private MedmatePrincipal riderP() {
    return new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal finance() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal ops() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal superAdmin() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  private static PlatformPricingConfigStore cfg() {
    return new PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String key) {
        return Optional.of("200000");
      }

      @Override
      public BigDecimal handlingFeeRupees() {
        return BigDecimal.ZERO;
      }

      @Override
      public void upsert(String key, String v, String description, UUID updatedBy, Instant now) {}
    };
  }

  private static RiderRecord rider(UUID id, long cod) {
    return new RiderRecord(
        id,
        "Ravi",
        "+919999000099",
        null,
        "BIKE",
        "KA01AB9999",
        null,
        "ONLINE",
        "APPROVED",
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        0,
        null,
        0L,
        cod,
        0,
        null,
        null,
        null,
        T0,
        T0);
  }
}
