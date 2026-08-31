package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleDrugRegisterServiceCoverageTest {

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
  void recordDispenseFallbacksAndErrors() {
    assertThatThrownBy(
            () ->
                service.recordDispense(
                    PHARM, RX, STAFF, List.of(new ApprovedMedicine("A", 1, BigDecimal.ONE, "H1"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RX_NOT_FOUND");

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
                    null,
                    null,
                    "CUSTOMER_UPLOAD",
                    null,
                    UUID.randomUUID(),
                    null,
                    NOW,
                    null,
                    NOW,
                    NOW,
                    null)));
    when(store.pharmacy(PHARM)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.recordDispense(
                    PHARM, RX, STAFF, List.of(new ApprovedMedicine("A", 1, BigDecimal.ONE, "H1"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");

    when(store.pharmacy(PHARM)).thenReturn(Optional.of(new PharmacySnapshot("P", "")));
    when(store.staffName(STAFF)).thenReturn(Optional.empty());
    when(store.orderIdForRx(RX, PHARM)).thenReturn(Optional.empty());
    when(doctors.findForPrescription(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(store.nextSno(any(), any())).thenReturn(1);
    when(store.nextRxSeq(any(), anyInt())).thenReturn(1);
    when(store.latestRunningBalance(any(), any(), any())).thenReturn(Optional.empty());
    when(batch.findOpeningStock(any(), any())).thenReturn(Optional.empty());
    java.util.ArrayList<ApprovedMedicine> meds = new java.util.ArrayList<>();
    meds.add(null);
    meds.add(new ApprovedMedicine("", 1, BigDecimal.ONE, "H1"));
    meds.add(new ApprovedMedicine("Capsules Drug", 0, BigDecimal.ONE, "H1"));
    meds.add(new ApprovedMedicine("Capsules Drug", 2, BigDecimal.ONE, "H1"));
    service.recordDispense(PHARM, RX, STAFF, meds);
    verify(store).insert(any());
  }

  @Test
  void authAndValidationBranches() {
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    MedmatePrincipal ownerNoPharm =
        new MedmatePrincipal(STAFF, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    MedmatePrincipal owner =
        new MedmatePrincipal(STAFF, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
    when(store.list(any())).thenReturn(new ListPage(List.of(), 0, 0));
    assertThat(service.listPharmacy(owner, null, null, null, null, 1, 10, false)).isNotNull();
    assertThat(service.listPharmacy(owner, "ALL", null, null, null, 1, 10, false)).isNotNull();
    assertThat(service.listAdmin(ops, "H1", null, "alp", "2026-01-01", "2026-01-31", 0, 0, false))
        .isNotNull();
    assertThatThrownBy(
            () -> service.listAdmin(customer, "H1", null, null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(
            () -> service.listPharmacy(ownerNoPharm, "H1", null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listAdmin(ops, "H1", null, null, "bad", null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.pharmacyExists(PHARM)).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.createExportJob(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j"),
                    PHARM,
                    "H1",
                    "2026-06-01",
                    "2026-05-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void exportJobFailureAndPollGenerating() {
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    when(store.pharmacyExists(PHARM)).thenReturn(true);
    UUID jobId = UUID.randomUUID();
    when(store.findExportJob(any()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              return Optional.of(
                  new ExportJob(
                      id,
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
            });
    when(store.listAll(any())).thenThrow(new RuntimeException("boom"));
    service.createExportJob(admin, PHARM, "H1", "2026-01-01", "2026-01-31");
    verify(store).updateExportJob(any());

    when(store.findExportJob(jobId))
        .thenReturn(
            Optional.of(
                new ExportJob(
                    jobId,
                    PHARM,
                    "H1",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    "FAILED",
                    null,
                    null,
                    admin.subject(),
                    null,
                    null,
                    "boom",
                    NOW)));
    when(store.findExportJob(argThat(id -> !jobId.equals(id)))).thenReturn(Optional.empty());
    assertThat(service.pollExportJob(admin, jobId).get("error_message")).isEqualTo("boom");
    assertThatThrownBy(() -> service.pollExportJob(admin, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void rateLimitedAndPharmacyExport() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listAdmin(admin, "H1", null, null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    MedmatePrincipal owner =
        new MedmatePrincipal(STAFF, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
    when(store.listAll(any())).thenReturn(List.of());
    Map<String, Object> exported =
        service.listPharmacy(owner, "H1", null, null, null, 1, 10, true).data();
    assertThat(exported.get("record_count")).isEqualTo(0);
  }

  @Test
  void listResultNullDataAndCsvStatic() {
    assertThat(new ScheduleDrugRegisterService.ListResult(null, null).data()).isEmpty();
    ScheduleDrugRegisterEntry e =
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
            NOW);
    String csv = ScheduleDrugRegisterService.buildRegulatoryCsv(List.of(e));
    assertThat(csv).startsWith(ScheduleDrugRegisterService.CSV_HEADER);
  }

  @Test
  void completeExportWhenAlreadyReadyIsNoopPath() {
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    UUID jobId = UUID.randomUUID();
    when(store.findExportJob(jobId))
        .thenReturn(
            Optional.of(
                new ExportJob(
                    jobId,
                    PHARM,
                    "H1",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    "READY",
                    "k",
                    1,
                    admin.subject(),
                    NOW,
                    NOW,
                    null,
                    NOW)));
    assertThat(service.pollExportJob(admin, jobId).get("status")).isEqualTo("READY");
  }
}
