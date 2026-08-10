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

class RxComplianceAuditServiceFinalCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final MedmatePrincipal ADMIN =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal SUPER =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal OPS =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @Test
  void remainingBranches() {
    RxAuditStore store = mock(RxAuditStore.class);
    PrescriptionStore rxStore = mock(PrescriptionStore.class);
    CatalogueSchedulePort catalogue = name -> Optional.empty();
    ComplianceExportStore export = mock(ComplianceExportStore.class);
    when(export.createDownloadUrl(any(), any())).thenReturn("u");
    DoctorCardPort doctors =
        (a, b, c, d) -> Optional.of(new DoctorCardPort.DoctorCard(c, null, null, false));
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
    RxComplianceAuditService service =
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

    UUID rxId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    PrescriptionRecord plain =
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
            null,
            "UPLOAD",
            null,
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    RxAuditEntry overdue =
        new RxAuditEntry(
            Ids.newId(),
            rxId,
            null,
            pharmacyId,
            "H1",
            "OVERDUE_AUDIT",
            NOW.minusSeconds(1),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    when(store.findByRxId(any())).thenReturn(Optional.empty());
    when(rxStore.findById(any())).thenReturn(Optional.of(plain));

    assertThat(
            service.createFromDispense(
                rxId,
                null,
                pharmacyId,
                List.of(
                    new ApprovedMedicine(null, 1, BigDecimal.ONE, "H1"),
                    new ApprovedMedicine("  ", 1, BigDecimal.ONE, "H1"),
                    new ApprovedMedicine("Drug", 1, BigDecimal.ONE, "H1")),
                plain,
                NOW))
        .isPresent();
    assertThat(
            service.createFromDispense(
                Ids.newId(),
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("NeedOcr", 1, null, null)),
                null,
                NOW))
        .isEmpty();
    assertThat(
            service.createFromDispense(
                Ids.newId(),
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("Ctrl", 1, null, "H1")),
                null,
                NOW))
        .isPresent();
    PrescriptionRecord ocrH1 =
        new PrescriptionRecord(
            Ids.newId(),
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
            List.of(new MedicineExtracted("Ctrl", "1", null, "H1")),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    assertThat(service.createFromDispense(ocrH1.id(), null, pharmacyId, List.of(), ocrH1, NOW))
        .isPresent();
    assertThat(
            service.createFromDispense(
                Ids.newId(),
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("NeedOcr", 1, null, null)),
                plain,
                NOW))
        .isEmpty();
    PrescriptionRecord withExtracted =
        new PrescriptionRecord(
            Ids.newId(),
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
            List.of(new MedicineExtracted("Other", "1", null, null)),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    assertThat(
            service.createFromDispense(
                withExtracted.id(),
                null,
                pharmacyId,
                List.of(
                    new ApprovedMedicine(null, 1, null, null),
                    new ApprovedMedicine("Unmatched", 1, null, null)),
                withExtracted,
                NOW))
        .isEmpty();
    assertThat(service.createFromDispense(Ids.newId(), null, pharmacyId, null, plain, NOW))
        .isEmpty();
    // null medicines but OCR H1 → detectDuplicate(rx, null)
    assertThat(service.createFromDispense(Ids.newId(), null, pharmacyId, null, ocrH1, NOW))
        .isPresent();
    // empty medicinesExtracted list (not null) → manually entered checklist
    PrescriptionRecord emptyExtracted =
        new PrescriptionRecord(
            Ids.newId(),
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
            List.of(),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    RxAuditEntry emptyEntry =
        new RxAuditEntry(
            Ids.newId(),
            emptyExtracted.id(),
            null,
            pharmacyId,
            "H",
            "AWAITING_AUDIT",
            NOW.plusSeconds(100),
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
    when(store.findByRxId(emptyExtracted.id())).thenReturn(Optional.of(emptyEntry));
    when(rxStore.findById(emptyExtracted.id())).thenReturn(Optional.of(emptyExtracted));
    when(store.dispenseContext(emptyExtracted.id(), pharmacyId)).thenReturn(Optional.empty());
    when(store.listActivity(emptyExtracted.id())).thenReturn(List.of());
    assertThat(service.get(ADMIN, emptyExtracted.id()).get("verification_checklist")).isNotNull();
    assertThat(
            service.createFromDispense(
                Ids.newId(),
                null,
                pharmacyId,
                List.of(
                    new ApprovedMedicine("a", 1, null, "SCHEDULE X"),
                    new ApprovedMedicine("b", 1, null, "foo X"),
                    new ApprovedMedicine("c", 1, null, "SCHEDULE H"),
                    new ApprovedMedicine("d", 1, null, "bar H")),
                plain,
                NOW))
        .isPresent();

    when(store.findByRxId(rxId)).thenReturn(Optional.of(overdue));
    when(rxStore.findById(rxId)).thenReturn(Optional.of(plain));
    when(store.dispenseContext(rxId, pharmacyId)).thenReturn(Optional.empty());
    when(store.pharmacyName(pharmacyId)).thenReturn(Optional.of("P"));
    when(store.listActivity(rxId)).thenReturn(List.of());
    assertThat(service.get(ADMIN, rxId).get("verification_checklist")).isNotNull();
    assertThat(service.get(SUPER, rxId)).containsKey("file_url");
    assertThat(service.get(OPS, rxId)).doesNotContainKey("file_url");
    // OCR checklist + enrichSchedules with existing schedule / blank schedule
    UUID ocrRx = Ids.newId();
    PrescriptionRecord ocrRxRec =
        new PrescriptionRecord(
            ocrRx,
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
            List.of(new MedicineExtracted("Alprazolam", "10", "1-0-1", "H1")),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    RxAuditEntry awaiting =
        new RxAuditEntry(
            Ids.newId(),
            ocrRx,
            null,
            pharmacyId,
            "H1",
            "AWAITING_AUDIT",
            NOW.minusSeconds(1),
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
    when(store.findByRxId(ocrRx)).thenReturn(Optional.of(awaiting));
    when(rxStore.findById(ocrRx)).thenReturn(Optional.of(ocrRxRec));
    java.util.HashMap<String, Object> blankSch = new java.util.HashMap<>();
    blankSch.put("name", null);
    blankSch.put("quantity", 1);
    blankSch.put("schedule", "  ");
    when(store.dispenseContext(ocrRx, pharmacyId))
        .thenReturn(
            Optional.of(
                new DispenseContext(
                    NOW,
                    List.of(
                        Map.of("name", "Alprazolam", "quantity", 10, "schedule", "H1"), blankSch),
                    "Pat",
                    "Dr")));
    when(store.pharmacyName(pharmacyId)).thenReturn(Optional.of("P"));
    when(store.listActivity(ocrRx)).thenReturn(List.of());
    assertThat(service.get(ADMIN, ocrRx).get("verification_checklist")).isNotNull();
    assertThat(RxComplianceAuditService.toJson(Map.of())).isEqualTo("{}");

    service.verify(SUPER, rxId, true, null, null);
    assertThatThrownBy(() -> service.verify(ADMIN, rxId, false, "  ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    service.flag(ADMIN, rxId, "medium case", "MEDIUM");
    assertThat(
            RxComplianceAuditService.buildCsv(
                List.of(
                    new ListRow(overdue, "P", "D", false, "Ph", NOW, "UPLOADED", "has\"quote"))))
        .contains("has\"\"");
    // blank schedule string (not null) for isBlank true side
    assertThat(
            service.createFromDispense(
                Ids.newId(),
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("z", 1, null, "   ")),
                plain,
                NOW))
        .isEmpty();
    PrescriptionRecord noPatient =
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
            "Dr",
            null,
            "UPLOAD",
            null,
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    assertThat(
            service.createFromDispense(
                noPatient.id(),
                null,
                pharmacyId,
                List.of(new ApprovedMedicine("H1 Drug", 1, null, "H1")),
                noPatient,
                NOW))
        .isPresent();
    assertThatThrownBy(() -> service.flag(ADMIN, rxId, null, "LOW"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.flag(ADMIN, rxId, "reason", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.list(any(), any()))
        .thenReturn(
            new ListPage(
                List.of(
                    new ListRow(overdue, "P", "D", false, "Ph", NOW, "UPLOADED", "D"),
                    new ListRow(awaiting, "P", "D", false, "Ph", null, "UPLOADED", "D")),
                2,
                new Kpis(0, 0, 0, 0, 0)));
    service.list(SUPER, "  ", "  ", "  ", "  ", "  ", "  ", null, 1, 20, false);
    service.list(OPS, null, null, null, null, null, null, null, 1, 20, false);
    assertThat(
            ((List<?>)
                    service
                        .list(ADMIN, null, "ALL", null, null, null, null, null, 1, 20, false)
                        .data()
                        .get("prescriptions"))
                .get(0))
        .extracting(m -> ((Map<?, ?>) m).get("is_overdue"))
        .isEqualTo(true);

    when(store.listAllForExport(any()))
        .thenReturn(
            List.of(
                new ListRow(overdue, "P", "D", false, "Ph", NOW, "UPLOADED", "D"),
                new ListRow(overdue, "P", "D", false, "Ph", null, "UPLOADED", "D")));
    service.list(ADMIN, null, null, null, null, null, null, null, 1, 20, true);

    assertThat(
            RxComplianceAuditService.buildCsv(
                List.of(
                    new ListRow(overdue, "P", "D", false, "Ph", NOW, "UPLOADED", "line\nbreak"))))
        .contains("line");
    assertThat(RxAuditEntry.higher(null, "NONE")).isEqualTo("NONE");
    assertThat(new Stats(null, 0, null, null, 0, 0, 0, 0).topFlaggedDrugs()).isEmpty();
    assertThat(new ListPage(null, 0, new Kpis(0, 0, 0, 0, 0)).items()).isEmpty();
    assertThat(new DispenseContext(NOW, null, "p", "d").medicines()).isEmpty();
  }
}
