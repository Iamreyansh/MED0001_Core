package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.InventoryBatchPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ExportJob;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ListPage;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.PharmacySnapshot;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.ScheduleDrugRegisterEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduleDrugRegisterServiceFinalCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:30:00Z");
  private static final UUID PHARM = UUID.randomUUID();
  private static final UUID RX = UUID.randomUUID();
  private static final UUID STAFF = UUID.randomUUID();

  private ScheduleDrugRegisterStore store;
  private PrescriptionStore rxStore;
  private CatalogueSchedulePort catalogue;
  private DoctorCardPort doctors;
  private InventoryBatchPort batch;
  private ComplianceExportStore exportStore;
  private RateLimiter rateLimiter;
  private ScheduleDrugRegisterService service;

  @BeforeEach
  void setUp() {
    store = mock(ScheduleDrugRegisterStore.class);
    rxStore = mock(PrescriptionStore.class);
    catalogue = mock(CatalogueSchedulePort.class);
    doctors = mock(DoctorCardPort.class);
    batch = mock(InventoryBatchPort.class);
    exportStore = mock(ComplianceExportStore.class);
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(exportStore.createDownloadUrl(any(), any())).thenReturn("u");
    service =
        new ScheduleDrugRegisterService(
            store,
            rxStore,
            catalogue,
            doctors,
            batch,
            exportStore,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void coversPrescriberPatientScheduleUnitAndPollGenerating() {
    when(rxStore.findById(RX))
        .thenReturn(
            Optional.of(
                new PrescriptionRecord(
                    RX,
                    UUID.randomUUID(),
                    "UPLOADED",
                    "VERIFIED",
                    "k",
                    1,
                    "image/jpeg",
                    "Pat",
                    null,
                    "  ",
                    null,
                    "CUSTOMER_UPLOAD",
                    null,
                    null,
                    null,
                    NOW,
                    null,
                    NOW,
                    NOW,
                    null)));
    when(store.pharmacy(PHARM)).thenReturn(Optional.of(new PharmacySnapshot("P", "LIC")));
    when(store.staffName(STAFF)).thenReturn(Optional.of("S"));
    when(store.orderIdForRx(RX, PHARM)).thenReturn(Optional.empty());
    when(doctors.findForPrescription(any(), any(), any(), any()))
        .thenReturn(Optional.of(new DoctorCardPort.DoctorCard("  ", "MBBS", "  ", true)));
    when(store.nextSno(any(), any())).thenReturn(1);
    when(store.nextRxSeq(any(), anyInt())).thenReturn(9);
    when(store.latestRunningBalance(any(), any(), any())).thenReturn(Optional.of(5));
    when(batch.findOpeningStock(any(), any())).thenReturn(Optional.empty());
    when(catalogue.resolveSchedule("Weird Capsule")).thenReturn(Optional.of("H1"));

    ArrayList<ApprovedMedicine> meds = new ArrayList<>();
    meds.add(new ApprovedMedicine(null, 1, BigDecimal.ONE, "H1"));
    meds.add(new ApprovedMedicine("Weird Capsule", 1, BigDecimal.ONE, "UNKNOWN"));
    meds.add(new ApprovedMedicine("Plain", 1, BigDecimal.ONE, "NONE"));
    meds.add(new ApprovedMedicine("H only", 1, BigDecimal.ONE, "H"));
    meds.add(new ApprovedMedicine("X drug", 1, BigDecimal.ONE, "X"));
    meds.add(new ApprovedMedicine("Syrup Only", 1, BigDecimal.ONE, "H1"));
    meds.add(new ApprovedMedicine("BlankSched", 1, BigDecimal.ONE, "  "));
    when(catalogue.resolveSchedule("BlankSched")).thenReturn(Optional.of("H1"));
    service.recordDispense(PHARM, RX, STAFF, meds);

    ArgumentCaptor<ScheduleDrugRegisterEntry> cap =
        ArgumentCaptor.forClass(ScheduleDrugRegisterEntry.class);
    verify(store, atLeastOnce()).insert(cap.capture());
    assertThat(cap.getAllValues()).isNotEmpty();
    assertThat(cap.getAllValues().stream().anyMatch(e -> "CAPSULES".equals(e.unit()))).isTrue();
    assertThat(cap.getAllValues().stream().anyMatch(e -> "ML".equals(e.unit()))).isTrue();
    assertThat(cap.getAllValues().get(0).prescriberName()).isEqualTo("Unknown");
    assertThat(cap.getAllValues().get(0).prescriberRegNo()).isEqualTo("UNKNOWN");
    assertThat(cap.getAllValues().get(0).patientName()).isEqualTo("Pat");

    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    UUID jobId = UUID.randomUUID();
    AtomicInteger finds = new AtomicInteger();
    when(store.findExportJob(jobId))
        .thenAnswer(
            inv -> {
              int n = finds.incrementAndGet();
              if (n == 1) {
                return Optional.of(
                    new ExportJob(
                        jobId,
                        PHARM,
                        "H1",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31),
                        "GENERATING",
                        null,
                        null,
                        admin.subject(),
                        null,
                        null,
                        null,
                        NOW));
              }
              if (n == 2) {
                return Optional.of(
                    new ExportJob(
                        jobId,
                        PHARM,
                        "H1",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31),
                        "GENERATING",
                        null,
                        null,
                        admin.subject(),
                        null,
                        null,
                        null,
                        NOW));
              }
              return Optional.of(
                  new ExportJob(
                      jobId,
                      PHARM,
                      "H1",
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(2026, 1, 31),
                      "READY",
                      "k",
                      2,
                      admin.subject(),
                      NOW,
                      NOW,
                      null,
                      NOW));
            });
    when(store.listAll(any())).thenReturn(List.of());
    Map<String, Object> poll = service.pollExportJob(admin, jobId);
    assertThat(poll.get("status")).isEqualTo("READY");
  }

  @Test
  void coversNullPrincipalBlankScheduleExportDatesAndCsvNewline() {
    assertThatThrownBy(() -> service.listAdmin(null, "H1", null, null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.retentionRules(null))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.createExportJob(null, PHARM, "H1", "2026-01-01", "2026-01-31"))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.listPharmacy(null, "H1", null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listAdmin(admin, null, null, null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SCHEDULE");
    assertThatThrownBy(() -> service.listAdmin(admin, "  ", null, null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SCHEDULE");
    assertThatThrownBy(() -> service.createExportJob(admin, null, "H1", "2026-01-01", "2026-01-31"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");

    when(store.listAll(any())).thenReturn(List.of());
    assertThat(service.listAdmin(admin, "H1", null, "  ", "  ", "  ", 1, 10, true).data())
        .containsKey("download_url");
    assertThat(
            service
                .listAdmin(admin, "H1", null, null, "2026-01-01", "2026-01-31", 1, 10, true)
                .data())
        .containsKey("download_url");
    when(store.list(any())).thenReturn(new ListPage(null, 0, 0));
    assertThat(service.listAdmin(admin, "H1", null, null, null, null, null, 999, false))
        .isNotNull();
    assertThat(service.listAdmin(admin, "H1", null, null, null, null, null, null, false))
        .isNotNull();
    assertThat(service.listAdmin(admin, "H1", null, null, null, null, 0, 50, false)).isNotNull();
    assertThat(service.listAdmin(admin, "H1", null, null, "  ", "  ", 2, 50, false)).isNotNull();
    assertThat(
            service
                .listAdmin(admin, "H1", null, null, "2026-01-01", "2026-01-31", 1, 50, false)
                .data()
                .get("entries"))
        .asList()
        .isEmpty();
    assertThat(
            service
                .listAdmin(admin, "H1", null, null, null, null, 1, 50, false)
                .data()
                .get("entries"))
        .asList()
        .isEmpty();

    ScheduleDrugRegisterEntry e =
        new ScheduleDrugRegisterEntry(
            UUID.randomUUID(),
            1,
            PHARM,
            "H1",
            RX,
            "RX-2026-00001",
            null,
            "P\nX",
            null,
            "D",
            "R",
            "Drug",
            null,
            1,
            "TABLETS",
            0,
            "L",
            "S",
            STAFF,
            NOW,
            NOW,
            false,
            NOW);
    assertThat(ScheduleDrugRegisterService.buildRegulatoryCsv(List.of(e))).contains("\"P\nX\"");

    when(store.pharmacyExists(PHARM)).thenReturn(true);
    when(store.findExportJob(any()))
        .thenReturn(
            Optional.of(
                new ExportJob(
                    UUID.randomUUID(),
                    PHARM,
                    "H1",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    "GENERATING",
                    null,
                    null,
                    admin.subject(),
                    null,
                    null,
                    null,
                    NOW)));
    doThrow(new RuntimeException()).when(store).listAll(any());
    service.createExportJob(admin, PHARM, "H1", "2026-01-01", "2026-01-31");
    ArgumentCaptor<ExportJob> jobCap = ArgumentCaptor.forClass(ExportJob.class);
    verify(store).updateExportJob(jobCap.capture());
    assertThat(jobCap.getValue().errorMessage()).isEqualTo("export failed");

    UUID readyNullKey = UUID.randomUUID();
    when(store.findExportJob(readyNullKey))
        .thenReturn(
            Optional.of(
                new ExportJob(
                    readyNullKey,
                    PHARM,
                    "H1",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    "READY",
                    null,
                    0,
                    admin.subject(),
                    NOW,
                    NOW,
                    null,
                    NOW)));
    assertThat(service.pollExportJob(admin, readyNullKey)).doesNotContainKey("download_url");

    UUID gone = UUID.randomUUID();
    AtomicInteger n = new AtomicInteger();
    doReturn(List.of()).when(store).listAll(any());
    when(store.findExportJob(gone))
        .thenAnswer(
            inv -> {
              int step = n.incrementAndGet();
              if (step == 1) {
                return Optional.of(
                    new ExportJob(
                        gone,
                        PHARM,
                        "H1",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31),
                        "GENERATING",
                        null,
                        null,
                        admin.subject(),
                        null,
                        null,
                        null,
                        NOW));
              }
              // completeExportJob: job missing → early return; poll reload → NOT_FOUND
              return Optional.empty();
            });
    assertThatThrownBy(() -> service.pollExportJob(admin, gone))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");

    MedmatePrincipal rider =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.RIDER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listPharmacy(rider, "H1", null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    service.recordDispense(
        PHARM, null, STAFF, List.of(new ApprovedMedicine("A", 1, BigDecimal.ONE, "H1")));
    service.recordDispense(PHARM, RX, STAFF, null);
  }

  @Test
  void coversDoctorBlankFallbackUnknownDoctorAndPharmacyMissingInToApi() {
    when(rxStore.findById(RX))
        .thenReturn(
            Optional.of(
                new PrescriptionRecord(
                    RX,
                    UUID.randomUUID(),
                    "UPLOADED",
                    "VERIFIED",
                    "k",
                    1,
                    "image/jpeg",
                    null,
                    null,
                    "Dr From Rx",
                    null,
                    "CUSTOMER_UPLOAD",
                    null,
                    null,
                    null,
                    NOW,
                    null,
                    NOW,
                    NOW,
                    null)));
    when(store.pharmacy(PHARM)).thenReturn(Optional.of(new PharmacySnapshot("P", null)));
    when(store.staffName(STAFF)).thenReturn(Optional.of("S"));
    when(store.orderIdForRx(RX, PHARM)).thenReturn(Optional.empty());
    when(doctors.findForPrescription(any(), any(), any(), any()))
        .thenReturn(Optional.of(new DoctorCardPort.DoctorCard(null, null, null, false)))
        .thenReturn(Optional.of(new DoctorCardPort.DoctorCard("Dr Card", "MBBS", "REG-9", true)));
    when(store.nextSno(any(), any())).thenReturn(1, 2);
    when(store.nextRxSeq(any(), anyInt())).thenReturn(1, 2);
    when(store.latestRunningBalance(any(), any(), any())).thenReturn(Optional.empty());
    when(batch.findOpeningStock(any(), any())).thenReturn(Optional.empty());
    service.recordDispense(
        PHARM, RX, STAFF, List.of(new ApprovedMedicine("Alprazolam", 1, BigDecimal.ONE, "H1")));
    when(rxStore.findById(RX))
        .thenReturn(
            Optional.of(
                new PrescriptionRecord(
                    RX,
                    UUID.randomUUID(),
                    "UPLOADED",
                    "VERIFIED",
                    "k",
                    1,
                    "image/jpeg",
                    "  ",
                    null,
                    "Dr From Rx",
                    null,
                    "CUSTOMER_UPLOAD",
                    null,
                    null,
                    null,
                    NOW,
                    null,
                    NOW,
                    NOW,
                    null)));
    service.recordDispense(
        PHARM, RX, STAFF, List.of(new ApprovedMedicine("Alprazolam", 1, BigDecimal.ONE, "H1")));
    ArgumentCaptor<ScheduleDrugRegisterEntry> cap =
        ArgumentCaptor.forClass(ScheduleDrugRegisterEntry.class);
    verify(store, atLeastOnce()).insert(cap.capture());
    assertThat(cap.getAllValues().get(0).prescriberName()).isEqualTo("Dr From Rx");
    assertThat(cap.getAllValues().get(0).prescriberRegNo()).isEqualTo("UNKNOWN");
    assertThat(cap.getAllValues().get(0).patientName()).isEqualTo("Unknown");
    assertThat(cap.getAllValues().get(1).prescriberName()).isEqualTo("Dr Card");
    assertThat(cap.getAllValues().get(1).prescriberRegNo()).isEqualTo("REG-9");
    assertThat(cap.getAllValues().get(1).patientName()).isEqualTo("Unknown");
    assertThat(cap.getAllValues().get(0).pharmacyLicenseNo()).isEqualTo("UNKNOWN");

    // completeExportJob early-return when status already READY
    MedmatePrincipal adminReady =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    when(store.pharmacyExists(PHARM)).thenReturn(true);
    when(store.findExportJob(any()))
        .thenReturn(
            Optional.of(
                new ExportJob(
                    UUID.randomUUID(),
                    PHARM,
                    "H1",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    "READY",
                    "k",
                    1,
                    adminReady.subject(),
                    NOW,
                    NOW,
                    null,
                    NOW)));
    Map<String, Object> created =
        service.createExportJob(adminReady, PHARM, "H1", "2026-01-01", "2026-01-31");
    assertThat(created.get("status")).isEqualTo("GENERATING");

    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    when(store.list(any()))
        .thenReturn(
            new ListPage(
                List.of(
                    new ScheduleDrugRegisterEntry(
                        UUID.randomUUID(),
                        1,
                        PHARM,
                        "H1",
                        RX,
                        "RX-2026-00001",
                        null,
                        "P",
                        null,
                        "D",
                        "R",
                        "Drug",
                        null,
                        1,
                        "TABLETS",
                        0,
                        "L",
                        "S",
                        STAFF,
                        NOW,
                        NOW,
                        false,
                        NOW)),
                1,
                1));
    when(store.pharmacy(PHARM)).thenReturn(Optional.empty());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries =
        (List<Map<String, Object>>)
            service
                .listAdmin(admin, "H1", PHARM, null, null, null, 1, 50, false)
                .data()
                .get("entries");
    assertThat(entries.get(0).get("pharmacy_name")).isNull();
  }
}
