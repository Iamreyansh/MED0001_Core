package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PharmacyRxQueueServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:30:00Z");
  private static final UUID PHARM = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID STAFF = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final MedmatePrincipal OWNER =
      new MedmatePrincipal(STAFF, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");

  private FakeQueueStore queue;
  private FakeRxStore rxStore;
  private PharmacyPlanPort plan;
  private OrderLinesPort orderLines;
  private OrderStatusPort orderStatus;
  private PosDispensePort pos;
  private NotificationDispatchPort notifications;
  private DoctorCardPort doctors;
  private ScheduleRegisterWritePort schedule;
  private InventoryStockPort inventory;
  private CustomerContactPort contacts;
  private PharmacyRxQueueService service;

  @BeforeEach
  void setUp() {
    queue = new FakeQueueStore();
    rxStore = new FakeRxStore();
    plan = mock(PharmacyPlanPort.class);
    when(plan.rxQueueEnabled(PHARM)).thenReturn(true);
    orderLines = mock(OrderLinesPort.class);
    orderStatus = mock(OrderStatusPort.class);
    pos = mock(PosDispensePort.class);
    when(pos.available()).thenReturn(true);
    when(pos.pushToBillingCart(any(), any(), any())).thenReturn(UUID.randomUUID());
    when(pos.createSaleRecord(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
    notifications = mock(NotificationDispatchPort.class);
    doctors = mock(DoctorCardPort.class);
    schedule = mock(ScheduleRegisterWritePort.class);
    inventory = mock(InventoryStockPort.class);
    contacts = mock(CustomerContactPort.class);
    when(contacts.find(any()))
        .thenReturn(Optional.of(new CustomerContactPort.Contact("Ravi", "+91")));
    when(contacts.previousOrdersCount(any(), any())).thenReturn(5);
    AtomicInteger url = new AtomicInteger();
    PresignedUrlService presigner =
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("https://x/" + key, key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl("https://x/" + key + "?g=" + url.incrementAndGet(), key, ttl);
          }
        };
    service =
        new PharmacyRxQueueService(
            queue,
            rxStore,
            plan,
            orderLines,
            orderStatus,
            pos,
            notifications,
            doctors,
            schedule,
            inventory,
            contacts,
            presigner,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_overdueSortedTopAndFlagged() {
    UUID overdueRx =
        seedRx("UPLOADED", List.of(new MedicineExtracted("Metformin", "60", null, null)));
    UUID freshRx = seedRx("UPLOADED", List.of());
    Instant overdueAt = NOW.minus(Duration.ofHours(2).plusMinutes(5));
    queue.insert(entry(overdueRx, overdueAt, "PENDING_REVIEW"));
    queue.insert(entry(freshRx, NOW.minusSeconds(60), "PENDING_REVIEW"));
    when(doctors.findForPrescription(any(), any(), any(), any())).thenReturn(Optional.empty());

    var result = service.list(OWNER, "PENDING_REVIEW", null, null, 1, 20, "urgency");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data().get("prescriptions");
    assertThat(rows.get(0).get("rx_id")).isEqualTo(overdueRx);
    assertThat(rows.get(0).get("is_overdue")).isEqualTo(true);
    assertThat((Long) rows.get(0).get("overdue_by_minutes")).isEqualTo(5L);
    verify(notifications).notifyPharmacyOwnerOverdue(PHARM, overdueRx);
  }

  @Test
  void ac_approveReplacesOrderLines() {
    UUID rxId = seedRx("UPLOADED", List.of());
    UUID orderId = UUID.randomUUID();
    queue.insert(entry(rxId, NOW.minusSeconds(10), "PENDING_REVIEW", orderId));
    List<ApprovedMedicine> meds =
        List.of(new ApprovedMedicine("Metformin 500mg", 60, new BigDecimal("85.00")));
    Map<String, Object> data = service.approve(OWNER, rxId, meds, "ok");
    assertThat(data.get("status")).isEqualTo("APPROVED");
    verify(orderLines).replaceOrderLines(orderId, meds);
  }

  @Test
  void ac_rejectNotifiesCustomer() {
    UUID rxId = seedRx("UPLOADED", List.of());
    queue.insert(entry(rxId, NOW, "PENDING_REVIEW"));
    Map<String, Object> data =
        service.reject(OWNER, rxId, "ILLEGIBLE", "Please upload clearer photo");
    assertThat(data.get("customer_notified")).isEqualTo(true);
    verify(notifications)
        .notifyCustomerRxRejected(CUST, rxId, "ILLEGIBLE", "Please upload clearer photo");
  }

  @Test
  void ac_ePrescriptionDoctorCardVerified() {
    UUID rxId = seedRx("E_PRESCRIPTION", List.of());
    queue.insert(entry(rxId, NOW, "PENDING_REVIEW"));
    when(doctors.findForPrescription(eq(rxId), eq("E_PRESCRIPTION"), any(), any()))
        .thenReturn(
            Optional.of(new DoctorCardPort.DoctorCard("Dr. Priya", "MBBS MD", "MH12345", true)));
    Map<String, Object> detail = service.get(OWNER, rxId);
    @SuppressWarnings("unchecked")
    Map<String, Object> doctor = (Map<String, Object>) detail.get("doctor");
    assertThat(doctor.get("verified")).isEqualTo(true);
    assertThat(doctor.get("registration_no")).isEqualTo("MH12345");
  }

  @Test
  void ac_freePlanForbidden() {
    when(plan.rxQueueEnabled(PHARM)).thenReturn(false);
    assertThatThrownBy(() -> service.list(OWNER, null, null, null, 1, 20, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PLAN_UPGRADE_REQUIRED");
  }

  @Test
  void ac_dispenseToBillingPrefillsPos() {
    UUID rxId = seedRx("UPLOADED", List.of());
    PharmacyRxQueueEntry e = entry(rxId, NOW, "APPROVED");
    e =
        new PharmacyRxQueueEntry(
            e.id(),
            e.rxId(),
            e.pharmacyId(),
            e.orderId(),
            e.receivedAt(),
            "APPROVED",
            List.of(new ApprovedMedicine("Metformin", 60, new BigDecimal("85"))),
            STAFF,
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
    queue.insert(e);
    UUID cart = UUID.randomUUID();
    when(pos.pushToBillingCart(eq(PHARM), eq(STAFF), any())).thenReturn(cart);
    Map<String, Object> data = service.dispenseToBilling(OWNER, rxId);
    assertThat(data.get("pos_cart_id")).isEqualTo(cart);
    assertThat(data.get("medicines_loaded")).isEqualTo(1);
  }

  @Test
  void ac_duplicateWarningNonBlocking() {
    UUID rxId =
        seedRx("UPLOADED", List.of(new MedicineExtracted("Metformin 500mg", "60", null, null)));
    queue.insert(entry(rxId, NOW, "PENDING_REVIEW"));
    queue.duplicate = true;
    Map<String, Object> detail = service.get(OWNER, rxId);
    assertThat(detail.get("duplicate_rx_warning")).isEqualTo(true);
    assertThat(detail.get("warning")).isEqualTo("POSSIBLE_DUPLICATE_RX");
    Map<String, Object> approved =
        service.approve(
            OWNER,
            rxId,
            List.of(new ApprovedMedicine("Metformin 500mg", 60, new BigDecimal("85"))),
            null);
    assertThat(approved.get("status")).isEqualTo("APPROVED");
    assertThat(approved.get("warning")).isEqualTo("POSSIBLE_DUPLICATE_RX");
  }

  @Test
  void ac_dispenseReadyForPickupAndSale() {
    UUID rxId = seedRx("UPLOADED", List.of());
    UUID orderId = UUID.randomUUID();
    PharmacyRxQueueEntry e = entry(rxId, NOW, "APPROVED", orderId);
    e =
        new PharmacyRxQueueEntry(
            e.id(),
            rxId,
            PHARM,
            orderId,
            NOW,
            "APPROVED",
            List.of(new ApprovedMedicine("Metformin", 1, BigDecimal.TEN)),
            STAFF,
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
    queue.insert(e);
    UUID sale = UUID.randomUUID();
    when(pos.createSaleRecord(any(), any(), any(), any())).thenReturn(sale);
    Map<String, Object> data = service.dispense(OWNER, rxId);
    assertThat(data.get("status")).isEqualTo("DISPENSED");
    assertThat(data.get("sale_record_id")).isEqualTo(sale);
    assertThat(data.get("order_status_updated_to")).isEqualTo("READY_FOR_PICKUP");
    verify(orderStatus).markReadyForPickup(orderId);
    verify(schedule).recordDispense(eq(PHARM), eq(rxId), eq(STAFF), any());
  }

  @Test
  void errors_notFoundAlreadyActionedEmptyInvalidPosUnavailable() {
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> service.get(OWNER, missing))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_NOT_FOUND");

    UUID rxId = seedRx("UPLOADED", List.of());
    queue.insert(entry(rxId, NOW, "APPROVED"));
    assertThatThrownBy(
            () ->
                service.approve(
                    OWNER, rxId, List.of(new ApprovedMedicine("x", 1, BigDecimal.ONE)), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_ALREADY_ACTIONED");
    assertThatThrownBy(() -> service.reject(OWNER, rxId, "ILLEGIBLE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_ALREADY_ACTIONED");

    UUID pending = seedRx("UPLOADED", List.of());
    queue.insert(entry(pending, NOW, "PENDING_REVIEW"));
    assertThatThrownBy(() -> service.approve(OWNER, pending, List.of(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("APPROVED_MEDICINES_EMPTY");
    assertThatThrownBy(() -> service.reject(OWNER, pending, "NOPE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REJECTION_REASON");
    assertThatThrownBy(() -> service.dispense(OWNER, pending))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_NOT_APPROVED");
    assertThatThrownBy(() -> service.dispenseToBilling(OWNER, pending))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_NOT_APPROVED");

    UUID approved = seedRx("UPLOADED", List.of());
    PharmacyRxQueueEntry a = entry(approved, NOW, "APPROVED");
    a =
        new PharmacyRxQueueEntry(
            a.id(),
            approved,
            PHARM,
            null,
            NOW,
            "APPROVED",
            List.of(new ApprovedMedicine("x", 1, BigDecimal.ONE)),
            STAFF,
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
    queue.insert(a);
    when(pos.available()).thenReturn(false);
    assertThatThrownBy(() -> service.dispenseToBilling(OWNER, approved))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("POS_UNAVAILABLE");
  }

  @Test
  void enqueue_idempotentAndNotifyOverdue() {
    UUID rxId = seedRx("UPLOADED", List.of());
    UUID id1 = service.enqueue(rxId, PHARM, null);
    UUID id2 = service.enqueue(rxId, PHARM, null);
    assertThat(id1).isEqualTo(id2);
    assertThatThrownBy(() -> service.enqueue(null, PHARM, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID overdue = seedRx("UPLOADED", List.of());
    queue.insert(entry(overdue, NOW.minus(Duration.ofHours(3)), "PENDING_REVIEW"));
    assertThat(service.notifyOverdue()).isEqualTo(1);
    verify(notifications).notifyPharmacyOwnerOverdue(PHARM, overdue);
    assertThat(service.notifyOverdue()).isZero();
  }

  @Test
  void enqueue_appliesDoctorAutoFlagsWhenAuditWired() {
    RxComplianceAuditService audit = mock(RxComplianceAuditService.class);
    AtomicInteger url = new AtomicInteger();
    PharmacyRxQueueService wired =
        new PharmacyRxQueueService(
            queue,
            rxStore,
            plan,
            orderLines,
            orderStatus,
            pos,
            notifications,
            doctors,
            schedule,
            inventory,
            contacts,
            new PresignedUrlService() {
              @Override
              public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
                return new PresignedUrl("https://x/" + key, key, ttl);
              }

              @Override
              public PresignedUrl createGetUrl(String key, Duration ttl) {
                return new PresignedUrl(
                    "https://x/" + key + "?g=" + url.incrementAndGet(), key, ttl);
              }
            },
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            audit);
    UUID rxId = seedRx("UPLOADED", List.of());
    wired.enqueue(rxId, PHARM, null);
    verify(audit).applyPendingFlags(rxId, PHARM);
  }

  @Test
  void listFiltersAndKpiCacheAndAuth() {
    UUID rxId =
        seedRx("E_PRESCRIPTION", List.of(new MedicineExtracted("A", "10 tabs", null, null)));
    queue.insert(entry(rxId, NOW, "PENDING_REVIEW"));
    when(inventory.findByName(any(), any()))
        .thenReturn(Optional.of(new InventoryStockPort.StockInfo(true, 10, 8500)));
    when(doctors.findForPrescription(any(), any(), any(), any())).thenReturn(Optional.empty());
    service.list(OWNER, "ALL", "DIGITAL", "A", 1, 20, "patient_name");
    service.list(OWNER, null, null, null, 1, 20, "received_at");
    service.get(OWNER, rxId);
    assertThatThrownBy(
            () ->
                service.list(
                    new MedmatePrincipal(STAFF, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    null,
                    null,
                    null,
                    1,
                    20,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.list(OWNER, null, "BAD", null, 1, 20, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    verify(orderLines, never()).replaceOrderLines(any(), any());
  }

  private UUID seedRx(String type, List<MedicineExtracted> meds) {
    UUID id = UUID.randomUUID();
    rxStore.insert(
        new PrescriptionRecord(
            id,
            CUST,
            type,
            "UPLOADED",
            "k/" + id,
            10,
            "image/jpeg",
            "Ravi Kumar",
            null,
            "Dr. Priya Sharma",
            null,
            "E_PRESCRIPTION".equals(type) ? "TELECONSULT" : "UPLOAD",
            meds,
            null,
            null,
            NOW.plus(Duration.ofDays(30)),
            null,
            NOW,
            NOW,
            null));
    return id;
  }

  private PharmacyRxQueueEntry entry(UUID rxId, Instant receivedAt, String status) {
    return entry(rxId, receivedAt, status, null);
  }

  private PharmacyRxQueueEntry entry(UUID rxId, Instant receivedAt, String status, UUID orderId) {
    return new PharmacyRxQueueEntry(
        UUID.randomUUID(),
        rxId,
        PHARM,
        orderId,
        receivedAt,
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
        receivedAt,
        receivedAt,
        null);
  }

  static final class FakeRxStore implements PrescriptionStore {
    private final Map<UUID, PrescriptionRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(PrescriptionRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public Optional<PrescriptionRecord> findByIdForCustomer(UUID id, UUID customerId) {
      return findById(id).filter(r -> r.customerId().equals(customerId));
    }

    @Override
    public Optional<PrescriptionRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Page listForCustomer(
        UUID customerId,
        String status,
        String type,
        int page,
        int limit,
        String sort,
        String order) {
      return new Page(List.of(), 0);
    }

    @Override
    public void softDelete(UUID id, Instant deletedAt, Instant updatedAt) {}

    @Override
    public void updateOcr(
        UUID id,
        String doctorName,
        java.time.LocalDate prescriptionDate,
        List<MedicineExtracted> medicines,
        Instant updatedAt) {}

    @Override
    public void updateStatus(UUID id, String status, Instant updatedAt) {}

    @Override
    public int markExpiredDue(Instant now, Instant updatedAt) {
      return 0;
    }
  }

  static final class FakeQueueStore implements PharmacyRxQueueStore {
    private final Map<UUID, PharmacyRxQueueEntry> byId = new ConcurrentHashMap<>();
    boolean duplicate;

    @Override
    public void insert(PharmacyRxQueueEntry entry) {
      byId.put(entry.id(), entry);
    }

    @Override
    public Optional<PharmacyRxQueueEntry> findByRxAndPharmacy(UUID rxId, UUID pharmacyId) {
      return byId.values().stream()
          .filter(e -> e.rxId().equals(rxId) && e.pharmacyId().equals(pharmacyId))
          .findFirst();
    }

    @Override
    public Optional<PharmacyRxQueueEntry> findLatestByRxId(UUID rxId) {
      return byId.values().stream().filter(e -> e.rxId().equals(rxId)).findFirst();
    }

    @Override
    public Page list(
        UUID pharmacyId,
        String status,
        String source,
        String search,
        int page,
        int limit,
        String sort) {
      List<PharmacyRxQueueEntry> all = new ArrayList<>(byId.values());
      all.sort(
          (a, b) -> {
            boolean ao = a.isOverdue(NOW);
            boolean bo = b.isOverdue(NOW);
            if (ao != bo) {
              return ao ? -1 : 1;
            }
            return a.receivedAt().compareTo(b.receivedAt());
          });
      return new Page(all, all.size());
    }

    @Override
    public Kpis computeKpis(UUID pharmacyId, Instant now) {
      return new Kpis(1, 1, 0, 0, 0, 38, 62.5, 91.3);
    }

    @Override
    public void markApproved(
        UUID id,
        List<ApprovedMedicine> medicines,
        UUID approvedBy,
        Instant approvedAt,
        String notes,
        boolean duplicateWarning,
        Instant updatedAt) {
      PharmacyRxQueueEntry e = byId.get(id);
      byId.put(
          id,
          new PharmacyRxQueueEntry(
              e.id(),
              e.rxId(),
              e.pharmacyId(),
              e.orderId(),
              e.receivedAt(),
              "APPROVED",
              medicines,
              approvedBy,
              approvedAt,
              null,
              null,
              null,
              null,
              null,
              null,
              notes,
              duplicateWarning,
              e.overdueNotifiedAt(),
              e.createdAt(),
              updatedAt,
              null));
    }

    @Override
    public void markRejected(
        UUID id,
        String reason,
        String customMessage,
        UUID rejectedBy,
        Instant rejectedAt,
        Instant updatedAt) {
      PharmacyRxQueueEntry e = byId.get(id);
      byId.put(
          id,
          new PharmacyRxQueueEntry(
              e.id(),
              e.rxId(),
              e.pharmacyId(),
              e.orderId(),
              e.receivedAt(),
              "REJECTED",
              null,
              null,
              null,
              reason,
              customMessage,
              rejectedBy,
              rejectedAt,
              null,
              null,
              null,
              false,
              e.overdueNotifiedAt(),
              e.createdAt(),
              updatedAt,
              null));
    }

    @Override
    public void markDispensed(UUID id, UUID dispensedBy, Instant dispensedAt, Instant updatedAt) {
      PharmacyRxQueueEntry e = byId.get(id);
      byId.put(
          id,
          new PharmacyRxQueueEntry(
              e.id(),
              e.rxId(),
              e.pharmacyId(),
              e.orderId(),
              e.receivedAt(),
              "DISPENSED",
              e.approvedMedicines(),
              e.approvedBy(),
              e.approvedAt(),
              null,
              null,
              null,
              null,
              dispensedBy,
              dispensedAt,
              e.notes(),
              e.duplicateWarning(),
              e.overdueNotifiedAt(),
              e.createdAt(),
              updatedAt,
              null));
    }

    @Override
    public void markOverdueNotified(UUID id, Instant notifiedAt, Instant updatedAt) {
      PharmacyRxQueueEntry e = byId.get(id);
      byId.put(
          id,
          new PharmacyRxQueueEntry(
              e.id(),
              e.rxId(),
              e.pharmacyId(),
              e.orderId(),
              e.receivedAt(),
              e.status(),
              e.approvedMedicines(),
              e.approvedBy(),
              e.approvedAt(),
              e.rejectedReason(),
              e.rejectedCustomMessage(),
              e.rejectedBy(),
              e.rejectedAt(),
              e.dispensedBy(),
              e.dispensedAt(),
              e.notes(),
              e.duplicateWarning(),
              notifiedAt,
              e.createdAt(),
              updatedAt,
              null));
    }

    @Override
    public List<PharmacyRxQueueEntry> findPendingOverdueUnnotified(Instant deadline, int limit) {
      return byId.values().stream()
          .filter(e -> "PENDING_REVIEW".equals(e.status()))
          .filter(e -> e.receivedAt().isBefore(deadline))
          .filter(e -> e.overdueNotifiedAt() == null)
          .limit(limit)
          .toList();
    }

    @Override
    public boolean hasDuplicateDispense(
        UUID customerId, String medicineName, Instant since, UUID excludeRxId) {
      return duplicate;
    }
  }
}
