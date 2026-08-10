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
import com.nammamedmate.prescription.application.port.out.RxAuditStore.Kpis;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListPage;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListRow;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RxComplianceAuditServiceMoreCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final MedmatePrincipal ADMIN =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal FINANCE =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  private RxAuditStore store;
  private PrescriptionStore rxStore;
  private CatalogueSchedulePort catalogue;
  private RxComplianceAuditService service;

  @BeforeEach
  void setUp() {
    store = mock(RxAuditStore.class);
    rxStore = mock(PrescriptionStore.class);
    catalogue = mock(CatalogueSchedulePort.class);
    when(catalogue.resolveSchedule(any())).thenReturn(Optional.empty());
    ComplianceExportStore export = mock(ComplianceExportStore.class);
    when(export.createDownloadUrl(any(), any())).thenReturn("u");
    DoctorCardPort doctors =
        (a, b, c, d) -> Optional.of(new DoctorCardPort.DoctorCard(c, null, null, false));
    PresignedUrlService presigner =
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("https://x/" + key + "?p=1", key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl("https://x/" + key + "?g=1", key, ttl);
          }
        };
    service =
        new RxComplianceAuditService(
            store,
            rxStore,
            catalogue,
            export,
            mock(NotificationDispatchPort.class),
            doctors,
            presigner,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void scheduleFallbacksAndDetailEdges() {
    UUID rxId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    PrescriptionRecord rx =
        new PrescriptionRecord(
            rxId,
            UUID.randomUUID(),
            "UPLOADED",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            "Pat",
            null,
            "Dr",
            LocalDate.of(2026, 1, 1),
            "UPLOAD",
            List.of(
                new MedicineExtracted("Drug A", "10", null, "Schedule H"),
                new MedicineExtracted("Drug B", "1", null, "SCH-X"),
                new MedicineExtracted("Drug C", "1", null, "weird"),
                new MedicineExtracted(null, "1", null, "H")),
            null,
            null,
            NOW.plusSeconds(100),
            null,
            NOW,
            NOW,
            null);
    when(store.findByRxId(rxId)).thenReturn(Optional.empty());
    when(rxStore.findById(rxId)).thenReturn(Optional.of(rx));

    // unknown schedule token → H1 fallback
    assertThat(
            service.createFromDispense(
                rxId,
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("Mystery", 1, BigDecimal.ONE, "CTRL")),
                rx,
                null))
        .isPresent();

    // OCR / catalogue schedule resolution when approved schedule blank
    when(catalogue.resolveSchedule("Drug A")).thenReturn(Optional.of("H"));
    UUID rx2 = Ids.newId();
    PrescriptionRecord rxOcrOnly =
        new PrescriptionRecord(
            rx2,
            UUID.randomUUID(),
            "UPLOADED",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            "Pat",
            null,
            "Dr",
            null,
            "UPLOAD",
            List.of(new MedicineExtracted("FromOcr", "5", null, "X")),
            null,
            null,
            NOW.plusSeconds(1),
            null,
            NOW,
            NOW,
            null);
    when(store.findByRxId(rx2)).thenReturn(Optional.empty());
    when(rxStore.findById(rx2)).thenReturn(Optional.of(rxOcrOnly));
    assertThat(
            service.createFromDispense(
                rx2,
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("FromOcr", 5, BigDecimal.ONE)),
                rxOcrOnly,
                NOW))
        .isPresent();

    // blank meds → OCR-only path
    UUID rx3 = Ids.newId();
    when(store.findByRxId(rx3)).thenReturn(Optional.empty());
    when(rxStore.findById(rx3)).thenReturn(Optional.of(rx));
    assertThat(service.createFromDispense(rx3, null, pharmacyId, List.of(), rx, NOW)).isPresent();

    RxAuditEntry entry =
        new RxAuditEntry(
            Ids.newId(),
            rxId,
            null,
            pharmacyId,
            "H",
            "AWAITING_AUDIT",
            NOW.plus(Duration.ofDays(7)),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    when(store.findByRxId(rxId)).thenReturn(Optional.of(entry));
    when(store.pharmacyName(pharmacyId)).thenReturn(Optional.empty());
    when(store.orderContext(any())).thenReturn(Optional.empty());
    when(store.dispenseContext(rxId, pharmacyId))
        .thenReturn(
            Optional.of(
                new DispenseContext(
                    NOW,
                    List.of(
                        Map.of("name", "Drug A", "quantity", 10),
                        new HashMap<>(Map.of("name", "x")),
                        Map.of("quantity", 2)),
                    "Pat",
                    "Dr")));
    when(store.findDuplicate(any(), any(), any(Integer.class), any(), any()))
        .thenReturn(Optional.empty());
    when(store.listActivity(rxId)).thenReturn(List.of());
    Map<String, Object> detail = service.get(ADMIN, rxId);
    assertThat(detail.get("file_url").toString()).contains("?");

    // refresh duplicate empty medicines / already flagged
    when(store.dispenseContext(rxId, pharmacyId))
        .thenReturn(Optional.of(new DispenseContext(NOW, List.of(), "Pat", "Dr")));
    service.get(ADMIN, rxId);
    RxAuditEntry flaggedDup =
        new RxAuditEntry(
            entry.id(),
            rxId,
            null,
            pharmacyId,
            "H",
            "AWAITING_AUDIT",
            entry.auditDeadline(),
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
    when(store.findByRxId(rxId)).thenReturn(Optional.of(flaggedDup));
    service.get(ADMIN, rxId);

    when(store.list(any(), any()))
        .thenReturn(
            new ListPage(
                List.of(new ListRow(entry, "P", "D", false, "Ph", null, "UPLOADED", "Drug, \"A\"")),
                1,
                new Kpis(1, 0, 0, 0, 0)));
    service.list(ADMIN, null, null, "UPLOADED", null, null, " ", null, null, null, false);

    assertThatThrownBy(() -> service.get(FINANCE, rxId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () -> service.list(FINANCE, null, null, null, null, null, null, null, 1, 20, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(rxStore.findById(rxId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(ADMIN, rxId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_NOT_FOUND");

    Map<String, Object> withNull = new HashMap<>();
    withNull.put("n", null);
    assertThat(RxComplianceAuditService.toJson(withNull)).contains("null");
    assertThat(
            RxComplianceAuditService.buildCsv(
                List.of(new ListRow(entry, null, null, false, null, null, "UPLOADED", null))))
        .contains("AWAITING_AUDIT");

    // mark overdue no-op path
    when(store.findAwaitingPastDeadline(any(), any(Integer.class))).thenReturn(List.of(entry));
    when(store.markOverdue(any(), any())).thenReturn(0);
    assertThat(service.markOverdueAudits()).isZero();
  }
}
