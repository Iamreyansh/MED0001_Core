package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.PresignedUrlService.PresignedUrl;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.DispenseContext;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.DuplicateMatch;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.Kpis;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListPage;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListRow;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.OrderContext;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.Stats;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import com.nammamedmate.prescription.domain.RxAuditEntry;
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

class RxComplianceAuditServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final MedmatePrincipal ADMIN =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  @Test
  void branchesForScheduleFiltersValidationAndDuplicateSkip() {
    RxAuditStore store = mock(RxAuditStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    CatalogueSchedulePort catalogue = mock(CatalogueSchedulePort.class);
    when(catalogue.resolveSchedule(any())).thenReturn(Optional.empty());
    when(catalogue.resolveSchedule("Sch-X Morphine")).thenReturn(Optional.of("X"));
    ComplianceExportStore export = mock(ComplianceExportStore.class);
    when(export.createDownloadUrl(any(), any())).thenReturn("u");
    DoctorCardPort doctors = mock(DoctorCardPort.class);
    when(doctors.findForPrescription(any(), any(), any(), any())).thenReturn(Optional.empty());
    NotificationDispatchPort notifications = mock(NotificationDispatchPort.class);
    PresignedUrlService presigner =
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("https://x/" + key, key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl("https://x/" + key, key, ttl);
          }
        };
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    RxComplianceAuditService service =
        new RxComplianceAuditService(
            store,
            rxStore,
            catalogue,
            export,
            notifications,
            doctors,
            presigner,
            limiter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    UUID rxId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    PrescriptionRecord rx =
        new PrescriptionRecord(
            rxId,
            UUID.randomUUID(),
            "E_PRESCRIPTION",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            "Pat",
            null,
            "Dr",
            null,
            "TELECONSULT",
            List.of(new MedicineExtracted("Sch-X Morphine", "1", null, "Schedule X")),
            orderId,
            null,
            NOW.plusSeconds(1000),
            null,
            NOW,
            NOW,
            null);
    when(rxStore.findById(any())).thenReturn(Optional.of(rx));
    when(store.findByRxId(any())).thenReturn(Optional.empty());
    when(store.findDuplicate(any(), any(), any(Integer.class), any(), any()))
        .thenReturn(Optional.of(new DuplicateMatch(Ids.newId(), Ids.newId())));

    assertThat(
            service.createFromDispense(
                rxId,
                orderId,
                pharmacyId,
                List.of(new ApprovedMedicine("Sch-X Morphine", 1, BigDecimal.ONE)),
                rx,
                NOW))
        .isPresent();

    RxAuditEntry entry =
        new RxAuditEntry(
            Ids.newId(),
            rxId,
            orderId,
            pharmacyId,
            "X",
            "AWAITING_AUDIT",
            NOW.plus(Duration.ofHours(24)),
            true,
            Ids.newId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    when(store.findByRxId(rxId)).thenReturn(Optional.of(entry));
    when(store.pharmacyName(pharmacyId)).thenReturn(Optional.of("P"));
    when(store.orderContext(orderId)).thenReturn(Optional.of(new OrderContext("ORD", "P")));
    when(store.dispenseContext(rxId, pharmacyId))
        .thenReturn(
            Optional.of(
                new DispenseContext(
                    NOW, List.of(Map.of("name", "Sch-X Morphine", "quantity", 1)), "Pat", "Dr")));
    when(store.listActivity(rxId)).thenReturn(List.of(Map.of("action", "RX_VERIFIED")));

    Map<String, Object> detail = service.get(ADMIN, rxId);
    assertThat(detail.get("possible_duplicate")).isEqualTo(true);
    assertThat(detail.get("order_context")).isNotNull();

    when(store.list(any(), any()))
        .thenReturn(
            new ListPage(
                List.of(new ListRow(entry, "Pat", "Dr", true, "P", NOW, "DIGITAL", "Drug")),
                1,
                new Kpis(1, 0, 1, 0, 90d)));
    assertThat(
            service
                .list(
                    ADMIN,
                    "X",
                    "AWAITING_AUDIT",
                    "DIGITAL",
                    "2026-07-01",
                    "2026-07-31",
                    "Pat",
                    pharmacyId,
                    0,
                    0,
                    false)
                .data()
                .get("kpis"))
        .isNotNull();

    when(store.statistics(any(), any()))
        .thenReturn(
            new Stats(Map.of("H", 1d, "H1", 2d, "X", 3d), 1d, List.of(), List.of(), 1, 1, 0, 0));
    assertThat(service.statistics(ADMIN, "2026-07-01", "2026-07-31").get("total_audited"))
        .isEqualTo(1L);

    assertThatThrownBy(
            () -> service.list(ADMIN, "Z", null, null, null, null, null, null, 1, 20, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.list(ADMIN, null, "NOPE", null, null, null, null, null, 1, 20, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.list(ADMIN, null, null, "SMS", null, null, null, null, 1, 20, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.list(ADMIN, null, null, null, "bad", null, null, null, 1, 20, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.statistics(ADMIN, "2026-08-01", "2026-07-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.verify(ADMIN, rxId, false, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify(ADMIN, rxId, true, null, "x".repeat(1001)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.flag(ADMIN, rxId, "", "HIGH"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.flag(ADMIN, rxId, "r".repeat(501), "HIGH"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.get(ADMIN, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_NOT_FOUND");

    PrescriptionRecord plain =
        new PrescriptionRecord(
            Ids.newId(),
            UUID.randomUUID(),
            "UPLOADED",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            null,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            NOW.plusSeconds(1),
            null,
            NOW,
            NOW,
            null);
    assertThat(
            service.createFromDispense(
                plain.id(),
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("Unknown", 1, null, "schedule h1")),
                plain,
                NOW))
        .isPresent();

    for (int i = 0; i < 60; i++) {
      limiter.tryAcquire("rxaudit:list:" + ADMIN.subject(), 60, 60);
    }
    assertThatThrownBy(
            () -> service.list(ADMIN, null, null, null, null, null, null, null, 1, 20, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }
}
