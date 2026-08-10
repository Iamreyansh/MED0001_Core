package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.prescription.application.port.out.CustomerContactPort;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.InventoryStockPort;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.OrderLinesPort;
import com.nammamedmate.prescription.application.port.out.OrderStatusPort;
import com.nammamedmate.prescription.application.port.out.PharmacyPlanPort;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore.Kpis;
import com.nammamedmate.prescription.application.port.out.PosDispensePort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.ScheduleRegisterWritePort;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyRxQueueServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:30:00Z");
  private static final UUID PHARM = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static final UUID STAFF = UUID.randomUUID();
  private static final UUID CUST = UUID.randomUUID();
  private static final MedmatePrincipal OWNER =
      new MedmatePrincipal(STAFF, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
  private static final MedmatePrincipal STAFF_P =
      new MedmatePrincipal(STAFF, AuthRole.PHARMACY_STAFF, PHARM, TokenScope.FULL, "j");

  @Test
  void validationAndMissingRxBranches() {
    PharmacyRxQueueStore queue = mock(PharmacyRxQueueStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    PharmacyPlanPort plan = mock(PharmacyPlanPort.class);
    when(plan.rxQueueEnabled(PHARM)).thenReturn(true);
    UUID rxId = UUID.randomUUID();
    when(queue.findByRxAndPharmacy(rxId, PHARM))
        .thenReturn(Optional.of(entry(rxId, "PENDING_REVIEW")));
    when(rxStore.findById(rxId)).thenReturn(Optional.empty());

    PharmacyRxQueueService service =
        service(queue, rxStore, plan, new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));
    assertThatThrownBy(() -> service.get(OWNER, rxId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_NOT_FOUND");

    when(rxStore.findById(rxId)).thenReturn(Optional.of(rx(rxId, "UPLOADED", List.of())));
    assertThatThrownBy(
            () ->
                service.approve(
                    OWNER,
                    rxId,
                    List.of(new ApprovedMedicine("x", 1, BigDecimal.ONE)),
                    "x".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reject(OWNER, rxId, "ILLEGIBLE", "x".repeat(301)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.enqueue(rxId, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void listResultNullData() {
    assertThat(new PharmacyRxQueueService.ListResult(null, null).data()).isEmpty();
  }

  @Test
  void rateLimited() {
    PharmacyRxQueueStore queue = mock(PharmacyRxQueueStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    PharmacyPlanPort plan = mock(PharmacyPlanPort.class);
    when(plan.rxQueueEnabled(PHARM)).thenReturn(true);
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    for (int i = 0; i < 60; i++) {
      limiter.tryAcquire("rxq:list:" + PHARM, 60, 60);
    }
    PharmacyRxQueueService service = service(queue, rxStore, plan, limiter);
    assertThatThrownBy(() -> service.list(OWNER, null, null, null, 1, 20, null))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("RATE_LIMITED"));
  }

  @Test
  void listSkipsMissingRxFiltersPagesAndCache() {
    PharmacyRxQueueStore queue = mock(PharmacyRxQueueStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    PharmacyPlanPort plan = mock(PharmacyPlanPort.class);
    when(plan.rxQueueEnabled(PHARM)).thenReturn(true);
    UUID missing = UUID.randomUUID();
    UUID present = UUID.randomUUID();
    when(queue.list(
            eq(PHARM),
            any(),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            any()))
        .thenReturn(
            new PharmacyRxQueueStore.Page(
                List.of(entry(missing, "PENDING_REVIEW"), entry(present, "PENDING_REVIEW")), 2));
    when(queue.computeKpis(eq(PHARM), any())).thenReturn(new Kpis(1, 0, 0, 0, 0, 0, 0, 0));
    when(queue.findPendingOverdueUnnotified(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(
            List.of(
                entryOtherPharmacy(UUID.randomUUID()),
                entry(UUID.randomUUID(), "PENDING_REVIEW", NOW.minus(Duration.ofHours(3)))));
    when(rxStore.findById(missing)).thenReturn(Optional.empty());
    when(rxStore.findById(present)).thenReturn(Optional.of(rx(present, "UPLOADED", null)));

    NotificationDispatchPort notifications = mock(NotificationDispatchPort.class);
    PharmacyRxQueueService service =
        serviceFull(
            queue,
            rxStore,
            plan,
            notifications,
            mock(CustomerContactPort.class),
            mock(DoctorCardPort.class),
            mock(InventoryStockPort.class),
            mock(PosDispensePort.class),
            urlWithQuery());

    service.list(STAFF_P, "PENDING", "all", null, 0, 0, " ");
    service.list(OWNER, "  ", null, null, 1, 200, null);
    verify(notifications, org.mockito.Mockito.atLeastOnce())
        .notifyPharmacyOwnerOverdue(eq(PHARM), any());
    // kpi cache hit
    service.list(OWNER, null, null, null, 1, 20, null);
  }

  @Test
  void detailTimelineDuplicateBlankNameAndDispenseNullMeds() {
    PharmacyRxQueueStore queue = mock(PharmacyRxQueueStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    PharmacyPlanPort plan = mock(PharmacyPlanPort.class);
    when(plan.rxQueueEnabled(PHARM)).thenReturn(true);
    UUID rxId = UUID.randomUUID();
    Instant t = NOW.minusSeconds(10);
    PharmacyRxQueueEntry dispensed =
        new PharmacyRxQueueEntry(
            UUID.randomUUID(),
            rxId,
            PHARM,
            null,
            t,
            "DISPENSED",
            null,
            STAFF,
            t,
            "ILLEGIBLE",
            "m",
            STAFF,
            t,
            STAFF,
            t,
            "n",
            true,
            null,
            t,
            t,
            null);
    when(queue.findByRxAndPharmacy(rxId, PHARM)).thenReturn(Optional.of(dispensed));
    when(queue.hasDuplicateDispense(any(), any(), any(), any())).thenReturn(false);
    when(rxStore.findById(rxId))
        .thenReturn(
            Optional.of(
                rx(
                    rxId,
                    "E_PRESCRIPTION",
                    List.of(
                        new MedicineExtracted(null, null, null, null),
                        new MedicineExtracted("  ", "abc", null, null),
                        new MedicineExtracted("Metformin", "tabs", null, null),
                        new MedicineExtracted("BlankQty", "", null, null),
                        new MedicineExtracted("X", "999999999999999999999", null, null)))));

    CustomerContactPort contacts = mock(CustomerContactPort.class);
    when(contacts.find(any()))
        .thenReturn(Optional.of(new CustomerContactPort.Contact(null, "+91")));
    when(contacts.previousOrdersCount(any(), any())).thenReturn(0);
    DoctorCardPort doctors = mock(DoctorCardPort.class);
    when(doctors.findForPrescription(any(), any(), any(), any())).thenReturn(Optional.empty());
    InventoryStockPort inventory = mock(InventoryStockPort.class);
    when(inventory.findByName(any(), eq("Metformin")))
        .thenReturn(Optional.of(new InventoryStockPort.StockInfo(true, 5, 100)));
    when(inventory.findByName(any(), eq("X")))
        .thenReturn(Optional.of(new InventoryStockPort.StockInfo(false, 0, 0)));
    when(inventory.findByName(any(), eq("BlankQty"))).thenReturn(Optional.empty());
    when(inventory.findByName(any(), eq(null))).thenReturn(Optional.empty());
    when(inventory.findByName(any(), eq("  "))).thenReturn(Optional.empty());

    PosDispensePort pos = mock(PosDispensePort.class);
    when(pos.available()).thenReturn(true);
    when(pos.pushToBillingCart(any(), any(), any())).thenReturn(UUID.randomUUID());
    when(pos.createSaleRecord(any(), any(), any(), any())).thenReturn(UUID.randomUUID());

    PharmacyRxQueueService service =
        serviceFull(
            queue,
            rxStore,
            plan,
            mock(NotificationDispatchPort.class),
            contacts,
            doctors,
            inventory,
            pos,
            urlNoQuery());

    Map<String, Object> detail = service.get(OWNER, rxId);
    assertThat(detail.get("timeline")).asList().hasSize(5);
    assertThat(detail.get("duplicate_rx_warning")).isEqualTo(true);

    service.dispenseToBilling(OWNER, rxId);

    UUID approvedRx = UUID.randomUUID();
    PharmacyRxQueueEntry approvedNullMeds =
        new PharmacyRxQueueEntry(
            UUID.randomUUID(),
            approvedRx,
            PHARM,
            null,
            t,
            "APPROVED",
            null,
            STAFF,
            t,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            t,
            t,
            null);
    when(queue.findByRxAndPharmacy(approvedRx, PHARM)).thenReturn(Optional.of(approvedNullMeds));
    when(rxStore.findById(approvedRx))
        .thenReturn(Optional.of(rx(approvedRx, "UPLOADED", List.of())));
    service.dispense(OWNER, approvedRx);

    UUID pendingRx = UUID.randomUUID();
    when(queue.findByRxAndPharmacy(pendingRx, PHARM))
        .thenReturn(Optional.of(entry(pendingRx, "PENDING_REVIEW")));
    when(rxStore.findById(pendingRx)).thenReturn(Optional.of(rx(pendingRx, "UPLOADED", List.of())));
    service.approve(OWNER, pendingRx, List.of(new ApprovedMedicine("y", 1, null)), "   ");
  }

  @Test
  void approveNullMedicinesRejectNullReasonAndExpiredKpiCache() {
    PharmacyRxQueueStore queue = mock(PharmacyRxQueueStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    PharmacyPlanPort plan = mock(PharmacyPlanPort.class);
    when(plan.rxQueueEnabled(PHARM)).thenReturn(true);
    UUID rxId = UUID.randomUUID();
    when(queue.findByRxAndPharmacy(rxId, PHARM))
        .thenReturn(Optional.of(entry(rxId, "PENDING_REVIEW")));
    when(rxStore.findById(rxId)).thenReturn(Optional.of(rx(rxId, "UPLOADED", null)));
    when(queue.list(
            eq(PHARM),
            any(),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            any()))
        .thenReturn(new PharmacyRxQueueStore.Page(List.of(), 0));
    when(queue.computeKpis(eq(PHARM), any())).thenReturn(new Kpis(0, 0, 0, 0, 0, 0, 0, 0));
    when(queue.findPendingOverdueUnnotified(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of());

    java.util.concurrent.atomic.AtomicLong offset = new java.util.concurrent.atomic.AtomicLong();
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return NOW.plusSeconds(offset.get());
          }
        };
    PharmacyRxQueueService service =
        new PharmacyRxQueueService(
            queue,
            rxStore,
            plan,
            mock(OrderLinesPort.class),
            mock(OrderStatusPort.class),
            mock(PosDispensePort.class),
            mock(NotificationDispatchPort.class),
            mock(DoctorCardPort.class),
            mock(ScheduleRegisterWritePort.class),
            mock(InventoryStockPort.class),
            mock(CustomerContactPort.class),
            urlWithQuery(),
            new InMemoryRateLimiter(clock),
            clock);

    assertThatThrownBy(() -> service.approve(OWNER, rxId, null, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("APPROVED_MEDICINES_EMPTY"));
    assertThatThrownBy(() -> service.reject(OWNER, rxId, null, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_REJECTION_REASON"));

    service.list(OWNER, "APPROVED", "UPLOADED", null, 2, null, "urgency");
    offset.set(120);
    service.list(OWNER, null, "DIGITAL", null, null, 20, "urgency");
    service.list(OWNER, null, "   ", null, 1, 20, null);
    Map<String, Object> detail = service.get(OWNER, rxId);
    assertThat(detail.get("medicines_verified")).asList().isEmpty();
  }

  @Test
  void authBranchesAndRejectBlankMessage() {
    PharmacyRxQueueStore queue = mock(PharmacyRxQueueStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    PharmacyPlanPort plan = mock(PharmacyPlanPort.class);
    when(plan.rxQueueEnabled(PHARM)).thenReturn(true);
    UUID rxId = UUID.randomUUID();
    when(queue.findByRxAndPharmacy(rxId, PHARM))
        .thenReturn(Optional.of(entry(rxId, "PENDING_REVIEW")));
    when(rxStore.findById(rxId)).thenReturn(Optional.of(rx(rxId, "UPLOADED", List.of())));
    PharmacyRxQueueService service =
        service(queue, rxStore, plan, new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));
    assertThatThrownBy(
            () ->
                service.list(
                    new MedmatePrincipal(
                        STAFF, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j"),
                    null,
                    null,
                    null,
                    1,
                    20,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.list(null, null, null, null, 1, 20, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    service.reject(OWNER, rxId, "EXPIRED", "  ");
    when(queue.findByRxAndPharmacy(rxId, PHARM))
        .thenReturn(Optional.of(entry(rxId, "PENDING_REVIEW")));
    service.reject(OWNER, rxId, "NOT_STOCKED", null);
  }

  private static PharmacyRxQueueEntry entry(UUID rxId, String status) {
    return entry(rxId, status, NOW);
  }

  private static PharmacyRxQueueEntry entry(UUID rxId, String status, Instant received) {
    return new PharmacyRxQueueEntry(
        UUID.randomUUID(),
        rxId,
        PHARM,
        null,
        received,
        status,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        received,
        received,
        null);
  }

  private static PharmacyRxQueueEntry entryOtherPharmacy(UUID rxId) {
    return new PharmacyRxQueueEntry(
        UUID.randomUUID(),
        rxId,
        OTHER,
        null,
        NOW.minus(Duration.ofHours(3)),
        "PENDING_REVIEW",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        NOW,
        NOW,
        null);
  }

  private static PrescriptionRecord rx(UUID id, String type, List<MedicineExtracted> meds) {
    return new PrescriptionRecord(
        id,
        CUST,
        type,
        "UPLOADED",
        "k",
        1,
        "image/jpeg",
        "P",
        null,
        "Dr",
        null,
        "UPLOAD",
        meds,
        null,
        null,
        NOW.plusSeconds(1000),
        null,
        NOW,
        NOW,
        null);
  }

  private static PharmacyRxQueueService service(
      PharmacyRxQueueStore queue,
      PrescriptionStore rxStore,
      PharmacyPlanPort plan,
      InMemoryRateLimiter limiter) {
    return serviceFull(
        queue,
        rxStore,
        plan,
        mock(NotificationDispatchPort.class),
        mock(CustomerContactPort.class),
        mock(DoctorCardPort.class),
        mock(InventoryStockPort.class),
        mock(PosDispensePort.class),
        urlWithQuery(),
        limiter);
  }

  private static PharmacyRxQueueService serviceFull(
      PharmacyRxQueueStore queue,
      PrescriptionStore rxStore,
      PharmacyPlanPort plan,
      NotificationDispatchPort notifications,
      CustomerContactPort contacts,
      DoctorCardPort doctors,
      InventoryStockPort inventory,
      PosDispensePort pos,
      PresignedUrlService presigner) {
    return serviceFull(
        queue,
        rxStore,
        plan,
        notifications,
        contacts,
        doctors,
        inventory,
        pos,
        presigner,
        new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));
  }

  private static PharmacyRxQueueService serviceFull(
      PharmacyRxQueueStore queue,
      PrescriptionStore rxStore,
      PharmacyPlanPort plan,
      NotificationDispatchPort notifications,
      CustomerContactPort contacts,
      DoctorCardPort doctors,
      InventoryStockPort inventory,
      PosDispensePort pos,
      PresignedUrlService presigner,
      InMemoryRateLimiter limiter) {
    return new PharmacyRxQueueService(
        queue,
        rxStore,
        plan,
        mock(OrderLinesPort.class),
        mock(OrderStatusPort.class),
        pos,
        notifications,
        doctors,
        mock(ScheduleRegisterWritePort.class),
        inventory,
        contacts,
        presigner,
        limiter,
        Clock.fixed(NOW, ZoneOffset.UTC),
        null);
  }

  @Test
  void dispenseCreatesComplianceAuditWhenWired() {
    PharmacyRxQueueStore queue = mock(PharmacyRxQueueStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    PharmacyPlanPort plan = pharmacyId -> true;
    RxComplianceAuditService audit = mock(RxComplianceAuditService.class);
    UUID rxId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    PharmacyRxQueueEntry approved =
        new PharmacyRxQueueEntry(
            UUID.randomUUID(),
            rxId,
            PHARM,
            orderId,
            NOW,
            "APPROVED",
            List.of(new ApprovedMedicine("Alprazolam", 1, BigDecimal.ONE, "H1")),
            OWNER.subject(),
            NOW,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            NOW,
            NOW,
            null);
    when(queue.findByRxAndPharmacy(rxId, PHARM)).thenReturn(Optional.of(approved));
    when(rxStore.findById(rxId))
        .thenReturn(
            Optional.of(
                new PrescriptionRecord(
                    rxId,
                    UUID.randomUUID(),
                    "UPLOADED",
                    "VERIFIED",
                    "k",
                    1,
                    "image/jpeg",
                    "P",
                    null,
                    "D",
                    null,
                    "UPLOAD",
                    null,
                    orderId,
                    null,
                    NOW.plusSeconds(1000),
                    null,
                    NOW,
                    NOW,
                    null)));
    PosDispensePort pos = mock(PosDispensePort.class);
    when(pos.createSaleRecord(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
    PharmacyRxQueueService svc =
        new PharmacyRxQueueService(
            queue,
            rxStore,
            plan,
            mock(OrderLinesPort.class),
            mock(OrderStatusPort.class),
            pos,
            mock(NotificationDispatchPort.class),
            mock(DoctorCardPort.class),
            mock(ScheduleRegisterWritePort.class),
            mock(InventoryStockPort.class),
            mock(CustomerContactPort.class),
            urlWithQuery(),
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            audit);
    svc.dispense(OWNER, rxId);
    verify(audit).createFromDispense(eq(rxId), eq(orderId), eq(PHARM), any(), any(), any());
  }

  private static PresignedUrlService urlWithQuery() {
    return new PresignedUrlService() {
      @Override
      public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
        return new PresignedUrl("https://x/" + key + "?put=1", key, ttl);
      }

      @Override
      public PresignedUrl createGetUrl(String key, Duration ttl) {
        return new PresignedUrl("https://x/" + key + "?get=1", key, ttl);
      }
    };
  }

  private static PresignedUrlService urlNoQuery() {
    return new PresignedUrlService() {
      @Override
      public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
        return new PresignedUrl("https://x/" + key, key, ttl);
      }

      @Override
      public PresignedUrl createGetUrl(String key, Duration ttl) {
        return new PresignedUrl("https://x/" + key, key, ttl);
      }
    };
  }
}
